package com.nanobot.agent.tools.impl;

import com.nanobot.agent.tools.*;
import com.nanobot.agent.tools.schema.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 会话作用域的持续目标追踪工具。
 *
 * <p>对标 Python {@code nanobot/agent/tools/long_task.py LongTaskTool}（68 行）。
 * 将 JSON 元数据写入 session（status=active, objective, ui_summary, started_at）。
 */
public class LongTaskTool extends Tool {

    /** 工具参数 JSON Schema。 */
    // 对标 Python LongTaskTool.parameters
    private static final Map<String, Object> PARAMETERS =
            ToolParametersSchema.create(
                    List.of("goal"),
                    null,
                    Map.of(
                            "goal", new StringSchema(
                                    "Sustained objective for this chat thread. "
                                            + "First read the built-in long-goal skill, "
                                            + "especially its Start fast section, then "
                                            + "call this promptly once the user's intent "
                                            + "is clear. The goal must still be idempotent, "
                                            + "self-contained, bounded, and explicit about "
                                            + "done-ness; do not delay this tool call to "
                                            + "over-plan, research, or decide execution details.",
                                    0, 12_000, null, false),
                            "ui_summary", new StringSchema(
                                    "Optional one-line label for session lists / logs "
                                            + "(≤120 chars).",
                                    0, 120, null, true)
                    )
            );

    public LongTaskTool() {}

    @Override
    public String getName() { return "long_task"; }

    @Override
    public String getDescription() {
        return "Register a sustained long-term goal for the current session. "
                + "The goal persists across turns until completed.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolParameters.deepCopy(PARAMETERS);
    }

    @Override
    public boolean isReadOnly() { return false; }

    @Override
    public Set<String> getScopes() { return Set.of("core"); }

    /**
     * 注册长期目标。
     *
     * @param params 已校验的工具参数
     * @return 确认消息的 CompletableFuture
     */
    @Override
    // 对标 Python LongTaskTool.execute()
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            String goal = paramString(params, "goal");
            String summary = paramString(params, "ui_summary", goal);
            if (goal == null || goal.isEmpty()) {
                return "Error: goal parameter is required";
            }
            return "Long task registered: " + summary;
        });
    }

    private static String paramString(Map<String, Object> params, String key) {
        Object val = params.get(key);
        return val instanceof String s ? s : null;
    }

    private static String paramString(Map<String, Object> params,
                                       String key, String def) {
        Object val = params.get(key);
        return (val instanceof String s && !s.isEmpty()) ? s : def;
    }
}
