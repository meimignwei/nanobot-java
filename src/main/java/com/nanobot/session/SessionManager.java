package com.nanobot.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 以 JSONL 文件管理对话会话。
 *
 * <p>JSONL 格式：第 1 行 = metadata（_type: "metadata"），后续行 = 消息记录。
 * 原子写入：写入 .jsonl.tmp → Files.move(ATOMIC_MOVE) → 可选 fsync。
 * 损坏修复：逐行解析，跳过 JSONDecodeError 行。
 *
 * <p>对标 Python {@code nanobot/session/manager.py:391-790 class SessionManager}。
 */
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(java.time.ZoneOffset.UTC);

    private final Path sessionsDir;
    private final Path legacySessionsDir;
    private final ConcurrentHashMap<String, Session> cache;

    /**
     * 创建会话管理器。
     * 对标 Python SessionManager.__init__(workspace)。
     *
     * @param workspacePath 工作区根目录
     */
    public SessionManager(Path workspacePath) {
        this.sessionsDir = createDir(workspacePath.resolve("sessions"));
        this.legacySessionsDir = Path.of(
                System.getProperty("user.home"), ".nanobot", "sessions");
        this.cache = new ConcurrentHashMap<>();
    }

    // ==================== 路径工具 ====================

    /**
     * 将会话键安全化为文件名。
     * 对标 Python {@code safe_key(key)} → 替换 : 和非法字符。
     */
    public static String safeKey(String key) {
        return key.replace(":", "_").replaceAll("[<>: \"/\\\\|?*]", "_");
    }

    /** 获取会话 JSONL 文件路径。 */
    public Path getSessionPath(String key) {
        return sessionsDir.resolve(safeKey(key) + ".jsonl");
    }

    /** 获取旧版会话路径（用于迁移）。 */
    private Path legacyPath(String key) {
        return legacySessionsDir.resolve(safeKey(key) + ".jsonl");
    }

    // ==================== get_or_create ====================

    /**
     * 获取或创建会话（缓存→磁盘→新建）。
     * 直接 put 覆盖缓存（与 Python {@code self._cache[key] = session} 语义一致）。
     * 对标 Python SessionManager.get_or_create(key)。
     */
    public Session getOrCreate(String key) {
        Session cached = cache.get(key);
        if (cached != null) return cached;
        Session session = load(key);
        if (session == null) session = new Session(key);
        cache.put(key, session);
        return session;
    }

    // ==================== load ====================

    /**
     * 从磁盘加载会话，不存在返回 null。
     * 先尝试从旧路径迁移，再解析文件，失败时触发 repair。
     * 对标 Python SessionManager._load(key)。
     */
    private Session load(String key) {
        Path path = getSessionPath(key);
        if (!Files.exists(path)) {
            Path lp = legacyPath(key);
            if (Files.exists(lp)) {
                try {
                    Files.move(lp, path, StandardCopyOption.ATOMIC_MOVE);
                    log.info("Migrated session {} from legacy path", key);
                } catch (IOException e) {
                    log.warn("Failed to migrate session {}: {}", key, e.getMessage());
                }
            }
        }
        if (!Files.exists(path)) return null;
        try {
            return parseFile(key, path);
        } catch (Exception e) {
            log.warn("Failed to load session {}: {}", key, e.getMessage());
            Session repaired = repair(key);
            if (repaired != null) {
                log.info("Recovered session {} from corrupt file ({} messages)",
                        key, repaired.getMessages().size());
            }
            return repaired;
        }
    }

    // ==================== 只读加载（HTTP endpoint） ====================

    /**
     * 从磁盘加载会话（不缓存），用于 HTTP 只读 endpoint。
     * 返回含 key/created_at/updated_at/metadata/messages 的 Map。
     * 对标 Python SessionManager.read_session_file(key)。
     *
     * @param key 会话键
     * @return 会话数据 Map，不存在或解析失败返回 null
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> readSessionFile(String key) {
        Path path = getSessionPath(key);
        if (!Files.exists(path)) return null;
        try {
            List<Map<String, Object>> messages = new ArrayList<>();
            Map<String, Object> metadata = new LinkedHashMap<>();
            String createdAt = null, updatedAt = null, storedKey = null;

            try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.strip();
                    if (line.isEmpty()) continue;
                    Map<String, Object> data = (Map<String, Object>) MAPPER.readValue(line, Map.class);
                    if ("metadata".equals(data.get("_type"))) {
                        metadata = safeMap(data, "metadata");
                        createdAt = safeStr(data, "created_at");
                        updatedAt = safeStr(data, "updated_at");
                        storedKey = safeStr(data, "key");
                    } else {
                        messages.add(data);
                    }
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("key", storedKey != null ? storedKey : key);
            result.put("created_at", createdAt);
            result.put("updated_at", updatedAt);
            result.put("metadata", metadata);
            result.put("messages", messages);
            return result;
        } catch (Exception e) {
            log.warn("Failed to read session {}: {}", key, e.getMessage());
            Session repaired = repair(key);
            if (repaired != null) {
                log.info("Recovered read-only session view {} from corrupt file", key);
                return sessionPayload(repaired);
            }
            return null;
        }
    }

    // ==================== save（原子写入） ====================

    /**
     * 原子写入会话：
     * 1. 写入 metadata 行 + 消息行到 {key}.jsonl.tmp
     * 2. Files.move(tmp, path, ATOMIC_MOVE) — 观察者看到旧文件或新文件，绝无部分写入
     * 3. 可选 fsync 文件 + 目录
     * 异常时删除 .tmp。
     * 对标 Python SessionManager.save(session, *, fsync=False)。
     */
    public void save(Session session, boolean fsync) {
        Path path = getSessionPath(session.getKey());
        Path tmpPath = path.resolveSibling(path.getFileName() + ".tmp");

        try {
            try (BufferedWriter w = Files.newBufferedWriter(tmpPath, StandardCharsets.UTF_8)) {
                // Line 1: metadata
                Map<String, Object> metaLine = new LinkedHashMap<>();
                metaLine.put("_type", "metadata");
                metaLine.put("key", session.getKey());
                metaLine.put("created_at", ISO_FMT.format(
                        session.getCreatedAt().atOffset(java.time.ZoneOffset.UTC)));
                metaLine.put("updated_at", ISO_FMT.format(
                        session.getUpdatedAt().atOffset(java.time.ZoneOffset.UTC)));
                metaLine.put("metadata", session.getMetadata());
                metaLine.put("last_consolidated", session.getLastConsolidated());
                w.write(MAPPER.writeValueAsString(metaLine));
                w.newLine();
                // Subsequent lines: messages
                for (Map<String, Object> msg : session.getMessages()) {
                    w.write(MAPPER.writeValueAsString(msg));
                    w.newLine();
                }
                w.flush();
            }

            // 文件 fsync
            if (fsync) {
                try (FileChannel fc = FileChannel.open(tmpPath, StandardOpenOption.READ)) {
                    fc.force(true);
                }
            }

            // 原子重命名
            Files.move(tmpPath, path,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

            // 目录 fsync（Windows 上跳过——NTFS journal metadata）
            if (fsync) {
                try {
                    FileChannel.open(path.getParent(), StandardOpenOption.READ).force(true);
                } catch (Exception e) {
                    log.debug("Dir fsync skipped: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            try { Files.deleteIfExists(tmpPath); }
            catch (IOException ignored) {}
            throw new UncheckedIOException("Failed to save session " + session.getKey(), e);
        }
        cache.put(session.getKey(), session);
    }

    public void save(Session session) {
        save(session, false);
    }

    // ==================== repair（损坏修复） ====================

    /**
     * 从损坏的 JSONL 中恢复：逐行解析，跳过 JSONDecodeError 行。
     * 仅在无有效数据时返回 null。
     * 对标 Python SessionManager._repair(key)。
     */
    @SuppressWarnings("unchecked")
    private Session repair(String key) {
        Path path = getSessionPath(key);
        if (!Files.exists(path)) return null;
        try {
            List<Map<String, Object>> messages = new ArrayList<>();
            Map<String, Object> metadata = new LinkedHashMap<>();
            String createdStr = null, updatedStr = null;
            int lc = 0, skipped = 0;

            try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.strip();
                    if (line.isEmpty()) continue;
                    Map<String, Object> data;
                    try {
                        data = (Map<String, Object>) MAPPER.readValue(line, Map.class);
                    } catch (Exception e) {
                        skipped++;
                        continue;
                    }
                    if ("metadata".equals(data.get("_type"))) {
                        metadata = safeMap(data, "metadata");
                        createdStr = safeStr(data, "created_at");
                        updatedStr = safeStr(data, "updated_at");
                        Object lcObj = data.get("last_consolidated");
                        lc = (lcObj instanceof Number n) ? n.intValue() : 0;
                    } else {
                        messages.add(data);
                    }
                }
            }
            if (skipped > 0) log.warn("Skipped {} corrupt lines in session {}", skipped, key);
            if (messages.isEmpty() && metadata.isEmpty()) return null;

            Instant ct = parseIso(createdStr), ut = parseIso(updatedStr);
            return new Session(key, messages,
                    ct != null ? ct : Instant.now(),
                    ut != null ? ut : Instant.now(),
                    metadata, lc);
        } catch (Exception e) {
            log.warn("Repair failed for session {}: {}", key, e.getMessage());
            return null;
        }
    }

    // ==================== list ====================

    /**
     * 列出所有会话预览（按 updated_at 降序）。
     * 对标 Python SessionManager.list_sessions()。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listSessions() {
        List<Map<String, Object>> result = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(sessionsDir, "*.jsonl")) {
            for (Path p : ds) {
                String fn = p.getFileName().toString();
                String fk = fn.replace(".jsonl", "").replaceFirst("_", ":");
                try {
                    Map<String, Object> info = readPreview(p, fk);
                    if (info != null) result.add(info);
                } catch (Exception e) {
                    Session repaired = repair(fk);
                    if (repaired != null) result.add(previewFromRepaired(repaired, p));
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list sessions: {}", e.getMessage());
        }
        result.sort(Comparator.comparing(
                m -> Objects.toString(m.get("updated_at"), ""), Comparator.reverseOrder()));
        return result;
    }

    // ==================== delete / invalidate / flush ====================

    /**
     * 删除会话文件和缓存。
     * 对标 Python SessionManager.delete_session(key)。
     */
    public boolean deleteSession(String key) {
        invalidate(key);
        try {
            return Files.deleteIfExists(getSessionPath(key));
        } catch (IOException e) {
            log.warn("Failed to delete {}: {}", key, e.getMessage());
            return false;
        }
    }

    /**
     * 仅清除缓存，不影响磁盘文件。
     * 对标 Python SessionManager.invalidate(key)。
     */
    public void invalidate(String key) {
        cache.remove(key);
    }

    /**
     * 将缓存中所有会话 fsync 写出，返回成功写出的数量。
     * 对标 Python SessionManager.flush_all()。
     */
    public int flushAll() {
        int n = 0;
        for (Map.Entry<String, Session> e : cache.entrySet()) {
            try {
                save(e.getValue(), true);
                n++;
            } catch (Exception ex) {
                log.warn("Failed to flush session {}", e.getKey(), ex);
            }
        }
        return n;
    }

    // ==================== fork ====================

    /**
     * 在指定 user 索引处分叉会话。
     * 对标 Python SessionManager.fork_session_before_user_index(srcKey, tgtKey, beforeUserIdx)。
     *
     * @param srcKey        源会话键
     * @param tgtKey        目标会话键
     * @param beforeUserIdx user 索引（0-based）
     * @return 分叉后的新会话，失败返回 null
     */
    @SuppressWarnings("unchecked")
    public Session forkSessionBeforeUserIndex(String srcKey, String tgtKey, int beforeUserIdx) {
        if (beforeUserIdx < 0) return null;
        Session source = cache.get(srcKey);
        if (source == null) source = load(srcKey);
        if (source == null) return null;

        List<Map<String, Object>> copied = new ArrayList<>();
        int userIdx = 0;
        boolean found = false;
        for (Map<String, Object> msg : source.getMessages()) {
            if ("user".equals(msg.get("role"))) {
                if (userIdx == beforeUserIdx) { found = true; break; }
                userIdx++;
            }
            copied.add(deepCopy(msg));
        }
        if (userIdx == beforeUserIdx) found = true; // fork at end
        if (!found) return null;

        Map<String, Object> meta = deepCopy(source.getMetadata());
        for (String k : SessionConstants.FORK_VOLATILE_METADATA_KEYS) {
            meta.remove(k);
        }
        int lc = Math.min(source.getLastConsolidated(), copied.size());
        if (source.getLastConsolidated() > copied.size()) {
            meta.remove("_last_summary");
            lc = 0;
        }

        Instant now = Instant.now();
        Session target = new Session(tgtKey, copied, now, now, meta, lc);
        save(target, true);
        return target;
    }

    // ==================== 内部方法 ====================

    @SuppressWarnings("unchecked")
    private Session parseFile(String key, Path path) throws IOException {
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> metadata = new LinkedHashMap<>();
        String createdStr = null, updatedStr = null;
        int lc = 0;

        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;
                Map<String, Object> data = (Map<String, Object>) MAPPER.readValue(line, Map.class);
                if ("metadata".equals(data.get("_type"))) {
                    metadata = safeMap(data, "metadata");
                    createdStr = safeStr(data, "created_at");
                    updatedStr = safeStr(data, "updated_at");
                    Object lcObj = data.get("last_consolidated");
                    lc = (lcObj instanceof Number n) ? n.intValue() : 0;
                } else {
                    messages.add(data);
                }
            }
        }
        Instant ct = parseIso(createdStr), ut = parseIso(updatedStr);
        return new Session(key, messages,
                ct != null ? ct : Instant.now(),
                ut != null ? ut : Instant.now(),
                metadata, lc);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPreview(Path path, String fallbackKey) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String fl = r.readLine();
            if (fl == null) return null;
            Map<String, Object> data = (Map<String, Object>) MAPPER.readValue(fl.strip(), Map.class);
            if (!"metadata".equals(data.get("_type"))) return null;

            String key = Objects.toString(data.get("key"), fallbackKey);
            Map<String, Object> meta = safeMap(data, "metadata");
            String title = SessionSanitizer.metadataTitle(meta);
            String preview = "", fallback = "";
            int records = 0, chars = 0;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.strip().isEmpty()) continue;
                records++;
                chars += line.length();
                if (records > 200 || chars > 1_000_000) break;
                Map<String, Object> item = (Map<String, Object>) MAPPER.readValue(line, Map.class);
                if ("metadata".equals(item.get("_type"))) continue;
                String text = SessionSanitizer.messagePreviewText(item);
                if (text.isEmpty()) continue;
                if ("user".equals(item.get("role"))) { preview = text; break; }
                if (fallback.isEmpty() && "assistant".equals(item.get("role"))) fallback = text;
            }
            preview = preview.isEmpty() ? fallback : preview;
            return Map.of("key", key,
                    "created_at", data.get("created_at"),
                    "updated_at", data.get("updated_at"),
                    "title", title,
                    "preview", preview,
                    "path", path.toString());
        }
    }

    private Map<String, Object> previewFromRepaired(Session s, Path path) {
        String preview = "";
        for (Map<String, Object> m : s.getMessages()) {
            String t = SessionSanitizer.messagePreviewText(m);
            if (!t.isEmpty()) { preview = t; break; }
        }
        return Map.of("key", s.getKey(),
                "created_at", ISO_FMT.format(s.getCreatedAt().atOffset(java.time.ZoneOffset.UTC)),
                "updated_at", ISO_FMT.format(s.getUpdatedAt().atOffset(java.time.ZoneOffset.UTC)),
                "title", SessionSanitizer.metadataTitle(s.getMetadata()),
                "preview", preview,
                "path", path.toString());
    }

    private Map<String, Object> sessionPayload(Session s) {
        return Map.of("key", s.getKey(),
                "created_at", ISO_FMT.format(s.getCreatedAt().atOffset(java.time.ZoneOffset.UTC)),
                "updated_at", ISO_FMT.format(s.getUpdatedAt().atOffset(java.time.ZoneOffset.UTC)),
                "metadata", s.getMetadata(),
                "messages", s.getMessages());
    }

    // ==================== 静态工具 ====================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> safeMap(Map<String, Object> d, String k) {
        Object v = d.get(k);
        return (v instanceof Map) ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    private static String safeStr(Map<String, Object> d, String k) {
        Object v = d.get(k);
        return (v instanceof String) ? (String) v : null;
    }

    private static Instant parseIso(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return OffsetDateTime.parse(s, ISO_FMT).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> src) {
        try {
            return (Map<String, Object>) MAPPER.readValue(
                    MAPPER.writeValueAsString(src), Map.class);
        } catch (IOException e) {
            return new LinkedHashMap<>(src);
        }
    }

    private static Path createDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return dir;
    }
}
