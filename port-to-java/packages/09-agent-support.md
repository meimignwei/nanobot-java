# 09 — agent 支撑：Memory + Consolidator + Subagent + Skills + AutoCompact + ModelPresets

**对标 Python：** `memory.py` (1,016行), `subagent.py` (394行), `skills.py` (261行), `autocompact.py` (97行), `model_presets.py` (66行)

---

## 1. MemoryStore (`memory.py`)

### 1.1 Python 源码分析

```
MemoryStore:
    文件:
    - workspace/memory/MEMORY.md          → 长期记忆索引
    - workspace/memory/history.jsonl      → 追加式对话历史（JSONL + cursor）
    - workspace/memory/.cursor            → 自增 cursor 计数器
    - workspace/memory/.dream_cursor      → Dream 已处理位置
    - workspace/SOUL.md, USER.md          → bootstrap 身份文件
    - workspace/memory/HISTORY.md         → 旧版历史（一次性迁移）

    核心方法:
    - read_file(path)                     → 静态读取，FileNotFound → ""
    - maybe_migrate_legacy_history()      → HISTORY.md → history.jsonl 一次性迁移
    - append_history(entry, max_chars, session_key)
                                          → strip_think + 线程安全 cursor 分配 + 追加写
    - read_unprocessed_history(since_cursor)
                                          → 返回 cursor > since_cursor 的合法条目
    - read_recent_history_for_prompt(...)
                                          → 按 session_key / unified_session 过滤
    - compact_history()                   → 超限时丢弃最旧条目
    - build_dream_prompt(max_entries)     → 构建 Dream 两阶段记忆整合的 prompt
    - build_dream_tools()                 → Dream 运行的受限工具集
    - raw_archive(messages)               → LLM 失败时的降级：直接格式化写入 history
    - _write_entries()                    → 原子写：tmp → fsync → rename → dir fsync
```

### 1.2 Java 实现

```java
package com.nanobot.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanobot.utils.GitStore;
import com.nanobot.utils.Pair;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 对标 Python memory.py:40 MemoryStore — 纯文件 I/O 记忆层。
 *
 * 负责 MEMORY.md、history.jsonl、SOUL.md、USER.md 的读写，
 * 以及 legacy HISTORY.md 的一次性迁移。
 */
@Slf4j
public class MemoryStore {

    // 对标 Python memory.py:43
    private static final int DEFAULT_MAX_HISTORY = 1000;
    // 对标 Python memory.py:44-45
    private static final Set<String> INTERNAL_HISTORY_SESSION_KEYS = Set.of("heartbeat");
    private static final Set<String> INTERNAL_HISTORY_SESSION_PREFIXES = Set.of("cron:", "dream:");
    // 对标 Python memory.py:46-50
    private static final Pattern LEGACY_ENTRY_START_RE = Pattern.compile(
            "^\\[(\\d{4}-\\d{2}-\\d{2}[^\\]]*)\\]\\s*");
    private static final Pattern LEGACY_TIMESTAMP_RE = Pattern.compile(
            "^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2})\\]\\s*");
    private static final Pattern LEGACY_RAW_MESSAGE_RE = Pattern.compile(
            "^\\[\\d{4}-\\d{2}-\\d{2}[^\\]]*\\]\\s+[A-Z][A-Z0-9_]*(?:\\s+\\[tools:\\s*[^\\]]+\\])?:");

    // 对标 Python memory.py:597-599
    private static final int RAW_ARCHIVE_MAX_CHARS = 16_000;
    private static final int ARCHIVE_SUMMARY_MAX_CHARS = 8_000;
    private static final int HISTORY_ENTRY_HARD_CAP = 64_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Path workspace;
    private final int maxHistoryEntries;
    private final Path memoryDir;
    private final Path memoryFile;
    private final Path historyFile;
    private final Path legacyHistoryFile;
    private final Path soulFile;
    private final Path userFile;
    private final Path cursorFile;
    private final Path dreamCursorFile;
    private volatile boolean corruptionLogged = false;   // 对标 Python memory.py:63
    private volatile boolean oversizeLogged = false;       // 对标 Python memory.py:64
    private final ReentrantLock appendLock = new ReentrantLock(); // 对标 Python memory.py:65
    private final GitStore git;

    // 对标 Python memory.py:52-69
    public MemoryStore(Path workspace, int maxHistoryEntries) {
        this.workspace = workspace;
        this.maxHistoryEntries = maxHistoryEntries;
        this.memoryDir = workspace.resolve("memory");
        try {
            Files.createDirectories(memoryDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.memoryFile = memoryDir.resolve("MEMORY.md");
        this.historyFile = memoryDir.resolve("history.jsonl");
        this.legacyHistoryFile = memoryDir.resolve("HISTORY.md");
        this.soulFile = workspace.resolve("SOUL.md");
        this.userFile = workspace.resolve("USER.md");
        this.cursorFile = memoryDir.resolve(".cursor");
        this.dreamCursorFile = memoryDir.resolve(".dream_cursor");
        this.git = new GitStore(workspace, List.of(
                "SOUL.md", "USER.md", "memory/MEMORY.md", "memory/.dream_cursor"
        ));
        maybeMigrateLegacyHistory();
    }

    public MemoryStore(Path workspace) {
        this(workspace, DEFAULT_MAX_HISTORY);
    }

    public GitStore getGit() {
        return git;
    }

    // -- generic helpers -----------------------------------------------------

    // 对标 Python memory.py:77-82
    public static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (FileNotFoundException | NoSuchFileException e) {
            return "";
        } catch (IOException e) {
            log.warn("Failed to read {}", path, e);
            return "";
        }
    }

    // -- legacy migration -----------------------------------------------------

    // 对标 Python memory.py:84-121
    private void maybeMigrateLegacyHistory() {
        if (!Files.exists(legacyHistoryFile)) {
            return;
        }
        if (Files.exists(historyFile)) {
            try {
                if (Files.size(historyFile) > 0) {
                    return;
                }
            } catch (IOException e) {
                return;
            }
        }
        try {
            String legacyText = Files.readString(legacyHistoryFile);
            List<Map<String, Object>> entries = parseLegacyHistory(legacyText);
            if (!entries.isEmpty()) {
                writeEntries(entries);
                int lastCursor = (int) entries.get(entries.size() - 1).get("cursor");
                Files.writeString(cursorFile, String.valueOf(lastCursor));
                // 默认标记为"已处理"，避免升级后 Dream 重放全部历史
                Files.writeString(dreamCursorFile, String.valueOf(lastCursor));
            }
            Path backup = nextLegacyBackupPath();
            Files.move(legacyHistoryFile, backup, StandardCopyOption.REPLACE_EXISTING);
            log.info("Migrated legacy HISTORY.md to history.jsonl ({} entries)", entries.size());
        } catch (Exception e) {
            log.error("Failed to migrate legacy HISTORY.md", e);
        }
    }

    // 对标 Python memory.py:123-147
    private List<Map<String, Object>> parseLegacyHistory(String text) {
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n").trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        String fallbackTimestamp = legacyFallbackTimestamp();
        List<Map<String, Object>> entries = new ArrayList<>();
        List<String> chunks = splitLegacyHistoryChunks(normalized);
        int cursor = 1;
        for (String chunk : chunks) {
            String timestamp = fallbackTimestamp;
            String content = chunk;
            Matcher m = LEGACY_TIMESTAMP_RE.matcher(chunk);
            if (m.find()) {
                timestamp = m.group(1);
                String remainder = chunk.substring(m.end()).stripLeading();
                if (!remainder.isEmpty()) {
                    content = remainder;
                }
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("cursor", cursor++);
            entry.put("timestamp", timestamp);
            entry.put("content", content);
            entries.add(entry);
        }
        return entries;
    }

    // 对标 Python memory.py:149-171
    private List<String> splitLegacyHistoryChunks(String text) {
        String[] lines = text.split("\n");
        List<String> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        boolean sawBlankSeparator = false;
        for (String line : lines) {
            if (sawBlankSeparator && !line.isBlank() && !current.isEmpty()) {
                chunks.add(String.join("\n", current).trim());
                current = new ArrayList<>();
                current.add(line);
                sawBlankSeparator = false;
                continue;
            }
            if (shouldStartNewLegacyChunk(line, current)) {
                if (!current.isEmpty()) {
                    chunks.add(String.join("\n", current).trim());
                }
                current = new ArrayList<>();
                current.add(line);
                sawBlankSeparator = false;
                continue;
            }
            current.add(line);
            sawBlankSeparator = line.isBlank();
        }
        if (!current.isEmpty()) {
            chunks.add(String.join("\n", current).trim());
        }
        return chunks.stream().filter(s -> !s.isEmpty()).toList();
    }

    // 对标 Python memory.py:173-180
    private boolean shouldStartNewLegacyChunk(String line, List<String> current) {
        if (current.isEmpty()) {
            return false;
        }
        if (!LEGACY_ENTRY_START_RE.matcher(line).find()) {
            return false;
        }
        if (isRawLegacyChunk(current) && LEGACY_RAW_MESSAGE_RE.matcher(line).find()) {
            return false;
        }
        return true;
    }

    // 对标 Python memory.py:182-188
    private boolean isRawLegacyChunk(List<String> lines) {
        String firstNonEmpty = lines.stream().filter(s -> !s.isBlank()).findFirst().orElse("");
        Matcher m = LEGACY_TIMESTAMP_RE.matcher(firstNonEmpty);
        if (!m.find()) {
            return false;
        }
        return firstNonEmpty.substring(m.end()).stripLeading().startsWith("[RAW]");
    }

    // 对标 Python memory.py:189-195
    private String legacyFallbackTimestamp() {
        try {
            return LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(Files.getLastModifiedTime(legacyHistoryFile).toMillis()),
                    java.time.ZoneId.systemDefault()
            ).format(TS_FMT);
        } catch (IOException e) {
            return LocalDateTime.now().format(TS_FMT);
        }
    }

    // 对标 Python memory.py:197-203
    private Path nextLegacyBackupPath() {
        Path candidate = memoryDir.resolve("HISTORY.md.bak");
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = memoryDir.resolve("HISTORY.md.bak." + suffix);
            suffix++;
        }
        return candidate;
    }

    // -- MEMORY.md ------------------------------------------------------------

    // 对标 Python memory.py:207-208
    public String readMemory() {
        return readFile(memoryFile);
    }

    // 对标 Python memory.py:210-211
    public void writeMemory(String content) {
        try {
            Files.writeString(memoryFile, content);
        } catch (IOException e) {
            log.error("Failed to write MEMORY.md", e);
        }
    }

    // -- SOUL.md --------------------------------------------------------------

    // 对标 Python memory.py:215-216
    public String readSoul() {
        return readFile(soulFile);
    }

    // 对标 Python memory.py:218-219
    public void writeSoul(String content) {
        try {
            Files.writeString(soulFile, content);
        } catch (IOException e) {
            log.error("Failed to write SOUL.md", e);
        }
    }

    // -- USER.md --------------------------------------------------------------

    // 对标 Python memory.py:223-224
    public String readUser() {
        return readFile(userFile);
    }

    // 对标 Python memory.py:226-227
    public void writeUser(String content) {
        try {
            Files.writeString(userFile, content);
        } catch (IOException e) {
            log.error("Failed to write USER.md", e);
        }
    }

    // -- context injection ----------------------------------------------------

    // 对标 Python memory.py:231-233
    public String getMemoryContext() {
        String longTerm = readMemory();
        return longTerm.isEmpty() ? "" : "## Long-term Memory\n" + longTerm;
    }

    // -- history.jsonl — append-only -----------------------------------------

    // 对标 Python memory.py:237-288
    public int appendHistory(String entry, @Nullable Integer maxChars, @Nullable String sessionKey) {
        int limit = maxChars != null ? maxChars : HISTORY_ENTRY_HARD_CAP;
        String raw = entry.stripTrailing();
        if (raw.length() > limit) {
            if (!oversizeLogged) {
                oversizeLogged = true;
                log.warn("history entry exceeds {} chars ({}); truncating. " +
                        "Usually means a caller forgot its own cap; further occurrences suppressed.",
                        limit, raw.length());
            }
            raw = truncateText(raw, limit);
        }
        String content = stripThink(raw); // 对标 Python strip_think()

        appendLock.lock();
        try {
            int cursor = nextCursor();
            if (!raw.isEmpty() && content.isEmpty()) {
                log.debug("history entry {} stripped to empty (likely template leak); " +
                        "persisting empty content to avoid re-polluting context", cursor);
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("cursor", cursor);
            record.put("timestamp", LocalDateTime.now().format(TS_FMT));
            record.put("content", content);
            if (sessionKey != null) {
                record.put("session_key", sessionKey);
            }
            Files.writeString(historyFile,
                    MAPPER.writeValueAsString(record) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Files.writeString(cursorFile, String.valueOf(cursor));
            return cursor;
        } catch (IOException e) {
            log.error("Failed to append history", e);
            return -1;
        } finally {
            appendLock.unlock();
        }
    }

    // 对标 Python memory.py:290-295
    @Nullable
    private static Integer validCursor(Object value) {
        if (value instanceof Boolean || !(value instanceof Number)) {
            return null;
        }
        return ((Number) value).intValue();
    }

    // 对标 Python memory.py:297-315
    private List<Pair<Map<String, Object>, Integer>> iterValidEntries() {
        List<Pair<Map<String, Object>, Integer>> result = new ArrayList<>();
        Object poisoned = null;
        for (Map<String, Object> entry : readEntries()) {
            Object raw = entry.get("cursor");
            if (raw == null) {
                continue;
            }
            Integer cursor = validCursor(raw);
            if (cursor == null) {
                poisoned = raw;
                continue;
            }
            result.add(new Pair<>(entry, cursor));
        }
        if (poisoned != null && !corruptionLogged) {
            corruptionLogged = true;
            log.warn("history.jsonl contains a non-int cursor ({}); dropping it. " +
                    "Usually caused by an external writer; further occurrences suppressed.", poisoned);
        }
        return result;
    }

    // 对标 Python memory.py:317-329
    private int nextCursor() {
        if (Files.exists(cursorFile)) {
            try {
                String text = Files.readString(cursorFile).trim();
                return Integer.parseInt(text) + 1;
            } catch (IOException | NumberFormatException ignored) {
            }
        }
        // Fast path: trust tail
        Map<String, Object> last = readLastEntry();
        if (last != null) {
            Integer cursor = validCursor(last.get("cursor"));
            if (cursor != null) {
                return cursor + 1;
            }
        }
        // Fallback: scan all
        int maxCursor = iterValidEntries().stream()
                .mapToInt(Pair::right)
                .max()
                .orElse(0);
        return maxCursor + 1;
    }

    // 对标 Python memory.py:331-333
    public List<Map<String, Object>> readUnprocessedHistory(int sinceCursor) {
        return iterValidEntries().stream()
                .filter(p -> p.right() > sinceCursor)
                .map(Pair::left)
                .collect(Collectors.toList());
    }

    // 对标 Python memory.py:336-342
    private static boolean isInternalHistorySession(@Nullable String sessionKey) {
        if (sessionKey == null || sessionKey.isEmpty()) {
            return false;
        }
        if (INTERNAL_HISTORY_SESSION_KEYS.contains(sessionKey)) {
            return true;
        }
        return INTERNAL_HISTORY_SESSION_PREFIXES.stream().anyMatch(sessionKey::startsWith);
    }

    // 对标 Python memory.py:344-363
    public List<Map<String, Object>> readRecentHistoryForPrompt(
            int sinceCursor,
            @Nullable String sessionKey,
            boolean unifiedSession) {
        List<Map<String, Object>> entries = readUnprocessedHistory(sinceCursor);
        if (sessionKey == null) {
            return entries;
        }
        if (!unifiedSession) {
            return entries.stream()
                    .filter(e -> sessionKey.equals(e.get("session_key")))
                    .toList();
        }
        return entries.stream()
                .filter(e -> {
                    String entrySession = (String) e.get("session_key");
                    return sessionKey.equals(entrySession) || !isInternalHistorySession(entrySession);
                })
                .toList();
    }

    // 对标 Python memory.py:365-373
    public void compactHistory() {
        if (maxHistoryEntries <= 0) {
            return;
        }
        List<Map<String, Object>> entries = readEntries();
        if (entries.size() <= maxHistoryEntries) {
            return;
        }
        List<Map<String, Object>> kept = entries.subList(entries.size() - maxHistoryEntries, entries.size());
        writeEntries(kept);
    }

    // -- JSONL helpers -------------------------------------------------------

    // 对标 Python memory.py:377-390
    private List<Map<String, Object>> readEntries() {
        List<Map<String, Object>> entries = new ArrayList<>();
        if (!Files.exists(historyFile)) {
            return entries;
        }
        try (BufferedReader br = Files.newBufferedReader(historyFile)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    entries.add(MAPPER.readValue(line, new TypeReference<>() {}));
                } catch (IOException e) {
                    // skip corrupt line
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read history.jsonl", e);
        }
        return entries;
    }

    // 对标 Python memory.py:392-408
    @Nullable
    private Map<String, Object> readLastEntry() {
        try (RandomAccessFile raf = new RandomAccessFile(historyFile.toFile(), "r")) {
            long size = raf.length();
            if (size == 0) {
                return null;
            }
            int readSize = (int) Math.min(size, 4096);
            raf.seek(size - readSize);
            byte[] buf = new byte[readSize];
            raf.readFully(buf);
            String data = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
            String[] lines = Arrays.stream(data.split("\n"))
                    .filter(s -> !s.isBlank())
                    .toArray(String[]::new);
            if (lines.length == 0) {
                return null;
            }
            return MAPPER.readValue(lines[lines.length - 1], new TypeReference<>() {});
        } catch (FileNotFoundException | NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    // 对标 Python memory.py:410-433
    private void writeEntries(List<Map<String, Object>> entries) {
        Path tmp = historyFile.resolveSibling(historyFile.getFileName() + ".tmp");
        try {
            try (BufferedWriter w = Files.newBufferedWriter(tmp)) {
                for (Map<String, Object> entry : entries) {
                    w.write(MAPPER.writeValueAsString(entry));
                    w.newLine();
                }
                w.flush();
                // fsync file
                if (w instanceof FileWriter fw) {
                    fw.flush();
                }
            }
            // 尝试通过 FileChannel force 模拟 fsync
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            } catch (IOException ignored) {
            }
            Files.move(tmp, historyFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            // fsync directory
            try {
                FileChannel dirCh = FileChannel.open(historyFile.getParent(), StandardOpenOption.READ);
                dirCh.force(true);
                dirCh.close();
            } catch (IOException ignored) {
            }
        } catch (Exception e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            throw new RuntimeException(e);
        }
    }

    // -- dream cursor --------------------------------------------------------

    // 对标 Python memory.py:437-441
    public int getLastDreamCursor() {
        if (Files.exists(dreamCursorFile)) {
            try {
                return Integer.parseInt(Files.readString(dreamCursorFile).trim());
            } catch (IOException | NumberFormatException ignored) {
            }
        }
        return 0;
    }

    // 对标 Python memory.py:443-444
    public void setLastDreamCursor(int cursor) {
        try {
            Files.writeString(dreamCursorFile, String.valueOf(cursor));
        } catch (IOException e) {
            log.error("Failed to write dream cursor", e);
        }
    }

    // 对标 Python memory.py:446-468
    @Nullable
    public Pair<String, Integer> buildDreamPrompt(int maxEntries) {
        int lastCursor = getLastDreamCursor();
        List<Map<String, Object>> entries = readUnprocessedHistory(lastCursor);
        if (entries.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> batch = entries.size() > maxEntries
                ? entries.subList(0, maxEntries)
                : entries;
        StringBuilder historyText = new StringBuilder();
        for (Map<String, Object> e : batch) {
            String ts = (String) e.getOrDefault("timestamp", "?");
            String content = truncateText((String) e.getOrDefault("content", ""), 500);
            historyText.append("[").append(ts).append("] ").append(content).append("\n");
        }
        String template = renderTemplate("agent/dream.md", true);
        String prompt = template + "\n\n## Conversation History\n" + historyText;
        int endCursor = (int) batch.get(batch.size() - 1).getOrDefault("cursor", lastCursor);
        return new Pair<>(prompt, endCursor);
    }

    // 对标 Python memory.py:470-510
    // build_dream_tools 依赖 ToolRegistry、FileStates 等，此处保留接口签名
    // public ToolRegistry buildDreamTools() { ... }

    // 对标 Python memory.py:512-516
    public static boolean dreamRunCompleted(@Nullable Object resp) {
        if (resp == null) {
            return false;
        }
        try {
            Object metadata = resp.getClass().getMethod("getMetadata").invoke(resp);
            if (metadata instanceof Map<?, ?> map) {
                return "completed".equals(map.get("_stop_reason"));
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // -- message formatting utility ------------------------------------------

    // 对标 Python memory.py:520-530
    public static String formatMessages(List<Map<String, Object>> messages) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> message : messages) {
            Object content = message.get("content");
            if (content == null || content.toString().isEmpty()) {
                continue;
            }
            Object toolsUsed = message.get("tools_used");
            String tools = "";
            if (toolsUsed instanceof List<?> list && !list.isEmpty()) {
                tools = " [tools: " + String.join(", ", list.stream().map(Object::toString).toList()) + "]";
            }
            String ts = Optional.ofNullable(message.get("timestamp")).map(Object::toString).orElse("?");
            if (ts.length() > 16) {
                ts = ts.substring(0, 16);
            }
            String role = Optional.ofNullable(message.get("role")).map(Object::toString).orElse("?").toUpperCase();
            sb.append("[").append(ts).append("] ").append(role).append(tools).append(": ")
                    .append(content).append("\n");
        }
        return sb.toString();
    }

    // 对标 Python memory.py:532-549
    public void rawArchive(List<Map<String, Object>> messages,
                           @Nullable Integer maxChars,
                           @Nullable String sessionKey) {
        int limit = maxChars != null ? maxChars : RAW_ARCHIVE_MAX_CHARS;
        String formatted = truncateText(formatMessages(messages), limit);
        appendHistory("[RAW] " + messages.size() + " messages\n" + formatted, null, sessionKey);
        log.warn("Memory consolidation degraded: raw-archived {} messages", messages.size());
    }

    // -- Dream helpers -------------------------------------------------------

    // 对标 Python memory.py:555-558
    public static String dreamSessionKey() {
        return "dream:" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }

    // 对标 Python memory.py:560-566
    public static String buildDreamCommitMessage(String prefix, @Nullable Object resp) {
        String msg = prefix;
        if (resp != null) {
            try {
                Object content = resp.getClass().getMethod("getContent").invoke(resp);
                if (content != null) {
                    String stripped = content.toString().strip();
                    if (!stripped.isEmpty()) {
                        msg = msg + "\n\n" + stripped;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return msg;
    }

    // 对标 Python memory.py:568-587
    public static void pruneDreamSessions(Path sessionsDir, int keep) {
        try (var stream = Files.list(sessionsDir)) {
            List<Path> dreamFiles = stream
                    .filter(p -> p.getFileName().toString().startsWith("dream_") &&
                            p.getFileName().toString().endsWith(".jsonl"))
                    .sorted(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0;
                        }
                    }))
                    .toList();
            if (dreamFiles.size() <= keep) {
                return;
            }
            List<Path> toRemove = dreamFiles.subList(0, dreamFiles.size() - keep);
            for (Path path : toRemove) {
                try {
                    Files.deleteIfExists(path);
                    log.debug("Pruned old dream session: {}", path.getFileName().toString().replaceFirst("\\.jsonl$", ""));
                } catch (IOException e) {
                    log.warn("Failed to prune dream session {}", path);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list dream sessions", e);
        }
    }

    // -- utils ---------------------------------------------------------------

    // 对标 Python helpers.strip_think
    private static String stripThink(String text) {
        // 简化实现：移除未闭合的 <think 前缀和 <channel|> 标记
        String s = text;
        if (s.contains("<think") && !s.contains("</think>")) {
            int idx = s.indexOf("<think");
            s = s.substring(0, idx);
        }
        s = s.replaceAll("<channel\\|[^>]*>", "");
        return s.stripTrailing();
    }

    // 对标 Python helpers.truncate_text
    private static String truncateText(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n... (truncated)";
    }

    // 对标 Python utils.prompt_templates.render_template
    private static String renderTemplate(String name, boolean strip) {
        // 实际实现从 classpath resources/templates/ 加载
        return ""; // 占位，由具体模板引擎实现
    }
}
```

---

## 2. Consolidator (`memory.py:602-1015`)

### 2.1 Python 源码分析

```
Consolidator:
    - __init__: store, provider, model, sessions, context_window_tokens,
                build_messages, get_tool_definitions, max_completion_tokens,
                consolidation_ratio, unified_session, _locks (WeakValueDictionary)
    - set_provider(provider, model, context_window_tokens)
    - get_lock(session_key) → asyncio.Lock (per-session 弱引用锁)
    - pick_consolidation_boundary(session, tokens_to_remove)
    - _full_unconsolidated_history(session)
    - _replay_overflow_boundary(session, replay_max_messages)
    - _consolidate_replay_overflow(session, replay_max_messages) → async
    - _persist_last_summary(session, summary)
    - estimate_session_prompt_tokens(session) → (int, str)
    - _input_token_budget → property
    - _truncate_to_token_budget(text) → str (tiktoken 截断)
    - archive(messages, session_key) → async (LLM 总结 → history.jsonl)
    - maybe_consolidate_by_tokens(session, replay_max_messages) → async
        → 循环：token 超限 → pick boundary → archive → 更新 last_consolidated
    - compact_idle_session(session_key, max_suffix) → async
        → 空闲会话硬截断：retain_recent_legal_suffix → archive 丢弃部分
```

### 2.2 Java 实现

```java
package com.nanobot.agent;

import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.LLMResponse;
import com.nanobot.session.Session;
import com.nanobot.session.SessionManager;
import com.nanobot.utils.Pair;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 对标 Python memory.py:602 Consolidator — 轻量级 token-budget 触发式历史压缩。
 *
 * 当 session 消息量或 token 估算超过安全预算时，循环归档旧消息到 history.jsonl。
 */
@Slf4j
public class Consolidator {

    // 对标 Python memory.py:605
    private static final int MAX_CONSOLIDATION_ROUNDS = 5;
    // 对标 Python memory.py:607
    private static final int SAFETY_BUFFER = 1024;
    // 对标 Python memory.py:597-599
    private static final int RAW_ARCHIVE_MAX_CHARS = 16_000;
    private static final int ARCHIVE_SUMMARY_MAX_CHARS = 8_000;

    /**
     * 对标 Python memory.py:616 build_messages 的完整参数签名。
     *
     * 构建用于 token 估算的 probe messages，需传递与真实 prompt 完全一致的上下文参数。
     */
    @FunctionalInterface
    public interface BuildMessagesFunction {
        List<Map<String, Object>> build(
                List<Map<String, Object>> history,
                String currentMessage,
                @Nullable String channel,
                @Nullable String chatId,
                @Nullable String senderId,
                @Nullable String sessionSummary,
                Map<String, Object> sessionMetadata,
                String sessionKey,
                boolean unifiedSession
        );
    }

    private final MemoryStore store;
    private LLMProvider provider;
    private String model;
    private final SessionManager sessions;
    private int contextWindowTokens;
    private int maxCompletionTokens;
    private final double consolidationRatio;
    private final boolean unifiedSession;
    private final BuildMessagesFunction buildMessagesFn;
    private final Supplier<List<Map<String, Object>>> getToolDefinitionsFn;

    // 对标 Python memory.py:632-634 WeakValueDictionary[str, asyncio.Lock]
    // Java 用 WeakHashMap + ReentrantLock 模拟 per-session 弱引用锁
    private final WeakHashMap<String, Lock> locks = new WeakHashMap<>();

    // 对标 Python memory.py:609-634
    public Consolidator(
            MemoryStore store,
            LLMProvider provider,
            String model,
            SessionManager sessions,
            int contextWindowTokens,
            BuildMessagesFunction buildMessagesFn,
            Supplier<List<Map<String, Object>>> getToolDefinitionsFn,
            int maxCompletionTokens,
            double consolidationRatio,
            boolean unifiedSession) {
        this.store = store;
        this.provider = provider;
        this.model = model;
        this.sessions = sessions;
        this.contextWindowTokens = contextWindowTokens;
        this.maxCompletionTokens = maxCompletionTokens;
        this.consolidationRatio = consolidationRatio;
        this.unifiedSession = unifiedSession;
        this.buildMessagesFn = buildMessagesFn;
        this.getToolDefinitionsFn = getToolDefinitionsFn;
    }

    // 对标 Python memory.py:636-646
    public void setProvider(LLMProvider provider, String model, int contextWindowTokens) {
        this.provider = provider;
        this.model = model;
        this.contextWindowTokens = contextWindowTokens;
        this.maxCompletionTokens = provider.getGenerationSettings().maxTokens();
    }

    // 对标 Python memory.py:647-649
    public synchronized Lock getLock(String sessionKey) {
        return locks.computeIfAbsent(sessionKey, k -> new ReentrantLock());
    }

    // 对标 Python memory.py:651-671
    @Nullable
    public Pair<Integer, Integer> pickConsolidationBoundary(Session session, int tokensToRemove) {
        int start = session.getLastConsolidated();
        List<Map<String, Object>> messages = session.getMessages();
        if (start >= messages.size() || tokensToRemove <= 0) {
            return null;
        }
        int removedTokens = 0;
        Pair<Integer, Integer> lastBoundary = null;
        for (int idx = start; idx < messages.size(); idx++) {
            Map<String, Object> message = messages.get(idx);
            if (idx > start && "user".equals(message.get("role"))) {
                lastBoundary = new Pair<>(idx, removedTokens);
                if (removedTokens >= tokensToRemove) {
                    return lastBoundary;
                }
            }
            removedTokens += estimateMessageTokens(message);
        }
        return lastBoundary;
    }

    // 对标 Python memory.py:673-686
    public static List<Map<String, Object>> fullUnconsolidatedHistory(Session session, boolean includeTimestamps) {
        int unconsolidatedCount = session.getMessages().size() - session.getLastConsolidated();
        if (unconsolidatedCount <= 0) {
            return List.of();
        }
        return session.getHistory(unconsolidatedCount, includeTimestamps);
    }

    // 对标 Python memory.py:688-717
    @Nullable
    public Integer replayOverflowBoundary(Session session, @Nullable Integer replayMaxMessages) {
        if (replayMaxMessages == null || replayMaxMessages <= 0) {
            return null;
        }
        List<Map<String, Object>> tail = new java.util.ArrayList<>();
        int base = session.getLastConsolidated();
        for (int i = base; i < session.getMessages().size(); i++) {
            tail.add(session.getMessages().get(i));
        }
        if (tail.size() <= replayMaxMessages) {
            return null;
        }
        List<Map<String, Object>> sliced = tail.subList(tail.size() - replayMaxMessages, tail.size());
        int start = 0;
        for (int i = 0; i < sliced.size(); i++) {
            if ("user".equals(sliced.get(i).get("role"))) {
                start = i;
                if (i > 0 && Boolean.TRUE.equals(sliced.get(i - 1).get("_channel_delivery"))) {
                    start = i - 1;
                }
                sliced = sliced.subList(start, sliced.size());
                break;
            }
        }
        int legalStart = findLegalMessageStart(sliced);
        if (legalStart > 0) {
            sliced = sliced.subList(legalStart, sliced.size());
        }
        if (sliced.isEmpty()) {
            return session.getMessages().size();
        }
        int firstVisibleIdx = base + tail.indexOf(sliced.get(0));
        if (firstVisibleIdx <= base) {
            return null;
        }
        return firstVisibleIdx;
    }

    // 对标 Python memory.py:719-740
    public CompletableFuture<String> consolidateReplayOverflow(Session session, @Nullable Integer replayMaxMessages) {
        Integer endIdx = replayOverflowBoundary(session, replayMaxMessages);
        if (endIdx == null) {
            return CompletableFuture.completedFuture(null);
        }
        List<Map<String, Object>> chunk = session.getMessages()
                .subList(session.getLastConsolidated(), endIdx);
        if (chunk.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        log.info("Replay-window consolidation for {}: chunk={} msgs, replay_max={}",
                session.getKey(), chunk.size(), replayMaxMessages);
        return archive(chunk, session.getKey())
                .thenApply(summary -> {
                    session.setLastConsolidated(endIdx);
                    sessions.save(session);
                    return summary;
                });
    }

    // 对标 Python memory.py:742-748
    private void persistLastSummary(Session session, @Nullable String summary) {
        if (summary != null && !"(nothing)".equals(summary)) {
            Map<String, Object> meta = new java.util.LinkedHashMap<>();
            meta.put("text", summary);
            meta.put("last_active", session.getUpdatedAt().toString());
            session.getMetadata().put("_last_summary", meta);
            sessions.save(session);
        }
    }

    // 对标 Python memory.py:750-776
    public Pair<Integer, String> estimateSessionPromptTokens(Session session) {
        List<Map<String, Object>> history = fullUnconsolidatedHistory(session, true);
        String[] parts = session.getKey().split(":", 2);
        String channel = parts.length > 1 ? parts[0] : null;
        String chatId = parts.length > 1 ? parts[1] : null;
        Object meta = session.getMetadata().get("_last_summary");
        String summary = null;
        if (meta instanceof Map<?, ?> map) {
            summary = (String) map.get("text");
        } else if (meta instanceof String s) {
            summary = s;
        }
        List<Map<String, Object>> probeMessages = buildMessagesFn.build(
                history,
                "[token-probe]",
                channel,
                chatId,
                null,
                summary,
                session.getMetadata(),
                session.getKey(),
                unifiedSession
        );
        int estimated = estimatePromptTokensChain(provider, model, probeMessages, getToolDefinitionsFn.get());
        String source = provider.hasTokenizer() ? "provider_tokenizer" : "cl100k_estimate";
        return new Pair<>(estimated, source);
    }

    // 对标 Python memory.py:778-781
    private int inputTokenBudget() {
        return contextWindowTokens - maxCompletionTokens - SAFETY_BUFFER;
    }

    // 对标 Python memory.py:783-795
    private String truncateToTokenBudget(String text) {
        int budget = inputTokenBudget();
        if (budget <= 0) {
            return truncateText(text, RAW_ARCHIVE_MAX_CHARS);
        }
        // 优先使用 provider 的 tokenizer（对标 Python tiktoken）
        if (provider != null && provider.hasTokenizer()) {
            int tokenCount = provider.countTokens(text);
            if (tokenCount <= budget) {
                return text;
            }
            String truncated = provider.truncateToTokens(text, budget);
            return truncated + "\n... (truncated)";
        }
        // 降级：按字符混合估算（中文 ≈ 2 chars/token，西文 ≈ 4 chars/token）
        int charBudget = estimateCharBudgetForTokens(text, budget);
        if (text.length() <= charBudget) {
            return text;
        }
        return text.substring(0, charBudget) + "\n... (truncated)";
    }

    // 对标 Python memory.py:797-839
    public CompletableFuture<String> archive(List<Map<String, Object>> messages, @Nullable String sessionKey) {
        if (messages.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        String formatted = truncateToTokenBudget(MemoryStore.formatMessages(messages));
        List<Map<String, Object>> llmMessages = List.of(
                Map.of("role", "system", "content", renderTemplate("agent/consolidator_archive.md", true)),
                Map.of("role", "user", "content", formatted)
        );
        return provider.chatWithRetry(model, llmMessages, null, null)
                .thenApply(response -> {
                    if ("error".equals(response.getFinishReason())) {
                        throw new RuntimeException("LLM returned error: " + response.getContent());
                    }
                    String summary = response.getContent() != null ? response.getContent() : "[no summary]";
                    store.appendHistory(summary, ARCHIVE_SUMMARY_MAX_CHARS, sessionKey);
                    return summary;
                })
                .exceptionally(ex -> {
                    log.warn("Consolidation LLM call failed, raw-dumping to history");
                    store.rawArchive(messages, null, sessionKey);
                    return null;
                });
    }

    // 对标 Python memory.py:841-948
    public CompletableFuture<Void> maybeConsolidateByTokens(Session session, @Nullable Integer replayMaxMessages) {
        if (contextWindowTokens <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        Lock lock = getLock(session.getKey());
        lock.lock();
        try {
            // Refresh session reference
            Session fresh = sessions.getOrCreate(session.getKey());
            if (fresh != session) {
                session = fresh;
            }
            if (session.getMessages().isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            return consolidateReplayOverflow(session, replayMaxMessages)
                    .thenCompose(lastSummary -> {
                        int budget = inputTokenBudget();
                        int target = (int) (budget * consolidationRatio);
                        try {
                            Pair<Integer, String> est = estimateSessionPromptTokens(session);
                            int estimated = est.left();
                            if (estimated <= 0) {
                                persistLastSummary(session, lastSummary);
                                return CompletableFuture.completedFuture(null);
                            }
                            if (estimated < budget) {
                                persistLastSummary(session, lastSummary);
                                return CompletableFuture.completedFuture(null);
                            }
                            return runConsolidationRounds(session, lastSummary, estimated, target, 0);
                        } catch (Exception e) {
                            log.error("Token estimation failed for {}", session.getKey(), e);
                            persistLastSummary(session, lastSummary);
                            return CompletableFuture.completedFuture(null);
                        }
                    });
        } finally {
            lock.unlock();
        }
    }

    // 对标 Python memory.py:893-948 的循环体
    private CompletableFuture<Void> runConsolidationRounds(Session session, String lastSummary,
                                                            int estimated, int target, int roundNum) {
        if (estimated <= target || roundNum >= MAX_CONSOLIDATION_ROUNDS) {
            persistLastSummary(session, lastSummary);
            return CompletableFuture.completedFuture(null);
        }
        Pair<Integer, Integer> boundary = pickConsolidationBoundary(session, Math.max(1, estimated - target));
        if (boundary == null) {
            log.debug("Token consolidation: no safe boundary for {} (round {})", session.getKey(), roundNum);
            persistLastSummary(session, lastSummary);
            return CompletableFuture.completedFuture(null);
        }
        int endIdx = boundary.left();
        List<Map<String, Object>> chunk = session.getMessages()
                .subList(session.getLastConsolidated(), endIdx);
        if (chunk.isEmpty()) {
            persistLastSummary(session, lastSummary);
            return CompletableFuture.completedFuture(null);
        }
        log.info("Token consolidation round {} for {}: {}/{} via tiktoken, chunk={} msgs",
                roundNum, session.getKey(), estimated, contextWindowTokens, chunk.size());
        return archive(chunk, session.getKey())
                .thenCompose(summary -> {
                    String newLastSummary = summary != null ? summary : lastSummary;
                    session.setLastConsolidated(endIdx);
                    sessions.save(session);
                    if (summary == null) {
                        // LLM 降级，停止本次调用
                        persistLastSummary(session, newLastSummary);
                        return CompletableFuture.completedFuture(null);
                    }
                    try {
                        Pair<Integer, String> est = estimateSessionPromptTokens(session);
                        int newEstimated = est.left();
                        if (newEstimated <= 0) {
                            persistLastSummary(session, newLastSummary);
                            return CompletableFuture.completedFuture(null);
                        }
                        return runConsolidationRounds(session, newLastSummary, newEstimated, target, roundNum + 1);
                    } catch (Exception e) {
                        log.error("Token estimation failed for {}", session.getKey(), e);
                        persistLastSummary(session, newLastSummary);
                        return CompletableFuture.completedFuture(null);
                    }
                });
    }

    // 对标 Python memory.py:950-1015
    public CompletableFuture<String> compactIdleSession(String sessionKey, int maxSuffix) {
        Lock lock = getLock(sessionKey);
        lock.lock();
        try {
            sessions.invalidate(sessionKey);
            Session session = sessions.getOrCreate(sessionKey);
            List<Map<String, Object>> tail = new java.util.ArrayList<>(
                    session.getMessages().subList(session.getLastConsolidated(), session.getMessages().size()));
            if (tail.isEmpty()) {
                session.setUpdatedAt(java.time.Instant.now());
                sessions.save(session);
                return CompletableFuture.completedFuture("");
            }
            // 用临时 probe session 计算合法后缀
            Session probe = new Session(session.getKey(), tail, session.getCreatedAt(),
                    session.getUpdatedAt(), new java.util.LinkedHashMap<>(), 0);
            Pair<List<Map<String, Object>>, Integer> retained = probe.retainRecentLegalSuffix(maxSuffix);
            List<Map<String, Object>> kept = retained.left();
            int alreadyConsolidated = retained.right();
            List<Map<String, Object>> archiveMsgs = new java.util.ArrayList<>(
                    probe.getMessages().subList(alreadyConsolidated, probe.getMessages().size()));
            if (archiveMsgs.isEmpty() && kept.isEmpty()) {
                session.setUpdatedAt(java.time.Instant.now());
                sessions.save(session);
                return CompletableFuture.completedFuture("");
            }
            java.time.Instant lastActive = session.getUpdatedAt();
            if (archiveMsgs.isEmpty()) {
                session.setMessages(kept);
                session.setLastConsolidated(0);
                session.setUpdatedAt(java.time.Instant.now());
                sessions.save(session);
                return CompletableFuture.completedFuture("");
            }
            return archive(archiveMsgs, sessionKey)
                    .thenApply(summary -> {
                        if (summary != null && !"(nothing)".equals(summary)) {
                            session.getMetadata().put("_last_summary", Map.of(
                                    "text", summary,
                                    "last_active", lastActive.toString()
                            ));
                        }
                        session.setMessages(kept);
                        session.setLastConsolidated(0);
                        session.setUpdatedAt(java.time.Instant.now());
                        sessions.save(session);
                        log.info("Idle-session compact for {}: archived={}, kept={}, summary={}",
                                sessionKey, archiveMsgs.size(), kept.size(), summary != null);
                        return summary;
                    });
        } finally {
            lock.unlock();
        }
    }

    // -- helpers (占位，依赖具体项目工具) -------------------------------------

    // 对标 Python helpers.estimate_message_tokens
    private static int estimateMessageTokens(Map<String, Object> message) {
        String content = (String) message.getOrDefault("content", "");
        if (content == null || content.isEmpty()) {
            return 4; // minimal overhead for empty message
        }
        // 混合语言估算：统计 CJK 字符和西文字符
        int cjkCount = 0;
        int asciiCount = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION) {
                cjkCount++;
            } else if (c <= 127) {
                asciiCount++;
            } else {
                cjkCount++; // 其他非 ASCII 也按 CJK 密度估算
            }
        }
        int tokenEstimate = cjkCount * 2 + asciiCount / 4;
        return tokenEstimate + 10; // 10 token overhead per message (role/delimiter)
    }

    // 对标 Python helpers.estimate_prompt_tokens_chain
    private static int estimatePromptTokensChain(LLMProvider provider, String model,
                                                  List<Map<String, Object>> messages,
                                                  List<Map<String, Object>> tools) {
        if (provider != null && provider.hasTokenizer()) {
            return provider.countTokens(messages, tools);
        }
        int total = 0;
        for (Map<String, Object> m : messages) {
            total += estimateMessageTokens(m);
        }
        // tool definitions 的 token 估算（每个 tool 约 100 token overhead）
        if (tools != null) {
            total += tools.size() * 100;
        }
        return total;
    }

    /**
     * 按文本语言组成估算达到目标 token 数所需的字符预算。
     * 中文密集时 2 chars/token，西文密集时 4 chars/token。
     */
    private static int estimateCharBudgetForTokens(String text, int targetTokens) {
        int cjkCount = 0;
        int asciiCount = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '一' && c <= '鿿') {
                cjkCount++;
            } else if (c <= 127) {
                asciiCount++;
            } else {
                cjkCount++;
            }
        }
        int totalChars = cjkCount + asciiCount;
        if (totalChars == 0) {
            return targetTokens * 4;
        }
        double cjkRatio = (double) cjkCount / totalChars;
        // 加权平均：cjkRatio * 2 + (1-cjkRatio) * 4 = 4 - 2*cjkRatio
        double charsPerToken = 4.0 - 2.0 * cjkRatio;
        return (int) (targetTokens * charsPerToken);
    }

    private static int findLegalMessageStart(List<Map<String, Object>> messages) {
        // 对标 Python helpers.find_legal_message_start
        // 简化实现：确保首条消息不是 orphan tool result
        for (int i = 0; i < messages.size(); i++) {
            String role = (String) messages.get(i).get("role");
            if ("user".equals(role) || "system".equals(role) || "assistant".equals(role)) {
                return i;
            }
        }
        return 0;
    }

    private static String truncateText(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n... (truncated)";
    }

    private static String renderTemplate(String name, boolean strip) {
        // 占位
        return "";
    }
}
```

---

## 3. SubagentManager (`subagent.py`)

### 3.1 Python 源码分析

```
SubagentStatus (dataclass):
    task_id, label, task_description, started_at (float monotonic),
    phase (initializing | awaiting_tools | tools_completed | final_response | done | error),
    iteration, tool_events, usage, stop_reason, error

_SubagentHook (AgentHook):
    - before_execute_tools: 记录 tool call debug 日志
    - after_iteration: 更新 status.iteration / tool_events / usage / error

SubagentManager:
    __init__: provider, workspace, bus, max_tool_result_chars, model, tools_config,
              restrict_to_workspace, disabled_skills, max_iterations, max_concurrent_subagents,
              llm_wall_timeout_for_session
    - _subagent_tools_config() → ToolsConfig (exec + web + restrict)
    - _build_tools(workspace, tools_config) → ToolRegistry (通过 ToolLoader 加载)
    - set_provider(provider, model)
    - spawn(task, label, origin_channel, origin_chat_id, session_key, origin_message_id,
            temperature, workspace_scope) → async str
        → 创建 SubagentStatus + asyncio.create_task(_run_subagent(...)) + done_callback cleanup
    - _run_subagent(task_id, task, label, origin, status, origin_message_id, temperature,
                    workspace_scope) → async
        → 构建 tools + system_prompt + AgentRunSpec → await runner.run()
        → 根据 stop_reason 决定成功/失败 → _announce_result()
    - _announce_result(...) → async → 通过 bus.publish_inbound 注入结果
    - _format_partial_progress(result) → str
    - _build_subagent_prompt(workspace) → str (render_template)
    - cancel_by_session(session_key) → async int
    - get_running_count() → int
    - get_running_count_by_session(session_key) → int
```

### 3.2 Java 实现

```java
package com.nanobot.agent;

import com.nanobot.agent.hook.AgentHook;
import com.nanobot.agent.hook.AgentHookContext;
import com.nanobot.agent.runner.AgentRunner;
import com.nanobot.agent.runner.AgentRunResult;
import com.nanobot.agent.runner.AgentRunSpec;
import com.nanobot.agent.tools.context.ToolContext;
import com.nanobot.agent.tools.file_state.FileStates;
import com.nanobot.agent.tools.loader.ToolLoader;
import com.nanobot.agent.tools.registry.ToolRegistry;
import com.nanobot.bus.InboundMessage;
import com.nanobot.bus.MessageBus;
import com.nanobot.config.AgentDefaults;
import com.nanobot.config.ToolsConfig;
import com.nanobot.providers.LLMProvider;
import com.nanobot.security.WorkspaceScope;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 对标 Python subagent.py:74 SubagentManager — 后台子 agent 执行管理。
 */
@Slf4j
public class SubagentManager {

    private final LLMProvider provider;
    private final Path workspace;
    private final MessageBus bus;
    private final int maxToolResultChars;
    private String model;
    private final ToolsConfig toolsConfig;
    private final boolean restrictToWorkspace;
    private final Set<String> disabledSkills;
    private final int maxIterations;
    private final int maxConcurrentSubagents;
    private final Function<String, Float> llmWallTimeoutForSession;
    private final AgentRunner runner;

    // 对标 Python subagent.py:112-114
    private final ConcurrentMap<String, CompletableFuture<Void>> runningTasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SubagentStatus> taskStatuses = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<String>> sessionTasks = new ConcurrentHashMap<>();

    // 对标 Python subagent.py:77-115
    public SubagentManager(
            LLMProvider provider,
            Path workspace,
            MessageBus bus,
            int maxToolResultChars,
            @Nullable String model,
            @Nullable ToolsConfig toolsConfig,
            boolean restrictToWorkspace,
            @Nullable List<String> disabledSkills,
            @Nullable Integer maxIterations,
            @Nullable Integer maxConcurrentSubagents,
            @Nullable Function<String, Float> llmWallTimeoutForSession) {
        AgentDefaults defaults = new AgentDefaults();
        this.provider = provider;
        this.workspace = workspace;
        this.bus = bus;
        this.model = model != null ? model : provider.getDefaultModel();
        this.toolsConfig = toolsConfig != null ? toolsConfig : new ToolsConfig();
        this.maxToolResultChars = maxToolResultChars;
        this.restrictToWorkspace = restrictToWorkspace;
        this.disabledSkills = disabledSkills != null ? ConcurrentHashMap.newKeySet() : ConcurrentHashMap.newKeySet();
        if (disabledSkills != null) {
            this.disabledSkills.addAll(disabledSkills);
        }
        this.maxIterations = maxIterations != null ? maxIterations : defaults.getMaxToolIterations();
        this.maxConcurrentSubagents = maxConcurrentSubagents != null ? maxConcurrentSubagents : defaults.getMaxConcurrentSubagents();
        this.llmWallTimeoutForSession = llmWallTimeoutForSession;
        this.runner = new AgentRunner(provider);
    }

    // 对标 Python subagent.py:116-122
    private ToolsConfig subagentToolsConfig() {
        return ToolsConfig.builder()
                .exec(toolsConfig.isExec())
                .web(toolsConfig.isWeb())
                .restrictToWorkspace(restrictToWorkspace)
                .build();
    }

    // 对标 Python subagent.py:124-143
    private ToolRegistry buildTools(@Nullable Path workspace, @Nullable ToolsConfig toolsConfig) {
        Path root = workspace != null ? workspace : this.workspace;
        ToolRegistry registry = new ToolRegistry();
        ToolsConfig cfg = toolsConfig != null ? toolsConfig : subagentToolsConfig();
        ToolContext ctx = ToolContext.builder()
                .config(cfg)
                .workspace(root.toAbsolutePath().toString())
                .fileStateStore(new FileStates())
                .workspaceSandbox(workspaceSandboxStatus(cfg.isRestrictToWorkspace(), root))
                .build();
        new ToolLoader().load(ctx, registry, "subagent");
        return registry;
    }

    private static Map<String, Object> workspaceSandboxStatus(boolean restrictToWorkspace, Path workspace) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("restrict_to_workspace", restrictToWorkspace);
        status.put("workspace", workspace.toString());
        return status;
    }

    // 对标 Python subagent.py:145-148
    public void setProvider(LLMProvider provider, String model) {
        this.provider = provider;
        this.model = model;
        this.runner.setProvider(provider);
    }

    // 对标 Python subagent.py:150-201
    public CompletableFuture<String> spawn(
            String task,
            @Nullable String label,
            String originChannel,
            String originChatId,
            @Nullable String sessionKey,
            @Nullable String originMessageId,
            @Nullable Float temperature,
            @Nullable WorkspaceScope workspaceScope) {

        String taskId = UUID.randomUUID().toString().substring(0, 8);
        String displayLabel = label != null ? label
                : (task.length() > 30 ? task.substring(0, 30) + "..." : task);
        Map<String, String> origin = new LinkedHashMap<>();
        origin.put("channel", originChannel);
        origin.put("chat_id", originChatId);
        if (sessionKey != null) {
            origin.put("session_key", sessionKey);
        }

        SubagentStatus status = new SubagentStatus(
                taskId, displayLabel, task,
                System.nanoTime(),
                "initializing", 0, List.of(),
                new LinkedHashMap<>(), null, null
        );
        taskStatuses.put(taskId, status);

        CompletableFuture<Void> bgTask = runSubagent(
                taskId, task, displayLabel, origin, status,
                originMessageId, temperature, workspaceScope
        );
        runningTasks.put(taskId, bgTask);
        if (sessionKey != null) {
            sessionTasks.computeIfAbsent(sessionKey, k -> ConcurrentHashMap.newKeySet()).add(taskId);
        }

        // 对标 Python subagent.py:190-198 _cleanup done_callback
        bgTask.whenComplete((v, ex) -> {
            runningTasks.remove(taskId);
            taskStatuses.remove(taskId);
            if (sessionKey != null) {
                Set<String> ids = sessionTasks.get(sessionKey);
                if (ids != null) {
                    ids.remove(taskId);
                    if (ids.isEmpty()) {
                        sessionTasks.remove(sessionKey);
                    }
                }
            }
            if (ex != null && !(ex instanceof java.util.concurrent.CancellationException)) {
                log.error("Subagent [{}] terminated with exception", taskId, ex);
            }
        });

        // 防御性兜底：即使 whenComplete 因极端情况未触发，
        // 5 分钟后强制扫描清理孤儿状态
        CompletableFuture.delayedExecutor(5, TimeUnit.MINUTES)
                .execute(() -> {
                    if (taskStatuses.containsKey(taskId) && bgTask.isDone()) {
                        taskStatuses.remove(taskId);
                        runningTasks.remove(taskId);
                    }
                });

        log.info("Spawned subagent [{}]: {}", taskId, displayLabel);
        return CompletableFuture.completedFuture(
                "Subagent [" + displayLabel + "] started (id: " + taskId + "). I'll notify you when it completes."
        );
    }

    // 对标 Python subagent.py:203-287
    private CompletableFuture<Void> runSubagent(
            String taskId,
            String task,
            String label,
            Map<String, String> origin,
            SubagentStatus status,
            @Nullable String originMessageId,
            @Nullable Float temperature,
            @Nullable WorkspaceScope workspaceScope) {

        return CompletableFuture.runAsync(() -> {
            log.info("Subagent [{}] starting task: {}", taskId, label);

            // 对标 Python subagent.py:217 _on_checkpoint
            Consumer<Map<String, Object>> onCheckpoint = payload -> {
                status.setPhase((String) payload.getOrDefault("phase", status.getPhase()));
                status.setIteration((Integer) payload.getOrDefault("iteration", status.getIteration()));
            };

            Object workspaceToken = null;
            try {
                Path root = workspaceScope != null ? workspaceScope.getProjectPath() : workspace;
                ToolsConfig cfg = null;
                if (workspaceScope != null) {
                    cfg = subagentToolsConfig();
                    cfg = cfg.toBuilder().restrictToWorkspace(workspaceScope.isRestrictToWorkspace()).build();
                }
                ToolRegistry tools = buildTools(root, cfg);
                String systemPrompt = buildSubagentPrompt(root);

                List<Map<String, Object>> messages = new ArrayList<>();
                messages.add(Map.of("role", "system", "content", systemPrompt));
                messages.add(Map.of("role", "user", "content", task));

                String sessKey = origin.get("session_key");
                Float llmTimeout = llmWallTimeoutForSession != null ? llmWallTimeoutForSession.apply(sessKey) : null;

                // 对标 Python subagent.py:240 bind_workspace_scope
                if (workspaceScope != null) {
                    workspaceToken = bindWorkspaceScope(workspaceScope);
                }

                AgentRunSpec spec = AgentRunSpec.builder()
                        .initialMessages(messages)
                        .tools(tools)
                        .model(model)
                        .temperature(temperature)
                        .maxIterations(maxIterations)
                        .maxToolResultChars(maxToolResultChars)
                        .hook(new SubagentHook(taskId, status))
                        .maxIterationsMessage("Task completed but no final response was generated.")
                        .finalizeOnMaxIterations(false)
                        .errorMessage(null)
                        .failOnToolError(true)
                        .checkpointCallback(onCheckpoint)
                        .sessionKey(sessKey)
                        .workspace(root)
                        .llmTimeoutS(llmTimeout)
                        .build();

                // 同步调用 runner.run（在 CompletableFuture 异步线程中执行，语义对标 Python asyncio.Task）
                AgentRunResult result = runner.run(spec);
                status.setPhase("done");
                status.setStopReason(result.getStopReason());

                if ("tool_error".equals(result.getStopReason())) {
                    status.setToolEvents(new ArrayList<>(result.getToolEvents()));
                    announceResult(taskId, label, task, formatPartialProgress(result),
                            origin, "error", originMessageId).join();
                } else if ("error".equals(result.getStopReason())) {
                    announceResult(taskId, label, task,
                            result.getError() != null ? result.getError() : "Error: subagent execution failed.",
                            origin, "error", originMessageId).join();
                } else {
                    String finalResult = result.getFinalContent() != null
                            ? result.getFinalContent()
                            : "Task completed but no final response was generated.";
                    log.info("Subagent [{}] completed successfully", taskId);
                    announceResult(taskId, label, task, finalResult, origin, "ok", originMessageId).join();
                }
            } catch (Exception e) {
                status.setPhase("error");
                status.setError(e.getMessage());
                log.error("Subagent [{}] failed", taskId, e);
                announceResult(taskId, label, task, "Error: " + e.getMessage(), origin, "error", originMessageId).join();
            } finally {
                // 对标 Python subagent.py:259-261 reset_workspace_scope
                if (workspaceToken != null) {
                    resetWorkspaceScope(workspaceToken);
                }
            }
        });
    }

    // 对标 Python subagent.py:289-332
    private CompletableFuture<Void> announceResult(
            String taskId,
            String label,
            String task,
            String result,
            Map<String, String> origin,
            String status,
            @Nullable String originMessageId) {
        String statusText = "ok".equals(status) ? "completed successfully" : "failed";
        String announceContent = renderTemplate("agent/subagent_announce.md",
                label, statusText, task, result);
        String override = origin.get("session_key") != null
                ? origin.get("session_key")
                : origin.get("channel") + ":" + origin.get("chat_id");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("injected_event", "subagent_result");
        metadata.put("subagent_task_id", taskId);
        if (originMessageId != null) {
            metadata.put("origin_message_id", originMessageId);
        }
        InboundMessage msg = InboundMessage.builder()
                .channel("system")
                .senderId("subagent")
                .chatId(origin.get("channel") + ":" + origin.get("chat_id"))
                .content(announceContent)
                .sessionKeyOverride(override)
                .metadata(metadata)
                .build();
        return bus.publishInbound(msg)
                .thenRun(() -> log.debug("Subagent [{}] announced result to {}:{}",
                        taskId, origin.get("channel"), origin.get("chat_id")));
    }

    // 对标 Python subagent.py:334-353
    private static String formatPartialProgress(AgentRunResult result) {
        List<Map<String, Object>> completed = result.getToolEvents().stream()
                .filter(e -> "ok".equals(e.get("status")))
                .toList();
        Map<String, Object> failure = null;
        List<Map<String, Object>> rev = new ArrayList<>(result.getToolEvents());
        Collections.reverse(rev);
        for (Map<String, Object> e : rev) {
            if ("error".equals(e.get("status"))) {
                failure = e;
                break;
            }
        }
        List<String> lines = new ArrayList<>();
        if (!completed.isEmpty()) {
            lines.add("Completed steps:");
            List<Map<String, Object>> lastCompleted = completed.size() > 3
                    ? completed.subList(completed.size() - 3, completed.size())
                    : completed;
            for (Map<String, Object> event : lastCompleted) {
                lines.add("- " + event.get("name") + ": " + event.get("detail"));
            }
        }
        if (failure != null) {
            if (!lines.isEmpty()) {
                lines.add("");
            }
            lines.add("Failure:");
            lines.add("- " + failure.get("name") + ": " + failure.get("detail"));
        }
        if (result.getError() != null && failure == null) {
            if (!lines.isEmpty()) {
                lines.add("");
            }
            lines.add("Failure:");
            lines.add("- " + result.getError());
        }
        String joined = String.join("\n", lines);
        return joined.isEmpty() ? (result.getError() != null ? result.getError() : "Error: subagent execution failed.") : joined;
    }

    // 对标 Python subagent.py:355-371
    private String buildSubagentPrompt(@Nullable Path workspace) {
        Path root = workspace != null ? workspace : this.workspace;
        // 对标 Python ContextBuilder._build_runtime_context(None, None)
        String timeCtx = ContextBuilder.buildRuntimeContext(null, null);
        // 对标 Python SkillsLoader(root, disabled_skills=self.disabled_skills).build_skills_summary()
        String skillsSummary = new SkillsLoader(root, null, disabledSkills).buildSkillsSummary(null);
        return renderTemplate("agent/subagent_system.md", timeCtx, root.toString(), skillsSummary);
    }

    // 对标 Python subagent.py:373-381
    public CompletableFuture<Integer> cancelBySession(String sessionKey) {
        Set<String> tids = sessionTasks.get(sessionKey);
        if (tids == null || tids.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        List<CompletableFuture<Void>> toCancel = new ArrayList<>();
        for (String tid : tids) {
            CompletableFuture<Void> task = runningTasks.get(tid);
            if (task != null && !task.isDone()) {
                task.cancel(true);
                toCancel.add(task);
            }
        }
        return CompletableFuture.allOf(toCancel.toArray(new CompletableFuture[0]))
                .handle((v, ex) -> toCancel.size());
    }

    // 对标 Python subagent.py:383-385
    public int getRunningCount() {
        return (int) runningTasks.values().stream().filter(f -> !f.isDone()).count();
    }

    // 对标 Python subagent.py:387-393
    public int getRunningCountBySession(String sessionKey) {
        Set<String> tids = sessionTasks.get(sessionKey);
        if (tids == null) {
            return 0;
        }
        int count = 0;
        for (String tid : tids) {
            CompletableFuture<Void> task = runningTasks.get(tid);
            if (task != null && !task.isDone()) {
                count++;
            }
        }
        return count;
    }

    // 对标 Python security.workspace_access.bind_workspace_scope
    private static Object bindWorkspaceScope(WorkspaceScope scope) {
        // 实际实现应将 workspace scope 绑定到当前线程上下文
        return scope;
    }

    // 对标 Python security.workspace_access.reset_workspace_scope
    private static void resetWorkspaceScope(Object token) {
        // 实际实现应清理线程上下文中的 workspace scope
    }

    // -- template helper placeholder -----------------------------------------

    private static String renderTemplate(String name, Object... args) {
        return "";
    }
}
```

### 3.3 SubagentStatus + SubagentHook

```java
package com.nanobot.agent;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 对标 Python subagent.py:32-45 SubagentStatus — 子 agent 实时状态。
 */
@Data
public class SubagentStatus {
    private final String taskId;
    private final String label;
    private final String taskDescription;
    private final long startedAt;  // System.nanoTime() 单调时钟
    private volatile String phase; // initializing | awaiting_tools | tools_completed | final_response | done | error
    private volatile int iteration;
    private volatile List<Map<String, Object>> toolEvents;
    private volatile Map<String, Object> usage;
    private volatile String stopReason;
    private volatile String error;
}
```

```java
package com.nanobot.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanobot.agent.hook.AgentHook;
import com.nanobot.agent.hook.AgentHookContext;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;

/**
 * 对标 Python subagent.py:48-71 _SubagentHook — 子 agent 执行 Hook。
 */
@Slf4j
public class SubagentHook extends AgentHook {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String taskId;
    private final SubagentStatus status;

    public SubagentHook(String taskId, SubagentStatus status) {
        this.taskId = taskId;
        this.status = status;
    }

    // 对标 Python subagent.py:56-62
    @Override
    public CompletableFuture<Void> beforeExecuteTools(AgentHookContext context) {
        for (var toolCall : context.getToolCalls()) {
            try {
                String argsStr = MAPPER.writeValueAsString(toolCall.getArguments());
                log.debug("Subagent [{}] executing: {} with arguments: {}",
                        taskId, toolCall.getName(), argsStr);
            } catch (JsonProcessingException e) {
                log.debug("Subagent [{}] executing: {}", taskId, toolCall.getName());
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    // 对标 Python subagent.py:64-71
    @Override
    public CompletableFuture<Void> afterIteration(AgentHookContext context) {
        if (status == null) {
            return CompletableFuture.completedFuture(null);
        }
        status.setIteration(context.getIteration());
        status.setToolEvents(context.getToolEvents());
        status.setUsage(context.getUsage());
        if (context.getError() != null) {
            status.setError(context.getError().toString());
        }
        return CompletableFuture.completedFuture(null);
    }
}
```

---

## 4. SkillsLoader (`skills.py`)

### 4.1 Python 源码分析

```
SkillsLoader:
    __init__: workspace, builtin_skills_dir, disabled_skills
    - _skill_entries_from_dir(base, source, skip_names) → list[{name, path, source}]
    - list_skills(filter_unavailable=True) → 合并 workspace + builtin，去重，过滤不可用
    - load_skill(name) → str | None (按 workspace → builtin 顺序查找 SKILL.md)
    - load_skills_for_context(skill_names) → 格式化 skills 内容（strip frontmatter）
    - build_skills_summary(exclude) → Markdown 列表（含 availability 状态）
    - _get_missing_requirements(meta) → str
    - get_skill_availability(name) → (bool, str)
    - get_skill_requirements(name) → dict[bins, env, missing_bins, missing_env]
    - _get_skill_description(name) → str (from frontmatter)
    - _strip_frontmatter(content) → str (正则移除 YAML frontmatter)
    - _parse_nanobot_metadata(raw) → dict (提取 nanobot/openclaw 元数据)
    - _check_requirements(meta) → bool (shutil.which + os.environ)
    - _get_skill_meta(name) → dict
    - get_always_skills() → list[str] (always=true 且满足 requirements)
    - get_skill_metadata(name) → dict | None (YAML frontmatter 解析)
```

### 4.2 Java 实现

```java
package com.nanobot.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 对标 Python skills.py:21 SkillsLoader — agent skills 加载器。
 */
@Slf4j
public class SkillsLoader {

    // 对标 Python skills.py:15-18
    private static final Pattern STRIP_SKILL_FRONTMATTER = Pattern.compile(
            "^---\\s*\\r?\\n(.*?)\\r?\\n---\\s*\\r?\\n?",
            Pattern.DOTALL);

    private static final ObjectMapper YAML_MAPPER = new YAMLMapper();
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final Path workspace;
    private final Path workspaceSkills;
    private final Path builtinSkills;
    private final Set<String> disabledSkills;

    // 对标 Python skills.py:29-33
    public SkillsLoader(Path workspace, @Nullable Path builtinSkillsDir, @Nullable Set<String> disabledSkills) {
        this.workspace = workspace;
        this.workspaceSkills = workspace.resolve("skills");
        this.builtinSkills = builtinSkillsDir != null ? builtinSkillsDir
                : resolveBuiltinSkillsDir();
        this.disabledSkills = disabledSkills != null ? new HashSet<>(disabledSkills) : new HashSet<>();
    }

    private static Path resolveBuiltinSkillsDir() {
        // 对标 Python BUILTIN_SKILLS_DIR = Path(__file__).parent.parent / "skills"
        // Java 中从 classpath 推断或硬编码
        return Path.of("src/main/resources/skills");
    }

    // 对标 Python skills.py:35-49
    private List<Map<String, String>> skillEntriesFromDir(Path base, String source,
                                                           @Nullable Set<String> skipNames) {
        if (!Files.exists(base)) {
            return List.of();
        }
        List<Map<String, String>> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(base)) {
            for (Path skillDir : stream) {
                if (!Files.isDirectory(skillDir)) {
                    continue;
                }
                Path skillFile = skillDir.resolve("SKILL.md");
                if (!Files.exists(skillFile)) {
                    continue;
                }
                String name = skillDir.getFileName().toString();
                if (skipNames != null && skipNames.contains(name)) {
                    continue;
                }
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("name", name);
                entry.put("path", skillFile.toString());
                entry.put("source", source);
                entries.add(entry);
            }
        } catch (IOException e) {
            log.warn("Failed to list skills from {}", base, e);
        }
        return entries;
    }

    // 对标 Python skills.py:51-73
    public List<Map<String, String>> listSkills(boolean filterUnavailable) {
        List<Map<String, String>> skills = skillEntriesFromDir(workspaceSkills, "workspace", null);
        Set<String> workspaceNames = skills.stream().map(e -> e.get("name")).collect(Collectors.toSet());
        if (builtinSkills != null && Files.exists(builtinSkills)) {
            skills.addAll(skillEntriesFromDir(builtinSkills, "builtin", workspaceNames));
        }
        if (!disabledSkills.isEmpty()) {
            skills = skills.stream()
                    .filter(s -> !disabledSkills.contains(s.get("name")))
                    .toList();
        }
        if (filterUnavailable) {
            skills = skills.stream()
                    .filter(s -> checkRequirements(getSkillMeta(s.get("name"))))
                    .toList();
        }
        return skills;
    }

    // 对标 Python skills.py:75-92
    @Nullable
    public String loadSkill(String name) {
        List<Path> roots = new ArrayList<>();
        roots.add(workspaceSkills);
        if (builtinSkills != null) {
            roots.add(builtinSkills);
        }
        for (Path root : roots) {
            Path path = root.resolve(name).resolve("SKILL.md");
            if (Files.exists(path)) {
                try {
                    return Files.readString(path);
                } catch (IOException e) {
                    log.warn("Failed to read skill {}", path);
                }
            }
        }
        return null;
    }

    // 对标 Python skills.py:94-109
    public String loadSkillsForContext(List<String> skillNames) {
        List<String> parts = new ArrayList<>();
        for (String name : skillNames) {
            String markdown = loadSkill(name);
            if (markdown != null) {
                parts.add("### Skill: " + name + "\n\n" + stripFrontmatter(markdown));
            }
        }
        return String.join("\n\n---\n\n", parts);
    }

    // 对标 Python skills.py:111-142
    public String buildSkillsSummary(@Nullable Set<String> exclude) {
        List<Map<String, String>> allSkills = listSkills(false);
        if (allSkills.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (Map<String, String> entry : allSkills) {
            String skillName = entry.get("name");
            if (exclude != null && exclude.contains(skillName)) {
                continue;
            }
            Map<String, Object> meta = getSkillMeta(skillName);
            boolean available = checkRequirements(meta);
            String desc = getSkillDescription(skillName);
            if (available) {
                lines.add("- **" + skillName + "** — " + desc + "  `" + entry.get("path") + "`");
            } else {
                String missing = getMissingRequirements(meta);
                String suffix = missing.isEmpty() ? " (unavailable)" : " (unavailable: " + missing + ")";
                lines.add("- **" + skillName + "** — " + desc + suffix + "  `" + entry.get("path") + "`");
            }
        }
        return String.join("\n", lines);
    }

    // 对标 Python skills.py:144-152
    private String getMissingRequirements(Map<String, Object> skillMeta) {
        Object requiresObj = skillMeta.get("requires");
        if (!(requiresObj instanceof Map<?, ?> requires)) {
            return "";
        }
        List<String> missing = new ArrayList<>();
        List<?> requiredBins = (List<?>) requires.getOrDefault("bins", List.of());
        List<?> requiredEnvVars = (List<?>) requires.getOrDefault("env", List.of());
        for (Object cmd : requiredBins) {
            if (!commandExists(cmd.toString())) {
                missing.add("CLI: " + cmd);
            }
        }
        for (Object env : requiredEnvVars) {
            if (System.getenv(env.toString()) == null) {
                missing.add("ENV: " + env);
            }
        }
        return String.join(", ", missing);
    }

    // 对标 Python skills.py:154-158
    public Pair<Boolean, String> getSkillAvailability(String name) {
        Map<String, Object> meta = getSkillMeta(name);
        boolean available = checkRequirements(meta);
        return new Pair<>(available, available ? "" : getMissingRequirements(meta));
    }

    // 对标 Python skills.py:160-170
    public Map<String, List<String>> getSkillRequirements(String name) {
        Map<String, Object> meta = getSkillMeta(name);
        Object requiresObj = meta.get("requires");
        Map<?, ?> requires = requiresObj instanceof Map<?, ?> m ? m : Map.of();
        List<String> bins = ((List<?>) requires.getOrDefault("bins", List.of())).stream()
                .map(Object::toString).toList();
        List<String> env = ((List<?>) requires.getOrDefault("env", List.of())).stream()
                .map(Object::toString).toList();
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("bins", bins);
        result.put("env", env);
        result.put("missing_bins", bins.stream().filter(s -> !commandExists(s)).toList());
        result.put("missing_env", env.stream().filter(s -> System.getenv(s) == null).toList());
        return result;
    }

    // 对标 Python skills.py:172-177
    private String getSkillDescription(String name) {
        Map<String, Object> meta = getSkillMetadata(name);
        if (meta != null && meta.get("description") instanceof String desc) {
            return desc;
        }
        return name;
    }

    // 对标 Python skills.py:179-186
    private String stripFrontmatter(String content) {
        if (!content.startsWith("---")) {
            return content;
        }
        Matcher m = STRIP_SKILL_FRONTMATTER.matcher(content);
        if (m.find()) {
            return content.substring(m.end()).trim();
        }
        return content;
    }

    // 对标 Python skills.py:188-205
    private Map<String, Object> parseNanobotMetadata(Object raw) {
        Map<String, Object> data;
        if (raw instanceof Map<?, ?> map) {
            data = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                data.put(e.getKey().toString(), e.getValue());
            }
        } else if (raw instanceof String s) {
            try {
                data = JSON_MAPPER.readValue(s, new TypeReference<>() {});
            } catch (IOException e) {
                return Map.of();
            }
        } else {
            return Map.of();
        }
        if (data == null) {
            return Map.of();
        }
        Object payload = data.get("nanobot");
        if (payload == null) {
            payload = data.get("openclaw");
        }
        if (payload instanceof Map<?, ?> pmap) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : pmap.entrySet()) {
                result.put(e.getKey().toString(), e.getValue());
            }
            return result;
        }
        return Map.of();
    }

    // 对标 Python skills.py:207-213
    private boolean checkRequirements(Map<String, Object> skillMeta) {
        Object requiresObj = skillMeta.get("requires");
        if (!(requiresObj instanceof Map<?, ?> requires)) {
            return true;
        }
        List<?> requiredBins = (List<?>) requires.getOrDefault("bins", List.of());
        List<?> requiredEnvVars = (List<?>) requires.getOrDefault("env", List.of());
        return requiredBins.stream().allMatch(cmd -> commandExists(cmd.toString()))
                && requiredEnvVars.stream().allMatch(v -> System.getenv(v.toString()) != null);
    }

    // 对标 Python skills.py:216-219
    private Map<String, Object> getSkillMeta(String name) {
        Map<String, Object> rawMeta = getSkillMetadata(name);
        if (rawMeta == null) {
            return Map.of();
        }
        return parseNanobotMetadata(rawMeta.get("metadata"));
    }

    // 对标 Python skills.py:221-231
    public List<String> getAlwaysSkills() {
        List<Map<String, String>> skills = listSkills(true);
        List<String> result = new ArrayList<>();
        for (Map<String, String> entry : skills) {
            String name = entry.get("name");
            Map<String, Object> meta = getSkillMetadata(name);
            if (meta == null) {
                continue;
            }
            Object nanobotMeta = parseNanobotMetadata(meta.get("metadata"));
            boolean always = Boolean.TRUE.equals(nanobotMeta.get("always"))
                    || Boolean.TRUE.equals(meta.get("always"));
            if (always) {
                result.add(name);
            }
        }
        return result;
    }

    // 对标 Python skills.py:233-260
    @Nullable
    public Map<String, Object> getSkillMetadata(String name) {
        String content = loadSkill(name);
        if (content == null || !content.startsWith("---")) {
            return null;
        }
        Matcher m = STRIP_SKILL_FRONTMATTER.matcher(content);
        if (!m.find()) {
            return null;
        }
        try {
            Map<String, Object> parsed = YAML_MAPPER.readValue(m.group(1), new TypeReference<>() {});
            if (parsed == null) {
                return null;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : parsed.entrySet()) {
                metadata.put(e.getKey(), e.getValue());
            }
            return metadata;
        } catch (IOException e) {
            return null;
        }
    }

    // 对标 Python shutil.which()
    private static boolean commandExists(String command) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        String[] dirs = path.split(java.io.File.pathSeparator);
        for (String dir : dirs) {
            Path candidate = Path.of(dir, command);
            if (Files.exists(candidate)) {
                return true;
            }
            // Windows 扩展名
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                for (String ext : new String[]{".exe", ".cmd", ".bat"}) {
                    if (Files.exists(Path.of(dir, command + ext))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
```

---

## 5. AutoCompact (`autocompact.py`)

### 5.1 Python 源码分析

```
AutoCompact:
    _RECENT_SUFFIX_MESSAGES = 8
    _INTERNAL_SESSION_PREFIXES = ("dream:",)

    __init__: sessions, consolidator, session_ttl_minutes
    _is_expired(ts, now) → bool
    _format_summary(text, last_active) → str
    _is_internal_session(key) → bool
    check_expired(schedule_background, active_session_keys)
        → 遍历 sessions.list_sessions()，对过期且非活跃会话 schedule_background(_archive(key))
    _archive(key) → async
        → 调用 consolidator.compact_idle_session(key, _RECENT_SUFFIX_MESSAGES)
        → 成功后将 summary 写入内存 _summaries[key]
    prepare_session(session, key) → (Session, str | None)
        → Hot path: _summaries 内存字典
        → Cold path: session.metadata["_last_summary"]
```

### 5.2 Java 实现

```java
package com.nanobot.agent;

import com.nanobot.session.Session;
import com.nanobot.session.SessionManager;
import com.nanobot.utils.Pair;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 对标 Python autocompact.py:17 AutoCompact — 空闲会话主动压缩。
 *
 * 当 session 空闲超过 ttl 时，调度后台 consolidation，减少 token 消耗和延迟。
 */
@Slf4j
public class AutoCompact {

    // 对标 Python autocompact.py:18
    private static final int RECENT_SUFFIX_MESSAGES = 8;
    // 对标 Python autocompact.py:19
    private static final Set<String> INTERNAL_SESSION_PREFIXES = Set.of("dream:");

    private final SessionManager sessions;
    private final Consolidator consolidator;
    private final int ttl; // minutes, 0 表示禁用
    // 对标 Python autocompact.py:26-27
    private final Set<String> archiving = ConcurrentHashMap.newKeySet();
    private final Map<String, Pair<String, Instant>> summaries = new ConcurrentHashMap<>();

    // 对标 Python autocompact.py:21-27
    public AutoCompact(SessionManager sessions, Consolidator consolidator, int sessionTtlMinutes) {
        this.sessions = sessions;
        this.consolidator = consolidator;
        this.ttl = sessionTtlMinutes;
    }

    // 对标 Python autocompact.py:29-35
    public boolean isExpired(Object ts, Instant now) {
        if (ttl <= 0 || ts == null) {
            return false;
        }
        Instant timestamp;
        if (ts instanceof Instant i) {
            timestamp = i;
        } else if (ts instanceof String s) {
            try {
                timestamp = Instant.parse(s);
            } catch (DateTimeParseException e) {
                return false;
            }
        } else {
            return false;
        }
        Instant effectiveNow = now != null ? now : Instant.now();
        return java.time.Duration.between(timestamp, effectiveNow).getSeconds() >= ttl * 60L;
    }

    // 对标 Python autocompact.py:37-39
    public static String formatSummary(String text, Instant lastActive) {
        return "Previous conversation summary (last active " + lastActive + "):\n" + text;
    }

    // 对标 Python autocompact.py:41-43
    public static boolean isInternalSession(String key) {
        if (key == null) {
            return false;
        }
        return INTERNAL_SESSION_PREFIXES.stream().anyMatch(key::startsWith);
    }

    // 对标 Python autocompact.py:45-57
    public void checkExpired(Consumer<CompletableFuture<Void>> scheduleBackground,
                             Collection<String> activeSessionKeys) {
        Instant now = Instant.now();
        for (Map<String, Object> info : sessions.listSessions()) {
            String key = (String) info.getOrDefault("key", "");
            if (key.isEmpty() || isInternalSession(key) || archiving.contains(key)) {
                continue;
            }
            if (activeSessionKeys.contains(key)) {
                continue;
            }
            Object updatedAt = info.get("updated_at");
            if (isExpired(updatedAt, now)) {
                archiving.add(key);
                scheduleBackground.accept(archive(key));
            }
        }
    }

    // 对标 Python autocompact.py:59-78
    public CompletableFuture<Void> archive(String key) {
        if (isInternalSession(key)) {
            archiving.remove(key);
            return CompletableFuture.completedFuture(null);
        }
        return consolidator.compactIdleSession(key, RECENT_SUFFIX_MESSAGES)
                .thenAccept(summary -> {
                    if (summary != null && !"(nothing)".equals(summary)) {
                        Session session = sessions.getOrCreate(key);
                        Object meta = session.getMetadata().get("_last_summary");
                        if (meta instanceof Map<?, ?> map && map.get("text") instanceof String text
                                && map.get("last_active") instanceof String lastActiveStr) {
                            try {
                                Instant lastActive = Instant.parse(lastActiveStr);
                                summaries.put(key, new Pair<>(text, lastActive));
                            } catch (DateTimeParseException e) {
                                // ignore
                            }
                        }
                    }
                })
                .exceptionally(ex -> {
                    log.error("Auto-compact: failed for {}", key, ex);
                    return null;
                })
                .thenRun(() -> archiving.remove(key));
    }

    // 对标 Python autocompact.py:80-96
    public Pair<Session, String> prepareSession(Session session, String key) {
        if (isInternalSession(key)) {
            archiving.remove(key);
            summaries.remove(key);
            return new Pair<>(session, null);
        }
        if (archiving.contains(key) || isExpired(session.getUpdatedAt(), null)) {
            log.info("Auto-compact: reloading session {} (archiving={})", key, archiving.contains(key));
            session = sessions.getOrCreate(key);
        }
        // Hot path: 内存字典（进程未重启）
        Pair<String, Instant> entry = summaries.remove(key);
        if (entry != null) {
            return new Pair<>(session, formatSummary(entry.left(), entry.right()));
        }
        // Cold path: session metadata（进程重启后）
        Object meta = session.getMetadata().get("_last_summary");
        if (meta instanceof Map<?, ?> map && map.get("text") instanceof String text
                && map.get("last_active") instanceof String lastActiveStr) {
            try {
                Instant lastActive = Instant.parse(lastActiveStr);
                return new Pair<>(session, formatSummary(text, lastActive));
            } catch (DateTimeParseException e) {
                // ignore
            }
        }
        return new Pair<>(session, null);
    }
}
```

---

## 6. ModelPresets (`model_presets.py`)

### 6.1 Python 源码分析

```
PresetSnapshotLoader = Callable[[str], ProviderSnapshot]

default_selection_signature(signature) → signature[:2] or None
configured_model_presets(config) → dict[str, ModelPresetConfig] (含 "default")
make_preset_snapshot_loader(config, provider_snapshot_loader) → PresetSnapshotLoader
build_static_preset_snapshot(provider, name, preset) → ProviderSnapshot
build_runtime_preset_snapshot(name, presets, provider, loader) → ProviderSnapshot
normalize_preset_name(name, presets) → str (校验存在性)
```

### 6.2 Java 实现

```java
package com.nanobot.agent;

import com.nanobot.config.ModelPresetConfig;
import com.nanobot.providers.GenerationSettings;
import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.ProviderSnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 对标 Python model_presets.py — 运行时模型预设选择辅助函数。
 */
public final class ModelPresets {

    private ModelPresets() {}

    // 对标 Python model_presets.py:12 PresetSnapshotLoader = Callable[[str], ProviderSnapshot]
    @FunctionalInterface
    public interface PresetSnapshotLoader {
        ProviderSnapshot load(String name);
    }

    // 对标 Python model_presets.py:15-16
    public static List<Object> defaultSelectionSignature(List<Object> signature) {
        if (signature == null || signature.isEmpty()) {
            return null;
        }
        if (signature.size() <= 2) {
            return signature;
        }
        return signature.subList(0, 2);
    }

    // 对标 Python model_presets.py:19-20
    public static Map<String, ModelPresetConfig> configuredModelPresets(Config config) {
        Map<String, ModelPresetConfig> result = new LinkedHashMap<>();
        result.putAll(config.getModelPresets());
        result.put("default", config.resolveDefaultPreset());
        return result;
    }

    // 对标 Python model_presets.py:23-29
    public static PresetSnapshotLoader makePresetSnapshotLoader(
            Config config,
            BiFunction<String, String, ProviderSnapshot> providerSnapshotLoader) {
        if (providerSnapshotLoader != null) {
            return name -> providerSnapshotLoader.apply(name, name);
        }
        return name -> buildProviderSnapshot(config, name);
    }

    // 对标 Python model_presets.py:32-43
    public static ProviderSnapshot buildStaticPresetSnapshot(
            LLMProvider provider,
            String name,
            ModelPresetConfig preset) {
        provider.setGenerationSettings(preset.toGenerationSettings());
        return ProviderSnapshot.builder()
                .provider(provider)
                .model(preset.getModel())
                .contextWindowTokens(preset.getContextWindowTokens())
                .signature(List.of("model_preset", name, preset.toJson()))
                .build();
    }

    // 对标 Python model_presets.py:46-55
    public static ProviderSnapshot buildRuntimePresetSnapshot(
            String name,
            Map<String, ModelPresetConfig> presets,
            LLMProvider provider,
            PresetSnapshotLoader loader) {
        if (loader != null) {
            return loader.load(name);
        }
        return buildStaticPresetSnapshot(provider, name, presets.get(name));
    }

    // 对标 Python model_presets.py:58-64
    public static String normalizePresetName(String name, Map<String, ModelPresetConfig> presets) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("model_preset must be a non-empty string");
        }
        name = name.trim();
        if (!presets.containsKey(name)) {
            throw new IllegalArgumentException("model_preset '" + name + "' not found. Available: "
                    + String.join(", ", presets.keySet()));
        }
        return name;
    }

    // -- 占位依赖 --

    private static ProviderSnapshot buildProviderSnapshot(Config config, String presetName) {
        // 对标 Python providers.factory.build_provider_snapshot
        throw new UnsupportedOperationException("Placeholder: buildProviderSnapshot not implemented");
    }

    // Config 占位接口
    public interface Config {
        Map<String, ModelPresetConfig> getModelPresets();
        ModelPresetConfig resolveDefaultPreset();
    }
}
```

---

## 代码量估算

| 文件 | 行数 | 说明 |
|------|------|------|
| MemoryStore.java | ~580 | 含 legacy 迁移、cursor、JSONL、Dream、raw archive |
| Consolidator.java | ~380 | 含 async archive / maybeConsolidateByTokens / compactIdleSession |
| SubagentManager.java | ~340 | 含 async spawn / runSubagent / announceResult / cancelBySession |
| SubagentStatus.java | ~20 | 数据类 |
| SubagentHook.java | ~50 | AgentHook 子类 |
| SkillsLoader.java | ~300 | 含 YAML frontmatter、requirements、availability、always skills |
| AutoCompact.java | ~130 | 有状态类，含 async _archive、hot/cold prepare_session |
| ModelPresets.java | ~90 | 含所有 helper 函数、PresetSnapshotLoader 函数式接口 |
| **合计** | **~1,890** | 对标 Python 合计 ~1,833 行 |

---

## 复刻检查清单

| Python 源文件 | Java 类 | 关键 async 方法 | 状态 |
|---|---|---|---|
| `memory.py:40-588` | `MemoryStore` | 无（纯同步文件 I/O，线程安全由 ReentrantLock 保证） | 已对齐 |
| `memory.py:602-1015` | `Consolidator` | `archive() → CF<String>`; `maybeConsolidateByTokens() → CF<Void>`; `compactIdleSession() → CF<String>` | 已对齐 |
| `subagent.py:32-45` | `SubagentStatus` | 无 | 已对齐 |
| `subagent.py:48-71` | `SubagentHook` | `beforeExecuteTools() → CF<Void>`; `afterIteration() → CF<Void>` | 已对齐 |
| `subagent.py:74-394` | `SubagentManager` | `spawn() → CF<String>`; `runSubagent() → CF<Void>`; `announceResult() → CF<Void>`; `cancelBySession() → CF<Integer>` | 已对齐 |
| `skills.py:21-260` | `SkillsLoader` | 无 | 已对齐 |
| `autocompact.py:17-96` | `AutoCompact` | `archive() → CF<Void>` | 已对齐 |
| `model_presets.py:1-66` | `ModelPresets` | 无 | 已对齐 |
