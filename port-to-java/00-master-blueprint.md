# nanobot → Java 复刻技术方案：总蓝图

> **最高规则：一切以 nanobot 源码为准。**
>
> 本文档及所有模块文档是复刻的参考指南，不是权威。当本文档与 `nanobot/` 目录下的 Python 源码有出入时，**源码即真相**。文档中的类名、方法签名、逻辑流程如有遗漏或偏差，以源码的实际行为为准。复刻过程中发现文档与源码不一致时，修正文档并继续。

## 1. 目标

将 nanobot Python 代码库复刻为 Java 21 + Spring Boot 3.2+ 实现，保留相同的分层架构、数据流、前端兼容性。

## 2. 技术栈选型

| 关注点 | Python 原版 | Java 复刻 | 理由 |
|--------|------------|-----------|------|
| 运行时 | asyncio | Virtual Threads (JEP 444) | 同步写法、异步性能、不再传染 async |
| HTTP/WS 服务 | aiohttp + websockets | Spring Boot 3.2+ (Tomcat/Jetty) | 虚拟线程一行启用，WS 成熟 |
| 配置 | Pydantic BaseSettings | @ConfigurationProperties + Java Record | 编译时类型安全，env 绑定 |
| JSON | 内置 json + json_repair | Jackson ObjectMapper | 行业标准 |
| 插件发现 | pkgutil.iter_modules + entry_points | Spring @Component + ServiceLoader | 编译时 + 运行时兼顾 |
| LLM HTTP | httpx / aiohttp | java.net.http.HttpClient (虚拟线程) | JDK 内置 |
| 测试 | pytest + pytest-asyncio | JUnit 5 + AssertJ | 标准选择 |
| 构建 | pip/setuptools | Maven (pom.xml) | 依赖管理清晰 |

## 3. 架构分层（保持不变）

```
┌─────────────────────────────────────────────────┐
│              WebUI (TypeScript)                  │  ← 零改动，复用现有 React SPA
│             WebSocket + HTTP/JSON                │
├─────────────────────────────────────────────────┤
│          Channel Layer (Java)                    │
│  WebSocketChannel │ TelegramChannel │ (more...)  │  ← Phase 6-7
├─────────────────────────────────────────────────┤
│              MessageBus                          │  ← Phase 1: BlockingQueue
├─────────────────────────────────────────────────┤
│          Agent Loop + Runner                     │  ← Phase 4: 核心状态机
├────────────────┬────────────────┬───────────────┤
│  Tool Registry │ Provider Layer │ Session/Memory│  ← Phase 2, 3, 5
├────────────────┴────────────────┴───────────────┤
│  Spring Boot 3.2+ / Java 21 / Virtual Threads   │
│  Jackson │ AutoCloseable │ Lock │ BlockingQueue │
└─────────────────────────────────────────────────┘
```

## 4. 数据流（与 Python 版一致）

```
Channel.start() → 监听平台消息
  → InboundMessage → bus.inbound.put(msg)
    → AgentLoop.consume() 拉取
      → SessionManager.load(key) 恢复历史
        → ContextBuilder 拼装 messages + tools
          → AgentRunner.run(spec):
              loop:
                provider.chat(messages, tools) → LLMResponse
                if tool_calls: execute → append tool results → 继续 loop
                else: 最终回复 → 退出 loop
          → SessionManager.save(key, messages)
        → OutboundMessage → bus.outbound.put(msg)
          → Channel.send(msg)
```

## 5. 模块开发优先级与依赖关系

```
P0 项目骨架 + Config ─────────────────────────────┐
       │                                            │
P1 MessageBus ←────── P0                           │
       │                                            │
P2 Provider Layer ←── P0                           │
       │                                            │
P3 Tool Layer ←────── P0                           │
       │                                            │
P4 Agent Loop+Runner ← P1 + P2 + P3                │
       │                                            │
P5 Session+Memory ←─── P4                           │
       │                                            │
P6 Channel+WebUI ←──── P1 + P4                      │
       │                                            │
P7 补渠道 ←─────────── P6                           │
```

| Phase | 模块 | 依赖 | 可验证标准 | 预估 Java 行数 |
|-------|------|------|-----------|---------------|
| **P0** | 项目骨架 + Config | 无 | `nanobot-java --help` 启动加载配置 | ~750 |
| **P1** | MessageBus | P0 | 入队→出队顺序正确 | ~170 |
| **P2** | Provider Layer | P0 | 调通 Anthropic API，拿到 LLMResponse | ~1,920 |
| **P3** | Tool Layer | P0 | `exec("echo hello")` → `"hello"` | ~695 |
| **P4** | Agent Loop + Runner | P1+P2+P3 | 命令行输入 "hello" → agent 回复 | ~905 |
| **P5** | Session + Memory | P4 | 两轮对话后上下文正确累积 | ~860 |
| **P6** | Channel + WebUI | P1+P4 | 浏览器打开 localhost:5173，聊天 | ~660 |
| **P7** | 补渠道 | P6 | Telegram/Discord 等逐个接入 | ~250/个 (×5≈1,500) |
| **合计** | | | | **~7,460** |

## 6. 关键风险与缓解

| 风险 | 缓解 |
|------|------|
| `asyncio.CancelledError` → `InterruptedException` | Agent loop 在关键检查点手动 `Thread.interrupted()`，形成协作取消协议 |
| `asyncio.wait_for(timeout=N)` | `StructuredTaskScope.joinUntil(deadline)` (Java 23) 或 `Future.get(timeout).cancel()` |
| `contextvars` 跨线程泄漏 | 虚拟线程 1:1 对应请求 → `ThreadLocal` 语义天然正确 |
| `dict[str, Any]` 类型安全 | 用 sealed interface `Message` 体系替代，但保持 JSON 序列化兼容 |
| provider `retry-after` header 解析 | 从 Python `email.utils.parsedate_to_datetime` → Java `DateTimeFormatter.RFC_1123_DATE_TIME` |

## 7. 项目结构

```
nanobot-java/
├── pom.xml
├── src/main/java/com/nanobot/
│   ├── NanobotApplication.java        # Spring Boot 入口
│   ├── config/
│   │   ├── NanobotConfig.java         # @ConfigurationProperties 根配置
│   │   ├── ProviderConfig.java        # 各 provider 子配置
│   │   └── ...
│   ├── bus/
│   │   ├── MessageBus.java            # BlockingQueue 实现
│   │   ├── InboundMessage.java        # record
│   │   └── OutboundMessage.java       # record
│   ├── providers/
│   │   ├── LLMProvider.java           # abstract class (原 base.py)
│   │   ├── LLMResponse.java           # record
│   │   ├── ToolCallRequest.java       # record
│   │   ├── GenerationSettings.java    # record
│   │   ├── ProviderSpec.java          # record (原 registry.py)
│   │   ├── ProviderRegistry.java      # static PROVIDERS list
│   │   ├── ProviderFactory.java       # makeProvider()
│   │   ├── OpenAiCompatProvider.java  # 主要的 HTTP→LLM 实现
│   │   ├── AnthropicProvider.java     # 原生 Anthropic SDK
│   │   └── FallbackProvider.java      # 复合 failover
│   ├── agent/
│   │   ├── AgentLoop.java             # 核心状态机
│   │   ├── AgentRunner.java           # LLM 执行循环
│   │   ├── TurnContext.java           # record
│   │   ├── TurnState.java             # enum
│   │   ├── ContextBuilder.java        # 上下文拼装
│   │   └── tools/
│   │       ├── Tool.java              # abstract class
│   │       ├── ToolRegistry.java      # Map<String, Tool>
│   │       ├── ToolLoader.java        # 工具发现与注册
│   │       ├── ToolContext.java       # record (ThreadLocal 绑定)
│   │       ├── ToolResult.java        # record
│   │       ├── ExecTool.java          # shell 执行
│   │       ├── FileReadTool.java      # 文件读取
│   │       ├── FileWriteTool.java     # 文件写入
│   │       ├── FileEditTool.java      # 文件编辑
│   │       └── ...
│   ├── session/
│   │   ├── Session.java               # 会话数据
│   │   ├── SessionManager.java        # 会话 CRUD
│   │   └── MemoryStore.java           # 文件 I/O 持久化
│   ├── channels/
│   │   ├── BaseChannel.java           # abstract class
│   │   ├── ChannelManager.java        # 渠道生命周期
│   │   ├── WebSocketChannel.java      # 核心：WebUI 连接
│   │   └── ConsoleChannel.java        # 调试：命令行输入
│   └── util/
│       ├── MessageSanitizer.java      # 消息清洗 (原 base.py 静态方法)
│       ├── RetryPolicy.java           # 重试逻辑 (原 _run_with_retry)
│       └── ...
└── src/test/java/com/nanobot/
    ├── bus/MessageBusTest.java
    ├── providers/...Test.java
    ├── agent/...Test.java
    └── ...
```

## 8. 各模块详细文档索引

- [P0 — 项目骨架 + Config](modules/01-project-skeleton.md)
- [P1 — MessageBus](modules/02-message-bus.md)
- [P2 — Provider Layer](modules/03-provider-layer.md)
- [P3 — Tool Layer](modules/04-tool-layer.md)
- [P4 — Agent Loop + Runner](modules/05-agent-loop-runner.md)
- [P5 — Session + Memory](modules/06-session-memory.md)
- [P6 — Channel + WebUI](modules/07-channel-webui.md)
- [P7 — 补渠道](modules/08-additional-channels.md)
