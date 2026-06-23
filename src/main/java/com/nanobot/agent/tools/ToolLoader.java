package com.nanobot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 发现并注册所有 Tool 实现。
 *
 * <p>对标 Python {@code nanobot/agent/tools/loader.py}。
 *
 * <p>发现策略：
 * <ol>
 *   <li>Spring 组件扫描：在 {@code com.nanobot.agent.tools.impl} 包中找到
 *       所有继承 Tool 的 {@code @Component} 类。</li>
 *   <li>Java {@link ServiceLoader} 加载外部插件工具
 *       （{@code META-INF/services/com.nanobot.agent.tools.Tool}）。</li>
 *   <li>过滤条件：必须是具体类（非抽象）、plugin-discoverable、
 *       通过 {@code enabled(ctx)} 检查。</li>
 *   <li>记录内置工具名；插件不能覆盖内置工具名。</li>
 * </ol>
 */
@Component
public class ToolLoader {

    private static final Logger log = LoggerFactory.getLogger(ToolLoader.class);

    /** 发现时跳过的模块名（基础设施类，非工具）。 */
    private static final Set<String> SKIP_CLASS_NAMES = Set.of(
            "Tool", "Schema", "ToolRegistry", "ToolLoader",
            "ToolContext", "FileStates", "FileStateStore",
            "RequestContext", "MCPToolWrapper"
    );

    private final ClassPathScanningCandidateComponentProvider scanner;

    /** 构造 ToolLoader，配置 Spring 类路径扫描器，过滤 Tool 的子类。 */
    public ToolLoader() {
        this.scanner = new ClassPathScanningCandidateComponentProvider(false);
        this.scanner.addIncludeFilter(new AssignableTypeFilter(Tool.class));
    }

    /**
     * 通过 Spring 组件扫描发现内置工具类。
     *
     * @return 按类名排序的内置工具类列表
     */
    public List<Class<? extends Tool>> discoverBuiltins() {
        List<Class<? extends Tool>> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();

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
     * 通过 Java ServiceLoader 发现外部插件工具。
     *
     * @return 类名到工具类的映射
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

    /**
     * 检查给定类是否为可用的具体工具类。
     *
     * @param cls  候选类
     * @param seen 已处理类名集合
     * @return 是有效具体工具类返回 true
     */
    private boolean isConcreteTool(Class<?> cls, Set<String> seen) {
        if (cls == null || cls == Tool.class) return false;
        if (SKIP_CLASS_NAMES.contains(cls.getSimpleName())) return false;
        if (cls.isInterface() || java.lang.reflect.Modifier.isAbstract(cls.getModifiers())) {
            return false;
        }
        if (!Tool.class.isAssignableFrom(cls)) return false;
        if (seen.contains(cls.getName())) return false;

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
     * 加载并将所有工具注册到给定注册表中。
     *
     * @param ctx      工具上下文，用于 enabled/create 检查
     * @param registry 目标注册表
     * @param scope    作用域过滤器（如 "core"、"subagent"、"memory"）
     * @return 已注册的工具名称列表
     */
    public List<String> load(ToolContext ctx, ToolRegistry registry, String scope) {
        List<String> registered = new ArrayList<>();
        Set<String> builtinNames = new HashSet<>();

        // 1. 通过组件扫描加载内置工具
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

        // 2. 通过 ServiceLoader 加载插件工具
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

    /**
     * 获取工具类的作用域集合。
     *
     * @param toolClass 工具类
     * @return 作用域集合，获取失败时返回 {"core"}
     */
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
