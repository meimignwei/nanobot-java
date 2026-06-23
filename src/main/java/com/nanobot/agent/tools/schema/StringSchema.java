package com.nanobot.agent.tools.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字符串参数 Schema，支持可选的字符串长度约束和枚举值。
 *
 * <p>对标 Python {@code nanobot/agent/tools/schema.py StringSchema}（52 行）。
 */
public class StringSchema extends Schema {

    private final String description;
    private final Integer minLength;
    private final Integer maxLength;
    private final List<Object> enumValues;
    private final boolean nullable;

    /**
     * 构造字符串 Schema，仅指定描述。
     *
     * @param description 字段描述文档
     */
    // 对标 Python StringSchema.__init__(description="")
    public StringSchema(String description) {
        this(description, null, null, null, false);
    }

    /**
     * 构造字符串 Schema，指定完整约束。
     *
     * @param description 字段描述文档
     * @param minLength   最小长度（可为 null）
     * @param maxLength   最大长度（可为 null）
     * @param enumValues  可选的枚举值列表（可为 null）
     * @param nullable    是否允许 null
     */
    // 对标 Python StringSchema.__init__(description, min_length=, max_length=, enum=, nullable=)
    public StringSchema(String description, Integer minLength, Integer maxLength,
                        List<Object> enumValues, boolean nullable) {
        this.description = description;
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.enumValues = enumValues;
        this.nullable = nullable;
    }

    /**
     * 返回此字符串 Schema 的 JSON Schema 片段，包含 type、description、
     * minLength、maxLength、enum 字段（如有设置）。
     *
     * @return JSON Schema 片段 Map
     */
    @Override
    // 对标 Python StringSchema.to_json_schema()
    public Map<String, Object> toJsonSchema() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", nullable ? List.of("string", "null") : "string");
        if (description != null && !description.isEmpty()) d.put("description", description);
        if (minLength != null) d.put("minLength", minLength);
        if (maxLength != null) d.put("maxLength", maxLength);
        if (enumValues != null) d.put("enum", enumValues);
        return d;
    }
}
