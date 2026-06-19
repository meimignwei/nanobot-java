package com.nanobot.agent.tools;

import jakarta.annotation.Nullable;

import java.util.*;

/**
 * Agent capability: read files, run commands, etc.
 * Port of Python Tool (base.py lines 124-297).
 */
public abstract class Tool {

    private static final Set<String> BOOL_TRUE = Set.of("true", "1", "yes");
    private static final Set<String> BOOL_FALSE = Set.of("false", "0", "no");

    @Nullable
    public static String resolveType(Object t) {
        return Schema.resolveJsonSchemaType(t);
    }

    // ---- Abstract properties ----

    public abstract String name();
    public abstract String description();
    public abstract Map<String, Object> parameters();

    // ---- Overridable ----

    public boolean isReadOnly() { return false; }

    public boolean isConcurrencySafe() {
        return isReadOnly() && !isExclusive();
    }

    public boolean isExclusive() { return false; }

    public String configKey() { return ""; }

    @Nullable
    @SuppressWarnings("rawtypes")
    public Class configClass() { return null; }

    public boolean isEnabled(ToolContext ctx) { return true; }

    /** Port of Python Tool.enabled classmethod / create classmethod.
     * In Java, factory logic lives in ToolLoader. */
    public static Tool create(ToolContext ctx, Class<? extends Tool> toolClass) {
        try {
            return toolClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool: " + toolClass.getSimpleName(), e);
        }
    }

    // ---- Core abstract ----

    /**
     * Execute the tool. Returns String or List&lt;Map&lt;String, Object&gt;&gt;.
     * Port of Python Tool.execute(self, **kwargs) -> Any.
     */
    public abstract Object execute(Map<String, Object> params, ToolContext ctx) throws Exception;

    // ---- Cast / Validate ----

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

    // ---- Schema output ----

    public Map<String, Object> toSchema() {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", name());
        fn.put("description", description());
        fn.put("parameters", parameters());
        return Map.of("type", "function", "function", fn);
    }
}
