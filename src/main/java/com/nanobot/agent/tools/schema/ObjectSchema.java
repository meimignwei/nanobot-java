package com.nanobot.agent.tools.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对象参数 Schema，支持属性定义、必填字段列表和 additionalProperties 控制。
 *
 * <p>对标 Python {@code nanobot/agent/tools/schema.py ObjectSchema}（32 行）。
 */
public class ObjectSchema extends Schema {

    private final Map<String, Object> properties;
    private final List<String> required;
    private final String description;
    private final Object additionalProperties;
    private final boolean nullable;

    /**
     * 构造对象 Schema，仅指定描述。
     *
     * @param description 字段描述文档
     */
    // 对标 Python ObjectSchema.__init__(description="")
    public ObjectSchema(String description) {
        this(null, null, description, null, false);
    }

    /**
     * 构造对象 Schema，指定完整约束。
     *
     * @param properties           属性定义 Map（key 为字段名，value 为 Schema 或 Map）
     * @param required             必填字段名列表
     * @param description          字段描述文档
     * @param additionalProperties 是否允许额外属性（可为 Boolean 或 Schema Map）
     * @param nullable             是否允许 null
     */
    // 对标 Python ObjectSchema.__init__(properties=None, required=None, description="",
    //         additional_properties=None, nullable=False, **kwargs)
    public ObjectSchema(Map<String, Object> properties, List<String> required,
                        String description, Object additionalProperties, boolean nullable) {
        this.properties = properties != null ? properties : new LinkedHashMap<>();
        this.required = required != null ? required : new ArrayList<>();
        this.description = description;
        this.additionalProperties = additionalProperties;
        this.nullable = nullable;
    }

    /**
     * 返回此对象 Schema 的 JSON Schema 片段，包含 type、properties、
     * required、description、additionalProperties 字段（如有设置）。
     * 属性值通过 {@link Schema#fragment} 标准化。
     *
     * @return JSON Schema 片段 Map
     */
    @Override
    // 对标 Python ObjectSchema.to_json_schema()
    public Map<String, Object> toJsonSchema() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", nullable ? List.of("object", "null") : "object");
        Map<String, Object> props = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : properties.entrySet()) {
            props.put(e.getKey(), Schema.fragment(e.getValue()));
        }
        d.put("properties", props);
        if (!required.isEmpty()) d.put("required", required);
        if (description != null && !description.isEmpty()) d.put("description", description);
        if (additionalProperties != null) d.put("additionalProperties", additionalProperties);
        return d;
    }
}
