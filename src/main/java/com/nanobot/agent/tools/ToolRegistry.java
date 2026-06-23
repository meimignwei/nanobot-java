package com.nanobot.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线程安全的工具注册表，管理工具生命周期。
 *
 * <p>对标 Python {@code nanobot/agent/tools/registry.py ToolRegistry}。
 * 功能：注册/注销、调用准备（cast + validate）、执行、
 * 导出 OpenAI 兼容 function 定义。定义列表在注册/注销后缓存失效以保证 prompt 稳定性。
 */
public class ToolRegistry {

    private final ConcurrentHashMap<String, Tool> tools = new ConcurrentHashMap<>();
    private volatile List<Map<String, Object>> cachedDefinitions;
    private static final ObjectMapper JSON = new ObjectMapper();

    // ==================== 注册 ====================

    /**
     * 注册工具，同名工具会被覆盖。
     *
     * @param tool 要注册的工具实例
     */
    public void register(Tool tool) {
        tools.put(tool.getName(), tool);
        cachedDefinitions = null;
    }

    /**
     * 按名称注销工具，不存在则无操作。
     *
     * @param name 工具名称
     */
    public void unregister(String name) {
        tools.remove(name);
        cachedDefinitions = null;
    }

    /**
     * 按名称获取工具。
     *
     * @param name 工具名称
     * @return 工具实例，未找到返回 null
     */
    public Tool get(String name) {
        return tools.get(name);
    }

    /**
     * 检查指定名称的工具是否已注册。
     *
     * @param name 工具名称
     * @return 存在返回 true
     */
    public boolean has(String name) {
        return tools.containsKey(name);
    }

    // ==================== 名称解析 ====================

    /**
     * 标准化工具名用于模糊匹配（仅用于建议，不用于执行）。
     * 去除非字母数字字符并转为小写。
     *
     * @param name 原始名称
     * @return 标准化后的查找 key
     */
    public static String lookupKey(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char ch : name.toCharArray()) {
            if (Character.isLetterOrDigit(ch)) {
                sb.append(Character.toLowerCase(ch));
            }
        }
        return sb.toString();
    }

    /**
     * 模糊名称建议：恰好有一个已注册工具标准化后匹配时返回其注册名。
     *
     * @param name 用户输入的近似名称
     * @return 匹配的注册名称，不唯一或不存在时返回 null
     */
    public String suggestName(String name) {
        String key = lookupKey(name);
        if (key.isEmpty()) return null;
        List<String> matches = new ArrayList<>();
        for (String registered : tools.keySet()) {
            if (key.equals(lookupKey(registered))) {
                matches.add(registered);
            }
        }
        return (matches.size() == 1) ? matches.get(0) : null;
    }

    // ==================== 定义导出 ====================

    /**
     * 获取排序稳定的工具定义列表，用于缓存友好的 prompt 构建。
     * 内置工具按名称排序在前，MCP 工具（"mcp_" 前缀）在后。
     * 结果缓存至下一次 register/unregister。
     *
     * @return OpenAI function schema 列表
     */
    public List<Map<String, Object>> getDefinitions() {
        List<Map<String, Object>> defs = cachedDefinitions;
        if (defs != null) return defs;

        List<Map<String, Object>> allDefs = new ArrayList<>();
        for (Tool tool : tools.values()) {
            allDefs.add(tool.toSchema());
        }

        List<Map<String, Object>> builtins = new ArrayList<>();
        List<Map<String, Object>> mcpTools = new ArrayList<>();
        for (Map<String, Object> schema : allDefs) {
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
        cachedDefinitions = result;
        return result;
    }

    @SuppressWarnings("unchecked")
    private static String schemaName(Map<String, Object> schema) {
        Object fn = schema.get("function");
        if (fn instanceof Map<?, ?> fnMap) {
            Map<String, Object> fnMapTyped = (Map<String, Object>) fnMap;
            Object name = fnMapTyped.get("name");
            return name instanceof String s ? s : "";
        }
        Object name = schema.get("name");
        return name instanceof String s ? s : "";
    }

    // ==================== 调用准备 ====================

    /**
     * {@link #prepareCall} 的结果：
     * tool — 解析后的 Tool 实例（错误时为 null），
     * params — 类型转换后的参数，
     * error — 成功时为 null。
     */
    public record PreparedCall(Tool tool, Map<String, Object> params, String error) {}

    /**
     * 解析、强制转换、类型转换并校验一次工具调用。
     *
     * <p>处理 LLM 常见的 {@code {"arguments": "{\\"key\\": \\"value\\"}"}} 格式，
     * 解包并 JSON 解析 arguments 字符串。
     *
     * @param name      工具名称
     * @param rawParams 原始参数（可能是 String JSON、Map 或 arguments 包装）
     * @return PreparedCall 结果
     */
    public PreparedCall prepareCall(String name, Object rawParams) {
        Tool tool = tools.get(name);
        if (tool == null) {
            String suggestion = suggestName(name);
            String hint = (suggestion != null)
                    ? " Did you mean '" + suggestion + "'? Tool names must match exactly."
                    : "";
            return new PreparedCall(
                    null, null,
                    "Error: Tool '" + name + "' not found." + hint
                            + " Available: " + tools.keySet());
        }

        Object coerced = coerceParams(tool, rawParams);
        if (!(coerced instanceof Map)) {
            return new PreparedCall(
                    tool, null,
                    "Error: Tool '" + name + "' parameters must be a JSON object, got "
                            + coerced.getClass().getSimpleName());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) coerced;

        Map<String, Object> castParams = tool.castParams(params);
        List<String> errors = tool.validateParams(castParams);
        if (!errors.isEmpty()) {
            return new PreparedCall(
                    tool, castParams,
                    "Error: Invalid parameters for tool '" + name + "': "
                            + String.join("; ", errors));
        }

        return new PreparedCall(tool, castParams, null);
    }

    /**
     * 强制转换原始参数：尝试 JSON 字符串解析，解包 {"arguments": ...} 包装。
     *
     * @param tool      目标工具
     * @param rawParams 原始参数
     * @return 转换后的参数
     */
    private static Object coerceParams(Tool tool, Object rawParams) {
        Object coerced = coerceArgumentValue(rawParams);
        return unwrapArgumentsPayload(tool, coerced);
    }

    /**
     * 如果值是 JSON 字符串（如 '{"x": 1}'），尝试解析为 Map。
     * 仅当字符串以 '{' 或 '[' 开头时尝试解析。
     *
     * @param value 原始参数值
     * @return 解析后的 Map 或原值
     */
    public static Object coerceArgumentValue(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof String str)) return value;

        String trimmed = str.strip();
        if (trimmed.isEmpty()) return Map.of();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return value;

        try {
            // 对标 Python json.loads: 支持 JSON 数组和对象
            if (trimmed.startsWith("[")) {
                return new ObjectMapper().readValue(trimmed, List.class);
            }
            return new ObjectMapper().readValue(trimmed, Map.class);
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * 解包 {"arguments": {...}} 格式，前提是该工具没有名为 "arguments" 的真实参数。
     *
     * @param tool   目标工具
     * @param params 参数值
     * @return 解包后的参数
     */
    private static Object unwrapArgumentsPayload(Tool tool, Object params) {
        if (!(params instanceof Map<?, ?> map)) return params;
        if (map.size() != 1 || !map.containsKey("arguments")) return params;

        Map<String, Object> toolParams = tool.getParameters();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>)
                (toolParams != null ? toolParams.getOrDefault("properties", Map.of()) : Map.of());

        if (properties.containsKey("arguments")) return params;

        return coerceArgumentValue(map.get("arguments"));
    }

    // ==================== 执行 ====================

    /**
     * 按名称和参数执行工具，处理解析、类型转换、校验和错误格式化。
     *
     * @param name   工具名称
     * @param params 原始参数
     * @return 执行结果的 CompletableFuture
     */
    public CompletableFuture<Object> execute(String name, Object params) {
        String hint = "\n\n[Analyze the error above and try a different approach.]";
        PreparedCall prepared = prepareCall(name, params);
        if (prepared.error() != null) {
            return CompletableFuture.completedFuture(prepared.error() + hint);
        }

        return prepared.tool().execute(prepared.params())
                .thenApply(result -> {
                    if (result instanceof String str && str.startsWith("Error")) {
                        return str + hint;
                    }
                    return result;
                })
                .exceptionally(e ->
                        "Error executing " + name + ": " + e.getMessage() + hint);
    }

    // ==================== 访问器 ====================

    /**
     * 返回所有已注册工具的名称列表。
     *
     * @return 工具名称不可变列表
     */
    public List<String> getToolNames() {
        return List.copyOf(tools.keySet());
    }

    /**
     * 返回已注册工具数量。
     *
     * @return 工具数量
     */
    public int size() {
        return tools.size();
    }

    /**
     * 检查指定名称的工具是否已注册。
     *
     * @param name 工具名称
     * @return 存在返回 true
     */
    public boolean contains(String name) {
        return tools.containsKey(name);
    }
}
