# 02 — config 包：配置系统

**对标 Python：** `nanobot/config/schema.py` (536行), `loader.py` (~100行), `paths.py` (76行)

## Python 源码分析

### `schema.py` — Pydantic 配置模型

```
Config (BaseSettings)
  env_prefix = "NANOBOT_"
  env_nested_delimiter = "__"
  alias_generator = to_camel  (JSON camelCase 兼容)

  字段:
  ├── agents: AgentsConfig
  │   └── defaults: AgentDefaults
  │       ├── workspace: str
  │       ├── model: str = "anthropic/claude-sonnet-4-6"
  │       ├── provider: str = "auto"
  │       ├── max_tokens: int = 8192
  │       ├── context_window_tokens: int = 65536
  │       ├── temperature: float = 0.1
  │       ├── max_tool_iterations: int = 200
  │       ├── provider_retry_mode: str = "standard"
  │       ├── reasoning_effort: str | None
  │       ├── disabled_skills: list[str] = []
  │       ├── session_ttl_minutes: int = 0
  │       ├── max_messages: int = 120
  │       ├── consolidation_ratio: float = 0.5
  │       ├── bot_name: str
  │       ├── bot_icon: str
  │       ├── timezone: str = "UTC"
  │       ├── fallback_models: list[...]
  │       ├── unified_session: bool = False
  │       └── dream: DreamConfig
  ├── channels: ChannelsConfig
  │   ├── send_progress: bool = True
  │   ├── send_tool_hints: bool = False
  │   ├── show_reasoning: bool = True
  │   ├── extract_document_text: bool = True
  │   ├── send_max_retries: int = 3
  │   └── extra="allow"  (channel 自定义字段)
  ├── transcription: TranscriptionConfig
  ├── providers: ProvidersConfig  (30+ provider 配置)
  │   ├── anthropic: ProviderConfig
  │   ├── openai: ProviderConfig
  │   ├── openrouter: ProviderConfig
  │   ├── ... (每个 provider 一个字段)
  │   └── bedrock: BedrockProviderConfig (extends ProviderConfig)
  ├── api: ApiConfig (host, port, timeout)
  ├── gateway: GatewayConfig (host, port, heartbeat)
  ├── tools: ToolsConfig
  │   ├── web, exec, cli_apps, my, image_generation
  │   ├── restrict_to_workspace, mcp_servers, ssrf_whitelist
  │   └── webui_allow_local_service_access
  ├── model_presets: dict[str, ModelPresetConfig]
  └── heartbeat: HeartbeatConfig

ProviderConfig 字段:
  api_key, api_base, api_type, api_version, engine,
  extra_headers, extra_body, extra_query, model_map, max_tokens

关键方法 (Config 上):
  _match_provider(model, preset) → ProviderMatch
  get_provider(model, preset) → ProviderConfig
  get_api_key(model, preset) → str
  resolve_preset(name) → ModelPresetConfig
```

### `loader.py` — 配置加载

```python
def load_config(config_path=None) -> Config:
    # 1. 确定配置文件路径
    # 2. 加载 JSON (支持注释)
    # 3. Config(**loaded_dict) → Pydantic 验证
    # 4. model_rebuild() → 解决前向引用
    # 5. 返回 Config 实例

def set_config_path(path), get_config_path()
```

### `paths.py` — 路径工具

```python
def workspace_dir() -> Path
def legacy_sessions_dir() -> Path
def resolve_workspace_path(config_path, workspace) -> Path
```

## Java 实现方案

### 1. 整体策略

用 Spring Boot `@ConfigurationProperties` 对标 Pydantic `BaseSettings`。配置按树状结构拆分为多个 Java record。

```
Python: Config (一个大类, 536行)
Java:   NanobotProperties (根 record) + 子 record:
          AgentProperties → AgentDefaultsProperties
          ChannelsProperties
          ProvidersProperties
          ProviderProperties (per-provider)
          ApiProperties
          GatewayProperties
          ToolsProperties → McpServerProperties
          TranscriptionProperties
          DreamProperties
          HeartbeatProperties
          ModelPresetProperties
```

### 2. 根配置 `NanobotProperties.java`

```java
package com.nanobot.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "nanobot")
public record NanobotProperties(

    @NestedConfigurationProperty
    @Valid
    AgentProperties agents,

    @NestedConfigurationProperty
    @Valid
    ChannelsProperties channels,

    @NestedConfigurationProperty
    @Valid
    TranscriptionProperties transcription,

    @NestedConfigurationProperty
    @Valid
    ProvidersProperties providers,

    @NestedConfigurationProperty
    @Valid
    ApiProperties api,

    @NestedConfigurationProperty
    @Valid
    GatewayProperties gateway,

    @NestedConfigurationProperty
    @Valid
    ToolsProperties tools,

    Map<String, @Valid ModelPresetProperties> modelPresets

) {

    // 默认值由 Spring 在绑定后通过 compact constructor 或 @Default 处理

    public NanobotProperties {
        if (agents == null) agents = AgentProperties.DEFAULTS;
        if (channels == null) channels = ChannelsProperties.DEFAULTS;
        if (providers == null) providers = ProvidersProperties.DEFAULTS;
        if (api == null) api = ApiProperties.DEFAULTS;
        if (gateway == null) gateway = GatewayProperties.DEFAULTS;
        if (tools == null) tools = ToolsProperties.DEFAULTS;
        if (modelPresets == null) modelPresets = new HashMap<>();
    }

    // --- 对标 Python Config 的关键方法 ---

    /**
     * 对标 Config.resolve_preset().
     * 按名称解析 ModelPresetConfig，找不到时返回 defaults.
     */
    public ModelPresetProperties resolvePreset(String name) {
        if (name == null || name.isEmpty() || "default".equals(name)) {
            return ModelPresetProperties.fromDefaults(agents.defaults());
        }
        ModelPresetProperties preset = modelPresets.get(name);
        if (preset == null) {
            throw new IllegalArgumentException(
                "Unknown model preset: '" + name + "'. " +
                "Available presets: " + modelPresets.keySet());
        }
        return preset;
    }

    /**
     * 对标 Config.get_api_key().
     * 从 preset 或 defaults 获取 API key.
     */
    public String getApiKey(String model, ModelPresetProperties preset) {
        ModelPresetProperties resolved = preset != null ? preset : resolvePreset(null);
        String forcedProvider = resolved.provider();
        if (!"auto".equals(forcedProvider)) {
            ProviderProperties providerProps = providers.getByName(forcedProvider);
            if (providerProps != null && providerProps.apiKey() != null) {
                return providerProps.apiKey();
            }
            // 也检查 env
            String envKey = providers.findEnvKey(forcedProvider);
            if (envKey != null) {
                String envVal = System.getenv(envKey);
                if (envVal != null) return envVal;
            }
        }
        // 通过 model 匹配 provider
        ProviderMatchResult match = matchProvider(model, resolved);
        if (match != null && match.config().apiKey() != null) {
            return match.config().apiKey();
        }
        return null;
    }

    /**
     * 对标 Config._match_provider().
     * 核心匹配逻辑，按优先级:
     * 1. 显式 provider 指定
     * 2. model 名 prefix 匹配 (如 "anthropic/claude-xxx")
     * 3. 关键词匹配 (如 "claude" 在 model 中)
     * 4. 本地 provider base URL 匹配
     * 5. 有 API key 的 gateway 优先
     * 6. 有 API key 的普通 provider
     */
    public ProviderMatchResult matchProvider(String model, ModelPresetProperties preset) {
        ModelPresetProperties resolved = preset != null ? preset : resolvePreset(null);
        String forced = resolved.provider();
        String resolvedModel = resolved.model();

        if (model != null && !model.isEmpty()) {
            resolvedModel = model;
        }

        // Step 1: 显式 provider
        if (!"auto".equals(forced)) {
            return matchByExplicitProvider(forced, resolvedModel);
        }

        // Step 2: prefix 匹配
        ProviderMatchResult prefixMatch = matchByPrefix(resolvedModel);
        if (prefixMatch != null) return prefixMatch;

        // Step 3: 关键词匹配
        ProviderMatchResult keywordMatch = matchByKeyword(resolvedModel);
        if (keywordMatch != null) return keywordMatch;

        // Step 4: 本地 provider base URL
        ProviderMatchResult localMatch = matchLocalByBaseUrl(resolvedModel);
        if (localMatch != null) return localMatch;

        // Step 5-6: 有 key 的 provider
        ProviderMatchResult keyMatch = matchByAnyAvailableKey();
        if (keyMatch != null) return keyMatch;

        throw new IllegalStateException(
            "Cannot match any provider for model '" + resolvedModel + "'");
    }

    // --- 内部匹配方法 ---

    private ProviderMatchResult matchByExplicitProvider(String providerName, String model) {
        ProviderProperties props = providers.getByName(providerName);
        if (props == null) {
            throw new IllegalArgumentException(
                "Unknown provider: '" + providerName + "'");
        }
        return new ProviderMatchResult(props, model);
    }

    private ProviderMatchResult matchByPrefix(String model) {
        String lowerModel = model.toLowerCase();
        for (ProviderSpec spec : providers.getRegistryOrder()) {
            String prefix = spec.prefixForModel(lowerModel);
            if (prefix != null) {
                ProviderProperties props = providers.getByName(spec.name());
                return new ProviderMatchResult(props, model, spec);
            }
        }
        return null;
    }

    // ... 更多匹配逻辑
}

// 匹配结果
public record ProviderMatchResult(
    ProviderProperties config,
    String model,
    @Nullable ProviderSpec spec
) {}
```

### 3. AgentDefaults 子配置

```java
package com.nanobot.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.List;

public record AgentDefaultsProperties(
    @NotBlank
    String workspace,

    String model,           // 默认: "anthropic/claude-sonnet-4-6"
    String provider,        // 默认: "auto"

    @Min(1)
    int maxTokens,          // 默认: 8192

    @Min(1)
    int contextWindowTokens, // 默认: 65536

    double temperature,     // 默认: 0.1

    @Min(1)
    int maxToolIterations,  // 默认: 200

    String providerRetryMode, // 默认: "standard"
    String reasoningEffort,   // nullable
    List<String> disabledSkills,
    int sessionTtlMinutes,
    int maxMessages,        // 默认: 120

    double consolidationRatio, // 默认: 0.5

    String botName,
    String botIcon,

    String timezone,        // 默认: "UTC"
    boolean unifiedSession,

    List<Object> fallbackModels,  // 字符串或内联 preset

    @NestedConfigurationProperty
    DreamProperties dream
) {
    public static final AgentDefaultsProperties DEFAULTS = new AgentDefaultsProperties(
        ".nanobot-java/workspace",
        "anthropic/claude-sonnet-4-6",
        "auto", 8192, 65536, 0.1, 200, "standard",
        null, List.of(), 0, 120, 0.5,
        "nanobot-java", "🐈", "UTC", false,
        List.of(), DreamProperties.DEFAULTS
    );

    public GenerationSettings toGenerationSettings() {
        return new GenerationSettings(
            temperature,
            maxTokens,
            reasoningEffort
        );
    }
}
```

### 4. Provider 配置系统

```java
// ProvidersProperties.java — 对标 Pydantic ProvidersConfig
public record ProvidersProperties(
    ProviderProperties anthropic,
    ProviderProperties openai,
    ProviderProperties openrouter,
    ProviderProperties deepseek,
    ProviderProperties groq,
    ProviderProperties gemini,
    ProviderProperties zhipu,
    ProviderProperties dashscope,
    ProviderProperties moonshot,
    ProviderProperties minimax,
    ProviderProperties mistral,
    ProviderProperties stepfun,
    ProviderProperties ollama,
    ProviderProperties vllm,
    ProviderProperties siliconflow,
    ProviderProperties novita,
    ProviderProperties volcengine,
    // ... 30+ provider
    BedrockProviderProperties bedrock
) {
    public static final ProvidersProperties DEFAULTS = new ProvidersProperties(
        null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null,
        null, null, null
    );

    /**
     * 按 registry 名称查找 provider 配置.
     */
    public ProviderProperties getByName(String name) {
        // 反射或 switch 查找
        return switch (name) {
            case "anthropic" -> anthropic;
            case "openai" -> openai;
            case "openrouter" -> openrouter;
            // ...
            default -> null;
        };
    }

    /**
     * 查找 provider 的 env key.
     */
    public String findEnvKey(String name) {
        ProviderSpec spec = ProviderSpec.findByName(name);
        return spec != null ? spec.envKey() : null;
    }
}

// ProviderProperties.java — 对标 Pydantic ProviderConfig
public record ProviderProperties(
    String apiKey,
    String apiBase,
    String apiType,
    String apiVersion,
    String engine,
    Map<String, String> extraHeaders,
    Map<String, Object> extraBody,
    Map<String, String> extraQuery,
    Map<String, String> modelMap,
    Integer maxTokens
) {
    public static final ProviderProperties DEFAULTS = new ProviderProperties(
        null, null, null, null, null,
        null, null, null, null, null
    );
}

// BedrockProviderProperties.java — 对标 Python BedrockProviderConfig
public record BedrockProviderProperties(
    String apiKey,
    String apiBase,
    // ... 继承 ProviderConfig 字段
    String region,
    String profile
) {}
```

### 5. 配置加载流程

Spring Boot 自动处理替代 `loader.py` 的手动加载：

```yaml
# Python 加载链:  JSON文件 → Config(**dict) → Pydantic 验证
# Java 加载链:   application.yml + env vars → @ConfigurationProperties → Jakarta Bean Validation

# 环境变量映射 (Spring Boot Relaxed Binding):
# NANOBOT_AGENTS_DEFAULTS_MODEL=xxx
#   → nanobot.agents.defaults.model = xxx

# 配置文件位置:
# ~/.nanobot-java/config.yml (由 CLI 指定)
# 或通过 spring.config.additional-location 指定
```

### 6. `AppPaths.java` — 对标 `paths.py`

```java
package com.nanobot.config;

import java.nio.file.Path;
import java.nio.file.Paths;

public class AppPaths {

    private final String workspacePath;

    public AppPaths(NanobotProperties properties) {
        this.workspacePath = resolveHome(properties.agents().defaults().workspace());
    }

    public Path workspaceDir() {
        return Paths.get(workspacePath).toAbsolutePath();
    }

    public Path sessionsDir() {
        return workspaceDir().resolve("sessions");
    }

    public Path memoryPath() {
        return workspaceDir().resolve("memory");
    }

    // 对标 Python 的 ~展开
    private static String resolveHome(String path) {
        if (path.startsWith("~")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
```

### 7. `ConfigLoader.java` — 对标 `loader.py`

```java
package com.nanobot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class ConfigLoader {

    private final Environment environment;
    private final ObjectMapper objectMapper;

    public ConfigLoader(Environment environment, ObjectMapper objectMapper) {
        this.environment = environment;
        this.objectMapper = objectMapper;
    }

    /**
     * 对标 Python load_config(config_path).
     * Spring Boot 已自动绑定 NanobotProperties，此方法提供
     * 额外的运行时配置重加载（用于 CLI preset 切换等场景）。
     */
    public NanobotProperties loadConfig(Path configPath) {
        // 读取外部 JSON/YAML → 合并到现有 properties
        throw new UnsupportedOperationException("TBD: 实现运行时重加载");
    }
}
```

## 关键设计决策

### Pydantic model_validator → Bean Validation

Python 的 `@model_validator(mode="after")` 交叉字段校验在 Java 中用 `@PostConstruct` 校验 bean 实现：

```java
@Component
public class ConfigValidator {

    private final NanobotProperties properties;

    public ConfigValidator(NanobotProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void validate() {
        if (properties.modelPresets().containsKey("default")) {
            throw new IllegalStateException(
                "model_preset name 'default' is reserved");
        }
        // 其他交叉字段校验
    }
}
```

### 循环引用

Python 用 `_lazy_default()` + `model_rebuild()` 解决 schema.py 和 tool 模块间的循环引用。Java 中 `@ConfigurationProperties` record 是纯数据，不存在此问题。

### camelCase 兼容

Jackson 默认支持 camelCase。如需更灵活的别名（对标 Pydantic `AliasChoices`），可用 `@JsonAlias`。

## 验证标准

```bash
# 1. 启动加载默认配置
java -jar nanobot-java.jar
# 预期: 日志显示 agent.defaults.model = anthropic/claude-sonnet-4-6

# 2. 环境变量覆盖
NANOBOT_AGENTS_DEFAULTS_MODEL=openai/gpt-5 java -jar nanobot-java.jar
# 预期: 日志显示 model = openai/gpt-5

# 3. provider match 测试
# JUnit: 构造多种 ProviderSpec，验证 matchProvider() 返回正确结果
```

## 代码量估算

| 文件 | 行数 |
|------|------|
| NanobotProperties.java (根) | ~200 |
| AgentDefaultsProperties.java | ~80 |
| AgentsProperties.java | ~20 |
| ChannelsProperties.java | ~30 |
| ProvidersProperties.java | ~100 |
| ProviderProperties.java | ~40 |
| BedrockProviderProperties.java | ~20 |
| ApiProperties.java | ~20 |
| GatewayProperties.java | ~25 |
| ToolsProperties.java | ~50 |
| McpServerProperties.java | ~30 |
| ModelPresetProperties.java | ~40 |
| TranscriptionProperties.java | ~15 |
| DreamProperties.java | ~15 |
| HeartbeatProperties.java | ~15 |
| AppPaths.java | ~40 |
| ConfigLoader.java | ~30 |
| ConfigValidator.java | ~30 |
| **合计** | **~800** |
