package com.nanobot.agent.tools;

import jakarta.annotation.Nullable;

import java.util.Map;

/**
 * 工具执行结果。
 * 对应 Python ToolResult dataclass。
 */
public record ToolResult(
        /** 工具输出 */
        Object output,
        /** 元数据 */
        Map<String, Object> meta,
        /** 是否持久化到会话 */
        boolean persist,
        /** 错误信息 */
        @Nullable String error
) {
    public ToolResult {
        if (meta == null) meta = Map.of();
    }

    /** 成功结果。对应 Python ToolResult.ok()。 */
    public static ToolResult ok(Object output) {
        return new ToolResult(output, Map.of(), true, null);
    }

    /** 成功结果（可控制是否持久化）。 */
    public static ToolResult ok(Object output, boolean persist) {
        return new ToolResult(output, Map.of(), persist, null);
    }

    /** 失败结果。对应 Python ToolResult.fail()。 */
    public static ToolResult fail(String error) {
        return new ToolResult(error, Map.of(), false, error);
    }

    /** 是否为错误。 */
    public boolean isError() {
        return error != null;
    }

    /** 输出转为字符串。对应 Python ensure_nonempty_tool_result。 */
    public String outputAsString() {
        if (output instanceof String s) return s;
        if (output == null) return "";
        return output.toString();
    }
}
