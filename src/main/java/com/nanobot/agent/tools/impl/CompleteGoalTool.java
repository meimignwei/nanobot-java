package com.nanobot.agent.tools.impl;

import com.nanobot.agent.tools.*;
import com.nanobot.agent.tools.schema.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 目标完成标记工具。
 *
 * <p>对标 Python {@code nanobot/agent/tools/long_task.py CompleteGoalTool}（68 行）。
 * 将目标状态切换为 completed 并附 recap，幂等操作。
 */
public class CompleteGoalTool extends Tool {

    /** 工具参数 JSON Schema。 */
    // 对标 Python CompleteGoalTool.parameters
    private static final Map<String, Object> PARAMETERS =
            ToolParametersSchema.create(
                    null, null,
                    Map.of(
                            "recap", new StringSchema(
                                    "Recap of what was accomplished")
                    )
            );

    public CompleteGoalTool() {}

    @Override
    public String getName() { return "complete_goal"; }

    @Override
    public String getDescription() {
        return "Mark the current long-term goal as completed. "
                + "Provides a recap of what was accomplished.";
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
     * 标记目档完成。
     *
     * @param params 已校验的工具参数
     * @return 确认消息的 CompletableFuture
     */
    @Override
    // 对标 Python CompleteGoalTool.execute()
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            String recap = paramString(params, "recap", "Goal completed.");
            return "Goal completed: " + recap;
        });
    }

    private static String paramString(Map<String, Object> params,
                                       String key, String def) {
        Object val = params.get(key);
        return (val instanceof String s && !s.isEmpty()) ? s : def;
    }
}
