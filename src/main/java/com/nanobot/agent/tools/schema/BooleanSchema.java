package com.nanobot.agent.tools.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 布尔参数 Schema，支持可选的默认值。
 *
 * <p>对标 Python {@code nanobot/agent/tools/schema.py BooleanSchema}（24 行）。
 * Python 中 bool 不可子类化，因此独立为 BooleanSchema 类。
 */
public class BooleanSchema extends Schema {

    private final String description;
    private final Boolean defaultValue;
    private final boolean nullable;

    /**
     * 构造布尔 Schema，仅指定描述。
     *
     * @param description 字段描述文档
     */
    // 对标 Python BooleanSchema.__init__(description="")
    public BooleanSchema(String description) {
        this(description, null, false);
    }

    /**
     * 构造布尔 Schema，指定完整约束。
     *
     * @param description  字段描述文档
     * @param defaultValue 默认值（可为 null）
     * @param nullable     是否允许 null
     */
    // 对标 Python BooleanSchema.__init__(description=, default=, nullable=)
    public BooleanSchema(String description, Boolean defaultValue, boolean nullable) {
        this.description = description;
        this.defaultValue = defaultValue;
        this.nullable = nullable;
    }

    /**
     * 返回此布尔 Schema 的 JSON Schema 片段，包含 type、description、
     * default 字段（如有设置）。
     *
     * @return JSON Schema 片段 Map
     */
    @Override
    // 对标 Python BooleanSchema.to_json_schema()
    public Map<String, Object> toJsonSchema() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", nullable ? List.of("boolean", "null") : "boolean");
        if (description != null && !description.isEmpty()) d.put("description", description);
        if (defaultValue != null) d.put("default", defaultValue);
        return d;
    }
}
