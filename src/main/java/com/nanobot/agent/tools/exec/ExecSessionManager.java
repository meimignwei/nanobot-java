package com.nanobot.agent.tools.exec;

import com.nanobot.agent.tools.impl.ExecTool;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 管理最多 8 个并发的长时间运行 exec 会话。
 * 每个会话包装一个带有异步 stdout/stderr 读取器的 Process，
 * 支持 stdin 写入、EOF 关闭、终止和带 yield 的轮询。
 *
 * <p>对标 Python {@code exec_session.py ExecSessionManager}（lines 178-311）。
 * 所有公共方法返回 {@link CompletableFuture}，对应 Python {@code async def}。
 */
public class ExecSessionManager {

    private static final int MAX_SESSIONS = 8;
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

    /** 默认 yield 毫秒数。 */
    // 对标 Python DEFAULT_YIELD_MS
    public static final int DEFAULT_YIELD_MS = 1000;
    /** 最大 yield 毫秒数。 */
    // 对标 Python MAX_YIELD_MS
    public static final int MAX_YIELD_MS = 30_000;
    /** 默认最大输出字符数。 */
    // 对标 Python DEFAULT_MAX_OUTPUT_CHARS
    public static final int DEFAULT_MAX_OUTPUT_CHARS = 10_000;
    /** 最大输出字符数。 */
    // 对标 Python MAX_OUTPUT_CHARS
    public static final int MAX_OUTPUT_CHARS = 50_000;

    private final ConcurrentHashMap<String, ExecSession> sessions = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public ExecSessionManager() {}

    /**
     * 启动新 exec 会话，创建进程并返回 session_id + 初始轮询结果。
     * 对标 Python {@code async def start(...)}。
     *
     * @param command         待执行的命令
     * @param cwd             工作目录
     * @param env             环境变量
     * @param timeout         超时秒数（null 表示无限制）
     * @param shellProgram    指定 shell 程序（可为 null）
     * @param login           是否使用登录 shell
     * @param yieldTimeMs     yield 等待毫秒数
     * @param maxOutputChars  最大输出字符数
     * @param ownerSessionKey 所属 session key
     * @return StartResult（sessionId + SessionPoll）的 CompletableFuture
     */
    // 对标 Python ExecSessionManager.start()
    public CompletableFuture<StartResult> start(
            String command, Path cwd, Map<String, String> env,
            Integer timeout, String shellProgram, boolean login,
            int yieldTimeMs, int maxOutputChars,
            String ownerSessionKey) {

        return cleanupStale().thenCompose(unused -> CompletableFuture.supplyAsync(() -> {
            lock.lock();
            try {
                if (sessions.size() >= MAX_SESSIONS) {
                    throw new RuntimeException(
                            "maximum exec sessions reached (" + MAX_SESSIONS + ")");
                }
                return true;
            } finally {
                lock.unlock();
            }
        })).thenCompose(unused ->
                ExecTool.spawn(command, cwd, env, shellProgram, login, true)
        ).thenCompose(process -> {
            String sessionId = UUID.randomUUID().toString()
                    .replace("-", "").substring(0, 12);
            ExecSession session = new ExecSession(
                    sessionId, process, command, cwd.toString(),
                    timeout, ownerSessionKey);
            lock.lock();
            try { sessions.put(sessionId, session); }
            finally { lock.unlock(); }
            return session.poll(yieldTimeMs, maxOutputChars)
                    .thenApply(poll -> {
                        if (poll.done()) {
                            lock.lock();
                            try { sessions.remove(sessionId); }
                            finally { lock.unlock(); }
                        }
                        return new StartResult(sessionId, poll);
                    });
        });
    }

    /**
     * 向现有会话写入 stdin / 轮询 / 关闭 / 终止。
     * 对标 Python {@code async def write(...)}。
     *
     * @param sessionId       会话 ID
     * @param chars           待写入的字符串（可为 null）
     * @param closeStdin      是否关闭 stdin
     * @param terminate       是否终止进程
     * @param yieldTimeMs     yield 等待毫秒数
     * @param maxOutputChars  最大输出字符数
     * @param ownerSessionKey 所属 session key
     * @return 轮询结果的 CompletableFuture
     */
    // 对标 Python ExecSessionManager.write()
    public CompletableFuture<SessionPoll> interact(
            String sessionId, String chars, boolean closeStdin,
            boolean terminate, int yieldTimeMs, int maxOutputChars,
            String ownerSessionKey) {
        return cleanupStale().thenCompose(unused -> CompletableFuture.supplyAsync(() -> {
            lock.lock();
            try {
                ExecSession session = sessions.get(sessionId);
                if (session == null) throw new NoSuchElementException(sessionId);
                if (ownerSessionKey != null && session.ownerSessionKey() != null
                        && !session.ownerSessionKey().equals(ownerSessionKey)) {
                    throw new NoSuchElementException(sessionId);
                }
                return session;
            } finally {
                lock.unlock();
            }
        })).thenCompose(session -> {
            CompletableFuture<String> writeFuture = (chars != null && !chars.isEmpty())
                    ? session.write(chars)
                    : CompletableFuture.completedFuture(null);
            return writeFuture.thenCompose(writeError -> {
                if (writeError != null) throw new RuntimeException(writeError);
                CompletableFuture<Void> closeFuture = closeStdin
                        ? session.closeStdin() : CompletableFuture.completedFuture(null);
                return closeFuture.thenCompose(closeUnused -> {
                    CompletableFuture<Void> killFuture = terminate
                            ? session.kill() : CompletableFuture.completedFuture(null);
                    return killFuture.thenCompose(killUnused -> session.poll(
                            yieldTimeMs, maxOutputChars, terminate, closeStdin)
                            .thenApply(poll -> {
                                if (poll.done()) {
                                    lock.lock();
                                    try { sessions.remove(sessionId); }
                                    finally { lock.unlock(); }
                                }
                                return poll;
                            }));
                });
            });
        });
    }

    /**
     * 列出活跃会话。
     * 对标 Python {@code async def list(...)}。
     *
     * @param ownerSessionKey 所属 session key（null 表示返回全部）
     * @return 会话摘要信息列表的 CompletableFuture
     */
    // 对标 Python ExecSessionManager.list()
    public CompletableFuture<List<ExecSessionInfo>> list(String ownerSessionKey) {
        return cleanupStale().thenApply(unused -> {
            lock.lock();
            try {
                Instant now = Instant.now();
                return sessions.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .filter(e -> ownerSessionKey == null
                                || e.getValue().ownerSessionKey() == null
                                || e.getValue().ownerSessionKey().equals(ownerSessionKey))
                        .map(e -> e.getValue().toInfo(now))
                        .toList();
            } finally {
                lock.unlock();
            }
        });
    }

    /**
     * 清除空闲超过 30 分钟的过期会话。
     *
     * @return 完成的 CompletableFuture
     */
    // 对标 Python ExecSessionManager._cleanup_locked()
    private CompletableFuture<Void> cleanupStale() {
        return CompletableFuture.supplyAsync(() -> {
            lock.lock();
            try {
                Instant now = Instant.now();
                List<ExecSession> stale = new ArrayList<>();
                Iterator<Map.Entry<String, ExecSession>> it =
                        sessions.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, ExecSession> e = it.next();
                    if (Duration.between(e.getValue().lastAccess(), now)
                            .compareTo(IDLE_TIMEOUT) > 0) {
                        it.remove();
                        stale.add(e.getValue());
                    }
                }
                return stale;
            } finally {
                lock.unlock();
            }
        }).thenCompose(stale -> {
            CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
            for (ExecSession s : stale) {
                future = future.thenCompose(unused -> s.kill());
            }
            return future;
        });
    }

    /**
     * 启动结果记录：包含会话 ID 和初始轮询结果。
     */
    public record StartResult(String sessionId, SessionPoll poll) {}
}
