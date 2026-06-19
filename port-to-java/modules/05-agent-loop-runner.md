# P4 — Agent Loop + Runner

## 复刻目标

对标 `nanobot/agent/loop.py`（1,779 行）+ `nanobot/agent/runner.py`（1,543 行）。这是整个系统的心脏——状态机驱动的 agent turn 处理 + LLM 执行循环。

## Python 源码对照

### AgentLoop 核心结构

```python
class TurnState(Enum):
    RESTORE = auto()    # 恢复会话历史
    COMPACT = auto()    # 上下文压缩
    COMMAND = auto()    # 斜杠命令处理
    BUILD = auto()      # 构建上下文（拼装 messages + tools）
    RUN = auto()        # 执行 AgentRunner
    SAVE = auto()       # 保存会话
    RESPOND = auto()    # 发送回复到 channel
    DONE = auto()

@dataclass
class TurnContext:
    msg: InboundMessage
    session_key: str
    provider: LLMProvider         # AgentLoop 持有，由工厂创建
    registry: ToolRegistry         # AgentLoop 持有
    bus: MessageBus
    session_manager: SessionManager
    # ... 更多状态

class AgentLoop:
    def __init__(config, bus, session_manager, ...):
        self._sessions: dict[str, asyncio.Lock]  # per-session 锁
        self._semaphore: asyncio.Semaphore        # 并发限制
        self._pending_task_queues: dict[str, asyncio.Queue]  # per-session task 排队

    async def run(self):
        """主循环: consume inbound → process → publish outbound"""
        while True:
            msg = await self.bus.consume_inbound()
            asyncio.create_task(self._process_message(msg))

    async def _process_message(self, msg):
        """Per-message 处理入口，管理 session lock + semaphore"""
        async with self._get_session_lock(key):
            async with self._semaphore:
                ctx = TurnContext(...)
                await self._run_state_machine(ctx)

    async def _run_state_machine(self, ctx):
        """状态机驱动一个 turn: RESTORE → COMPACT → COMMAND → BUILD → RUN → SAVE → RESPOND → DONE"""
        # 逐状态执行，可从任意状态跳转到 DONE
```

### AgentRunner 核心结构

```python
@dataclass
class AgentRunSpec:
    initial_messages: list[dict]
    tools: ToolRegistry
    model: str
    max_iterations: int        # 最大 tool 调用轮次
    max_tool_result_chars: int
    temperature, max_tokens, reasoning_effort
    hook: AgentHook | None
    concurrent_tools: bool
    ...

class AgentRunner:
    async def run(self, spec, provider, workspace, ...) -> AgentRunResult:
        """主执行循环:
        while 未达到 max_iterations:
            response = await provider.chat_with_retry(messages, tools, ...)
            if response.should_execute_tools:
                # 执行工具
                for tc in response.tool_calls:
                    tool, params, err = registry.prepare_call(tc.name, tc.arguments)
                    if err: append error as tool result
                    else:
                        result = await tool.execute(params, ctx)
                        append tool result message
                continue  # 继续 loop
            else:
                return final response  # 最终回复
        """
```

### 关键复杂点

1. **上下文紧凑** (`_compact_if_needed`): 当 token 数超过 context_window 的 `consolidation_ratio` 触发
2. **图像处理**: 提取用户消息中的图片 URL，placeholder + 文件路径 _meta
3. **文档提取**: `extract_documents()` 从消息附件中提取文本
4. **流式恢复**: `on_stream_recover` 回调——流中断后重连不重复发送已有内容
5. **工具结果截断**: 超过 `max_tool_result_chars` 的结果持久化到文件，消息中放摘要
6. **文件编辑事件**: `StreamingFileEditTracker` 跟踪文件修改并发进度事件

## Java 实现方案

### 1. TurnState 枚举 + TurnContext

```java
// TurnState.java
public enum TurnState {
    RESTORE, COMPACT, COMMAND, BUILD, RUN, SAVE, RESPOND, DONE;

    public TurnState next() {
        var values = values();
        return ordinal() < values.length - 1
            ? values[ordinal() + 1] : DONE;
    }
}

// TurnContext.java — 对标 Python TurnContext dataclass
public class TurnContext {
    final InboundMessage msg;
    final String sessionKey;
    final MessageBus bus;
    final LLMProvider provider;
    final ToolRegistry toolRegistry;
    final SessionManager sessionManager;
    final ChannelManager channelManager;  // 用于流式 delta 推送
    Session session;                      // 当前会话（RESTORE 后填充）
    List<Map<String, Object>> messages;   // 上下文消息列表
    AgentRunResult finalResponse;         // RUN 状态后填充
    // 状态追踪
    TurnState currentState = TurnState.RESTORE;
    List<StateTraceEntry> trace = new ArrayList<>();
    boolean cancelled = false;
    // ... builder 模式构造
}
```

### 2. AgentLoop — 主循环

```java
// AgentLoop.java
@Component
public class AgentLoop implements Runnable {
    private final MessageBus bus;
    private final ProviderFactory providerFactory;
    private final AgentRunner agentRunner;
    private final ToolRegistry toolRegistry;
    private final SessionManager sessionManager;
    private final ChannelManager channelManager;
    private final NanobotProperties config;
    private final ExecutorService executor;

    // 对标 Python self._sessions: dict[str, asyncio.Lock]
    private final ConcurrentMap<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();
    // 对标 Python self._semaphore
    private final Semaphore semaphore;

    public AgentLoop(MessageBus bus, ProviderFactory providerFactory,
                     AgentRunner agentRunner, ToolRegistry toolRegistry,
                     SessionManager sessionManager, ChannelManager channelManager,
                     NanobotProperties config) {
        this.bus = bus;
        this.providerFactory = providerFactory;
        this.agentRunner = agentRunner;
        this.toolRegistry = toolRegistry;
        this.sessionManager = sessionManager;
        this.channelManager = channelManager;
        this.config = config;
        this.semaphore = new Semaphore(
            config.agents().defaults().maxConcurrentSubagents());
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /** 对标 Python AgentLoop.run() — 主消费循环 */
    @Override
    public void run() {
        while (!Thread.interrupted()) {
            try {
                var msg = bus.consumeInbound();  // 阻塞 → 虚拟线程自动挂起
                // 对标 asyncio.create_task(self._process_message(msg))
                executor.submit(() -> processMessage(msg));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;  // 优雅退出
            }
        }
    }

    /** 对标 Python _process_message — 管理 session lock + semaphore */
    void processMessage(InboundMessage msg) {
        var lock = sessionLocks.computeIfAbsent(msg.sessionKey(),
            k -> new ReentrantLock());
        lock.lock();
        try {
            semaphore.acquire();
            try {
                runStateMachine(msg);
            } finally {
                semaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
    }

    /** 对标 Python _run_state_machine */
    void runStateMachine(InboundMessage msg) throws InterruptedException {
        var ctx = TurnContext.builder()
            .msg(msg)
            .sessionKey(msg.sessionKey())
            .bus(bus)
            .provider(providerFactory.makeProvider(null, null, null, false))
            .toolRegistry(toolRegistry)
            .sessionManager(sessionManager)
            .build();

        // 对标 Python: 协作取消检查
        for (var state = TurnState.RESTORE;
             state != TurnState.DONE && !Thread.interrupted();
             state = ctx.currentState.next()) {

            ctx.currentState = state;
            long start = System.nanoTime();

            switch (state) {
                case RESTORE -> doRestore(ctx);
                case COMPACT -> doCompact(ctx);
                case COMMAND -> doCommand(ctx);
                case BUILD -> doBuild(ctx);
                case RUN -> doRun(ctx);
                case SAVE -> doSave(ctx);
                case RESPOND -> doRespond(ctx);
            }

            ctx.trace.add(new StateTraceEntry(state, start,
                (System.nanoTime() - start) / 1_000_000, null));
        }
    }

    // === 各状态处理 (私用方法) ===

    void doRestore(TurnContext ctx) {
        ctx.session = sessionManager.loadOrCreate(ctx.sessionKey);
        // 对标 Python: 从 session.history 恢复历史消息
        ctx.messages = new ArrayList<>(ctx.session.history());
    }

    void doCompact(TurnContext ctx) {
        // 对标 _compact_if_needed: 检查 token 数，触发 Consolidator
        int tokens = estimateTokens(ctx.messages);
        int budget = config.agents().defaults().contextWindowTokens();
        if (tokens > budget * config.agents().defaults().consolidationRatio()) {
            // 触发 Consolidator.compact()
        }
    }

    void doCommand(TurnContext ctx) {
        // 斜杠命令检测与路由
        // 对标 command.CommandRouter
    }

    void doBuild(TurnContext ctx) {
        // 对标 ContextBuilder: 拼装 system prompt + tools + history + user message
        ctx.messages = new ContextBuilder(toolRegistry, config)
            .build(ctx.session, ctx.msg);
    }

    void doRun(TurnContext ctx) {
        // 委托给 AgentRunner（Spring 注入）
        var spec = new AgentRunSpec(
            ctx.messages,
            toolRegistry,
            ctx.provider.getDefaultModel(),
            config.agents().defaults().maxToolIterations(),
            config.agents().defaults().maxToolResultChars(),
            config.agents().defaults().temperature(),
            config.agents().defaults().maxTokens(),
            config.agents().defaults().reasoningEffort(),
            null  // hook
        );
        // 流式回调 → 通过 ChannelManager 推送到前端
        var wsChannel = channelManager.getChannel(ctx.msg.channel())
            .filter(c -> c instanceof WebSocketChannel)
            .map(c -> (WebSocketChannel) c)
            .orElse(null);
        var result = agentRunner.run(spec, ctx.provider, config.workspacePath(),
            delta -> {
                if (wsChannel != null) wsChannel.sendDelta(ctx.msg.chatId(), delta);
            }
        );
        ctx.messages = result.messages();
        ctx.finalResponse = result;
    }

    void doSave(TurnContext ctx) {
        sessionManager.save(ctx.sessionKey, ctx.messages);
    }

    void doRespond(TurnContext ctx) {
        var out = new OutboundMessage(
            ctx.msg.channel(), ctx.msg.chatId(),
            ctx.finalResponse.content(),
            ctx.msg.metadata().get("message_id") != null
                ? ctx.msg.metadata().get("message_id").toString() : null,
            List.of(), Map.of(), List.of()
        );
        bus.publishOutbound(out);  // 对标 Python: bus.publish_outbound(out)
    }
}
```

### 3. AgentRunner — LLM 执行循环

```java
// AgentRunner.java
public class AgentRunner {
    private final ToolRegistry toolRegistry;

    /** 对标 Python AgentRunner.run() — 核心 tool-use loop */
    public AgentRunResult run(AgentRunSpec spec, LLMProvider provider,
                               Path workspace,
                               ThrowingConsumer<String> onContentDelta
    ) throws InterruptedException {
        var messages = new ArrayList<>(spec.initialMessages());
        int iteration = 0;

        while (iteration < spec.maxIterations() && !Thread.interrupted()) {
            // 1. 调用 LLM（对标 provider.chat_stream_with_retry — 流式优先）
            var response = provider.chatStreamWithRetry(
                messages,
                spec.tools().getDefinitions(),
                spec.model(),
                spec.maxTokens(), spec.temperature(),
                spec.reasoningEffort(), null,
                onContentDelta, null, null, null,
                spec.retryMode(), null
            );

            // 2. 追加 assistant 消息到历史
            messages.add(buildAssistantMessage(response));

            // 3. 判断是否需要执行工具
            if (!response.shouldExecuteTools()) {
                // 最终回复，追加后返回
                return new AgentRunResult(
                    response.content(), messages, iteration, response.usage());
            }

            // 4. 执行工具
            for (var tc : response.toolCalls()) {
                if (Thread.interrupted()) throw new InterruptedException();

                var prepared = toolRegistry.prepareCall(tc.name(), tc.arguments());
                ToolResult toolResult;
                if (prepared.error() != null) {
                    toolResult = ToolResult.fail(prepared.error());
                } else {
                    try {
                        toolResult = prepared.tool().execute(
                            (Map<String, Object>) prepared.params(),
                            ToolContext.current()  // ThreadLocal
                        );
                    } catch (Exception e) {
                        toolResult = ToolResult.fail("Tool error: " + e.getMessage());
                    }
                }

                // 5. 截断过长结果 (对标 _persist_tool_result)
                if (toolResult.outputAsString().length() > spec.maxToolResultChars()) {
                    var persisted = persistToFile(toolResult, workspace);
                    toolResult = new ToolResult(
                        "[Result too long, saved to " + persisted + "]\n"
                        + toolResult.outputAsString().substring(0, 1000),
                        toolResult.meta(), toolResult.persist(), toolResult.error()
                    );
                }

                // 6. 追加 tool result 消息
                messages.add(Map.of(
                    "role", "tool",
                    "tool_call_id", tc.id(),
                    "content", toolResult.outputAsString()
                ));
            }

            iteration++;
        }

        // max iterations reached → 请求 LLM 最终总结
        var finalResp = provider.chat(messages, null,
            spec.model(), spec.maxTokens(), spec.temperature(), null, null);
        messages.add(buildAssistantMessage(finalResp));
        return new AgentRunResult(
            finalResp.content(), messages, iteration, finalResp.usage());
    }

    private String persistToFile(ToolResult result, Path workspace) { ... }
    private Map<String, Object> buildAssistantMessage(LLMResponse resp) { ... }
}
```

### 4. AgentRunSpec 和 AgentRunResult

```java
// AgentRunSpec.java — 对标 Python 同名 dataclass
public record AgentRunSpec(
    List<Map<String, Object>> initialMessages,
    ToolRegistry tools,
    String model,
    int maxIterations,
    int maxToolResultChars,
    double temperature,
    int maxTokens,
    @Nullable String reasoningEffort,
    @Nullable AgentHook hook,
    @Default String retryMode,       // "standard"
    @Default boolean concurrentTools  // false
) {}

// AgentRunResult.java
public record AgentRunResult(
    @Nullable String content,
    List<Map<String, Object>> messages,
    int iterations,
    Map<String, Integer> usage
) {}
```

### 5. 协作取消协议

对标 Python `asyncio.CancelledError`，Java 用 `Thread.interrupted()`：

```java
// 在每个状态转变前检查
for (var state = TurnState.RESTORE;
     state != TurnState.DONE && !Thread.interrupted();  // ← 中断检查
     state = ctx.currentState.next()) { ... }

// 在 AgentRunner 循环中检查
while (iteration < spec.maxIterations() && !Thread.interrupted()) {
    // LLM 调用在虚拟线程中阻塞 → Thread.interrupt() 会抛 InterruptedException
}

// 在工具执行前检查
for (var tc : response.toolCalls()) {
    if (Thread.interrupted()) throw new InterruptedException();  // ← 手动检查点
    ...
}
```

外部取消（用户中断）:
```java
// 外部线程调用
loopThread.interrupt();  // 设置中断标志
// → _run_state_machine 循环检测到 → 退出
// → 或 _run loop 的 consumeInbound() 处抛 InterruptedException
```

### 6. ContextBuilder

```java
// ContextBuilder.java — 对标 agent/context.py
public class ContextBuilder {
    private final ToolRegistry tools;
    private final NanobotProperties config;

    /**
     * 对标 Python ContextBuilder.build()
     *
     * 拼装完整的 messages 列表发送给 LLM:
     * [system_prompt, ...history, current_user_message]
     */
    public List<Map<String, Object>> build(Session session, InboundMessage msg) {
        var messages = new ArrayList<Map<String, Object>>();

        // 1. System prompt (从 SOUL.md + 内置模板)
        messages.add(Map.of("role", "system", "content", buildSystemPrompt()));

        // 2. 历史消息 (从 session.history)
        messages.addAll(session.history());

        // 3. 当前用户消息（含文档提取）
        var userMsg = new LinkedHashMap<String, Object>();
        userMsg.put("role", "user");
        userMsg.put("content", msg.content());
        messages.add(userMsg);

        return messages;
    }

    private String buildSystemPrompt() {
        // 读取 SOUL.md，拼装工具描述
        var toolDescs = tools.getDefinitions().stream()
            .map(d -> "- " + schemaName(d))
            .collect(Collectors.joining("\n"));
        return "You are a helpful AI assistant.\n\nAvailable tools:\n" + toolDescs;
    }
}
```

## 测试对齐

```java
// AgentLoopTest.java
@SpringBootTest
class AgentLoopTest {

    @MockBean MessageBus bus;
    @MockBean ProviderFactory providerFactory;
    @MockBean SessionManager sessionManager;

    @Test
    void simpleTurnNoTools() throws InterruptedException {
        var provider = mock(LLMProvider.class);
        when(provider.chatWithRetry(any(), any(), any(), anyInt(), anyDouble(),
                any(), any(), any(), any()))
            .thenReturn(new LLMResponse("Hello!", List.of(), "stop", Map.of(),
                null, null, null, null, null, null, null, null, null));

        when(providerFactory.makeProvider(any(), any(), any(), anyBoolean()))
            .thenReturn(provider);
        when(bus.consumeInbound())
            .thenReturn(new InboundMessage("test", "u1", "c1", "hi", null, null, null, null))
            .thenThrow(new InterruptedException());  // stop after one

        var loop = new AgentLoop(bus, providerFactory, ...);
        loop.run();
        // 验证 outbound 被发布
        verify(bus).publishOutbound(argThat(o ->
            o.content().equals("Hello!")));
    }

    @Test
    void toolCallTurn() {
        // 构造 provider 返回 tool_call → 验证工具被调用
    }

    @Test
    void maxIterationsExceeded() {
        // 反复返回 tool_call → 验证最终总结发生
    }

    @Test
    void cancellationStopsProcessing() {
        // 在另一个虚拟线程中 interrupt() → 验证 clean exit
    }
}

// AgentRunnerTest.java
class AgentRunnerTest {
    @Test void emptyMessagesReturnsEmpty() { ... }
    @Test void toolCallExecuted() { ... }
    @Test void toolErrorHandledGraceful() { ... }
    @Test void longToolResultTruncated() { ... }
}
```

## 验证标准

```bash
# 单测
mvn test -Dtest=AgentLoopTest,AgentRunnerTest

# 集成: 用真实 API key 跑一个 turn
NANOBOT_PROVIDERS_ANTHROPIC_API_KEY=sk-ant-xxx mvn test \
    -Dtest=AgentLoopIntegrationTest

# 预期:
# Input:  "What is 2+2?"
# Output: "2+2 = 4"  (LLM 不需要调用工具)
#
# Input:  "Run 'ls' and tell me what you see"
# Output: tool_call(exec) → "I can see the following files: ..."
```

## 代码量估算

- TurnState enum: ~15 行
- TurnContext class: ~50 行
- AgentLoop: ~250 行
- AgentRunner: ~180 行
- AgentRunSpec + AgentRunResult: ~30 行
- ContextBuilder: ~80 行
- 测试: ~300 行
- **合计: ~905 行**
