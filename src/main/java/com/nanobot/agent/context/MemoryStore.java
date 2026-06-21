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
import com.nanobot.utils.GitStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 记忆文件纯 I/O：MEMORY.md、history.jsonl、SOUL.md、USER.md 的读写。
 * 对应 Python MemoryStore 类（agent/memory.py）。
 *
 * <p>职责：长期记忆读写、历史 JSONL 追加/游标/截断、新近历史读取供 prompt 注入、
 * think/thought 标签清洗、文件数量控制。</p>
 */
public class MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 内部历史 session key（心跳等），不注入用户 prompt */
    private static final Set<String> INTERNAL_HISTORY_SESSION_KEYS = Set.of("heartbeat");
    /** 内部历史 session 前缀（cron:、dream:） */
    private static final Set<String> INTERNAL_HISTORY_SESSION_PREFIXES = Set.of("cron:", "dream:");

    // think/thought 标签清洗正则，对应 Python _strip_think()
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
    /** history.jsonl 自增游标文件 */
    private final Path cursorFile;
    /** dream 处理游标文件 */
    private final Path dreamCursorFile;
    private final int maxHistoryEntries;
    /** history 追加锁，保证游标和写入的原子性 */
    private final ReentrantLock appendLock = new ReentrantLock();

    /** Git 版本控制存储——对标 Python self._git = GitStore(...) */
    private final GitStore gitStore;

    private boolean corruptionLogged;
    private boolean oversizeLogged;

    public MemoryStore(Path workspace) {
        this(workspace, 1000);
    }

    /** 被 git 追踪的文件列表（workspace 相对路径）。
     *  对标 Python GitStore tracked_files。 */
    private static final List<String> TRACKED_FILES = List.of(
            "memory/MEMORY.md", "memory/history.jsonl", "SOUL.md", "USER.md");

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
        this.gitStore = new GitStore(workspace, TRACKED_FILES);
    }

    /** 获取 GitStore 实例，供外部（Dream、memory 命令等）使用。
     *  对标 Python MemoryStore.git property。 */
    public GitStore git() { return gitStore; }

    // -- MEMORY.md --
    // 对应 Python MemoryStore.read_memory() / write_memory()

    /** 读取长期记忆文件 */
    public String readMemory() {
        return readFile(memoryFile);
    }

    /** 写入长期记忆文件 */
    public void writeMemory(String content) {
        writeFile(memoryFile, content);
    }

    // -- SOUL.md --
    // 对应 Python MemoryStore.read_soul() / write_soul()

    /** 读取 SOUL.md */
    public String readSoul() {
        return readFile(soulFile);
    }

    /** 写入 SOUL.md */
    public void writeSoul(String content) {
        writeFile(soulFile, content);
    }

    // -- USER.md --
    // 对应 Python MemoryStore.read_user() / write_user()

    /** 读取 USER.md */
    public String readUser() {
        return readFile(userFile);
    }

    /** 写入 USER.md */
    public void writeUser(String content) {
        writeFile(userFile, content);
    }

    // -- context injection --
    // 对应 Python MemoryStore.get_memory_context()

    /** 获取记忆上下文，用于注入系统提示词。
     *  对应 Python MemoryStore.get_memory_context()。 */
    public String getMemoryContext() {
        var longTerm = readMemory();
        if (longTerm.isEmpty()) return "";
        return "## Long-term Memory\n" + longTerm;
    }

    // -- history.jsonl --
    // 对应 Python MemoryStore.append_history() / read_unprocessed_history()

    /** 追加历史条目（无字符限制、无 session key） */
    public int appendHistory(String entry) {
        return appendHistory(entry, null, null);
    }

    /**
     * 追加历史条目到 history.jsonl，自动分配游标、清洗 think 标签。
     * 对应 Python MemoryStore.append_history()。
     */
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

    /** 读取指定游标之后的未处理历史记录。
     *  对应 Python MemoryStore.read_unprocessed_history()。 */
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

    /** 读取最近历史供 prompt 注入，按 unified 标志过滤内部 session。
     *  对应 Python MemoryStore.read_recent_history_for_prompt()。 */
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

    /** 压缩 history.jsonl，仅保留最近 maxHistoryEntries 条记录。
     *  对应 Python MemoryStore.compact_history()。 */
    public void compactHistory() {
        if (maxHistoryEntries <= 0) return;
        var entries = readEntries();
        if (entries.size() <= maxHistoryEntries) return;
        var kept = entries.subList(entries.size() - maxHistoryEntries, entries.size());
        writeEntries(kept);
    }

    // -- dream cursor --
    // 对应 Python MemoryStore.get_last_dream_cursor() / set_last_dream_cursor()

    /** 获取最后处理的 dream 游标 */
    public int getLastDreamCursor() {
        try {
            if (Files.exists(dreamCursorFile)) {
                return Integer.parseInt(Files.readString(dreamCursorFile).strip());
            }
        } catch (IOException | NumberFormatException ignored) {}
        return 0;
    }

    /** 设置最后处理的 dream 游标 */
    public void setLastDreamCursor(int cursor) {
        writeFile(dreamCursorFile, String.valueOf(cursor));
    }

    // -- internal helpers --
    // 对应 Python MemoryStore._next_cursor()

    /** 计算下一条历史记录的游标值。
     *  对应 Python MemoryStore._next_cursor()。 */
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

    /** 读取 history.jsonl 全部条目 */
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

    /** 读取 history.jsonl 最后一条记录（只读尾部 4KB） */
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

    /** 原子写入条目列表到 history.jsonl */
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
    // 对应 Python MemoryStore._is_internal_history_session()

    /** 判断是否为内部历史 session（心跳、cron、dream 等不暴露给用户的会话）。
     *  对应 Python MemoryStore._is_internal_history_session()。 */
    static boolean isInternalHistorySession(String sessionKey) {
        if (sessionKey == null) return false;
        if (INTERNAL_HISTORY_SESSION_KEYS.contains(sessionKey)) return true;
        for (var prefix : INTERNAL_HISTORY_SESSION_PREFIXES) {
            if (sessionKey.startsWith(prefix)) return true;
        }
        return false;
    }

    /** 清洗文本中的 think/thought 标签及 channel 标记。
     *  对应 Python MemoryStore._strip_think()。 */
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

    /** 截断文本到指定字符数。
     *  对应 Python MemoryStore._truncate_text()。 */
    static String truncateText(String text, int maxChars) {
        if (maxChars <= 0 || text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n... (truncated)";
    }

    /** 读取文件内容，不存在返回空字符串 */
    static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            return "";
        }
    }

    /** 写入文件内容 */
    static void writeFile(Path path, String content) {
        try {
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + path, e);
        }
    }

    /** 确保目录存在 */
    static Path ensureDir(Path path) {
        try {
            return Files.createDirectories(path);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create directory: " + path, e);
        }
    }
}
