package com.nanobot.agent.tools.impl;

import com.nanobot.agent.tools.*;
import com.nanobot.agent.tools.exec.*;
import com.nanobot.agent.tools.schema.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 列出活跃 exec 会话的工具。
 *
 * <p>对标 Python {@code exec_session.py ListExecSessionsTool}（lines 544-609）。
 * 显示 session_id、状态、耗时、空闲时间、剩余超时、工作目录和命令预览，
 * 用于在上下文切换后恢复会话 ID。
 */
public class ListExecSessionsTool extends Tool {

    /** 工具参数 JSON Schema（无必需参数）。 */
    // 对标 Python ListExecSessionsTool.parameters
    private static final Map<String, Object> PARAMETERS =
            ToolParametersSchema.create(null, null, Map.of());

    /** 默认全局 ExecSessionManager 实例（对标 Python DEFAULT_EXEC_SESSION_MANAGER）。 */
    // 对标 Python DEFAULT_EXEC_SESSION_MANAGER
    private static final ExecSessionManager DEFAULT_MANAGER = new ExecSessionManager();

    private final ExecSessionManager manager;

    /** 默认构造器，使用全局单例 ExecSessionManager。 */
    public ListExecSessionsTool() {
        this(DEFAULT_MANAGER);
    }

    /**
     * 构造 ListExecSessionsTool。
     *
     * @param manager ExecSessionManager 实例
     */
    // 对标 Python ListExecSessionsTool.__init__()
    public ListExecSessionsTool(ExecSessionManager manager) {
        this.manager = manager;
    }

    @Override
    public String getName() { return "list_exec_sessions"; }

    @Override
    public String getDescription() {
        return "List active long-running exec sessions, including session_id, cwd, "
                + "elapsed time, idle time, remaining timeout, and command preview. "
                + "Use this to recover a session_id after context shifts before "
                + "polling, writing stdin, or terminating with write_stdin.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolParameters.deepCopy(PARAMETERS);
    }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public Set<String> getScopes() { return Set.of("core", "subagent"); }

    @Override
    public String getConfigKey() { return "exec"; }

    /**
     * 列出当前 session key 下的所有活跃 exec 会话。
     *
     * @param params 已校验的工具参数
     * @return 格式化会话列表的 CompletableFuture
     */
    @Override
    // 对标 Python ListExecSessionsTool.execute()
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String ownerSessionKey = RequestContext.currentSessionKey();
                List<ExecSessionInfo> sessions =
                        manager.list(ownerSessionKey).join();
                if (sessions.isEmpty()) {
                    return "No active exec sessions.";
                }
                StringBuilder sb = new StringBuilder();
                for (ExecSessionInfo info : sessions) {
                    String command = info.command().replaceAll("\\s+", " ").trim();
                    if (command.length() > 120) {
                        command = command.substring(0, 119) + "...";
                    }
                    String status = info.returncode() != null ? "exited" : "running";
                    sb.append(String.format(
                            "%s | %s | elapsed=%.1fs | idle=%.1fs "
                                    + "| remaining=%.1fs | cwd=%s | %s%n",
                            info.sessionId(), status,
                            info.elapsedSec(), info.idleSec(),
                            info.remainingSec(), info.cwd(), command));
                }
                return sb.toString().stripTrailing();
            } catch (Exception e) {
                return "Error listing exec sessions: " + e.getMessage();
            }
        });
    }
}
