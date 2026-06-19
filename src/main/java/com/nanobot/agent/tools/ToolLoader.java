package com.nanobot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Tool discovery and registration.
 * Port of Python ToolLoader (loader.py, 117 lines).
 *
 * Java adaptation: Spring {@code @Component} scanning replaces
 * {@code pkgutil.iter_modules}; {@code ServiceLoader<Tool>} replaces
 * {@code entry_points(group="nanobot.tools")}.
 */
public class ToolLoader {

    private static final Logger log = LoggerFactory.getLogger(ToolLoader.class);

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
     * Port of Python discover() — finds Tool subclasses via Spring bean scanning.
     * When Spring context is available, beans are injected directly; this method
     * provides programmatic discovery for non-Spring contexts (tests, plugins).
     */
    @SuppressWarnings("unchecked")
    public List<Class<? extends Tool>> discover() {
        if (testClasses != null) return List.copyOf(testClasses);
        if (discovered != null) return discovered;

        // In Spring Boot, tools are discovered via @Component scanning.
        // This method handles non-Spring programmatic discovery.
        // Tools are typically registered via load(toolBeans, ctx, registry).
        discovered = List.of();
        return discovered;
    }

    /**
     * Port of Python _discover_plugins() — discovers external tool plugins
     * registered via Java ServiceLoader.
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
     * Port of Python load() — instantiates and registers tools into the registry.
     *
     * @param toolBeans Spring-injected tool beans (or manually created list)
     * @param ctx       the tool context for enabled/config checks
     * @param registry  the target registry
     * @param scope     scope filter (default "core")
     * @return list of registered tool names
     */
    public List<String> load(
            Collection<Tool> toolBeans,
            ToolContext ctx,
            ToolRegistry registry,
            String scope
    ) {
        List<String> registered = new ArrayList<>();
        Set<String> builtinNames = new HashSet<>();

        // Phase 1: built-in tools (from Spring beans)
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

        // Phase 2: plugin tools (from ServiceLoader)
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

    // ---- helpers ----

    private static boolean isInScope(Tool tool, String scope) {
        // _scopes field — default to {"core"} if not specified
        Set<String> scopes = getToolScopes(tool);
        return scopes.contains(scope);
    }

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
