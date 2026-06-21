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

    _state_restore(turn)  → "ok"
    _state_compact(turn)  → "ok"
    _state_command(turn)  → "dispatch" | "shortcut"
    _state_build(turn)    → "ok"
    _state_run(turn)      → "ok"
    _state_save(turn)     → "ok"
    _state_respond(turn)  → "ok"
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

    private static final EnumMap<TurnState, Map<String, TurnState>> TRANSITIONS;

    static {
        TRANSITIONS = new EnumMap<>(TurnState.class);
        TRANSITIONS.put(RESTORE, mapOf("ok", COMPACT));
        TRANSITIONS.put(COMPACT, mapOf("ok", COMMAND));
        TRANSITIONS.put(COMMAND, mapOf("dispatch", BUILD, "shortcut", DONE));
        TRANSITIONS.put(BUILD, mapOf("ok", RUN));
        TRANSITIONS.put(RUN, mapOf("ok", SAVE));
        TRANSITIONS.put(SAVE, mapOf("ok", RESPOND));
        TRANSITIONS.put(RESPOND, mapOf("ok", DONE));
        TRANSITIONS.put(DONE, new LinkedHashMap<>());
    }

    public static TurnState next(TurnState current, String event) {
        Map<String, TurnState> nextStates = TRANSITIONS.get(current);
        TurnState next = nextStates.get(event);
        if (next == null) {
            throw new IllegalStateException(
                "No transition for state " + current + " with event '" + event + "'");
        }
        return next;
    }

    private static Map<String, TurnState> mapOf(Object... pairs) {
        Map<String, TurnState> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            String key = (String) pairs[i];
            TurnState value = (TurnState) pairs[i + 1];
            map.put(key, value);
        }
        return map;
    }
}
```

### 1a. 支撑数据类

```java
package com.nanobot.agent;

import com.nanobot.bus.InboundMessage;
import com.nanobot.bus.OutboundMessage;
import com.nanobot.providers.LLMResponse;
import com.nanobot.providers.ToolCallRequest;
import com.nanobot.session.Session;
import java.util.*;

/**
 * 对标 Python {@code StateTraceEntry} — 记录每个状态的耗时与事件。
 */
public record StateTraceEntry(
    TurnState state,
    long startedAtNanos,
    long durationMs,
    String event,
    String error
) {
    public StateTraceEntry(TurnState state, long startedAtNanos, long durationMs, String event) {
        this(state, startedAtNanos, durationMs, event, null);
    }
}

/**
 * 对标 Python {@code TurnContext} — 单轮 turn 的 mutable 上下文。
 */
public class TurnContext {

    private InboundMessage msg;
    private String sessionKey;
    private TurnState state;
    private final String turnId;
    private Session session;

    private List<Map<String, Object>> history = new ArrayList<>();
    private List<Map<String, Object>> initialMessages = new ArrayList<>();

    private String finalContent;
    private List<String> toolsUsed = new ArrayList<>();
    private List<Map<String, Object>> allMessages = new ArrayList<>();
    private String stopReason = "";
    private boolean hadInjections = false;

    private boolean userPersistedEarly = false;
    private int saveSkip = 0;

    private OutboundMessage outbound;
    private boolean suppressResponse = false;

    // 回调在 Python 中是 Callable[..., Awaitable[None]]；Java 侧用 CompletableFuture 函数接口
    private java.util.function.BiFunction<String, Map<String, Object>, CompletableFuture<Void>> onProgress;
    private java.util.function.Function<String, CompletableFuture<Void>> onStream;
    private java.util.function.Function<Boolean, CompletableFuture<Void>> onStreamEnd;
    private java.util.function.Function<String, CompletableFuture<Void>> onRetryWait;

    private java.util.Queue<InboundMessage> pendingQueue;
    private String pendingSummary;

    private boolean ephemeral = false;
    private com.nanobot.agent.tools.ToolRegistry tools;

    private final long turnWallStartedAt;
    private Long visibleRunStartedAt;
    private Integer turnLatencyMs;

    private final List<StateTraceEntry> trace = new ArrayList<>();

    // 使用 Lombok @Data + @Builder 自动生成 getter/setter/builder
    // 以下列出核心业务字段的 getter/setter（其余字段同模式）

    public InboundMessage getMsg() { return msg; }
    public void setMsg(InboundMessage msg) { this.msg = msg; }

    public String getSessionKey() { return sessionKey; }
    public void setSessionKey(String sessionKey) { this.sessionKey = sessionKey; }

    public TurnState getState() { return state; }
    public void setState(TurnState state) { this.state = state; }

    public String getTurnId() { return turnId; }

    public Session getSession() { return session; }
    public void setSession(Session session) { this.session = session; }

    public List<Map<String, Object>> getHistory() { return history; }
    public void setHistory(List<Map<String, Object>> history) { this.history = history; }

    public List<Map<String, Object>> getInitialMessages() { return initialMessages; }
    public void setInitialMessages(List<Map<String, Object>> initialMessages) { this.initialMessages = initialMessages; }

    public String getFinalContent() { return finalContent; }
    public void setFinalContent(String finalContent) { this.finalContent = finalContent; }

    public List<String> getToolsUsed() { return toolsUsed; }
    public void setToolsUsed(List<String> toolsUsed) { this.toolsUsed = toolsUsed; }

    public List<Map<String, Object>> getAllMessages() { return allMessages; }
    public void setAllMessages(List<Map<String, Object>> allMessages) { this.allMessages = allMessages; }

    public String getStopReason() { return stopReason; }
    public void setStopReason(String stopReason) { this.stopReason = stopReason; }

    public boolean isHadInjections() { return hadInjections; }
    public void setHadInjections(boolean hadInjections) { this.hadInjections = hadInjections; }

    public boolean isUserPersistedEarly() { return userPersistedEarly; }
    public void setUserPersistedEarly(boolean userPersistedEarly) { this.userPersistedEarly = userPersistedEarly; }

    public int getSaveSkip() { return saveSkip; }
    public void setSaveSkip(int saveSkip) { this.saveSkip = saveSkip; }

    public OutboundMessage getOutbound() { return outbound; }
    public void setOutbound(OutboundMessage outbound) { this.outbound = outbound; }

    public boolean isSuppressResponse() { return suppressResponse; }
    public void setSuppressResponse(boolean suppressResponse) { this.suppressResponse = suppressResponse; }

    public java.util.Queue<InboundMessage> getPendingQueue() { return pendingQueue; }
    public void setPendingQueue(java.util.Queue<InboundMessage> pendingQueue) { this.pendingQueue = pendingQueue; }

    public String getPendingSummary() { return pendingSummary; }
    public void setPendingSummary(String pendingSummary) { this.pendingSummary = pendingSummary; }

    public boolean isEphemeral() { return ephemeral; }
    public void setEphemeral(boolean ephemeral) { this.ephemeral = ephemeral; }

    public com.nanobot.agent.tools.ToolRegistry getTools() { return tools; }
    public void setTools(com.nanobot.agent.tools.ToolRegistry tools) { this.tools = tools; }

    public long getTurnWallStartedAt() { return turnWallStartedAt; }

    public Long getVisibleRunStartedAt() { return visibleRunStartedAt; }
    public void setVisibleRunStartedAt(Long visibleRunStartedAt) { this.visibleRunStartedAt = visibleRunStartedAt; }

    public Integer getTurnLatencyMs() { return turnLatencyMs; }
    public void setTurnLatencyMs(Integer turnLatencyMs) { this.turnLatencyMs = turnLatencyMs; }

    public List<StateTraceEntry> getTrace() { return trace; }
}

/**
 * 对标 Python {@code AgentHookContext} — 单 iteration 的 mutable 状态。
 */
public class AgentHookContext {
    public int iteration;
    public List<Map<String, Object>> messages;
    public LLMResponse response;
    public Map<String, Integer> usage = new HashMap<>();
    public List<ToolCallRequest> toolCalls = new ArrayList<>();
    public List<Object> toolResults = new ArrayList<>();
    public List<Map<String, String>> toolEvents = new ArrayList<>();
    public boolean streamedContent = false;
    public boolean streamedReasoning = false;
    public String finalContent;
    public String stopReason;
    public String error;
    public String sessionKey;
}

/**
 * 对标 Python {@code AgentRunHookContext} — run 级别的状态快照。
 */
public class AgentRunHookContext {
    public List<Map<String, Object>> messages;
    public String finalContent;
    public List<String> toolsUsed = new ArrayList<>();
    public Map<String, Integer> usage = new HashMap<>();
    public String stopReason;
    public String error;
    public List<Map<String, String>> toolEvents = new ArrayList<>();
    public boolean hadInjections = false;
    public Throwable exception;
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
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class AgentLoop {

    private static final String UNIFIED_SESSION_KEY = "unified:default";

    private final MessageBus bus;
    private final LLMProvider provider;
    private final ToolRegistry toolRegistry;
    private final SessionManager sessionManager;
    private final MemoryStore memoryStore;
    private final Consolidator consolidator;
    private final CommandRouter commandRouter;
    private final SkillsLoader skillsLoader;
    private final ContextBuilder contextBuilder;
    private final AgentRunner runner;
    private final AutoCompact autoCompact;
    private final String model;
    private final int maxIterations;
    private final int maxToolResultChars;
    private final String providerRetryMode;
    private final int maxMessages;
    private final boolean unifiedSession;
    private final int contextWindowTokens;
    private final RuntimeEventBus runtimeEvents;

    // 并发控制
    private final Map<String, Lock> sessionLocks = new ConcurrentHashMap<>();
    private final Semaphore concurrencyGate;
    private final Map<String, BlockingQueue<InboundMessage>> pendingQueues = new ConcurrentHashMap<>();
    private final Map<String, List<CompletableFuture<Void>>> activeTasks = new ConcurrentHashMap<>();

    private volatile boolean running = false;

    public AgentLoop(MessageBus bus, LLMProvider provider, Path workspace, String model,
                     Integer maxIterations, Integer contextWindowTokens, Integer maxToolResultChars,
                     String providerRetryMode, Integer maxMessages, boolean unifiedSession,
                     SessionManager sessionManager, Consolidator consolidator,
                     CommandRouter commandRouter, SkillsLoader skillsLoader,
                     ContextBuilder contextBuilder, AutoCompact autoCompact,
                     RuntimeEventBus runtimeEvents, ToolRegistry toolRegistry) {
        this.bus = bus;
        this.provider = provider;
        this.model = model != null ? model : provider.getDefaultModel();
        this.maxIterations = maxIterations != null ? maxIterations : 200;
        this.contextWindowTokens = contextWindowTokens != null ? contextWindowTokens : 65536;
        this.maxToolResultChars = maxToolResultChars != null ? maxToolResultChars : 100_000;
        this.providerRetryMode = providerRetryMode != null ? providerRetryMode : "standard";
        this.maxMessages = maxMessages != null && maxMessages > 0 ? maxMessages : 120;
        this.unifiedSession = unifiedSession;
        this.sessionManager = sessionManager;
        this.consolidator = consolidator;
        this.commandRouter = commandRouter;
        this.skillsLoader = skillsLoader;
        this.contextBuilder = contextBuilder;
        this.autoCompact = autoCompact;
        this.runtimeEvents = runtimeEvents;
        this.toolRegistry = toolRegistry;
        this.runner = new AgentRunner(provider);
        int maxConcurrent = Integer.parseInt(System.getenv().getOrDefault("NANOBOT_MAX_CONCURRENT_REQUESTS", "3"));
        this.concurrencyGate = maxConcurrent > 0 ? new Semaphore(maxConcurrent) : null;
    }

    /**
     * 核心消息循环。对标 Python {@code async def run()}。
     */
    public CompletableFuture<Void> run() {
        running = true;
        return _connectMcp()
            .thenCompose(_ -> {
                log.info("Agent loop started");
                return runLoop();
            });
    }

    private CompletableFuture<Void> runLoop() {
        if (!running) {
            return CompletableFuture.completedFuture(null);
        }
        return bus.consumeInbound()
            .orTimeout(1, TimeUnit.SECONDS)
            .thenCompose(msg -> processSingleMessage(msg))
            .exceptionally(ex -> {
                Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
                if (cause instanceof TimeoutException) {
                    autoCompact.checkExpired(
                        this::_scheduleBackground,
                        activeSessionKeys: pendingQueues.keySet()
                    );
                    return null;
                }
                if (cause instanceof CancellationException) {
                    if (!running) {
                        throw (CancellationException) cause;
                    }
                    return null;
                }
                log.warn("Error consuming inbound message: {}, continuing...", cause.getMessage());
                return null;
            })
            .thenCompose(_ -> runLoop());
    }

    private CompletableFuture<Void> processSingleMessage(InboundMessage msg) {
        String raw = msg.content().strip();
        String effectiveKey = _effectiveSessionKey(msg);

        // runtime control (e.g. /stop)
        return agentContextHandleRuntimeControl(msg, toolRegistry)
            .thenApply(handled -> {
                if (handled) return true;
                if (commandRouter.isPriority(raw)) {
                    _dispatchCommandInline(msg, effectiveKey, raw, commandRouter::dispatchPriority);
                    return true;
                }
                return false;
            })
            .thenCompose(handled -> {
                if (handled) return CompletableFuture.completedFuture(null);

                // pending queue routing
                if (pendingQueues.containsKey(effectiveKey)) {
                    if (commandRouter.isDispatchableCommand(raw)) {
                        return _dispatchCommandInline(msg, effectiveKey, raw, commandRouter::dispatch);
                    }
                    InboundMessage pendingMsg = msg;
                    if (!effectiveKey.equals(msg.sessionKey())) {
                        pendingMsg = msg.withSessionKeyOverride(effectiveKey);
                    }
                    BlockingQueue<InboundMessage> queue = pendingQueues.get(effectiveKey);
                    if (queue != null) {
                        boolean offered = queue.offer(pendingMsg);
                        if (!offered) {
                            log.warn("Pending queue full for session {}, falling back to queued task", effectiveKey);
                        } else {
                            log.info("Routed follow-up message to pending queue for session {}", effectiveKey);
                            return CompletableFuture.completedFuture(null);
                        }
                    }
                }

                // create dispatch task
                CompletableFuture<Void> task = _dispatch(msg);
                activeTasks.computeIfAbsent(effectiveKey, k -> new ArrayList<>()).add(task);
                task.whenComplete((_, __) -> {
                    List<CompletableFuture<Void>> tasks = activeTasks.get(effectiveKey);
                    if (tasks != null) tasks.remove(task);
                });
                return task;
            });
    }

    public void stop() {
        running = false;
        log.info("Agent loop stopping");
    }

    /**
     * 对标 Python {@code async def _dispatch()} — per-session serial, cross-session concurrent.
     */
    private CompletableFuture<Void> _dispatch(InboundMessage msg) {
        String sessionKey = _effectiveSessionKey(msg);
        InboundMessage effectiveMsg = sessionKey.equals(msg.sessionKey()) ? msg
            : msg.withSessionKeyOverride(sessionKey);

        Lock lock = sessionLocks.computeIfAbsent(sessionKey, k -> new ReentrantLock());
        BlockingQueue<InboundMessage> pending = new ArrayBlockingQueue<>(20);
        pendingQueues.put(sessionKey, pending);

        return CompletableFuture.runAsync(() -> {
            if (concurrencyGate != null) {
                try {
                    concurrencyGate.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
            lock.lock();
        }).thenCompose(_ -> {
            // streaming callbacks
            boolean wantsStream = Boolean.TRUE.equals(msg.metadata().get("_wants_stream"));
            AtomicInteger streamSegment = new AtomicInteger(0);
            String streamBaseId = msg.sessionKey() + ":" + System.nanoTime();

            Function<String, CompletableFuture<Void>> onStream = null;
            Function<Boolean, CompletableFuture<Void>> onStreamEnd = null;

            if (wantsStream) {
                onStream = delta -> {
                    Map<String, Object> meta = new HashMap<>(msg.metadata() != null ? msg.metadata() : Map.of());
                    meta.put("_stream_delta", true);
                    meta.put("_stream_id", streamBaseId + ":" + streamSegment.get());
                    return bus.publishOutbound(new OutboundMessage(
                        msg.channel(), msg.chatId(), delta, null, null, meta
                    ));
                };
                onStreamEnd = resuming -> {
                    Map<String, Object> meta = new HashMap<>(msg.metadata() != null ? msg.metadata() : Map.of());
                    meta.put("_stream_end", true);
                    meta.put("_resuming", resuming);
                    meta.put("_stream_id", streamBaseId + ":" + streamSegment.get());
                    streamSegment.incrementAndGet();
                    return bus.publishOutbound(new OutboundMessage(
                        msg.channel(), msg.chatId(), "", null, null, meta
                    ));
                };
            }

            return _processMessage(effectiveMsg, onStream, onStreamEnd, pending)
                .thenCompose(response -> {
                    String completedChannel = msg.channel();
                    String completedChatId = msg.chatId();
                    if (response != null) {
                        return bus.publishOutbound(response)
                            .thenApply(_ -> {
                                return Map.entry(response.channel(), response.chatId());
                            });
                    } else if ("cli".equals(msg.channel())) {
                        return bus.publishOutbound(new OutboundMessage(
                            msg.channel(), msg.chatId(), "", null, null,
                            msg.metadata() != null ? msg.metadata() : Map.of()
                        )).thenApply(_ -> Map.entry(msg.channel(), msg.chatId()));
                    }
                    return CompletableFuture.completedFuture(Map.entry(completedChannel, completedChatId));
                })
                .thenCompose(entry -> {
                    boolean continuing = turnContinuationInternalContinuationPending(msg.metadata());
                    if (!continuing) {
                        return runtimeEvents.turnCompleted(
                            entry.getKey(), entry.getValue(), sessionKey, msg.metadata()
                        );
                    }
                    return CompletableFuture.completedFuture(null);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
                    if (cause instanceof CancellationException) {
                        log.info("Task cancelled for session {}", sessionKey);
                        try {
                            String key = _effectiveSessionKey(msg);
                            Session session = sessionManager.getOrCreate(key);
                            if (_restoreRuntimeCheckpoint(session)) {
                                _clearPendingUserTurn(session);
                                sessionManager.save(session);
                                log.info("Restored partial context for cancelled session {}", key);
                            }
                        } catch (Exception e) {
                            log.debug("Could not restore checkpoint for cancelled session {}", sessionKey, e);
                        }
                        throw new CompletionException(cause);
                    }
                    log.error("Error processing message for session {}", sessionKey, cause);
                    return bus.publishOutbound(new OutboundMessage(
                        msg.channel(), msg.chatId(),
                        "Sorry, I encountered an error.", null, null, Map.of()
                    )).thenApply(_ -> {
                        boolean continuing = turnContinuationInternalContinuationPending(msg.metadata());
                        if (!continuing) {
                            runtimeEvents.turnCompleted(msg.channel(), msg.chatId(), sessionKey, msg.metadata());
                        }
                        return null;
                    }).join();
                })
                .thenCompose(_ -> {
                    // Drain pending queue
                    BlockingQueue<InboundMessage> queue = null;
                    if (pendingQueues.get(sessionKey) == pending) {
                        queue = pendingQueues.remove(sessionKey);
                    } else {
                        queue = pending;
                    }
                    if (queue != null) {
                        List<InboundMessage> leftover = new ArrayList<>();
                        queue.drainTo(leftover);
                        CompletableFuture<Void> drainFuture = CompletableFuture.completedFuture(null);
                        for (InboundMessage item : leftover) {
                            drainFuture = drainFuture.thenCompose(__ -> bus.publishInbound(item));
                        }
                        if (!leftover.isEmpty()) {
                            log.info("Re-published {} leftover message(s) to bus for session {}",
                                leftover.size(), sessionKey);
                        }
                        return drainFuture.thenCompose(__ -> {
                            boolean continuing = turnContinuationInternalContinuationPending(msg.metadata());
                            if (!continuing) {
                                return runtimeEvents.runStatusChanged(msg, sessionKey, "idle")
                                    .thenCompose(___ -> runtimeEvents.clearTurn(sessionKey));
                            }
                            return CompletableFuture.completedFuture(null);
                        });
                    }
                    return CompletableFuture.completedFuture(null);
                });
        }).whenComplete((_, __) -> {
            lock.unlock();
            if (concurrencyGate != null) concurrencyGate.release();
            if (pendingQueues.get(sessionKey) == pending) {
                pendingQueues.remove(sessionKey);
            }
            boolean continuing = turnContinuationInternalContinuationPending(msg.metadata());
            if (!continuing) {
                runtimeEvents.runStatusChanged(msg, sessionKey, "idle")
                    .thenCompose(_ -> runtimeEvents.clearTurn(sessionKey));
            }
        });
    }

    public CompletableFuture<OutboundMessage> _processMessage(
            InboundMessage inboundMsg,
            Function<String, CompletableFuture<Void>> onStream,
            Function<Boolean, CompletableFuture<Void>> onStreamEnd,
            BlockingQueue<InboundMessage> pendingQueue) {
        InboundMessage sanitized = sanitizeMessage(inboundMsg);
        String sessionKey = resolveSessionKey(sanitized);

        TurnContext turn = TurnContext.builder()
            .msg(sanitized)
            .sessionKey(sessionKey)
            .state(TurnState.RESTORE)
            .turnId(sessionKey + ":" + System.nanoTime())
            .turnWallStartedAt(System.currentTimeMillis() / 1000.0)
            .trace(new ArrayList<>())
            .onStream(onStream)
            .onStreamEnd(onStreamEnd)
            .pendingQueue(pendingQueue)
            .build();

        return _processTurn(turn);
    }

    private CompletableFuture<OutboundMessage> _processTurn(TurnContext turn) {
        if (turn.getState() == TurnState.DONE) {
            return CompletableFuture.completedFuture(turn.getOutbound());
        }

        TurnState state = turn.getState();
        CompletableFuture<String> eventFuture;
        long t0 = System.nanoTime();

        switch (state) {
            case RESTORE:  eventFuture = _stateRestore(turn);  break;
            case COMPACT:  eventFuture = _stateCompact(turn);  break;
            case COMMAND:  eventFuture = _stateCommand(turn);  break;
            case BUILD:    eventFuture = _stateBuild(turn);    break;
            case RUN:      eventFuture = _stateRun(turn);      break;
            case SAVE:     eventFuture = _stateSave(turn);     break;
            case RESPOND:  eventFuture = _stateRespond(turn);  break;
            default:
                return CompletableFuture.failedFuture(
                    new IllegalStateException("Missing state handler for " + state));
        }

        return eventFuture.thenCompose(event -> {
            long durationMs = (System.nanoTime() - t0) / 1_000_000;
            turn.getTrace().add(new StateTraceEntry(state, t0, durationMs, event));
            log.debug("[turn {}] State {} took {}ms -> event {}",
                    turn.getTurnId(), state, durationMs, event);

            TurnState nextState = TurnStateTransitions.next(state, event);
            turn.setState(nextState);
            return _processTurn(turn);
        });
    }

    // === 状态处理器（全部 async，返回 CompletableFuture<String>）===

    private CompletableFuture<String> _stateRestore(TurnContext ctx) {
        InboundMessage msg = ctx.getMsg();
        // 文档/图片预处理（对标 _prepare_message_media）
        return CompletableFuture.supplyAsync(() -> {
            if (ctx.getSession() == null) {
                ctx.setSession(sessionManager.getOrCreate(ctx.getSessionKey()));
            }
            return ctx.getSession();
        }).thenCompose(session -> {
            // 恢复 checkpoint / pending user turn
            _restoreRuntimeCheckpoint(session);
            _restorePendingUserTurn(session);
            sessionManager.save(session);
            return CompletableFuture.completedFuture("ok");
        });
    }

    private CompletableFuture<String> _stateCompact(TurnContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            // 对标 auto_compact.prepare_session
            ctx.setSession(autoCompact.prepareSession(ctx.getSession(), ctx.getSessionKey()));
            return "ok";
        });
    }

    private CompletableFuture<String> _stateCommand(TurnContext ctx) {
        return CompletableFuture.supplyAsync(() -> ctx.getMsg().content().strip())
            .thenCompose(raw -> {
                CommandContext cmdCtx = new CommandContext(
                    ctx.getMsg(), ctx.getSession(), ctx.getSessionKey(), raw, this
                );
                return commandRouter.dispatch(cmdCtx);
            })
            .thenApply(result -> {
                if (result != null) {
                    ctx.setOutbound(result);
                    // shortcut 命令跳过 BUILD/SAVE，需提前持久化
                    String raw = ctx.getMsg().content().strip();
                    if (!"/new".equalsIgnoreCase(raw)) {
                        ctx.setUserPersistedEarly(
                            _persistUserMessageEarly(ctx.getMsg(), ctx.getSession())
                        );
                        ctx.getSession().addMessage("assistant", result.content(), Map.of("_command", true));
                        sessionManager.save(ctx.getSession());
                        _clearPendingUserTurn(ctx.getSession());
                    }
                    return "shortcut";
                }
                return "dispatch";
            });
    }

    private CompletableFuture<String> _stateBuild(TurnContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            // 设置工具上下文
            _setToolContext(
                ctx.getMsg().channel(), ctx.getMsg().chatId(),
                ctx.getMsg().metadata().get("message_id"),
                ctx.getMsg().metadata(),
                ctx.getSessionKey()
            );

            // 获取历史
            ctx.setHistory(ctx.getSession().getHistory(maxMessages, replayTokenBudget(), true));

            // 构建初始 messages（对标 _build_initial_messages）
            List<Map<String, Object>> messages = contextBuilder.buildMessages(
                ctx.getHistory(), ctx.getMsg(), ctx.getSession(),
                ctx.getPendingSummary(), !ctx.isEphemeral()
            );
            ctx.setInitialMessages(Collections.unmodifiableList(messages));

            ctx.setUserPersistedEarly(_persistUserMessageEarly(ctx.getMsg(), ctx.getSession()));
            return "ok";
        });
    }

    private CompletableFuture<String> _stateRun(TurnContext ctx) {
        if (ctx.getVisibleRunStartedAt() == null) {
            ctx.setVisibleRunStartedAt(System.currentTimeMillis() / 1000.0);
        }
        return _runAgentLoop(ctx)
            .thenApply(result -> {
                ctx.setFinalContent(result.finalContent());
                ctx.setAllMessages(result.messages());
                ctx.setToolsUsed(result.toolsUsed());
                ctx.setStopReason(result.stopReason());
                ctx.setHadInjections(result.hadInjections());
                return "ok";
            });
    }

    private CompletableFuture<AgentRunResult> _runAgentLoop(TurnContext ctx) {
        // 构建 callbacks
        BiFunction<String, Map<String, Object>, CompletableFuture<Void>> onProgress = ctx.getOnProgress();
        Function<String, CompletableFuture<Void>> onStream = ctx.getOnStream();
        Function<Boolean, CompletableFuture<Void>> onStreamEnd = ctx.getOnStreamEnd();
        Function<String, CompletableFuture<Void>> onRetryWait = ctx.getOnRetryWait();

        if (onProgress == null) {
            onProgress = _buildBusProgressCallback(ctx.getMsg());
            ctx.setOnProgress(onProgress);
        }
        if (onRetryWait == null) {
            onRetryWait = _buildRetryWaitCallback(ctx.getMsg());
            ctx.setOnRetryWait(onRetryWait);
        }

        WorkspaceScope scope = workspaceScopes.forMessage(ctx.getMsg(), ctx.getSession().metadata());
        String sessionKey = ctx.getSessionKey();

        // checkpoint callback
        Function<Map<String, Object>, CompletableFuture<Void>> checkpointCallback = payload -> {
            if (ctx.getSession() != null) {
                ctx.getSession().setRuntimeCheckpoint(payload);
                sessionManager.save(ctx.getSession());
            }
            return CompletableFuture.completedFuture(null);
        };

        // injection callback
        Function<Integer, CompletableFuture<List<Map<String, Object>>>> injectionCallback = limit -> {
            BlockingQueue<InboundMessage> queue = ctx.getPendingQueue();
            List<Map<String, Object>> injected = new ArrayList<>();
            if (queue == null) return CompletableFuture.completedFuture(injected);
            int count = 0;
            while (count < limit) {
                InboundMessage m = queue.poll();
                if (m == null) break;
                injected.add(Map.of("role", "user", "content", m.content()));
                count++;
            }
            return CompletableFuture.completedFuture(injected);
        };

        AgentRunSpec spec = AgentRunSpec.builder()
            .initialMessages(ctx.getInitialMessages())
            .tools(ctx.getTools() != null ? ctx.getTools() : toolRegistry)
            .model(model)
            .maxIterations(maxIterations)
            .maxToolResultChars(maxToolResultChars)
            .temperature(null)
            .maxTokens(null)
            .providerRetryMode(providerRetryMode)
            .hook(buildHooks(ctx))
            .errorMessage("Sorry, I encountered an error calling the AI model.")
            .concurrentTools(true)
            .workspace(scope.projectPath())
            .sessionKey(sessionKey)
            .contextWindowTokens(contextWindowTokens)
            .contextBlockLimit(null)
            .progressCallback(onProgress)
            .streamProgressDeltas(onStream != null)
            .retryWaitCallback(onRetryWait)
            .checkpointCallback(checkpointCallback)
            .injectionCallback(injectionCallback)
            .llmTimeoutS(runnerWallLlmTimeoutS(sessionManager, sessionKey, ctx.getSession().metadata(), ctx.getMsg().metadata()))
            .goalActivePredicate(() -> sustainedGoalActive(ctx.getSession().metadata()))
            .goalContinueMessage(SUSTAINED_GOAL_CONTINUE_PROMPT)
            .finalizeOnMaxIterations(turnContinuation.shouldFinalizeOnMaxIterations(
                ctx.getPendingQueue() != null && ctx.getSession() != null,
                ctx.getSession() != null ? ctx.getSession().metadata() : Map.of(),
                ctx.getMsg().metadata()))
            .build();

        return runner.run(spec);
    }

    private CompletableFuture<String> _stateSave(TurnContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            if ((ctx.getFinalContent() == null || ctx.getFinalContent().isBlank()) && !ctx.isSuppressResponse()) {
                ctx.setFinalContent("[No response generated]"); // 对标 EMPTY_FINAL_RESPONSE_MESSAGE
            }
            long latencyMs = Math.max(0,
                (long) ((System.currentTimeMillis() / 1000.0 - ctx.getTurnWallStartedAt()) * 1000));
            ctx.setTurnLatencyMs((int) latencyMs);
            _saveTurn(ctx.getSession(), ctx.getAllMessages(), ctx.getSaveSkip(), latencyMs);
            sessionManager.save(ctx.getSession());
            _clearPendingUserTurn(ctx.getSession());
            return "ok";
        });
    }

    private CompletableFuture<String> _stateRespond(TurnContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            if (ctx.isSuppressResponse()) {
                ctx.setOutbound(null);
                return "ok";
            }
            OutboundMessage outbound = _assembleOutbound(
                ctx.getMsg(), ctx.getFinalContent(), ctx.getAllMessages(),
                ctx.getStopReason(), ctx.getHadInjections()
            );
            ctx.setOutbound(outbound);
            return "ok";
        });
    }

    // ... 更多方法

    // === 辅助方法 ===

    private String _effectiveSessionKey(InboundMessage msg) {
        if (unifiedSession && msg.sessionKeyOverride() == null) {
            return UNIFIED_SESSION_KEY;
        }
        return msg.sessionKey();
    }

    private CompletableFuture<Void> _dispatchCommandInline(
            InboundMessage msg, String key, String raw,
            Function<CommandContext, CompletableFuture<OutboundMessage>> dispatchFn) {
        CommandContext ctx = new CommandContext(msg, null, key, raw, this);
        return dispatchFn.apply(ctx)
            .thenCompose(result -> {
                if (result != null) {
                    return bus.publishOutbound(result);
                }
                log.warn("Command '{}' matched but dispatch returned None", raw);
                return CompletableFuture.completedFuture(null);
            });
    }

    private CompletableFuture<Boolean> agentContextHandleRuntimeControl(
            InboundMessage msg, ToolRegistry tools) {
        // 对标 agent_context.handle_runtime_control
        return CompletableFuture.completedFuture(false);
    }

    private boolean turnContinuationInternalContinuationPending(Map<String, Object> metadata) {
        // 对标 turn_continuation.internal_continuation_pending
        return Boolean.TRUE.equals(metadata.get("_continuation_pending"));
    }

    private void _scheduleBackground(Runnable task) {
        CompletableFuture.runAsync(task);
    }

    private BiFunction<String, Map<String, Object>, CompletableFuture<Void>> _buildBusProgressCallback(InboundMessage msg) {
        return (content, meta) -> bus.publishOutbound(new OutboundMessage(
            msg.channel(), msg.chatId(), content, null, null, meta
        ));
    }

    private Function<String, CompletableFuture<Void>> _buildRetryWaitCallback(InboundMessage msg) {
        return content -> {
            Map<String, Object> meta = new HashMap<>(msg.metadata() != null ? msg.metadata() : Map.of());
            meta.put("_retry_wait", true);
            return bus.publishOutbound(new OutboundMessage(
                msg.channel(), msg.chatId(), content, null, null, meta
            ));
        };
    }

    private int _replayTokenBudget() {
        if (contextWindowTokens <= 0) return 0;
        int maxOutput = 4096;
        try {
            maxOutput = provider.generation().maxTokens();
        } catch (Exception ignored) {}
        int budget = contextWindowTokens - Math.max(1, maxOutput) - 1024;
        return budget > 0 ? budget : Math.max(128, contextWindowTokens / 2);
    }

    private InboundMessage sanitizeMessage(InboundMessage msg) { return msg; }
    private String resolveSessionKey(InboundMessage msg) { return msg.sessionKey(); }
    private AgentHook buildHooks(TurnContext ctx) { return new AgentHook() {}; }
    private boolean _restoreRuntimeCheckpoint(Session session) { return false; }
    private void _clearPendingUserTurn(Session session) {}
    private void _saveTurn(Session session, List<Map<String, Object>> messages, int skip, long latencyMs) {}
    private OutboundMessage _assembleOutbound(InboundMessage msg, String content,
            List<Map<String, Object>> messages, String stopReason, boolean hadInjections) {
        return new OutboundMessage(msg.channel(), msg.chatId(), content, null, null, Map.of());
    }
    private void _setToolContext(String channel, String chatId, Object messageId,
            Map<String, Object> metadata, String sessionKey) {}
    private boolean _persistUserMessageEarly(InboundMessage msg, Session session) { return false; }
    private double runnerWallLlmTimeoutS(SessionManager sessions, String key,
            Map<String, Object> sessionMetadata, Map<String, Object> messageMetadata) { return 300.0; }
    private boolean sustainedGoalActive(Map<String, Object> metadata) { return false; }
}
```

### 3. AgentRunner.java

```java
package com.nanobot.agent;

import com.nanobot.agent.tools.ToolRegistry;
import com.nanobot.providers.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class AgentRunner {

    // ----- 常量 -----
    private static final String DEFAULT_ERROR_MESSAGE = "Sorry, I encountered an error calling the AI model.";
    private static final String ARREARAGE_ERROR_MESSAGE =
        "The AI provider rejected the request because the API key is out of quota or the "
        + "account is in arrears. Please top up / check the billing status of your API key and try again.";
    private static final String PERSISTED_MODEL_ERROR_PLACEHOLDER = "[Assistant reply unavailable due to model error.]";
    private static final int MAX_EMPTY_RETRIES = 2;
    private static final int MAX_LENGTH_RECOVERIES = 3;
    private static final int MAX_INJECTIONS_PER_TURN = 3;
    private static final int MAX_INJECTION_CYCLES = 5;
    private static final int SNIP_SAFETY_BUFFER = 1024;
    private static final int MICROCOMPACT_KEEP_RECENT = 10;
    private static final int MICROCOMPACT_MIN_CHARS = 500;
    private static final Set<String> COMPACTABLE_TOOLS = Set.of(
        "read_file", "exec", "grep", "find_files",
        "web_search", "web_fetch", "list_dir", "list_exec_sessions"
    );
    private static final Set<String> TOOL_RESULT_OFFLOAD_EXEMPT_TOOLS = Set.of("read_file");
    private static final String BACKFILL_CONTENT = "[Tool result unavailable — call was interrupted or lost]";

    private final LLMProvider provider;

    public AgentRunner(LLMProvider provider) {
        this.provider = provider;
    }

    /** 对标 Python {@code async def run(spec)} — 入口包装，含异常/取消/最终处理 */
    public CompletableFuture<AgentRunResult> run(AgentRunSpec spec) {
        AgentHook hook = spec.hook() != null ? spec.hook() : new AgentHook() {};
        List<Map<String, Object>> messages = new ArrayList<>(spec.initialMessages());
        AgentRunHookContext runCtx = new AgentRunHookContext(deepCopy(messages));

        CompletableFuture<AgentRunResult> core = hook.beforeRun(runCtx)
            .thenCompose(_ -> _runCore(spec, hook, messages));

        return core.handle((result, ex) -> {
            if (ex != null) {
                runCtx.messages = deepCopy(messages);
                if (ex instanceof java.util.concurrent.CancellationException) {
                    runCtx.stopReason = "cancelled";
                    runCtx.error = null;
                } else {
                    runCtx.stopReason = "error";
                    runCtx.error = "Error: " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
                }
                runCtx.exception = ex;
                return hook.onError(runCtx).thenCompose(_ ->
                    CompletableFuture.<AgentRunResult>failedFuture(new RuntimeException(ex))
                );
            }
            // else 分支
            runCtx.messages = deepCopy(result.messages());
            runCtx.finalContent = result.finalContent();
            runCtx.toolsUsed = new ArrayList<>(result.toolsUsed());
            runCtx.usage = new HashMap<>(result.usage());
            runCtx.stopReason = result.stopReason();
            runCtx.error = result.error();
            runCtx.toolEvents = result.toolEvents() != null ? new ArrayList<>(result.toolEvents()) : new ArrayList<>();
            runCtx.hadInjections = result.hadInjections();
            runCtx.exception = null;
            CompletableFuture<Void> errHook = (runCtx.error != null)
                ? hook.onError(runCtx) : CompletableFuture.completedFuture(null);
            return errHook.thenCompose(_ -> hook.afterRun(runCtx).thenApply(_ -> result));
        }).thenCompose(cf -> cf)
        .whenComplete((result, ex) -> {
            runCtx.messages = deepCopy(messages);
            try {
                hook.onFinally(runCtx);
            } catch (Exception e) {
                // LOG.warn("AgentHook.on_finally error after {}", runCtx.stopReason, e);
            }
        });
    }

    // ----- 内部状态对象 -----
    private static class _RunCoreState {
        String finalContent;
        final List<String> toolsUsed = new ArrayList<>();
        final Map<String, Integer> usage = new HashMap<>(Map.of("prompt_tokens", 0, "completion_tokens", 0));
        String error;
        String stopReason = "completed";
        final List<Map<String, String>> toolEvents = new ArrayList<>();
        final Map<String, Integer> externalLookupCounts = new HashMap<>();
        final Map<String, Integer> workspaceViolationCounts = new HashMap<>();
        int emptyContentRetries = 0;
        int lengthRecoveryCount = 0;
        boolean hadInjections = false;
        int injectionCycles = 0;
    }

    private static record _ToolOutcome(List<Object> results, List<Map<String, String>> events, Throwable fatalError) {}
    private static record _DrainResult(boolean shouldContinue, int cycles) {}

    /** 对标 Python {@code _run_core()} — 核心对话循环入口 */
    private CompletableFuture<AgentRunResult> _runCore(AgentRunSpec spec, AgentHook hook, List<Map<String, Object>> messages) {
        _RunCoreState s = new _RunCoreState();
        return _runCoreIter(spec, hook, messages, s, 0);
    }

    private CompletableFuture<AgentRunResult> _runCoreIter(
            AgentRunSpec spec, AgentHook hook,
            List<Map<String, Object>> messages, _RunCoreState s, int iteration) {

        if (iteration >= spec.maxIterations()) {
            return _handleMaxIterations(spec, hook, messages, s);
        }

        // 1. Context governance (drop orphan → backfill → microcompact → budget → snip → re-drop → re-backfill)
        List<Map<String, Object>> messagesForModel;
        try {
            messagesForModel = _governContext(spec, messages);
        } catch (Exception e) {
            try {
                messagesForModel = _backfillMissingToolResults(_dropOrphanToolResults(messages));
            } catch (Exception e2) {
                messagesForModel = messages;
            }
        }

        AgentHookContext ctx = new AgentHookContext();
        ctx.iteration = iteration;
        ctx.messages = messages;
        ctx.sessionKey = spec.sessionKey();

        return hook.beforeIteration(ctx)
            .thenCompose(_ -> _requestModel(spec, messagesForModel, hook, ctx))
            .thenCompose(response -> {
                ctx.response = response;
                ctx.toolCalls = new ArrayList<>(response.toolCalls());

                String reasoningText = response.reasoningContent();
                if (reasoningText != null && !reasoningText.isEmpty() && !ctx.streamedReasoning) {
                    return hook.emitReasoning(reasoningText)
                        .thenCompose(_ -> hook.emitReasoningEnd())
                        .thenApply(_ -> {
                            ctx.streamedReasoning = true;
                            return response;
                        });
                }
                return CompletableFuture.completedFuture(response);
            })
            .thenCompose(response -> {
                Map<String, Integer> rawUsage = _usageOrEstimate(spec, messagesForModel, response);
                ctx.usage = new HashMap<>(rawUsage);
                _accumulateUsage(s.usage, rawUsage);

                if (response.shouldExecuteTools()) {
                    return _handleToolBranch(spec, hook, messages, messagesForModel, s, ctx, iteration, response);
                }
                return _handleFinalBranch(spec, hook, messages, messagesForModel, s, ctx, iteration, response);
            });
    }

    private CompletableFuture<AgentRunResult> _handleToolBranch(
            AgentRunSpec spec, AgentHook hook,
            List<Map<String, Object>> messages, List<Map<String, Object>> messagesForModel,
            _RunCoreState s, AgentHookContext ctx, int iteration, LLMResponse response) {

        return _endStreamIfWanted(hook, ctx, true)
            .thenCompose(_ -> {
                Map<String, Object> assistantMessage = RuntimeUtils.buildAssistantMessage(
                    response.content() != null ? response.content() : "",
                    response.toolCalls(),
                    response.reasoningContent(),
                    response.thinkingBlocks()
                );
                messages.add(assistantMessage);
                return _emitCheckpoint(spec, Map.of(
                    "phase", "awaiting_tools",
                    "iteration", iteration,
                    "model", spec.model(),
                    "assistant_message", assistantMessage,
                    "completed_tool_results", List.of(),
                    "pending_tool_calls", response.toolCalls()
                )).thenCompose(_2 -> hook.beforeExecuteTools(ctx))
                  .thenCompose(_2 -> _executeTools(spec, response.toolCalls(), s.externalLookupCounts, s.workspaceViolationCounts));
            })
            .thenCompose(toolOutcome -> {
                List<Object> results = toolOutcome.results;
                List<Map<String, String>> newEvents = toolOutcome.events;
                Throwable fatalError = toolOutcome.fatalError;

                s.toolEvents.addAll(newEvents);
                for (int i = 0; i < response.toolCalls().size(); i++) {
                    if ("ok".equals(newEvents.get(i).get("status"))) {
                        s.toolsUsed.add(response.toolCalls().get(i).name());
                    }
                }
                ctx.toolResults = new ArrayList<>(results);
                ctx.toolEvents = new ArrayList<>(newEvents);

                List<Map<String, Object>> completedToolResults = new ArrayList<>();
                for (int i = 0; i < response.toolCalls().size(); i++) {
                    ToolCallRequest tc = response.toolCalls().get(i);
                    Map<String, Object> toolMessage = new LinkedHashMap<>();
                    toolMessage.put("role", "tool");
                    toolMessage.put("tool_call_id", tc.id());
                    toolMessage.put("name", tc.name());
                    toolMessage.put("content", _normalizeToolResult(spec, tc.id(), tc.name(), results.get(i)));
                    messages.add(toolMessage);
                    completedToolResults.add(toolMessage);
                }

                if (fatalError != null) {
                    s.error = "Error: " + fatalError.getClass().getSimpleName() + ": " + fatalError.getMessage();
                    s.finalContent = s.error;
                    s.stopReason = "tool_error";
                    _appendFinalMessage(messages, s.finalContent);
                    ctx.finalContent = s.finalContent;
                    ctx.error = s.error;
                    ctx.stopReason = s.stopReason;
                    return hook.afterIteration(ctx)
                        .thenCompose(_ -> _tryDrainInjections(spec, messages, null, s.injectionCycles, "after tool error", iteration, false))
                        .thenCompose(drain -> {
                            if (drain.shouldContinue) {
                                s.hadInjections = true;
                                s.injectionCycles = drain.cycles;
                                return _runCoreIter(spec, hook, messages, s, iteration + 1);
                            }
                            return CompletableFuture.completedFuture(_buildResult(messages, s));
                        });
                }

                return _emitCheckpoint(spec, Map.of(
                    "phase", "tools_completed",
                    "iteration", iteration,
                    "model", spec.model(),
                    "assistant_message", messages.get(messages.size() - 1 - completedToolResults.size()),
                    "completed_tool_results", completedToolResults,
                    "pending_tool_calls", List.of()
                )).thenCompose(_ -> {
                    s.emptyContentRetries = 0;
                    s.lengthRecoveryCount = 0;
                    return _tryDrainInjections(spec, messages, null, s.injectionCycles, "after tool execution", iteration, false);
                }).thenCompose(drain -> {
                    if (drain.shouldContinue) {
                        s.hadInjections = true;
                        s.injectionCycles = drain.cycles;
                    }
                    return hook.afterIteration(ctx)
                        .thenCompose(_ -> _runCoreIter(spec, hook, messages, s, iteration + 1));
                });
            });
    }

    private CompletableFuture<AgentRunResult> _handleFinalBranch(
            AgentRunSpec spec, AgentHook hook,
            List<Map<String, Object>> messages, List<Map<String, Object>> messagesForModel,
            _RunCoreState s, AgentHookContext ctx, int iteration, LLMResponse response) {

        if (response.hasToolCalls()) {
            // LOG.warn("Ignoring tool calls under finish_reason='{}' for {}", response.finishReason(), spec.sessionKey());
        }

        String clean = hook.finalizeContent(ctx, response.content());

        if (!"error".equals(response.finishReason()) && RuntimeUtils.isBlankText(clean)) {
            s.emptyContentRetries++;
            if (s.emptyContentRetries < MAX_EMPTY_RETRIES) {
                return _endStreamIfWanted(hook, ctx, false)
                    .thenCompose(_ -> hook.afterIteration(ctx))
                    .thenCompose(_ -> _runCoreIter(spec, hook, messages, s, iteration + 1));
            }
            return _endStreamIfWanted(hook, ctx, false)
                .thenCompose(_ -> _requestFinalizationRetry(spec, messagesForModel))
                .thenCompose(retryResponse -> {
                    List<Map<String, Object>> retryMessages = _finalizationRetryMessages(messagesForModel);
                    Map<String, Integer> retryUsage = _usageOrEstimate(spec, retryMessages, retryResponse);
                    _accumulateUsage(s.usage, retryUsage);
                    ctx.response = retryResponse;
                    ctx.usage = _mergeUsage(ctx.usage, retryUsage);
                    ctx.toolCalls = new ArrayList<>(retryResponse.toolCalls());
                    String retryClean = hook.finalizeContent(ctx, retryResponse.content());
                    return _finishFinalBranch(spec, hook, messages, s, ctx, iteration, retryResponse, retryClean);
                });
        }

        return _finishFinalBranch(spec, hook, messages, s, ctx, iteration, response, clean);
    }

    private CompletableFuture<AgentRunResult> _finishFinalBranch(
            AgentRunSpec spec, AgentHook hook,
            List<Map<String, Object>> messages, _RunCoreState s,
            AgentHookContext ctx, int iteration, LLMResponse response, String clean) {

        if ("length".equals(response.finishReason()) && !RuntimeUtils.isBlankText(clean)) {
            s.lengthRecoveryCount++;
            if (s.lengthRecoveryCount <= MAX_LENGTH_RECOVERIES) {
                return _endStreamIfWanted(hook, ctx, true)
                    .thenCompose(_ -> {
                        messages.add(RuntimeUtils.buildAssistantMessage(clean, response.reasoningContent(), response.thinkingBlocks()));
                        messages.add(RuntimeUtils.buildLengthRecoveryMessage());
                        return hook.afterIteration(ctx);
                    })
                    .thenCompose(_ -> _runCoreIter(spec, hook, messages, s, iteration + 1));
            }
        }

        Map<String, Object> assistantMessage = null;
        if (!"error".equals(response.finishReason()) && !RuntimeUtils.isBlankText(clean)) {
            assistantMessage = RuntimeUtils.buildAssistantMessage(clean, response.reasoningContent(), response.thinkingBlocks());
        }

        return _tryDrainInjections(spec, messages, assistantMessage, s.injectionCycles, "after final response", iteration, true)
            .thenCompose(drainResult -> {
                boolean shouldContinue = drainResult.shouldContinue;
                s.injectionCycles = drainResult.cycles;
                if (shouldContinue) {
                    s.hadInjections = true;
                }

                return _endStreamIfWanted(hook, ctx, shouldContinue)
                    .thenCompose(_ -> {
                        if (shouldContinue) {
                            return hook.afterIteration(ctx)
                                .thenCompose(_ -> _runCoreIter(spec, hook, messages, s, iteration + 1));
                        }

                        if ("error".equals(response.finishReason())) {
                            if (LLMProvider.isArrearageResponse(response)) {
                                s.finalContent = ARREARAGE_ERROR_MESSAGE;
                            } else {
                                s.finalContent = clean != null ? clean : (spec.errorMessage() != null ? spec.errorMessage() : DEFAULT_ERROR_MESSAGE);
                            }
                            s.stopReason = "error";
                            s.error = s.finalContent;
                            _appendModelErrorPlaceholder(messages);
                            ctx.finalContent = s.finalContent;
                            ctx.error = s.error;
                            ctx.stopReason = s.stopReason;
                            return hook.afterIteration(ctx)
                                .thenCompose(_ -> _tryDrainInjections(spec, messages, null, s.injectionCycles, "after LLM error", iteration, false))
                                .thenCompose(drain2 -> {
                                    if (drain2.shouldContinue) {
                                        s.hadInjections = true;
                                        s.injectionCycles = drain2.cycles;
                                        return _runCoreIter(spec, hook, messages, s, iteration + 1);
                                    }
                                    return CompletableFuture.completedFuture(_buildResult(messages, s));
                                });
                        }

                        if (RuntimeUtils.isBlankText(clean)) {
                            s.finalContent = RuntimeUtils.EMPTY_FINAL_RESPONSE_MESSAGE;
                            s.stopReason = "empty_final_response";
                            s.error = s.finalContent;
                            _appendFinalMessage(messages, s.finalContent);
                            ctx.finalContent = s.finalContent;
                            ctx.error = s.error;
                            ctx.stopReason = s.stopReason;
                            return hook.afterIteration(ctx)
                                .thenCompose(_ -> _tryDrainInjections(spec, messages, null, s.injectionCycles, "after empty response", iteration, false))
                                .thenCompose(drain2 -> {
                                    if (drain2.shouldContinue) {
                                        s.hadInjections = true;
                                        s.injectionCycles = drain2.cycles;
                                        return _runCoreIter(spec, hook, messages, s, iteration + 1);
                                    }
                                    return CompletableFuture.completedFuture(_buildResult(messages, s));
                                });
                        }

                        Map<String, Object> finalAssistant = assistantMessage != null ? assistantMessage
                            : RuntimeUtils.buildAssistantMessage(clean, response.reasoningContent(), response.thinkingBlocks());
                        messages.add(finalAssistant);
                        return _emitCheckpoint(spec, Map.of(
                            "phase", "final_response",
                            "iteration", iteration,
                            "model", spec.model(),
                            "assistant_message", finalAssistant,
                            "completed_tool_results", List.of(),
                            "pending_tool_calls", List.of()
                        )).thenCompose(_ -> {
                            s.finalContent = clean;
                            ctx.finalContent = s.finalContent;
                            ctx.stopReason = s.stopReason;
                            return hook.afterIteration(ctx)
                                .thenApply(_ -> _buildResult(messages, s));
                        });
                    });
            });
    }

    private CompletableFuture<AgentRunResult> _handleMaxIterations(
            AgentRunSpec spec, AgentHook hook,
            List<Map<String, Object>> messages, _RunCoreState s) {
        s.stopReason = "max_iterations";
        return _tryDrainInjections(spec, messages, null, s.injectionCycles, "after max_iterations", null, false)
            .thenCompose(drainResult -> {
                if (drainResult.shouldContinue) {
                    s.hadInjections = true;
                    s.injectionCycles = drainResult.cycles;
                }
                s.finalContent = null;
                if (spec.finalizeOnMaxIterations()) {
                    return _tryFinalizeAfterMaxIterations(spec, hook, messages, s.usage)
                        .thenApply(finalizeContent -> {
                            if (finalizeContent == null) {
                                finalizeContent = _maxIterationsFallback(spec);
                            }
                            s.finalContent = finalizeContent;
                            _appendFinalMessage(messages, s.finalContent);
                            return _buildResult(messages, s);
                        });
                }
                s.finalContent = _maxIterationsFallback(spec);
                _appendFinalMessage(messages, s.finalContent);
                return CompletableFuture.completedFuture(_buildResult(messages, s));
            });
    }

    private List<Map<String, Object>> _governContext(AgentRunSpec spec, List<Map<String, Object>> messages) {
        List<Map<String, Object>> m = _dropOrphanToolResults(messages);
        m = _backfillMissingToolResults(m);
        m = _microcompact(m);
        m = _applyToolResultBudget(spec, m);
        m = _snipHistory(spec, m);
        m = _dropOrphanToolResults(m);
        m = _backfillMissingToolResults(m);
        return m;
    }

    private CompletableFuture<Void> _endStreamIfWanted(AgentHook hook, AgentHookContext ctx, boolean resuming) {
        if (hook.wantsStreaming()) {
            return hook.onStreamEnd(ctx, resuming);
        }
        return CompletableFuture.completedFuture(null);
    }

    private AgentRunResult _buildResult(List<Map<String, Object>> messages, _RunCoreState s) {
        return new AgentRunResult(
            s.finalContent, messages, s.toolsUsed, s.usage,
            s.stopReason, s.error, s.toolEvents, s.hadInjections
        );
    }

    private Map<String, Object> _buildRequestKwargs(AgentRunSpec spec, List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        Map<String, Object> kwargs = new LinkedHashMap<>();
        kwargs.put("messages", messages);
        kwargs.put("tools", tools);
        kwargs.put("model", spec.model());
        kwargs.put("retry_mode", spec.providerRetryMode());
        kwargs.put("on_retry_wait", spec.retryWaitCallback());
        if (spec.temperature() != null) kwargs.put("temperature", spec.temperature());
        if (spec.maxTokens() != null) kwargs.put("max_tokens", spec.maxTokens());
        if (spec.reasoningEffort() != null) kwargs.put("reasoning_effort", spec.reasoningEffort());
        return kwargs;
    }

    private CompletableFuture<LLMResponse> _requestModel(
            AgentRunSpec spec, List<Map<String, Object>> messages,
            AgentHook hook, AgentHookContext ctx) {
        double timeoutS = spec.llmTimeoutS() != null ? spec.llmTimeoutS() : 300.0;
        String envTimeout = System.getenv("NANOBOT_LLM_TIMEOUT_S");
        if (envTimeout != null) {
            try { timeoutS = Double.parseDouble(envTimeout.trim()); } catch (NumberFormatException ignored) {}
        }
        if (timeoutS <= 0) timeoutS = Double.NaN;

        Map<String, Object> kwargs = _buildRequestKwargs(spec, messages, spec.tools().getDefinitions());
        boolean wantsStreaming = hook.wantsStreaming();
        boolean wantsProgressStreaming = !wantsStreaming && spec.streamProgressDeltas()
            && spec.progressCallback() != null && provider.supportsProgressDeltas();

        // StreamingFileEditTracker setup（对标 Python StreamingFileEditTracker）
        // 当 progressCallback 支持 file edit events 时初始化 tracker，
        // 用于在流式 tool_call_delta 中追踪文件编辑操作。
        // Object liveFileEdits = (spec.progressCallback() != null && acceptsFileEditEvents(spec.progressCallback()))
        //     ? new StreamingFileEditTracker(spec.workspace(), spec.tools(), events -> invokeFileEditProgress(spec.progressCallback(), events))
        //     : null;

        CompletableFuture<LLMResponse> coro;
        if (wantsStreaming) {
            coro = provider.chatStreamWithRetry(
                kwargs,
                delta -> { ctx.streamedContent = true; return hook.onStream(ctx, delta); },
                delta -> { if (delta != null && !delta.isEmpty()) { ctx.streamedReasoning = true; return hook.emitReasoning(delta); } return CompletableFuture.completedFuture(null); },
                null,
                () -> hook.onStreamEnd(ctx, true)
            );
        } else if (wantsProgressStreaming) {
            String[] streamBuf = { "" };
            IncrementalThinkExtractor thinkExtractor = new IncrementalThinkExtractor();
            Map<String, Boolean> progressState = new HashMap<>(Map.of("reasoning_open", false));
            coro = provider.chatStreamWithRetry(
                kwargs,
                delta -> {
                    if (delta == null || delta.isEmpty()) return CompletableFuture.completedFuture(null);
                    String prevClean = StripThink.strip(streamBuf[0]);
                    streamBuf[0] += delta;
                    String newClean = StripThink.strip(streamBuf[0]);
                    String incremental = newClean.substring(prevClean.length());
                    if (thinkExtractor.feed(streamBuf[0])) {
                        ctx.streamedReasoning = true;
                        progressState.put("reasoning_open", true);
                    }
                    if (!incremental.isEmpty()) {
                        if (progressState.get("reasoning_open")) {
                            hook.emitReasoningEnd();
                            progressState.put("reasoning_open", false);
                        }
                        ctx.streamedContent = true;
                        if (spec.progressCallback() != null) {
                            return spec.progressCallback().apply(incremental, Map.of());
                        }
                    }
                    return CompletableFuture.completedFuture(null);
                },
                null, null, null
            );
        } else {
            coro = provider.chatWithRetry(kwargs);
        }

        Double outerTimeout = (wantsStreaming || wantsProgressStreaming) ? null : timeoutS;
        if (outerTimeout != null && !outerTimeout.isNaN()) {
            return coro.orTimeout((long) (outerTimeout * 1000), java.util.concurrent.TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof java.util.concurrent.TimeoutException) {
                        return new LLMResponse(
                            "Error calling LLM: timed out after " + outerTimeout + "s",
                            null, "error", null, null, null, null,
                            null, "timeout", null, null, null, null
                        );
                    }
                    throw new RuntimeException(ex);
                });
        }
        return coro;
    }

    private CompletableFuture<LLMResponse> _requestNoTools(AgentRunSpec spec, List<Map<String, Object>> messages) {
        Map<String, Object> kwargs = _buildRequestKwargs(spec, messages, null);
        return provider.chatWithRetry(kwargs);
    }

    private CompletableFuture<LLMResponse> _requestFinalizationRetry(AgentRunSpec spec, List<Map<String, Object>> messages) {
        List<Map<String, Object>> retryMessages = _finalizationRetryMessages(messages);
        return _requestNoTools(spec, retryMessages);
    }

    private static List<Map<String, Object>> _finalizationRetryMessages(List<Map<String, Object>> messages) {
        List<Map<String, Object>> retry = new ArrayList<>(messages);
        retry.add(RuntimeUtils.buildFinalizationRetryMessage());
        return retry;
    }

    private CompletableFuture<String> _tryFinalizeAfterMaxIterations(
            AgentRunSpec spec, AgentHook hook,
            List<Map<String, Object>> messages, Map<String, Integer> usage) {
        List<Map<String, Object>> retryMessages = _budgetExhaustedFinalizationMessages(messages);
        return _requestNoTools(spec, retryMessages)
            .thenApply(response -> {
                Map<String, Integer> rawUsage = _usageOrEstimate(spec, retryMessages, response);
                _accumulateUsage(usage, rawUsage);
                if ("error".equals(response.finishReason()) || response.hasToolCalls()) {
                    return null;
                }
                AgentHookContext ctx = new AgentHookContext();
                ctx.iteration = spec.maxIterations();
                ctx.messages = messages;
                ctx.response = response;
                ctx.usage = new HashMap<>(rawUsage);
                ctx.sessionKey = spec.sessionKey();
                String clean = hook.finalizeContent(ctx, response.content());
                return RuntimeUtils.isBlankText(clean) ? null : clean;
            })
            .exceptionally(ex -> null);
    }

    private static List<Map<String, Object>> _budgetExhaustedFinalizationMessages(List<Map<String, Object>> messages) {
        List<Map<String, Object>> retry = new ArrayList<>(messages);
        retry.add(RuntimeUtils.buildBudgetExhaustedFinalizationMessage());
        return retry;
    }

    private static String _maxIterationsFallback(AgentRunSpec spec) {
        if (spec.maxIterationsMessage() != null) {
            return spec.maxIterationsMessage().replace("{max_iterations}", String.valueOf(spec.maxIterations()));
        }
        return RuntimeUtils.renderTemplate("agent/max_iterations_message.md", Map.of("max_iterations", spec.maxIterations()));
    }

    private CompletableFuture<_ToolOutcome> _executeTools(
            AgentRunSpec spec, List<ToolCallRequest> toolCalls,
            Map<String, Integer> externalLookupCounts,
            Map<String, Integer> workspaceViolationCounts) {
        List<List<ToolCallRequest>> batches = _partitionToolBatches(spec, toolCalls);
        List<CompletableFuture<List<_ToolOutcome>>> batchFutures = new ArrayList<>();

        for (List<ToolCallRequest> batch : batches) {
            if (spec.concurrentTools() && batch.size() > 1) {
                List<CompletableFuture<_ToolOutcome>> futures = new ArrayList<>();
                for (ToolCallRequest tc : batch) {
                    futures.add(_runTool(spec, tc, externalLookupCounts, workspaceViolationCounts));
                }
                batchFutures.add(CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(_ -> futures.stream().map(CompletableFuture::join).toList()));
            } else {
                CompletableFuture<List<_ToolOutcome>> chain = CompletableFuture.completedFuture(new ArrayList<>());
                for (ToolCallRequest tc : batch) {
                    ToolCallRequest tcFinal = tc;
                    chain = chain.thenCompose(list -> _runTool(spec, tcFinal, externalLookupCounts, workspaceViolationCounts)
                        .thenApply(o -> { list.add(o); return list; }));
                }
                batchFutures.add(chain);
            }
        }

        return CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]))
            .thenApply(_ -> {
                List<Object> results = new ArrayList<>();
                List<Map<String, String>> events = new ArrayList<>();
                Throwable fatal = null;
                for (CompletableFuture<List<_ToolOutcome>> bf : batchFutures) {
                    for (_ToolOutcome o : bf.join()) {
                        results.addAll(o.results());
                        events.addAll(o.events());
                        if (o.fatalError() != null && fatal == null) fatal = o.fatalError();
                    }
                }
                return new _ToolOutcome(results, events, fatal);
            });
    }

    private List<List<ToolCallRequest>> _partitionToolBatches(AgentRunSpec spec, List<ToolCallRequest> toolCalls) {
        if (!spec.concurrentTools()) {
            List<List<ToolCallRequest>> result = new ArrayList<>();
            for (ToolCallRequest tc : toolCalls) result.add(List.of(tc));
            return result;
        }
        List<List<ToolCallRequest>> batches = new ArrayList<>();
        List<ToolCallRequest> current = new ArrayList<>();
        for (ToolCallRequest tc : toolCalls) {
            Object tool = spec.tools().get(tc.name());
            boolean canBatch = false;
            if (tool != null) {
                try {
                    canBatch = (Boolean) tool.getClass().getMethod("concurrencySafe").invoke(tool);
                } catch (Exception ignored) {}
            }
            if (canBatch) {
                current.add(tc);
            } else {
                if (!current.isEmpty()) {
                    batches.add(new ArrayList<>(current));
                    current.clear();
                }
                batches.add(List.of(tc));
            }
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    private CompletableFuture<_ToolOutcome> _runTool(
            AgentRunSpec spec, ToolCallRequest toolCall,
            Map<String, Integer> externalLookupCounts,
            Map<String, Integer> workspaceViolationCounts) {
        String hint = "\n\n[Analyze the error above and try a different approach.]";

        String lookupError = RuntimeUtils.repeatedExternalLookupError(
            toolCall.name(), toolCall.arguments(), externalLookupCounts);
        if (lookupError != null) {
            Map<String, String> event = new HashMap<>(Map.of(
                "name", toolCall.name(),
                "status", "error",
                "detail", "repeated external lookup blocked"
            ));
            if (spec.failOnToolError()) {
                return CompletableFuture.completedFuture(new _ToolOutcome(
                    List.of(lookupError + hint), List.of(event), new RuntimeException(lookupError)));
            }
            return CompletableFuture.completedFuture(new _ToolOutcome(
                List.of(lookupError + hint), List.of(event), null));
        }

        Object tool = null;
        Map<String, Object> params = toolCall.arguments();
        String prepError = null;
        try {
            Object prepared = spec.tools().getClass().getMethod("prepareCall", String.class, Map.class)
                .invoke(spec.tools(), toolCall.name(), toolCall.arguments());
            if (prepared instanceof Object[] arr && arr.length == 3) {
                tool = arr[0]; params = (Map<String, Object>) arr[1]; prepError = (String) arr[2];
            }
        } catch (Exception ignored) {}

        if (prepError != null) {
            Map<String, String> event = new HashMap<>(Map.of(
                "name", toolCall.name(),
                "status", "error",
                "detail", prepError.split(": ", 2)[Math.min(1, prepError.split(": ", 2).length - 1)].substring(0, Math.min(120, prepError.length()))
            ));
            _ToolOutcome handled = _classifyViolation(
                prepError, prepError + hint, event, toolCall, workspaceViolationCounts);
            if (handled != null) return CompletableFuture.completedFuture(handled);
            if (spec.failOnToolError()) {
                return CompletableFuture.completedFuture(new _ToolOutcome(
                    List.of(prepError + hint), List.of(event), new RuntimeException(prepError)));
            }
            return CompletableFuture.completedFuture(new _ToolOutcome(
                List.of(prepError + hint), List.of(event), null));
        }

        CompletableFuture<Object> exec;
        if (tool != null) {
            try {
                exec = (CompletableFuture<Object>) tool.getClass().getMethod("execute", Map.class).invoke(tool, params);
            } catch (Exception e) {
                exec = CompletableFuture.failedFuture(e);
            }
        } else {
            exec = spec.tools().execute(toolCall.name(), params);
        }

        return exec.handle((result, ex) -> {
            if (ex != null) {
                if (ex instanceof java.util.concurrent.CancellationException) {
                    throw (java.util.concurrent.CancellationException) ex;
                }
                Map<String, String> event = new HashMap<>(Map.of(
                    "name", toolCall.name(),
                    "status", "error",
                    "detail", ex.getMessage() != null ? ex.getMessage() : ""
                ));
                String payload = "Error: " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
                _ToolOutcome handled = _classifyViolation(
                    ex.getMessage(), payload, event, toolCall, workspaceViolationCounts);
                if (handled != null) return handled;
                if (spec.failOnToolError()) {
                    return new _ToolOutcome(List.of(payload), List.of(event), ex);
                }
                return new _ToolOutcome(List.of(payload), List.of(event), null);
            }

            if (result instanceof String str && str.startsWith("Error")) {
                Map<String, String> event = new HashMap<>(Map.of(
                    "name", toolCall.name(),
                    "status", "error",
                    "detail", str.replace("\n", " ").trim().substring(0, Math.min(120, str.length()))
                ));
                _ToolOutcome handled = _classifyViolation(
                    str, str + hint, event, toolCall, workspaceViolationCounts);
                if (handled != null) return handled;
                if (spec.failOnToolError()) {
                    return new _ToolOutcome(List.of(str + hint), List.of(event), new RuntimeException(str));
                }
                return new _ToolOutcome(List.of(str + hint), List.of(event), null);
            }

            String detail = result == null ? "(empty)" : result.toString().replace("\n", " ").trim();
            if (detail.isEmpty()) detail = "(empty)";
            if (detail.length() > 120) detail = detail.substring(0, 120) + "...";
            return new _ToolOutcome(List.of(result), List.of(new HashMap<>(Map.of(
                "name", toolCall.name(), "status", "ok", "detail", detail
            ))), null);
        }).thenApply(x -> x);
    }

    private CompletableFuture<_DrainResult> _tryDrainInjections(
            AgentRunSpec spec, List<Map<String, Object>> messages,
            Map<String, Object> assistantMessage, int injectionCycles,
            String phase, Integer iteration, boolean allowGoalContinue) {
        if (injectionCycles >= MAX_INJECTION_CYCLES) {
            return CompletableFuture.completedFuture(new _DrainResult(false, injectionCycles));
        }
        return _drainInjections(spec).thenCompose(injections -> {
            boolean realInjection = !injections.isEmpty();
            if (!realInjection && allowGoalContinue && assistantMessage != null) {
                if (spec.goalActivePredicate() != null && spec.goalActivePredicate().test(null)) {
                    injections = List.of(RuntimeUtils.buildGoalContinueMessage(spec.goalContinueMessage()));
                }
            }
            if (injections.isEmpty()) {
                return CompletableFuture.completedFuture(new _DrainResult(false, injectionCycles));
            }
            int newCycles = injectionCycles + (realInjection ? 1 : 0);
            if (assistantMessage != null) {
                messages.add(assistantMessage);
                if (iteration != null) {
                    return _emitCheckpoint(spec, new HashMap<>(Map.of(
                        "phase", "final_response",
                        "iteration", iteration,
                        "model", spec.model(),
                        "assistant_message", assistantMessage,
                        "completed_tool_results", List.of(),
                        "pending_tool_calls", List.of()
                    ))).thenApply(_ -> {
                        _appendInjectedMessages(messages, injections);
                        return new _DrainResult(true, newCycles);
                    });
                }
            }
            _appendInjectedMessages(messages, injections);
            return CompletableFuture.completedFuture(new _DrainResult(true, newCycles));
        });
    }

    private CompletableFuture<List<Map<String, Object>>> _drainInjections(AgentRunSpec spec) {
        if (spec.injectionCallback() == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return spec.injectionCallback().apply(MAX_INJECTIONS_PER_TURN)
            .thenApply(items -> {
                if (items == null || items.isEmpty()) return List.of();
                List<Map<String, Object>> injected = new ArrayList<>();
                for (Map<String, Object> item : items) {
                    if ("user".equals(item.get("role")) && item.containsKey("content")) {
                        injected.add(item);
                    } else {
                        String text = item.get("content") != null ? item.get("content").toString() : item.toString();
                        if (!text.trim().isEmpty()) {
                            injected.add(new HashMap<>(Map.of("role", "user", "content", text)));
                        }
                    }
                }
                if (injected.size() > MAX_INJECTIONS_PER_TURN) {
                    return injected.subList(0, MAX_INJECTIONS_PER_TURN);
                }
                return injected;
            })
            .exceptionally(ex -> List.of());
    }

    private CompletableFuture<Void> _emitCheckpoint(AgentRunSpec spec, Map<String, Object> payload) {
        if (spec.checkpointCallback() != null) {
            return spec.checkpointCallback().apply(payload);
        }
        return CompletableFuture.completedFuture(null);
    }

    private static void _appendFinalMessage(List<Map<String, Object>> messages, String content) {
        if (content == null || content.isEmpty()) return;
        if (!messages.isEmpty() && "assistant".equals(messages.get(messages.size() - 1).get("role"))
                && messages.get(messages.size() - 1).get("tool_calls") == null) {
            if (content.equals(messages.get(messages.size() - 1).get("content"))) return;
            Map<String, Object> merged = new LinkedHashMap<>(messages.get(messages.size() - 1));
            merged.put("content", content);
            messages.set(messages.size() - 1, merged);
            return;
        }
        messages.add(RuntimeUtils.buildAssistantMessage(content, null, null, null));
    }

    private static void _appendModelErrorPlaceholder(List<Map<String, Object>> messages) {
        if (!messages.isEmpty() && "assistant".equals(messages.get(messages.size() - 1).get("role"))
                && messages.get(messages.size() - 1).get("tool_calls") == null) {
            return;
        }
        messages.add(RuntimeUtils.buildAssistantMessage(PERSISTED_MODEL_ERROR_PLACEHOLDER, null, null, null));
    }

    private Object _normalizeToolResult(AgentRunSpec spec, String toolCallId, String toolName, Object result) {
        result = RuntimeUtils.ensureNonemptyToolResult(toolName, result);
        if (TOOL_RESULT_OFFLOAD_EXEMPT_TOOLS.contains(toolName)) {
            return result;
        }
        Object content;
        try {
            content = RuntimeUtils.maybePersistToolResult(
                spec.workspace(), spec.sessionKey(), toolCallId, result, spec.maxToolResultChars());
        } catch (Exception e) {
            content = result;
        }
        if (content instanceof String str && str.length() > spec.maxToolResultChars()) {
            return RuntimeUtils.truncateText(str, spec.maxToolResultChars());
        }
        return content;
    }

    // ----- Context Governance -----

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> _dropOrphanToolResults(List<Map<String, Object>> messages) {
        Set<String> declared = new HashSet<>();
        List<Map<String, Object>> updated = null;
        for (int idx = 0; idx < messages.size(); idx++) {
            Map<String, Object> msg = messages.get(idx);
            String role = (String) msg.get("role");
            if ("assistant".equals(role)) {
                List<Map<String, Object>> tcs = (List<Map<String, Object>>) msg.get("tool_calls");
                if (tcs != null) {
                    for (Map<String, Object> tc : tcs) {
                        if (tc != null && tc.get("id") != null) {
                            declared.add(tc.get("id").toString());
                        }
                    }
                }
            }
            if ("tool".equals(role)) {
                String tid = (String) msg.get("tool_call_id");
                if (tid != null && !declared.contains(tid)) {
                    if (updated == null) {
                        updated = new ArrayList<>();
                        for (int j = 0; j < idx; j++) updated.add(new LinkedHashMap<>(messages.get(j)));
                    }
                    continue;
                }
            }
            if (updated != null) {
                updated.add(new LinkedHashMap<>(msg));
            }
        }
        return updated != null ? updated : messages;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> _backfillMissingToolResults(List<Map<String, Object>> messages) {
        List<Object[]> declared = new ArrayList<>(); // [assistant_idx, call_id, name]
        Set<String> fulfilled = new HashSet<>();
        for (int idx = 0; idx < messages.size(); idx++) {
            Map<String, Object> msg = messages.get(idx);
            String role = (String) msg.get("role");
            if ("assistant".equals(role)) {
                List<Map<String, Object>> tcs = (List<Map<String, Object>>) msg.get("tool_calls");
                if (tcs != null) {
                    for (Map<String, Object> tc : tcs) {
                        if (tc != null && tc.get("id") != null) {
                            String name = "";
                            Map<String, Object> func = (Map<String, Object>) tc.get("function");
                            if (func != null) name = (String) func.get("name");
                            declared.add(new Object[]{idx, tc.get("id").toString(), name});
                        }
                    }
                }
            } else if ("tool".equals(role)) {
                String tid = (String) msg.get("tool_call_id");
                if (tid != null) fulfilled.add(tid);
            }
        }
        List<Object[]> missing = new ArrayList<>();
        for (Object[] d : declared) {
            if (!fulfilled.contains((String) d[1])) missing.add(d);
        }
        if (missing.isEmpty()) return messages;

        List<Map<String, Object>> updated = new ArrayList<>();
        for (Map<String, Object> m : messages) updated.add(new LinkedHashMap<>(m));
        int offset = 0;
        for (Object[] m : missing) {
            int assistantIdx = (Integer) m[0];
            String callId = (String) m[1];
            String name = (String) m[2];
            int insertAt = assistantIdx + 1 + offset;
            while (insertAt < updated.size() && "tool".equals(updated.get(insertAt).get("role"))) {
                insertAt++;
            }
            Map<String, Object> fill = new LinkedHashMap<>();
            fill.put("role", "tool");
            fill.put("tool_call_id", callId);
            fill.put("name", name);
            fill.put("content", BACKFILL_CONTENT);
            updated.add(insertAt, fill);
            offset++;
        }
        return updated;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> _microcompact(List<Map<String, Object>> messages) {
        List<Integer> compactableIndices = new ArrayList<>();
        for (int idx = 0; idx < messages.size(); idx++) {
            Map<String, Object> msg = messages.get(idx);
            if ("tool".equals(msg.get("role")) && COMPACTABLE_TOOLS.contains(msg.get("name"))) {
                compactableIndices.add(idx);
            }
        }
        if (compactableIndices.size() <= MICROCOMPACT_KEEP_RECENT) return messages;
        List<Integer> stale = compactableIndices.subList(0, compactableIndices.size() - MICROCOMPACT_KEEP_RECENT);
        List<Map<String, Object>> updated = null;
        for (int idx : stale) {
            Map<String, Object> msg = messages.get(idx);
            Object content = msg.get("content");
            if (!(content instanceof String str) || str.length() < MICROCOMPACT_MIN_CHARS) continue;
            String name = (String) msg.getOrDefault("name", "tool");
            String summary = "[" + name + " result omitted from context]";
            if (updated == null) {
                updated = new ArrayList<>();
                for (Map<String, Object> m : messages) updated.add(new LinkedHashMap<>(m));
            }
            updated.get(idx).put("content", summary);
        }
        return updated != null ? updated : messages;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> _applyToolResultBudget(AgentRunSpec spec, List<Map<String, Object>> messages) {
        List<Map<String, Object>> updated = messages;
        for (int idx = 0; idx < messages.size(); idx++) {
            Map<String, Object> msg = messages.get(idx);
            if (!"tool".equals(msg.get("role"))) continue;
            String tci = msg.get("tool_call_id") != null ? msg.get("tool_call_id").toString() : "tool_" + idx;
            String name = msg.get("name") != null ? msg.get("name").toString() : "tool";
            Object normalized = _normalizeToolResult(spec, tci, name, msg.get("content"));
            if (!Objects.equals(normalized, msg.get("content"))) {
                if (updated == messages) {
                    updated = new ArrayList<>();
                    for (Map<String, Object> m : messages) updated.add(new LinkedHashMap<>(m));
                }
                updated.get(idx).put("content", normalized);
            }
        }
        return updated;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> _snipHistory(AgentRunSpec spec, List<Map<String, Object>> messages) {
        if (messages.isEmpty() || spec.contextWindowTokens() == null) return messages;
        int providerMaxTokens = 4096;
        try {
            Object generation = provider.getClass().getMethod("generation").invoke(provider);
            providerMaxTokens = (Integer) generation.getClass().getMethod("maxTokens").invoke(generation);
        } catch (Exception ignored) {}
        int maxOutput = spec.maxTokens() != null ? spec.maxTokens() : providerMaxTokens;
        int budget = spec.contextBlockLimit() != null ? spec.contextBlockLimit()
            : spec.contextWindowTokens() - maxOutput - SNIP_SAFETY_BUFFER;
        if (budget <= 0) return messages;

        int estimate = RuntimeUtils.estimatePromptTokensChain(provider, spec.model(), messages, spec.tools().getDefinitions());
        if (estimate <= budget) return messages;

        List<Map<String, Object>> systemMessages = new ArrayList<>();
        List<Map<String, Object>> nonSystem = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            if ("system".equals(msg.get("role"))) systemMessages.add(new LinkedHashMap<>(msg));
            else nonSystem.add(new LinkedHashMap<>(msg));
        }
        if (nonSystem.isEmpty()) return messages;

        int systemTokens = systemMessages.stream().mapToInt(RuntimeUtils::estimateMessageTokens).sum();
        int fixedTokens = RuntimeUtils.estimatePromptTokensChain(provider, spec.model(), systemMessages, spec.tools().getDefinitions());
        int remainingBudget = Math.max(0, budget - Math.max(systemTokens, fixedTokens));

        List<Map<String, Object>> kept = new ArrayList<>();
        int keptTokens = 0;
        for (int i = nonSystem.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = nonSystem.get(i);
            int msgTokens = RuntimeUtils.estimateMessageTokens(msg);
            if (!kept.isEmpty() && keptTokens + msgTokens > remainingBudget) break;
            kept.add(msg);
            keptTokens += msgTokens;
        }
        Collections.reverse(kept);

        if (!kept.isEmpty()) {
            boolean foundUser = false;
            for (int i = 0; i < kept.size(); i++) {
                if ("user".equals(kept.get(i).get("role"))) {
                    kept = kept.subList(i, kept.size());
                    foundUser = true;
                    break;
                }
            }
            if (!foundUser) {
                for (int i = nonSystem.size() - 1; i >= 0; i--) {
                    if ("user".equals(nonSystem.get(i).get("role"))) {
                        kept = nonSystem.subList(i, nonSystem.size());
                        break;
                    }
                }
            }
            int start = RuntimeUtils.findLegalMessageStart(kept);
            if (start > 0) kept = kept.subList(start, kept.size());
        }
        if (kept.isEmpty()) {
            kept = nonSystem.subList(Math.max(0, nonSystem.size() - 4), nonSystem.size());
            int start = RuntimeUtils.findLegalMessageStart(kept);
            if (start > 0) kept = kept.subList(start, kept.size());
        }
        List<Map<String, Object>> result = new ArrayList<>(systemMessages);
        result.addAll(kept);
        return result;
    }

    // ----- Violation Classification -----

    private static final List<String> SSRF_MARKERS = List.of(
        "internal/private url detected",
        "private/internal address",
        "private address"
    );
    private static final String SSRF_BOUNDARY_NOTE = (
        "This is a non-bypassable security boundary. Stop trying to access "
        + "private/internal URLs. Do not retry with curl, wget, encoded IPs, "
        + "alternate DNS, redirects, proxies, or another tool. Ask the user for "
        + "local files, logs, screenshots, or an explicit safe public URL instead. "
        + "If the user explicitly trusts this private URL, ask them to whitelist "
        + "the exact IP/CIDR via tools.ssrfWhitelist."
    );
    private static final List<String> WORKSPACE_VIOLATION_MARKERS = List.of(
        "outside the configured workspace",
        "outside allowed directory",
        "working_dir is outside",
        "working_dir could not be resolved",
        "path outside working dir",
        "path traversal detected"
    );

    private _ToolOutcome _classifyViolation(String rawText, String softPayload,
            Map<String, String> event, ToolCallRequest toolCall,
            Map<String, Integer> workspaceViolationCounts) {
        if (_isSsrfViolation(rawText)) {
            event.put("detail", _eventDetail("ssrf_violation: ", rawText));
            return new _ToolOutcome(_ssrfSoftPayload(rawText), event, null);
        }
        if (_isWorkspaceViolation(rawText)) {
            String escalation = RuntimeUtils.repeatedWorkspaceViolationError(
                toolCall.name(), toolCall.arguments(), workspaceViolationCounts);
            event.put("detail", _eventDetail("workspace_violation: ", rawText));
            if (escalation != null) {
                event.put("detail", _eventDetail("workspace_violation_escalated: ", rawText));
                return new _ToolOutcome(escalation, event, null);
            }
            return new _ToolOutcome(softPayload, event, null);
        }
        return null;
    }

    private static boolean _isSsrfViolation(String text) {
        if (text == null || text.isEmpty()) return false;
        String lowered = text.toLowerCase();
        return SSRF_MARKERS.stream().anyMatch(lowered::contains);
    }

    private static boolean _isWorkspaceViolation(String text) {
        if (text == null || text.isEmpty()) return false;
        String lowered = text.toLowerCase();
        if (_isSsrfViolation(lowered)) return true;
        return WORKSPACE_VIOLATION_MARKERS.stream().anyMatch(lowered::contains);
    }

    private static String _ssrfSoftPayload(String rawText) {
        String text = rawText != null ? rawText.strip() : "Error: request blocked by SSRF guard";
        return text + "\n\n" + SSRF_BOUNDARY_NOTE;
    }

    private static String _eventDetail(String prefix, String text) {
        String cleaned = text.replace("\n", " ").strip();
        String full = prefix + cleaned;
        return full.length() > 160 ? full.substring(0, 160) : full;
    }

    // ----- Usage Helpers -----

    private Map<String, Integer> _usageOrEstimate(AgentRunSpec spec,
            List<Map<String, Object>> messages, LLMResponse response) {
        Map<String, Integer> usage = _usageDict(response.usage());
        int total = _usageTotal(usage);
        if (total > 0) {
            usage.put("total_tokens", total);
            usage.putIfAbsent("provider_tokens", total);
            return usage;
        }
        if ("error".equals(response.finishReason())) return new HashMap<>();
        return _estimateResponseUsage(spec, messages, response);
    }

    private Map<String, Integer> _estimateResponseUsage(AgentRunSpec spec,
            List<Map<String, Object>> messages, LLMResponse response) {
        List<Map<String, Object>> tools;
        try { tools = spec.tools().getDefinitions(); } catch (Exception e) { tools = null; }
        int promptTokens = RuntimeUtils.estimatePromptTokensChain(provider, spec.model(), messages, tools);
        Map<String, Object> assistantMessage = RuntimeUtils.buildAssistantMessage(
            response.content() != null ? response.content() : "",
            response.toolCalls(), response.reasoningContent(), response.thinkingBlocks());
        int completionTokens = RuntimeUtils.estimateMessageTokens(assistantMessage);
        int total = Math.max(0, promptTokens) + Math.max(0, completionTokens);
        if (total <= 0) return new HashMap<>();
        return new HashMap<>(Map.of(
            "prompt_tokens", Math.max(0, promptTokens),
            "completion_tokens", Math.max(0, completionTokens),
            "total_tokens", total,
            "estimated_tokens", total
        ));
    }

    private static Map<String, Integer> _usageDict(Map<String, Object> usage) {
        if (usage == null) return new HashMap<>();
        Map<String, Integer> result = new HashMap<>();
        for (Map.Entry<String, Object> e : usage.entrySet()) {
            try { result.put(e.getKey(), Integer.valueOf(String.valueOf(e.getValue()))); }
            catch (NumberFormatException ignored) {}
        }
        return result;
    }

    private static int _usageTotal(Map<String, Integer> usage) {
        int total = usage.getOrDefault("total_tokens", 0);
        if (total == 0) {
            total = usage.getOrDefault("prompt_tokens", 0) + usage.getOrDefault("completion_tokens", 0);
        }
        return Math.max(0, total);
    }

    private static void _accumulateUsage(Map<String, Integer> target, Map<String, Integer> addition) {
        for (Map.Entry<String, Integer> e : addition.entrySet()) {
            target.put(e.getKey(), target.getOrDefault(e.getKey(), 0) + e.getValue());
        }
    }

    private static Map<String, Integer> _mergeUsage(Map<String, Integer> left, Map<String, Integer> right) {
        Map<String, Integer> merged = new HashMap<>(left);
        for (Map.Entry<String, Integer> e : right.entrySet()) {
            merged.put(e.getKey(), merged.getOrDefault(e.getKey(), 0) + e.getValue());
        }
        return merged;
    }

    // ----- Content Merge -----

    @SuppressWarnings("unchecked")
    private static Object _mergeMessageContent(Object left, Object right) {
        if (left instanceof String ls && right instanceof String rs) {
            return ls.isEmpty() ? rs : ls + "\n\n" + rs;
        }
        List<Map<String, Object>> result = new ArrayList<>(_toBlocks(left));
        result.addAll(_toBlocks(right));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> _toBlocks(Object value) {
        if (value instanceof List) return new ArrayList<>((List<Map<String, Object>>) value);
        if (value == null) return new ArrayList<>();
        return new ArrayList<>(List.of(Map.of("type", "text", "text", String.valueOf(value))));
    }

    @SuppressWarnings("unchecked")
    private static void _appendInjectedMessages(List<Map<String, Object>> messages,
            List<Map<String, Object>> injections) {
        for (Map<String, Object> injection : injections) {
            if (!messages.isEmpty() && "user".equals(injection.get("role"))
                    && "user".equals(messages.get(messages.size() - 1).get("role"))) {
                Map<String, Object> merged = new LinkedHashMap<>(messages.get(messages.size() - 1));
                merged.put("content", _mergeMessageContent(
                    merged.get("content"), injection.get("content")));
                messages.set(messages.size() - 1, merged);
                continue;
            }
            messages.add(injection);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> deepCopy(List<Map<String, Object>> original) {
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> m : original) {
            copy.add(new LinkedHashMap<>(m));
        }
        return copy;
    }
}
```

### 4. AgentRunSpec / AgentRunResult — Builder Pattern

```java
package com.nanobot.agent;

import com.nanobot.agent.tools.ToolRegistry;
import com.nanobot.providers.LLMProvider;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.BiFunction;

public class AgentRunSpec {

    private final List<Map<String, Object>> initialMessages;
    private final ToolRegistry tools;
    private final String model;
    private final int maxIterations;
    private final int maxToolResultChars;
    private final Double temperature;
    private final Integer maxTokens;
    private final String reasoningEffort;
    private final AgentHook hook;
    private final String errorMessage;
    private final String maxIterationsMessage;
    private final boolean concurrentTools;
    private final boolean failOnToolError;
    private final Path workspace;
    private final String sessionKey;
    private final Integer contextWindowTokens;
    private final Integer contextBlockLimit;
    private final String providerRetryMode;
    private final BiFunction<String, Map<String, Object>, CompletableFuture<Void>> progressCallback;
    private final boolean streamProgressDeltas;
    private final java.util.function.Function<String, CompletableFuture<Void>> retryWaitCallback;
    private final java.util.function.Function<Map<String, Object>, CompletableFuture<Void>> checkpointCallback;
    private final java.util.function.Function<Integer, CompletableFuture<List<Map<String, Object>>>> injectionCallback;
    private final Double llmTimeoutS;
    private final Predicate<Void> goalActivePredicate;
    private final String goalContinueMessage;
    private final boolean finalizeOnMaxIterations;

    // 使用 Builder 模式（Lombok @Builder 或手写）
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private List<Map<String, Object>> initialMessages;
        private ToolRegistry tools;
        private String model;
        private int maxIterations = 200;
        private int maxToolResultChars = 100_000;
        private Double temperature = null;
        private Integer maxTokens = null;
        private String reasoningEffort = null;
        private AgentHook hook = null;
        private String errorMessage = "Sorry, I encountered an error calling the AI model.";
        private String maxIterationsMessage = null;
        private boolean concurrentTools = false;
        private boolean failOnToolError = false;
        private Path workspace = null;
        private String sessionKey = null;
        private Integer contextWindowTokens = null;
        private Integer contextBlockLimit = null;
        private String providerRetryMode = "standard";
        private BiFunction<String, Map<String, Object>, CompletableFuture<Void>> progressCallback = null;
        private boolean streamProgressDeltas = true;
        private java.util.function.Function<String, CompletableFuture<Void>> retryWaitCallback = null;
        private java.util.function.Function<Map<String, Object>, CompletableFuture<Void>> checkpointCallback = null;
        private java.util.function.Function<Integer, CompletableFuture<List<Map<String, Object>>>> injectionCallback = null;
        private Double llmTimeoutS = null;
        private Predicate<Void> goalActivePredicate = null;
        private String goalContinueMessage = null;
        private boolean finalizeOnMaxIterations = true;

        public Builder initialMessages(List<Map<String, Object>> v) { this.initialMessages = v; return this; }
        public Builder tools(ToolRegistry v) { this.tools = v; return this; }
        public Builder model(String v) { this.model = v; return this; }
        public Builder maxIterations(int v) { this.maxIterations = v; return this; }
        public Builder maxToolResultChars(int v) { this.maxToolResultChars = v; return this; }
        public Builder temperature(Double v) { this.temperature = v; return this; }
        public Builder maxTokens(Integer v) { this.maxTokens = v; return this; }
        public Builder reasoningEffort(String v) { this.reasoningEffort = v; return this; }
        public Builder hook(AgentHook v) { this.hook = v; return this; }
        public Builder errorMessage(String v) { this.errorMessage = v; return this; }
        public Builder maxIterationsMessage(String v) { this.maxIterationsMessage = v; return this; }
        public Builder concurrentTools(boolean v) { this.concurrentTools = v; return this; }
        public Builder failOnToolError(boolean v) { this.failOnToolError = v; return this; }
        public Builder workspace(Path v) { this.workspace = v; return this; }
        public Builder sessionKey(String v) { this.sessionKey = v; return this; }
        public Builder contextWindowTokens(Integer v) { this.contextWindowTokens = v; return this; }
        public Builder contextBlockLimit(Integer v) { this.contextBlockLimit = v; return this; }
        public Builder providerRetryMode(String v) { this.providerRetryMode = v; return this; }
        public Builder progressCallback(BiFunction<String, Map<String, Object>, CompletableFuture<Void>> v) { this.progressCallback = v; return this; }
        public Builder streamProgressDeltas(boolean v) { this.streamProgressDeltas = v; return this; }
        public Builder retryWaitCallback(java.util.function.Function<String, CompletableFuture<Void>> v) { this.retryWaitCallback = v; return this; }
        public Builder checkpointCallback(java.util.function.Function<Map<String, Object>, CompletableFuture<Void>> v) { this.checkpointCallback = v; return this; }
        public Builder injectionCallback(java.util.function.Function<Integer, CompletableFuture<List<Map<String, Object>>>> v) { this.injectionCallback = v; return this; }
        public Builder llmTimeoutS(Double v) { this.llmTimeoutS = v; return this; }
        public Builder goalActivePredicate(Predicate<Void> v) { this.goalActivePredicate = v; return this; }
        public Builder goalContinueMessage(String v) { this.goalContinueMessage = v; return this; }
        public Builder finalizeOnMaxIterations(boolean v) { this.finalizeOnMaxIterations = v; return this; }

        public AgentRunSpec build() {
            return new AgentRunSpec(this);
        }
    }

    private AgentRunSpec(Builder b) {
        this.initialMessages = b.initialMessages;
        this.tools = b.tools;
        this.model = b.model;
        this.maxIterations = b.maxIterations;
        this.maxToolResultChars = b.maxToolResultChars;
        this.temperature = b.temperature;
        this.maxTokens = b.maxTokens;
        this.reasoningEffort = b.reasoningEffort;
        this.hook = b.hook;
        this.errorMessage = b.errorMessage;
        this.maxIterationsMessage = b.maxIterationsMessage;
        this.concurrentTools = b.concurrentTools;
        this.failOnToolError = b.failOnToolError;
        this.workspace = b.workspace;
        this.sessionKey = b.sessionKey;
        this.contextWindowTokens = b.contextWindowTokens;
        this.contextBlockLimit = b.contextBlockLimit;
        this.providerRetryMode = b.providerRetryMode;
        this.progressCallback = b.progressCallback;
        this.streamProgressDeltas = b.streamProgressDeltas;
        this.retryWaitCallback = b.retryWaitCallback;
        this.checkpointCallback = b.checkpointCallback;
        this.injectionCallback = b.injectionCallback;
        this.llmTimeoutS = b.llmTimeoutS;
        this.goalActivePredicate = b.goalActivePredicate;
        this.goalContinueMessage = b.goalContinueMessage;
        this.finalizeOnMaxIterations = b.finalizeOnMaxIterations;
    }

    // getters
}

/**
 * 对标 Python {@code AgentRunResult} — Runner 执行结果。
 */
public record AgentRunResult(
    String finalContent,
    List<Map<String, Object>> messages,
    List<String> toolsUsed,
    Map<String, Integer> usage,
    String stopReason,
    String error,
    List<Map<String, String>> toolEvents,
    boolean hadInjections
) {
    public AgentRunResult {
        toolsUsed = toolsUsed != null ? List.copyOf(toolsUsed) : List.of();
        usage = usage != null ? Map.copyOf(usage) : Map.of();
        toolEvents = toolEvents != null ? List.copyOf(toolEvents) : List.of();
        stopReason = stopReason != null ? stopReason : "completed";
    }
}
```

### 5. AgentHook + CompositeHook

```java
package com.nanobot.agent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 生命周期 Hook 接口。
 * 对标 Python {@code AgentHook} — 所有回调均为 async def，故 Java 侧返回
 * {@link CompletableFuture<Void>}。仅 {@link #wantsStreaming()} 与
 * {@link #finalizeContent(AgentHookContext, String)} 为同步（与 Python 一致）。
 */
public interface AgentHook {

    default boolean wantsStreaming() { return false; }

    default CompletableFuture<Void> beforeRun(AgentRunHookContext context) {
        return CompletableFuture.completedFuture(null);
    }

    default CompletableFuture<Void> afterRun(AgentRunHookContext context) {
        return CompletableFuture.completedFuture(null);
    }

    default CompletableFuture<Void> onError(AgentRunHookContext context) {
        return CompletableFuture.completedFuture(null);
    }

    default CompletableFuture<Void> onFinally(AgentRunHookContext context) {
        return CompletableFuture.completedFuture(null);
    }

    default CompletableFuture<Void> beforeIteration(AgentHookContext context) {
        return CompletableFuture.completedFuture(null);
    }

    default CompletableFuture<Void> onStream(AgentHookContext context, String delta) {
        return CompletableFuture.completedFuture(null);
    }

    default CompletableFuture<Void> onStreamEnd(AgentHookContext context, boolean resuming) {
        return CompletableFuture.completedFuture(null);
    }

    default CompletableFuture<Void> beforeExecuteTools(AgentHookContext context) {
        return CompletableFuture.completedFuture(null);
    }

    default CompletableFuture<Void> emitReasoning(String reasoningContent) {
        return CompletableFuture.completedFuture(null);
    }

    default CompletableFuture<Void> emitReasoningEnd() {
        return CompletableFuture.completedFuture(null);
    }

    default CompletableFuture<Void> afterIteration(AgentHookContext context) {
        return CompletableFuture.completedFuture(null);
    }

    default String finalizeContent(AgentHookContext context, String content) { return content; }

    static AgentHook composite(AgentHook... hooks) {
        return new CompositeHook(List.of(hooks));
    }
}

class CompositeHook implements AgentHook {
    private final List<AgentHook> hooks;

    CompositeHook(List<AgentHook> hooks) { this.hooks = List.copyOf(hooks); }

    @Override
    public boolean wantsStreaming() {
        return hooks.stream().anyMatch(AgentHook::wantsStreaming);
    }

    @Override
    public CompletableFuture<Void> beforeRun(AgentRunHookContext context) {
        return forEachHookSafe(hook -> hook.beforeRun(context));
    }

    @Override
    public CompletableFuture<Void> afterRun(AgentRunHookContext context) {
        return forEachHookSafe(hook -> hook.afterRun(context));
    }

    @Override
    public CompletableFuture<Void> onError(AgentRunHookContext context) {
        return forEachHookSafe(hook -> hook.onError(context));
    }

    @Override
    public CompletableFuture<Void> onFinally(AgentRunHookContext context) {
        return forEachHookSafe(hook -> hook.onFinally(context));
    }

    @Override
    public CompletableFuture<Void> beforeIteration(AgentHookContext context) {
        return forEachHookSafe(hook -> hook.beforeIteration(context));
    }

    @Override
    public CompletableFuture<Void> onStream(AgentHookContext context, String delta) {
        return forEachHookSafe(hook -> hook.onStream(context, delta));
    }

    @Override
    public CompletableFuture<Void> onStreamEnd(AgentHookContext context, boolean resuming) {
        return forEachHookSafe(hook -> hook.onStreamEnd(context, resuming));
    }

    @Override
    public CompletableFuture<Void> beforeExecuteTools(AgentHookContext context) {
        return forEachHookSafe(hook -> hook.beforeExecuteTools(context));
    }

    @Override
    public CompletableFuture<Void> emitReasoning(String reasoningContent) {
        return forEachHookSafe(hook -> hook.emitReasoning(reasoningContent));
    }

    @Override
    public CompletableFuture<Void> emitReasoningEnd() {
        return forEachHookSafe(AgentHook::emitReasoningEnd);
    }

    @Override
    public CompletableFuture<Void> afterIteration(AgentHookContext context) {
        return forEachHookSafe(hook -> hook.afterIteration(context));
    }

    @Override
    public String finalizeContent(AgentHookContext context, String content) {
        String result = content;
        for (AgentHook hook : hooks) {
            result = hook.finalizeContent(context, result);
        }
        return result;
    }

    private CompletableFuture<Void> forEachHookSafe(java.util.function.Function<AgentHook, CompletableFuture<Void>> action) {
        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (AgentHook hook : hooks) {
            future = future.thenCompose(_ -> {
                try {
                    return action.apply(hook);
                } catch (Exception e) {
                    log.warn("Hook {} error", hook.getClass().getSimpleName(), e);
                    return CompletableFuture.completedFuture(null);
                }
            }).exceptionally(ex -> {
                log.warn("Hook {} error", hook.getClass().getSimpleName(), ex);
                return null;
            });
        }
        return future;
    }
}
```

### 6. AgentProgressHook

```java
package com.nanobot.agent;

import com.nanobot.utils.IncrementalThinkExtractor;
import com.nanobot.utils.StripThink;
import com.nanobot.utils.FormatToolHints;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 对标 Python {@code AgentProgressHook} — 将 runner 生命周期事件转换为用户可见的进度信号。
 * 接收回调函数（非直接操作 MessageBus），与 Python 设计一致。
 */
public class AgentProgressHook implements AgentHook {

    private final BiFunction<String, Map<String, Object>, CompletableFuture<Void>> onProgress;
    private final Function<String, CompletableFuture<Void>> onStream;
    private final Function<Boolean, CompletableFuture<Void>> onStreamEnd;
    private final String channel;
    private final String chatId;
    private final String messageId;
    private final Map<String, Object> metadata;
    private final String sessionKey;
    private final int toolHintMaxLength;
    private final Consumer<Map<String, Object>> setToolContext;
    private final Consumer<Integer> onIteration;

    private String streamBuf = "";
    private final IncrementalThinkExtractor thinkExtractor = new IncrementalThinkExtractor();
    private boolean reasoningOpen = false;

    public AgentProgressHook(
        BiFunction<String, Map<String, Object>, CompletableFuture<Void>> onProgress,
        Function<String, CompletableFuture<Void>> onStream,
        Function<Boolean, CompletableFuture<Void>> onStreamEnd,
        String channel,
        String chatId,
        String messageId,
        Map<String, Object> metadata,
        String sessionKey,
        int toolHintMaxLength,
        Consumer<Map<String, Object>> setToolContext,
        Consumer<Integer> onIteration
    ) {
        this.onProgress = onProgress;
        this.onStream = onStream;
        this.onStreamEnd = onStreamEnd;
        this.channel = channel;
        this.chatId = chatId;
        this.messageId = messageId;
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        this.sessionKey = sessionKey;
        this.toolHintMaxLength = toolHintMaxLength;
        this.setToolContext = setToolContext;
        this.onIteration = onIteration;
    }

    @Override
    public boolean wantsStreaming() {
        return onStream != null;
    }

    @Override
    public CompletableFuture<Void> onStream(AgentHookContext context, String delta) {
        return CompletableFuture.runAsync(() -> {
            String prevClean = StripThink.strip(streamBuf);
            streamBuf += delta;
            String newClean = StripThink.strip(streamBuf);
            String incremental = newClean.substring(prevClean != null ? prevClean.length() : 0);

            // reasoning 处理（对标 IncrementalThinkExtractor.feed）
            if (thinkExtractor.feed(streamBuf)) {
                reasoningOpen = true;
                if (onProgress != null) {
                    onProgress.apply("", Map.of("reasoning", true));
                }
            }
            if (reasoningOpen && incremental != null && !incremental.isEmpty()) {
                reasoningOpen = false;
                if (onProgress != null) {
                    onProgress.apply("", Map.of("reasoning_end", true));
                }
            }
            if (incremental != null && !incremental.isEmpty() && onStream != null) {
                onStream.apply(incremental);
            }
        });
    }

    @Override
    public CompletableFuture<Void> onStreamEnd(AgentHookContext context, boolean resuming) {
        return CompletableFuture.runAsync(() -> {
            if (onStreamEnd != null) {
                onStreamEnd.apply(resuming);
            }
            streamBuf = "";
            thinkExtractor.reset();
        });
    }

    @Override
    public CompletableFuture<Void> beforeIteration(AgentHookContext context) {
        return CompletableFuture.runAsync(() -> {
            if (onIteration != null) {
                onIteration.accept(context.iteration);
            }
        });
    }

    @Override
    public CompletableFuture<Void> beforeExecuteTools(AgentHookContext context) {
        return CompletableFuture.runAsync(() -> {
            if (onProgress != null) {
                String toolHint = FormatToolHints.format(context.toolCalls, toolHintMaxLength);
                if (toolHint != null && !toolHint.isEmpty()) {
                    onProgress.apply(toolHint, Map.of("tool_hint", true));
                }
                for (Map<String, String> event : context.toolEvents) {
                    onProgress.apply("", Map.of("tool_event", event));
                }
            }
            if (setToolContext != null) {
                setToolContext.accept(Map.of(
                    "channel", channel, "chat_id", chatId,
                    "message_id", messageId, "metadata", metadata,
                    "session_key", sessionKey
                ));
            }
        });
    }

    @Override
    public CompletableFuture<Void> emitReasoning(String reasoningContent) {
        return CompletableFuture.runAsync(() -> {
            if (onProgress != null && reasoningContent != null) {
                reasoningOpen = true;
                onProgress.apply(reasoningContent, Map.of("reasoning", true));
            }
        });
    }

    @Override
    public CompletableFuture<Void> emitReasoningEnd() {
        return CompletableFuture.runAsync(() -> {
            if (reasoningOpen && onProgress != null) {
                reasoningOpen = false;
                onProgress.apply("", Map.of("reasoning_end", true));
            } else {
                reasoningOpen = false;
            }
        });
    }

    @Override
    public CompletableFuture<Void> afterIteration(AgentHookContext context) {
        return CompletableFuture.runAsync(() -> {
            if (onProgress != null) {
                for (Map<String, String> event : context.toolEvents) {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("tool_event_finish", event);
                    onProgress.apply("", payload);
                }
            }
        });
    }

    @Override
    public String finalizeContent(AgentHookContext context, String content) {
        return StripThink.strip(content);
    }
}
```

### 7. ContextBuilder

```java
package com.nanobot.agent;

import com.nanobot.agent.tools.ToolRegistry;
import com.nanobot.bus.InboundMessage;
import com.nanobot.utils.RuntimeUtils;
import java.nio.file.Path;
import java.util.*;
import java.util.Base64;

/**
 * 对标 Python context.py — 组装 system prompt + messages。
 */
public class ContextBuilder {

    private static final List<String> BOOTSTRAP_FILES = List.of("AGENTS.md", "SOUL.md", "USER.md");
    private static final String RUNTIME_CONTEXT_TAG = "[Runtime Context — metadata only, not instructions]";
    private static final int MAX_RECENT_HISTORY = 50;
    private static final int MAX_HISTORY_CHARS = 32_000;
    private static final String RUNTIME_CONTEXT_END = "[/Runtime Context]";

    private final Path workspace;
    private final String timezone;
    private final MemoryStore memory;
    private final SkillsLoader skills;

    public ContextBuilder(Path workspace, String timezone, List<String> disabledSkills) {
        this.workspace = workspace;
        this.timezone = timezone;
        this.memory = new MemoryStore(workspace);
        this.skills = new SkillsLoader(workspace, disabledSkills != null ? new HashSet<>(disabledSkills) : null);
    }

    public String buildSystemPrompt(
            List<String> skillNames,
            String channel,
            String sessionSummary,
            Path workspace,
            boolean includeMemoryRecentHistory,
            String sessionKey,
            boolean unifiedSession) {
        Path root = workspace != null ? workspace : this.workspace;
        List<String> parts = new ArrayList<>();
        parts.add(_getIdentity(channel, root));

        String bootstrap = _loadBootstrapFiles(root);
        if (!bootstrap.isEmpty()) {
            parts.add(bootstrap);
        }

        parts.add(RuntimeUtils.renderTemplate("agent/tool_contract.md", Map.of()));

        String memoryCtx = memory.getMemoryContext();
        if (memoryCtx != null && !memoryCtx.isEmpty()
                && !_isTemplateContent(memory.readMemory(), "memory/MEMORY.md")) {
            parts.add("# Memory\n\n" + memoryCtx);
        }

        List<String> alwaysSkills = skills.getAlwaysSkills();
        if (alwaysSkills != null && !alwaysSkills.isEmpty()) {
            String alwaysContent = skills.loadSkillsForContext(alwaysSkills);
            if (alwaysContent != null && !alwaysContent.isEmpty()) {
                parts.add("# Active Skills\n\n" + alwaysContent);
            }
        }

        String skillsSummary = skills.buildSkillsSummary(new HashSet<>(alwaysSkills != null ? alwaysSkills : List.of()));
        if (skillsSummary != null && !skillsSummary.isEmpty()) {
            parts.add(RuntimeUtils.renderTemplate("agent/skills_section.md", Map.of("skills_summary", skillsSummary)));
        }

        if (includeMemoryRecentHistory) {
            List<Map<String, Object>> entries = memory.readRecentHistoryForPrompt(
                memory.getLastDreamCursor(), sessionKey, unifiedSession);
            if (entries != null && !entries.isEmpty()) {
                int fromIndex = Math.max(0, entries.size() - MAX_RECENT_HISTORY);
                List<Map<String, Object>> capped = entries.subList(fromIndex, entries.size());
                StringBuilder historyText = new StringBuilder();
                for (Map<String, Object> e : capped) {
                    historyText.append("- [").append(e.get("timestamp")).append("] ").append(e.get("content")).append("\n");
                }
                String historyStr = RuntimeUtils.truncateText(historyText.toString().trim(), MAX_HISTORY_CHARS);
                parts.add("# Recent History\n\n" + historyStr);
            }
        }

        if (sessionSummary != null && !sessionSummary.isEmpty()) {
            parts.add("[Archived Context Summary]\n\n" + sessionSummary);
        }

        return String.join("\n\n---\n\n", parts);
    }

    /**
     * 对标 Python {@code build_messages()} — 13+ 参数完整签名。
     */
    public List<Map<String, Object>> buildMessages(
            List<Map<String, Object>> history,
            String currentMessage,
            List<String> skillNames,
            List<String> media,
            String channel,
            String chatId,
            String currentRole,
            String senderId,
            String sessionSummary,
            Map<String, Object> sessionMetadata,
            List<String> currentRuntimeLines,
            Path workspace,
            Object runtimeState,
            InboundMessage inboundMessage,
            boolean skipRuntimeLines,
            boolean includeMemoryRecentHistory,
            String sessionKey,
            boolean unifiedSession) {

        Path root = workspace != null ? workspace : this.workspace;
        List<String> extra = new ArrayList<>();
        extra.addAll(RuntimeUtils.goalStateRuntimeLines(sessionMetadata));
        if (runtimeState != null && inboundMessage != null) {
            extra.addAll(RuntimeUtils.runtimeLines(runtimeState, inboundMessage, root, skipRuntimeLines));
        }
        if (currentRuntimeLines != null) {
            for (String line : currentRuntimeLines) {
                if (line != null && !line.isEmpty()) extra.add(line);
            }
        }
        String runtimeCtx = _buildRuntimeContext(channel, chatId, timezone, senderId, extra);
        Object userContent = _buildUserContent(currentMessage, media);

        // Merge runtime context and user content into a single user message
        Object merged;
        if (userContent instanceof String us) {
            merged = us + "\n\n" + runtimeCtx;
        } else {
            List<Map<String, Object>> blocks = new ArrayList<>((List<Map<String, Object>>) userContent);
            blocks.add(Map.of("type", "text", "text", runtimeCtx));
            merged = blocks;
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of(
            "role", "system",
            "content", buildSystemPrompt(
                skillNames, channel, sessionSummary, root,
                includeMemoryRecentHistory, sessionKey, unifiedSession)
        ));
        messages.addAll(history);

        if (!messages.isEmpty() && currentRole.equals(messages.get(messages.size() - 1).get("role"))) {
            Map<String, Object> last = new LinkedHashMap<>(messages.get(messages.size() - 1));
            last.put("content", _mergeMessageContent(last.get("content"), merged));
            messages.set(messages.size() - 1, last);
            return messages;
        }
        messages.add(new LinkedHashMap<>(Map.of("role", currentRole, "content", merged)));
        return messages;
    }

    private String _getIdentity(String channel, Path workspace) {
        Path root = workspace != null ? workspace : this.workspace;
        String workspacePath = root.toAbsolutePath().normalize().toString();
        String system = System.getProperty("os.name");
        String runtime = ("Mac OS X".equals(system) ? "macOS" : system) + " " + System.getProperty("os.arch")
            + ", Java " + System.getProperty("java.version");
        return RuntimeUtils.renderTemplate("agent/identity.md", Map.of(
            "workspace_path", workspacePath,
            "runtime", runtime,
            "platform_policy", RuntimeUtils.renderTemplate("agent/platform_policy.md", Map.of("system", system)),
            "channel", channel != null ? channel : ""
        ));
    }

    private static String _buildRuntimeContext(String channel, String chatId, String timezone,
            String senderId, List<String> supplementalLines) {
        List<String> lines = new ArrayList<>();
        lines.add("Current Time: " + RuntimeUtils.currentTimeStr(timezone));
        if (channel != null && !channel.isEmpty() && chatId != null && !chatId.isEmpty()) {
            lines.add("Channel: " + channel);
            lines.add("Chat ID: " + chatId);
        }
        if (senderId != null && !senderId.isEmpty()) {
            lines.add("Sender ID: " + senderId);
        }
        if (supplementalLines != null) {
            lines.addAll(supplementalLines);
        }
        return RUNTIME_CONTEXT_TAG + "\n" + String.join("\n", lines) + "\n" + RUNTIME_CONTEXT_END;
    }

    @SuppressWarnings("unchecked")
    private static Object _mergeMessageContent(Object left, Object right) {
        if (left instanceof String ls && right instanceof String rs) {
            return ls.isEmpty() ? rs : ls + "\n\n" + rs;
        }
        List<Map<String, Object>> result = new ArrayList<>(_toBlocks(left));
        result.addAll(_toBlocks(right));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> _toBlocks(Object value) {
        if (value instanceof List) return new ArrayList<>((List<Map<String, Object>>) value);
        if (value == null) return new ArrayList<>();
        return new ArrayList<>(List.of(Map.of("type", "text", "text", String.valueOf(value))));
    }

    private String _loadBootstrapFiles(Path workspace) {
        Path root = workspace != null ? workspace : this.workspace;
        List<String> parts = new ArrayList<>();
        for (String filename : BOOTSTRAP_FILES) {
            Path filePath = root.resolve(filename);
            if (filePath.toFile().exists()) {
                try {
                    String content = java.nio.file.Files.readString(filePath);
                    parts.add("## " + filename + "\n\n" + content);
                } catch (Exception ignored) {}
            }
        }
        return String.join("\n\n", parts);
    }

    private static boolean _isTemplateContent(String content, String templatePath) {
        String tpl = RuntimeUtils.loadBundledTemplate(templatePath);
        if (tpl != null) {
            return content.strip().equals(tpl.strip());
        }
        return false;
    }

    private Object _buildUserContent(String text, List<String> media) {
        if (media == null || media.isEmpty()) {
            return text;
        }
        List<Map<String, Object>> images = new ArrayList<>();
        for (String path : media) {
            Path p = Path.of(path);
            if (!p.toFile().isFile()) continue;
            try {
                byte[] raw = java.nio.file.Files.readAllBytes(p);
                String mime = RuntimeUtils.detectImageMime(raw);
                if (mime == null) {
                    mime = java.net.URLConnection.guessContentTypeFromName(path);
                }
                if (mime == null || !mime.startsWith("image/")) continue;
                String b64 = Base64.getEncoder().encodeToString(raw);
                Map<String, Object> imageUrl = new LinkedHashMap<>();
                imageUrl.put("url", "data:" + mime + ";base64," + b64);
                Map<String, Object> imageBlock = new LinkedHashMap<>();
                imageBlock.put("type", "image_url");
                imageBlock.put("image_url", imageUrl);
                imageBlock.put("_meta", Map.of("path", p.toString()));
                images.add(imageBlock);
            } catch (Exception ignored) {}
        }
        if (images.isEmpty()) {
            return text;
        }
        List<Map<String, Object>> result = new ArrayList<>(images);
        result.add(Map.of("type", "text", "text", text));
        return result;
    }
}
```

## 关键设计决策

### asyncio → CompletableFuture

Python 的 `async def` / `await` 在 Java 中映射为 `CompletableFuture<T>` / `thenCompose`。

- `AgentLoop.run()` → `CompletableFuture<Void>`，消息消费通过 `bus.consumeInbound().thenCompose(...)` 链式调度
- `AgentRunner.run()` → `CompletableFuture<AgentRunResult>`，迭代循环通过递归 `thenCompose` 实现
- `AgentHook` 所有回调 → `CompletableFuture<Void>`，仅 `wantsStreaming()` 与 `finalizeContent()` 保持同步（与 Python 一致）
- `CompositeHook` 使用 `thenCompose` 顺序执行子 hook，异常隔离通过 `exceptionally` 捕获

Virtual Threads **不**用于替代 asyncio 语义，仅用于底层阻塞 I/O（如未提供异步 API 的第三方 SDK 调用）。

### CancelledError → CancellationException

Python 的 `asyncio.CancelledError` 映射为 Java 的 `CancellationException`。
`CompletableFuture.cancel(true)` 会设置中断标志，异步链中的 `supplyAsync` / `thenCompose` 可通过 `Thread.interrupted()` 检测取消。

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
