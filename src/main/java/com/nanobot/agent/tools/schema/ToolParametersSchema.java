package com.nanobot.agent.tools.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具参数 Schema 的静态便捷工厂方法。
 *
 * <p>对标 Python {@code nanobot/agent/tools/schema.py tool_parameters_schema()}。
 * 构建根级工具参数 {@code {"type": "object", "properties": ...}}，
 * 用于 {@code Tool.parameters} 返回值。
 */
public final class ToolParametersSchema {

    private ToolParametersSchema() {}

    /**
     * 构建根级工具参数的 JSON Schema 对象，包含 properties 和 required 字段。
     *
     * @param required    必填字段名列表（可为 null 或空）
     * @param description  参数对象的描述文档（可为 null 或空）
     * @param properties  字段名到 Schema 实例的映射
     * @return 根级 JSON Schema Map，格式为 {@code {"type": "object", "properties": {...}}}
     */
    // 对标 Python tool_parameters_schema(required=None, description="", **properties)
    public static Map<String, Object> create(
            List<String> required,
            String description,
            Map<String, Schema> properties) {

        Map<String, Object> props = new LinkedHashMap<>();
        for (Map.Entry<String, Schema> e : properties.entrySet()) {
            props.put(e.getKey(), e.getValue().toJsonSchema());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "object");
        out.put("properties", props);
        if (required != null && !required.isEmpty()) {
            out.put("required", required);
        }
        if (description != null && !description.isEmpty()) {
            out.put("description", description);
        }
        return out;
    }
}
