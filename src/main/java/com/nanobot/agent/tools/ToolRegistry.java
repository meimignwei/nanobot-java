package com.nanobot.agent.tools;

import jakarta.annotation.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表，支持动态工具管理。
 * 对应 Python ToolRegistry（registry.py，183 行）。
 *
 * <p>职责：工具注册/注销、schema 定义缓存（builtin 排序在前，MCP 在后）、
 * 工具调用准备（名称建议、参数转换、校验）、工具执行。</p>
 */
public class ToolRegistry {

    /** 工具注册表，key 为工具名称 */
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    /** 缓存的工具定义列表（含 schema），注册变更时失效 */
    private volatile List<Map<String, Object>> cachedDefinitions = null;

    /** 注册工具。对应 Python ToolRegistry.register()。 */
    public void register(Tool tool) {
        tools.put(tool.name(), tool);
        cachedDefinitions = null;
    }

    /** 注销工具。对应 Python ToolRegistry.unregister()。 */
    public void unregister(String name) {
        tools.remove(name);
        cachedDefinitions = null;
    }

    /** 按名称获取工具。 */
    @Nullable
    public Tool get(String name) {
        return tools.get(name);
    }

    /** 检查工具是否存在。 */
    public boolean has(String name) {
        return tools.containsKey(name);
    }

    /** 返回所有已注册工具名称。 */
    public Set<String> toolNames() {
        return Set.copyOf(tools.keySet());
    }

    /** 已注册工具数量。 */
    public int size() {
        return tools.size();
    }

    // ---- 名称建议（对应 Python _lookup_key + _suggest_name） ----

    /** 规范化工具名称为查找键（仅保留字母数字，小写） */
    private static String lookupKey(String name) {
        StringBuilder sb = new StringBuilder();
        for (char ch : name.toCharArray()) {
            if (Character.isLetterOrDigit(ch)) sb.append(Character.toLowerCase(ch));
        }
        return sb.toString();
    }

    /** 根据输入建议唯一匹配的工具名称。 */
    @Nullable
    private String suggestName(String name) {
        String key = lookupKey(name != null ? name : "");
        if (key.isEmpty()) return null;
        List<String> matches = tools.keySet().stream()
                .filter(r -> lookupKey(r).equals(key))
                .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    // ---- Schema 名称提取 ----

    /** 从工具 schema 中提取名称 */
    @SuppressWarnings("unchecked")
    static String schemaName(Map<String, Object> schema) {
        Map<String, Object> fn = (Map<String, Object>) schema.get("function");
        if (fn != null) {
            Object name = fn.get("name");
            if (name instanceof String s) return s;
        }
        Object name = schema.get("name");
        return name instanceof String s ? s : "";
    }

    // ---- get_definitions ----

    /** 获取所有已注册工具的 schema 定义列表（builtin 排序在前，MCP 在后）。
     *  对应 Python ToolRegistry.get_definitions()。 */
    public List<Map<String, Object>> getDefinitions() {
        if (cachedDefinitions != null) return cachedDefinitions;

        List<Map<String, Object>> builtins = new ArrayList<>();
        List<Map<String, Object>> mcpTools = new ArrayList<>();

        for (Tool tool : tools.values()) {
            Map<String, Object> schema = tool.toSchema();
            String name = schemaName(schema);
            if (name.startsWith("mcp_")) {
                mcpTools.add(schema);
            } else {
                builtins.add(schema);
            }
        }

        builtins.sort(Comparator.comparing(ToolRegistry::schemaName));
        mcpTools.sort(Comparator.comparing(ToolRegistry::schemaName));

        List<Map<String, Object>> result = new ArrayList<>(builtins);
        result.addAll(mcpTools);
        cachedDefinitions = List.copyOf(result);
        return cachedDefinitions;
    }

    // ---- prepare_call ----

    /** 准备工具调用：名称解析、参数转换、校验。
     *  对应 Python ToolRegistry.prepare_call()。 */
    @SuppressWarnings("unchecked")
    public PreparedCall prepareCall(String name, Object params) {
        Tool tool = tools.get(name);
        if (tool == null) {
            String suggestion = suggestName(name);
            String hint = suggestion != null ? " Did you mean '" + suggestion + "'? Tool names must match exactly." : "";
            return new PreparedCall(null, params,
                    "Error: Tool '" + name + "' not found." + hint
                            + " Available: " + String.join(", ", toolNames()));
        }

        Object coerced = coerceParams(tool, params);
        if (!(coerced instanceof Map)) {
            return new PreparedCall(tool, coerced,
                    "Error: Tool '" + name + "' parameters must be a JSON object, got "
                            + coerced.getClass().getSimpleName()
                            + ". Use named parameters like tool_name(param1=\"value1\", param2=\"value2\")"
                            + " matching the tool schema.");
        }

        Map<String, Object> castParams = tool.castParams((Map<String, Object>) coerced);
        List<String> errors = tool.validateParams(castParams);
        if (!errors.isEmpty()) {
            return new PreparedCall(tool, castParams,
                    "Error: Invalid parameters for tool '" + name + "': " + String.join("; ", errors));
        }

        return new PreparedCall(tool, castParams, null);
    }

    // ---- execute ----

    /** 执行工具调用。对应 Python ToolRegistry.execute()。 */
    public Object execute(String name, Object params) {
        String hint = "\n\n[Analyze the error above and try a different approach.]";
        PreparedCall prepared = prepareCall(name, params);
        if (prepared.error != null) {
            return prepared.error + hint;
        }
        try {
            Tool tool = prepared.tool;
            assert tool != null;
            Object result = tool.execute((Map<String, Object>) prepared.params, ToolContext.current());
            if (result instanceof String s && s.startsWith("Error")) {
                return s + hint;
            }
            return result;
        } catch (Exception e) {
            return "Error executing " + name + ": " + e.getMessage() + hint;
        }
    }

    // ---- 参数转换辅助 ----

    @SuppressWarnings("unchecked")
    private static Object coerceParams(Tool tool, Object params) {
        Object coerced = coerceArgumentValue(params);
        return unwrapArgumentsPayload(tool, coerced);
    }

    /** 尝试将 JSON 字符串解析为 Map */
    @SuppressWarnings("unchecked")
    private static Object coerceArgumentValue(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof String s)) return value;
        String stripped = s.strip();
        if (stripped.isEmpty()) return Map.of();
        if (!stripped.startsWith("{") && !stripped.startsWith("[")) return value;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(stripped, Map.class);
        } catch (Exception e) {
            return value;
        }
    }

    /** 解包 {"arguments": {...}} 格式的参数 */
    @SuppressWarnings("unchecked")
    private static Object unwrapArgumentsPayload(Tool tool, Object params) {
        if (!(params instanceof Map map) || !Set.of("arguments").equals(map.keySet())) {
            return params;
        }
        Map<String, Object> props = (Map<String, Object>) (tool.parameters() != null
                ? tool.parameters().getOrDefault("properties", Map.of()) : Map.of());
        if (props.containsKey("arguments")) return params;
        return coerceArgumentValue(map.get("arguments"));
    }

    // ---- 内部类型 ----

    /** 准备好的工具调用（含工具引用、参数和可能的错误） */
    public record PreparedCall(@Nullable Tool tool, Object params, @Nullable String error) {}
}
