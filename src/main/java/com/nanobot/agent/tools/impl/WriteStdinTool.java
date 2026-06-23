package com.nanobot.agent.tools.impl;

import com.nanobot.agent.tools.*;
import com.nanobot.agent.tools.exec.*;
import com.nanobot.agent.tools.schema.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 向运行中的 exec 会话写入 stdin 或轮询输出的工具。
 *
 * <p>对标 Python {@code exec_session.py WriteStdinTool}（lines 354-542）。
 * 支持写入文本、关闭 stdin、终止进程、等待特定输出模式等操作。
 * 不可用于启动新命令，仅操作已有的 exec 会话。
 */
public class WriteStdinTool extends Tool {

    /** 默认等待输出超时毫秒数（对标 Python DEFAULT_WAIT_FOR_MS）。 */
    private static final int DEFAULT_WAIT_FOR_MS = 10_000;
    /** 最大等待输出超时毫秒数（对标 Python MAX_WAIT_FOR_MS）。 */
    private static final int MAX_WAIT_FOR_MS = 120_000;
    /** 等待输出时单次轮询间隔毫秒数。 */
    private static final int WAIT_FOR_POLL_MS = 500;

    /** 工具参数 JSON Schema。 */
    // 对标 Python WriteStdinTool.parameters
    private static final Map<String, Object> PARAMETERS =
            ToolParametersSchema.create(
                    List.of("session_id"),
                    null,
                    Map.of(
                            "session_id", new StringSchema(
                                    "Session id returned by exec when yield_time_ms is used."),
                            "chars", new StringSchema(
                                    "Bytes/text to write to stdin. Omit or pass an empty "
                                            + "string to only poll recent output.",
                                    null, null, null, true),
                            "close_stdin", new BooleanSchema(
                                    "Close stdin after writing chars. Useful for "
                                            + "commands waiting for EOF.",
                                    false, true),
                            "terminate", new BooleanSchema(
                                    "Terminate the running exec session.",
                                    false, true),
                            "yield_time_ms", new IntegerSchema(
                                    ExecSessionManager.DEFAULT_YIELD_MS,
                                    "Milliseconds to wait before returning recent output "
                                            + "(default 1000, max 30000).",
                                    0, ExecSessionManager.MAX_YIELD_MS, null, true),
                            "wait_for", new StringSchema(
                                    "Optional text to wait for in output before returning. "
                                            + "Useful for interactive commands and dev servers.",
                                    null, null, null, true),
                            "wait_timeout_ms", new IntegerSchema(
                                    DEFAULT_WAIT_FOR_MS,
                                    "Maximum milliseconds to wait for wait_for text "
                                            + "(default 10000, max 120000).",
                                    0, MAX_WAIT_FOR_MS, null, true),
                            "max_output_chars", new IntegerSchema(
                                    ExecSessionManager.DEFAULT_MAX_OUTPUT_CHARS,
                                    "Maximum output characters to return from this poll "
                                            + "(default 10000, max 50000).",
                                    1000, ExecSessionManager.MAX_OUTPUT_CHARS, null, true),
                            "max_output_tokens", new IntegerSchema(
                                    ExecSessionManager.DEFAULT_MAX_OUTPUT_CHARS,
                                    "Compatibility alias for max_output_chars. "
                                            + "The current runtime uses a character budget.",
                                    1000, ExecSessionManager.MAX_OUTPUT_CHARS, null, true)
                    )
            );

    /** 默认全局 ExecSessionManager 实例（对标 Python DEFAULT_EXEC_SESSION_MANAGER）。 */
    // 对标 Python DEFAULT_EXEC_SESSION_MANAGER
    private static final ExecSessionManager DEFAULT_MANAGER = new ExecSessionManager();

    private final ExecSessionManager manager;

    /** 默认构造器，使用全局单例 ExecSessionManager。 */
    public WriteStdinTool() {
        this(DEFAULT_MANAGER);
    }

    /**
     * 构造 WriteStdinTool。
     *
     * @param manager ExecSessionManager 实例
     */
    // 对标 Python WriteStdinTool.__init__()
    public WriteStdinTool(ExecSessionManager manager) {
        this.manager = manager;
    }

    @Override
    public String getName() { return "write_stdin"; }

    @Override
    public String getDescription() {
        return "Interact with a running exec session created by exec with "
                + "yield_time_ms. Use chars='' to poll without writing, chars to send "
                + "stdin, close_stdin=true to send EOF, or terminate=true to stop the "
                + "process. Use wait_for with wait_timeout_ms for dev servers, test "
                + "watchers, and prompts where you need to wait for expected output. "
                + "Do not use this to start new commands; start them with exec.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolParameters.deepCopy(PARAMETERS);
    }

    @Override
    public boolean isReadOnly() { return false; }

    @Override
    public boolean isExclusive() { return true; }

    @Override
    public Set<String> getScopes() { return Set.of("core", "subagent"); }

    @Override
    public String getConfigKey() { return "exec"; }

    /**
     * 与运行中的 exec 会话交互：写入 stdin / 轮询 / 关闭 / 终止。
     *
     * @param params 已校验的工具参数
     * @return 格式化轮询结果的 CompletableFuture
     */
    @Override
    // 对标 Python WriteStdinTool.execute()
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String sessionId = paramString(params, "session_id");
                if (sessionId == null || sessionId.isEmpty()) {
                    return "Error: session_id is required";
                }
                String chars = paramString(params, "chars");
                boolean closeStdin = paramBool(params, "close_stdin");
                boolean terminate = paramBool(params, "terminate");
                String waitFor = paramString(params, "wait_for");
                int outputLimit = resolveMaxOutputChars(params);
                String ownerSessionKey = RequestContext.currentSessionKey();

                if (waitFor != null && !waitFor.isEmpty()) {
                    int waitTimeoutMs = clampInt(
                            paramInt(params, "wait_timeout_ms"),
                            DEFAULT_WAIT_FOR_MS, 0, MAX_WAIT_FOR_MS);
                    return executeWaitFor(sessionId, chars, closeStdin, terminate,
                            waitFor, waitTimeoutMs, outputLimit, ownerSessionKey);
                }

                int yieldTimeMs = clampInt(
                        paramInt(params, "yield_time_ms"),
                        ExecSessionManager.DEFAULT_YIELD_MS, 0,
                        ExecSessionManager.MAX_YIELD_MS);

                SessionPoll poll = manager.interact(
                        sessionId, chars, closeStdin, terminate,
                        yieldTimeMs, outputLimit, ownerSessionKey).join();

                return formatSessionPoll(sessionId, poll);
            } catch (NoSuchElementException e) {
                return "Error: exec session not found: " + e.getMessage();
            } catch (Exception e) {
                return "Error writing to exec session: " + e.getMessage();
            }
        });
    }

    /**
     * 等待特定输出模式：循环轮询直到在输出中看到 wait_for 文本或超时。
     * 对标 Python {@code WriteStdinTool._wait_for_output()}。
     */
    // 对标 Python WriteStdinTool._wait_for_output()
    private String executeWaitFor(
            String sessionId, String chars, boolean closeStdin, boolean terminate,
            String waitFor, int waitTimeoutMs, int maxOutputChars,
            String ownerSessionKey) {

        Instant deadline = Instant.now().plusMillis(waitTimeoutMs);
        List<String> aggregate = new ArrayList<>();
        boolean first = true;
        SessionPoll poll = null;

        while (true) {
            long remainingMs = Math.max(0,
                    deadline.toEpochMilli() - Instant.now().toEpochMilli());
            int stepMs = (int) Math.min(WAIT_FOR_POLL_MS, remainingMs);

            poll = manager.interact(
                    sessionId,
                    first ? chars : null,
                    first && closeStdin,
                    first && terminate,
                    stepMs, maxOutputChars, ownerSessionKey).join();
            first = false;

            if (poll.output() != null && !poll.output().isEmpty()) {
                aggregate.add(poll.output());
                String joined = String.join("", aggregate);
                if (joined.contains(waitFor)) {
                    // 返回匹配后的完整聚合输出
                    SessionPoll matchedPoll = new SessionPoll(
                            joined, poll.done(), poll.exitCode(),
                            poll.elapsedSec(), poll.timedOut(),
                            poll.terminated(), poll.stdinClosed(),
                            poll.truncatedChars());
                    return formatSessionPoll(sessionId, matchedPoll);
                }
            }
            if (poll.done() || remainingMs <= 0) {
                String joined = String.join("", aggregate);
                SessionPoll finalPoll = new SessionPoll(
                        joined, poll.done(), poll.exitCode(),
                        poll.elapsedSec(), poll.timedOut(),
                        poll.terminated(), poll.stdinClosed(),
                        poll.truncatedChars());
                String result = formatSessionPoll(sessionId, finalPoll);
                if (!joined.contains(waitFor)) {
                    result += "\nWait target not observed: " + repr(waitFor);
                }
                return result;
            }
        }
    }

    /**
     * 格式化会话轮询结果为人类可读字符串。
     * 对标 Python {@code format_session_poll()}。
     *
     * @param sessionId 会话 ID
     * @param poll      轮询结果
     * @return 格式化字符串
     */
    // 对标 Python format_session_poll()
    static String formatSessionPoll(String sessionId, SessionPoll poll) {
        List<String> parts = new ArrayList<>();
        if (poll.output() != null && !poll.output().isEmpty()) {
            parts.add(poll.output());
        }
        if (poll.truncatedChars() > 0) {
            parts.add("(output truncated by " + poll.truncatedChars() + " chars)");
        }
        if (poll.timedOut()) {
            parts.add("Error: Command timed out; session was terminated.");
        }
        if (poll.terminated() && !poll.timedOut()) {
            parts.add("Session terminated.");
        }
        if (poll.stdinClosed()) {
            parts.add("Stdin closed.");
        }
        if (poll.done()) {
            parts.add("Exit code: " + poll.exitCode());
        } else {
            parts.add("Process running. session_id: " + sessionId);
        }
        parts.add("Elapsed: " + String.format("%.1f", poll.elapsedSec()) + "s");
        return parts.isEmpty() ? "(no output yet)" : String.join("\n", parts);
    }

    // ==================== 参数辅助 ====================

    /**
     * 限制整数值在指定范围内。
     * 对标 Python {@code clamp_session_int()}。
     */
    // 对标 Python clamp_session_int()
    static int clampInt(Integer value, int def, int min, int max) {
        if (value == null) return def;
        return Math.min(Math.max(value, min), max);
    }

    private int resolveMaxOutputChars(Map<String, Object> params) {
        Integer chars = paramInt(params, "max_output_chars");
        if (chars == null) chars = paramInt(params, "max_output_tokens");
        return clampInt(chars, ExecSessionManager.DEFAULT_MAX_OUTPUT_CHARS,
                1000, ExecSessionManager.MAX_OUTPUT_CHARS);
    }

    private static String repr(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static String paramString(Map<String, Object> params, String key) {
        Object val = params.get(key);
        return val instanceof String s ? s : null;
    }

    private static boolean paramBool(Map<String, Object> params, String key) {
        Object val = params.get(key);
        return val instanceof Boolean b && b;
    }

    private static Integer paramInt(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val instanceof Integer i) return i;
        if (val instanceof Number n) return n.intValue();
        return null;
    }
}
