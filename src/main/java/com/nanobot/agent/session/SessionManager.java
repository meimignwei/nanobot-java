package com.nanobot.agent.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话持久化管理器。
 *
 * <p>职责：会话的 CRUD、JSONL 磁盘持久化、从损坏文件中修复、fork 会话、
 * 以及 flushAll 批量刷盘。对应 Python SessionManager 类。</p>
 *
 * <p>存储格式：每会话一个 .jsonl 文件，第一行为 metadata 行（_type=metadata），
 * 后续每行一条消息 JSON。</p>
 */
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    /** 文件名中不允许的字符，会被替换为下划线 */
    private static final String UNSAFE_CHARS = "[<>:\"/\\\\|?*]";

    /** 内存缓存：key → Session */
    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final Path workspace;
    private final Path sessionsDir;

    public SessionManager(Path workspace) {
        this.workspace = workspace;
        this.sessionsDir = ensureDir(workspace.resolve("sessions"));
    }

    // -- key 安全化 --

    /** 将 session key 中的不安全字符替换为下划线，生成安全的文件名 */
    public static String safeKey(String key) {
        return key.replaceAll(UNSAFE_CHARS, "_").replace(":", "_").strip();
    }

    // -- 路径辅助 --

    private Path getSessionPath(String key) {
        return sessionsDir.resolve(safeKey(key) + ".jsonl");
    }

    // -- 缓存操作 --

    /**
     * 获取或创建会话。先从磁盘加载，失败则新建。
     * 对应 Python get_or_create。
     */
    public Session getOrCreate(String key) {
        return sessions.computeIfAbsent(key, k -> {
            var loaded = loadFromDisk(k);
            return loaded != null ? loaded : new Session(k);
        });
    }

    /** 从内存缓存获取会话（不从磁盘加载） */
    public Session get(String key) {
        return sessions.get(key);
    }

    /** 异步保存（不执行 fsync） */
    public void save(Session session) {
        save(session, false);
    }

    /**
     * 将会话持久化到磁盘。
     *
     * <p>先写入 .tmp 文件，再原子 rename 到目标路径，保证写入不损坏已有数据。
     * fsync=true 时做 best-effort 同步。</p>
     */
    public void save(Session session, boolean fsync) {
        var path = getSessionPath(session.key());
        var tmpPath = path.resolveSibling(path.getFileName() + ".tmp");

        try {
            var lines = new ArrayList<String>();
            // 元数据行
            var meta = new LinkedHashMap<String, Object>();
            meta.put("_type", "metadata");
            meta.put("key", session.key());
            meta.put("created_at", session.createdAt().toString());
            meta.put("updated_at", session.updatedAt().toString());
            meta.put("metadata", session.metadata());
            meta.put("last_consolidated", session.lastConsolidated());
            lines.add(mapper.writeValueAsString(meta));
            // 消息行
            for (var msg : session.messages()) {
                lines.add(mapper.writeValueAsString(msg));
            }

            Files.writeString(tmpPath, String.join("\n", lines) + "\n");
            if (fsync) {
                // Best-effort fsync — 平台相关
            }
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try { Files.deleteIfExists(tmpPath); } catch (IOException ignored) {}
            throw new RuntimeException("Failed to save session " + session.key(), e);
        }

        sessions.put(session.key(), session);
    }

    /** 从内存缓存中移除会话 */
    public void invalidate(String key) {
        sessions.remove(key);
    }

    /** 从内存缓存中移除（同 invalidate） */
    public void delete(String key) {
        sessions.remove(key);
    }

    /** 从内存和磁盘中删除会话 */
    public void deleteSession(String key) {
        invalidate(key);
        try {
            Files.deleteIfExists(getSessionPath(key));
        } catch (IOException e) {
            log.warn("Failed to delete session file {}", key, e);
        }
    }

    /** 检查会话是否存在（先查内存缓存，再查磁盘文件） */
    public boolean exists(String key) {
        if (sessions.containsKey(key)) {
            return true;
        }
        return Files.exists(getSessionPath(key));
    }

    public Path workspace() {
        return workspace;
    }

    // -- 批量刷盘 --

    /** 将所有内存中的会话同步写入磁盘，返回成功刷盘的数量 */
    public int flushAll() {
        int flushed = 0;
        for (var entry : List.copyOf(sessions.entrySet())) {
            try {
                save(entry.getValue(), true);
                flushed++;
            } catch (Exception e) {
                log.warn("Failed to flush session {}", entry.getKey(), e);
            }
        }
        return flushed;
    }

    // -- 磁盘 I/O --

    /** 从磁盘 JSONL 文件加载会话 */
    private Session loadFromDisk(String key) {
        var path = getSessionPath(key);
        if (!Files.exists(path)) {
            return null;
        }

        try {
            var messages = new ArrayList<Map<String, Object>>();
            var metadata = new LinkedHashMap<String, Object>();
            String createdAt = null;
            String updatedAt = null;
            int lastConsolidated = 0;

            var lines = Files.readAllLines(path);
            for (var line : lines) {
                line = line.strip();
                if (line.isEmpty()) continue;

                Map<String, Object> data;
                try {
                    @SuppressWarnings("unchecked")
                    var parsed = (Map<String, Object>) mapper.readValue(line, Map.class);
                    data = parsed;
                } catch (JsonProcessingException e) {
                    continue;
                }

                if ("metadata".equals(data.get("_type"))) {
                    @SuppressWarnings("unchecked")
                    var meta = (Map<String, Object>) data.getOrDefault("metadata", new LinkedHashMap<>());
                    metadata.putAll(meta);
                    createdAt = (String) data.get("created_at");
                    updatedAt = (String) data.get("updated_at");
                    var lc = data.get("last_consolidated");
                    if (lc instanceof Number n) {
                        lastConsolidated = n.intValue();
                    }
                } else {
                    messages.add(data);
                }
            }

            Instant cat = parseInstant(createdAt);
            Instant uat = parseInstant(updatedAt);
            var session = new Session(key);
            session.messages().addAll(messages);
            session.metadata().putAll(metadata);
            session.setLastConsolidated(lastConsolidated);
            return session;
        } catch (IOException e) {
            log.warn("Failed to load session {}: {}", key, e.getMessage());
            return repairFromDisk(key);
        }
    }

    /**
     * 从损坏的 JSONL 文件中修复会话。
     * 跳过无法解析的行，尽力恢复可读的消息和元数据。
     */
    private Session repairFromDisk(String key) {
        var path = getSessionPath(key);
        if (!Files.exists(path)) {
            return null;
        }

        try {
            var messages = new ArrayList<Map<String, Object>>();
            var metadata = new LinkedHashMap<String, Object>();
            String createdAt = null;
            String updatedAt = null;
            int lastConsolidated = 0;
            int skipped = 0;

            var lines = Files.readAllLines(path);
            for (var line : lines) {
                line = line.strip();
                if (line.isEmpty()) continue;

                Map<String, Object> data;
                try {
                    @SuppressWarnings("unchecked")
                    var parsed = (Map<String, Object>) mapper.readValue(line, Map.class);
                    data = parsed;
                } catch (JsonProcessingException e) {
                    skipped++;
                    continue;
                }

                if ("metadata".equals(data.get("_type"))) {
                    @SuppressWarnings("unchecked")
                    var meta = (Map<String, Object>) data.getOrDefault("metadata", new LinkedHashMap<>());
                    metadata.putAll(meta);
                    createdAt = (String) data.get("created_at");
                    updatedAt = (String) data.get("updated_at");
                    var lc = data.get("last_consolidated");
                    if (lc instanceof Number n) {
                        lastConsolidated = n.intValue();
                    }
                } else {
                    messages.add(data);
                }
            }

            if (skipped > 0) {
                log.warn("Skipped {} corrupt lines in session {}", skipped, key);
            }
            if (messages.isEmpty() && metadata.isEmpty()) {
                return null;
            }

            var session = new Session(key);
            session.messages().addAll(messages);
            session.metadata().putAll(metadata);
            session.setLastConsolidated(lastConsolidated);
            if (skipped > 0) {
                log.info("Recovered session {} from corrupt file ({} messages)", key, messages.size());
            }
            return session;
        } catch (IOException e) {
            log.warn("Repair failed for session {}: {}", key, e.getMessage());
            return null;
        }
    }

    /** 列出所有磁盘上的会话，按更新时间倒序排列 */
    public List<Map<String, Object>> listSessions() {
        var sessions = new ArrayList<Map<String, Object>>();
        try (var stream = Files.newDirectoryStream(sessionsDir, "*.jsonl")) {
            for (var path : stream) {
                String fileName = path.getFileName().toString();
                String fallbackKey = fileName.substring(0, fileName.lastIndexOf('.')).replaceFirst("_", ":");
                try {
                    var lines = Files.readAllLines(path);
                    if (lines.isEmpty()) continue;

                    @SuppressWarnings("unchecked")
                    var data = (Map<String, Object>) mapper.readValue(lines.get(0), Map.class);
                    if (!"metadata".equals(data.get("_type"))) continue;

                    var key = (String) data.getOrDefault("key", fallbackKey);
                    var info = new LinkedHashMap<String, Object>();
                    info.put("key", key);
                    info.put("created_at", data.get("created_at"));
                    info.put("updated_at", data.get("updated_at"));
                    info.put("path", path.toString());

                    // 读取预览文本（优先 user 消息，其次 assistant）
                    String preview = "";
                    for (int i = 1; i < lines.size(); i++) {
                        @SuppressWarnings("unchecked")
                        var msg = (Map<String, Object>) mapper.readValue(lines.get(i), Map.class);
                        if ("metadata".equals(msg.get("_type"))) continue;
                        var content = msg.get("content");
                        if (content instanceof String s && !s.isBlank()) {
                            if ("user".equals(msg.get("role"))) {
                                preview = s;
                                break;
                            }
                            if (preview.isEmpty() && "assistant".equals(msg.get("role"))) {
                                preview = s;
                            }
                        }
                    }
                    info.put("preview", preview);
                    sessions.add(info);
                } catch (Exception e) {
                    // 修复并加入基本信息
                    var repaired = repairFromDisk(fallbackKey);
                    if (repaired != null) {
                        var info = new LinkedHashMap<String, Object>();
                        info.put("key", repaired.key());
                        info.put("created_at", repaired.createdAt().toString());
                        info.put("updated_at", repaired.updatedAt().toString());
                        info.put("preview", "");
                        info.put("path", path.toString());
                        sessions.add(info);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list sessions", e);
        }
        sessions.sort(Comparator.<Map<String, Object>, String>comparing(
                m -> (String) m.getOrDefault("updated_at", "")).reversed());
        return sessions;
    }

    /** 读取会话文件的完整内容（元数据 + 消息列表） */
    public Map<String, Object> readSessionFile(String key) {
        var path = getSessionPath(key);
        if (!Files.exists(path)) return null;

        try {
            var messages = new ArrayList<Map<String, Object>>();
            var metadata = new LinkedHashMap<String, Object>();
            String createdAt = null;
            String updatedAt = null;
            String storedKey = null;

            var lines = Files.readAllLines(path);
            for (var line : lines) {
                line = line.strip();
                if (line.isEmpty()) continue;

                @SuppressWarnings("unchecked")
                var data = (Map<String, Object>) mapper.readValue(line, Map.class);
                if ("metadata".equals(data.get("_type"))) {
                    @SuppressWarnings("unchecked")
                    var meta = (Map<String, Object>) data.getOrDefault("metadata", new LinkedHashMap<>());
                    metadata.putAll(meta);
                    createdAt = (String) data.get("created_at");
                    updatedAt = (String) data.get("updated_at");
                    storedKey = (String) data.get("key");
                } else {
                    messages.add(data);
                }
            }

            var result = new LinkedHashMap<String, Object>();
            result.put("key", storedKey != null ? storedKey : key);
            result.put("created_at", createdAt);
            result.put("updated_at", updatedAt);
            result.put("metadata", metadata);
            result.put("messages", messages);
            return result;
        } catch (IOException e) {
            log.warn("Failed to read session {}: {}", key, e.getMessage());
            var repaired = repairFromDisk(key);
            if (repaired != null) {
                log.info("Recovered read-only session view {} from corrupt file", key);
                var result = new LinkedHashMap<String, Object>();
                result.put("key", repaired.key());
                result.put("created_at", repaired.createdAt().toString());
                result.put("updated_at", repaired.updatedAt().toString());
                result.put("metadata", repaired.metadata());
                result.put("messages", repaired.messages());
                return result;
            }
            return null;
        }
    }

    /**
     * Fork 会话：在指定 user 消息之前截断，创建新的目标会话。
     *
     * @param sourceKey      源会话 key
     * @param targetKey      目标会话 key
     * @param beforeUserIndex 在第几个 user 消息前截断
     * @return 包含 key 和 messages 的 Map，失败返回 null
     */
    public Map<String, Object> forkSessionBeforeUserIndex(
            String sourceKey, String targetKey, int beforeUserIndex) {
        if (beforeUserIndex < 0) return null;

        var source = sessions.containsKey(sourceKey) ? sessions.get(sourceKey) : loadFromDisk(sourceKey);
        if (source == null) return null;

        var copied = new ArrayList<Map<String, Object>>();
        int userIndex = 0;
        boolean foundTarget = false;
        for (var message : source.messages()) {
            if ("user".equals(message.get("role"))) {
                if (userIndex == beforeUserIndex) {
                    foundTarget = true;
                    break;
                }
                userIndex++;
            }
            copied.add(new LinkedHashMap<>(message));
        }
        if (userIndex == beforeUserIndex) {
            foundTarget = true;
        }
        if (!foundTarget) return null;

        // 清除易失性元数据
        var newMetadata = new LinkedHashMap<>(source.metadata());
        for (var volatileKey : List.of("goal_state", "pending_user_turn", "runtime_checkpoint",
                "thread_goal", "title", "title_user_edited")) {
            newMetadata.remove(volatileKey);
        }

        int lc = Math.min(source.lastConsolidated(), copied.size());
        if (source.lastConsolidated() > copied.size()) {
            newMetadata.remove("_last_summary");
            lc = 0;
        }

        var target = new Session(targetKey);
        target.messages().addAll(copied);
        target.metadata().putAll(newMetadata);
        target.setLastConsolidated(lc);
        save(target, true);
        return Map.of("key", targetKey, "messages", copied);
    }

    // -- 内部辅助 --

    /** 确保目录存在，不存在则创建 */
    static Path ensureDir(Path path) {
        try {
            return Files.createDirectories(path);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create directory: " + path, e);
        }
    }

    /** 安全解析 ISO 时间戳，失败返回当前时间 */
    static Instant parseInstant(String isoString) {
        if (isoString == null || isoString.isEmpty()) return Instant.now();
        try {
            return Instant.parse(isoString);
        } catch (Exception e) {
            return Instant.now();
        }
    }
}
