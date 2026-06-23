package com.nanobot.agent.tools.impl;

import com.nanobot.agent.tools.*;
import com.nanobot.agent.tools.schema.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 一次性子 agent 创建工具。
 *
 * <p>对标 Python {@code nanobot/agent/tools/spawn.py SpawnTool}（97 行）。
 * 接受任务描述，创建子 agent 执行任务，受 SubagentManager 并发上限约束。
 */
public class SpawnTool extends Tool {

    /** 工具参数 JSON Schema。 */
    // 对标 Python SpawnTool.parameters
    private static final Map<String, Object> PARAMETERS =
            ToolParametersSchema.create(
                    List.of("task"),
                    null,
                    Map.of(
                            "task", new StringSchema(
                                    "The task description for the subagent"),
                            "label", new StringSchema(
                                    "Optional label for logging"),
                            "temperature", new NumberSchema(0.0,
                                    "Optional temperature override",
                                    null, null, null, true)
                    )
            );

    public SpawnTool() {}

    @Override
    public String getName() { return "spawn"; }

    @Override
    public String getDescription() {
        return "Spawn a subagent to handle a task autonomously. "
                + "Concurrency is limited by SubagentManager.";
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
     * 创建子 agent 执行任务。
     *
     * @param params 已校验的工具参数
     * @return 任务描述确认的 CompletableFuture
     */
    @Override
    // 对标 Python SpawnTool.execute()
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            String task = paramString(params, "task");
            if (task == null || task.isEmpty()) {
                return "Error: task parameter is required";
            }
            String label = paramString(params, "label", task);
            return "Subagent spawned: " + label + "\n"
                    + "[SubagentManager integration pending - package 09]";
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
