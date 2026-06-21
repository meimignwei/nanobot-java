# nanobot → Java 完整文件映射

> **源码路径**
> - Python: `/Users/mmw/PycharmProjects/nanobot/nanobot/` (165 文件, 63,569 行)
> - Java:   `/Users/mmw/IdeaProjects/nanobot-java/port-to-java/` → `src/main/java/com/nanobot/`

## 架构总览

```
nanobot/
├── __main__.py              → 入口 (9 行)，委托给 cli
├── nanobot.py               → 编程 API 门面 (114 行)
│
├── config/                  → 配置层 (Pydantic BaseSettings)
├── bus/                     → 消息总线 (asyncio.Queue)
├── providers/               → LLM 提供者 (适配器模式)
├── agent/                   → 核心代理引擎
│   ├── loop.py              → 状态机驱动的主循环
│   ├── runner.py            → LLM 调用 + 工具执行
│   ├── tools/               → 工具集 (23 个文件)
│   ├── memory.py            → 对话记忆 + Dream 整理
│   ├── context.py           → 提示词上下文构造
│   ├── subagent.py          → 子代理管理
│   └── ...
├── session/                 → 会话持久化 (JSON 文件)
├── channels/                → 聊天平台适配器 (17 个)
├── webui/                   → Web UI 网关 (后端 API)
├── cli/                     → CLI 交互 (rich 终端)
├── command/                 → 斜杠命令路由
├── apps/                    → CLI 应用协议
├── utils/                   → 工具函数 (19 个文件)
├── security/                → 工作区安全
├── cron/                    → 定时任务调度
├── skills/                  → 技能模板文件
├── templates/               → 提示词模板
├── audio/                   → 语音转录
├── api/                     → OpenAI API 兼容端点
├── pairing/                 → 设备配对
└── web/                     → (占位)
```

## 完整文件映射

### 阶段 0 — 项目骨架 + Config (~1,200 行 Java)

| # | Python 文件 | 行数 | 核心类/函数 | 复刻复杂度 | Java 目标 |
|---|-----------|------|-----------|-----------|----------|
| 1 | `config/schema.py` | 536 | `Config`, `AgentDefaults`, `ProviderConfig`, `ModelPresetConfig`, `ChannelsConfig`, `ToolsConfig` | 🟡 中 | `config/NanobotProperties.java` (Spring `@ConfigurationProperties`) |
| 2 | `config/loader.py` | ~200 | `load_config()`, `resolve_config_env_vars()` | 🟢 低 | `config/ConfigLoader.java` |
| 3 | `config/paths.py` | ~80 | `get_legacy_sessions_dir()`, `get_config_dir()` | 🟢 低 | `config/AppPaths.java` |
| 4 | `security/workspace_access.py` | ~250 | `WorkspaceScope`, `WorkspaceScopeResolver` | 🟡 中 | `security/WorkspaceScope.java`, `WorkspaceScopeResolver.java` |
| 5 | `security/workspace_policy.py` | ~100 | `WorkspacePolicy` | 🟢 低 | `security/WorkspacePolicy.java` |
| 6 | `security/network.py` | ~60 | `NetworkSecurity` | 🟢 低 | `security/NetworkSecurity.java` |
| 7 | `utils/helpers.py` | 639 | `truncate_text()`, `estimate_message_tokens()`, `build_assistant_message()`, `strip_think()` 等 ~40 个函数 | 🔴 高 | `utils/Helpers.java` (拆分为 2-3 个文件) |
| 8 | `utils/path.py` | ~150 | `PathUtils` | 🟢 低 | `utils/PathUtils.java` |

### 阶段 1 — MessageBus (~400 行 Java)

| # | Python 文件 | 行数 | 核心类/函数 | 复刻复杂度 | Java 目标 |
|---|-----------|------|-----------|-----------|----------|
| 9 | `bus/events.py` | 53 | `InboundMessage`, `OutboundMessage` | 🟢 低 | `bus/InboundMessage.java`, `bus/OutboundMessage.java` |
| 10 | `bus/queue.py` | 44 | `MessageBus` (asyncio.Queue → LinkedBlockingQueue) | 🟢 低 | `bus/MessageBus.java` |
| 11 | `bus/progress.py` | ~50 | `build_bus_progress_callback()` | 🟢 低 | `bus/ProgressCallback.java` |
| 12 | `bus/runtime_events.py` | ~80 | `RuntimeEventBus`, `RuntimeEventPublisher` | 🟡 中 | `bus/RuntimeEventBus.java`, `bus/RuntimeEventPublisher.java` |

### 阶段 2 — Provider Layer (~3,000 行 Java)

| # | Python 文件 | 行数 | 核心类/函数 | 复刻复杂度 | Java 目标 |
|---|-----------|------|-----------|-----------|----------|
| 13 | `providers/base.py` | 935 | `ToolCallRequest`, `LLMResponse`, `LLMProvider` (ABC) | 🔴 高 | `providers/ToolCallRequest.java`, `providers/LLMResponse.java`, `providers/LLMProvider.java` |
| 14 | `providers/registry.py` | ~100 | `ProviderSpec`, `find_by_name()` | 🟢 低 | `providers/ProviderSpec.java`, `providers/ProviderRegistry.java` |
| 15 | `providers/factory.py` | ~350 | `ProviderSnapshot`, `create_provider()`, `_make_provider_core()` | 🟡 中 | `providers/ProviderFactory.java`, `providers/ProviderSnapshot.java` |
| 16 | `providers/anthropic_provider.py` | 693 | `AnthropicProvider` | 🟡 中 | `providers/AnthropicProvider.java` |
| 17 | `providers/openai_compat_provider.py` | 1,482 | `OpenAICompatProvider` | 🔴 高 | `providers/OpenAiCompatProvider.java` |
| 18 | `providers/azure_openai_provider.py` | ~200 | `AzureOpenAIProvider` 继承 OpenAICompatProvider | 🟡 中 | `providers/AzureOpenAiProvider.java` |
| 19 | `providers/bedrock_provider.py` | 754 | `BedrockProvider` | 🔴 高 | `providers/BedrockProvider.java` |
| 20 | `providers/fallback_provider.py` | ~350 | `FallbackProvider` (重试/降级) | 🟡 中 | `providers/FallbackProvider.java` |
| 21 | `providers/github_copilot_provider.py` | ~200 | `GitHubCopilotProvider` | 🟡 中 | `providers/GitHubCopilotProvider.java` |
| 22 | `providers/openai_codex_provider.py` | ~150 | `OpenAICodexProvider` | 🟢 低 | `providers/OpenAiCodexProvider.java` |
| 23 | `providers/image_generation.py` | 1,699 | `ImageGenerationProvider`, `RecraftProvider` 等 | 🔴 高 | `providers/ImageGenerationProvider.java` |
| 24 | `providers/transcription.py` | 827 | `TranscriptionProvider`, 多个实现 | 🔴 高 | `providers/TranscriptionProvider.java` |
| 25 | `providers/openai_responses/converters.py` | ~300 | OpenAI Responses API 转换 | 🟡 中 | `providers/openairesponses/ResponseConverters.java` |
| 26 | `providers/openai_responses/parsing.py` | ~200 | JSON/Lisp 输出解析 | 🟡 中 | `providers/openairesponses/ResponseParsing.java` |

### 阶段 3 — Tool Layer (~4,000 行 Java)

| # | Python 文件 | 行数 | 核心类/函数 | 复刻复杂度 | Java 目标 |
|---|-----------|------|-----------|-----------|----------|
| 27 | `agent/tools/base.py` | 297 | `Schema` (ABC), `Tool` (ABC) | 🔴 高 | `agent/tools/Tool.java`, `agent/tools/Schema.java` |
| 28 | `agent/tools/schema.py` | ~300 | `StringSchema`, `IntegerSchema`, `NumberSchema`, `BooleanSchema`, `ArraySchema`, `ObjectSchema` | 🟡 中 | 6 个 Schema 类，各为独立 Java 文件 |
| 29 | `agent/tools/registry.py` | 183 | `ToolRegistry` | 🟡 中 | `agent/tools/ToolRegistry.java` |
| 30 | `agent/tools/loader.py` | 117 | `ToolLoader` (插件发现) | 🟡 中 | `agent/tools/ToolLoader.java` |
| 31 | `agent/tools/context.py` | ~80 | `ToolContext`, `RequestContext` | 🟢 低 | `agent/tools/ToolContext.java` |
| 32 | `agent/tools/path_utils.py` | ~80 | `ToolPathUtils` | 🟢 低 | `agent/tools/ToolPathUtils.java` |
| 33 | `agent/tools/sandbox.py` | ~100 | `SandboxWrapper` | 🟡 中 | `agent/tools/SandboxWrapper.java` |
| 34 | `agent/tools/file_state.py` | ~120 | `FileStateStore` | 🟡 中 | `agent/tools/FileStateStore.java` |
| 35 | `agent/tools/shell.py` | 677 | `ExecTool`, `ExecToolConfig` | 🔴 高 | `agent/tools/ExecTool.java` |
| 36 | `agent/tools/filesystem.py` | 1,022 | `ReadFileTool`, `WriteFileTool`, `EditFileTool`, `ListDirTool` | 🔴 高 | 4 个独立 Java 文件 |
| 37 | `agent/tools/search.py` | ~300 | `FindFilesTool`, `GrepTool` | 🟡 中 | `agent/tools/FindFilesTool.java`, `agent/tools/GrepTool.java` |
| 38 | `agent/tools/web.py` | 987 | `WebSearchTool`, `WebFetchTool` | 🔴 高 | `agent/tools/WebSearchTool.java`, `agent/tools/WebFetchTool.java` |
| 39 | `agent/tools/mcp.py` | 1,122 | `McpTool`, `McpServerManager` | 🔴 高 | `agent/tools/McpTool.java`, `agent/tools/McpServerManager.java` |
| 40 | `agent/tools/message.py` | ~50 | `MessageTool` | 🟢 低 | `agent/tools/MessageTool.java` |
| 41 | `agent/tools/self.py` | ~200 | `MyTool` (bot 自我介绍) | 🟢 低 | `agent/tools/MyTool.java` |
| 42 | `agent/tools/spawn.py` | ~250 | `SpawnSubagentTool` | 🟡 中 | `agent/tools/SpawnSubagentTool.java` |
| 43 | `agent/tools/long_task.py` | ~250 | `LongTaskTool`, `CompleteGoalTool` | 🟡 中 | `agent/tools/LongTaskTool.java`, `agent/tools/CompleteGoalTool.java` |
| 44 | `agent/tools/cron.py` | ~180 | `ScheduleCronTool` | 🟡 中 | `agent/tools/ScheduleCronTool.java` |
| 45 | `agent/tools/apply_patch.py` | ~250 | `ApplyPatchTool` | 🟡 中 | `agent/tools/ApplyPatchTool.java` |
| 46 | `agent/tools/image_generation.py` | ~200 | `ImageGenerationTool` | 🟡 中 | `agent/tools/ImageGenerationTool.java` |
| 47 | `agent/tools/cli_apps.py` | ~150 | `CliAppsTool` | 🟡 中 | `agent/tools/CliAppsTool.java` |
| 48 | `agent/tools/exec_session.py` | 609 | `ExecSessionManager` | 🔴 高 | `agent/tools/ExecSessionManager.java` |
| 49 | `agent/tools/runtime_state.py` | ~80 | `RuntimeStateHelper` | 🟢 低 | `agent/tools/RuntimeStateHelper.java` |

### 阶段 4 — Agent + Session (~4,500 行 Java)

| # | Python 文件 | 行数 | 核心类/函数 | 复刻复杂度 | Java 目标 |
|---|-----------|------|-----------|-----------|----------|
| 50 | `agent/loop.py` | 1,779 | `AgentLoop`, `TurnState` (8 状态枚举), `TurnContext` | 🔴 非常高 | `agent/AgentLoop.java` |
| 51 | `agent/runner.py` | 1,543 | `AgentRunner`, `AgentRunSpec` | 🔴 非常高 | `agent/AgentRunner.java` |
| 52 | `agent/context.py` | 280 | `ContextBuilder` | 🟡 中 | `agent/ContextBuilder.java` |
| 53 | `agent/memory.py` | 1,015 | `MemoryStore`, `DreamConsolidator` | 🔴 高 | `agent/MemoryStore.java`, `agent/Consolidator.java` |
| 54 | `agent/subagent.py` | 394 | `SubagentManager` | 🟡 中 | `agent/SubagentManager.java` |
| 55 | `agent/hook.py` | 106 | `AgentHook`, `CompositeHook` | 🟢 低 | `agent/AgentHook.java`, `agent/CompositeHook.java` |
| 56 | `agent/progress_hook.py` | ~100 | `AgentProgressHook` | 🟢 低 | `agent/AgentProgressHook.java` |
| 57 | `agent/autocompact.py` | ~150 | `AutoCompact` | 🟡 中 | `agent/AutoCompact.java` |
| 58 | `agent/skills.py` | ~150 | `SkillsLoader` | 🟡 中 | `agent/SkillsLoader.java` |
| 59 | `agent/model_presets.py` | ~150 | `ModelPresets` | 🟡 中 | `agent/ModelPresets.java` |
| 60 | `session/manager.py` | 817 | `Session`, `SessionManager` | 🔴 高 | `session/Session.java`, `session/SessionManager.java` |
| 61 | `session/goal_state.py` | ~250 | `GoalState` | 🟡 中 | `session/GoalState.java` |
| 62 | `session/turn_continuation.py` | ~120 | `TurnContinuation` | 🟢 低 | `session/TurnContinuation.java` |
| 63 | `session/webui_turns.py` | ~100 | `WebUITurns` | 🟢 低 | `session/WebUITurns.java` |
| 64 | `nanobot.py` | 114 | `Nanobot` (门面), `RunResult` | 🟢 低 | `Nanobot.java` |
| 65 | `__main__.py` | 9 | `app()` 入口 | 🟢 低 | `NanobotApplication.java` (Spring Boot main) |

### 阶段 5 — Channels + WebUI (~3,000 行 Java)

| # | Python 文件 | 行数 | 核心类/函数 | 复刻复杂度 | Java 目标 |
|---|-----------|------|-----------|-----------|----------|
| 66 | `channels/base.py` | 257 | `BaseChannel` (ABC) | 🔴 高 | `channels/BaseChannel.java` |
| 67 | `channels/manager.py` | ~250 | `ChannelManager` | 🟡 中 | `channels/ChannelManager.java` |
| 68 | `channels/registry.py` | ~80 | `ChannelRegistry` | 🟢 低 | `channels/ChannelRegistry.java` |
| 69 | `channels/websocket.py` | 1,178 | `WebSocketChannel` | 🔴 高 | `channels/WebSocketChannel.java` |
| 70 | `channels/telegram.py` | 1,472 | `TelegramChannel` | 🔴 非常高 | `channels/TelegramChannel.java` |
| 71 | `channels/discord.py` | 818 | `DiscordChannel` | 🔴 高 | `channels/DiscordChannel.java` |
| 72 | `channels/slack.py` | 729 | `SlackChannel` | 🔴 高 | `channels/SlackChannel.java` |
| 73 | `channels/weixin.py` | 1,586 | `WeixinChannel` | 🔴 高 | `channels/WeixinChannel.java` |
| 74 | `channels/feishu.py` | 1,984 | `FeishuChannel` | 🔴 高 | `channels/FeishuChannel.java` |
| 75 | `channels/dingtalk.py` | 759 | `DingTalkChannel` | 🔴 高 | `channels/DingTalkChannel.java` |
| 76 | `channels/whatsapp.py` | ~250 | `WhatsAppChannel` | 🟡 中 | `channels/WhatsAppChannel.java` |
| 77 | `channels/signal.py` | 1,402 | `SignalChannel` | 🔴 高 | `channels/SignalChannel.java` |
| 78 | `channels/matrix.py` | 1,022 | `MatrixChannel` | 🔴 高 | `channels/MatrixChannel.java` |
| 79 | `channels/msteams.py` | 822 | `MsTeamsChannel` | 🔴 高 | `channels/MsTeamsChannel.java` |
| 80 | `channels/email.py` | 912 | `EmailChannel` | 🔴 高 | `channels/EmailChannel.java` |
| 81 | `channels/qq.py` | 701 | `QQChannel` | 🔴 高 | `channels/QQChannel.java` |
| 82 | `channels/napcat.py` | ~150 | `NapCatChannel` | 🟡 中 | `channels/NapCatChannel.java` |
| 83 | `channels/mochat.py` | 943 | `MoChatChannel` | 🔴 高 | `channels/MoChatChannel.java` |
| 84 | `channels/wecom.py` | ~300 | `WeComChannel` | 🟡 中 | `channels/WeComChannel.java` |
| 85 | `webui/gateway_services.py` | ~300 | `GatewayServices` | 🟡 中 | `webui/GatewayServices.java` |
| 86 | `webui/ws_http.py` | 602 | `GatewayHttpHandler` (静态文件 + WebSocket + API) | 🔴 高 | `webui/GatewayHttpHandler.java` |
| 87 | `webui/websocket_logging.py` | ~200 | WebSocket 日志广播 | 🟡 中 | `webui/WebSocketLogging.java` |
| 88 | `webui/transcript.py` | 1,864 | `TranscriptRecorder` | 🔴 高 | `webui/TranscriptRecorder.java` |
| 89 | `webui/settings_api.py` | 1,434 | `SettingsApi` | 🔴 高 | `webui/SettingsApi.java` |
| 90 | `webui/settings_routes.py` | ~150 | `SettingsRoutes` | 🟡 中 | `webui/SettingsRoutes.java` |
| 91 | `webui/skills_api.py` | ~200 | `SkillsApi` | 🟡 中 | `webui/SkillsApi.java` |
| 92 | `webui/workspaces.py` | ~200 | `WorkspacesController` | 🟡 中 | `webui/WorkspacesController.java` |
| 93 | `webui/media_api.py` | ~150 | `MediaApi` | 🟡 中 | `webui/MediaApi.java` |
| 94 | `webui/media_gateway.py` | ~150 | `MediaGateway` | 🟡 中 | `webui/MediaGateway.java` |
| 95 | `webui/mcp_presets_api.py` | 1,318 | `McpPresetsApi` | 🔴 高 | `webui/McpPresetsApi.java` |
| 96 | `webui/mcp_presets_runtime.py` | ~200 | `McpPresetsRuntime` | 🟡 中 | `webui/McpPresetsRuntime.java` |
| 97 | `webui/session_list_index.py` | ~120 | `SessionListIndex` | 🟢 低 | `webui/SessionListIndex.java` |
| 98 | `webui/session_automations.py` | ~200 | `SessionAutomations` | 🟡 中 | `webui/SessionAutomations.java` |
| 99 | `webui/sidebar_state.py` | ~100 | `SidebarState` | 🟢 低 | `webui/SidebarState.java` |
| 100 | `webui/thread_disk.py` | ~200 | `ThreadDisk` | 🟡 中 | `webui/ThreadDisk.java` |
| 101 | `webui/file_preview.py` | ~120 | `FilePreview` | 🟢 低 | `webui/FilePreview.java` |
| 102 | `webui/token_usage.py` | ~100 | `TokenUsage` | 🟢 低 | `webui/TokenUsage.java` |
| 103 | `webui/version_check.py` | ~80 | `VersionCheck` | 🟢 低 | `webui/VersionCheck.java` |
| 104 | `webui/http_utils.py` | ~60 | `HttpUtils` | 🟢 低 | `webui/HttpUtils.java` |
| 105 | `webui/forking.py` | ~150 | `ForkingService` | 🟡 中 | `webui/ForkingService.java` |
| 106 | `webui/transcription_ws.py` | ~150 | `TranscriptionWs` | 🟡 中 | `webui/TranscriptionWs.java` |
| 107 | `webui/cli_apps_api.py` | ~100 | `CliAppsApi` | 🟢 低 | `webui/CliAppsApi.java` |
| 108 | `webui/gateway_tokens.py` | ~80 | `GatewayTokenStore` | 🟢 低 | `webui/GatewayTokenStore.java` |

### 阶段 6 — CLI + Command (~2,500 行 Java)

| # | Python 文件 | 行数 | 核心类/函数 | 复刻复杂度 | Java 目标 |
|---|-----------|------|-----------|-----------|----------|
| 109 | `cli/commands.py` | 2,043 | `app()`, `run()`, `chat()`, 30+ 子命令 | 🔴 非常高 | `cli/CliCommands.java` (需拆分) |
| 110 | `cli/models.py` | ~150 | CLI 模型选择 UI | 🟡 中 | `cli/CliModels.java` |
| 111 | `cli/onboard.py` | 1,385 | `OnboardWizard` (10 步引导) | 🔴 高 | `cli/OnboardWizard.java` |
| 112 | `cli/stream.py` | ~200 | `StreamRenderer` (终端流式渲染) | 🟡 中 | `cli/StreamRenderer.java` |
| 113 | `command/router.py` | ~250 | `CommandRouter` (3 层优先级匹配) | 🟡 中 | `command/CommandRouter.java` |
| 114 | `command/builtin.py` | 719 | `BuiltinCommands` (~15 个内建命令) | 🔴 高 | `command/BuiltinCommands.java` |

### 阶段 7 — 补全 (~6,000 行 Java)

| # | Python 文件 | 行数 | 核心类/函数 | 复刻复杂度 | Java 目标 |
|---|-----------|------|-----------|-----------|----------|
| 115 | `api/server.py` | ~200 | `OpenAiApiServer` (OpenAI 兼容 API) | 🟡 中 | `api/OpenAiApiServer.java` |
| 116 | `apps/protocol.py` | ~150 | `AppProtocol` | 🟢 低 | `apps/AppProtocol.java` |
| 117 | `apps/cli/service.py` | 1,263 | `CliAppService` | 🔴 高 | `apps/cli/CliAppService.java` |
| 118 | `apps/cli/utils.py` | ~200 | `CliAppUtils` | 🟢 低 | `apps/cli/CliAppUtils.java` |
| 119 | `audio/transcription.py` | ~400 | `transcribe_audio_file()`, `resolve_transcription_config()` | 🔴 高 | `audio/TranscriptionResult.java` + `audio/TranscriptionService.java` |
| 120 | `audio/transcription_registry.py` | ~150 | `TranscriptionRegistry` | 🟡 中 | `audio/TranscriptionRegistry.java` |
| 121 | `cron/service.py` | 664 | `CronService`, Cron 管理 | 🔴 高 | `cron/CronService.java` |
| 122 | `cron/types.py` | ~120 | `CronJob`, `CronSchedule`, `CronPayload` | 🟢 低 | `cron/CronJob.java`, `cron/CronSchedule.java`, `cron/CronPayload.java` |
| 123 | `pairing/store.py` | ~80 | `PairingStore` (设备配对) | 🟢 低 | `pairing/PairingStore.java` |

### 工具函数 (utils/) — 按需分发到各个阶段

| # | Python 文件 | 行数 | 核心功能 | 依赖阶段 | Java 目标 |
|---|-----------|------|---------|---------|----------|
| 124 | `utils/runtime.py` | ~300 | `EMPTY_FINAL_RESPONSE_MESSAGE`, `build_goal_continue_message()` 等 | 4 | `utils/RuntimeUtils.java` |
| 125 | `utils/file_edit_events.py` | 964 | `StreamingFileEditTracker`, 文件编辑进度事件 | 4 | `utils/FileEditEvents.java` |
| 126 | `utils/prompt_templates.py` | ~200 | `render_template()` Jinja2 模板 | 4 | `utils/PromptTemplates.java` |
| 127 | `utils/gitstore.py` | ~150 | `GitStore` (Git 状态缓存) | 4 | `utils/GitStore.java` |
| 128 | `utils/document.py` | ~250 | `extract_documents()`, 附件处理 | 4 | `utils/DocumentUtils.java` |
| 129 | `utils/media_decode.py` | ~150 | Base64/媒体解码 | 5 | `utils/MediaDecode.java` |
| 130 | `utils/artifacts.py` | ~100 | `ArtifactUtils` (HTML 制品) | 5 | `utils/ArtifactUtils.java` |
| 131 | `utils/evaluator.py` | ~100 | `Evaluator` (表达式求值) | 4 | `utils/Evaluator.java` |
| 132 | `utils/llm_runtime.py` | ~80 | `LLMRuntime` (provider+model 对) | 4 | `utils/LlmRuntime.java` |
| 133 | `utils/searchusage.py` | ~100 | `SearchUsageTracker` | 5 | `utils/SearchUsageTracker.java` |
| 134 | `utils/tool_hints.py` | ~100 | `ToolHints` | 5 | `utils/ToolHints.java` |
| 135 | `utils/progress_events.py` | ~100 | `ProgressEvents` | 5 | `utils/ProgressEvents.java` |
| 136 | `utils/restart.py` | ~80 | `RestartUtils` | 5 | `utils/RestartUtils.java` |
| 137 | `utils/logging_bridge.py` | ~60 | `LoggingBridge` | 0 | `utils/LoggingBridge.java` |
| 138 | `utils/subagent_channel_display.py` | ~80 | `SubagentChannelDisplay` | 4 | `utils/SubagentChannelDisplay.java` |
| 139 | `utils/image_generation_intent.py` | ~80 | `ImageGenerationIntent` | 4 | `utils/ImageGenerationIntent.java` |

## 关键依赖图

```
                     ┌─────────────┐
                     │   config    │
                     └──────┬──────┘
                            │
              ┌─────────────┼─────────────┐
              │             │             │
         ┌────▼────┐  ┌────▼────┐  ┌────▼────┐
         │   bus   │  │providers│  │ security│
         └────┬────┘  └────┬────┘  └────┬────┘
              │             │             │
              └───────┬─────┘             │
                      │                   │
                 ┌────▼────┐              │
                 │agent/tools◄────────────┘
                 └────┬────┘
                      │
            ┌─────────┼─────────┐
            │         │         │
      ┌─────▼──┐ ┌───▼────┐ ┌─▼───────┐
      │ agent  │ │session │ │ command │
      │(loop/  │ │        │ │         │
      │runner) │ │        │ │         │
      └─────┬──┘ └───┬────┘ └────┬────┘
            │         │          │
            └────┬────┘          │
                 │               │
            ┌────▼────┐          │
            │channels │          │
            └────┬────┘          │
                 │               │
            ┌────▼────┐    ┌────▼────┐
            │  webui  │    │   cli   │
            └─────────┘    └─────────┘
```

## Java 包结构对照

```
com.nanobot
├── NanobotApplication.java          # Spring Boot main + __main__.py
├── Nanobot.java                     # 编程 API 门面
│
├── config/                          # nanobot/config/
│   ├── NanobotProperties.java       # schema.py (所有 @ConfigurationProperties)
│   ├── ConfigLoader.java            # loader.py
│   └── AppPaths.java                # paths.py
│
├── bus/                             # nanobot/bus/
│   ├── MessageBus.java              # queue.py
│   ├── InboundMessage.java          # events.py
│   ├── OutboundMessage.java         # events.py
│   ├── ProgressCallback.java        # progress.py
│   ├── RuntimeEventBus.java         # runtime_events.py
│   └── RuntimeEventPublisher.java   # runtime_events.py
│
├── providers/                       # nanobot/providers/
│   ├── LLMProvider.java             # base.py (接口)
│   ├── LLMResponse.java             # base.py
│   ├── ToolCallRequest.java         # base.py
│   ├── GenerationSettings.java      # base.py
│   ├── ProviderSpec.java            # registry.py
│   ├── ProviderRegistry.java        # registry.py
│   ├── ProviderFactory.java         # factory.py
│   ├── ProviderSnapshot.java        # factory.py
│   ├── AnthropicProvider.java       # anthropic_provider.py
│   ├── OpenAiCompatProvider.java    # openai_compat_provider.py
│   ├── AzureOpenAiProvider.java     # azure_openai_provider.py
│   ├── BedrockProvider.java         # bedrock_provider.py
│   ├── FallbackProvider.java        # fallback_provider.py
│   ├── GitHubCopilotProvider.java   # github_copilot_provider.py
│   ├── OpenAiCodexProvider.java     # openai_codex_provider.py
│   ├── ImageGenerationProvider.java # image_generation.py
│   ├── TranscriptionProvider.java   # transcription.py
│   └── openairesponses/
│       ├── ResponseConverters.java
│       └── ResponseParsing.java
│
├── agent/
│   ├── AgentLoop.java               # loop.py (状态机核心)
│   ├── AgentRunner.java             # runner.py (LLM 调用 + 工具执行)
│   ├── ContextBuilder.java          # context.py
│   ├── MemoryStore.java             # memory.py (前半)
│   ├── Consolidator.java            # memory.py (Dream 整理)
│   ├── SubagentManager.java         # subagent.py
│   ├── AgentHook.java               # hook.py
│   ├── CompositeHook.java           # hook.py
│   ├── AgentProgressHook.java       # progress_hook.py
│   ├── AutoCompact.java             # autocompact.py
│   ├── SkillsLoader.java            # skills.py
│   ├── ModelPresets.java            # model_presets.py
│   └── tools/
│       ├── Tool.java                # base.py
│       ├── Schema.java              # base.py
│       ├── StringSchema.java        # schema.py
│       ├── IntegerSchema.java       # schema.py
│       ├── NumberSchema.java        # schema.py
│       ├── BooleanSchema.java       # schema.py
│       ├── ArraySchema.java         # schema.py
│       ├── ObjectSchema.java        # schema.py
│       ├── ToolRegistry.java        # registry.py
│       ├── ToolLoader.java          # loader.py
│       ├── ToolContext.java         # context.py
│       ├── ToolPathUtils.java       # path_utils.py
│       ├── SandboxWrapper.java      # sandbox.py
│       ├── FileStateStore.java      # file_state.py
│       ├── ExecTool.java            # shell.py
│       ├── ReadFileTool.java        # filesystem.py
│       ├── WriteFileTool.java       # filesystem.py
│       ├── EditFileTool.java        # filesystem.py
│       ├── ListDirTool.java         # filesystem.py
│       ├── FindFilesTool.java       # search.py
│       ├── GrepTool.java            # search.py
│       ├── WebSearchTool.java       # web.py
│       ├── WebFetchTool.java        # web.py
│       ├── McpTool.java             # mcp.py
│       ├── McpServerManager.java    # mcp.py
│       ├── MessageTool.java         # message.py
│       ├── MyTool.java              # self.py
│       ├── SpawnSubagentTool.java   # spawn.py
│       ├── LongTaskTool.java        # long_task.py
│       ├── CompleteGoalTool.java    # long_task.py
│       ├── ScheduleCronTool.java    # cron.py
│       ├── ApplyPatchTool.java      # apply_patch.py
│       ├── ImageGenerationTool.java # image_generation.py
│       ├── CliAppsTool.java         # cli_apps.py
│       ├── ExecSessionManager.java  # exec_session.py
│       └── RuntimeStateHelper.java  # runtime_state.py
│
├── session/
│   ├── Session.java                 # manager.py
│   ├── SessionManager.java          # manager.py
│   ├── GoalState.java               # goal_state.py
│   ├── TurnContinuation.java        # turn_continuation.py
│   └── WebUITurns.java              # webui_turns.py
│
├── channels/
│   ├── BaseChannel.java             # base.py
│   ├── ChannelManager.java          # manager.py
│   ├── ChannelRegistry.java         # registry.py
│   ├── WebSocketChannel.java        # websocket.py
│   ├── TelegramChannel.java         # telegram.py
│   ├── DiscordChannel.java          # discord.py
│   ├── SlackChannel.java            # slack.py
│   ├── WeixinChannel.java           # weixin.py
│   ├── FeishuChannel.java           # feishu.py
│   ├── DingTalkChannel.java         # dingtalk.py
│   ├── WhatsAppChannel.java         # whatsapp.py
│   ├── SignalChannel.java           # signal.py
│   ├── MatrixChannel.java           # matrix.py
│   ├── MsTeamsChannel.java          # msteams.py
│   ├── EmailChannel.java            # email.py
│   ├── QQChannel.java               # qq.py
│   ├── NapCatChannel.java           # napcat.py
│   ├── MoChatChannel.java           # mochat.py
│   └── WeComChannel.java            # wecom.py
│
├── webui/
│   ├── GatewayServices.java
│   ├── GatewayHttpHandler.java      # ws_http.py (核心 HTTP/WS 处理器)
│   ├── WebSocketLogging.java
│   ├── TranscriptRecorder.java
│   ├── SettingsApi.java
│   ├── SettingsRoutes.java
│   ├── SkillsApi.java
│   ├── WorkspacesController.java
│   ├── MediaApi.java
│   ├── MediaGateway.java
│   ├── McpPresetsApi.java
│   ├── McpPresetsRuntime.java
│   ├── SessionListIndex.java
│   ├── SessionAutomations.java
│   ├── SidebarState.java
│   ├── ThreadDisk.java
│   ├── FilePreview.java
│   ├── TokenUsage.java
│   ├── VersionCheck.java
│   ├── HttpUtils.java
│   ├── ForkingService.java
│   ├── TranscriptionWs.java
│   ├── CliAppsApi.java
│   └── GatewayTokenStore.java
│
├── cli/
│   ├── CliCommands.java             # commands.py
│   ├── CliModels.java               # models.py
│   ├── OnboardWizard.java           # onboard.py
│   └── StreamRenderer.java          # stream.py
│
├── command/
│   ├── CommandRouter.java           # router.py
│   └── BuiltinCommands.java         # builtin.py
│
├── api/
│   └── OpenAiApiServer.java         # server.py
│
├── apps/
│   ├── AppProtocol.java
│   └── cli/
│       ├── CliAppService.java
│       └── CliAppUtils.java
│
├── audio/
│   ├── TranscriptionService.java
│   └── TranscriptionRegistry.java
│
├── cron/
│   ├── CronService.java
│   ├── CronJob.java
│   ├── CronSchedule.java
│   └── CronPayload.java
│
├── pairing/
│   └── PairingStore.java
│
├── security/
│   ├── WorkspaceScope.java
│   ├── WorkspaceScopeResolver.java
│   ├── WorkspacePolicy.java
│   └── NetworkSecurity.java
│
├── utils/
│   ├── Helpers.java
│   ├── RuntimeUtils.java
│   ├── FileEditEvents.java
│   ├── PromptTemplates.java
│   ├── GitStore.java
│   ├── DocumentUtils.java
│   ├── PathUtils.java
│   ├── MediaDecode.java
│   ├── ArtifactUtils.java
│   ├── Evaluator.java
│   ├── LlmRuntime.java
│   ├── SearchUsageTracker.java
│   ├── ToolHints.java
│   ├── ProgressEvents.java
│   ├── RestartUtils.java
│   ├── LoggingBridge.java
│   ├── SubagentChannelDisplay.java
│   └── ImageGenerationIntent.java
│
└── web/
    └── package-info.java
```

## 复刻复杂度统计

| 复杂度 | 文件数 | 说明 |
|--------|--------|------|
| 🟢 低 | ~38 | 简单数据类、工具函数、薄封装 |
| 🟡 中 | ~55 | 有业务逻辑，需要理解算法但结构清晰 |
| 🔴 高 | ~40 | 大量状态管理、异步逻辑、第三方 SDK 集成 |
| 🔴 非常高 | ~6 | `AgentLoop`, `AgentRunner`, `OpenAiCompatProvider`, `cli/commands.py`, `TelegramChannel`, `FeishuChannel` |

## 核心数据流

```
Channel (WebSocket/Telegram/... )
  │
  ├─→ InboundMessage
  │     │
  │     ▼
  │   MessageBus.inbound ──→ AgentLoop.process_one()  ← 状态机核心
  │                              │
  │                              ├─ RESTORE: 恢复 session 历史
  │                              ├─ COMPACT: 自动压缩上下文
  │                              ├─ COMMAND: 检查斜杠命令 (CommandRouter)
  │                              ├─ BUILD:   构造提示词上下文 (ContextBuilder)
  │                              ├─ RUN:     调用 AgentRunner
  │                              │             ├─ LLMProvider.generate()
  │                              │             ├─ 解析 LLMResponse
  │                              │             ├─ Tool.call() 执行工具
  │                              │             └─ 多轮循环直到 finish
  │                              ├─ SAVE:    持久化到 Session
  │                              ├─ RESPOND: 构造 OutboundMessage
  │                              └─ DONE
  │
  └─ OutboundMessage ←── MessageBus.outbound
        │
        ▼
      Channel.send() / WebSocket push
```

## Python → Java 关键转换点

| Python 特性 | Java 对应 | 难点 |
|------------|----------|------|
| `asyncio.Queue` | `LinkedBlockingQueue` | ✅ 直接映射 |
| `asyncio.create_task()` | `Thread.startVirtualThread()` | ✅ Spring 虚拟线程 |
| `async with` / `AsyncExitStack` | `try-with-resources` / `Closable` | 🟡 需要适配 |
| `contextvars.ContextVar` | `ThreadLocal` | ✅ 虚拟线程天然隔离 |
| Pydantic `BaseSettings` | `@ConfigurationProperties` records | 🟡 字段映射 (camelCase ↔ snake_case) |
| `pkgutil.iter_modules` (插件发现) | Spring `@ComponentScan` + `ServiceLoader` | 🟡 编译时 vs 运行时 |
| `json_repair` (修复破损 JSON) | Jackson + 手动容错 | 🟡 需要自行实现容错 |
| `loguru` 结构化日志 | Logback + MDC | 🟡 风格差异 |
| Jinja2 模板 | Pebble 或 Thymeleaf | 🟡 语法差异 |
| `rich` 终端 UI | Spring Shell 或 JLine | 🔴 差异大 |
| Python `dict` 动态属性 | `Map<String, Object>` + Jackson `@JsonAnySetter` | 🟡 类型安全 |
| `signal.SIGINT` 优雅关闭 | `@PreDestroy` + `Runtime.getRuntime().addShutdownHook()` | ✅ 标准 |
