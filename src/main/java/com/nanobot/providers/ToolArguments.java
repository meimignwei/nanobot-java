package com.nanobot.providers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 工具参数解析/修复的静态工具方法。
 *
 * <p>对标 Python {@code nanobot/providers/base.py} 中的
 * {@code parse_tool_arguments()}、{@code tool_arguments_object_for_replay()}、
 * {@code tool_arguments_json_for_replay()}。
 */
public final class ToolArguments {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolArguments() {
    }

    /**
     * 解析 provider 工具参数。有效 JSON 对象字符串转为 Map，空字符串转空 Map。
     * 无效 JSON 和非对象 JSON 值原样保留，供 ToolRegistry 在执行前拒绝。
     *
     * @param arguments 参数（String、Map 或 null）
     * @return 解析后的对象
     */
    // 对标 Python base.py parse_tool_arguments()
    public static Object parseToolArguments(Object arguments) {
        if (arguments == null) return Map.of();
        if (!(arguments instanceof String s)) return arguments;

        String stripped = s.strip();
        if (stripped.isEmpty()) return Map.of();

        try {
            Object parsed = JSON.readValue(stripped, Object.class);
            return (parsed == null) ? s : parsed;
        } catch (JsonProcessingException e) {
            return s;
        }
    }

    /**
     * 返回对象形式的参数，仅用于 provider 历史 replay。
     * 可修复损坏的 JSON——仅用于构建对话历史，不用于新工具调用。
     *
     * @param arguments 参数（String、Map 或 null）
     * @return 参数 Map，解析失败返回空 Map
     */
    // 对标 Python base.py tool_arguments_object_for_replay()
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toolArgumentsObjectForReplay(Object arguments) {
        if (arguments == null) return Map.of();
        if (arguments instanceof Map<?, ?> m) return (Map<String, Object>) m;
        if (!(arguments instanceof String s)) return Map.of();

        String stripped = s.strip();
        if (stripped.isEmpty()) return Map.of();

        try {
            Object parsed = JSON.readValue(stripped, Object.class);
            return (parsed instanceof Map<?, ?> m) ? (Map<String, Object>) m : Map.of();
        } catch (JsonProcessingException e) {
            // json_repair 回退：对标 Python json_repair.loads()
            // 尝试修复常见 JSON 损坏：单引号→双引号、尾部逗号等
            try {
                String repaired = stripped
                        .replace('\'', '"')
                        .replaceAll(",\\s*}", "}")
                        .replaceAll(",\\s*]", "]");
                Object parsed = JSON.readValue(repaired, Object.class);
                return (parsed instanceof Map<?, ?> m) ? (Map<String, Object>) m : Map.of();
            } catch (JsonProcessingException ex) {
                return Map.of();
            }
        }
    }

    /**
     * 返回参数 JSON 字符串形式，用于 provider 历史 replay。
     *
     * @param arguments 参数对象
     * @return JSON 字符串
     */
    // 对标 Python base.py tool_arguments_json_for_replay()
    public static String toolArgumentsJsonForReplay(Object arguments) {
        try {
            return JSON.writeValueAsString(toolArgumentsObjectForReplay(arguments));
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
