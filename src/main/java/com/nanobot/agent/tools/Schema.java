package com.nanobot.agent.tools;

import jakarta.annotation.Nullable;

import java.util.*;

/**
 * JSON Schema 片段抽象基类，描述工具参数。
 * 对应 Python Schema（base.py 行 28-122）+ 具体类型（schema.py）。
 *
 * <p>提供：JSON Schema 类型解析、值校验（validateJsonSchemaValue）、
 * 具体 schema 类型（StringSchema、IntegerSchema、BooleanSchema、ArraySchema、ObjectSchema）。</p>
 */
public abstract class Schema {

    /** JSON Schema type → Java 类型映射 */
    static final Map<String, Set<Class<?>>> JSON_TYPE_MAP = Map.of(
            "string", Set.of(String.class),
            "integer", Set.of(Integer.class, Long.class),
            "number", Set.of(Integer.class, Long.class, Float.class, Double.class),
            "boolean", Set.of(Boolean.class),
            "array", Set.of(List.class),
            "object", Set.of(Map.class)
    );

    // ---- 静态辅助方法（对应 Python Schema.resolve_json_schema_type / subpath） ----

    /** 解析 JSON Schema type 字段（支持 ["string","null"] 数组形式）。
     *  对应 Python resolve_json_schema_type()。 */
    @Nullable
    public static String resolveJsonSchemaType(Object t) {
        if (t instanceof List<?> list) {
            return list.stream()
                    .filter(x -> !"null".equals(x))
                    .map(Object::toString)
                    .findFirst()
                    .orElse(null);
        }
        return t != null ? t.toString() : null;
    }

    /** 构建嵌套路径，如 "parent.child" */
    public static String subpath(String path, String key) {
        return !path.isEmpty() ? path + "." + key : key;
    }

    /**
     * 按 JSON Schema 片段校验值。
     * 对应 Python Schema.validate_json_schema_value。
     */
    @SuppressWarnings("unchecked")
    public static List<String> validateJsonSchemaValue(
            @Nullable Object val,
            Map<String, Object> schema,
            String path
    ) {
        Object rawType = schema.get("type");
        String type = resolveJsonSchemaType(rawType);
        boolean nullable = (rawType instanceof List<?> list && list.contains("null"))
                || Boolean.TRUE.equals(schema.get("nullable"));
        String label = !path.isEmpty() ? path : "parameter";

        if (nullable && val == null) return List.of();

        if ("integer".equals(type)) {
            if (!(val instanceof Integer || val instanceof Long) || val instanceof Boolean) {
                return List.of(label + " should be integer");
            }
        } else if ("number".equals(type)) {
            if (!(val instanceof Number) || val instanceof Boolean) {
                return List.of(label + " should be number");
            }
        } else if (JSON_TYPE_MAP.containsKey(type) && !"integer".equals(type) && !"number".equals(type)) {
            boolean match = false;
            for (Class<?> c : JSON_TYPE_MAP.get(type)) {
                if (c.isInstance(val)) { match = true; break; }
            }
            if (!match) return List.of(label + " should be " + type);
        }

        List<String> errors = new ArrayList<>();

        if (schema.containsKey("enum")) {
            List<?> enumVals = (List<?>) schema.get("enum");
            if (!enumVals.contains(val)) {
                errors.add(label + " must be one of " + enumVals);
            }
        }

        if ("integer".equals(type) || "number".equals(type)) {
            Number n = (Number) val;
            if (schema.containsKey("minimum") && n.doubleValue() < ((Number) schema.get("minimum")).doubleValue()) {
                errors.add(label + " must be >= " + schema.get("minimum"));
            }
            if (schema.containsKey("maximum") && n.doubleValue() > ((Number) schema.get("maximum")).doubleValue()) {
                errors.add(label + " must be <= " + schema.get("maximum"));
            }
        }

        if ("string".equals(type)) {
            String s = (String) val;
            if (schema.containsKey("minLength") && s.length() < ((Number) schema.get("minLength")).intValue()) {
                errors.add(label + " must be at least " + schema.get("minLength") + " chars");
            }
            if (schema.containsKey("maxLength") && s.length() > ((Number) schema.get("maxLength")).intValue()) {
                errors.add(label + " must be at most " + schema.get("maxLength") + " chars");
            }
        }

        if ("object".equals(type) && val instanceof Map) {
            Map<String, Object> mapVal = (Map<String, Object>) val;
            Map<String, Object> props = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
            List<String> required = (List<String>) schema.getOrDefault("required", List.of());
            for (String k : required) {
                if (!mapVal.containsKey(k)) {
                    errors.add("missing required " + subpath(path, k));
                }
            }
            for (var entry : mapVal.entrySet()) {
                if (props.containsKey(entry.getKey())) {
                    errors.addAll(validateJsonSchemaValue(
                            entry.getValue(),
                            (Map<String, Object>) props.get(entry.getKey()),
                            subpath(path, entry.getKey())));
                }
            }
        }

        if ("array".equals(type) && val instanceof List) {
            List<?> listVal = (List<?>) val;
            if (schema.containsKey("minItems") && listVal.size() < ((Number) schema.get("minItems")).intValue()) {
                errors.add(label + " must have at least " + schema.get("minItems") + " items");
            }
            if (schema.containsKey("maxItems") && listVal.size() > ((Number) schema.get("maxItems")).intValue()) {
                errors.add(label + " must be at most " + schema.get("maxItems") + " items");
            }
            if (schema.containsKey("items")) {
                String prefix = path.isEmpty() ? "[{}]" : path + "[{}]";
                Map<String, Object> itemsSchema = (Map<String, Object>) schema.get("items");
                for (int i = 0; i < listVal.size(); i++) {
                    errors.addAll(validateJsonSchemaValue(
                            listVal.get(i), itemsSchema, prefix.replace("{}", String.valueOf(i))));
                }
            }
        }

        return errors;
    }

    /**
     * 将 Schema 实例或 Map 规范化为 JSON Schema 片段。
     * 对应 Python Schema.fragment。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> fragment(Object value) {
        if (value instanceof Schema s) return s.toJsonSchema();
        if (value instanceof Map) return (Map<String, Object>) value;
        throw new IllegalArgumentException("Expected schema object or dict, got " + value.getClass().getSimpleName());
    }

    // ---- 抽象方法 ----

    /** 转为 JSON Schema Map */
    public abstract Map<String, Object> toJsonSchema();

    /** 校验值 */
    public List<String> validateValue(Object value, String path) {
        return validateJsonSchemaValue(value, toJsonSchema(), path);
    }

    // ---- 具体 schema 类型 ----

    /** 字符串类型 schema */
    public static class StringSchema extends Schema {
        private final String description;
        @Nullable private final Integer minLength, maxLength;
        @Nullable private final List<String> enumVals;
        private final boolean nullable;

        public StringSchema(String description) {
            this(description, null, null, null, false);
        }

        public StringSchema(String description, @Nullable Integer minLength,
                            @Nullable Integer maxLength, @Nullable List<String> enumVals,
                            boolean nullable) {
            this.description = description;
            this.minLength = minLength;
            this.maxLength = maxLength;
            this.enumVals = enumVals;
            this.nullable = nullable;
        }

        @Override
        public Map<String, Object> toJsonSchema() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("type", nullable ? List.of("string", "null") : "string");
            if (description != null && !description.isEmpty()) d.put("description", description);
            if (minLength != null) d.put("minLength", minLength);
            if (maxLength != null) d.put("maxLength", maxLength);
            if (enumVals != null) d.put("enum", enumVals);
            return d;
        }
    }

    /** 整数类型 schema */
    public static class IntegerSchema extends Schema {
        private final String description;
        @Nullable private final Integer minimum, maximum;
        @Nullable private final List<Integer> enumVals;
        private final boolean nullable;

        public IntegerSchema(String description) {
            this(description, null, null, null, false);
        }

        public IntegerSchema(String description, @Nullable Integer minimum,
                             @Nullable Integer maximum, @Nullable List<Integer> enumVals,
                             boolean nullable) {
            this.description = description;
            this.minimum = minimum;
            this.maximum = maximum;
            this.enumVals = enumVals;
            this.nullable = nullable;
        }

        @Override
        public Map<String, Object> toJsonSchema() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("type", nullable ? List.of("integer", "null") : "integer");
            if (description != null && !description.isEmpty()) d.put("description", description);
            if (minimum != null) d.put("minimum", minimum);
            if (maximum != null) d.put("maximum", maximum);
            if (enumVals != null) d.put("enum", enumVals);
            return d;
        }
    }

    /** 布尔类型 schema */
    public static class BooleanSchema extends Schema {
        private final String description;
        @Nullable private final Boolean defaultValue;
        private final boolean nullable;

        public BooleanSchema(String description) {
            this(description, null, false);
        }

        public BooleanSchema(String description, @Nullable Boolean defaultValue, boolean nullable) {
            this.description = description;
            this.defaultValue = defaultValue;
            this.nullable = nullable;
        }

        @Override
        public Map<String, Object> toJsonSchema() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("type", nullable ? List.of("boolean", "null") : "boolean");
            if (description != null && !description.isEmpty()) d.put("description", description);
            if (defaultValue != null) d.put("default", defaultValue);
            return d;
        }
    }

    /** 数组类型 schema */
    public static class ArraySchema extends Schema {
        private final Schema itemsSchema;
        private final String description;
        @Nullable private final Integer minItems, maxItems;
        private final boolean nullable;

        public ArraySchema(Schema items, String description) {
            this(items, description, null, null, false);
        }

        public ArraySchema(Schema items, String description,
                           @Nullable Integer minItems, @Nullable Integer maxItems,
                           boolean nullable) {
            this.itemsSchema = items != null ? items : new StringSchema("");
            this.description = description;
            this.minItems = minItems;
            this.maxItems = maxItems;
            this.nullable = nullable;
        }

        @Override
        public Map<String, Object> toJsonSchema() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("type", nullable ? List.of("array", "null") : "array");
            d.put("items", fragment(itemsSchema));
            if (description != null && !description.isEmpty()) d.put("description", description);
            if (minItems != null) d.put("minItems", minItems);
            if (maxItems != null) d.put("maxItems", maxItems);
            return d;
        }
    }

    /** 对象类型 schema */
    public static class ObjectSchema extends Schema {
        private final Map<String, Object> propRaw;
        private final List<String> required;
        private final String description;
        private final boolean nullable;

        public ObjectSchema(Map<String, Object> properties, List<String> required, String description) {
            this(properties, required, description, false);
        }

        public ObjectSchema(Map<String, Object> properties, List<String> required,
                            String description, boolean nullable) {
            this.propRaw = properties != null ? properties : Map.of();
            this.required = required != null ? required : List.of();
            this.description = description;
            this.nullable = nullable;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Map<String, Object> toJsonSchema() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("type", nullable ? List.of("object", "null") : "object");
            Map<String, Object> props = new LinkedHashMap<>();
            for (var e : propRaw.entrySet()) {
                props.put(e.getKey(), fragment(e.getValue()));
            }
            d.put("properties", props);
            if (!required.isEmpty()) d.put("required", required);
            if (description != null && !description.isEmpty()) d.put("description", description);
            return d;
        }
    }

    /**
     * 便捷方法：构建根工具参数 schema。
     * 对应 Python tool_parameters_schema。
     */
    public static Map<String, Object> toolParametersSchema(
            List<String> required, String description, Map<String, Object> properties) {
        return new ObjectSchema(properties, required, description).toJsonSchema();
    }
}
