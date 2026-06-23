package com.nanobot.agent.tools;

import com.nanobot.agent.tools.schema.Schema;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Agent 工具抽象基类，定义工具的完整生命周期和参数处理逻辑。
 *
 * <p>对标 Python {@code nanobot/agent/tools/base.py Tool}（168 行）。
 * 每个工具必须提供：name（唯一标识）、description（功能描述）、
 * parameters（JSON Schema 参数定义）、execute（执行逻辑）。
 *
 * <p>生命周期：enabled() 检查 → create() 工厂构造 →
 * castParams() + validateParams() 参数处理 → execute() 执行。
 */
public abstract class Tool {

    /** 对标 Python _BOOL_TRUE */
    private static final Set<String> BOOL_TRUE = Set.of("true", "1", "yes");
    /** 对标 Python _BOOL_FALSE */
    private static final Set<String> BOOL_FALSE = Set.of("false", "0", "no");

    // ==================== 抽象属性 ====================

    /**
     * 返回工具的唯一名称，用于 LLM function call 中的工具标识。
     *
     * @return 工具名称
     */
    // 对标 Python Tool.name
    public abstract String getName();

    /**
     * 返回工具的人类可读功能描述。
     *
     * @return 工具描述
     */
    // 对标 Python Tool.description
    public abstract String getDescription();

    /**
     * 返回描述工具参数的 JSON Schema 对象。
     *
     * @return 参数 JSON Schema Map
     */
    // 对标 Python Tool.parameters
    public abstract Map<String, Object> getParameters();

    // ==================== 可选覆写的属性 ====================

    /**
     * 返回工具是否为只读（无副作用），只读工具可安全并行执行。
     *
     * @return 默认 false
     */
    // 对标 Python Tool.read_only
    public boolean isReadOnly() {
        return false;
    }

    /**
     * 返回工具是否可与其他并发安全工具同时运行。
     *
     * @return 当 isReadOnly() 且非 isExclusive() 时为 true
     */
    // 对标 Python Tool.concurrency_safe
    public boolean isConcurrencySafe() {
        return isReadOnly() && !isExclusive();
    }

    /**
     * 返回工具是否应独占运行，即使并发已启用。
     *
     * @return 默认 false
     */
    // 对标 Python Tool.exclusive
    public boolean isExclusive() {
        return false;
    }

    // ==================== 插件元数据 ====================

    /**
     * 返回配置段 key，如 "exec"、"web"。无配置时返回空字符串。
     *
     * @return 配置 key
     */
    // 对标 Python Tool.config_key
    public String getConfigKey() {
        return "";
    }

    /**
     * 返回此工具类对应的配置类（对标 Python config_cls()）。
     * Java 中等效方式：通过 Spring {@code @ConfigurationProperties}
     * 或在 {@link #create} 中从 {@code ToolContext.config} 读取。
     *
     * @return 配置类，默认 null
     */
    // 对标 Python Tool.config_cls()
    public static Class<?> getConfigClass(Class<? extends Tool> toolClass) {
        return null;
    }

    /**
     * 返回该工具是否可被插件发现。手动注册的工具可设为 false。
     *
     * @return 默认 true
     */
    // 对标 Python Tool._plugin_discoverable
    public boolean isPluginDiscoverable() {
        return true;
    }

    /**
     * 返回该工具所属的作用域集合。
     *
     * @return 默认 {"core"}
     */
    // 对标 Python Tool._scopes
    public Set<String> getScopes() {
        return Set.of("core");
    }

    // ==================== 类级生命周期 ====================

    /**
     * 检查当前上下文下此工具是否启用。覆写以读取配置、检查 API key 等。
     *
     * @param ctx       工具上下文
     * @param toolClass 工具类
     * @return 默认 true
     */
    // 对标 Python Tool.enabled(ctx)
    public static boolean enabled(ToolContext ctx, Class<? extends Tool> toolClass) {
        return true;
    }

    /**
     * 工厂方法：使用给定上下文创建工具实例。覆写以注入配置、服务、工作空间等。
     *
     * @param ctx       工具上下文
     * @param toolClass 工具类
     * @return 新工具实例
     */
    // 对标 Python Tool.create(ctx) -> cls()
    public static Tool create(ToolContext ctx, Class<? extends Tool> toolClass) {
        try {
            return toolClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool: " + toolClass.getName(), e);
        }
    }

    // ==================== 执行 ====================

    /**
     * 执行工具逻辑，返回 String（纯文本、JSON 或格式化输出）或多模态内容块列表。
     *
     * @param params 已校验的参数 Map
     * @return 执行结果的 CompletableFuture
     */
    // 对标 Python Tool.execute(**kwargs)
    public abstract CompletableFuture<Object> execute(Map<String, Object> params);

    // ==================== 参数处理 ====================

    /**
     * 在验证前应用安全的 schema 驱动类型转换。
     * 根据声明的 JSON Schema 类型将字符串转换为 int/float/bool。
     *
     * @param params 原始参数 Map
     * @return 类型转换后的参数 Map
     */
    // 对标 Python Tool.cast_params()
    @SuppressWarnings("unchecked")
    public Map<String, Object> castParams(Map<String, Object> params) {
        Map<String, Object> schema = getParameters();
        if (schema == null || !"object".equals(schema.get("type"))) {
            return new LinkedHashMap<>(params);
        }
        Map<String, Object> props =
                (Map<String, Object>) schema.getOrDefault("properties", Map.of());
        return castObject(params, props);
    }

    /**
     * 递归转换对象中各字段的类型。
     *
     * @param obj   原始对象 Map
     * @param props 属性 Schema Map
     * @return 类型转换后的对象 Map
     */
    // 对标 Python Tool._cast_object()
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
     * 根据 JSON Schema 类型对单个值进行安全的类型转换。
     *
     * <p>转换规则：
     * <ul>
     *   <li>字符串 "123" → Integer 123（当 type=integer）</li>
     *   <li>字符串 "3.14" → Double 3.14（当 type=number）</li>
     *   <li>字符串 "true"/"1"/"yes" → Boolean true（当 type=boolean）</li>
     *   <li>递归转换数组元素和对象属性</li>
     * </ul>
     *
     * @param val    原始值
     * @param schema 该字段的 JSON Schema Map
     * @return 类型转换后的值
     */
    // 对标 Python Tool._cast_value()
    @SuppressWarnings("unchecked")
    public static Object castValue(Object val, Map<String, Object> schema) {
        String t = Schema.resolveJsonSchemaType(schema.get("type"));

        if (val == null) return null;

        // 已是正确类型则直接返回（boolean、integer、及非特殊类型）
        // 对标 Python: t == "boolean" and isinstance(val, bool)
        if ("boolean".equals(t) && val instanceof Boolean b) return b;
        // 对标 Python: t == "integer" and isinstance(val, int) and not isinstance(val, bool)
        if ("integer".equals(t) && val instanceof Integer i && !(val instanceof Boolean)) return i;
        // 对标 Python: t in _TYPE_MAP and t not in ("boolean", "integer", "array", "object")
        // 即 string 和 number 类型已匹配时直接返回
        if ("string".equals(t) && val instanceof String s) return s;
        if ("number".equals(t) && val instanceof Number n && !(val instanceof Boolean)) return n;

        // 对标 Python: isinstance(val, str) and t in ("integer", "number")
        if (val instanceof String strVal) {
            if ("integer".equals(t)) {
                try { return Integer.parseInt(strVal); }
                catch (NumberFormatException e) { return val; }
            }
            if ("number".equals(t)) {
                try { return Double.parseDouble(strVal); }
                catch (NumberFormatException e) { return val; }
            }
        }

        // 对标 Python: t == "string": return val if val is None else str(val)
        if ("string".equals(t) && !(val instanceof String)) {
            return val.toString();
        }

        // 对标 Python: t == "boolean" and isinstance(val, str)
        if ("boolean".equals(t) && val instanceof String strVal) {
            String lower = strVal.toLowerCase();
            if (BOOL_TRUE.contains(lower)) return true;
            if (BOOL_FALSE.contains(lower)) return false;
            return val;
        }

        // 对标 Python: t == "array" and isinstance(val, list)
        // 注意：array 和 object 不会在早期返回，始终递归转换内部元素
        if ("array".equals(t) && val instanceof List<?> list) {
            Map<String, Object> itemsSchema = (Map<String, Object>) schema.get("items");
            if (itemsSchema != null) {
                List<Object> result = new ArrayList<>();
                for (Object item : list) {
                    result.add(castValue(item, itemsSchema));
                }
                return result;
            }
            return val;
        }

        // 对标 Python: t == "object" and isinstance(val, dict)
        if ("object".equals(t) && val instanceof Map<?, ?> map) {
            Map<String, Object> props = (Map<String, Object>)
                    schema.getOrDefault("properties", Map.of());
            Map<String, Object> typedMap = (Map<String, Object>) map;
            return castObjectInternal(typedMap, props);
        }

        return val;
    }

    /**
     * 内部静态版本的 castObject，供静态 castValue 调用。
     *
     * @param obj   原始对象 Map
     * @param props 属性 Schema Map
     * @return 类型转换后的对象 Map
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> castObjectInternal(
            Map<String, Object> obj, Map<String, Object> props) {
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
     * 解析 JSON Schema type 字段中的非 null 类型名，委托给
     * {@link Schema#resolveJsonSchemaType}。
     *
     * @param rawType JSON Schema type 字段值
     * @return 非 null 类型名
     */
    // 对标 Python Tool._resolve_type()
    public static String resolveType(Object rawType) {
        return Schema.resolveJsonSchemaType(rawType);
    }

    /**
     * 根据工具的 JSON Schema 校验参数，返回错误消息列表（空列表表示通过）。
     *
     * @param params 待校验的参数 Map
     * @return 错误消息列表，空列表表示校验通过
     * @throws IllegalArgumentException 如果工具 schema 不是 object 类型
     */
    // 对标 Python Tool.validate_params()
    public List<String> validateParams(Map<String, Object> params) {
        if (params == null) {
            return List.of("parameters must not be null");
        }
        Map<String, Object> schema = getParameters();
        if (schema == null || !"object".equals(schema.get("type"))) {
            throw new IllegalArgumentException(
                    "Tool schema must be object type, got "
                            + (schema != null ? schema.get("type") : "null"));
        }
        Map<String, Object> fullSchema = new LinkedHashMap<>(schema);
        fullSchema.put("type", "object");
        return Schema.validateJsonSchemaValue(params, fullSchema, "");
    }

    /**
     * 构建 OpenAI 兼容的 function schema，包含 type、function（name +
     * description + parameters）字段。
     *
     * @return OpenAI function schema Map
     */
    // 对标 Python Tool.to_schema()
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
