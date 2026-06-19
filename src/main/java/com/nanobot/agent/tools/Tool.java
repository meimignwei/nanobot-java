package com.nanobot.agent.tools;

import jakarta.annotation.Nullable;

import java.util.*;

/**
 * Agent 能力抽象基类：读写文件、执行命令等。
 * 对应 Python Tool（base.py 行 124-297）。
 *
 * <p>子类需实现 name()、description()、parameters()、execute()。
 * 内置参数类型转换（castParams）和 JSON Schema 校验（validateParams）。</p>
 */
public abstract class Tool {

    private static final Set<String> BOOL_TRUE = Set.of("true", "1", "yes");
    private static final Set<String> BOOL_FALSE = Set.of("false", "0", "no");

    /** 解析 JSON Schema type 字段（支持数组形式如 ["string","null"]）。
     *  对应 Python resolve_type()。 */
    @Nullable
    public static String resolveType(Object t) {
        return Schema.resolveJsonSchemaType(t);
    }

    // ---- 抽象属性 ----

    /** 工具名称。对应 Python Tool.name property。 */
    public abstract String name();
    /** 工具描述。对应 Python Tool.description property。 */
    public abstract String description();
    /** 工具参数 JSON Schema。对应 Python Tool.parameters property。 */
    public abstract Map<String, Object> parameters();

    // ---- 可覆盖属性 ----

    /** 是否只读工具。对应 Python Tool.is_read_only()。 */
    public boolean isReadOnly() { return false; }

    /** 是否并发安全。对应 Python Tool.is_concurrency_safe()。 */
    public boolean isConcurrencySafe() {
        return isReadOnly() && !isExclusive();
    }

    /** 是否独占（不允许并发执行）。对应 Python Tool.is_exclusive()。 */
    public boolean isExclusive() { return false; }

    /** 配置键名。对应 Python Tool.config_key()。 */
    public String configKey() { return ""; }

    /** 配置类。对应 Python Tool.config_class()。 */
    @Nullable
    @SuppressWarnings("rawtypes")
    public Class configClass() { return null; }

    /** 是否启用。对应 Python Tool.is_enabled()。 */
    public boolean isEnabled(ToolContext ctx) { return true; }

    /** 工厂方法：创建工具实例。对应 Python Tool.create() classmethod。
     *  在 Java 中，工厂逻辑位于 ToolLoader。 */
    public static Tool create(ToolContext ctx, Class<? extends Tool> toolClass) {
        try {
            return toolClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool: " + toolClass.getSimpleName(), e);
        }
    }

    // ---- 核心抽象 ----

    /**
     * 执行工具，返回 String 或 List&lt;Map&lt;String, Object&gt;&gt;。
     * 对应 Python Tool.execute(self, **kwargs) -> Any。
     */
    public abstract Object execute(Map<String, Object> params, ToolContext ctx) throws Exception;

    // ---- 参数类型转换 / 校验 ----

    /** 按 schema 转换参数类型（字符串→数字/布尔等）。
     *  对应 Python Tool.cast_params()。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> castParams(Map<String, Object> params) {
        Map<String, Object> schema = parameters();
        if (schema == null || !"object".equals(schema.get("type"))) return params;
        return castObject(params, schema);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castObject(Object obj, Map<String, Object> schema) {
        if (!(obj instanceof Map)) return (Map<String, Object>) obj;
        Map<String, Object> map = (Map<String, Object>) obj;
        Map<String, Object> props = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
        Map<String, Object> result = new LinkedHashMap<>();
        for (var e : map.entrySet()) {
            if (props.containsKey(e.getKey())) {
                result.put(e.getKey(), castValue(e.getValue(), (Map<String, Object>) props.get(e.getKey())));
            } else {
                result.put(e.getKey(), e.getValue());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object castValue(Object val, Map<String, Object> schema) {
        String t = resolveType(schema.get("type"));

        if ("boolean".equals(t) && val instanceof Boolean) return val;
        if ("integer".equals(t) && val instanceof Integer && !(val instanceof Boolean)) return val;
        if (t != null && Schema.JSON_TYPE_MAP.containsKey(t)
                && !"boolean".equals(t) && !"integer".equals(t)
                && !"array".equals(t) && !"object".equals(t)) {
            boolean match = false;
            for (Class<?> c : Schema.JSON_TYPE_MAP.get(t)) {
                if (c.isInstance(val)) { match = true; break; }
            }
            if (match) return val;
        }

        if (val instanceof String s) {
            if ("integer".equals(t)) {
                try { return Integer.parseInt(s); } catch (NumberFormatException ex) { return val; }
            }
            if ("number".equals(t)) {
                try { return Double.parseDouble(s); } catch (NumberFormatException ex) { return val; }
            }
        }

        if ("string".equals(t)) {
            return val == null ? null : val.toString();
        }

        if ("boolean".equals(t) && val instanceof String s) {
            String low = s.toLowerCase();
            if (BOOL_TRUE.contains(low)) return true;
            if (BOOL_FALSE.contains(low)) return false;
            return val;
        }

        if ("array".equals(t) && val instanceof List list) {
            Map<String, Object> items = (Map<String, Object>) schema.get("items");
            if (items != null) {
                List<Object> result = new ArrayList<>();
                for (Object x : list) result.add(castValue(x, items));
                return result;
            }
            return val;
        }

        if ("object".equals(t) && val instanceof Map) {
            return castObject(val, schema);
        }

        return val;
    }

    /** 校验参数是否符合 schema。
     *  对应 Python Tool.validate_params()。 */
    public List<String> validateParams(Map<String, Object> params) {
        if (!(params instanceof Map)) {
            return List.of("parameters must be an object, got " + params.getClass().getSimpleName());
        }
        Map<String, Object> schema = parameters();
        if (schema == null || !"object".equals(schema.get("type"))) {
            throw new IllegalArgumentException("Schema must be object type, got " +
                    (schema != null ? schema.get("type") : "null"));
        }
        Map<String, Object> objSchema = new LinkedHashMap<>(schema);
        objSchema.put("type", "object");
        return Schema.validateJsonSchemaValue(params, objSchema, "");
    }

    // ---- Schema 输出 ----

    /** 输出 OpenAI 格式的工具 schema。
     *  对应 Python Tool.to_schema()。 */
    public Map<String, Object> toSchema() {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", name());
        fn.put("description", description());
        fn.put("parameters", parameters());
        return Map.of("type", "function", "function", fn);
    }
}
