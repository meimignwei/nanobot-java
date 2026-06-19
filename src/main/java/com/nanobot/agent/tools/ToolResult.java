package com.nanobot.agent.tools;

import jakarta.annotation.Nullable;

import java.util.Map;

/**
 * Tool execution result.
 * Port of Python ToolResult dataclass.
 */
public record ToolResult(
        Object output,
        Map<String, Object> meta,
        boolean persist,
        @Nullable String error
) {
    public ToolResult {
        if (meta == null) meta = Map.of();
    }

    public static ToolResult ok(Object output) {
        return new ToolResult(output, Map.of(), true, null);
    }

    public static ToolResult ok(Object output, boolean persist) {
        return new ToolResult(output, Map.of(), persist, null);
    }

    public static ToolResult fail(String error) {
        return new ToolResult(error, Map.of(), false, error);
    }

    public boolean isError() {
        return error != null;
    }

    /** Port of Python ensure_nonempty_tool_result. */
    public String outputAsString() {
        if (output instanceof String s) return s;
        if (output == null) return "";
        return output.toString();
    }
}
