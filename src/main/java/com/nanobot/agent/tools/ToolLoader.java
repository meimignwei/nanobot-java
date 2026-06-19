package com.nanobot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 工具发现与注册。
 * 对应 Python ToolLoader（loader.py，117 行）。
 *
 * <p>Java 适配：Spring {@code @Component} 扫描替代 {@code pkgutil.iter_modules}；
 * {@code ServiceLoader<Tool>} 替代 {@code entry_points(group="nanobot.tools")}。</p>
 */
public class ToolLoader {

    private static final Logger log = LoggerFactory.getLogger(ToolLoader.class);

    /** 跳过的基础模块名 */
    private static final Set<String> SKIP_MODULES = Set.of(
            "base", "schema", "registry", "context", "loader", "config",
            "file_state", "sandbox", "mcp", "__init__", "runtime_state"
    );

    private final List<Class<? extends Tool>> testClasses;
    private List<Class<? extends Tool>> discovered;
    private Map<String, Class<? extends Tool>> plugins;

    public ToolLoader() {
        this(null);
    }

    public ToolLoader(List<Class<? extends Tool>> testClasses) {
        this.testClasses = testClasses;
    }

    /**
     * 发现 Tool 子类（通过 Spring bean 扫描）。
     * 对应 Python discover()。
     *
     * <p>Spring 环境下通过 @Component 扫描自动注入；此方法提供非 Spring 环境的
     * 编程式发现（测试、插件）。</p>
     */
    @SuppressWarnings("unchecked")
    public List<Class<? extends Tool>> discover() {
        if (testClasses != null) return List.copyOf(testClasses);
        if (discovered != null) return discovered;

        // Spring Boot 下工具通过 @Component 扫描发现。
        // 此方法处理非 Spring 编程式发现。
        discovered = List.of();
        return discovered;
    }

    /**
     * 发现通过 Java ServiceLoader 注册的外部工具插件。
     * 对应 Python _discover_plugins()。
     */
    public Map<String, Class<? extends Tool>> discoverPlugins() {
        if (plugins != null) return plugins;
        Map<String, Class<? extends Tool>> result = new LinkedHashMap<>();
        try {
            ServiceLoader<Tool> loader = ServiceLoader.load(Tool.class);
            for (Tool tool : loader) {
                Class<? extends Tool> cls = tool.getClass();
                if (!isAbstract(cls) && isPluginDiscoverable(cls)) {
                    result.put(tool.name(), cls);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to discover tool plugins: {}", e.getMessage());
        }
        plugins = result;
        return plugins;
    }

    /**
     * 实例化并注册工具到注册表。
     * 对应 Python load()。
     *
     * @param toolBeans Spring 注入的工具 bean 列表（或手动创建的列表）
     * @param ctx       工具上下文（用于 enabled/config 检查）
     * @param registry  目标注册表
     * @param scope     作用域过滤（默认 "core"）
     * @return 已注册的工具名称列表
     */
    public List<String> load(
            Collection<Tool> toolBeans,
            ToolContext ctx,
            ToolRegistry registry,
            String scope
    ) {
        List<String> registered = new ArrayList<>();
        Set<String> builtinNames = new HashSet<>();

        // Phase 1: 内置工具（来自 Spring beans）
        for (Tool tool : toolBeans) {
            String clsLabel = tool.getClass().getSimpleName();
            try {
                if (!isInScope(tool, scope)) continue;
                if (!tool.isEnabled(ctx)) continue;
                registerTool(registry, tool, clsLabel, false, builtinNames, registered);
            } catch (Exception e) {
                log.error("Failed to register tool: {}", clsLabel, e);
            }
        }

        // Phase 2: 插件工具（来自 ServiceLoader）
        for (var entry : discoverPlugins().entrySet()) {
            String pluginName = entry.getKey();
            Class<? extends Tool> pluginClass = entry.getValue();
            try {
                Tool tool = Tool.create(ctx, pluginClass);
                if (!isInScope(tool, scope)) continue;
                if (!tool.isEnabled(ctx)) continue;
                registerTool(registry, tool, pluginName, true, builtinNames, registered);
            } catch (Exception e) {
                log.error("Failed to register plugin tool: {}", pluginName, e);
            }
        }

        return registered;
    }

    /** 注册单个工具，处理名称冲突 */
    private static void registerTool(
            ToolRegistry registry,
            Tool tool,
            String label,
            boolean isPlugin,
            Set<String> builtinNames,
            List<String> registered
    ) {
        if (registry.has(tool.name())) {
            if (isPlugin && builtinNames.contains(tool.name())) {
                log.warn("Plugin {} skipped: conflicts with built-in tool {}",
                        label, tool.name());
                return;
            }
            log.warn("Tool name collision: {} from {} overwrites existing",
                    tool.name(), label);
        }
        registry.register(tool);
        registered.add(tool.name());
        if (!isPlugin) {
            builtinNames.add(tool.name());
        }
    }

    // ---- 辅助方法 ----

    /** 检查工具是否在指定 scope 内 */
    private static boolean isInScope(Tool tool, String scope) {
        Set<String> scopes = getToolScopes(tool);
        return scopes.contains(scope);
    }

    /** 通过反射获取工具的 _scopes 字段 */
    @SuppressWarnings("unchecked")
    private static Set<String> getToolScopes(Tool tool) {
        try {
            var field = tool.getClass().getDeclaredField("_scopes");
            field.setAccessible(true);
            Object val = field.get(tool);
            if (val instanceof Set) return (Set<String>) val;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // fall through
        }
        return Set.of("core");
    }

    private static boolean isAbstract(Class<? extends Tool> cls) {
        return java.lang.reflect.Modifier.isAbstract(cls.getModifiers());
    }

    /** 检查插件类是否标记为可发现（默认 true） */
    private static boolean isPluginDiscoverable(Class<? extends Tool> cls) {
        try {
            var field = cls.getDeclaredField("_plugin_discoverable");
            field.setAccessible(true);
            Object val = field.get(null); // static field
            return !(val instanceof Boolean b) || b;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return true;
        }
    }
}
