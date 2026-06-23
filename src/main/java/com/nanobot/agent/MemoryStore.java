package com.nanobot.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanobot.agent.tools.ToolRegistry;
import com.nanobot.agent.tools.impl.ApplyPatchTool;
import com.nanobot.agent.tools.impl.EditFileTool;
import com.nanobot.agent.tools.impl.ReadFileTool;
import com.nanobot.agent.tools.impl.WriteFileTool;

import javax.annotation.Nullable;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
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
 * 纯文件 I/O 记忆层——管理 MEMORY.md、history.jsonl、SOUL.md、USER.md 的读写，
 * 以及旧版 HISTORY.md 的一次性迁移。
 *
 * <p>对标 Python {@code nanobot/agent/memory.py:40-588 class MemoryStore}。
 */
public class MemoryStore {

    /** 对标 Python GitStore——工作区内存文件的 Git 版本控制。 */
    private final GitStore git;

    // ==================== 常量 ====================

    /** 对标 Python _DEFAULT_MAX_HISTORY = 1000。 */
    private static final int DEFAULT_MAX_HISTORY = 1000;

    /** 内部会话键集合（心跳、cron、dream）。对标 Python _INTERNAL_HISTORY_SESSION_KEYS。 */
    private static final Set<String> INTERNAL_HISTORY_SESSION_KEYS = Set.of("heartbeat");
    private static final Set<String> INTERNAL_HISTORY_SESSION_PREFIXES =
            Set.of("cron:", "dream:");

    /** 旧版历史解析正则。对标 Python 模块级 RE 常量。 */
    private static final Pattern LEGACY_ENTRY_START_RE = Pattern.compile(
            "^\\[(\\d{4}-\\d{2}-\\d{2}[^\\]]*)\\]\\s*");
    private static final Pattern LEGACY_TIMESTAMP_RE = Pattern.compile(
            "^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2})\\]\\s*");
    private static final Pattern LEGACY_RAW_MESSAGE_RE = Pattern.compile(
            "^\\[\\d{4}-\\d{2}-\\d{2}[^\\]]*\\]\\s+[A-Z][A-Z0-9_]*(?:\\s+\\[tools:\\s*[^\\]]+\\])?:");

    /** 降级归档的字符上限。对标 Python _RAW_ARCHIVE_MAX_CHARS / _ARCHIVE_SUMMARY_MAX_CHARS。 */
    private static final int RAW_ARCHIVE_MAX_CHARS = 16_000;
    private static final int ARCHIVE_SUMMARY_MAX_CHARS = 8_000;

    /** 单条 history 条目的硬上限。对标 Python _HISTORY_ENTRY_HARD_CAP = 64_000。 */
    private static final int HISTORY_ENTRY_HARD_CAP = 64_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // ==================== 字段 ====================

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

    /** 对标 Python self._append_lock = threading.Lock()——序列化 cursor 分配 + append。 */
    private final ReentrantLock appendLock = new ReentrantLock();

    /** 对标 Python self._corruption_logged / self._oversize_logged——限制重复日志。 */
    private volatile boolean corruptionLogged = false;
    private volatile boolean oversizeLogged = false;

    // ==================== 构造 ====================

    /**
     * 创建 MemoryStore。
     * 对标 Python MemoryStore.__init__(workspace, max_history_entries)。
     */
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
        // 对标 Python self._git = GitStore(workspace, tracked_files=[...])
        this.git = new GitStore(workspace, List.of(
                memoryFile.toString(), historyFile.toString(),
                soulFile.toString(), userFile.toString()));
        maybeMigrateLegacyHistory();
    }

    /** 对标 Python MemoryStore.git 属性。 */
    public GitStore git() { return git; }

    public MemoryStore(Path workspace) {
        this(workspace, DEFAULT_MAX_HISTORY);
    }

    // ==================== 通用读取 ====================

    /**
     * 静态读取文件，FileNotFound 时返回空字符串。
     * 对标 Python MemoryStore.read_file(path) 静态方法。
     */
    public static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (FileNotFoundException | NoSuchFileException e) {
            return "";
        } catch (IOException e) {
            return "";
        }
    }

    // ==================== 旧版迁移 ====================

    /**
     * 一次性将 HISTORY.md 迁移到 history.jsonl。
     * 对标 Python MemoryStore.maybe_migrate_legacy_history()。
     */
    private void maybeMigrateLegacyHistory() {
        if (!Files.exists(legacyHistoryFile)) return;
        if (Files.exists(historyFile)) {
            try {
                if (Files.size(historyFile) > 0) return;
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
                Files.writeString(dreamCursorFile, String.valueOf(lastCursor));
            }
            Path backup = nextLegacyBackupPath();
            Files.move(legacyHistoryFile, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            // suppress——non-critical migration failure
        }
    }

    /**
     * 解析旧版 HISTORY.md 文本。
     * 对标 Python MemoryStore._parse_legacy_history(text)。
     */
    private List<Map<String, Object>> parseLegacyHistory(String text) {
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n").trim();
        if (normalized.isEmpty()) return List.of();
        String fallbackTs = legacyFallbackTimestamp();
        List<Map<String, Object>> entries = new ArrayList<>();
        List<String> chunks = splitLegacyHistoryChunks(normalized);
        int cursor = 1;
        for (String chunk : chunks) {
            String timestamp = fallbackTs;
            String content = chunk;
            Matcher m = LEGACY_TIMESTAMP_RE.matcher(chunk);
            if (m.find()) {
                timestamp = m.group(1);
                String remainder = chunk.substring(m.end()).stripLeading();
                if (!remainder.isEmpty()) content = remainder;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("cursor", cursor++);
            entry.put("timestamp", timestamp);
            entry.put("content", content);
            entries.add(entry);
        }
        return entries;
    }

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
                if (!current.isEmpty()) chunks.add(String.join("\n", current).trim());
                current = new ArrayList<>();
                current.add(line);
                sawBlankSeparator = false;
                continue;
            }
            current.add(line);
            sawBlankSeparator = line.isBlank();
        }
        if (!current.isEmpty()) chunks.add(String.join("\n", current).trim());
        return chunks.stream().filter(s -> !s.isEmpty()).toList();
    }

    private boolean shouldStartNewLegacyChunk(String line, List<String> current) {
        if (current.isEmpty()) return false;
        if (!LEGACY_ENTRY_START_RE.matcher(line).find()) return false;
        if (isRawLegacyChunk(current) && LEGACY_RAW_MESSAGE_RE.matcher(line).find()) return false;
        return true;
    }

    private boolean isRawLegacyChunk(List<String> lines) {
        String first = lines.stream().filter(s -> !s.isBlank()).findFirst().orElse("");
        Matcher m = LEGACY_TIMESTAMP_RE.matcher(first);
        if (!m.find()) return false;
        return first.substring(m.end()).stripLeading().startsWith("[RAW]");
    }

    private String legacyFallbackTimestamp() {
        try {
            return LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(Files.getLastModifiedTime(legacyHistoryFile).toMillis()),
                    java.time.ZoneId.systemDefault()).format(TS_FMT);
        } catch (IOException e) {
            return LocalDateTime.now().format(TS_FMT);
        }
    }

    private Path nextLegacyBackupPath() {
        Path candidate = memoryDir.resolve("HISTORY.md.bak");
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = memoryDir.resolve("HISTORY.md.bak." + suffix);
            suffix++;
        }
        return candidate;
    }

    // ==================== MEMORY.md ====================

    /** 对标 Python read_memory()。 */
    public String readMemory() { return readFile(memoryFile); }

    /** 对标 Python write_memory(content)。 */
    public void writeMemory(String content) {
        try { Files.writeString(memoryFile, content); }
        catch (IOException e) { /* suppress */ }
    }

    // ==================== SOUL.md / USER.md ====================

    public String readSoul() { return readFile(soulFile); }
    public void writeSoul(String content) {
        try { Files.writeString(soulFile, content); }
        catch (IOException e) { /* suppress */ }
    }

    public String readUser() { return readFile(userFile); }
    public void writeUser(String content) {
        try { Files.writeString(userFile, content); }
        catch (IOException e) { /* suppress */ }
    }

    // ==================== context injection ====================

    /**
     * 获取供 LLM system prompt 使用的记忆上下文。
     * 对标 Python get_memory_context()。
     */
    public String getMemoryContext() {
        String lt = readMemory();
        return lt.isEmpty() ? "" : "## Long-term Memory\n" + lt;
    }

    // ==================== history.jsonl — append-only ====================

    /**
     * 以追加写方式持久化一条 history 条目。
     * 线程安全的 cursor 分配 + 写入。
     * 对标 Python append_history(entry, *, max_chars=None, session_key=None) → int。
     */
    public int appendHistory(String entry, @Nullable Integer maxChars, @Nullable String sessionKey) {
        int limit = maxChars != null ? maxChars : HISTORY_ENTRY_HARD_CAP;
        String raw = entry.stripTrailing();
        if (raw.length() > limit) {
            if (!oversizeLogged) {
                oversizeLogged = true;
                // oversize: log once, then suppress
            }
            raw = truncateText(raw, limit);
        }
        String content = stripThink(raw);

        appendLock.lock();
        try {
            int cursor = nextCursor();
            if (!raw.isEmpty() && content.isEmpty()) {
                // stripped to empty — persist empty to prevent re-pollution
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("cursor", cursor);
            record.put("timestamp", LocalDateTime.now().format(TS_FMT));
            record.put("content", content);
            if (sessionKey != null) record.put("session_key", sessionKey);

            Files.writeString(historyFile,
                    MAPPER.writeValueAsString(record) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Files.writeString(cursorFile, String.valueOf(cursor));
            return cursor;
        } catch (IOException e) {
            return -1;
        } finally {
            appendLock.unlock();
        }
    }

    // ==================== cursor ====================

    /**
     * cursor 类型校验——排除 boolean（Python 中 bool 是 int 子类）。
     * 对标 Python _valid_cursor(value) 静态方法。
     */
    @Nullable
    private static Integer validCursor(Object value) {
        if (value instanceof Boolean || !(value instanceof Number)) return null;
        return ((Number) value).intValue();
    }

    /**
     * 遍历所有有效条目（含 cursor 校验）。
     * 对标 Python _iter_valid_entries()。
     */
    private List<Pair> iterValidEntries() {
        List<Pair> result = new ArrayList<>();
        Object poisoned = null;
        for (Map<String, Object> entry : readEntries()) {
            Object raw = entry.get("cursor");
            if (raw == null) continue;
            Integer cursor = validCursor(raw);
            if (cursor == null) {
                poisoned = raw;
                continue;
            }
            result.add(new Pair(entry, cursor));
        }
        if (poisoned != null && !corruptionLogged) {
            corruptionLogged = true;
        }
        return result;
    }

    /**
     * 分配下一个 cursor 值。
     * 对标 Python _next_cursor() —— cursor 文件 → 尾部 → 全量扫描。
     */
    private int nextCursor() {
        if (Files.exists(cursorFile)) {
            try {
                return Integer.parseInt(Files.readString(cursorFile).trim()) + 1;
            } catch (IOException | NumberFormatException ignored) {}
        }
        // Fast path: trust tail
        Map<String, Object> last = readLastEntry();
        if (last != null) {
            Integer c = validCursor(last.get("cursor"));
            if (c != null) return c + 1;
        }
        // Fallback: scan all
        int max = iterValidEntries().stream().mapToInt(p -> p.cursor).max().orElse(0);
        return max + 1;
    }

    // ==================== history 读取 ====================

    /**
     * 读取自 sinceCursor 之后未处理的 history 条目。
     * 对标 Python read_unprocessed_history(since_cursor)。
     */
    public List<Map<String, Object>> readUnprocessedHistory(int sinceCursor) {
        return iterValidEntries().stream()
                .filter(p -> p.cursor > sinceCursor)
                .map(p -> p.entry)
                .toList();
    }

    /**
     * 判断是否内部 history session（心跳/cron/dream）。
     * 对标 Python _is_internal_history_session(session_key)。
     */
    private static boolean isInternalHistorySession(@Nullable String sessionKey) {
        if (sessionKey == null || sessionKey.isEmpty()) return false;
        if (INTERNAL_HISTORY_SESSION_KEYS.contains(sessionKey)) return true;
        return INTERNAL_HISTORY_SESSION_PREFIXES.stream().anyMatch(sessionKey::startsWith);
    }

    /**
     * 读取用于 LLM prompt 的近期 history。
     * 若 unifiedSession=true，包含匹配 session 的条目 + 所有非内部 session 条目。
     * 对标 Python read_recent_history_for_prompt(...)。
     */
    public List<Map<String, Object>> readRecentHistoryForPrompt(
            int sinceCursor, @Nullable String sessionKey, boolean unifiedSession) {
        List<Map<String, Object>> entries = readUnprocessedHistory(sinceCursor);
        if (sessionKey == null) return entries;
        if (!unifiedSession) {
            return entries.stream()
                    .filter(e -> sessionKey.equals(e.get("session_key")))
                    .toList();
        }
        return entries.stream()
                .filter(e -> {
                    String es = (String) e.get("session_key");
                    return sessionKey.equals(es) || !isInternalHistorySession(es);
                })
                .toList();
    }

    // ==================== compaction ====================

    /**
     * 超出上限时丢弃最旧的 history 条目。
     * 对标 Python compact_history()。
     */
    public void compactHistory() {
        if (maxHistoryEntries <= 0) return;
        List<Map<String, Object>> entries = readEntries();
        if (entries.size() <= maxHistoryEntries) return;
        List<Map<String, Object>> kept = entries.subList(
                entries.size() - maxHistoryEntries, entries.size());
        writeEntries(kept);
    }

    // ==================== JSONL I/O ====================

    /**
     * 逐行读取 history.jsonl。
     * 对标 Python _read_entries()。
     */
    private List<Map<String, Object>> readEntries() {
        List<Map<String, Object>> entries = new ArrayList<>();
        if (!Files.exists(historyFile)) return entries;
        try (BufferedReader br = Files.newBufferedReader(historyFile)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    entries.add(MAPPER.readValue(line, new TypeReference<>() {}));
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
        return entries;
    }

    /**
     * 读取末条（seek 到末尾 4096 字节）。
     * 对标 Python _read_last_entry()。
     */
    @Nullable
    private Map<String, Object> readLastEntry() {
        try (RandomAccessFile raf = new RandomAccessFile(historyFile.toFile(), "r")) {
            long size = raf.length();
            if (size == 0) return null;
            int readSize = (int) Math.min(size, 4096);
            raf.seek(size - readSize);
            byte[] buf = new byte[readSize];
            raf.readFully(buf);
            String data = new String(buf, StandardCharsets.UTF_8);
            String[] lines = Arrays.stream(data.split("\n"))
                    .filter(s -> !s.isBlank())
                    .toArray(String[]::new);
            if (lines.length == 0) return null;
            return MAPPER.readValue(lines[lines.length - 1], new TypeReference<>() {});
        } catch (FileNotFoundException | NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 原子写入全部条目（tmp → fsync → rename → dir fsync）。
     * 对标 Python _write_entries(entries)。
     */
    private void writeEntries(List<Map<String, Object>> entries) {
        Path tmp = historyFile.resolveSibling(historyFile.getFileName() + ".tmp");
        try {
            try (BufferedWriter w = Files.newBufferedWriter(tmp)) {
                for (Map<String, Object> entry : entries) {
                    w.write(MAPPER.writeValueAsString(entry));
                    w.newLine();
                }
                w.flush();
            }
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            } catch (IOException ignored) {}
            Files.move(tmp, historyFile,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            try {
                FileChannel dirCh = FileChannel.open(historyFile.getParent(), StandardOpenOption.READ);
                dirCh.force(true);
                dirCh.close();
            } catch (IOException ignored) {}
        } catch (Exception e) {
            try { Files.deleteIfExists(tmp); }
            catch (IOException ignored) {}
            throw new RuntimeException(e);
        }
    }

    // ==================== dream cursor ====================

    /**
     * 获取 dream 已处理位置。
     * 对标 Python get_last_dream_cursor()。
     */
    public int getLastDreamCursor() {
        if (Files.exists(dreamCursorFile)) {
            try {
                return Integer.parseInt(Files.readString(dreamCursorFile).trim());
            } catch (IOException | NumberFormatException ignored) {}
        }
        return 0;
    }

    /**
     * 写入 dream 已处理位置。
     * 对标 Python set_last_dream_cursor(cursor)。
     */
    public void setLastDreamCursor(int cursor) {
        try {
            Files.writeString(dreamCursorFile, String.valueOf(cursor));
        } catch (IOException ignored) {}
    }

    // ==================== dream prompt / tools ====================

    /**
     * 构建 Dream 两阶段记忆整合的 prompt。
     * 对标 Python build_dream_prompt(max_entries) → (prompt, end_cursor) | None。
     */
    @Nullable
    public Map.Entry<String, Integer> buildDreamPrompt(int maxEntries) {
        int lastCursor = getLastDreamCursor();
        List<Map<String, Object>> entries = readUnprocessedHistory(lastCursor);
        if (entries.isEmpty()) return null;
        List<Map<String, Object>> batch = entries.size() > maxEntries
                ? entries.subList(0, maxEntries) : entries;
        StringBuilder historyText = new StringBuilder();
        for (Map<String, Object> e : batch) {
            String ts = (String) e.getOrDefault("timestamp", "?");
            String content = truncateText(
                    (String) e.getOrDefault("content", ""), 500);
            historyText.append("[").append(ts).append("] ").append(content).append("\n");
        }
        String prompt = "Review conversation history and identify key facts, decisions, and memories.\n\n"
                + "## Conversation History\n" + historyText;
        int endCursor = batch.get(batch.size() - 1).get("cursor") instanceof Number n
                ? n.intValue() : lastCursor;
        return Map.entry(prompt, endCursor);
    }

    /** 对标 Python dream_run_completed(resp) 静态方法。 */
    public static boolean dreamRunCompleted(@Nullable Object resp) {
        if (resp == null) return false;
        try {
            Object metadata = resp.getClass().getMethod("getMetadata").invoke(resp);
            if (metadata instanceof Map<?, ?> map) {
                return "completed".equals(map.get("_stop_reason"));
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ==================== message formatting ====================

    /**
     * 将消息列表格式化为易读文本。
     * 对标 Python format_messages(messages) 静态方法。
     */
    public static String formatMessages(List<Map<String, Object>> messages) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> message : messages) {
            Object content = message.get("content");
            if (content == null || content.toString().isEmpty()) continue;
            Object toolsUsed = message.get("tools_used");
            String tools = "";
            if (toolsUsed instanceof List<?> list && !list.isEmpty()) {
                tools = " [tools: " + list.stream().map(Object::toString)
                        .collect(Collectors.joining(", ")) + "]";
            }
            String ts = Optional.ofNullable(message.get("timestamp"))
                    .map(Object::toString).orElse("?");
            if (ts.length() > 16) ts = ts.substring(0, 16);
            String role = Optional.ofNullable(message.get("role"))
                    .map(Object::toString).orElse("?").toUpperCase();
            sb.append("[").append(ts).append("] ").append(role).append(tools)
                    .append(": ").append(content).append("\n");
        }
        return sb.toString();
    }

    /**
     * LLM 总结失败时的降级归档——直接格式化写入 history。
     * 对标 Python raw_archive(messages, max_chars=None, session_key=None)。
     */
    public void rawArchive(List<Map<String, Object>> messages,
                           @Nullable Integer maxChars, @Nullable String sessionKey) {
        int limit = maxChars != null ? maxChars : RAW_ARCHIVE_MAX_CHARS;
        String formatted = truncateText(formatMessages(messages), limit);
        appendHistory("[RAW] " + messages.size() + " messages\n" + formatted, null, sessionKey);
    }

    // ==================== dream helpers ====================

    /** 对标 Python dream_session_key() 静态方法。 */
    public static String dreamSessionKey() {
        return "dream:" + LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }

    /** 对标 Python build_dream_commit_message(prefix, resp) 静态方法。 */
    public static String buildDreamCommitMessage(String prefix, @Nullable Object resp) {
        String msg = prefix;
        if (resp != null) {
            try {
                Object content = resp.getClass().getMethod("getContent").invoke(resp);
                if (content != null) {
                    String stripped = content.toString().strip();
                    if (!stripped.isEmpty()) msg = msg + "\n\n" + stripped;
                }
            } catch (Exception ignored) {}
        }
        return msg;
    }

    /** 对标 Python prune_dream_sessions(sessions_dir, keep=10) 静态方法。 */
    public static void pruneDreamSessions(Path sessionsDir, int keep) {
        try (var stream = Files.list(sessionsDir)) {
            List<Path> dreamFiles = stream
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("dream_") && n.endsWith(".jsonl");
                    })
                    .sorted(Comparator.comparingLong(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); }
                        catch (IOException e) { return 0; }
                    }))
                    .toList();
            if (dreamFiles.size() <= keep) return;
            List<Path> toRemove = dreamFiles.subList(0, dreamFiles.size() - keep);
            for (Path path : toRemove) {
                try { Files.deleteIfExists(path); }
                catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
    }

    // ==================== 工具方法 ====================

    /** 对标 Python strip_think(text)。 */
    private static String stripThink(String text) {
        String s = text;
        if (s.contains("<think") && !s.contains("</think>")) {
            int idx = s.indexOf("<think");
            s = s.substring(0, idx);
        }
        s = s.replaceAll("<channel\\|[^>]*>", "");
        return s.stripTrailing();
    }

    private static String truncateText(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n... (truncated)";
    }

    /**
     * 读取 MEMORY.md 文件内容（若存在且非空）。
     *
     * @return 文件内容，不存在或为空时返回 null
     */
    @Nullable
    public String loadMemoryFile() {
        try {
            if (Files.isRegularFile(memoryFile)) {
                String content = Files.readString(memoryFile).strip();
                return content.isEmpty() ? null : content;
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    // ==================== dream tools ====================

    /**
     * 构建 Dream 运行所需的受限工具注册表（ReadFile/EditFile/ApplyPatch/WriteFile）。
     * 对标 Python MemoryStore.build_dream_tools()。
     */
    public ToolRegistry buildDreamTools() {
        ToolRegistry tools = new ToolRegistry();
        Path skillsDir = workspace.resolve("skills");
        try { Files.createDirectories(skillsDir); } catch (IOException ignored) {}
        com.nanobot.agent.tools.FileStates fileStates = new com.nanobot.agent.tools.FileStates();

        List<Path> extraRead = List.of();
        try {
            Path builtin = Path.of("src/main/resources/skills");
            if (Files.exists(builtin)) extraRead = List.of(builtin);
        } catch (Exception ignored) {}
        List<Path> editableRoots = List.of(soulFile, userFile, skillsDir);

        // 对标 Python: 有限制的工具注册，Dream agent 仅可读写 memory 相关文件
        tools.register(new ReadFileTool(workspace, workspace, workspace,
                extraRead, fileStates, true, false));
        tools.register(new EditFileTool(workspace, memoryDir, workspace,
                editableRoots, fileStates, true, false));
        tools.register(new ApplyPatchTool(workspace, memoryDir, workspace,
                editableRoots, fileStates, true, false));
        tools.register(new WriteFileTool(workspace, skillsDir, workspace,
                editableRoots, fileStates, true, false));
        return tools;
    }

    /** iterValidEntries 内部使用的 pair。 */
    private record Pair(Map<String, Object> entry, int cursor) {}
}
