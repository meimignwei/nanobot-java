# 08 — agent 核心：AgentLoop + AgentRunner + ContextBuilder + Hook

**对标 Python：** `nanobot/agent/loop.py` (1,779行), `runner.py` (1,543行), `context.py` (280行), `hook.py` (106行), `progress_hook.py` (~90行)

## Python 源码分析

### TurnState 状态机 (`loop.py`)

```
enum TurnState: RESTORE → COMPACT → COMMAND → BUILD → RUN → SAVE → RESPOND → DONE

_TRANSITIONS = {
    RESTORE:  → COMPACT | RESPOND,
    COMPACT:  → COMMAND,
    COMMAND:  → BUILD | RESPOND,
    BUILD:    → RUN,
    RUN:      → SAVE,
    SAVE:     → RESPOND,
    RESPOND:  → DONE,
    DONE:     → (结束),
}
```

每个状态有 `_state_<name>()` handler，返回事件字符串驱动状态机转换。

### TurnContext (`loop.py`)

```python
@dataclass
class TurnContext:
    msg: InboundMessage
    session_key: str
    state: TurnState
    turn_id: str
    session: Session
    history: list[dict]
    initial_messages: list[dict]
    final_content: str
    tools_used: list[str]
    all_messages: list[dict]
    stop_reason: str
    had_injections: bool
    user_persisted_early: bool
    save_skip: bool
    outbound: OutboundMessage | None
    suppress_response: bool
    on_progress, on_stream, on_stream_end, on_retry_wait: Callable
    pending_queue: list
    tools: list[dict]
    turn_wall_started_at: float
    trace: list[StateTraceEntry]
```

### AgentLoop 关键方法

```
AgentLoop:
    __init__(bus, workspace, model, provider, tool_registry,
             session_manager, memory_store, consolidator,
             channel_manager, command_router, ...)  # ~30 个参数

    from_config(config, ...)  ← 静态工厂

    async run()
        while True:
            msg = await bus.consume_inbound()
            _process_message(msg)  → state machine

    async _process_message(msg)
        turn = TurnContext(msg=sanitized_message, state=RESTORE, ...)
        while state != DONE:
            handler = TRANSITIONS[state]
            event = await handler(turn)
            state = TRANSITIONS[state][event]

    _state_restore(turn)  → "loaded" | "respond"
    _state_compact(turn)  → "done"
    _state_command(turn)  → "handled" | "not_handled"
    _state_build(turn)    → "ready"
    _state_run(turn)      → "done" | "error"
    _state_save(turn)     → "saved"
    _state_respond(turn)  → "sent" | "complete"
```

### AgentRunner (`runner.py`)

```python
AgentRunner:
    @staticmethod
    async def run(spec: AgentRunSpec) -> AgentRunResult

AgentRunSpec:
    initial_messages, tools, model, max_iterations,
    max_tool_result_chars, temperature, max_tokens,
    reasoning_effort, hook, error_message,
    max_iterations_message, concurrent_tools,
    fail_on_tool_error, workspace, session_key,
    context_window_tokens, context_block_limit,
    provider_retry_mode, progress_callback,
    stream_progress_deltas, retry_wait_callback,
    checkpoint_callback, injection_callback,
    llm_timeout_s, goal_active_predicate,
    goal_continue_message, finalize_on_max_iterations

AgentRunResult:
    final_content, messages, tools_used, usage,
    stop_reason, error, tool_events, had_injections
```

Runner 核心循环：
```
loop:
    check_interrupt()                          ← CancelledError → InterruptedException
    response = provider.chat(messages, tools)  ← 调用 LLM
    if response.error and should_retry:        ← retry
        continue
    if tool_calls:
        execute_tools(tool_calls)               ← 并行或串行
        append tool_results to messages
        continue                                ← 继续下一轮
    else:
        final_content = response.content
        break                                   ← 结束
```

### ContextBuilder (`context.py`)

组装 system prompt：身份信息 → AGENTS.md / USER.md / SOUL.md → memory → skills → runtime context → 图片编码。

### Hook 系统 (`hook.py`)

```python
class AgentHook(ABC):
    async before_run(ctx), after_run(ctx)
    async before_iteration(ctx), after_iteration(ctx)
    async on_stream(delta), finalize_content(raw)

class CompositeHook(AgentHook):  # 链式多个 hook
class AgentProgressHook(AgentHook):  # bridge to bus
```

## Java 实现方案

### 1. TurnState 枚举 + 状态机

```java
package com.nanobot.agent;

public enum TurnState {
    RESTORE,
    COMPACT,
    COMMAND,
    BUILD,
    RUN,
    SAVE,
    RESPOND,
    DONE;
}

public final class TurnStateTransitions {

    private static final EnumMap<TurnState, EnumMap<String, TurnState>> TRANSITIONS;

    static {
        TRANSITIONS = new EnumMap<>(TurnState.class);
        TRANSITIONS.put(RESTORE, mapOf("loaded", COMPACT, "respond", RESPOND));
        TRANSITIONS.put(COMPACT, mapOf("done", COMMAND));
        TRANSITIONS.put(COMMAND, mapOf("handled", BUILD, "not_handled", RESPOND));
        TRANSITIONS.put(BUILD, mapOf("ready", RUN));
        TRANSITIONS.put(RUN, mapOf("done", SAVE, "error", RESPOND));
        TRANSITIONS.put(SAVE, mapOf("saved", RESPOND));
        TRANSITIONS.put(RESPOND, mapOf("done", DONE));
        TRANSITIONS.put(DONE, new EnumMap<>(String.class));
    }

    public static TurnState next(TurnState current, String event) {
        EnumMap<String, TurnState> nextStates = TRANSITIONS.get(current);
        TurnState next = nextStates.get(event);
        if (next == null) {
            throw new IllegalStateException(
                "No transition for state " + current + " with event '" + event + "'");
        }
        return next;
    }

    private static <K extends Enum<K>, V> EnumMap<K, V> mapOf(Object... pairs) {
        EnumMap<K, V> map = new EnumMap<>(pairs[0].getClass());
        for (int i = 0; i < pairs.length; i += 2) {
            @SuppressWarnings("unchecked")
            K key = (K) pairs[i];
            @SuppressWarnings("unchecked")
            V value = (V) pairs[i + 1];
            map.put(key, value);
        }
        return map;
    }
}
```

### 2. AgentLoop.java

```java
package com.nanobot.agent;

import com.nanobot.agent.tools.ToolRegistry;
import com.nanobot.bus.*;
import com.nanobot.command.CommandRouter;
import com.nanobot.providers.*;
import com.nanobot.session.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

@Slf4j
@RequiredArgsConstructor
public class AgentLoop implements Runnable {

    private final MessageBus bus;
    private final LLMProvider provider;
    private final ToolRegistry toolRegistry;
    private final SessionManager sessionManager;
    private final MemoryStore memoryStore;
    private final Consolidator consolidator;
    private final CommandRouter commandRouter;
    private final SkillsLoader skillsLoader;
    private final ContextBuilder contextBuilder;
    // ... 更多依赖

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                InboundMessage msg = bus.consumeInbound();  // 阻塞
                processMessage(msg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error processing message", e);
            }
        }
    }

    public void processMessage(InboundMessage inboundMsg) throws InterruptedException {
        InboundMessage sanitized = sanitizeMessage(inboundMsg);
        String sessionKey = resolveSessionKey(sanitized);

        TurnContext turn = TurnContext.builder()
            .msg(sanitized)
            .sessionKey(sessionKey)
            .state(TurnState.RESTORE)
            .turnId(UUID.randomUUID().toString())
            .turnWallStartedAt(System.currentTimeMillis())
            .trace(new ArrayList<>())
            .build();

        TurnState state = TurnState.RESTORE;
        while (state != TurnState.DONE) {
            // 协作取消检查
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }

            String event;
            switch (state) {
                case RESTORE: event = stateRestore(turn); break;
                case COMPACT: event = stateCompact(turn); break;
                case COMMAND: event = stateCommand(turn); break;
                case BUILD:   event = stateBuild(turn); break;
                case RUN:     event = stateRun(turn); break;
                case SAVE:    event = stateSave(turn); break;
                case RESPOND: event = stateRespond(turn); break;
                default:      return;
            }
            state = TurnStateTransitions.next(state, event);
        }
    }

    // === 状态处理器 ===

    private String stateRestore(TurnContext turn) {
        // 加载 session 历史
        Session session = sessionManager.getOrCreate(turn.getSessionKey());
        turn.setSession(session);

        // 从记忆恢复
        List<Map<String, Object>> history = new ArrayList<>();
        memoryStore.loadMemory(history);

        // 检查 autocompact
        AutoCompact.maybeCompact(session);

        turn.setHistory(history);
        return "loaded";
    }

    private String stateCommand(TurnContext turn) {
        // 检查是否以 / 开头的命令
        boolean handled = commandRouter.dispatch(turn.getMsg().content(), turn);
        return handled ? "handled" : "not_handled";
    }

    private String stateBuild(TurnContext turn) {
        // 构建 context
        List<Map<String, Object>> messages = contextBuilder.build(
            turn.getHistory(),
            turn.getMsg(),
            skillsLoader,
            toolRegistry
        );
        turn.setInitialMessages(Collections.unmodifiableList(messages));
        return "ready";
    }

    private String stateRun(TurnContext turn) {
        // 构建 run spec
        AgentRunSpec spec = AgentRunSpec.builder()
            .initialMessages(turn.getInitialMessages())
            .tools(toolRegistry.getDefinitions())
            .model(resolveModel(turn))
            .maxIterations(properties.agents().defaults().maxToolIterations())
            .temperature(properties.agents().defaults().temperature())
            .maxTokens(properties.agents().defaults().maxTokens())
            .providerRetryMode(properties.agents().defaults().providerRetryMode())
            .hook(buildHooks(turn))
            .build();

        AgentRunResult result = AgentRunner.run(spec);

        turn.setFinalContent(result.finalContent());
        turn.setAllMessages(result.messages());
        turn.setToolsUsed(result.toolsUsed());
        turn.setStopReason(result.stopReason());
        turn.setHadInjections(result.hadInjections());

        if (result.error() != null) {
            log.error("Runner error: {}", result.error());
            return "error";
        }
        return "done";
    }

    // ... 更多方法
}
```

### 3. AgentRunner.java

```java
package com.nanobot.agent;

import com.nanobot.agent.tools.ToolRegistry;
import com.nanobot.providers.*;
import java.util.*;

public final class AgentRunner {

    /** 对标 Python AgentRunner.run() — 核心 LLM 对话循环 */
    public static AgentRunResult run(AgentRunSpec spec) {
        List<Map<String, Object>> messages = new ArrayList<>(spec.initialMessages());
        List<String> toolsUsed = new ArrayList<>();
        Map<String, Integer> totalUsage = new HashMap<>();
        boolean hadInjections = false;
        String finalContent = null;
        String stopReason = null;
        String error = null;

        int iteration = 0;
        while (iteration < spec.maxIterations()) {
            // 协作取消检查
            if (Thread.interrupted()) {
                return AgentRunResult.builder()
                    .error("Interrupted")
                    .stopReason("cancelled")
                    .messages(messages)
                    .toolsUsed(toolsUsed)
                    .build();
            }

            iteration++;

            // Hook: before_iteration
            if (spec.hook() != null) {
                spec.hook().beforeIteration(messages, iteration);
            }

            // 调用 LLM
            LLMResponse response = spec.provider().chatWithRetry(
                messages,
                spec.tools(),
                spec.model(),
                spec.maxTokens(),
                spec.temperature(),
                spec.reasoningEffort(),
                spec.providerRetryMode()
            );

            // 累积 usage
            if (response.usage() != null) {
                response.usage().forEach((k, v) ->
                    totalUsage.merge(k, v, Integer::sum));
            }

            // 检查错误
            if (response.isError() && response.errorShouldRetry()) {
                continue;  // 重试循环处理
            }

            // 检查 tool calls
            if (response.hasToolCalls()) {
                for (ToolCallRequest tc : response.toolCalls()) {
                    // 执行 tool
                    Object result = spec.toolRegistry().execute(tc.name(), tc.arguments());
                    toolsUsed.add(tc.name());

                    // 追加 assistant + tool result 消息
                    Map<String, Object> toolResultMsg = new LinkedHashMap<>();
                    toolResultMsg.put("role", "tool");
                    toolResultMsg.put("tool_call_id", tc.id());
                    toolResultMsg.put("content", truncateToolResult(result, spec.maxToolResultChars()));
                    messages.add(toolResultMsg);
                }
                continue;  // 继续下一轮
            }

            // 无 tool call: 最终回复
            finalContent = response.content();
            stopReason = response.finishReason();

            // Hook: finalize_content
            if (spec.hook() != null) {
                finalContent = spec.hook().finalizeContent(finalContent);
            }
            break;
        }

        if (iteration >= spec.maxIterations()) {
            finalContent = spec.maxIterationsMessage();
            stopReason = "max_iterations";
        }

        return AgentRunResult.builder()
            .finalContent(finalContent)
            .messages(messages)
            .toolsUsed(toolsUsed)
            .usage(totalUsage)
            .stopReason(stopReason)
            .error(error)
            .hadInjections(hadInjections)
            .build();
    }

    private static String truncateToolResult(Object result, int maxChars) {
        String text = result instanceof String
            ? (String) result
            : Objects.toString(result);
        if (text.length() > maxChars) {
            return text.substring(0, maxChars) + "\n... [truncated]";
        }
        return text;
    }
}
```

### 4. AgentRunSpec / AgentRunResult — Builder Pattern

```java
package com.nanobot.agent;

import com.nanobot.agent.tools.ToolRegistry;
import com.nanobot.providers.LLMProvider;
import java.util.List;
import java.util.Map;

public class AgentRunSpec {

    private final List<Map<String, Object>> initialMessages;
    private final List<Map<String, Object>> tools;
    private final String model;
    private final int maxIterations;
    private final int maxToolResultChars;
    private final double temperature;
    private final int maxTokens;
    private final String reasoningEffort;
    private final AgentHook hook;
    private final String errorMessage;
    private final String maxIterationsMessage;
    private final boolean concurrentTools;
    private final boolean failOnToolError;
    private final String providerRetryMode;
    private final String sessionKey;
    private final int contextWindowTokens;
    private final ToolRegistry toolRegistry;
    private final LLMProvider provider;
    // ... 更多字段

    // 使用 Builder 模式（Lombok @Builder 或手写）
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private List<Map<String, Object>> initialMessages;
        private List<Map<String, Object>> tools;
        private String model;
        private int maxIterations = 200;
        private int maxToolResultChars = 100_000;
        private double temperature = 0.1;
        private int maxTokens = 8192;
        private String reasoningEffort;
        private AgentHook hook;
        private String errorMessage = "An error occurred during processing.";
        private String maxIterationsMessage = "Reached maximum tool iterations.";
        private boolean concurrentTools = false;
        private boolean failOnToolError = false;
        private String providerRetryMode = "standard";
        private String sessionKey;
        private int contextWindowTokens = 65536;
        private ToolRegistry toolRegistry;
        private LLMProvider provider;

        public Builder initialMessages(List<Map<String, Object>> v) { this.initialMessages = v; return this; }
        public Builder tools(List<Map<String, Object>> v) { this.tools = v; return this; }
        public Builder model(String v) { this.model = v; return this; }
        public Builder maxIterations(int v) { this.maxIterations = v; return this; }
        public Builder maxToolResultChars(int v) { this.maxToolResultChars = v; return this; }
        public Builder temperature(double v) { this.temperature = v; return this; }
        public Builder maxTokens(int v) { this.maxTokens = v; return this; }
        public Builder reasoningEffort(String v) { this.reasoningEffort = v; return this; }
        public Builder hook(AgentHook v) { this.hook = v; return this; }
        public Builder toolRegistry(ToolRegistry v) { this.toolRegistry = v; return this; }
        public Builder provider(LLMProvider v) { this.provider = v; return this; }
        // ...

        public AgentRunSpec build() {
            return new AgentRunSpec(this);
        }
    }

    private AgentRunSpec(Builder b) {
        this.initialMessages = b.initialMessages;
        this.tools = b.tools;
        this.model = b.model;
        // ...
    }

    // getters
}
```

### 5. AgentHook + CompositeHook

```java
package com.nanobot.agent;

import java.util.List;
import java.util.Map;

public interface AgentHook {

    default void beforeRun(List<Map<String, Object>> messages) {}

    default void afterRun(AgentRunHookContext context) {}

    default void beforeIteration(List<Map<String, Object>> messages, int iteration) {}

    default void afterIteration(AgentHookContext context) {}

    default void onContentDelta(String delta) {}

    default void onReasoningDelta(String delta) {}

    default String finalizeContent(String raw) { return raw; }

    // Composite
    static AgentHook composite(AgentHook... hooks) {
        return new CompositeHook(List.of(hooks));
    }
}

class CompositeHook implements AgentHook {
    private final List<AgentHook> hooks;

    CompositeHook(List<AgentHook> hooks) { this.hooks = List.copyOf(hooks); }

    @Override
    public void beforeRun(List<Map<String, Object>> messages) {
        for (AgentHook hook : hooks) hook.beforeRun(messages);
    }

    @Override
    public void afterRun(AgentRunHookContext context) {
        for (AgentHook hook : hooks) hook.afterRun(context);
    }

    @Override
    public void onContentDelta(String delta) {
        for (AgentHook hook : hooks) hook.onContentDelta(delta);
    }

    @Override
    public String finalizeContent(String raw) {
        String result = raw;
        for (AgentHook hook : hooks) result = hook.finalizeContent(result);
        return result;
    }
    // ... 所有方法委托给每个 hook
}
```

### 6. AgentProgressHook

```java
package com.nanobot.agent;

import com.nanobot.bus.MessageBus;
import com.nanobot.bus.OutboundMessage;

public class AgentProgressHook implements AgentHook {

    private final MessageBus bus;
    private final String channel;
    private final String chatId;

    public AgentProgressHook(MessageBus bus, String channel, String chatId) {
        this.bus = bus;
        this.channel = channel;
        this.chatId = chatId;
    }

    @Override
    public void onContentDelta(String delta) {
        OutboundMessage msg = new OutboundMessage(
            channel, chatId, delta, null, null,
            Map.of("_stream_delta", true)
        );
        try {
            bus.publishOutbound(msg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### 7. ContextBuilder

```java
package com.nanobot.agent;

import com.nanobot.agent.tools.ToolRegistry;
import com.nanobot.bus.InboundMessage;
import java.util.*;

/**
 * 对标 Python context.py — 组装 system prompt + 初始 messages.
 */
public class ContextBuilder {

    private final MemoryStore memoryStore;
    private final SkillsLoader skillsLoader;
    private final String botName;
    private final String botIcon;
    // ...

    public List<Map<String, Object>> build(
            List<Map<String, Object>> history,
            InboundMessage inboundMsg,
            List<Map<String, Object>> tools) {

        List<Map<String, Object>> messages = new ArrayList<>();

        // 1. System prompt
        String systemPrompt = buildSystemPrompt(tools);
        Map<String, Object> systemMsg = new LinkedHashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.add(systemMsg);

        // 2. History (从 memory + session)
        messages.addAll(history);

        // 3. 当前用户消息
        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", inboundMsg.content());
        if (inboundMsg.media() != null && !inboundMsg.media().isEmpty()) {
            userMsg.put("content", buildMultiModalContent(inboundMsg));
        }
        messages.add(userMsg);

        return messages;
    }

    private String buildSystemPrompt(List<Map<String, Object>> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are ").append(botName).append(", ");
        sb.append("a helpful AI assistant.\n\n");

        // Bootstrap files: AGENTS.md, USER.md, SOUL.md
        String agentsMd = memoryStore.loadBootstrapFile("AGENTS.md");
        if (agentsMd != null) {
            sb.append("<agent_instructions>\n").append(agentsMd)
              .append("\n</agent_instructions>\n\n");
        }
        // ... 更多上下文

        return sb.toString();
    }
}
```

## 关键设计决策

### asyncio → Virtual Threads

AgentLoop.run() 在虚拟线程中执行，通过 `LinkedBlockingQueue.take()` 阻塞等待消息。无需 `async/await`。

### CancelledError → InterruptedException

Python 的 `asyncio.CancelledError` 在 Java 中映射为 `InterruptedException`。在每次状态机转换前调用 `Thread.interrupted()` 检查取消。

### AgentRunSpec — Builder vs Record

使用 Builder 模式而非 Java record，因为字段太多（~25个），大部分有默认值。

### Hook 链

Python `CompositeHook` → Java `CompositeHook implements AgentHook`，使用 List 存储 hooks 并委托。

## 验证标准

```bash
# 构造最小的 turn 处理
# 1. 初始化 MessageBus + MemoryStore + ToolRegistry
# 2. 创建 AgentRunSpec，只带 model + messages（无 tools）
# 3. 调用 AgentRunner.run()
# 4. 验证返回 AgentRunResult.finalContent 非空
```

## 代码量估算

| 文件 | 行数 |
|------|------|
| TurnState.java | ~30 |
| TurnStateTransitions.java | ~60 |
| TurnContext.java (class + builder) | ~100 |
| StateTraceEntry.java (record) | ~15 |
| AgentLoop.java | ~500 |
| AgentRunner.java | ~250 |
| AgentRunSpec.java | ~200 |
| AgentRunResult.java (record) | ~20 |
| AgentHook.java (interface) | ~30 |
| CompositeHook.java | ~80 |
| AgentProgressHook.java | ~50 |
| AgentHookContext.java (record) | ~20 |
| AgentRunHookContext.java (record) | ~20 |
| ContextBuilder.java | ~200 |
| **合计** | **~1,575** |
