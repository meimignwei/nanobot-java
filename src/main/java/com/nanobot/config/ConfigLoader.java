package com.nanobot.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 配置加载、保存、迁移与 ${VAR} 环境变量解析工具。对标 Python loader.py。
 *
 * <p>流程：JSON 文件 → Map → migrate → resolveEnvVars → Jackson 反序列化为 Config。
 *
 * <p>对标 Python {@code nanobot/config/loader.py:1-176}。
 */
public final class ConfigLoader {

    // 对标 Python loader.py:87 _ENV_REF_PATTERN
    private static final Pattern ENV_REF_PATTERN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // 对标 Python loader.py:15 _current_config_path
    private static Path currentConfigPath;

    private ConfigLoader() {}

    // 对标 Python loader.py:19-21 set_config_path()
    public static void setConfigPath(Path path) {
        currentConfigPath = path;
    }

    // 对标 Python loader.py:25-29 get_config_path()
    public static Path getConfigPath() {
        return currentConfigPath != null
                ? currentConfigPath
                : Path.of(System.getProperty("user.home"), ".nanobot", "config.json");
    }

    /**
     * 从 JSON 文件加载配置，自动处理旧格式迁移和 ${VAR} 环境变量引用解析。
     *
     * <p>流程：JSON → Map → migrateConfig → resolveEnvVarsInPlace → Jackson → Config。
     * 文件不存在时返回全默认值的 Config。
     *
     * @param configPath 配置文件路径，null 则使用 ~/.nanobot/config.json
     * @return 加载并解析完成的 Config 实例
     * @throws RuntimeException JSON 解析失败或文件不可读时抛出
     */
    // 对标 Python loader.py:32-60 load_config() — 主加载流程
    public static Config load(Path configPath) {
        Path path = configPath != null ? configPath : getConfigPath();
        if (!Files.exists(path)) {
            return new Config();
        }
        try {
            String json = Files.readString(path);
            Map<String, Object> data = OBJECT_MAPPER.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            migrateConfig(data);
            resolveEnvVarsInPlace(data);
            String migratedJson = OBJECT_MAPPER.writeValueAsString(data);
            return OBJECT_MAPPER.readValue(migratedJson, Config.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config from " + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * 将配置序列化并写入 JSON 文件。
     *
     * @param config     待保存的 Config 实例
     * @param configPath 目标文件路径，null 则使用默认路径
     * @throws RuntimeException 写入失败时抛出
     */
    // 对标 Python loader.py:70-84 save_config() — 持久化配置
    public static void save(Config config, Path configPath) {
        Path path = configPath != null ? configPath : getConfigPath();
        try {
            Files.createDirectories(path.getParent());
            OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), config);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save config to " + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * 原地递归解析 Map 中所有字符串值的 {@code ${VAR}} 环境变量引用。
     * 未设置的环境变量会抛出 IllegalArgumentException。
     *
     * <p>Spring Boot 的 Relaxed Binding 不会处理属性值内部的 ${VAR} 替换，需要此方法补充。
     *
     * @param data 配置数据 Map，会被原地修改
     * @throws IllegalArgumentException 引用了未设置的环境变量时抛出
     */
    // 对标 Python loader.py:90-97 resolve_config_env_vars()
    public static void resolveEnvVarsInPlace(Map<String, Object> data) {
        resolveMapInPlace(data);
    }

    @SuppressWarnings("unchecked")
    private static void resolveMapInPlace(Map<String, Object> map) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s) {
                e.setValue(resolveString(s));
            } else if (v instanceof Map) {
                resolveMapInPlace((Map<String, Object>) v);
            } else if (v instanceof java.util.List) {
                resolveListInPlace((java.util.List<Object>) v);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void resolveListInPlace(java.util.List<Object> list) {
        for (int i = 0; i < list.size(); i++) {
            Object v = list.get(i);
            if (v instanceof String s) {
                list.set(i, resolveString(s));
            } else if (v instanceof Map) {
                resolveMapInPlace((Map<String, Object>) v);
            } else if (v instanceof java.util.List) {
                resolveListInPlace((java.util.List<Object>) v);
            }
        }
    }

    // 对标 Python loader.py:143-150 _env_replace() — 查询环境变量或抛异常
    private static String resolveString(String value) {
        return ENV_REF_PATTERN.matcher(value).replaceAll(mr -> {
            String name = mr.group(1);
            String env = System.getenv(name);
            if (env == null) {
                throw new IllegalArgumentException(
                        "Environment variable '" + name + "' referenced in config is not set");
            }
            return env;
        });
    }

    /**
     * 迁移旧版配置键名到当前格式：
     * <ul>
     *   <li>{@code tools.exec.restrictToWorkspace} → {@code tools.restrictToWorkspace}</li>
     *   <li>{@code tools.myEnabled / tools.mySet} → {@code tools.my.enable / tools.my.allowSet}</li>
     * </ul>
     *
     * @param data 原始配置 Map，会被原地修改
     * @return 迁移后的同一 Map 实例
     */
    // 对标 Python loader.py:153-175 _migrate_config()
    static void migrateConfig(Map<String, Object> data) {
        Map<String, Object> tools = (Map<String, Object>) data.computeIfAbsent("tools", k -> new java.util.HashMap<>());
        Map<String, Object> execCfg = (Map<String, Object>) tools.computeIfAbsent("exec", k -> new java.util.HashMap<>());

        // 对标 Python loader.py:157-159 — 迁移 tools.exec.restrictToWorkspace → tools.restrictToWorkspace
        if (execCfg.containsKey("restrictToWorkspace")
                && !tools.containsKey("restrictToWorkspace")) {
            tools.put("restrictToWorkspace", execCfg.remove("restrictToWorkspace"));
        }

        // 对标 Python loader.py:164-173 — 迁移 myEnabled/mySet → tools.my.{enable,allowSet}
        if (tools.containsKey("myEnabled") || tools.containsKey("mySet")) {
            Map<String, Object> myCfg = (Map<String, Object>) tools.computeIfAbsent("my", k -> new java.util.HashMap<>());
            if (tools.containsKey("myEnabled") && !myCfg.containsKey("enable")) {
                myCfg.put("enable", tools.remove("myEnabled"));
            } else {
                tools.remove("myEnabled");
            }
            if (tools.containsKey("mySet") && !myCfg.containsKey("allowSet")) {
                myCfg.put("allowSet", tools.remove("mySet"));
            } else {
                tools.remove("mySet");
            }
        }
    }
}
