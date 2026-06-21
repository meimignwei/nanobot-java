package com.nanobot.agent.autocompact;

import com.nanobot.agent.context.Consolidator;
import com.nanobot.agent.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 空闲会话自动压缩——TTL 过期后触发 Consolidator 归档。
 * 对应 Python AutoCompact（agent/autocompact.py:17-66）。
 *
 * <p>职责：定期检查会话更新时间，对超过 session_ttl_minutes 的空闲会话
 * 触发 compact_idle_session，生成摘要并清理旧消息。</p>
 */
public class AutoCompact {

    private static final Logger log = LoggerFactory.getLogger(AutoCompact.class);

    /** 归档时保留的最近消息数 */
    private static final int RECENT_SUFFIX_MESSAGES = 8;
    /** 内部 session 前缀（跳过自动压缩） */
    private static final Set<String> INTERNAL_SESSION_PREFIXES = Set.of("dream:");

    private final SessionManager sessions;
    private final Consolidator consolidator;
    private final int ttlMinutes;
    private final Set<String> archiving = ConcurrentHashMap.newKeySet();

    public AutoCompact(SessionManager sessions, Consolidator consolidator, int sessionTtlMinutes) {
        this.sessions = sessions;
        this.consolidator = consolidator;
        this.ttlMinutes = sessionTtlMinutes;
    }

    /** 判断会话是否过期。
     *  对应 Python AutoCompact._is_expired()。 */
    boolean isExpired(Instant updatedAt, Instant now) {
        if (ttlMinutes <= 0 || updatedAt == null) return false;
        return Duration.between(updatedAt, now != null ? now : Instant.now()).toMinutes() >= ttlMinutes;
    }

    /** 判断是否为内部会话（跳过自动压缩）。
     *  对应 Python AutoCompact._is_internal_session()。 */
    static boolean isInternalSession(String key) {
        if (key == null) return false;
        for (var prefix : INTERNAL_SESSION_PREFIXES) {
            if (key.startsWith(prefix)) return true;
        }
        return false;
    }

    /** 检查过期会话并调度归档。
     *  对应 Python AutoCompact.check_expired()。 */
    public void checkExpired(Consumer<Runnable> scheduleBackground,
                              Collection<String> activeSessionKeys) {
        var now = Instant.now();
        for (var info : sessions.listSessions()) {
            var key = (String) info.get("key");
            if (key == null || isInternalSession(key) || archiving.contains(key)) continue;
            if (activeSessionKeys != null && activeSessionKeys.contains(key)) continue;

            var updatedAtRaw = info.get("updated_at");
            Instant updatedAt = null;
            if (updatedAtRaw instanceof Instant inst) {
                updatedAt = inst;
            } else if (updatedAtRaw instanceof String s) {
                try { updatedAt = Instant.parse(s); } catch (Exception ignored) {}
            }

            if (isExpired(updatedAt, now)) {
                archiving.add(key);
                scheduleBackground.accept(() -> archive(key));
            }
        }
    }

    /** 归档单个空闲会话。
     *  对应 Python AutoCompact._archive()。 */
    void archive(String key) {
        if (isInternalSession(key)) {
            archiving.remove(key);
            return;
        }
        try {
            var summary = consolidator.compactIdleSession(key, RECENT_SUFFIX_MESSAGES);
            if (summary != null && !summary.isEmpty()) {
                log.info("Auto-compacted session {}: summary generated", key);
            }
        } catch (Exception e) {
            log.warn("Auto-compact failed for session {}", key, e);
        } finally {
            archiving.remove(key);
        }
    }
}
