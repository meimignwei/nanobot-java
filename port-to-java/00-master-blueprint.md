# nanobot → Java 100% 复刻：总蓝图

> **最高规则：一切以 nanobot Python 源码为准。**
>
> 当本文档与 `nanobot/` 目录下的 Python 源码有出入时，**源码即真相**。

## 源码路径

| 角色 | 路径 |
|------|------|
| Python 源码仓库根目录（权威） | `/Users/mmw/PycharmProjects/nanobot/` |
| Python 包目录 | `/Users/mmw/PycharmProjects/nanobot/nanobot/` |
| Java 工程根目录 | `/Users/mmw/IdeaProjects/nanobot-java/` |
| Java 源码目录 | `port-to-java/` → 最终为 `src/main/java/com/nanobot/` |
| Python 总行数 | **63,569 行** (165 个 .py 文件) |

## 1. 目标

将 nanobot Python 代码库 **100% 复刻**为 Java 21 + Spring Boot 3.2+ 实现。Java 包结构与 Python `nanobot/` 目录树严格一一对应，前端直接复用现有 React SPA（零改动）。

## 2. 技术栈

| 关注点 | Python 原版 | Java 复刻 | 理由 |
|--------|------------|-----------|------|
| 运行时基础设施 | asyncio | Virtual Threads (JEP 444) | 仅用于同步并发场景（如消息循环），不替代 async def 语义 |
| HTTP/WS | aiohttp + websockets | Spring Boot 3.2+ (Tomcat) | 虚拟线程一行启用，WebSocket 成熟 |
| 配置 | Pydantic BaseSettings | @ConfigurationProperties + Java Record | 编译时类型安全 |
| JSON | 内置 json + json_repair | Jackson ObjectMapper | 行业标准 |
| 插件发现 | pkgutil.iter_modules + entry_points | Spring @Component scan + ServiceLoader | 编译时 + 运行时 |
| LLM HTTP | httpx / aiohttp | java.net.http.HttpClient (异步 API) | JDK 内置，sendAsync() 返回 CompletableFuture |
| 测试 | pytest + pytest-asyncio | JUnit 5 + AssertJ | 标准选择 |
| 构建 | pip/setuptools | Maven (pom.xml) | 依赖管理清晰 |

## 3. Java 编码规范

### 3.1 类型声明

**禁止使用 `var`。** 所有局部变量必须显式声明类型：

```java
// ✅ 正确
List<LLMMessage> messages = new ArrayList<>();
String content = response.content();
for (ToolCallRequest tc : toolCalls) { ... }

// ❌ 禁止
var messages = new ArrayList<>();
var content = response.content();
```

### 3.2 命名约定

| Python | Java | 示例 |
|--------|------|------|
| `snake_case` 文件 | `PascalCase` 类 | `agent_loop.py` → `AgentLoop.java` |
| `snake_case` 方法 | `camelCase` 方法 | `_handle_message()` → `handleMessage()` |
| `snake_case` 目录（无下划线） | 同名 package | `channels/` → `channels/` |
| `snake_case` 目录（有下划线） | 去下划线 package | `openai_responses/` → `openairesponses/` |

### 3.3 Python → Java 类型映射

| Python | Java |
|--------|------|
| `str` | `String` |
| `int` | `int` / `long` |
| `float` | `double` |
| `bool` | `boolean` |
| `dict[str, Any]` | `Map<String, Object>` |
| `list[T]` | `List<T>` |
| `Optional[T]` | `@Nullable T` (jakarta.annotation.Nullable) |
| `dataclass` | Java `record` 或 Lombok `@Data` |
| `ABC` | `abstract class` |
| `Protocol` | `interface` |
| `Enum` | Java `enum` |
| `asyncio.Queue` | `LinkedBlockingQueue` |
| `contextvars.ContextVar` | `ThreadLocal<T>`（虚拟线程天然隔离） |
| `async def` | `CompletableFuture<T>` |
| `await` | `.thenCompose()` / `.join()` / `.get()` |

### 3.4 并发模型

**最高原则**：源码中的 `async def` 必须映射为 `CompletableFuture<T>`，`await` 映射为 CompletableFuture 链式操作。Virtual Threads 仅用于源码中**本就同步**的并发场景（如长时间运行的阻塞循环），**绝不用于将异步方法改为同步风格**。

| Python | Java | 说明 |
|--------|------|------|
| `async def` | `CompletableFuture<T>` | 100% 复刻源码异步语义 |
| `await` | `.thenCompose()` / `.join()` | CompletableFuture 链式组合或阻塞等待 |
| `asyncio.Queue` | `LinkedBlockingQueue` | 在 Virtual Thread 中调用 `take()` 或使用 `supplyAsync(() -> queue.take())` |
| `asyncio.wait_for(task, timeout=N)` | `.orTimeout(timeout, unit)` | CF 原生超时 |
| `asyncio.CancelledError` | `CancellationException` | CF 取消时抛出 |

```java
// Python: async def chat(...) -> LLMResponse
// Java:   public CompletableFuture<LLMResponse> chat(...) { ... }

// Python: response = await provider.chat(...)
// Java:   LLMResponse response = provider.chat(...).join();

// Python: asyncio.Queue + await queue.get()
// Java:   LinkedBlockingQueue + queue.take()  (在 Virtual Thread 中运行)

// Python: asyncio.wait_for(task, timeout=N)
// Java:   task.orTimeout(timeout, TimeUnit.SECONDS)

// Python: asyncio.CancelledError
// Java:   CancellationException (CompletableFuture.cancel(true))
```

Spring Boot 启用虚拟线程：

```yaml
# application.yml
spring:
  threads:
    virtual:
      enabled: true
```

### 3.5 依赖注入

Python 使用构造函数手动注入（`__init__` 接受所有依赖）。Java 使用 Spring `@Component` + 构造函数注入（Lombok `@RequiredArgsConstructor`）：

```java
@Component
@RequiredArgsConstructor
public class AgentLoop {
    private final MessageBus bus;
    private final SessionManager sessionManager;
    private final LLMProvider provider;
    // ...
}
```

## 4. 1:1 包结构映射

Java 工程根目录为 `nanobot-java/`（与 Python 的 `nanobot/` 同名但不同语言）。基础包名为 `com.nanobot`。

### 完整包树

```
nanobot/                              nanobot-java/src/main/java/com/nanobot/
│
├── nanobot.py                 →      Nanobot.java              (主应用编排类)
├── __main__.py                →      NanobotApplication.java    (Spring Boot 入口)
│
├── agent/                     →      agent/
│   ├── loop.py (1779行)       →      AgentLoop.java
│   ├── runner.py (1543行)      →      AgentRunner.java
│   ├── subagent.py (394行)     →      SubagentManager.java
│   ├── context.py (280行)      →      ContextBuilder.java
│   ├── memory.py (1015行)      →      MemoryStore.java, Consolidator.java
│   ├── hook.py (106行)         →      AgentHook.java, CompositeHook.java
│   ├── progress_hook.py        →      AgentProgressHook.java
│   ├── autocompact.py          →      AutoCompact.java
│   ├── skills.py               →      SkillsLoader.java
│   ├── model_presets.py        →      ModelPresets.java
│   │
│   └── tools/                  →      tools/
│       ├── base.py (297行)     →      Tool.java, Schema.java
│       ├── schema.py           →      StringSchema.java, IntegerSchema.java, ...
│       ├── registry.py (183行) →      ToolRegistry.java
│       ├── loader.py (117行)   →      ToolLoader.java
│       ├── context.py          →      ToolContext.java, RequestContext.java
│       ├── path_utils.py       →      ToolPathUtils.java
│       ├── sandbox.py          →      SandboxWrapper.java
│       ├── file_state.py       →      FileStateStore.java
│       ├── shell.py (677行)    →      ExecTool.java, ExecToolConfig.java
│       ├── filesystem.py (1022)→      ReadFileTool.java, WriteFileTool.java,
│       │                       →      EditFileTool.java, ListDirTool.java
│       ├── search.py           →      FindFilesTool.java, GrepTool.java
│       ├── web.py (987行)      →      WebSearchTool.java, WebFetchTool.java
│       ├── mcp.py (1122行)     →      McpTool.java, McpServerManager.java
│       ├── message.py          →      MessageTool.java
│       ├── self.py             →      MyTool.java
│       ├── spawn.py            →      SpawnSubagentTool.java
│       ├── long_task.py        →      LongTaskTool.java, CompleteGoalTool.java
│       ├── cron.py             →      ScheduleCronTool.java
│       ├── apply_patch.py      →      ApplyPatchTool.java
│       ├── image_generation.py →      ImageGenerationTool.java
│       ├── cli_apps.py         →      CliAppsTool.java
│       ├── exec_session.py     →      ExecSessionManager.java
│       └── runtime_state.py    →      RuntimeStateHelper.java
│
├── api/                       →      api/
│   └── server.py               →      OpenAiApiServer.java
│
├── apps/                       →      apps/
│   ├── protocol.py             →      AppProtocol.java
│   └── cli/
│       ├── service.py          →      CliAppService.java
│       └── utils.py            →      CliAppUtils.java
│
├── audio/                      →      audio/
│   ├── transcription.py        →      TranscriptionResult.java
│   └── transcription_registry.py→     TranscriptionRegistry.java
│
├── bus/                        →      bus/
│   ├── queue.py (44行)         →      MessageBus.java
│   ├── events.py (53行)        →      InboundMessage.java, OutboundMessage.java
│   ├── progress.py             →      ProgressCallback.java
│   └── runtime_events.py       →      RuntimeEventBus.java, RuntimeEventPublisher.java
│
├── channels/                   →      channels/
│   ├── base.py (257行)         →      BaseChannel.java
│   ├── manager.py              →      ChannelManager.java
│   ├── registry.py             →      ChannelRegistry.java
│   ├── websocket.py (1178行)   →      WebSocketChannel.java
│   ├── telegram.py (1472行)    →      TelegramChannel.java
│   ├── discord.py              →      DiscordChannel.java
│   ├── slack.py                →      SlackChannel.java
│   ├── weixin.py               →      WeixinChannel.java
│   ├── feishu.py               →      FeishuChannel.java
│   ├── dingtalk.py             →      DingTalkChannel.java
│   ├── whatsapp.py             →      WhatsAppChannel.java
│   ├── signal.py               →      SignalChannel.java
│   ├── matrix.py               →      MatrixChannel.java
│   ├── msteams.py              →      MsTeamsChannel.java
│   ├── email.py                →      EmailChannel.java
│   ├── qq.py                   →      QQChannel.java
│   ├── napcat.py               →      NapCatChannel.java
│   ├── mochat.py               →      MoChatChannel.java
│   └── wecom.py                →      WeComChannel.java
│
├── cli/                        →      cli/
│   ├── commands.py (2043行)    →      CliCommands.java
│   ├── models.py               →      CliModels.java
│   ├── onboard.py              →      OnboardWizard.java
│   └── stream.py               →      StreamRenderer.java
│
├── command/                    →      command/
│   ├── router.py               →      CommandRouter.java
│   └── builtin.py              →      BuiltinCommands.java
│
├── config/                     →      config/
│   ├── schema.py (536行)       →      NanobotProperties.java
│   ├── loader.py               →      ConfigLoader.java
│   └── paths.py                →      AppPaths.java
│
├── cron/                       →      cron/
│   ├── service.py              →      CronService.java
│   └── types.py                →      CronJob.java, CronSchedule.java, CronPayload.java
│
├── pairing/                    →      pairing/
│   └── store.py                →      PairingStore.java
│
├── providers/                  →      providers/
│   ├── base.py (935行)         →      LLMProvider.java, LLMResponse.java,
│   │                           →      ToolCallRequest.java, GenerationSettings.java
│   ├── registry.py             →      ProviderSpec.java, ProviderRegistry.java
│   ├── factory.py              →      ProviderFactory.java, ProviderSnapshot.java
│   ├── anthropic_provider.py   →      AnthropicProvider.java
│   ├── openai_compat_provider.py→     OpenAiCompatProvider.java
│   ├── azure_openai_provider.py→      AzureOpenAiProvider.java
│   ├── bedrock_provider.py     →      BedrockProvider.java
│   ├── fallback_provider.py    →      FallbackProvider.java
│   ├── github_copilot_provider.py→    GitHubCopilotProvider.java
│   ├── openai_codex_provider.py→      OpenAiCodexProvider.java
│   ├── image_generation.py     →      ImageGenerationProvider.java
│   ├── transcription.py        →      TranscriptionProvider.java
│   └── openai_responses/       →      providers/openairesponses/
│       ├── converters.py       →      ResponseConverters.java
│       └── parsing.py          →      ResponseParsing.java
│
├── security/                   →      security/
│   ├── workspace_access.py     →      WorkspaceScope.java, WorkspaceScopeResolver.java
│   ├── workspace_policy.py     →      WorkspacePolicy.java
│   └── network.py              →      NetworkSecurity.java
│
├── session/                    →      session/
│   ├── manager.py (817行)      →      Session.java, SessionManager.java
│   ├── goal_state.py           →      GoalState.java
│   ├── turn_continuation.py    →      TurnContinuation.java
│   └── webui_turns.py          →      WebUITurns.java
│
├── skills/                     →      src/main/resources/skills/  (模板文件)
│   ├── skill-creator/
│   ├── cron/
│   ├── github/
│   ├── image-generation/
│   ├── long-goal/
│   ├── memory/
│   ├── my/
│   ├── summarize/
│   ├── tmux/
│   ├── weather/
│   ├── update-setup/
│   └── clawhub/
│
├── templates/                  →      src/main/resources/templates/  (模板文件)
│
├── utils/                      →      utils/
│   ├── helpers.py              →      Helpers.java
│   ├── runtime.py              →      RuntimeUtils.java
│   ├── file_edit_events.py     →      FileEditEvents.java
│   ├── prompt_templates.py     →      PromptTemplates.java
│   ├── gitstore.py             →      GitStore.java
│   ├── document.py             →      DocumentUtils.java
│   ├── path.py                 →      PathUtils.java
│   ├── media_decode.py         →      MediaDecode.java
│   ├── artifacts.py            →      ArtifactUtils.java
│   ├── evaluator.py            →      Evaluator.java
│   ├── llm_runtime.py          →      LLMRuntime.java
│   ├── searchusage.py          →      SearchUsageTracker.java
│   ├── tool_hints.py           →      ToolHints.java
│   ├── progress_events.py      →      ProgressEvents.java
│   ├── restart.py              →      RestartUtils.java
│   ├── logging_bridge.py       →      LoggingBridge.java
│   ├── subagent_channel_display.py →  SubagentChannelDisplay.java
│   └── image_generation_intent.py→    ImageGenerationIntent.java
│
├── web/                        →      web/
│   (仅 __init__.py)            →      package-info.java
│
└── webui/                      →      webui/
    ├── gateway_services.py     →      GatewayServices.java
    ├── ws_http.py              →      GatewayHttpHandler.java
    ├── websocket_logging.py    →      WebSocketLogging.java
    ├── transcript.py           →      TranscriptRecorder.java
    ├── settings_api.py         →      SettingsApi.java
    ├── settings_routes.py      →      SettingsRoutes.java
    ├── skills_api.py           →      SkillsApi.java
    ├── workspaces.py           →      WorkspacesController.java
    ├── media_api.py            →      MediaApi.java
    ├── media_gateway.py        →      MediaGateway.java
    ├── mcp_presets_api.py      →      McpPresetsApi.java
    ├── mcp_presets_runtime.py  →      McpPresetsRuntime.java
    ├── session_list_index.py   →      SessionListIndex.java
    ├── session_automations.py  →      SessionAutomations.java
    ├── sidebar_state.py        →      SidebarState.java
    ├── thread_disk.py          →      ThreadDisk.java
    ├── file_preview.py         →      FilePreview.java
    ├── token_usage.py          →      TokenUsage.java
    ├── version_check.py        →      VersionCheck.java
    ├── http_utils.py           →      HttpUtils.java
    ├── forking.py              →      ForkingService.java
    ├── transcription_ws.py     →      TranscriptionWs.java
    ├── cli_apps_api.py         →      CliAppsApi.java
    └── gateway_tokens.py       →      GatewayTokenStore.java
```

## 5. 前端复用

前端 `webui/` 目录（Vite + React + TypeScript）**原样复制**到 Java 工程中：

```
nanobot-java/
├── webui/                        ← 直接从 ../nanobot/webui 复制
│   ├── src/                      ← React/TypeScript 源码 (零改动)
│   ├── public/
│   ├── package.json
│   ├── vite.config.ts
│   └── ...
```

构建产物 `webui/dist/` 由 Java 后端作为静态文件服务（`GatewayHttpHandler`）。

**改动点**：Java 后端的 API 端点和 WebSocket 协议必须与 Python 版完全兼容，确保前端零改动。

## 6. 实现优先级

按依赖关系分为 7 个阶段。每个阶段独立可测试。

```
阶段 0: 项目骨架 + Config ──────────────────────────────┐
       │                                                   │
阶段 1: MessageBus ←────── 阶段0                           │
       │                                                   │
阶段 2: Provider Layer ←── 阶段0                           │
       │                                                   │
阶段 3: Tool Layer ←────── 阶段0                           │
       │                                                   │
阶段 4: Agent (Loop+Runner+Session+Memory) ← 阶段1+2+3     │
       │                                                   │
阶段 5: Channel + WebUI ←──── 阶段1+4                       │
       │                                                   │
阶段 6: CLI ←──────────────── 阶段4+5                       │
       │                                                   │
阶段 7: 补渠道+技能 ←───────── 阶段5                        │
```

| 阶段 | 涉及包 | 可验证标准 | 预估 Java 行数 |
|------|--------|-----------|---------------|
| **0** | config, security, utils | `java -jar nanobot-java.jar --help` 启动加载配置 | ~1,200 |
| **1** | bus | 入队→出队顺序正确 | ~400 |
| **2** | providers (base, registry, factory, anthropic, openai_compat) | 调通 Anthropic API，拿到响应 | ~3,000 |
| **3** | agent/tools (base, schema, registry, loader, shell, filesystem, web, mcp) | `exec("echo hello")` → `"hello"` | ~4,000 |
| **4** | agent (loop, runner, context, memory, subagent, hook), session | 命令行输入 "hello" → agent 回复 | ~4,500 |
| **5** | channels (base, manager, registry, websocket), webui | 浏览器打开 localhost:5173 聊天 | ~3,000 |
| **6** | cli, command, nanobot.py | 完整的 CLI 启动体验 | ~2,500 |
| **7** | 补 channels, 补 skills, api, apps, audio, cron, pairing, templates | 全部功能就绪 | ~6,000 |
| **合计** | | | **~24,600** |

### 各阶段依赖的包

```
阶段0: config/, security/, utils/
阶段1: bus/                (依赖 config)
阶段2: providers/          (依赖 config)
阶段3: agent/tools/        (依赖 config, security, utils)
阶段4: agent/ (除tools), session/  (依赖 bus, providers, agent/tools, utils)
阶段5: channels/, webui/   (依赖 bus, agent, session)
阶段6: cli/, command/, nanobot.py  (依赖全部以上)
阶段7: channels补/, skills/, api/, apps/, audio/, cron/, pairing/, templates/
```

## 7. 文档索引

每个包的详细技术方案：

| 序号 | 包 | 文档 |
|------|-----|------|
| — | **完整文件映射** (含行数/复杂度/依赖) | **[01-file-mapping.md](00-file-mapping.md)** |
| 01 | root (配置入口) | [packages/01-root.md](packages/01-root.md) |
| 02 | config | [packages/02-config.md](packages/02-config.md) |
| 03 | bus | [packages/03-bus.md](packages/03-bus.md) |
| 04 | providers (base, registry, factory) | [packages/04-providers.md](packages/04-providers.md) |
| 05 | providers (全部实现) | [packages/05-providers-impl.md](packages/05-providers-impl.md) |
| 06 | agent/tools (base, schema, registry, loader) | [packages/06-agent-tools-core.md](packages/06-agent-tools-core.md) |
| 07 | agent/tools (全部实现) | [packages/07-agent-tools-impl.md](packages/07-agent-tools-impl.md) |
| 08 | agent (loop, runner, context, hook) | [packages/08-agent-core.md](packages/08-agent-core.md) |
| 09 | agent (memory, subagent, skills, autocompact) | [packages/09-agent-support.md](packages/09-agent-support.md) |
| 10 | session | [packages/10-session.md](packages/10-session.md) |
| 11 | channels (base, manager, registry) | [packages/11-channels-core.md](packages/11-channels-core.md) |
| 12 | channels (全部实现) | [packages/12-channels-impl.md](packages/12-channels-impl.md) |
| 13 | webui | [packages/13-webui.md](packages/13-webui.md) |
| 14 | cli + command | [packages/14-cli-command.md](packages/14-cli-command.md) |
| 15 | utils | [packages/15-utils.md](packages/15-utils.md) |
| 16 | security | [packages/16-security.md](packages/16-security.md) |
| 17 | skills + templates | [packages/17-skills-templates.md](packages/17-skills-templates.md) |
| 18 | api + apps + audio + cron + pairing + web | [packages/18-remaining.md](packages/18-remaining.md) |

## 8. Maven 依赖

```xml
<!-- pom.xml 关键依赖 -->
<dependencies>
    <!-- Spring Boot 核心 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-websocket</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-configuration-processor</artifactId>
        <optional>true</optional>
    </dependency>

    <!-- Jackson JSON -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>

    <!-- Anthropic SDK -->
    <dependency>
        <groupId>com.anthropic</groupId>
        <artifactId>anthropic-java</artifactId>
        <version>0.8.0</version>
    </dependency>

    <!-- OpenAI SDK -->
    <dependency>
        <groupId>com.openai</groupId>
        <artifactId>openai-java</artifactId>
        <version>0.28.0</version>
    </dependency>

    <!-- 工具 -->
    <dependency>
        <groupId>com.google.guava</groupId>
        <artifactId>guava</artifactId>
    </dependency>
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>

    <!-- 测试 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.assertj</groupId>
        <artifactId>assertj-core</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

## 9. 关键风险与缓解

| 风险 | 缓解 |
|------|------|
| Python asyncio.CancelledError | `CompletableFuture` 层用 `CancellationException`；Virtual Thread 层用 `InterruptedException` 实现协作取消，在关键检查点 `Thread.interrupted()` |
| asyncio.wait_for(timeout=N) | `CompletableFuture.orTimeout(timeout, unit)` 或 `Future.get(timeout, unit).cancel(true)` |
| contextvars 跨线程泄漏 | 虚拟线程 1:1 对应请求 → `ThreadLocal<T>` 天然正确 |
| dict[str, Any] 类型安全 | 用 sealed interface / record 替代，保持 JSON 序列化兼容 |
| provider retry-after header 解析 | `DateTimeFormatter.RFC_1123_DATE_TIME` 对标 Python `email.utils.parsedate_to_datetime` |
| Python 动态特性 (getattr, hasattr) | Java reflection + Jackson @JsonAnySetter/Getter |
| 大量第三方 SDK (Telegram, Discord, Slack 等) | 使用对应的 Java SDK，保持相同 API 语义 |

## 10. 项目目录结构

```
nanobot-java/
├── pom.xml
├── application.yml                     # 默认配置
├── webui/                              # 直接复制 ../nanobot/webui (前端零改动)
│   ├── src/
│   ├── public/
│   ├── package.json
│   ├── vite.config.ts
│   └── ...
├── src/main/java/com/nanobot/
│   ├── NanobotApplication.java
│   ├── Nanobot.java                    # 对标 nanobot.py
│   ├── agent/                          # 对标 nanobot/agent/
│   │   ├── AgentLoop.java
│   │   ├── AgentRunner.java
│   │   ├── SubagentManager.java
│   │   ├── ContextBuilder.java
│   │   ├── MemoryStore.java
│   │   ├── Consolidator.java
│   │   ├── AgentHook.java
│   │   ├── CompositeHook.java
│   │   ├── AgentProgressHook.java
│   │   ├── AutoCompact.java
│   │   ├── SkillsLoader.java
│   │   ├── ModelPresets.java
│   │   └── tools/                     # 对标 nanobot/agent/tools/
│   │       ├── Tool.java
│   │       ├── Schema.java
│   │       ├── StringSchema.java
│   │       ├── IntegerSchema.java
│   │       ├── NumberSchema.java
│   │       ├── BooleanSchema.java
│   │       ├── ArraySchema.java
│   │       ├── ObjectSchema.java
│   │       ├── ToolRegistry.java
│   │       ├── ToolLoader.java
│   │       ├── ToolContext.java
│   │       ├── RequestContext.java
│   │       ├── ExecTool.java
│   │       ├── ReadFileTool.java
│   │       ├── WriteFileTool.java
│   │       ├── EditFileTool.java
│   │       ├── ListDirTool.java
│   │       ├── FindFilesTool.java
│   │       ├── GrepTool.java
│   │       ├── WebSearchTool.java
│   │       ├── WebFetchTool.java
│   │       ├── McpTool.java
│   │       ├── McpServerManager.java
│   │       ├── MessageTool.java
│   │       ├── MyTool.java
│   │       ├── SpawnSubagentTool.java
│   │       ├── LongTaskTool.java
│   │       ├── CompleteGoalTool.java
│   │       ├── ScheduleCronTool.java
│   │       ├── ApplyPatchTool.java
│   │       ├── ImageGenerationTool.java
│   │       ├── CliAppsTool.java
│   │       ├── ExecSessionManager.java
│   │       └── ...
│   ├── api/
│   │   └── OpenAiApiServer.java
│   ├── apps/
│   │   ├── AppProtocol.java
│   │   └── cli/
│   │       ├── CliAppService.java
│   │       └── CliAppUtils.java
│   ├── audio/
│   │   ├── TranscriptionResult.java
│   │   └── TranscriptionRegistry.java
│   ├── bus/
│   │   ├── MessageBus.java
│   │   ├── InboundMessage.java
│   │   ├── OutboundMessage.java
│   │   ├── ProgressCallback.java
│   │   ├── RuntimeEventBus.java
│   │   └── RuntimeEventPublisher.java
│   ├── channels/
│   │   ├── BaseChannel.java
│   │   ├── ChannelManager.java
│   │   ├── ChannelRegistry.java
│   │   ├── WebSocketChannel.java
│   │   ├── TelegramChannel.java
│   │   └── ...
│   ├── cli/
│   │   ├── CliCommands.java
│   │   ├── CliModels.java
│   │   ├── OnboardWizard.java
│   │   └── StreamRenderer.java
│   ├── command/
│   │   ├── CommandRouter.java
│   │   └── BuiltinCommands.java
│   ├── config/
│   │   ├── NanobotProperties.java
│   │   ├── ConfigLoader.java
│   │   └── AppPaths.java
│   ├── cron/
│   │   ├── CronService.java
│   │   ├── CronJob.java
│   │   ├── CronSchedule.java
│   │   └── CronPayload.java
│   ├── pairing/
│   │   └── PairingStore.java
│   ├── providers/
│   │   ├── LLMProvider.java
│   │   ├── LLMResponse.java
│   │   ├── ToolCallRequest.java
│   │   ├── GenerationSettings.java
│   │   ├── ProviderSpec.java
│   │   ├── ProviderRegistry.java
│   │   ├── ProviderFactory.java
│   │   ├── ProviderSnapshot.java
│   │   ├── AnthropicProvider.java
│   │   ├── OpenAiCompatProvider.java
│   │   ├── AzureOpenAiProvider.java
│   │   ├── BedrockProvider.java
│   │   ├── FallbackProvider.java
│   │   ├── GitHubCopilotProvider.java
│   │   ├── OpenAiCodexProvider.java
│   │   ├── ImageGenerationProvider.java
│   │   ├── TranscriptionProvider.java
│   │   └── openairesponses/
│   │       ├── ResponseConverters.java
│   │       └── ResponseParsing.java
│   ├── security/
│   │   ├── WorkspaceScope.java
│   │   ├── WorkspaceScopeResolver.java
│   │   ├── WorkspacePolicy.java
│   │   └── NetworkSecurity.java
│   ├── session/
│   │   ├── Session.java
│   │   ├── SessionManager.java
│   │   ├── GoalState.java
│   │   ├── TurnContinuation.java
│   │   └── WebUITurns.java
│   ├── utils/
│   │   ├── Helpers.java
│   │   ├── RuntimeUtils.java
│   │   ├── FileEditEvents.java
│   │   ├── PromptTemplates.java
│   │   ├── GitStore.java
│   │   ├── DocumentUtils.java
│   │   ├── PathUtils.java
│   │   ├── MediaDecode.java
│   │   ├── ArtifactUtils.java
│   │   ├── Evaluator.java
│   │   ├── LlmRuntime.java
│   │   ├── SearchUsageTracker.java
│   │   ├── ToolHints.java
│   │   ├── ProgressEvents.java
│   │   ├── RestartUtils.java
│   │   ├── LoggingBridge.java
│   │   ├── SubagentChannelDisplay.java
│   │   └── ImageGenerationIntent.java
│   ├── web/
│   │   └── package-info.java
│   └── webui/
│       ├── GatewayServices.java
│       ├── GatewayHttpHandler.java
│       ├── WebSocketLogging.java
│       ├── TranscriptRecorder.java
│       ├── SettingsApi.java
│       ├── SettingsRoutes.java
│       ├── SkillsApi.java
│       ├── WorkspacesController.java
│       ├── MediaApi.java
│       ├── MediaGateway.java
│       ├── McpPresetsApi.java
│       ├── McpPresetsRuntime.java
│       ├── SessionListIndex.java
│       ├── SessionAutomations.java
│       ├── SidebarState.java
│       ├── ThreadDisk.java
│       ├── FilePreview.java
│       ├── TokenUsage.java
│       ├── VersionCheck.java
│       ├── HttpUtils.java
│       ├── ForkingService.java
│       ├── TranscriptionWs.java
│       ├── CliAppsApi.java
│       └── GatewayTokenStore.java
├── src/main/resources/
│   ├── application.yml
│   ├── templates/                     # 对标 nanobot/templates/
│   └── skills/                        # 对标 nanobot/skills/
└── src/test/java/com/nanobot/
    ├── bus/MessageBusTest.java
    ├── config/NanobotPropertiesTest.java
    ├── providers/ProviderFactoryTest.java
    ├── agent/tools/ToolRegistryTest.java
    ├── agent/AgentLoopTest.java
    └── ...
```
