package com.nanobot.agent.tools.impl;

import com.nanobot.agent.tools.*;
import com.nanobot.agent.tools.schema.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 已注册的 CLI 应用调用工具。
 *
 * <p>对标 Python {@code nanobot/agent/tools/cli_apps.py CliAppsTool}（~150 行）。
 * 从配置（cli_apps 段）加载已注册应用，校验应用名存在性，
 * 执行时支持 json 标记、working_dir 覆写和 per-call timeout。
 */
public class CliAppsTool extends Tool {

    /** 工具参数 JSON Schema。 */
    // 对标 Python CliAppsTool.parameters
    private static final Map<String, Object> PARAMETERS =
            ToolParametersSchema.create(
                    List.of("app", "args"),
                    null,
                    Map.of(
                            "app", new StringSchema(
                                    "Registered CLI app name"),
                            "args", new StringSchema(
                                    "Arguments to pass to the CLI app"),
                            "json", new BooleanSchema(
                                    "Request JSON output (default: false)",
                                    false, true),
                            "working_dir", new StringSchema(
                                    "Override working directory"),
                            "timeout", new IntegerSchema(30,
                                    "Per-call timeout in seconds",
                                    1, 300, null, true)
                    )
            );

    public CliAppsTool() {}

    @Override
    public String getName() { return "run_cli_app"; }

    @Override
    public String getDescription() {
        return "Invoke a registered CLI application with arguments.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolParameters.deepCopy(PARAMETERS);
    }

    @Override
    public boolean isReadOnly() { return false; }

    @Override
    public Set<String> getScopes() { return Set.of("core"); }

    @Override
    public String getConfigKey() { return "cli_apps"; }

    /**
     * 调用已注册的 CLI 应用。
     *
     * @param params 已校验的工具参数
     * @return CLI 输出或错误消息的 CompletableFuture
     */
    @Override
    // 对标 Python CliAppsTool.execute()
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            String app = paramString(params, "app");
            String args = paramString(params, "args", "");
            if (app == null || app.isEmpty()) {
                return "Error: app parameter is required";
            }
            return "CLI app '" + app + "' execution: "
                    + "[CLI apps integration pending - package 09]";
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
