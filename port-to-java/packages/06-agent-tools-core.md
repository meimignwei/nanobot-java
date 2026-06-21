# Package 06: Agent Tools Core — Tool Infrastructure

> **Language**: Java 21 + Spring Boot 3.2, Virtual Threads
> **Base Package**: `com.nanobot.agent.tools`
> **Source**: `nanobot/agent/tools/base.py`, `schema.py`, `registry.py`, `loader.py`, `context.py`, `sandbox.py`, `path_utils.py`, `file_state.py`

## Overview

This package provides the foundational infrastructure for all agent tools. It defines:

1. **Schema.java** — JSON Schema fragment types used to describe and validate tool parameters
2. **Tool.java** — Abstract base class for every tool the agent can invoke
3. **ToolRegistry.java** — Thread-safe registry managing tool lifecycle
4. **ToolLoader.java** — Spring-aligned discovery that scans classpath and loads plugins
5. **ToolContext.java** — Dependency injection container holding all cross-cutting references
6. **RequestContext.java** — Per-invocation routing metadata (channel, chat, session)
7. **FileStates.java** — Per-session read/write tracker for file deduplication and read-before-edit warnings
8. **Sandbox — Command wrapping for bwrap isolation

---

## Component 1: Schema.java — JSON Schema Fragment Types

### Package: `com.nanobot.agent.tools.schema`

### Design

Every `Tool` instance exposes a `parameters` property that is a `Map<String, Object>` conforming to a subset of JSON Schema. Individual parameter types are modeled as concrete `Schema` subclasses. The base `Schema` abstract class provides static validation methods used by `Tool.validateParams()`.

### Schema.java (Abstract Base)

```java
package com.nanobot.agent.tools.schema;

import java.util.*;

/**
 * Abstract base for JSON Schema fragments describing tool parameters.
 * Concrete types: StringSchema, IntegerSchema, NumberSchema,
 * BooleanSchema, ArraySchema, ObjectSchema.
 */
public abstract class Schema {

    /**
     * Return the non-null type name from a JSON Schema "type" field.
     * E.g. ["string", "null"] -> "string" (picks first non-null).
     */
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
     * Validate a value against a JSON Schema fragment dict.
     * Returns empty list on success, error messages otherwise.
     *
     * @param val    the value to validate
     * @param schema the JSON Schema fragment as a Map
     * @param path   dot-path for error messages (e.g. "query.count")
     * @return list of error messages (empty = valid)
     */
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

        // --- null handling ---
        if (nullable && val == null) {
            return errors;
        }

        // --- type checking ---
        if ("integer".equals(type)) {
            if (!(val instanceof Integer) || val instanceof Boolean) {
                errors.add(label + " should be integer");
                return errors;
            }
        } else if ("number".equals(type)) {
            if ((!(val instanceof Number) || val instanceof Boolean)) {
                errors.add(label + " should be number");
                return errors;
            }
        } else if (type != null && !type.equals("integer") && !type.equals("number")) {
            boolean typeOk = switch (type) {
                case "string"  -> val instanceof String;
                case "boolean" -> val instanceof Boolean;
                case "array"   -> val instanceof List;
                case "object"  -> val instanceof Map;
                default        -> false;
            };
            if (!typeOk) {
                errors.add(label + " should be " + type);
                return errors;
            }
        }

        // --- constraints ---
        Object enumVal = schema.get("enum");
        if (enumVal instanceof List<?> enumList && !enumList.contains(val)) {
            errors.add(label + " must be one of " + enumList);
        }

        if (type != null && (type.equals("integer") || type.equals("number"))) {
            Number numVal = (Number) val;
            Object minObj = schema.get("minimum");
            if (minObj instanceof Number min) {
                if (numVal.doubleValue() < min.doubleValue()) {
                    errors.add(label + " must be >= " + min);
                }
            }
            Object maxObj = schema.get("maximum");
            if (maxObj instanceof Number max) {
                if (numVal.doubleValue() > max.doubleValue()) {
                    errors.add(label + " must be <= " + max);
                }
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
            @SuppressWarnings("unchecked")
            Map<String, Object> valMap = (Map<String, Object>) val;
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>)
                    schema.getOrDefault("properties", Map.of());
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
                if (childSchema instanceof Map<?,?> childMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> childMapTyped = (Map<String, Object>) childMap;
                    errors.addAll(
                        validateJsonSchemaValue(entry.getValue(), childMapTyped, subpath(path, key)));
                }
            }
        }

        if ("array".equals(type)) {
            @SuppressWarnings("unchecked")
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
            if (itemsSchema instanceof Map<?,?> itemsMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> itemsMapTyped = (Map<String, Object>) itemsMap;
                String prefix = (path != null && !path.isEmpty()) ? path + "[{}]" : "[{}]";
                for (int i = 0; i < arrVal.size(); i++) {
                    errors.addAll(
                        validateJsonSchemaValue(arrVal.get(i), itemsMapTyped,
                            prefix.replace("{}", String.valueOf(i))));
                }
            }
        }
        return errors;
    }

    public static String subpath(String path, String key) {
        return (path != null && !path.isEmpty()) ? path + "." + key : key;
    }

    /**
     * Normalize a Schema instance or an existing Map to a JSON Schema fragment.
     */
    public static Map<String, Object> fragment(Object value) {
        if (value instanceof Schema schema) {
            return schema.toJsonSchema();
        }
        if (value instanceof Map<?,?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) map;
            return result;
        }
        throw new IllegalArgumentException(
                "Expected Schema or Map, got " + value.getClass().getName());
    }

    /** Return the JSON Schema fragment dict. */
    public abstract Map<String, Object> toJsonSchema();

    /**
     * Validate a single value against this schema.
     * Subclasses may override for extra rules.
     */
    public List<String> validateValue(Object value, String path) {
        return validateJsonSchemaValue(value, this.toJsonSchema(), path);
    }
}
```

### Schema Subclasses

```java
// StringSchema.java
public class StringSchema extends Schema {
    private final String description;
    private final Integer minLength;
    private final Integer maxLength;
    private final List<Object> enumValues;
    private final boolean nullable;

    public StringSchema(String description) {
        this(description, null, null, null, false);
    }

    public StringSchema(String description, Integer minLength, Integer maxLength,
                        List<Object> enumValues, boolean nullable) {
        this.description = description;
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.enumValues = enumValues;
        this.nullable = nullable;
    }

    @Override
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

// IntegerSchema.java
public class IntegerSchema extends Schema {
    private final String description;
    private final Integer minimum;
    private final Integer maximum;
    private final List<Integer> enumValues;
    private final boolean nullable;

    public IntegerSchema(String description) {
        this(description, null, null, null, false);
    }

    public IntegerSchema(String description, Integer minimum, Integer maximum,
                         List<Integer> enumValues, boolean nullable) {
        this.description = description;
        this.minimum = minimum;
        this.maximum = maximum;
        this.enumValues = enumValues;
        this.nullable = nullable;
    }

    @Override
    public Map<String, Object> toJsonSchema() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", nullable ? List.of("integer", "null") : "integer");
        if (description != null && !description.isEmpty()) d.put("description", description);
        if (minimum != null) d.put("minimum", minimum);
        if (maximum != null) d.put("maximum", maximum);
        if (enumValues != null) d.put("enum", enumValues);
        return d;
    }
}

// NumberSchema.java
public class NumberSchema extends Schema {
    private final String description;
    private final Double minimum;
    private final Double maximum;
    private final List<Double> enumValues;
    private final boolean nullable;

    public NumberSchema(String description) {
        this(description, null, null, null, false);
    }

    public NumberSchema(String description, Double minimum, Double maximum,
                        List<Double> enumValues, boolean nullable) {
        this.description = description;
        this.minimum = minimum;
        this.maximum = maximum;
        this.enumValues = enumValues;
        this.nullable = nullable;
    }

    @Override
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

// BooleanSchema.java — type: "boolean", supports default value
public class BooleanSchema extends Schema {
    private final String description;
    private final Boolean defaultValue;
    private final boolean nullable;

    public BooleanSchema(String description) {
        this(description, null, false);
    }

    public BooleanSchema(String description, Boolean defaultValue, boolean nullable) {
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

// ArraySchema.java — type: "array", takes child `Schema items` (defaults to StringSchema)
public class ArraySchema extends Schema {
    private final Schema itemsSchema;
    private final String description;
    private final Integer minItems;
    private final Integer maxItems;
    private final boolean nullable;

    public ArraySchema(String description) {
        this(new StringSchema(""), description, null, null, false);
    }

    public ArraySchema(Schema itemsSchema, String description,
                       Integer minItems, Integer maxItems, boolean nullable) {
        this.itemsSchema = itemsSchema != null ? itemsSchema : new StringSchema("");
        this.description = description;
        this.minItems = minItems;
        this.maxItems = maxItems;
        this.nullable = nullable;
    }

    @Override
    public Map<String, Object> toJsonSchema() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", nullable ? List.of("array", "null") : "array");
        d.put("items", itemsSchema.toJsonSchema());
        if (description != null && !description.isEmpty()) d.put("description", description);
        if (minItems != null) d.put("minItems", minItems);
        if (maxItems != null) d.put("maxItems", maxItems);
        return d;
    }
}

// ObjectSchema.java — type: "object", takes Map<String, Object> properties, List<String> required
public class ObjectSchema extends Schema {
    private final Map<String, Object> properties;
    private final List<String> required;
    private final String description;
    private final Object additionalProperties;
    private final boolean nullable;

    public ObjectSchema(String description) {
        this(null, null, description, null, false);
    }

    public ObjectSchema(Map<String, Object> properties, List<String> required,
                        String description, Object additionalProperties, boolean nullable) {
        this.properties = properties != null ? properties : new LinkedHashMap<>();
        this.required = required != null ? required : new ArrayList<>();
        this.description = description;
        this.additionalProperties = additionalProperties;
        this.nullable = nullable;
    }

    @Override
    public Map<String, Object> toJsonSchema() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", nullable ? List.of("object", "null") : "object");
        Map<String, Object> props = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : properties.entrySet()) {
            props.put(e.getKey(), fragment(e.getValue()));
        }
        d.put("properties", props);
        if (!required.isEmpty()) d.put("required", required);
        if (description != null && !description.isEmpty()) d.put("description", description);
        if (additionalProperties != null) d.put("additionalProperties", additionalProperties);
        return d;
    }
}
```

### Static Convenience Factory

```java
// ToolParametersSchema.java
public final class ToolParametersSchema {

    private ToolParametersSchema() {}

    /** Build root tool parameters {"type": "object", "properties": ...}. */
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
```

---

## Component 2: Tool.java — Abstract Tool Base Class

### Package: `com.nanobot.agent.tools`

```java
package com.nanobot.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanobot.agent.tools.schema.Schema;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Agent capability: read files, run commands, search the web, etc.
 *
 * <p>Every tool MUST provide:
 * <ul>
 *   <li>{@link #getName()} — unique tool name used in LLM function calls</li>
 *   <li>{@link #getDescription()} — what the tool does</li>
 *   <li>{@link #getParameters()} — JSON Schema describing the parameters</li>
 *   <li>{@link #execute(Map)} — runs the tool, returns String or structured result</li>
 * </ul>
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Static {@code enabled(ToolContext)} check</li>
 *   <li>Static {@code create(ToolContext)} factory</li>
 *   <li>{@code castParams() + validateParams()} on each invocatioon</li>
 *   <li>{@code execute(params)}</li>
 * </ol>
 */
public abstract class Tool {

    private static final Set<String> BOOL_TRUE  = Set.of("true", "1", "yes");
    private static final Set<String> BOOL_FALSE = Set.of("false", "0", "no");

    // ==================== Abstract Properties ====================

    /** Unique tool name used in LLM function calls. */
    public abstract String getName();

    /** Human-readable description of what the tool does. */
    public abstract String getDescription();

    /** JSON Schema object describing the tool's parameters. */
    public abstract Map<String, Object> getParameters();

    // ==================== Optional Overrides ====================

    /** Side-effect free? If true, safe to parallelize. */
    public boolean isReadOnly() {
        return false;
    }

    /** Can this tool run alongside other concurrency-safe tools? */
    public boolean isConcurrencySafe() {
        return isReadOnly() && !isExclusive();
    }

    /** Should this tool run alone even when concurrency is enabled? */
    public boolean isExclusive() {
        return false;
    }

    // ==================== Plugin Metadata ====================

    /** Config section key, e.g. "exec", "web". Empty if no config. */
    public String getConfigKey() {
        return "";
    }

    /**
     * Optional config class for this tool (Python {@code config_cls()} mapping).
     * Java equivalent: bind via Spring {@code @ConfigurationProperties}
     * or read from the {@link ToolContext#config} bag in {@link #create}.
     */
    public static Class<?> getConfigClass(Class<? extends Tool> toolClass) {
        return null;
    }

    /** Plugin-discoverable flag (default true). Set false for manually-registered tools. */
    public boolean isPluginDiscoverable() {
        return true;
    }

    /** Scopes this tool belongs to. Default: {"core"}. */
    public Set<String> getScopes() {
        return Set.of("core");
    }

    // ==================== Class-level Lifecycle ====================

    /**
     * Check whether this tool is enabled under the given context.
     * Override to read config, check API keys, etc.
     */
    public static boolean enabled(ToolContext ctx, Class<? extends Tool> toolClass) {
        return true;
    }

    /**
     * Factory: create a new Tool instance with the given context.
     * Override to inject config, services, workspace, etc.
     */
    public static Tool create(ToolContext ctx, Class<? extends Tool> toolClass) {
        try {
            return toolClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool: " + toolClass.getName(), e);
        }
    }

    // ==================== Execution ====================

    /**
     * Run the tool. Returns a String (plain text, JSON, or formatted output)
     * or a list of content blocks for multi-modal results.
     */
    public abstract CompletableFuture<Object> execute(Map<String, Object> params);

    // ==================== Parameter Handling ====================

    /**
     * Apply safe schema-driven type coercion before validation.
     * Strings -> int/float/bool based on the declared JSON Schema type.
     */
    public Map<String, Object> castParams(Map<String, Object> params) {
        Map<String, Object> schema = getParameters();
        if (schema == null || !"object".equals(schema.get("type"))) {
            return new LinkedHashMap<>(params);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>)
                schema.getOrDefault("properties", Map.of());
        return castObject(params, props);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castObject(Map<String, Object> obj, Map<String, Object> props) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : obj.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            Map<String, Object> childSchema = props.containsKey(key)
                    ? (Map<String, Object>) props.get(key) : null;
            result.put(key, childSchema != null ? castValue(val, childSchema) : val);
        }
        return result;
    }

    /**
     * Cast a single value based on its JSON Schema type.
     *
     * Safe coercion rules:
     * - String "123" -> Integer 123 when type=integer
     * - String "3.14" -> Double 3.14 when type=number
     * - String "true"/"1"/"yes" -> Boolean true when type=boolean
     * - Non-string int -> Double when type=number
     * - Recursively cast arrays and objects
     */
    public static Object castValue(Object val, Map<String, Object> schema) {
        String type = resolveType(schema.get("type"));

        if (val == null) return null;

        // Already correct type?
        if ("boolean".equals(type) && val instanceof Boolean b) return b;
        if ("integer".equals(type) && val instanceof Integer i && !(val instanceof Boolean)) return i;
        if ("number".equals(type) && val instanceof Number n && !(val instanceof Boolean)) return n;
        if ("string".equals(type) && val instanceof String s) return s;
        if ("array".equals(type) && val instanceof List) return val;
        if ("object".equals(type) && val instanceof Map) return val;

        // String -> numeric coercion
        if (val instanceof String strVal) {
            if ("integer".equals(type)) {
                try { return Integer.parseInt(strVal); }
                catch (NumberFormatException e) { return val; }
            }
            if ("number".equals(type)) {
                try { return Double.parseDouble(strVal); }
                catch (NumberFormatException e) { return val; }
            }
        }

        // String -> boolean coercion
        if ("boolean".equals(type) && val instanceof String strVal) {
            String lower = strVal.toLowerCase();
            if (BOOL_TRUE.contains(lower)) return true;
            if (BOOL_FALSE.contains(lower)) return false;
            return val;
        }

        // Number -> integer truncation
        if ("integer".equals(type) && val instanceof Number num) {
            return num.intValue();
        }

        // Non-boolean -> string
        if ("string".equals(type) && !(val instanceof String)) {
            return val.toString();
        }

        // Recursive casts
        if ("array".equals(type) && val instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            Map<String, Object> itemsSchema = (Map<String, Object>) schema.get("items");
            if (itemsSchema != null) {
                List<Object> result = new ArrayList<>();
                for (Object item : list) {
                    result.add(castValue(item, itemsSchema));
                }
                return result;
            }
        }

        if ("object".equals(type) && val instanceof Map<?,?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>)
                    schema.getOrDefault("properties", Map.of());
            @SuppressWarnings("unchecked")
            Map<String, Object> typedMap = (Map<String, Object>) map;
            return castObject(typedMap, props);
        }

        return val;
    }

    public static String resolveType(Object rawType) {
        return Schema.resolveJsonSchemaType(rawType);
    }

    /**
     * Validate parameters against this tool's JSON Schema.
     * Returns empty list if valid; error messages otherwise.
     */
    public List<String> validateParams(Map<String, Object> params) {
        if (params == null) {
            return List.of("parameters must not be null");
        }
        Map<String, Object> schema = getParameters();
        if (schema == null || !"object".equals(schema.get("type"))) {
            throw new IllegalArgumentException(
                    "Tool schema must be object type, got " + schema.get("type"));
        }
        Map<String, Object> fullSchema = new LinkedHashMap<>(schema);
        fullSchema.put("type", "object");
        return Schema.validateJsonSchemaValue(params, fullSchema, "");
    }

    /** Build the OpenAI-compatible function schema. */
    public Map<String, Object> toSchema() {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", getName());
        fn.put("description", getDescription());
        fn.put("parameters", getParameters());

        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("type", "function");
        outer.put("function", fn);
        return outer;
    }
}
```

### Annotations: @ToolParameters

Instead of a decorator annotation (which cannot "replace" a property in Java), use a static helper:

```java
package com.nanobot.agent.tools;

import java.util.*;
import java.util.function.Supplier;

/**
 * Immutable snapshot of tool parameters for use in Tool subclasses.
 * Usage: define a private static final PARAMETERS field.
 *
 * <pre>{@code
 * public class ReadFileTool extends Tool {
 *     private static final Map<String, Object> PARAMETERS =
 *         ToolParametersSchema.create(
 *             List.of("path"),
 *             null,
 *             Map.of(
 *                 "path", new StringSchema("The file path to read"),
 *                 "offset", new IntegerSchema(1, "Line number to start reading from", 1, null)
 *             )
 *         );
 *
 *     @Override
 *     public Map<String, Object> getParameters() {
 *         return deepCopy(PARAMETERS);  // defensive copy
 *     }
 * }
 * }</pre>
 *
 * The deep-copy pattern prevents accidental mutation of the shared static map.
 */
public final class ToolParameters {

    private ToolParameters() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepCopy(Map<String, Object> original) {
        // Simple recursive deep copy for JSON-safe maps/lists
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : original.entrySet()) {
            copy.put(e.getKey(), deepCopyValue(e.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object deepCopyValue(Object val) {
        if (val instanceof Map<?,?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?,?> e : m.entrySet()) {
                copy.put((String) e.getKey(), deepCopyValue(e.getValue()));
            }
            return copy;
        }
        if (val instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) {
                copy.add(deepCopyValue(item));
            }
            return copy;
        }
        return val; // immutable types: String, Integer, Boolean
    }
}
```

---

## Component 3: ToolRegistry.java — Thread-safe Tool Registry

### Package: `com.nanobot.agent.tools`

```java
package com.nanobot.agent.tools;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry for agent tools.
 *
 * Manages the lifecycle: register, unregister, prepare calls (cast + validate),
 * execute, and export OpenAI-compatible function definitions.
 *
 * Definitions are cached until mutation (register/unregister) for prompt stability.
 */
public class ToolRegistry {

    private final ConcurrentHashMap<String, Tool> tools = new ConcurrentHashMap<>();
    private volatile List<Map<String, Object>> cachedDefinitions = null;

    // ==================== Registration ====================

    /** Register a tool. Overwrites if name already exists. */
    public void register(Tool tool) {
        tools.put(tool.getName(), tool);
        cachedDefinitions = null; // invalidate cache
    }

    /** Unregister a tool by name. No-op if not found. */
    public void unregister(String name) {
        tools.remove(name);
        cachedDefinitions = null;
    }

    /** Get a tool by exact name, or null. */
    public Tool get(String name) {
        return tools.get(name);
    }

    /** Check if a tool is registered. */
    public boolean has(String name) {
        return tools.containsKey(name);
    }

    // ==================== Name Resolution ====================

    /**
     * Normalize for fuzzy matching only (never for execution).
     * Strips non-alphanumeric chars and lowercases.
     */
    public static String lookupKey(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char ch : name.toCharArray()) {
            if (Character.isLetterOrDigit(ch)) {
                sb.append(Character.toLowerCase(ch));
            }
        }
        return sb.toString();
    }

    /**
     * Fuzzy name suggestion when the exact name is not found.
     * Returns the registered name if exactly one tool normalizes to the same key.
     */
    public String suggestName(String name) {
        String key = lookupKey(name);
        if (key.isEmpty()) return null;
        List<String> matches = new ArrayList<>();
        for (String registered : tools.keySet()) {
            if (key.equals(lookupKey(registered))) {
                matches.add(registered);
            }
        }
        return (matches.size() == 1) ? matches.get(0) : null;
    }

    // ==================== Definitions ====================

    /**
     * Get tool definitions with stable ordering for cache-friendly prompts.
     * Built-in tools sorted first (by name), then MCP tools (prefixed "mcp_").
     * Result is cached until the next register/unregister call.
     */
    public List<Map<String, Object>> getDefinitions() {
        List<Map<String, Object>> defs = cachedDefinitions;
        if (defs != null) return defs;

        List<Map<String, Object>> allDefs = new ArrayList<>();
        for (Tool tool : tools.values()) {
            allDefs.add(tool.toSchema());
        }

        List<Map<String, Object>> builtins = new ArrayList<>();
        List<Map<String, Object>> mcpTools = new ArrayList<>();
        for (Map<String, Object> schema : allDefs) {
            String name = schemaName(schema);
            if (name.startsWith("mcp_")) {
                mcpTools.add(schema);
            } else {
                builtins.add(schema);
            }
        }

        builtins.sort(Comparator.comparing(ToolRegistry::schemaName));
        mcpTools.sort(Comparator.comparing(ToolRegistry::schemaName));

        List<Map<String, Object>> result = new ArrayList<>(builtins);
        result.addAll(mcpTools);
        cachedDefinitions = result;
        return result;
    }

    @SuppressWarnings("unchecked")
    private static String schemaName(Map<String, Object> schema) {
        Object fn = schema.get("function");
        if (fn instanceof Map<?,?> fnMap) {
            Map<String, Object> fnMapTyped = (Map<String, Object>) fnMap;
            Object name = fnMapTyped.get("name");
            return name instanceof String s ? s : "";
        }
        Object name = schema.get("name");
        return name instanceof String s ? s : "";
    }

    // ==================== Call Preparation ====================

    /**
     * Result of {@link #prepareCall(String, Object)}:
     * - tool: the resolved Tool instance (or null on error)
     * - params: cast + unwrapped params
     * - error: null on success, error message otherwise
     */
    public record PreparedCall(Tool tool, Map<String, Object> params, String error) {}

    /**
     * Resolve, coerce, cast, and validate one tool call.
     *
     * Handles the common LLM pattern where params arrive as
     * {"arguments": "{\\"key\\": \\"value\\"}"} by unwrapping and
     * JSON-parsing the arguments string.
     */
    public PreparedCall prepareCall(String name, Object rawParams) {
        Tool tool = tools.get(name);
        if (tool == null) {
            String suggestion = suggestName(name);
            String hint = (suggestion != null)
                    ? " Did you mean '" + suggestion + "'? Tool names must match exactly."
                    : "";
            return new PreparedCall(
                    null, null,
                    "Error: Tool '" + name + "' not found." + hint
                            + " Available: " + tools.keySet());
        }

        Object coerced = coerceParams(tool, rawParams);
        if (!(coerced instanceof Map)) {
            return new PreparedCall(
                    tool, null,
                    "Error: Tool '" + name + "' parameters must be a JSON object, got "
                            + coerced.getClass().getSimpleName());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) coerced;

        Map<String, Object> castParams = tool.castParams(params);
        List<String> errors = tool.validateParams(castParams);
        if (!errors.isEmpty()) {
            return new PreparedCall(
                    tool, castParams,
                    "Error: Invalid parameters for tool '" + name + "': "
                            + String.join("; ", errors));
        }

        return new PreparedCall(tool, castParams, null);
    }

    /**
     * Coerce raw params: parse JSON strings, unwrap {"arguments": ...} payloads.
     */
    private static Object coerceParams(Tool tool, Object rawParams) {
        Object coerced = coerceArgumentValue(rawParams);
        return unwrapArgumentsPayload(tool, coerced);
    }

    /**
     * If the value is a JSON string like '{"x": 1}', parse it.
     * Only attempts parsing if the trimmed string starts with '{' or '['.
     */
    public static Object coerceArgumentValue(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof String str)) return value;

        String trimmed = str.strip();
        if (trimmed.isEmpty()) return Map.of();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return value;

        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(trimmed, Map.class);
        } catch (Exception e) {
            return value; // not valid JSON, return as-is
        }
    }

    /**
     * Unwrap {"arguments": {...}} when it's NOT a real parameter named "arguments".
     */
    private static Object unwrapArgumentsPayload(Tool tool, Object params) {
        if (!(params instanceof Map<?,?> map)) return params;
        if (map.size() != 1 || !map.containsKey("arguments")) return params;

        Map<String, Object> toolParams = tool.getParameters();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>)
                (toolParams != null ? toolParams.getOrDefault("properties", Map.of()) : Map.of());

        // If the tool genuinely has an "arguments" property, don't unwrap
        if (properties.containsKey("arguments")) return params;

        return coerceArgumentValue(map.get("arguments"));
    }

    // ==================== Execution ====================

    /**
     * Execute a tool by name with given parameters.
     * Handles resolution, casting, validation, and error formatting.
     */
    public CompletableFuture<Object> execute(String name, Object params) {
        String hint = "\n\n[Analyze the error above and try a different approach.]";
        PreparedCall prepared = prepareCall(name, params);
        if (prepared.error() != null) {
            return CompletableFuture.completedFuture(prepared.error() + hint);
        }

        return prepared.tool().execute(prepared.params())
                .thenApply(result -> {
                    if (result instanceof String str && str.startsWith("Error")) {
                        return str + hint;
                    }
                    return result;
                })
                .exceptionally(e ->
                        "Error executing " + name + ": " + e.getMessage() + hint);
    }

    // ==================== Accessors ====================

    public List<String> getToolNames() {
        return List.copyOf(tools.keySet());
    }

    public int size() {
        return tools.size();
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }
}
```

---

## Component 4: ToolLoader.java — Spring-Aligned Tool Discovery

### Package: `com.nanobot.agent.tools`

```java
package com.nanobot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Discovers and registers all Tool implementations.
 *
 * <p>Discovery strategy:
 * <ol>
 *   <li>Spring component scan: finds all {@code @Component} classes extending Tool
 *       in the {@code com.nanobot.agent.tools.impl} package.</li>
 *   <li>Java {@link ServiceLoader} for external plugin tools ({@code META-INF/services/}).</li>
 *   <li>Filters: must be concrete (!abstract), plugin-discoverable, and pass
 *       {@code enabled(ctx)}.</li>
 *   <li>Built-in names are tracked; plugins may not shadow built-in tool names.</li>
 * </ol>
 */
@Component
public class ToolLoader {

    private static final Logger log = LoggerFactory.getLogger(ToolLoader.class);

    /**
     * Module names to skip during discovery (infrastructure, not tools).
     */
    private static final Set<String> SKIP_CLASS_NAMES = Set.of(
            "Tool", "Schema", "ToolRegistry", "ToolLoader",
            "ToolContext", "FileStates", "FileStateStore",
            "RequestContext", "MCPToolWrapper"
    );

    private final ClassPathScanningCandidateComponentProvider scanner;

    public ToolLoader() {
        this.scanner = new ClassPathScanningCandidateComponentProvider(false);
        this.scanner.addIncludeFilter(new AssignableTypeFilter(Tool.class));
    }

    /**
     * Discover built-in tool classes via Spring component scan.
     */
    public List<Class<? extends Tool>> discoverBuiltins() {
        List<Class<? extends Tool>> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // Scan the impl package for Tool subclasses
        for (BeanDefinition bd : scanner.findCandidateComponents(
                "com.nanobot.agent.tools.impl")) {
            try {
                Class<?> cls = Class.forName(bd.getBeanClassName());
                if (isConcreteTool(cls, seen)) {
                    @SuppressWarnings("unchecked")
                    Class<? extends Tool> toolClass = (Class<? extends Tool>) cls;
                    results.add(toolClass);
                    seen.add(cls.getName());
                }
            } catch (ClassNotFoundException e) {
                log.warn("Tool class not found: {}", bd.getBeanClassName(), e);
            }
        }

        results.sort(Comparator.comparing(Class::getName));
        return results;
    }

    /**
     * Discover external plugin tools via Java ServiceLoader.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Class<? extends Tool>> discoverPlugins() {
        Map<String, Class<? extends Tool>> plugins = new LinkedHashMap<>();
        ServiceLoader<Tool> loader = ServiceLoader.load(Tool.class);
        for (Tool tool : loader) {
            Class<? extends Tool> cls = tool.getClass();
            if (isConcreteTool(cls, Set.of())) {
                plugins.put(cls.getName(), cls);
            }
        }
        return plugins;
    }

    private boolean isConcreteTool(Class<?> cls, Set<String> seen) {
        if (cls == null || cls == Tool.class) return false;
        if (SKIP_CLASS_NAMES.contains(cls.getSimpleName())) return false;
        if (cls.isInterface() || java.lang.reflect.Modifier.isAbstract(cls.getModifiers())) {
            return false;
        }
        if (!Tool.class.isAssignableFrom(cls)) return false;
        if (seen.contains(cls.getName())) return false;

        // Check plugin_discoverable
        try {
            Object instance = cls.getDeclaredConstructor().newInstance();
            if (instance instanceof Tool tool) {
                if (!tool.isPluginDiscoverable()) return false;
            }
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    /**
     * Load and register all tools into the given registry.
     *
     * @param ctx      the tool context for enabled/create checks
     * @param registry the registry to populate
     * @param scope    filter scope (e.g. "core", "subagent", "memory")
     * @return list of registered tool names
     */
    public List<String> load(ToolContext ctx, ToolRegistry registry, String scope) {
        List<String> registered = new ArrayList<>();
        Set<String> builtinNames = new HashSet<>();

        // 1. Built-in tools via component scan
        for (Class<? extends Tool> toolClass : discoverBuiltins()) {
            String className = toolClass.getSimpleName();
            try {
                Set<String> scopes = getToolScopes(toolClass);
                if (!scopes.contains(scope)) continue;
                if (!Tool.enabled(ctx, toolClass)) continue;

                Tool tool = Tool.create(ctx, toolClass);
                if (registry.has(tool.getName())) {
                    log.warn("Tool name collision: {} from {} overwrites existing",
                            tool.getName(), className);
                }
                registry.register(tool);
                registered.add(tool.getName());
                builtinNames.add(tool.getName());
            } catch (Exception e) {
                log.error("Failed to register tool: {}", className, e);
            }
        }

        // 2. Plugin tools via ServiceLoader
        for (Map.Entry<String, Class<? extends Tool>> entry : discoverPlugins().entrySet()) {
            String pluginName = entry.getKey();
            Class<? extends Tool> toolClass = entry.getValue();
            try {
                Set<String> scopes = getToolScopes(toolClass);
                if (!scopes.contains(scope)) continue;
                if (!Tool.enabled(ctx, toolClass)) continue;

                Tool tool = Tool.create(ctx, toolClass);
                if (registry.has(tool.getName())) {
                    if (builtinNames.contains(tool.getName())) {
                        log.warn("Plugin {} skipped: conflicts with built-in tool {}",
                                pluginName, tool.getName());
                        continue;
                    }
                    log.warn("Tool name collision: {} from {} overwrites existing",
                            tool.getName(), pluginName);
                }
                registry.register(tool);
                registered.add(tool.getName());
            } catch (Exception e) {
                log.error("Failed to register plugin tool: {}", pluginName, e);
            }
        }
        return registered;
    }

    @SuppressWarnings("unchecked")
    private Set<String> getToolScopes(Class<? extends Tool> toolClass) {
        try {
            Tool instance = toolClass.getDeclaredConstructor().newInstance();
            return instance.getScopes();
        } catch (Exception e) {
            return Set.of("core");
        }
    }
}
```

---

## Component 5: ToolContext.java — Dependency Injection Container

### Package: `com.nanobot.agent.tools`

```java
package com.nanobot.agent.tools;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Bag of cross-cutting references injected into every Tool at creation time.
 *
 * <p>This is a Java {@code record} — immutable by design. Tool creators
 * destructure it to extract only what they need.
 *
 * <p>All fields are nullable except config, workspace, and timezone.
 *
 * @param config                 the full application config (Pydantic-equivalent POJO)
 * @param workspace              absolute path to the agent's workspace directory
 * @param bus                    the MessageBus for outbound messages (nullable)
 * @param subagentManager        for spawning subagents (nullable)
 * @param cronService            for scheduling cron jobs (nullable)
 * @param sessions               SessionManager for long-goal persistence (nullable)
 * @param fileStateStore         per-session FileStates tracker (nullable)
 * @param providerSnapshotLoader lazy loader for provider config snapshots (nullable)
 * @param imageGenerationProviderConfigs per-provider image gen configs (nullable)
 * @param timezone               IANA timezone string, default "UTC"
 * @param workspaceSandbox       sandbox name (e.g. "bwrap") or null
 * @param runtimeEvents          RuntimeEventBus for publishing events (nullable)
 */
public record ToolContext(
        Object config,
        String workspace,
        Object bus,
        Object subagentManager,
        Object cronService,
        Object sessions,
        Object fileStateStore,
        Supplier<Object> providerSnapshotLoader,
        Map<String, Object> imageGenerationProviderConfigs,
        String timezone,
        Object workspaceSandbox,
        Object runtimeEvents
) {
    public ToolContext {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        if (workspace == null) throw new IllegalArgumentException("workspace must not be null");
        if (timezone == null) timezone = "UTC";
    }

    /** Convenience builder for tests and manual construction. */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Object config;
        private String workspace = ".";
        private Object bus;
        private Object subagentManager;
        private Object cronService;
        private Object sessions;
        private Object fileStateStore;
        private Supplier<Object> providerSnapshotLoader;
        private Map<String, Object> imageGenerationProviderConfigs;
        private String timezone = "UTC";
        private Object workspaceSandbox;
        private Object runtimeEvents;

        public Builder config(Object config) { this.config = config; return this; }
        public Builder workspace(String workspace) { this.workspace = workspace; return this; }
        public Builder bus(Object bus) { this.bus = bus; return this; }
        public Builder subagentManager(Object sm) { this.subagentManager = sm; return this; }
        public Builder cronService(Object cs) { this.cronService = cs; return this; }
        public Builder sessions(Object sessions) { this.sessions = sessions; return this; }
        public Builder fileStateStore(Object fss) { this.fileStateStore = fss; return this; }
        public Builder providerSnapshotLoader(Supplier<Object> loader) {
            this.providerSnapshotLoader = loader; return this;
        }
        public Builder imageGenerationProviderConfigs(Map<String, Object> cfgs) {
            this.imageGenerationProviderConfigs = cfgs; return this;
        }
        public Builder timezone(String tz) { this.timezone = tz; return this; }
        public Builder workspaceSandbox(Object ws) { this.workspaceSandbox = ws; return this; }
        public Builder runtimeEvents(Object re) { this.runtimeEvents = re; return this; }

        public ToolContext build() {
            return new ToolContext(config, workspace, bus, subagentManager, cronService,
                    sessions, fileStateStore, providerSnapshotLoader,
                    imageGenerationProviderConfigs, timezone, workspaceSandbox, runtimeEvents);
        }
    }
}
```

---

## Component 6: RequestContext.java — Per-Invocation Routing

### Package: `com.nanobot.agent.tools`

```java
package com.nanobot.agent.tools;

import java.util.Map;

/**
 * Per-request routing context injected into tools at message-processing time.
 * Frozen (immutable record), bound to virtual threads via a {@link ThreadLocal}.
 *
 * <p>Java virtual threads are designed to be ThreadLocal-safe: each virtual
 * thread gets its own value, with zero interference between concurrent tasks.
 *
 * @param channel    the originating channel name (e.g. "telegram", "websocket")
 * @param chatId     the chat/room/user identifier
 * @param messageId  optional message ID for reply threading
 * @param sessionKey session routing key (e.g. "telegram:12345")
 * @param metadata   arbitrary key-value metadata from the channel
 */
public record RequestContext(
        String channel,
        String chatId,
        String messageId,
        String sessionKey,
        Map<String, Object> metadata
) {
    // ==================== ThreadLocal Binding ====================

    private static final ThreadLocal<RequestContext> CURRENT =
            ThreadLocal.withInitial(() -> null);

    /**
     * Bind a RequestContext to the current virtual thread.
     * Returns the previous context (for nesting/restore).
     */
    public static RequestContext bind(RequestContext ctx) {
        RequestContext previous = CURRENT.get();
        CURRENT.set(ctx);
        return previous;
    }

    /** Unbind the current request context. */
    public static void unbind() {
        CURRENT.remove();
    }

    /** Get the RequestContext bound to the current virtual thread, or null. */
    public static RequestContext current() {
        return CURRENT.get();
    }

    /** Convenience: get the session key from the current context, or null. */
    public static String currentSessionKey() {
        RequestContext ctx = current();
        return (ctx != null) ? ctx.sessionKey() : null;
    }
}
```

### ContextAware Protocol Mapping

Python `context.py` defines a `@runtime_checkable Protocol` for tools that need explicit request-context injection:

```python
class ContextAware(Protocol):
    def set_context(self, ctx: RequestContext) -> None: ...
```

Usage in `agent/loop.py`: before executing a tool, the loop checks `isinstance(tool, ContextAware)` and calls `tool.set_context(request_ctx)` so the tool knows the current channel, chat, and session.

Java equivalent — an interface in the same package:

```java
package com.nanobot.agent.tools;

/**
 * Marker interface for tools that need per-request routing metadata.
 *
 * <p>Before execution, the agent loop checks {@code tool instanceof ContextAware}
 * and calls {@link #setContext(RequestContext)} so the tool can route messages
 * or access session-scoped state.
 *
 * <p>Example: a {@code SpawnTool} that sends follow-up messages needs the
 * channel name and chat ID from the current request.
 */
public interface ContextAware {
    void setContext(RequestContext ctx);
}
```

---

## Component 7: FileStates.java — Read/Write Tracker

### Package: `com.nanobot.agent.tools`

Tracks file read state per session for:
- **Read deduplication**: "File unchanged since last read" when same file+offset+limit and content hash matches
- **Read-before-edit warnings**: Alert when a file was modified externally since the last read

```java
package com.nanobot.agent.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class FileStates {

    public record ReadState(
            double mtime,
            int offset,
            Integer limit,
            String contentHash,
            boolean canDedup
    ) {}

    private final ConcurrentHashMap<String, ReadState> state = new ConcurrentHashMap<>();

    /** Record a successful read. */
    public void recordRead(Path path, int offset, Integer limit) {
        String key = path.toAbsolutePath().normalize().toString();
        try {
            double mtime = Files.getLastModifiedTime(path).toMillis() / 1000.0;
            String hash = sha256Hex(path);
            state.put(key, new ReadState(mtime, offset, limit, hash, true));
        } catch (IOException e) {
            // file disappeared between read and record; skip
        }
    }

    /** Record a successful write — updates mtime, resets dedup. */
    public void recordWrite(Path path) {
        String key = path.toAbsolutePath().normalize().toString();
        try {
            double mtime = Files.getLastModifiedTime(path).toMillis() / 1000.0;
            String hash = sha256Hex(path);
            state.put(key, new ReadState(mtime, 1, null, hash, false));
        } catch (IOException e) {
            state.remove(key);
        }
    }

    /**
     * Check if a file was read and is still fresh.
     * Returns null if OK, or a warning string.
     */
    public String checkRead(Path path) {
        String key = path.toAbsolutePath().normalize().toString();
        ReadState entry = state.get(key);
        if (entry == null) {
            return "Warning: file has not been read yet. Read it first to verify content before editing.";
        }
        try {
            double currentMtime = Files.getLastModifiedTime(path).toMillis() / 1000.0;
            if (currentMtime != entry.mtime()) {
                // mtime changed — check content hash
                String currentHash = sha256Hex(path);
                if (entry.contentHash() != null && currentHash.equals(entry.contentHash())) {
                    // Content same, update mtime in-place
                    state.put(key, new ReadState(currentMtime, entry.offset(),
                            entry.limit(), entry.contentHash(), entry.canDedup()));
                    return null;
                }
                return "Warning: file has been modified since last read. Re-read to verify content before editing.";
            }
            // mtime unchanged — still verify content hash
            if (entry.contentHash() != null) {
                String currentHash = sha256Hex(path);
                if (!currentHash.equals(entry.contentHash())) {
                    return "Warning: file has been modified since last read. Re-read to verify content before editing.";
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Get the ReadState for a path, or null. */
    public ReadState get(Path path) {
        return state.get(path.toAbsolutePath().normalize().toString());
    }

    /** Clear all tracked state. */
    public void clear() {
        state.clear();
    }

    /** Is this file unchanged since last read with the same params? */
    public boolean isUnchanged(Path path, int offset, Integer limit) {
        ReadState entry = get(path);
        if (entry == null || !entry.canDedup()) return false;
        if (entry.offset() != offset) return false;
        if (!Objects.equals(entry.limit(), limit)) return false;
        try {
            double currentMtime = Files.getLastModifiedTime(path).toMillis() / 1000.0;
            if (currentMtime != entry.mtime()) {
                entry = new ReadState(currentMtime, entry.offset(), entry.limit(),
                        entry.contentHash(), false);
                state.put(path.toAbsolutePath().normalize().toString(), entry);
                return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String sha256Hex(Path path) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(Files.readAllBytes(path));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }
}

// ---- FileStateStore.java ----
/**
 * Per-session FileStates lookup.
 * Sessions share the same process; each gets its own tracker to keep
 * read-dedup and read-before-edit warnings scoped to one chat.
 */
public class FileStateStore {

    private final ConcurrentHashMap<String, FileStates> statesByKey = new ConcurrentHashMap<>();

    public FileStates forSession(String sessionKey) {
        String key = (sessionKey != null) ? sessionKey : "__default__";
        return statesByKey.computeIfAbsent(key, k -> new FileStates());
    }

    public void clear() {
        statesByKey.clear();
    }
}
```

**ContextVar mapping**: Python `file_state.py` uses `contextvars.ContextVar` (`_current_file_states`) with `bind_file_states()` / `reset_file_states()` and module-level helpers (`record_read`, `record_write`, etc.). In Java this maps to either (a) passing `FileStates` explicitly through `ToolContext` / `RequestContext`, or (b) a `ThreadLocal<FileStates>` bound around tool execution, reset in `try-finally`. The per-session lookup via `FileStateStore.forSession(sessionKey)` is the primary entry point; implicit global accessors are omitted because Java lacks Python's module-level dynamic attribute fallback (`__getattr__`).

---

## Component 8: Path Resolution Utilities

### Package: `com.nanobot.agent.tools.path`

```java
package com.nanobot.agent.tools.path;

import java.nio.file.Path;
import java.util.List;

/**
 * Shared path helpers for workspace-scoped tools.
 * Mirrors nanobot/agent/tools/path_utils.py.
 */
public final class PathUtils {

    private PathUtils() {}

    /**
     * Resolve a user-supplied path against the workspace and enforce
     * containment within the allowed directory.
     *
     * <p>Mirrors Python {@code resolve_workspace_path}: the media directory
     * is automatically included as an extra allowed root (read-only access
     * for attachments), alongside any caller-supplied extras.
     */
    public static Path resolveWorkspacePath(
            String path,
            Path workspace,
            Path allowedDir,
            Path mediaDir,
            List<Path> extraAllowedDirs) {

        Path resolved = workspace.resolve(path).normalize();

        // Check containment
        if (allowedDir != null) {
            if (isPathWithin(resolved, allowedDir)) {
                return resolved;
            }
            // Always allow media directory (Python get_media_dir() is auto-included)
            if (mediaDir != null && isPathWithin(resolved, mediaDir)) {
                return resolved;
            }
            if (extraAllowedDirs != null) {
                for (Path extra : extraAllowedDirs) {
                    if (isPathWithin(resolved, extra)) {
                        return resolved;
                    }
                }
            }
            throw new SecurityException(
                    "Path '" + path + "' is outside the allowed workspace directory");
        }
        return resolved;
    }

    /** Check if child is under parent (or equal). */
    public static boolean isPathWithin(Path child, Path parent) {
        Path cp = child.normalize().toAbsolutePath();
        Path pp = parent.normalize().toAbsolutePath();
        return cp.startsWith(pp);
    }
}
```

---

## Component 9: Sandbox Wrapping

### Package: `com.nanobot.agent.tools.sandbox`

Wraps shell commands in a bubblewrap sandbox for process isolation.

```java
package com.nanobot.agent.tools.sandbox;

import java.nio.file.Path;
import java.util.*;

/**
 * Sandbox backends for shell command execution.
 *
 * Currently supports "bwrap" (bubblewrap) for Linux container-like isolation.
 * The workspace is bind-mounted read-write; the media directory is
 * bind-mounted read-only so exec commands can read attachments.
 */
public final class Sandbox {

    private Sandbox() {}

    public static final List<String> REQUIRED_BINDS = List.of("/usr");
    public static final List<String> OPTIONAL_BINDS = List.of(
            "/bin", "/lib", "/lib64", "/etc/alternatives",
            "/etc/ssl/certs", "/etc/resolv.conf", "/etc/ld.so.cache"
    );

    /**
     * Wrap a command with bubblewrap sandboxing.
     *
     * @param command   the raw shell command
     * @param workspace absolute workspace path
     * @param cwd       working directory within the workspace
     * @return the bwrap-wrapped command string
     */
    public static String bwrap(String command, Path workspace, Path cwd, Path mediaDir) {
        Path ws = workspace.normalize().toAbsolutePath();
        Path media = mediaDir.normalize().toAbsolutePath();

        // Resolve sandbox CWD
        Path sandboxCwd;
        try {
            sandboxCwd = ws.resolve(ws.relativize(cwd.normalize().toAbsolutePath()));
        } catch (IllegalArgumentException e) {
            sandboxCwd = ws;
        }

        List<String> args = new ArrayList<>();
        args.add("bwrap");
        args.add("--new-session");
        args.add("--die-with-parent");
        args.add("--setenv"); args.add("HOME"); args.add(ws.toString());

        // Required bind mounts (read-only)
        for (String p : REQUIRED_BINDS) {
            args.add("--ro-bind"); args.add(p); args.add(p);
        }
        // Optional bind mounts
        for (String p : OPTIONAL_BINDS) {
            args.add("--ro-bind-try"); args.add(p); args.add(p);
        }

        args.addAll(List.of("--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp"));
        args.addAll(List.of("--tmpfs", ws.getParent().toString())); // mask config dir
        args.addAll(List.of("--dir", ws.toString()));              // recreate workspace mount point
        args.addAll(List.of("--bind", ws.toString(), ws.toString()));

        // Read-only media access
        args.add("--ro-bind-try"); args.add(media.toString()); args.add(media.toString());

        args.addAll(List.of("--chdir", sandboxCwd.toString()));
        args.addAll(List.of("--", "sh", "-c", command));

        // Join into a single shell-safe string
        return joinCommand(args);
    }

    /** Wrap command using the named sandbox backend. */
    public static String wrapCommand(String sandbox, String command,
                                      Path workspace, Path cwd, Path mediaDir) {
        return switch (sandbox) {
            case "bwrap" -> bwrap(command, workspace, cwd, mediaDir);
            default -> throw new IllegalArgumentException(
                    "Unknown sandbox backend '" + sandbox + "'. Available: [bwrap]");
        };
    }

    /** Join command parts with proper shell quoting. */
    private static String joinCommand(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!sb.isEmpty()) sb.append(' ');
            // Basic quoting: wrap in single quotes, escape internal single quotes
            if (part.contains(" ") || part.contains("'") || part.isEmpty()) {
                sb.append('\'').append(part.replace("'", "'\\''")).append('\'');
            } else {
                sb.append(part);
            }
        }
        return sb.toString();
    }
}
```

---

## Summary of Components and Their Purpose

| Component | Class | Purpose |
|-----------|-------|---------|
| `base.py` | `Tool.java` | Abstract tool base: name, description, parameters, execute, type coercion, validation, OpenAI schema export |
| `schema.py` | `Schema.java` + subclasses | JSON Schema fragment types for declaring and validating tool parameters |
| `registry.py` | `ToolRegistry.java` | Thread-safe ConcurrentHashMap-backed tool registry with name resolution, call preparation, fuzzy matching, definition caching |
| `loader.py` | `ToolLoader.java` | Spring ComponentScan-based discovery + Java ServiceLoader for plugin tools |
| `context.py` | `ToolContext.java`, `RequestContext.java` | DI container for cross-cutting refs; ThreadLocal-based per-request routing |
| `file_state.py` | `FileStates.java`, `FileStateStore.java` | Per-session file state tracking for deduplication and read-before-edit warnings |
| `path_utils.py` | `PathUtils.java` | Workspace path resolution with containment enforcement |
| `sandbox.py` | `Sandbox.java` | bwrap command wrapping for process isolation |

### Key Design Decisions

1. **ThreadLocal for RequestContext** -- Java virtual threads inherit their carrier thread' s ThreadLocal by default, but since each virtual thread gets its own carrier-thread attachment, and each tool invocation runs on its own virtual thread, the ThreadLocal pattern is safe. Use `try-finally` unbind for cleanup.

2. **ConcurrentHashMap for Registry** -- `ToolRegistry` uses `ConcurrentHashMap` for thread-safe registration from multiple concurrent discovery sources (builtins + plugins).

3. **Defensive Parameter Copy** -- `Tool.getParameters()` always returns a deep copy so the shared schema map is never mutated by callers.

4. **CompletableFuture for Async** -- `Tool.execute()` returns `CompletableFuture<Object>` to faithfully map Python `async def execute(**kwargs)`. This preserves the explicit async contract from the source code: any subclass that performs I/O must chain async operations via `thenCompose` / async SDK clients, not block a worker thread. Virtual Threads are **not** a substitute for this async semantics.

5. **Record for ToolContext** -- Immutable by design. Tools destructure it in their factory method to extract only the dependencies they need.
