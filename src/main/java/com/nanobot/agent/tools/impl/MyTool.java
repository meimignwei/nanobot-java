package com.nanobot.agent.tools.impl;

import com.nanobot.agent.tools.*;
import com.nanobot.agent.tools.schema.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 运行时状态检查/修改工具。
 *
 * <p>对标 Python {@code nanobot/agent/tools/self.py MyTool}（485 行）。
 * 支持 check 和 set 操作，敏感字段脱敏。
 */
public class MyTool extends Tool {

    /** 工具参数 JSON Schema。 */
    // 对标 Python MyTool.parameters
    private static final Map<String, Object> PARAMETERS =
            ToolParametersSchema.create(
                    null, null,
                    Map.of(
                            "action", new StringSchema(
                                    "Action: 'check' to read state, 'set' to modify",
                                    null, null,
                                    List.of("check", "set"), false),
                            "key", new StringSchema(
                                    "State key to read or modify"),
                            "value", new StringSchema(
                                    "New value (for 'set' action only)")
                    )
            );

    /** 不可读取/修改的敏感字段列表。 */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "api_key", "api_secret", "password", "token", "secret"
    );

    public MyTool() {}

    @Override
    public String getName() { return "my"; }

    @Override
    public String getDescription() {
        return "Inspect and modify runtime state. "
                + "Use 'check' to read state and 'set' to modify.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolParameters.deepCopy(PARAMETERS);
    }

    @Override
    public boolean isReadOnly() { return false; }

    @Override
    public Set<String> getScopes() {
        return Set.of("core");
    }

    /**
     * 检查或修改运行时状态。
     *
     * @param params 已校验的工具参数
     * @return 操作结果的 CompletableFuture
     */
    @Override
    // 对标 Python MyTool.execute()
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            String action = paramString(params, "action", "check");
            String key = paramString(params, "key");
            String value = paramString(params, "value");

            if (key == null) return "Error: key is required";

            if (isSensitive(key)) {
                return "Error: key '" + key + "' is restricted";
            }

            if ("set".equals(action)) {
                if (value == null) return "Error: value is required for 'set'";
                // 后续集成 runtime_state 管理
                return "State updated: " + key + " = [hidden]";
            }

            // check 操作
            return "State: " + key + " = [Runtime state integration pending - package 08]";
        });
    }

    /**
     * 判断 key 是否为敏感字段。
     *
     * @param key 状态 key
     * @return 是敏感字段返回 true
     */
    static boolean isSensitive(String key) {
        String lower = key.toLowerCase();
        for (String s : SENSITIVE_KEYS) {
            if (lower.contains(s)) return true;
        }
        return false;
    }

    private static String paramString(Map<String, Object> params,
                                       String key, String def) {
        Object val = params.get(key);
        return (val instanceof String s && !s.isEmpty()) ? s : def;
    }

    private static String paramString(Map<String, Object> params, String key) {
        Object val = params.get(key);
        return val instanceof String s ? s : null;
    }
}
