package com.nanobot.agent.tools.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数值参数 Schema（JSON number），支持可选的范围约束和枚举值。
 *
 * <p>对标 Python {@code nanobot/agent/tools/schema.py NumberSchema}（33 行）。
 */
public class NumberSchema extends Schema {

    private final double value;
    private final String description;
    private final Double minimum;
    private final Double maximum;
    private final List<Double> enumValues;
    private final boolean nullable;

    /**
     * 构造数值 Schema，仅指定描述（value 默认为 0.0）。
     *
     * @param description 字段描述文档
     */
    // 对标 Python NumberSchema.__init__(description="")
    public NumberSchema(String description) {
        this(0.0, description, null, null, null, false);
    }

    /**
     * 构造数值 Schema，指定完整约束。
     *
     * @param value       占位数值（对标 Python 位置参数 value）
     * @param description 字段描述文档
     * @param minimum     最小值（可为 null）
     * @param maximum     最大值（可为 null）
     * @param enumValues  可选的枚举值列表（可为 null）
     * @param nullable    是否允许 null
     */
    // 对标 Python NumberSchema.__init__(value=0.0, description=, minimum=, maximum=, enum=, nullable=)
    public NumberSchema(double value, String description, Double minimum, Double maximum,
                        List<Double> enumValues, boolean nullable) {
        this.value = value;
        this.description = description;
        this.minimum = minimum;
        this.maximum = maximum;
        this.enumValues = enumValues;
        this.nullable = nullable;
    }

    /**
     * 返回此数值 Schema 的 JSON Schema 片段，包含 type、description、
     * minimum、maximum、enum 字段（如有设置）。
     *
     * @return JSON Schema 片段 Map
     */
    @Override
    // 对标 Python NumberSchema.to_json_schema()
    public Map<String, Object> toJsonSchema() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", nullable ? List.of("number", "null") : "number");
        if (description != null && !description.isEmpty()) d.put("description", description);
        if (minimum != null) d.put("minimum", minimum);
        if (maximum != null) d.put("maximum", maximum);
        if (enumValues != null) d.put("enum", enumValues);
        return d;
    }
}
