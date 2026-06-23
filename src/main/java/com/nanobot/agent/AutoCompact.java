package com.nanobot.agent;

import com.nanobot.session.Session;
import com.nanobot.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 空闲会话主动压缩——当 session 空闲超过 ttl 时，调度后台 consolidation，
 * 减少后续 turn 的 token 消耗和延迟。
 *
 * <p>对标 Python {@code nanobot/agent/autocompact.py:17-96 class AutoCompact}。
 */
public class AutoCompact {

    private static final Logger log = LoggerFactory.getLogger(AutoCompact.class);

    /** 对标 Python _RECENT_SUFFIX_MESSAGES = 8。 */
    private static final int RECENT_SUFFIX_MESSAGES = 8;

    /** 对标 Python _INTERNAL_SESSION_PREFIXES = ("dream:",)。 */
    private static final Set<String> INTERNAL_SESSION_PREFIXES = Set.of("dream:");

    private final SessionManager sessions;
    private final Consolidator consolidator;
    private final int ttlMinutes;

    /** 正在归档的 session 键集合（防止重复调度）。对标 Python self._archiving。 */
    private final Set<String> archiving = ConcurrentHashMap.newKeySet();

    /**
     * 内存摘要缓存。
     * Hot path: 进程未重启时直接从内存获取。
     * Cold path: 进程重启后从 session metadata._last_summary 恢复。
     * 对标 Python self._summaries: dict[str, (str, datetime)]。
     */
    private final Map<String, Map.Entry<String, Instant>> summaries = new ConcurrentHashMap<>();

    /**
     * @param sessions          会话管理器
     * @param consolidator      整合器
     * @param sessionTtlMinutes 空闲 ttl（0 禁用）
     */
    public AutoCompact(SessionManager sessions, Consolidator consolidator, int sessionTtlMinutes) {
        this.sessions = sessions;
        this.consolidator = consolidator;
        this.ttlMinutes = sessionTtlMinutes;
    }

    // ==================== 过期判断 ====================

    /**
     * 检查时间戳是否过期。
     * 对标 Python _is_expired(ts, now)。
     */
    public boolean isExpired(@Nullable Object ts, Instant now) {
        if (ttlMinutes <= 0 || ts == null) return false;
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
        return java.time.Duration.between(timestamp, effectiveNow).getSeconds()
                >= ttlMinutes * 60L;
    }

    // ==================== 摘要格式化 ====================

    /**
     * 格式化摘要文本。
     * 对标 Python _format_summary(text, last_active)。
     */
    public static String formatSummary(String text, Instant lastActive) {
        return "Previous conversation summary (last active " + lastActive + "):\n" + text;
    }

    /**
     * 判断是否内部会话键（dream 等）。
     * 对标 Python _is_internal_session(key)。
     */
    public static boolean isInternalSession(@Nullable String key) {
        if (key == null) return false;
        return INTERNAL_SESSION_PREFIXES.stream().anyMatch(key::startsWith);
    }

    // ==================== 过期检查 ====================

    /**
     * 遍历所有会话，对空闲超时的非活跃会话调度后台归档。
     * 对标 Python check_expired(schedule_background, active_session_keys=())。
     *
     * @param scheduleBackground 后台任务调度回调（接受 Runnable）
     * @param activeSessionKeys  当前活跃的 session key 集合
     */
    public void checkExpired(Consumer<CompletableFuture<Void>> scheduleBackground,
                             Collection<String> activeSessionKeys) {
        Instant now = Instant.now();
        for (Map<String, Object> info : sessions.listSessions()) {
            String key = (String) info.getOrDefault("key", "");
            if (key.isEmpty() || isInternalSession(key) || archiving.contains(key)) {
                continue;
            }
            if (activeSessionKeys.contains(key)) continue;
            Object updatedAt = info.get("updated_at");
            if (isExpired(updatedAt, now)) {
                archiving.add(key);
                scheduleBackground.accept(archive(key));
            }
        }
    }

    // ==================== 归档 ====================

    /**
     * 归档单个空闲会话。
     * 对标 Python _archive(key) async。
     *
     * @param key 会话键
     * @return 归档完成后完成的 future
     */
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
                        if (meta instanceof Map<?, ?> map
                                && map.get("text") instanceof String text
                                && map.get("last_active") instanceof String lastActiveStr) {
                            try {
                                Instant lastActive = Instant.parse(lastActiveStr);
                                summaries.put(key, Map.entry(text, lastActive));
                            } catch (DateTimeParseException ignored) {}
                        }
                    }
                })
                .exceptionally(ex -> {
                    log.error("Auto-compact: failed for {}", key, ex);
                    return null;
                })
                .thenRun(() -> archiving.remove(key));
    }

    // ==================== prepare_session ====================

    /**
     * 准备会话（检查是否需要重新加载，返回会话 + 可选的摘要文本）。
     * 对标 Python prepare_session(session, key)。
     *
     * @param session 当前会话
     * @param key     会话键
     * @return (session, summary) — summary 为 null 时无需注入
     */
    public Map.Entry<Session, String> prepareSession(Session session, String key) {
        if (isInternalSession(key)) {
            archiving.remove(key);
            summaries.remove(key);
            return Map.entry(session, null);
        }
        // 如果正在归档或已过期，重新加载
        if (archiving.contains(key) || isExpired(session.getUpdatedAt(), null)) {
            log.info("Auto-compact: reloading session {} (archiving={})",
                    key, archiving.contains(key));
            session = sessions.getOrCreate(key);
        }
        // Hot path: 内存字典
        Map.Entry<String, Instant> entry = summaries.remove(key);
        if (entry != null) {
            return Map.entry(session, formatSummary(entry.getKey(), entry.getValue()));
        }
        // Cold path: session metadata
        Object meta = session.getMetadata().get("_last_summary");
        if (meta instanceof Map<?, ?> map
                && map.get("text") instanceof String text
                && map.get("last_active") instanceof String lastActiveStr) {
            try {
                Instant lastActive = Instant.parse(lastActiveStr);
                return Map.entry(session, formatSummary(text, lastActive));
            } catch (DateTimeParseException ignored) {}
        }
        return Map.entry(session, null);
    }
}
