package com.nanobot.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Configuration loading, saving, env-var resolution, and migration.
 * Mirrors nanobot/config/loader.py exactly.
 */
public final class ConfigLoader {

    private static final Pattern ENV_REF_PATTERN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);

    private static Path currentConfigPath;
    private static boolean schemaRefsReady;

    private ConfigLoader() {}

    // ---- config path management (mirrors set_config_path / get_config_path) ----

    public static void setConfigPath(Path path) {
        currentConfigPath = path;
    }

    public static Path getConfigPath() {
        if (currentConfigPath != null) {
            return currentConfigPath;
        }
        return PathUtils.DEFAULT_DATA_DIR.resolve("config.json");
    }

    // ---- load / save (mirrors load_config / save_config) ----

    public static NanobotProperties loadConfig(Path configPath) {
        Path path = configPath != null ? configPath : getConfigPath();

        NanobotProperties config = new NanobotProperties(
                null, null, null, null, null, null, null, null);
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path);
                Map<String, Object> data = MAPPER.readValue(json,
                        new TypeReference<Map<String, Object>>() {});
                data = migrateConfig(data);
                config = MAPPER.convertValue(data, NanobotProperties.class);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load config from " + path, e);
            }
        }

        applySsrfWhitelist(config);
        return config;
    }

    public static void saveConfig(NanobotProperties config, Path configPath) {
        Path path = configPath != null ? configPath : getConfigPath();
        try {
            Files.createDirectories(path.getParent());
            Map<String, Object> data = MAPPER.convertValue(config,
                    new TypeReference<Map<String, Object>>() {});
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            Files.writeString(path, json);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save config to " + path, e);
        }
    }

    // ---- env var resolution (mirrors resolve_config_env_vars) ----

    public static NanobotProperties resolveEnvVars(NanobotProperties config) {
        return resolveInPlace(config);
    }

    @SuppressWarnings("unchecked")
    private static <T> T resolveInPlace(T obj) {
        if (obj instanceof String s) {
            String resolved = ENV_REF_PATTERN.matcher(s).replaceAll(mr -> envReplace(mr.group(1)));
            return resolved.equals(s) ? obj : (T) resolved;
        }
        if (obj instanceof Map<?, ?> map) {
            var result = (Map<String, Object>) map;
            boolean changed = false;
            for (var entry : result.entrySet()) {
                var resolved = resolveInPlace(entry.getValue());
                if (resolved != entry.getValue()) {
                    result.put(entry.getKey(), resolved);
                    changed = true;
                }
            }
            return changed ? obj : obj;
        }
        if (obj instanceof List<?> list) {
            boolean changed = false;
            for (int i = 0; i < list.size(); i++) {
                var resolved = resolveInPlace(list.get(i));
                if (resolved != list.get(i)) {
                    ((List<Object>) list).set(i, resolved);
                    changed = true;
                }
            }
            return changed ? obj : obj;
        }
        return obj;
    }

    private static String envReplace(String name) {
        String value = System.getenv(name);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Environment variable '" + name + "' referenced in config is not set");
        }
        return value;
    }

    // ---- config migration (mirrors _migrate_config) ----

    @SuppressWarnings("unchecked")
    static Map<String, Object> migrateConfig(Map<String, Object> data) {
        var tools = (Map<String, Object>) data.getOrDefault("tools", Map.of());

        // Move tools.exec.restrictToWorkspace → tools.restrictToWorkspace
        var execCfg = (Map<String, Object>) tools.getOrDefault("exec", Map.of());
        if (execCfg.containsKey("restrictToWorkspace") && !tools.containsKey("restrictToWorkspace")) {
            tools.put("restrictToWorkspace", execCfg.remove("restrictToWorkspace"));
        }

        // Move old flat myEnabled / mySet → tools.my.enable / tools.my.allowSet
        if (tools.containsKey("myEnabled") || tools.containsKey("mySet")) {
            var myCfg = (Map<String, Object>) tools.computeIfAbsent("my", k -> new java.util.HashMap<>());
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

        return data;
    }

    // ---- SSRF whitelist (mirrors _apply_ssrf_whitelist; no-op until security module exists) ----

    private static void applySsrfWhitelist(NanobotProperties config) {
        // Port-once: security.network.configure_ssrf_whitelist(config.tools().ssrfWhitelist())
    }
}
