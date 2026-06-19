# P0 — 项目骨架 + Config

## 复刻目标

对标 Python 的 `nanobot/config/schema.py`（536 行）+ 项目入口。配置加载、env 绑定、provider 匹配逻辑用 Spring Boot + Java Record 重写。

## Python 源码对照

**源文件**: `nanobot/config/schema.py` (536 行)

**核心类型**（按层级）:

```
Config (BaseSettings, env_prefix="NANOBOT_", env_nested_delimiter="__")
├── agents: AgentsConfig
│   └── defaults: AgentDefaults
│       ├── workspace, model, provider, max_tokens, context_window_tokens
│       ├── temperature, fallback_models, max_tool_iterations
│       ├── provider_retry_mode, reasoning_effort, timezone
│       ├── bot_name, bot_icon, unified_session
│       ├── disabled_skills, session_ttl_minutes, max_messages
│       ├── consolidation_ratio
│       └── dream: DreamConfig
├── channels: ChannelsConfig
│   ├── send_progress, send_tool_hints, show_reasoning
│   ├── extract_document_text, send_max_retries
│   └── extra="allow" (channel 自定义字段)
├── transcription: TranscriptionConfig
├── providers: ProvidersConfig  ← 30+ 个 ProviderConfig 字段
│   ├── anthropic, openai, openrouter, deepseek, ...
│   └── api_key, api_base, api_type, extra_headers, extra_body, extra_query
├── api: ApiConfig (host, port, timeout)
├── gateway: GatewayConfig (host, port, heartbeat)
├── tools: ToolsConfig
│   ├── web, exec, cli_apps, my, image_generation
│   ├── restrict_to_workspace, mcp_servers, ssrf_whitelist
│   └── webui_allow_local_service_access
└── model_presets: dict[str, ModelPresetConfig]
```

**关键方法**（Config 上）:
- `_match_provider(model, preset)` → 按 registry 优先级 match
- `get_provider(model, preset)` → 返回 ProviderConfig
- `get_api_key(model, preset)` → 返回 API key
- `resolve_preset(name)` → 返回 ModelPresetConfig

**Pydantic 特性使用**:
- `BaseSettings` + `env_prefix="NANOBOT_"` + `env_nested_delimiter="__"`
- `AliasChoices` 用于兼容多个 JSON key 名
- `ConfigDict(alias_generator=to_camel)` — camelCase JSON 兼容
- `model_validator(mode="after")` — 交叉字段校验
- `Field(default_factory=_lazy_default(...))` — 延迟导入避免循环引用
- `model_rebuild()` — 解决前向引用

## Java 约定说明

本方案中出现的 `@Default` 是自定义编译时注解，用于为 Java record 组件的 `null` 值提供默认值。Spring Boot 在 `@ConfigurationProperties` 绑定时会自动处理默认值，但纯 record 的 compact constructor 也需要相同语义。实际实现时可以三选一：

- **方案 A**：用 Lombok `@Builder` + `@Builder.Default`（推荐，最简单）
- **方案 B**：在 compact constructor 中手动处理 `if (x == null) x = defaultValue`
- **方案 C**：用 `Optional.ofNullable(x).orElse(defaultValue)` 模式

文档中为简洁统一用 `@Default` 占位，实现时替换为选定的方案。

同样，`@Nullable` 来自 `jakarta.annotation.Nullable`（Spring Boot 内置）。

## Java 实现方案

### 1. 依赖

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-configuration-processor</artifactId>
    <optional>true</optional>
</dependency>
<dependency>
    <groupId>jakarta.validation</groupId>
    <artifactId>jakarta.validation-api</artifactId>
</dependency>
```

### 2. 入口类

```java
// NanobotApplication.java
@SpringBootApplication
public class NanobotApplication {
    public static void main(String[] args) {
        SpringApplication.run(NanobotApplication.class, args);
    }
}
```

### 3. 配置映射（对标 Pydantic BaseSettings）

Spring Boot `@ConfigurationProperties` 天然对标 Pydantic：

```java
// NanobotProperties.java — 对标 Config(BaseSettings)
@ConfigurationProperties(prefix = "nanobot")
@Validated
public record NanobotProperties(
    @Default AgentProperties agents,
    @Default ChannelsProperties channels,
    @Default TranscriptionProperties transcription,
    @Default ProvidersProperties providers,
    @Default ApiProperties api,
    @Default GatewayProperties gateway,
    @Default ToolsProperties tools,
    Map<String, ModelPresetProperties> modelPresets
) {
    // env 变量绑定: NANOBOT_AGENTS_DEFAULTS_MODEL=xxx
    //   → nanobot.agents.defaults.model (Spring Boot 自动处理)
    //   对标 Python 的 env_prefix="NANOBOT_", env_nested_delimiter="__"

    // 对标 resolve_preset()
    public ModelPresetProperties resolvePreset(@Nullable String name) { ... }

    // 对标 _match_provider() — 核心 provider 匹配逻辑
    public ProviderMatch matchProvider(@Nullable String model, ...) { ... }
}
```

### 4. ProviderMatch 记录

Provider 匹配结果需要在配置和 registry 之间建立联系：

```java
// ProviderMatch.java
public record ProviderMatch(ProviderConfig config, ProviderSpec spec) {}
```

### 5. Provider 匹配逻辑（对齐 `_match_provider()`）

Python `_match_provider()` 的匹配顺序：
1. 显式 provider（`provider != "auto"`）
2. 模型名 prefix 匹配（如 `anthropic/claude-xxx` → Anthropic）
3. 关键词匹配（如 `"claude" in model`）
4. 本地 provider base URL 匹配（如 `"11434" in api_base` → Ollama）
5. Fallback：有 API key 的网关优先、有其他 key 的其次

Java 实现保持相同优先级：

```java
// ProviderMatcher.java
public class ProviderMatcher {
    /**
     * Match provider by model name, following the same priority chain
     * as Python _match_provider().
     */
    public Optional<ProviderMatch> match(String model, ModelPresetProperties preset) {
        var resolved = preset != null ? preset : properties.resolvePreset(null);
        String forced = resolved.provider();
        if (!"auto".equals(forced)) {
            return matchByName(forced);
        }
        // prefix match → keyword match → local fallback → gateway fallback
        return matchByPrefix(model, resolved)
            .or(() -> matchByKeyword(model, resolved))
            .or(() -> matchLocalByBaseUrl(model))
            .or(() -> matchAnyWithKey());
    }
}
```

### 6. 环境变量绑定

Spring Boot 自动处理 `NANOBOT_` 前缀 + 嵌套：

```bash
# Python: NANOBOT_PROVIDERS__ANTHROPIC__API_KEY=sk-ant-xxx
# Java:   NANOBOT_PROVIDERS_ANTHROPIC_API_KEY=sk-ant-xxx
#         → nanobot.providers.anthropic.api-key (Spring Boot Relaxed Binding)
```

### 7. 配置加载流程

```yaml
# application.yml — 默认值
nanobot:
  agents:
    defaults:
      workspace: ~/.nanobot-java/workspace
      model: anthropic/claude-opus-4-5
      provider: auto
      max-tokens: 8192
      context-window-tokens: 65536
      temperature: 0.1
      max-tool-iterations: 200
      provider-retry-mode: standard
      bot-name: nanobot-java
      bot-icon: "🐈"
      timezone: UTC
      session-ttl-minutes: 0
      max-messages: 120
      consolidation-ratio: 0.5
  channels:
    send-progress: true
    send-tool-hints: false
    show-reasoning: true
    extract-document-text: true
    send-max-retries: 3
  api:
    host: 127.0.0.1
    port: 8900
    timeout: 120.0
  gateway:
    host: 127.0.0.1
    port: 18790
```

### 8. Pydantic model_validator → Bean Validation

```java
// 对标 @model_validator(mode="after")
// 使用 @PostConstruct 或自定义 Validator
@Component
public class NanobotConfigValidator {
    private final NanobotProperties props;

    @PostConstruct
    void validate() {
        if (props.modelPresets().containsKey("default")) {
            throw new IllegalStateException(
                "model_preset name 'default' is reserved");
        }
        // 对标 _validate_model_preset
    }
}
```

### 9. 循环引用问题消除

Python 用 `_lazy_default()` + `model_rebuild()` 解决 schema.py 和 tool 模块间的循环导入。Java 中 `@ConfigurationProperties` record 是纯数据，不存在循环引用问题——tool 默认值在 tool Spring Bean 初始化时注入，而非配置类加载时。

## 测试对齐

对标 `tests/test_config.py`（假设存在），需要覆盖：

| Python 测试场景 | Java 测试 |
|----------------|----------|
| 默认值正确 | `@SpringBootTest` 加载空配置，assert 默认值 |
| 环境变量覆盖 | `application-test.yml` + `@TestPropertySource` |
| camelCase JSON 解析 | `@JsonTest` + `ObjectMapper` + Jackson `PropertyNamingStrategies.LOWER_CAMEL_CASE` |
| model_preset 校验 | `assertThrows(IllegalStateException.class, ...)` |
| provider match 优先级 | 构造多种 ProviderSpec 组合，mock providers config，assert match 结果 |
| fallback chain 构建 | 多 fallback 配置 → 验证 FallbackProvider 包装顺序 |

## 验证标准

```bash
cd nanobot-java
mvn spring-boot:run
# 预期输出: Started NanobotApplication in X.XXX seconds
# 日志中包含: "Loaded config: ..."
# env 覆盖测试: NANOBOT_AGENTS_DEFAULTS_MODEL=openai/gpt-5 mvn spring-boot:run
# 预期: agent.defaults.model = "openai/gpt-5"
```

## 代码量估算

- NanobotProperties + 子 record: ~300 行
- ProviderMatcher: ~120 行
- 配置校验: ~50 行
- application.yml 默认值: ~80 行
- 测试: ~200 行
- **合计: ~750 行**
