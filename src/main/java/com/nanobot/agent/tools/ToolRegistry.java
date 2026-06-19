package com.nanobot.agent.tools;

import jakarta.annotation.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tool registry for dynamic tool management.
 * Port of Python ToolRegistry (registry.py, 183 lines).
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private volatile List<Map<String, Object>> cachedDefinitions = null;

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
        cachedDefinitions = null;
    }

    public void unregister(String name) {
        tools.remove(name);
        cachedDefinitions = null;
    }

    @Nullable
    public Tool get(String name) {
        return tools.get(name);
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    public Set<String> toolNames() {
        return Set.copyOf(tools.keySet());
    }

    public int size() {
        return tools.size();
    }

    // ---- Name suggestion (port of _lookup_key + _suggest_name) ----

    private static String lookupKey(String name) {
        StringBuilder sb = new StringBuilder();
        for (char ch : name.toCharArray()) {
            if (Character.isLetterOrDigit(ch)) sb.append(Character.toLowerCase(ch));
        }
        return sb.toString();
    }

    @Nullable
    private String suggestName(String name) {
        String key = lookupKey(name != null ? name : "");
        if (key.isEmpty()) return null;
        List<String> matches = tools.keySet().stream()
                .filter(r -> lookupKey(r).equals(key))
                .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    // ---- Schema name extraction ----

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

    // ---- coercion helpers ----

    @SuppressWarnings("unchecked")
    private static Object coerceParams(Tool tool, Object params) {
        Object coerced = coerceArgumentValue(params);
        return unwrapArgumentsPayload(tool, coerced);
    }

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

    // ---- inner types ----

    public record PreparedCall(@Nullable Tool tool, Object params, @Nullable String error) {}
}
