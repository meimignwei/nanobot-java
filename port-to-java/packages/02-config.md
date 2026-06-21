# 02 — config 包：配置系统

**对标 Python：** `nanobot/config/schema.py` (537行), `loader.py` (176行), `paths.py` (77行)

## Python 源码分析

### `schema.py` — Pydantic 配置模型

```
Base (BaseModel)
  alias_generator = to_camel, populate_by_name = True
  → 同时接受 camelCase 和 snake_case 键名

Config (BaseSettings)
  env_prefix = "NANOBOT_"
  env_nested_delimiter = "__"

  字段:
  ├── agents: AgentsConfig
  │   └── defaults: AgentDefaults
  │       ├── workspace: str = "~/.nanobot/workspace"
  │       ├── model_preset: str | None = None        ← 当前激活的 preset 名
  │       ├── model: str = "anthropic/claude-opus-4-5"
  │       ├── provider: str = "auto"
  │       ├── max_tokens: int = 8192
  │       ├── context_window_tokens: int = 65536
  │       ├── context_block_limit: int | None = None
  │       ├── temperature: float = 0.1
  │       ├── fallback_models: list[FallbackCandidate] = []
  │       ├── max_tool_iterations: int = 200
  │       ├── max_concurrent_subagents: int = 1       ← 并发子 agent 限制
  │       ├── max_tool_result_chars: int = 16000
  │       ├── provider_retry_mode: Literal["standard","persistent"] = "standard"
  │       ├── tool_hint_max_length: int = 40          ← 别名 toolHintMaxLength
  │       ├── reasoning_effort: str | None = None
  │       ├── timezone: str = "UTC"
  │       ├── bot_name: str = "nanobot"
  │       ├── bot_icon: str = "🐈"
  │       ├── unified_session: bool = False
  │       ├── disabled_skills: list[str] = []
  │       ├── session_ttl_minutes: int = 0            ← 别名 idleCompactAfterMinutes
  │       ├── max_messages: int = 120
  │       ├── consolidation_ratio: float = 0.5
  │       └── dream: DreamConfig
  ├── channels: ChannelsConfig
  │   ├── extra="allow"  ← 允许插件自定义字段
  │   ├── send_progress: bool = True
  │   ├── send_tool_hints: bool = False
  │   ├── show_reasoning: bool = True
  │   ├── extract_document_text: bool = True
  │   ├── send_max_retries: int = 3  (ge=0, le=10)
  │   ├── transcription_provider: str = "groq"        ← Deprecated
  │   └── transcription_language: str | None = None   ← Deprecated, pattern=[a-z]{2,3}
  ├── transcription: TranscriptionConfig
  │   ├── enabled: bool = True
  │   ├── provider: str | None = None
  │   ├── model: str | None = None
  │   ├── language: str | None = None  (pattern=[a-z]{2,3})
  │   ├── max_duration_sec: int = 120  (ge=1, le=600)
  │   └── max_upload_mb: int = 25      (ge=1, le=100)
  ├── providers: ProvidersConfig  (37 个 provider 字段)
  │   ├── custom, azure_openai, bedrock, anthropic, openai, openrouter
  │   ├── assemblyai, huggingface, skywork, deepseek, groq, zhipu, dashscope
  │   ├── vllm, ollama, lm_studio, atomic_chat, ovms, gemini, moonshot
  │   ├── minimax, minimax_anthropic, mistral, stepfun, xiaomi_mimo
  │   ├── longcat, ant_ling, aihubmix, siliconflow, novita, volcengine
  │   ├── volcengine_coding_plan, byteplus, byteplus_coding_plan
  │   ├── openai_codex (exclude=True), github_copilot (exclude=True)
  │   ├── qianfan, nvidia
  │   └── @model_validator: api_type != "auto" 只允许 openai
  ├── api: ApiConfig (host, port, timeout)
  ├── gateway: GatewayConfig (host, port, heartbeat)
  ├── tools: ToolsConfig
  │   ├── web, exec, cli_apps, my, image_generation  ← 通过 _lazy_default 解决循环引用
  │   ├── restrict_to_workspace: bool = False
  │   ├── webui_allow_local_service_access: bool = True  ← 多别名
  │   ├── mcp_servers: dict[str, MCPServerConfig]
  │   └── ssrf_whitelist: list[str]
  └── model_presets: dict[str, ModelPresetConfig]  ← 别名 modelPresets

ProviderConfig 字段:
  api_key, api_base, api_type (Literal["auto","chat_completions","responses"]),
  extra_headers, extra_body, extra_query

关键方法 (Config 上):
  _validate_model_preset()           → 校验 default 不能作为 preset 名等
  resolve_default_preset()           → 从 agents.defaults 构建默认 preset
  resolve_preset(name)               → 按名称解析 preset
  workspace_path (property)          → 展开 ~ 的 workspace 路径
  _match_provider(model, preset)     → 返回 (ProviderConfig, spec_name)
  get_provider(model, preset)        → 返回 ProviderConfig
  get_provider_name(model, preset)   → 返回 registry 名如 "deepseek"
  get_api_key(model, preset)         → 返回 api_key 字符串
  get_api_base(model, preset)        → 返回 api_base，fallback 到 spec.default_api_base
```

### `loader.py` — 配置加载

```python
# 全局配置路径
_current_config_path: Path | None = None
set_config_path(path), get_config_path() → Path

load_config(config_path=None) → Config:
    1. _resolve_tool_config_refs()  ← 解决循环引用
    2. 读取 JSON
    3. _migrate_config(data)        ← 旧格式迁移
    4. Config.model_validate(data)
    5. _apply_ssrf_whitelist(config)

save_config(config, config_path=None) → None:
    序列化 config.model_dump(mode="json", by_alias=True)

resolve_config_env_vars(config) → Config:
    原地递归解析字符串中的 ${VAR} 引用，未设置则抛 ValueError

_migrate_config(data) → dict:
    tools.exec.restrictToWorkspace → tools.restrictToWorkspace
    tools.myEnabled / tools.mySet  → tools.my.{enable, allowSet}
```

### `paths.py` — 路径工具

```python
get_config_path() → Path          ← 懒导入 loader.get_config_path
get_data_dir() → Path             ← config_path 的父目录
get_runtime_subdir(name) → Path   ← data_dir / name
get_media_dir(channel=None) → Path
get_cron_dir() → Path
get_logs_dir() → Path
get_webui_dir() → Path
get_workspace_path(workspace=None) → Path   ← 默认 ~/.nanobot/workspace
is_default_workspace(workspace) → bool
get_cli_history_path() → Path
get_bridge_install_dir() → Path
get_legacy_sessions_dir() → Path
```

## Java 实现方案

### 1. 整体策略

用 Spring Boot `@ConfigurationProperties` 对标 Pydantic `BaseSettings`。配置按树状结构拆分为多个 Java record。

与 Python 的关键差异：
- **可变性**：Pydantic `BaseModel` 是可变的（属性可赋值），Spring `@ConfigurationProperties` record 不可变。如需运行时修改后保存，需配合可变副本或 POJO。
- **别名**：Python `Base` 通过 `alias_generator=to_camel` 同时接受 camelCase 和 snake_case。Java 中用 `@JsonAlias` 或 Spring Relaxed Binding 兼容。
- **`${VAR}` 解析**：Spring Relaxed Binding 处理 `NANOBOT_XXX=yyy` → 属性绑定，但**不会自动处理字符串值内部的 `${VAR}` 引用**（如 `"apiBase": "${MY_BASE}"`），需自定义解析器。

```
Python: Config (一个大类, 537行)
Java:   NanobotProperties (根 record) + 子 record:
          AgentsProperties → AgentDefaultsProperties
          ChannelsProperties
          TranscriptionProperties
          ProvidersProperties → ProviderProperties / BedrockProviderProperties
          ApiProperties
          GatewayProperties → HeartbeatProperties
          ToolsProperties → McpServerProperties + 工具子配置占位
          ModelPresetProperties
          InlineFallbackProperties
          DreamProperties
```

### 2. 根配置 `NanobotProperties.java`

```java
package com.nanobot.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "nanobot")
public record NanobotProperties(

    @NestedConfigurationProperty @Valid
    AgentsProperties agents,

    @NestedConfigurationProperty @Valid
    ChannelsProperties channels,

    @NestedConfigurationProperty @Valid
    TranscriptionProperties transcription,

    @NestedConfigurationProperty @Valid
    ProvidersProperties providers,

    @NestedConfigurationProperty @Valid
    ApiProperties api,

    @NestedConfigurationProperty @Valid
    GatewayProperties gateway,

    @NestedConfigurationProperty @Valid
    ToolsProperties tools,

    @JsonAlias("modelPresets")
    Map<String, @Valid ModelPresetProperties> modelPresets

) {
    public NanobotProperties {
        if (agents == null) agents = AgentsProperties.DEFAULTS;
        if (channels == null) channels = ChannelsProperties.DEFAULTS;
        if (transcription == null) transcription = TranscriptionProperties.DEFAULTS;
        if (providers == null) providers = ProvidersProperties.DEFAULTS;
        if (api == null) api = ApiProperties.DEFAULTS;
        if (gateway == null) gateway = GatewayProperties.DEFAULTS;
        if (tools == null) tools = ToolsProperties.DEFAULTS;
        if (modelPresets == null) modelPresets = new HashMap<>();
    }

    // --- 对标 Python Config 方法 ---

    /**
     * 对标 Config.resolve_default_preset().
     * 从 agents.defaults 字段构建隐式 default preset.
     */
    public ModelPresetProperties resolveDefaultPreset() {
        AgentDefaultsProperties d = agents.defaults();
        return new ModelPresetProperties(
            null,                    // label
            d.model(),
            d.provider(),
            d.maxTokens(),
            d.contextWindowTokens(),
            d.temperature(),
            d.reasoningEffort()
        );
    }

    /**
     * 对标 Config.resolve_preset().
     * agents.defaults.model_preset 优先；name 为 null 或 "default" 时返回默认 preset.
     */
    public ModelPresetProperties resolvePreset(String name) {
        String active = agents.defaults().modelPreset();
        String resolvedName = (name == null) ? active : name;
        if (resolvedName == null || resolvedName.isEmpty() || "default".equals(resolvedName)) {
            return resolveDefaultPreset();
        }
        ModelPresetProperties preset = modelPresets.get(resolvedName);
        if (preset == null) {
            throw new IllegalArgumentException(
                "model_preset '" + resolvedName + "' not found in model_presets");
        }
        return preset;
    }

    /**
     * 对标 Config.workspace_path (property).
     */
    public Path workspacePath() {
        String ws = agents.defaults().workspace();
        return Paths.get(ws.replaceFirst("^~", System.getProperty("user.home"))).toAbsolutePath();
    }

    /**
     * 对标 Config._match_provider().
     * 返回 (ProviderProperties, specName).
     */
    public ProviderMatchResult matchProvider(String model, ModelPresetProperties preset) {
        ModelPresetProperties resolved = preset != null ? preset : resolvePreset(null);
        String forced = resolved.provider();
        String resolvedModel = (model != null && !model.isEmpty()) ? model : resolved.model();

        if (!"auto".equals(forced)) {
            return matchByExplicitProvider(forced, resolvedModel);
        }

        String modelLower = resolvedModel.toLowerCase();
        String modelNormalized = modelLower.replace("-", "_");
        String modelPrefix = modelLower.contains("/")
            ? modelLower.substring(0, modelLower.indexOf('/')) : "";
        String normalizedPrefix = modelPrefix.replace("-", "_");

        // Step 1: 显式 provider prefix 匹配
        ProviderMatchResult prefixMatch = matchByPrefix(normalizedPrefix, modelLower);
        if (prefixMatch != null) return prefixMatch;

        // Step 2: 关键词匹配
        ProviderMatchResult keywordMatch = matchByKeyword(modelLower, modelNormalized);
        if (keywordMatch != null) return keywordMatch;

        // Step 3: 本地 provider base URL 匹配
        ProviderMatchResult localMatch = matchLocalByBaseUrl();
        if (localMatch != null) return localMatch;

        // Step 4: 有 key 的 provider（gateway 优先）
        ProviderMatchResult keyMatch = matchByAnyAvailableKey();
        if (keyMatch != null) return keyMatch;

        return null;  // 对标 Python 返回 (None, None)
    }

    /**
     * 对标 Config.get_provider().
     */
    public ProviderProperties getProvider(String model, ModelPresetProperties preset) {
        ProviderMatchResult r = matchProvider(model, preset);
        return r != null ? r.config() : null;
    }

    /**
     * 对标 Config.get_provider_name().
     */
    public String getProviderName(String model, ModelPresetProperties preset) {
        ProviderMatchResult r = matchProvider(model, preset);
        return r != null ? r.specName() : null;
    }

    /**
     * 对标 Config.get_api_key().
     */
    public String getApiKey(String model, ModelPresetProperties preset) {
        ProviderProperties p = getProvider(model, preset);
        return p != null ? p.apiKey() : null;
    }

    /**
     * 对标 Config.get_api_base().
     */
    public String getApiBase(String model, ModelPresetProperties preset) {
        ProviderMatchResult r = matchProvider(model, preset);
        if (r == null) return null;
        ProviderProperties p = r.config();
        if (p != null && p.apiBase() != null) {
            return p.apiBase();
        }
        ProviderSpec spec = ProviderSpec.findByName(r.specName());
        return spec != null ? spec.defaultApiBase() : null;
    }

    // --- 内部匹配方法 ---

    private ProviderMatchResult matchByExplicitProvider(String providerName, String model) {
        ProviderSpec spec = ProviderSpec.findByName(providerName);
        if (spec == null) return null;
        ProviderProperties p = providers.getByName(spec.name());
        return p != null ? new ProviderMatchResult(p, spec.name()) : null;
    }

    private ProviderMatchResult matchByPrefix(String normalizedPrefix, String modelLower) {
        for (ProviderSpec spec : ProviderSpec.REGISTRY) {
            if (spec.isTranscriptionOnly()) continue;
            ProviderProperties p = providers.getByName(spec.name());
            if (p == null) continue;
            if (!normalizedPrefix.isEmpty() && normalizedPrefix.equals(spec.name())) {
                if (spec.isOauth() || spec.isLocal() || spec.isDirect() || p.apiKey() != null) {
                    return new ProviderMatchResult(p, spec.name());
                }
            }
        }
        return null;
    }

    private ProviderMatchResult matchByKeyword(String modelLower, String modelNormalized) {
        for (ProviderSpec spec : ProviderSpec.REGISTRY) {
            if (spec.isTranscriptionOnly()) continue;
            ProviderProperties p = providers.getByName(spec.name());
            if (p == null) continue;
            boolean matches = spec.keywords().stream().anyMatch(kw -> {
                String kl = kw.toLowerCase();
                return modelLower.contains(kl) || modelNormalized.contains(kl.replace("-", "_"));
            });
            if (matches && (spec.isOauth() || spec.isLocal() || spec.isDirect() || p.apiKey() != null)) {
                return new ProviderMatchResult(p, spec.name());
            }
        }
        return null;
    }

    private ProviderMatchResult matchLocalByBaseUrl() {
        ProviderMatchResult fallback = null;
        for (ProviderSpec spec : ProviderSpec.REGISTRY) {
            if (!spec.isLocal()) continue;
            ProviderProperties p = providers.getByName(spec.name());
            if (p == null || p.apiBase() == null) continue;
            if (spec.detectByBaseKeyword() != null
                && p.apiBase().contains(spec.detectByBaseKeyword())) {
                return new ProviderMatchResult(p, spec.name());
            }
            if (fallback == null) fallback = new ProviderMatchResult(p, spec.name());
        }
        return fallback;
    }

    private ProviderMatchResult matchByAnyAvailableKey() {
        for (ProviderSpec spec : ProviderSpec.REGISTRY) {
            if (spec.isOauth() || spec.isTranscriptionOnly()) continue;
            ProviderProperties p = providers.getByName(spec.name());
            if (p != null && p.apiKey() != null) {
                return new ProviderMatchResult(p, spec.name());
            }
        }
        return null;
    }
}

// 匹配结果
public record ProviderMatchResult(
    ProviderProperties config,
    String specName
) {}
```

### 3. AgentDefaults 子配置

```java
package com.nanobot.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.List;

public record AgentDefaultsProperties(
    @NotBlank
    String workspace,                    // 默认: "~/.nanobot/workspace"

    String modelPreset,                  // 默认: null

    String model,                        // 默认: "anthropic/claude-opus-4-5"
    String provider,                     // 默认: "auto"

    @Min(1)
    int maxTokens,                       // 默认: 8192

    @Min(1)
    int contextWindowTokens,             // 默认: 65536

    Integer contextBlockLimit,           // 默认: null

    double temperature,                  // 默认: 0.1

    List<@Valid FallbackCandidate> fallbackModels,  // 字符串或 InlineFallbackProperties

    @Min(1)
    int maxToolIterations,               // 默认: 200

    @Min(1)
    int maxConcurrentSubagents,          // 默认: 1

    @Min(1)
    int maxToolResultChars,              // 默认: 16000

    String providerRetryMode,            // 默认: "standard"

    @Min(20)
    @JsonAlias("toolHintMaxLength")
    int toolHintMaxLength,               // 默认: 40

    String reasoningEffort,              // nullable
    String timezone,                     // 默认: "UTC"

    @NotBlank
    String botName,                      // 默认: "nanobot"

    String botIcon,                      // 默认: "🐈"

    boolean unifiedSession,              // 默认: false

    List<String> disabledSkills,

    @Min(0)
    @JsonAlias({"idleCompactAfterMinutes", "sessionTtlMinutes"})
    int sessionTtlMinutes,               // 默认: 0

    @Min(0)
    int maxMessages,                     // 默认: 120

    double consolidationRatio,           // 默认: 0.5

    @NestedConfigurationProperty
    DreamProperties dream
) {
    public static final AgentDefaultsProperties DEFAULTS = new AgentDefaultsProperties(
        "~/.nanobot/workspace", null,
        "anthropic/claude-opus-4-5", "auto", 8192, 65536, null,
        0.1, List.of(), 200, 1, 16000, "standard", 40,
        null, "UTC", "nanobot", "🐈", false,
        List.of(), 0, 120, 0.5, DreamProperties.DEFAULTS
    );

    public GenerationSettings toGenerationSettings() {
        return new GenerationSettings(temperature, maxTokens, reasoningEffort);
    }
}
```

### 4. InlineFallback 与 FallbackCandidate

```java
package com.nanobot.config;

/**
 * 对标 Python InlineFallbackConfig.
 */
public record InlineFallbackProperties(
    String model,
    String provider,
    Integer maxTokens,
    Integer contextWindowTokens,
    Double temperature,
    String reasoningEffort
) {}

/**
 * FallbackCandidate = str | InlineFallbackProperties.
 * Jackson 反序列化时通过 @JsonTypeInfo 或自定义 deserializer 区分字符串和对象。
 */
```

### 5. Provider 配置系统

```java
// ProviderProperties.java — 对标 Python ProviderConfig
// 注意：只包含源码中实际存在的字段
public record ProviderProperties(
    String apiKey,
    String apiBase,
    String apiType,          // 默认: "auto" (仅 providers.openai 支持非 auto)
    Map<String, String> extraHeaders,
    Map<String, Object> extraBody,
    Map<String, String> extraQuery
) {
    public static final ProviderProperties DEFAULTS =
        new ProviderProperties(null, null, "auto", null, null, null);
}

// BedrockProviderProperties.java — 对标 Python BedrockProviderConfig
public record BedrockProviderProperties(
    String apiKey,
    String apiBase,
    String apiType,
    Map<String, String> extraHeaders,
    Map<String, Object> extraBody,
    Map<String, String> extraQuery,
    String region,           // fallback to AWS_REGION/AWS_DEFAULT_REGION/profile
    String profile
) {}

// ProvidersProperties.java — 对标 Python ProvidersConfig (37 个字段)
public record ProvidersProperties(
    ProviderProperties custom,
    ProviderProperties azureOpenai,
    BedrockProviderProperties bedrock,
    ProviderProperties anthropic,
    ProviderProperties openai,
    ProviderProperties openrouter,
    ProviderProperties assemblyai,
    ProviderProperties huggingface,
    ProviderProperties skywork,
    ProviderProperties deepseek,
    ProviderProperties groq,
    ProviderProperties zhipu,
    ProviderProperties dashscope,
    ProviderProperties vllm,
    ProviderProperties ollama,
    ProviderProperties lmStudio,
    ProviderProperties atomicChat,
    ProviderProperties ovms,
    ProviderProperties gemini,
    ProviderProperties moonshot,
    ProviderProperties minimax,
    ProviderProperties minimaxAnthropic,
    ProviderProperties mistral,
    ProviderProperties stepfun,
    ProviderProperties xiaomiMimo,
    ProviderProperties longcat,
    ProviderProperties antLing,
    ProviderProperties aihubmix,
    ProviderProperties siliconflow,
    ProviderProperties novita,
    ProviderProperties volcengine,
    ProviderProperties volcengineCodingPlan,
    ProviderProperties byteplus,
    ProviderProperties byteplusCodingPlan,
    ProviderProperties openaiCodex,      // exclude from serialization
    ProviderProperties githubCopilot,    // exclude from serialization
    ProviderProperties qianfan,
    ProviderProperties nvidia
) {
    public static final ProvidersProperties DEFAULTS = new ProvidersProperties(
        ProviderProperties.DEFAULTS, ProviderProperties.DEFAULTS,
        BedrockProviderProperties.DEFAULTS, ProviderProperties.DEFAULTS,
        ProviderProperties.DEFAULTS, ProviderProperties.DEFAULTS,
        ProviderProperties.DEFAULTS, ProviderProperties.DEFAULTS,
        ProviderProperties.DEFAULTS, ProviderProperties.DEFAULTS,
        ProviderProperties.DEFAULTS, ProviderProperties.DEFAULTS,
        ProviderProperties.DEFAULTS, ProviderProperties.DEFAULTS,
        ProviderProperties.DEFAULTS, ProviderProperties.DEFAULTS,
        ProviderProperties.DEFAULTS, ProviderProperties.DEFAULTS,
        ProviderProperties.DEFAULTS, ProviderProperties.DEFAULTS,
        ProviderProperties.DEFAULTS, ProviderProperties.DEFAULTS,
        ProviderProperties.DEFAULTS, ProviderProperties.DEFAULTS,
        ProviderProperties.DEFAULTS, ProviderProperties.DEFAULTS,
        ProviderProperties.DEFAULTS, ProviderProperties.DEFAULTS,
        ProviderProperties.DEFAULTS, ProviderProperties.DEFAULTS,
        ProviderProperties.DEFAULTS, ProviderProperties.DEFAULTS,
        ProviderProperties.DEFAULTS, ProviderProperties.DEFAULTS,
        ProviderProperties.DEFAULTS, ProviderProperties.DEFAULTS
    );

    public ProviderProperties getByName(String name) {
        return switch (name) {
            case "custom" -> custom;
            case "azure_openai", "azureOpenai" -> azureOpenai;
            case "bedrock" -> bedrock;
            case "anthropic" -> anthropic;
            case "openai" -> openai;
            case "openrouter" -> openrouter;
            case "assemblyai" -> assemblyai;
            case "huggingface" -> huggingface;
            case "skywork" -> skywork;
            case "deepseek" -> deepseek;
            case "groq" -> groq;
            case "zhipu" -> zhipu;
            case "dashscope" -> dashscope;
            case "vllm" -> vllm;
            case "ollama" -> ollama;
            case "lm_studio", "lmStudio" -> lmStudio;
            case "atomic_chat", "atomicChat" -> atomicChat;
            case "ovms" -> ovms;
            case "gemini" -> gemini;
            case "moonshot" -> moonshot;
            case "minimax" -> minimax;
            case "minimax_anthropic", "minimaxAnthropic" -> minimaxAnthropic;
            case "mistral" -> mistral;
            case "stepfun" -> stepfun;
            case "xiaomi_mimo", "xiaomiMimo" -> xiaomiMimo;
            case "longcat" -> longcat;
            case "ant_ling", "antLing" -> antLing;
            case "aihubmix" -> aihubmix;
            case "siliconflow" -> siliconflow;
            case "novita" -> novita;
            case "volcengine" -> volcengine;
            case "volcengine_coding_plan", "volcengineCodingPlan" -> volcengineCodingPlan;
            case "byteplus" -> byteplus;
            case "byteplus_coding_plan", "byteplusCodingPlan" -> byteplusCodingPlan;
            case "openai_codex", "openaiCodex" -> openaiCodex;
            case "github_copilot", "githubCopilot" -> githubCopilot;
            case "qianfan" -> qianfan;
            case "nvidia" -> nvidia;
            default -> null;
        };
    }
}
```

### 6. ModelPresetProperties

```java
package com.nanobot.config;

public record ModelPresetProperties(
    String label,              // ← 源码中存在，原文档缺失
    String model,
    String provider,
    int maxTokens,
    int contextWindowTokens,
    double temperature,
    String reasoningEffort
) {
    public static ModelPresetProperties fromDefaults(AgentDefaultsProperties d) {
        return new ModelPresetProperties(
            null, d.model(), d.provider(), d.maxTokens(),
            d.contextWindowTokens(), d.temperature(), d.reasoningEffort()
        );
    }

    public GenerationSettings toGenerationSettings() {
        return new GenerationSettings(temperature, maxTokens, reasoningEffort);
    }
}
```

### 7. ChannelsProperties

```java
package com.nanobot.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.HashMap;
import java.util.Map;

/**
 * 对标 Python ChannelsConfig (extra="allow").
 * Spring 中通过 Map<String, Object> 或 @JsonAnySetter 接收额外字段。
 */
public record ChannelsProperties(
    boolean sendProgress,
    boolean sendToolHints,
    boolean showReasoning,
    boolean extractDocumentText,

    @Min(0) @Max(10)
    int sendMaxRetries,

    // Deprecated fields
    String transcriptionProvider,
    @Pattern(regexp = "^[a-z]{2,3}$")
    String transcriptionLanguage,

    // 接收插件 channel 自定义配置 (extra="allow")
    @JsonAnySetter
    Map<String, Object> extras
) {
    public ChannelsProperties {
        if (extras == null) extras = new HashMap<>();
    }

    public static final ChannelsProperties DEFAULTS = new ChannelsProperties(
        true, false, true, true, 3,
        "groq", null, new HashMap<>()
    );
}
```

### 8. DreamProperties

```java
package com.nanobot.config;

import com.nanobot.cron.types.CronSchedule;

public record DreamProperties(
    boolean enabled,
    int intervalH,               // ge=1, 默认: 2
    String cron,                 // exclude from serialization
    @JsonAlias({"modelOverride", "model", "model_override"})
    String modelOverride,
    int maxBatchSize,            // Deprecated
    int maxIterations,           // Deprecated
    boolean annotateLineAges     // Deprecated
) {
    public static final DreamProperties DEFAULTS =
        new DreamProperties(true, 2, null, null, 20, 15, true);

    private static final int HOUR_MS = 3_600_000;

    public CronSchedule buildSchedule(String timezone) {
        if (cron != null && !cron.isEmpty()) {
            return new CronSchedule("cron", cron, timezone);
        }
        return new CronSchedule("every", intervalH * HOUR_MS, timezone);
    }

    public String describeSchedule() {
        if (cron != null && !cron.isEmpty()) {
            return "cron " + cron + " (legacy)";
        }
        return "every " + intervalH + "h";
    }
}
```

### 9. MCPServerProperties

```java
package com.nanobot.config;

import java.util.List;
import java.util.Map;

public record McpServerProperties(
    String type,                   // "stdio" | "sse" | "streamableHttp" | null
    String command,
    List<String> args,
    Map<String, String> env,
    String cwd,
    String url,
    Map<String, String> headers,
    int toolTimeout,               // 默认: 30
    List<String> enabledTools      // 默认: ["*"]
) {
    public McpServerProperties {
        if (args == null) args = List.of();
        if (env == null) env = Map.of();
        if (headers == null) headers = Map.of();
        if (enabledTools == null) enabledTools = List.of("*");
    }
}
```

### 10. 配置加载流程 (`ConfigLoader.java`)

Spring Boot 自动处理 application.yml + env vars → `@ConfigurationProperties` 绑定。
但需补充 Python `loader.py` 中的以下功能：

```java
package com.nanobot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

@Component
public class ConfigLoader {

    private static final Pattern ENV_REF_PATTERN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private final ObjectMapper objectMapper;
    private final NanobotProperties properties;

    private static Path currentConfigPath;

    public ConfigLoader(ObjectMapper objectMapper, NanobotProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public static void setConfigPath(Path path) {
        currentConfigPath = path;
    }

    public static Path getConfigPath() {
        return currentConfigPath != null
            ? currentConfigPath
            : Path.of(System.getProperty("user.home"), ".nanobot", "config.json");
    }

    /**
     * 对标 Python load_config(config_path).
     * Spring Boot 启动时已自动绑定 NanobotProperties，
     * 此方法用于从指定路径加载并覆盖。
     */
    public NanobotProperties loadConfig(Path configPath) throws IOException {
        Path path = configPath != null ? configPath : getConfigPath();
        if (!Files.exists(path)) {
            return properties;
        }
        Map<String, Object> data = objectMapper.readValue(path.toFile(), Map.class);
        data = migrateConfig(data);
        // 合并到现有 properties 并重新绑定
        // 实际实现可用 Binder 或重建 record
        throw new UnsupportedOperationException("TBD: runtime reload via Binder");
    }

    /**
     * 对标 Python save_config(config, config_path).
     * 注意：record 不可变，需先序列化再写入。
     */
    public void saveConfig(NanobotProperties config, Path configPath) throws IOException {
        Path path = configPath != null ? configPath : getConfigPath();
        Files.createDirectories(path.getParent());
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT)
            .writeValue(path.toFile(), config);
    }

    /**
     * 对标 Python resolve_config_env_vars(config).
     * Spring Boot 不会自动解析字符串值内的 ${VAR}，需手动处理。
     */
    public static void resolveEnvVarsInPlace(Map<String, Object> data) {
        resolveMapInPlace(data);
    }

    @SuppressWarnings("unchecked")
    private static void resolveMapInPlace(Map<String, Object> map) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s) {
                String resolved = resolveString(s);
                if (resolved != s) e.setValue(resolved);
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
                String resolved = resolveString(s);
                if (resolved != s) list.set(i, resolved);
            } else if (v instanceof Map) {
                resolveMapInPlace((Map<String, Object>) v);
            } else if (v instanceof java.util.List) {
                resolveListInPlace((java.util.List<Object>) v);
            }
        }
    }

    private static String resolveString(String value) {
        return ENV_REF_PATTERN.matcher(value)
            .replaceAll((MatchResult m) -> {
                String name = m.group(1);
                String env = System.getenv(name);
                if (env == null) {
                    throw new IllegalArgumentException(
                        "Environment variable '" + name + "' referenced in config is not set");
                }
                return env;
            });
    }

    /**
     * 对标 Python _migrate_config(data).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> migrateConfig(Map<String, Object> data) {
        Map<String, Object> tools = (Map<String, Object>) data.getOrDefault("tools", new java.util.HashMap<>());
        Map<String, Object> execCfg = (Map<String, Object>) tools.getOrDefault("exec", new java.util.HashMap<>());

        if (execCfg.containsKey("restrictToWorkspace")
            && !tools.containsKey("restrictToWorkspace")) {
            tools.put("restrictToWorkspace", execCfg.remove("restrictToWorkspace"));
        }

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
        return data;
    }
}
```

### 11. `AppPaths.java` — 对标 `paths.py`

Python `paths.py` 中大多数函数是**不依赖 Config 的独立工具函数**（仅 `get_config_path()` 通过懒导入委托 loader）。Java 中保持相同设计：独立工具类，不依赖 `NanobotProperties`。

```java
package com.nanobot.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class AppPaths {

    private AppPaths() {}

    /** 委托 ConfigLoader.getConfigPath() */
    public static Path getConfigPath() {
        return ConfigLoader.getConfigPath();
    }

    /** config_path 的父目录 */
    public static Path getDataDir() {
        return ensureDir(getConfigPath().getParent());
    }

    /** data_dir / name */
    public static Path getRuntimeSubdir(String name) {
        return ensureDir(getDataDir().resolve(name));
    }

    /** media 目录，可选 channel 子目录 */
    public static Path getMediaDir(String channel) {
        Path base = getRuntimeSubdir("media");
        return channel != null ? ensureDir(base.resolve(channel)) : base;
    }

    public static Path getCronDir() {
        return getRuntimeSubdir("cron");
    }

    public static Path getLogsDir() {
        return getRuntimeSubdir("logs");
    }

    public static Path getWebuiDir() {
        return getRuntimeSubdir("webui");
    }

    /** 解析并确保 workspace 路径 */
    public static Path getWorkspacePath(String workspace) {
        Path path = workspace != null
            ? Paths.get(workspace.replaceFirst("^~", System.getProperty("user.home")))
            : Paths.get(System.getProperty("user.home"), ".nanobot", "workspace");
        return ensureDir(path);
    }

    /** 判断是否默认 workspace */
    public static boolean isDefaultWorkspace(String workspace) {
        Path current = getWorkspacePath(workspace);
        Path defaultWs = getWorkspacePath(null);
        try {
            return current.toRealPath().equals(defaultWs.toRealPath());
        } catch (Exception e) {
            return current.toAbsolutePath().normalize()
                .equals(defaultWs.toAbsolutePath().normalize());
        }
    }

    public static Path getCliHistoryPath() {
        return Paths.get(System.getProperty("user.home"), ".nanobot", "history", "cli_history");
    }

    public static Path getBridgeInstallDir() {
        return Paths.get(System.getProperty("user.home"), ".nanobot", "bridge");
    }

    public static Path getLegacySessionsDir() {
        return Paths.get(System.getProperty("user.home"), ".nanobot", "sessions");
    }

    private static Path ensureDir(Path path) {
        try {
            Files.createDirectories(path);
        } catch (Exception ignored) {}
        return path;
    }
}
```

## 关键设计决策

### Pydantic `model_validator` → Bean Validation + @PostConstruct

```java
@Component
public class ConfigValidator {

    private final NanobotProperties properties;

    public ConfigValidator(NanobotProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void validate() {
        // 对标 Config._validate_model_preset()
        if (properties.modelPresets().containsKey("default")) {
            throw new IllegalStateException("model_preset name 'default' is reserved for agents.defaults");
        }
        String presetName = properties.agents().defaults().modelPreset();
        if (presetName != null && !"default".equals(presetName)
            && !properties.modelPresets().containsKey(presetName)) {
            throw new IllegalStateException("model_preset '" + presetName + "' not found in model_presets");
        }
        for (Object fallback : properties.agents().defaults().fallbackModels()) {
            if (fallback instanceof String s && !properties.modelPresets().containsKey(s)) {
                throw new IllegalStateException("fallback_models entry '" + s + "' not found in model_presets");
            }
        }

        // 对标 ProvidersConfig._validate_api_type_scope()
        ProvidersProperties providers = properties.providers();
        // 仅 openai 允许 api_type != "auto"
        for (String name : ALL_PROVIDER_NAMES) {
            if ("openai".equals(name)) continue;
            ProviderProperties p = providers.getByName(name);
            if (p != null && p.apiType() != null && !"auto".equals(p.apiType())) {
                throw new IllegalStateException(
                    "providers." + name + ".api_type is only supported for providers.openai");
            }
        }
    }
}
```

### 循环引用处理

Python 用 `_lazy_default()` + `_resolve_tool_config_refs()` + `model_rebuild()` 解决 `schema.py` 和 tool 模块间的循环引用。

Java 中 `@ConfigurationProperties` record 是纯数据，但仍需处理 `ToolsProperties` 中 `WebToolsConfig`、`ExecToolConfig` 等类型的循环依赖：
- 方案 A：用 `@Lazy` 延迟注入工具子配置 bean
- 方案 B：将工具子配置拆为独立的 `@ConfigurationProperties`，由各自模块定义
- 方案 C：用 `Map<String, Object>` 占位，运行时转换为具体类型

推荐方案 B：每个工具模块定义自己的 `@ConfigurationProperties`，通过 `nanobot.tools.*` prefix 绑定。

### camelCase / snake_case 兼容

Python `Base` 通过 `alias_generator=to_camel` + `populate_by_name=True` 同时接受两种键名。

Java 中：
- Spring Boot Relaxed Binding 自动将 `NANOBOT_AGENTS_DEFAULTS_MODEL` → `agents.defaults.model`
- JSON 反序列化中，用 `@JsonAlias` 标注 snake_case 别名（如 `@JsonAlias("modelPresets")`）
- `populate_by_name=True` 的等价物：Jackson 默认允许按字段名绑定，配合 `@JsonAlias` 即可

### `${VAR}` 环境变量引用解析

Spring Boot 的 Relaxed Binding 处理**属性名**到环境变量的映射，但不处理**属性值**内部的 `${VAR}` 字符串替换。

Python `resolve_config_env_vars()` 在加载配置后递归扫描所有字符串值，替换 `${VAR}`。Java 中需在 `ConfigLoader.loadConfig()` 中，在 JSON → Map 阶段后调用 `resolveEnvVarsInPlace(data)`，然后再绑定到 record。

## 验证标准

```bash
# 1. 启动加载默认配置
java -jar nanobot-java.jar
# 预期: agents.defaults.model = anthropic/claude-opus-4-5

# 2. 环境变量覆盖
NANOBOT_AGENTS_DEFAULTS_MODEL=openai/gpt-5 java -jar nanobot-java.jar
# 预期: model = openai/gpt-5

# 3. 配置值内 ${VAR} 解析
# config.json 中: { "providers": { "anthropic": { "apiKey": "${ANTHROPIC_KEY}" } } }
# 预期: 启动时 apiKey 被替换为环境变量值；未设置则抛异常

# 4. provider match 测试 (JUnit)
# 验证 matchProvider("anthropic/claude-xxx") 返回 anthropic
# 验证 matchProvider("deepseek-chat") 返回 deepseek
# 验证 forced provider "ollama" 返回 ollama
# 验证 getApiBase fallback 到 spec.defaultApiBase

# 5. preset 校验
# 设置 agents.defaults.modelPreset = "nonexistent"
# 预期: 启动抛 IllegalStateException

# 6. api_type 校验
# 设置 providers.deepseek.apiType = "chat_completions"
# 预期: 启动抛 IllegalStateException (仅 openai 允许)

# 7. AppPaths 独立测试
assert AppPaths.getWorkspacePath(null).endsWith(".nanobot/workspace");
assert AppPaths.isDefaultWorkspace("~/.nanobot/workspace");
assert AppPaths.getMediaDir("slack").endsWith("media/slack");
```

## 代码量估算

| 文件 | 估算行数 |
|------|---------|
| NanobotProperties.java (根) | ~280 |
| AgentsProperties.java | ~20 |
| AgentDefaultsProperties.java | ~100 |
| ChannelsProperties.java | ~40 |
| TranscriptionProperties.java | ~25 |
| ProvidersProperties.java | ~150 |
| ProviderProperties.java | ~20 |
| BedrockProviderProperties.java | ~15 |
| ApiProperties.java | ~20 |
| GatewayProperties.java | ~20 |
| HeartbeatProperties.java | ~20 |
| ToolsProperties.java | ~50 |
| McpServerProperties.java | ~25 |
| ModelPresetProperties.java | ~30 |
| InlineFallbackProperties.java | ~15 |
| DreamProperties.java | ~40 |
| AppPaths.java | ~70 |
| ConfigLoader.java | ~120 |
| ConfigValidator.java | ~50 |
| **合计** | **~1130** |
