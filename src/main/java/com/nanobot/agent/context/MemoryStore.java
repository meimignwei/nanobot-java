package com.nanobot.agent.context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure file I/O for memory files: MEMORY.md, history.jsonl, SOUL.md, USER.md.
 * Mirrors Python MemoryStore class.
 */
public class MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final Set<String> INTERNAL_HISTORY_SESSION_KEYS = Set.of("heartbeat");
    private static final Set<String> INTERNAL_HISTORY_SESSION_PREFIXES = Set.of("cron:", "dream:");

    // strip_think patterns (simplified subset)
    private static final Pattern THINK_BLOCK = Pattern.compile("<think>[\\s\\S]*?</think>");
    private static final Pattern THOUGHT_BLOCK = Pattern.compile("<thought>[\\s\\S]*?</thought>");
    private static final Pattern UNCLOSED_THINK = Pattern.compile("^\\s*<think>[\\s\\S]*$");
    private static final Pattern UNCLOSED_THOUGHT = Pattern.compile("^\\s*<thought>[\\s\\S]*$");
    private static final Pattern ORPHAN_CLOSE_START = Pattern.compile("^\\s*</think>\\s*|^\\s*</thought>\\s*");
    private static final Pattern ORPHAN_CLOSE_END = Pattern.compile("\\s*</think>\\s*$|\\s*</thought>\\s*$");
    private static final Pattern CHANNEL_MARKER = Pattern.compile("^\\s*<\\|?channel\\|?>\\s*");

    private final Path workspace;
    private final Path memoryDir;
    private final Path memoryFile;
    private final Path historyFile;
    private final Path soulFile;
    private final Path userFile;
    private final Path cursorFile;
    private final Path dreamCursorFile;
    private final int maxHistoryEntries;
    private final ReentrantLock appendLock = new ReentrantLock();

    private boolean corruptionLogged;
    private boolean oversizeLogged;

    public MemoryStore(Path workspace) {
        this(workspace, 1000);
    }

    public MemoryStore(Path workspace, int maxHistoryEntries) {
        this.workspace = workspace;
        this.maxHistoryEntries = maxHistoryEntries;
        this.memoryDir = ensureDir(workspace.resolve("memory"));
        this.memoryFile = memoryDir.resolve("MEMORY.md");
        this.historyFile = memoryDir.resolve("history.jsonl");
        this.soulFile = workspace.resolve("SOUL.md");
        this.userFile = workspace.resolve("USER.md");
        this.cursorFile = memoryDir.resolve(".cursor");
        this.dreamCursorFile = memoryDir.resolve(".dream_cursor");
    }

    // -- MEMORY.md --

    public String readMemory() {
        return readFile(memoryFile);
    }

    public void writeMemory(String content) {
        writeFile(memoryFile, content);
    }

    // -- SOUL.md --

    public String readSoul() {
        return readFile(soulFile);
    }

    public void writeSoul(String content) {
        writeFile(soulFile, content);
    }

    // -- USER.md --

    public String readUser() {
        return readFile(userFile);
    }

    public void writeUser(String content) {
        writeFile(userFile, content);
    }

    // -- context injection --

    public String getMemoryContext() {
        var longTerm = readMemory();
        if (longTerm.isEmpty()) return "";
        return "## Long-term Memory\n" + longTerm;
    }

    // -- history.jsonl --

    public int appendHistory(String entry) {
        return appendHistory(entry, null, null);
    }

    public int appendHistory(String entry, Integer maxChars, String sessionKey) {
        int limit = maxChars != null ? maxChars : 64_000;
        var ts = LocalDateTime.now().format(TS_FMT);
        var raw = entry.stripTrailing();
        if (raw.length() > limit) {
            if (!oversizeLogged) {
                oversizeLogged = true;
                log.warn("history entry exceeds {} chars ({}); truncating", limit, raw.length());
            }
            raw = truncateText(raw, limit);
        }
        var content = stripThink(raw);

        appendLock.lock();
        try {
            var cursor = nextCursor();
            if (!raw.isEmpty() && content.isEmpty()) {
                log.debug("history entry {} stripped to empty; persisting empty content", cursor);
            }
            var record = new LinkedHashMap<String, Object>();
            record.put("cursor", cursor);
            record.put("timestamp", ts);
            record.put("content", content);
            if (sessionKey != null) {
                record.put("session_key", sessionKey);
            }
            try {
                var json = mapper.writeValueAsString(record);
                Files.writeString(historyFile, json + "\n", java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new RuntimeException("Failed to append to history file", e);
            }
            writeFile(cursorFile, String.valueOf(cursor));
            return cursor;
        } finally {
            appendLock.unlock();
        }
    }

    public List<Map<String, Object>> readUnprocessedHistory(int sinceCursor) {
        var result = new ArrayList<Map<String, Object>>();
        for (var entry : readEntries()) {
            var raw = entry.get("cursor");
            if (raw == null) continue;
            int cursor;
            if (raw instanceof Number n) {
                cursor = n.intValue();
            } else if (raw instanceof Boolean) {
                if (!corruptionLogged) {
                    corruptionLogged = true;
                    log.warn("history.jsonl contains a non-int cursor ({!r}); dropping it", raw);
                }
                continue;
            } else {
                continue;
            }
            if (cursor > sinceCursor) {
                result.add(entry);
            }
        }
        return result;
    }

    public List<Map<String, Object>> readRecentHistoryForPrompt(
            int sinceCursor, String sessionKey, boolean unified) {
        var entries = readUnprocessedHistory(sinceCursor);
        if (sessionKey == null) return entries;
        if (!unified) {
            return entries.stream()
                    .filter(e -> sessionKey.equals(e.get("session_key")))
                    .toList();
        }
        return entries.stream()
                .filter(e -> {
                    var es = (String) e.get("session_key");
                    return sessionKey.equals(es) || !isInternalHistorySession(es);
                })
                .toList();
    }

    public void compactHistory() {
        if (maxHistoryEntries <= 0) return;
        var entries = readEntries();
        if (entries.size() <= maxHistoryEntries) return;
        var kept = entries.subList(entries.size() - maxHistoryEntries, entries.size());
        writeEntries(kept);
    }

    // -- dream cursor --

    public int getLastDreamCursor() {
        try {
            if (Files.exists(dreamCursorFile)) {
                return Integer.parseInt(Files.readString(dreamCursorFile).strip());
            }
        } catch (IOException | NumberFormatException ignored) {}
        return 0;
    }

    public void setLastDreamCursor(int cursor) {
        writeFile(dreamCursorFile, String.valueOf(cursor));
    }

    // -- internal helpers --

    private int nextCursor() {
        if (Files.exists(cursorFile)) {
            try {
                return Integer.parseInt(Files.readString(cursorFile).strip()) + 1;
            } catch (IOException | NumberFormatException ignored) {}
        }
        var last = readLastEntry();
        if (last != null && last.get("cursor") instanceof Number n) {
            return n.intValue() + 1;
        }
        int max = 0;
        for (var entry : readEntries()) {
            if (entry.get("cursor") instanceof Number n) {
                max = Math.max(max, n.intValue());
            }
        }
        return max + 1;
    }

    private List<Map<String, Object>> readEntries() {
        var entries = new ArrayList<Map<String, Object>>();
        if (!Files.exists(historyFile)) return entries;
        try {
            for (var line : Files.readAllLines(historyFile)) {
                line = line.strip();
                if (line.isEmpty()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    var data = (Map<String, Object>) mapper.readValue(line, Map.class);
                    entries.add(data);
                } catch (JsonProcessingException ignored) {}
            }
        } catch (IOException e) {
            log.warn("Failed to read history file", e);
        }
        return entries;
    }

    private Map<String, Object> readLastEntry() {
        if (!Files.exists(historyFile)) return null;
        try {
            var bytes = Files.readAllBytes(historyFile);
            if (bytes.length == 0) return null;
            int readSize = Math.min(bytes.length, 4096);
            int start = bytes.length - readSize;
            var tail = new String(bytes, start, readSize);
            var lines = tail.lines().filter(l -> !l.isBlank()).toList();
            if (lines.isEmpty()) return null;
            @SuppressWarnings("unchecked")
            var result = (Map<String, Object>) mapper.readValue(lines.getLast(), Map.class);
            return result;
        } catch (IOException e) {
            return null;
        }
    }

    private void writeEntries(List<Map<String, Object>> entries) {
        var tmpPath = Path.of(historyFile + ".tmp");
        try {
            var sb = new StringBuilder();
            for (var entry : entries) {
                sb.append(mapper.writeValueAsString(entry)).append("\n");
            }
            Files.writeString(tmpPath, sb.toString());
            Files.move(tmpPath, historyFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try { Files.deleteIfExists(tmpPath); } catch (IOException ignored) {}
            throw new RuntimeException("Failed to write history entries", e);
        }
    }

    // -- static helpers --

    static boolean isInternalHistorySession(String sessionKey) {
        if (sessionKey == null) return false;
        if (INTERNAL_HISTORY_SESSION_KEYS.contains(sessionKey)) return true;
        for (var prefix : INTERNAL_HISTORY_SESSION_PREFIXES) {
            if (sessionKey.startsWith(prefix)) return true;
        }
        return false;
    }

    static String stripThink(String text) {
        text = THINK_BLOCK.matcher(text).replaceAll("");
        text = UNCLOSED_THINK.matcher(text).replaceAll("");
        text = THOUGHT_BLOCK.matcher(text).replaceAll("");
        text = UNCLOSED_THOUGHT.matcher(text).replaceAll("");
        text = ORPHAN_CLOSE_START.matcher(text).replaceAll("");
        text = ORPHAN_CLOSE_END.matcher(text).replaceAll("");
        text = CHANNEL_MARKER.matcher(text).replaceAll("");
        return text.strip();
    }

    static String truncateText(String text, int maxChars) {
        if (maxChars <= 0 || text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n... (truncated)";
    }

    static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            return "";
        }
    }

    static void writeFile(Path path, String content) {
        try {
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + path, e);
        }
    }

    static Path ensureDir(Path path) {
        try {
            return Files.createDirectories(path);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create directory: " + path, e);
        }
    }
}
