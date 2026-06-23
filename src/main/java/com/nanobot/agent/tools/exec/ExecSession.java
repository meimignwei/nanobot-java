package com.nanobot.agent.tools.exec;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 包装单个运行中的子进程，提供异步 stdout/stderr 读取、stdin 写入、
 * EOF 关闭、终止和定时轮询功能。
 *
 * <p>对标 Python {@code exec_session.py _ExecSession}（lines 54-176）。
 * 所有可变操作方法返回 {@link CompletableFuture}，对应 Python 中的
 * {@code async def write/close_stdin/poll/kill}。
 */
public class ExecSession {

    private static final Duration OUTPUT_DRAIN_GRACE = Duration.ofMillis(100);

    private final String sessionId;
    private final Process process;
    private final String command;
    private final String cwd;
    private final Instant startedAt;
    private final Instant deadline;
    private volatile Instant lastAccess;
    private final String ownerSessionKey;
    private final List<String> chunks = Collections.synchronizedList(new ArrayList<>());
    private final CompletableFuture<Void> stdoutTask;
    private final CompletableFuture<Void> stderrTask;
    private boolean timedOut = false;

    /**
     * 构造执行会话，启动异步 stdout/stderr 读取任务。
     *
     * @param sessionId        会话 ID（12 位 hex）
     * @param process          已启动的进程
     * @param command          执行的命令
     * @param cwd              工作目录
     * @param timeout          超时秒数（null/0 表示无限制）
     * @param ownerSessionKey  所属 session key
     */
    // 对标 Python _ExecSession.__init__()
    public ExecSession(String sessionId, Process process, String command, String cwd,
                       Integer timeout, String ownerSessionKey) {
        this.sessionId = sessionId;
        this.process = process;
        this.command = command;
        this.cwd = cwd;
        this.ownerSessionKey = ownerSessionKey;
        this.startedAt = Instant.now();
        this.deadline = (timeout != null && timeout > 0)
                ? Instant.now().plusSeconds(timeout) : Instant.MAX;
        this.lastAccess = Instant.now();
        this.stdoutTask = readStream(process.getInputStream(), "");
        this.stderrTask = readStream(process.getErrorStream(), "STDERR:\n");
    }

    /**
     * 异步读取输入流，将解码文本追加到 chunks 列表。
     *
     * @param stream 输入流
     * @param prefix 首次读取时的前缀文本
     * @return 读取完成的 CompletableFuture
     */
    // 对标 Python _ExecSession._read_stream()
    private CompletableFuture<Void> readStream(InputStream stream, String prefix) {
        return CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                boolean first = true;
                char[] buffer = new char[4096];
                int n;
                while ((n = reader.read(buffer)) != -1) {
                    String text = new String(buffer, 0, n);
                    if (prefix != null && !prefix.isEmpty() && first) {
                        text = prefix + text;
                        first = false;
                    }
                    chunks.add(text);
                }
            } catch (IOException e) {
                // 进程退出时流关闭，属正常行为
            }
        });
    }

    /**
     * 向进程 stdin 写入字符串，返回错误消息或 null。
     *
     * @param chars 待写入的文本
     * @return 错误消息（成功时为 null）的 CompletableFuture
     */
    // 对标 Python _ExecSession.write()
    public CompletableFuture<String> write(String chars) {
        return CompletableFuture.supplyAsync(() -> {
            if (!process.isAlive()) return "session has already exited";
            try (OutputStream out = process.getOutputStream()) {
                out.write(chars.getBytes(StandardCharsets.UTF_8));
                out.flush();
                return null;
            } catch (IOException e) {
                return "session stdin is closed";
            }
        });
    }

    /**
     * 关闭进程 stdin（发送 EOF），返回错误消息或 null。
     *
     * @return 完成的 CompletableFuture
     */
    // 对标 Python _ExecSession.close_stdin()
    public CompletableFuture<Void> closeStdin() {
        return CompletableFuture.runAsync(() -> {
            if (process.isAlive()) {
                try { process.getOutputStream().close(); }
                catch (IOException ignored) {}
            }
        });
    }

    /**
     * 终止进程并等待回收（最多 5 秒）。
     *
     * @return 完成的 CompletableFuture
     */
    // 对标 Python _ExecSession.kill()
    public CompletableFuture<Void> kill() {
        return CompletableFuture.runAsync(() -> {
            if (!process.isAlive()) return;
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
        });
    }

    /**
     * 轮询最近输出，可选择等待新数据生成。
     * 对标 Python {@code async def poll(...)}。
     *
     * @param yieldTimeMs    等待新输出的毫秒数
     * @param maxOutputChars 最大输出字符数
     * @param terminated     是否已被显式终止
     * @param stdinClosed    此轮是否关闭了 stdin
     * @return 轮询结果的 CompletableFuture
     */
    // 对标 Python _ExecSession.poll()
    public CompletableFuture<SessionPoll> poll(int yieldTimeMs, int maxOutputChars,
                                               boolean terminated, boolean stdinClosed) {
        return CompletableFuture.supplyAsync(() -> {
            lastAccess = Instant.now();
            if (yieldTimeMs > 0 && process.isAlive()) {
                try { Thread.sleep(Math.min(yieldTimeMs, ExecSessionManager.MAX_YIELD_MS)); }
                catch (InterruptedException ignored) {}
            }
            return process.isAlive() && Instant.now().isAfter(deadline);
        }).thenCompose(shouldKill -> {
            if (shouldKill) {
                timedOut = true;
                return kill();
            }
            return CompletableFuture.completedFuture(null);
        }).thenApply(pollUnused -> {
            if (!process.isAlive()) {
                try {
                    CompletableFuture.allOf(stdoutTask, stderrTask)
                            .get(2, TimeUnit.SECONDS);
                } catch (Exception ignored) {}
            } else if (yieldTimeMs > 0) {
                waitForBufferedOutput();
            }

            String output;
            synchronized (chunks) {
                output = String.join("", chunks);
                chunks.clear();
            }

            Truncated truncated = truncateOutput(output, maxOutputChars);
            return new SessionPoll(
                    truncated.text(),
                    !process.isAlive(),
                    process.isAlive() ? null : process.exitValue(),
                    Duration.between(startedAt, Instant.now()).toMillis() / 1000.0,
                    timedOut,
                    terminated,
                    stdinClosed,
                    truncated.omitted()
            );
        });
    }

    /**
     * 重载 poll 方法（无 terminated/stdinClosed 标记）。
     *
     * @param yieldTimeMs    等待新输出的毫秒数
     * @param maxOutputChars 最大输出字符数
     * @return 轮询结果的 CompletableFuture
     */
    public CompletableFuture<SessionPoll> poll(int yieldTimeMs, int maxOutputChars) {
        return poll(yieldTimeMs, maxOutputChars, false, false);
    }

    /**
     * 等待缓冲输出到达（OUTPUT_DRAIN_GRACE 超时）。
     */
    // 对标 Python _ExecSession._wait_for_buffered_output()
    private void waitForBufferedOutput() {
        Instant graceDeadline = Instant.now().plus(OUTPUT_DRAIN_GRACE);
        while (Instant.now().isBefore(graceDeadline)) {
            synchronized (chunks) {
                if (!chunks.isEmpty()) return;
            }
            try { Thread.sleep(10); } catch (InterruptedException ignored) {}
        }
    }

    /**
     * 截断过长输出，保留首尾各一半并插入省略提示。
     *
     * @param output   原始输出
     * @param maxChars 最大字符数
     * @return 截断结果
     */
    // 对标 Python _truncate_output()
    private static Truncated truncateOutput(String output, int maxChars) {
        if (output.length() <= maxChars) return new Truncated(output, 0);
        int half = maxChars / 2;
        int omitted = output.length() - maxChars;
        return new Truncated(
                output.substring(0, half)
                        + "\n\n... (" + omitted + " chars truncated) ...\n\n"
                        + output.substring(output.length() - half),
                omitted
        );
    }

    /** 截断结果记录：截断后文本和被省略的字符数。 */
    private record Truncated(String text, int omitted) {}

    // ==================== 访问器 ====================

    /** @return 最近访问时间 */
    public Instant lastAccess() { return lastAccess; }

    /** @return 所属 session key */
    public String ownerSessionKey() { return ownerSessionKey; }

    /** @return 会话 ID */
    public String sessionId() { return sessionId; }

    /** @return 执行的命令 */
    public String command() { return command; }

    /** @return 工作目录 */
    public String cwd() { return cwd; }

    /** @return 启动时间 */
    public Instant startedAt() { return startedAt; }

    /** @return 超时截止时间 */
    public Instant deadline() { return deadline; }

    /** @return 进程是否存活 */
    public boolean isAlive() { return process.isAlive(); }

    /** @return 进程退出码（运行中为 null） */
    public Integer exitValue() {
        return process.isAlive() ? null : process.exitValue();
    }

    /**
     * 构建会话摘要信息。
     *
     * @param now 当前时间
     * @return 执行会话摘要信息
     */
    // 对标 Python _ExecSession → ExecSessionInfo 转换（在 ExecSessionManager.list() 中）
    public ExecSessionInfo toInfo(Instant now) {
        return new ExecSessionInfo(
                sessionId,
                command,
                cwd,
                Duration.between(startedAt, now).toMillis() / 1000.0,
                Duration.between(lastAccess, now).toMillis() / 1000.0,
                deadline.equals(Instant.MAX) ? Double.POSITIVE_INFINITY
                        : Math.max(0, Duration.between(now, deadline).toMillis() / 1000.0),
                process.isAlive() ? null : process.exitValue(),
                ownerSessionKey
        );
    }
}
