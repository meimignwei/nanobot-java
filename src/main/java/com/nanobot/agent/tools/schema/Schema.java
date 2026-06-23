package com.nanobot.agent.tools.schema;

import java.util.*;

/**
 * JSON Schema 片段类型的抽象基类，用于描述和校验工具参数。
 *
 * <p>对标 Python {@code nanobot/agent/tools/base.py Schema}（122 行）。
 * 具体子类：StringSchema、IntegerSchema、NumberSchema、BooleanSchema、
 * ArraySchema、ObjectSchema。提供共享的静态校验方法
 * {@link #validateJsonSchemaValue} 和片段标准化方法 {@link #fragment}。
 */
public abstract class Schema {

    /**
     * 从 JSON Schema 的 type 字段中解析出非 null 的类型名。
     * 例如 {@code ["string", "null"]} 返回 {@code "string"}。
     *
     * @param rawType JSON Schema type 字段值（String 或 List）
     * @return 非 null 的类型名，无法解析时返回 null
     */
    // 对标 Python Schema.resolve_json_schema_type()
    public static String resolveJsonSchemaType(Object rawType) {
        if (rawType instanceof List<?> list) {
            for (Object x : list) {
                if (!"null".equals(x)) {
                    return (String) x;
                }
            }
            return null;
        }
        return (String) rawType;
    }

    /**
     * 用 JSON Schema 片段校验一个值，返回错误消息列表（空列表表示通过）。
     *
     * @param val    待校验的值
     * @param schema JSON Schema 片段 Map
     * @param path   用于错误消息的点号路径（如 "query.count"）
     * @return 错误消息列表，空列表表示校验通过
     */
    // 对标 Python Schema.validate_json_schema_value()
    @SuppressWarnings("unchecked")
    public static List<String> validateJsonSchemaValue(
            Object val,
            Map<String, Object> schema,
            String path) {

        List<String> errors = new ArrayList<>();
        Object rawType = schema.get("type");
        boolean nullable = false;

        if (rawType instanceof List<?> list) {
            nullable = list.contains("null");
        }
        Boolean nullableFlag = (Boolean) schema.get("nullable");
        if (Boolean.TRUE.equals(nullableFlag)) {
            nullable = true;
        }

        String type = resolveJsonSchemaType(rawType);
        String label = (path != null && !path.isEmpty()) ? path : "parameter";

        // null 处理
        if (nullable && val == null) {
            return errors;
        }

        // 类型检查
        if ("integer".equals(type)) {
            if (!(val instanceof Integer) || val instanceof Boolean) {
                errors.add(label + " should be integer");
                return errors;
            }
        } else if ("number".equals(type)) {
            if (!(val instanceof Number) || val instanceof Boolean) {
                errors.add(label + " should be number");
                return errors;
            }
        } else if (type != null && !"integer".equals(type) && !"number".equals(type)) {
            boolean typeOk = switch (type) {
                case "string" -> val instanceof String;
                case "boolean" -> val instanceof Boolean;
                case "array" -> val instanceof List;
                case "object" -> val instanceof Map;
                default -> false;
            };
            if (!typeOk) {
                errors.add(label + " should be " + type);
                return errors;
            }
        }

        // 约束校验
        Object enumVal = schema.get("enum");
        if (enumVal instanceof List<?> enumList && !enumList.contains(val)) {
            errors.add(label + " must be one of " + enumList);
        }

        if (type != null && ("integer".equals(type) || "number".equals(type))) {
            Number numVal = (Number) val;
            Object minObj = schema.get("minimum");
            if (minObj instanceof Number min && numVal.doubleValue() < min.doubleValue()) {
                errors.add(label + " must be >= " + min);
            }
            Object maxObj = schema.get("maximum");
            if (maxObj instanceof Number max && numVal.doubleValue() > max.doubleValue()) {
                errors.add(label + " must be <= " + max);
            }
        }

        if ("string".equals(type)) {
            String strVal = (String) val;
            Object minLenObj = schema.get("minLength");
            if (minLenObj instanceof Integer minLen && strVal.length() < minLen) {
                errors.add(label + " must be at least " + minLen + " chars");
            }
            Object maxLenObj = schema.get("maxLength");
            if (maxLenObj instanceof Integer maxLen && strVal.length() > maxLen) {
                errors.add(label + " must be at most " + maxLen + " chars");
            }
        }

        if ("object".equals(type)) {
            Map<String, Object> valMap = (Map<String, Object>) val;
            Map<String, Object> props =
                    (Map<String, Object>) schema.getOrDefault("properties", Map.of());
            Object requiredObj = schema.get("required");
            if (requiredObj instanceof List<?> requiredList) {
                for (Object k : requiredList) {
                    String key = (String) k;
                    if (!valMap.containsKey(key)) {
                        errors.add("missing required " + subpath(path, key));
                    }
                }
            }
            for (Map.Entry<String, Object> entry : valMap.entrySet()) {
                String key = entry.getKey();
                Object childSchema = props.get(key);
                if (childSchema instanceof Map<?, ?> childMap) {
                    errors.addAll(validateJsonSchemaValue(
                            entry.getValue(),
                            (Map<String, Object>) childMap,
                            subpath(path, key)));
                }
            }
        }

        if ("array".equals(type)) {
            List<Object> arrVal = (List<Object>) val;
            Object minItemsObj = schema.get("minItems");
            if (minItemsObj instanceof Integer minItems && arrVal.size() < minItems) {
                errors.add(label + " must have at least " + minItems + " items");
            }
            Object maxItemsObj = schema.get("maxItems");
            if (maxItemsObj instanceof Integer maxItems && arrVal.size() > maxItems) {
                errors.add(label + " must be at most " + maxItems + " items");
            }
            Object itemsSchema = schema.get("items");
            if (itemsSchema instanceof Map<?, ?> itemsMap) {
                String prefix = (path != null && !path.isEmpty()) ? path + "[{}]" : "[{}]";
                for (int i = 0; i < arrVal.size(); i++) {
                    errors.addAll(validateJsonSchemaValue(
                            arrVal.get(i),
                            (Map<String, Object>) itemsMap,
                            prefix.replace("{}", String.valueOf(i))));
                }
            }
        }
        return errors;
    }

    /**
     * 拼接路径字符串，用于嵌套字段的错误消息路径。
     *
     * @param path 父级路径（可为空）
     * @param key  当前字段名
     * @return 拼接后的路径，如 "parent.child"
     */
    // 对标 Python Schema.subpath()
    public static String subpath(String path, String key) {
        return (path != null && !path.isEmpty()) ? path + "." + key : key;
    }

    /**
     * 将 Schema 实例或已有的 Map 标准化为 JSON Schema 片段。
     * Schema 实例调用其 {@link #toJsonSchema()}，Map 直接返回。
     *
     * @param value Schema 实例或 Map
     * @return JSON Schema 片段 Map
     * @throws IllegalArgumentException 如果参数类型不正确
     */
    // 对标 Python Schema.fragment()
    @SuppressWarnings("unchecked")
    public static Map<String, Object> fragment(Object value) {
        if (value instanceof Schema schema) {
            return schema.toJsonSchema();
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException(
                "Expected Schema or Map, got " + value.getClass().getName());
    }

    /**
     * 返回此 Schema 的 JSON Schema 片段 Map，兼容
     * {@link #validateJsonSchemaValue} 和 {@code Tool.parameters}。
     *
     * @return JSON Schema 片段 Map
     */
    // 对标 Python Schema.to_json_schema()
    public abstract Map<String, Object> toJsonSchema();

    /**
     * 用此 Schema 校验单个值，返回错误消息列表（空列表表示通过）。
     * 子类可覆写以添加额外规则。
     *
     * @param value 待校验的值
     * @param path  用于错误消息的点号路径
     * @return 错误消息列表，空列表表示校验通过
     */
    // 对标 Python Schema.validate_value()
    public List<String> validateValue(Object value, String path) {
        return validateJsonSchemaValue(value, this.toJsonSchema(), path);
    }
}
