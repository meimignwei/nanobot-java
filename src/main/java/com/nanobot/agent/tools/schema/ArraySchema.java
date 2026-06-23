package com.nanobot.agent.tools.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数组参数 Schema，元素 Schema 由 {@code items} 指定（默认为 StringSchema）。
 *
 * <p>对标 Python {@code nanobot/agent/tools/schema.py ArraySchema}（34 行）。
 */
public class ArraySchema extends Schema {

    private final Schema itemsSchema;
    private final String description;
    private final Integer minItems;
    private final Integer maxItems;
    private final boolean nullable;

    /**
     * 构造数组 Schema，仅指定描述（items 默认为空字符串的 StringSchema）。
     *
     * @param description 字段描述文档
     */
    // 对标 Python ArraySchema.__init__(description="")
    public ArraySchema(String description) {
        this(new StringSchema(""), description, null, null, false);
    }

    /**
     * 构造数组 Schema，指定完整约束。
     *
     * @param itemsSchema 元素 Schema（可为 null，默认 StringSchema("")）
     * @param description 字段描述文档
     * @param minItems    最小元素数（可为 null）
     * @param maxItems    最大元素数（可为 null）
     * @param nullable    是否允许 null
     */
    // 对标 Python ArraySchema.__init__(items=None, description=, min_items=, max_items=, nullable=)
    public ArraySchema(Schema itemsSchema, String description,
                       Integer minItems, Integer maxItems, boolean nullable) {
        this.itemsSchema = itemsSchema != null ? itemsSchema : new StringSchema("");
        this.description = description;
        this.minItems = minItems;
        this.maxItems = maxItems;
        this.nullable = nullable;
    }

    /**
     * 返回此数组 Schema 的 JSON Schema 片段，包含 type、items、
     * description、minItems、maxItems 字段（如有设置）。
     *
     * @return JSON Schema 片段 Map
     */
    @Override
    // 对标 Python ArraySchema.to_json_schema()
    public Map<String, Object> toJsonSchema() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", nullable ? List.of("array", "null") : "array");
        d.put("items", Schema.fragment(itemsSchema));
        if (description != null && !description.isEmpty()) d.put("description", description);
        if (minItems != null) d.put("minItems", minItems);
        if (maxItems != null) d.put("maxItems", maxItems);
        return d;
    }
}
