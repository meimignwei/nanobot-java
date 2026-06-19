# P4 Agent Loop + Runner Implementation Plan（基于源码完善）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
>
> **Source:** `/Users/mmw/PycharmProjects/nanobot/nanobot/`
> - `agent/loop.py` (1780 lines) — state machine, session lock, task dispatch, streaming, checkpoint
> - `agent/runner.py` (1544 lines) — LLM tool-use loop, context governance, tool execution, SSRF guard
> - `agent/context.py` (281 lines) — system prompt, bootstrap files, images, runtime context
> - `agent/memory.py` (1016 lines) — MemoryStore (file I/O) + Consolidator (LLM summarization)
> - `agent/hook.py` — AgentHook interfaces for progress/streaming/callbacks
> - `session/manager.py` (818 lines) — Session (JSONL persistence, message replay) + SessionManager
> - `command/router.py` (89 lines) — three-tier dispatch: priority/exact/prefix
> - `command/builtin.py` (720 lines) — 13 built-in slash commands

**Goal:** Full Java port of nanobot agent loop state machine, runner execution loop, context builder, command router, consolidator, session manager, and channel manager.

**Architecture:** AgentLoop consumes from MessageBus → state machine (RESTORE→COMPACT→COMMAND→BUILD→RUN→SAVE→RESPOND→DONE) → AgentRunner executes LLM tool-use loop → publishes OutboundMessage to bus. SessionManager persists conversation history to JSONL files. ChannelManager routes streaming deltas. Consolidator archives old messages via LLM summarization.

**Tech Stack:** Java 21, Spring Boot 3.2+, Virtual Threads, JUnit 5, AssertJ

**Key Java adaptations:**
- `asyncio.Lock` → `ReentrantLock`; `asyncio.Semaphore` → `java.util.concurrent.Semaphore`
- `asyncio.create_task` → virtual thread `executor.submit()`
- `asyncio.CancelledError` → `InterruptedException`
- `contextvars.ContextVar` → `ThreadLocal`
- `weakref.WeakValueDictionary` → `ConcurrentHashMap` with manual cleanup
- `tiktoken` → simple char/4 token estimation (or jtokkit if available)
- JSONL file I/O → Java NIO `Files.readString`/`Files.writeString`

---

## Task 1: Session + SessionManager（完整对标 session/manager.py 818行）

**Files:**
- Create: `src/main/java/com/nanobot/agent/session/Session.java` (~250 lines)
- Create: `src/main/java/com/nanobot/agent/session/SessionManager.java` (~250 lines)
- Create: `src/test/java/com/nanobot/agent/session/SessionManagerTest.java`

### Session.java 对标清单

| Python (`session/manager.py`) | Java `Session.java` |
|---|---|
| `key: str` | `String key` |
| `messages: list[dict]` | `List<Map<String, Object>> messages` |
| `created_at: datetime` | `Instant createdAt` |
| `updated_at: datetime` | `Instant updatedAt` |
| `metadata: dict` | `Map<String, Object> metadata` |
| `last_consolidated: int` | `int lastConsolidated` |
| `add_message(role, content, **kwargs)` | `addMessage(role, content, extra)` |
| `get_history(max_messages, max_tokens, include_timestamps)` | `getHistory(maxMessages, maxTokens, includeTimestamps)` — 含 user-turn alignment、_command filter、orphan tool cleanup、media/cli_apps/mcp breadcrumbs |
| `clear()` | `clear()` — reset messages + metadata |
| `retain_recent_legal_suffix(max)` | `retainRecentLegalSuffix(max)` → `(dropped, alreadyConsolidated)` |
| `enforce_file_cap(on_archive, limit)` | `enforceFileCap(onArchive, limit)` |
| `_annotate_message_time(msg, content)` | `annotateMessageTime(msg, content)` |

### SessionManager.java 对标清单

| Python | Java |
|---|---|
| `workspace / sessions_dir` | `Path workspace / sessionsDir` |
| `_cache: dict[str, Session]` | `ConcurrentHashMap<String, Session>` |
| `safe_key(key)` | `safeKey(key)` — `:` → `_` |
| `get_or_create(key)` | `getOrCreate(key)` — cache hit → disk load → create new |
| `_load(key)` — JSONL parsing | `loadFromDisk(key)` — read metadata line + message lines |
| `save(session, fsync)` | `save(session, fsync)` — atomic write via tmp file + rename |
| `invalidate(key)` | `invalidate(key)` — cache remove |
| `delete_session(key)` | `deleteSession(key)` — unlink file + invalidate |
| `flush_all()` | `flushAll()` — fsync all cached sessions |
| `_repair(key)` | `repairFromDisk(key)` — skip corrupt JSON lines |
| `list_sessions()` | `listSessions()` — glob *.jsonl, read metadata + preview |

**TDD 步骤:**
- [ ] RED: `SessionManagerTest` — CRUD, load/save, history pagination, clear, retain suffix, file cap
- [ ] GREEN: `Session.java` + `SessionManager.java`
- [ ] REFACTOR + commit

---

## Task 2: Channel + ChannelManager（最小接口，P6 补 WebSocket）

**Files:**
- Create: `src/main/java/com/nanobot/agent/channel/Channel.java`
- Create: `src/main/java/com/nanobot/agent/channel/ChannelManager.java`

```java
// Channel.java — 对标 Python Channel 抽象
public interface Channel {
    String name();
    void send(OutboundMessage msg) throws Exception;
    default void sendDelta(String chatId, String delta) throws Exception {}
    default boolean supportsStreaming() { return false; }
}

// ChannelManager.java — 对标 Python channels 注册
public class ChannelManager {
    private final ConcurrentMap<String, Channel> channels = new ConcurrentHashMap<>();
    public void register(Channel c) { channels.put(c.name(), c); }
    public Optional<Channel> get(String name) { ... }
    public void unregister(String name) { channels.remove(name); }
}
```

- [ ] Commit（纯数据结构，无需单独测试）

---

## Task 3: AgentHook 接口层（对标 agent/hook.py）

**Files:**
- Create: `src/main/java/com/nanobot/agent/hook/AgentHook.java`
- Create: `src/main/java/com/nanobot/agent/hook/AgentHookContext.java`
- Create: `src/main/java/com/nanobot/agent/hook/AgentRunHookContext.java`

### AgentHook 对标清单

| Python `AgentHook` | Java `AgentHook` (interface with defaults) |
|---|---|
| `async before_run(ctx)` | `default void beforeRun(AgentRunHookContext ctx)` |
| `async after_run(ctx)` | `default void afterRun(AgentRunHookContext ctx)` |
| `async on_error(ctx)` | `default void onError(AgentRunHookContext ctx)` |
| `async on_finally(ctx)` | `default void onFinally(AgentRunHookContext ctx)` |
| `async before_iteration(ctx)` | `default void beforeIteration(AgentHookContext ctx)` |
| `async after_iteration(ctx)` | `default void afterIteration(AgentHookContext ctx)` |
| `async before_execute_tools(ctx)` | `default void beforeExecuteTools(AgentHookContext ctx)` |
| `async on_stream(ctx, delta)` | `default void onStream(AgentHookContext ctx, String delta)` |
| `async on_stream_end(ctx, resuming)` | `default void onStreamEnd(AgentHookContext ctx, boolean resuming)` |
| `emit_reasoning(delta)` | `default void emitReasoning(String delta)` |
| `emit_reasoning_end()` | `default void emitReasoningEnd()` |
| `wants_streaming()` | `default boolean wantsStreaming()` |
| `finalize_content(ctx, content)` | `default String finalizeContent(AgentHookContext ctx, String content)` |

Context classes 对标:
- `AgentHookContext` — iteration, messages, response, toolCalls, toolResults, toolEvents, usage, sessionKey, streamedContent, streamedReasoning
- `AgentRunHookContext` — messages, finalContent, toolsUsed, usage, stopReason, error, toolEvents, hadInjections, exception, cancelled

- [ ] Commit

---

## Task 4: MemoryStore（对标 agent/memory.py MemoryStore 类）

**Files:**
- Create: `src/main/java/com/nanobot/agent/context/MemoryStore.java` (~200 lines)
- Create: `src/test/java/com/nanobot/agent/context/MemoryStoreTest.java`

对标 `MemoryStore`（纯文件 I/O 层）:
| Python | Java |
|---|---|
| `workspace, memory_dir, memory_file, history_file` | `Path workspace, memoryDir, memoryFile, historyFile` |
| `read_memory()` / `write_memory(content)` | `readMemory()` / `writeMemory(content)` |
| `read_soul()` / `write_soul(content)` | `readSoul()` / `writeSoul(content)` |
| `read_user()` / `write_user(content)` | `readUser()` / `writeUser(content)` |
| `get_memory_context()` | `getMemoryContext()` — "## Long-term Memory\n{content}" |
| `append_history(entry, max_chars, session_key)` | `appendHistory(entry, maxChars, sessionKey)` — JSONL append with cursor |
| `read_unprocessed_history(since_cursor)` | `readUnprocessedHistory(sinceCursor)` |
| `read_recent_history_for_prompt(since_cursor, session_key, unified)` | `readRecentHistoryForPrompt(sinceCursor, sessionKey, unified)` |
| `compact_history()` | `compactHistory()` — keep last N entries |
| `get_last_dream_cursor()` / `set_last_dream_cursor(c)` | `getLastDreamCursor()` / `setLastDreamCursor(c)` |
| `_next_cursor()` — max cursor recovery | `nextCursor()` — max cursor or file tail |
| `_read_entries()` / `_write_entries()` | `readEntries()` / `writeEntries()` |

**TDD:** Write test for appendHistory → readUnprocessedHistory → compactHistory cycle.

---

## Task 5: ContextBuilder（对标 agent/context.py 281行）

**Files:**
- Create: `src/main/java/com/nanobot/agent/context/ContextBuilder.java` (~200 lines)
- Create: `src/test/java/com/nanobot/agent/context/ContextBuilderTest.java`

| Python `ContextBuilder` | Java |
|---|---|
| `BOOTSTRAP_FILES = ["AGENTS.md", "SOUL.md", "USER.md"]` | `BOOTSTRAP_FILES` |
| `__init__(workspace, timezone, disabled_skills)` | 构造器 — workspace, timezone |
| `build_system_prompt(channel, workspace, ...)` | `buildSystemPrompt(...)` — identity + bootstrap + tool_contract + memory + skills + recent history |
| `build_messages(history, current_message, media, ...)` | `buildMessages(...)` — system prompt + history + merged user message |
| `_build_user_content(text, media)` | `buildUserContent(text, media)` — text + base64 images |
| `_build_runtime_context(channel, chat_id, sender_id, ...)` | `buildRuntimeContext(...)` — time/channel/chat_id/sender_id block |
| `_get_identity(channel, workspace)` | `buildIdentity(...)` — workspace path + platform info |
| `_load_bootstrap_files(workspace)` | `loadBootstrapFiles(Path)` |
| `_merge_message_content(left, right)` | `mergeMessageContent(left, right)` — string concat or block list merge |
| `detect_image_mime(bytes)` | `detectImageMime(byte[])` — magic bytes: JPEG/PNG/GIF/WebP |

**TDD:** Write test with mock workspace containing AGENTS.md → verify system prompt includes content.

---

## Task 6: CommandRouter + 内置命令（对标 command/router.py + command/builtin.py）

**Files:**
- Create: `src/main/java/com/nanobot/agent/command/Command.java` (interface)
- Create: `src/main/java/com/nanobot/agent/command/CommandContext.java`
- Create: `src/main/java/com/nanobot/agent/command/CommandRouter.java` (~80 lines)
- Create: `src/main/java/com/nanobot/agent/command/BuiltinCommands.java` (~400 lines)
- Create: `src/test/java/com/nanobot/agent/command/CommandRouterTest.java`

### CommandRouter 三层层级

```java
// 对标 Python CommandRouter（command/router.py）
public class CommandRouter {
    // Tier 1: priority — exact match, dispatched WITHOUT session lock
    private final Map<String, CommandHandler> priority = new LinkedHashMap<>();
    // Tier 2: exact — dispatched WITH session lock
    private final Map<String, CommandHandler> exact = new LinkedHashMap<>();
    // Tier 3: prefix — longest-prefix-first match
    private final List<PrefixEntry> prefix = new ArrayList<>();

    boolean isPriority(String text);           // check priority tier
    boolean isDispatchableCommand(String text); // check exact + prefix tiers
    OutboundMessage dispatchPriority(CommandContext ctx);  // priority only
    OutboundMessage dispatch(CommandContext ctx);           // exact → prefix
}
```

### 内置命令（对标 command/builtin.py 720行）

| 命令 | Python 函数 | Java 方法 | 层级 |
|------|------------|----------|------|
| `/stop` | `cmd_stop` | `cmdStop` | priority |
| `/restart` | `cmd_restart` | `cmdRestart` | priority |
| `/status` | `cmd_status` | `cmdStatus` | priority + exact |
| `/new` | `cmd_new` | `cmdNew` — clear session + archive | exact |
| `/model` | `cmd_model` | `cmdModel` — show/switch preset | exact + prefix |
| `/history` | `cmd_history` | `cmdHistory` — last N messages | exact + prefix |
| `/goal` | `cmd_goal` | `cmdGoal` — rewrite to long_task prompt | exact + prefix |
| `/dream` | `cmd_dream` | `cmdDream` — trigger memory consolidation | exact |
| `/dream-log` | `cmd_dream_log` | `cmdDreamLog` — show git diff | exact + prefix |
| `/dream-restore` | `cmd_dream_restore` | `cmdDreamRestore` — git revert | exact + prefix |
| `/skill` | `cmd_skill` | `cmdSkill` — list enabled skills | exact |
| `/help` | `cmd_help` | `cmdHelp` — list all commands | exact |
| `/pairing` | `cmd_pairing` | `cmdPairing` | exact + prefix |

**TDD:** Register commands → dispatch → verify response content.

---

## Task 7: Consolidator（对标 agent/memory.py Consolidator 类 ~350行）

**Files:**
- Create: `src/main/java/com/nanobot/agent/context/Consolidator.java` (~300 lines)
- Create: `src/test/java/com/nanobot/agent/context/ConsolidatorTest.java`

| Python `Consolidator` | Java |
|---|---|
| `__init__(store, provider, model, sessions, ctx_window_tokens, build_messages, get_tool_defs, ...)` | 构造器注入依赖 |
| `set_provider(provider, model, ctx_window_tokens)` | `setProvider(...)` |
| `get_lock(session_key)` → `asyncio.Lock` | `getLock(sessionKey)` → `ReentrantLock` |
| `estimate_session_prompt_tokens(session)` | `estimateSessionPromptTokens(session)` — build probe messages + tokenize |
| `pick_consolidation_boundary(session, tokens_to_remove)` | `pickConsolidationBoundary(...)` — find user-turn boundary removing enough tokens |
| `archive(messages, session_key)` → LLM summary | `archive(messages, sessionKey)` — LLM summarization via provider |
| `maybe_consolidate_by_tokens(session, replay_max_messages)` | `maybeConsolidateByTokens(...)` — loop: estimate → boundary → archive → repeat |
| `_consolidate_replay_overflow(session, replay_max)` | `consolidateReplayOverflow(...)` |
| `compact_idle_session(session_key, max_suffix)` | `compactIdleSession(key, maxSuffix)` |
| `_truncate_to_token_budget(text)` | `truncateToTokenBudget(text)` |
| `_full_unconsolidated_history(session)` | `fullUnconsolidatedHistory(session)` |
| `_input_token_budget` (property) | `getInputTokenBudget()` — ctx_window - max_completion - 1024 |

Token 估算策略:
```java
// Java 无 tiktoken → 简单估算: ~4 chars per token
static int estimateTokens(String text) {
    return text == null ? 0 : Math.max(1, text.length() / 4);
}
static int estimateMessageTokens(Map<String, Object> msg) {
    // recursive: sum of content + tool_calls + reasoning tokens
}
```

**TDD:** Mock LLMProvider → call archive → verify LLM called with summarization prompt.

---

## Task 8: AgentRunSpec + AgentRunResult（对标 runner.py dataclasses）

**Files:**
- Create: `src/main/java/com/nanobot/agent/runner/AgentRunSpec.java` (~80 lines)
- Create: `src/main/java/com/nanobot/agent/runner/AgentRunResult.java` (~40 lines)

### AgentRunSpec 对标 runner.py 83-113 行

```java
public record AgentRunSpec(
    List<Map<String, Object>> initialMessages,
    ToolRegistry tools, String model,
    int maxIterations, int maxToolResultChars,
    @Nullable Double temperature, @Nullable Integer maxTokens,
    @Nullable String reasoningEffort,
    @Nullable AgentHook hook,
    @Nullable String errorMessage,       // default: _DEFAULT_ERROR_MESSAGE
    @Nullable String maxIterationsMessage,
    boolean concurrentTools,             // default: false
    boolean failOnToolError,             // default: false
    @Nullable Path workspace,
    @Nullable String sessionKey,
    @Nullable Integer contextWindowTokens,
    @Nullable Integer contextBlockLimit,
    String providerRetryMode,            // default: "standard"
    @Nullable Consumer<String> progressCallback,
    boolean streamProgressDeltas,        // default: true
    @Nullable Consumer<String> retryWaitCallback,
    @Nullable Consumer<Map<String, Object>> checkpointCallback,
    @Nullable Supplier<List<Map<String, Object>>> injectionCallback,
    @Nullable Double llmTimeoutS,
    @Nullable BooleanSupplier goalActivePredicate,
    @Nullable String goalContinueMessage,
    boolean finalizeOnMaxIterations      // default: true
) {}
```

### AgentRunResult 对标 runner.py 116-127 行

```java
public record AgentRunResult(
    @Nullable String finalContent,
    List<Map<String, Object>> messages,
    List<String> toolsUsed,
    Map<String, Integer> usage,
    String stopReason,                   // "completed" | "max_iterations" | "error" | ...
    @Nullable String error,
    List<Map<String, String>> toolEvents,
    boolean hadInjections
) {}
```

- [ ] Commit

---

## Task 9: AgentRunner（对标 agent/runner.py 1544行，核心）

**Files:**
- Create: `src/main/java/com/nanobot/agent/runner/AgentRunner.java` (~600 lines)
- Create: `src/test/java/com/nanobot/agent/runner/AgentRunnerTest.java`

### 核心执行循环对标

```java
public AgentRunResult run(AgentRunSpec spec) {
    // 对标 Python AgentRunner.run()
    // 1. build context → beforeRun hook
    // 2. _run_core: for iteration in 0..maxIterations:
    //    a. Context governance: drop orphans, backfill, microcompact,
    //       apply tool result budget, snip history
    //    b. beforeIteration hook
    //    c. _requestModel → LLMResponse
    //    d. Extract reasoning, accumulate usage
    //    e. If shouldExecuteTools:
    //       - Build assistant message, emit checkpoint
    //       - _executeTools (sequential or batched concurrent)
    //       - Emit tool results + checkpoint
    //       - Drain injections
    //       - Continue loop
    //    f. If empty content → retry (_MAX_EMPTY_RETRIES=2)
    //    g. If length (truncated) → length recovery (_MAX_LENGTH_RECOVERIES=3)
    //    h. If error → error placeholder + drain injections
    //    i. Final response → drain injections → return
    // 3. Max iterations exceeded → finalization pass
}
```

### 关键方法对标清单

| Python 方法 | Java 方法 | 行数估算 |
|---|---|---|
| `_request_model(spec, messages, hook, ctx)` | `requestModel(...)` — streaming/progress/normal dispatch | ~100 |
| `_execute_tools(spec, tool_calls, ext_counts, ws_counts)` | `executeTools(...)` — batch partition + run | ~40 |
| `_run_tool(spec, tool_call, ...)` | `runTool(...)` — prepare + execute + classify violation | ~80 |
| `_try_drain_injections(spec, messages, ...)` | `tryDrainInjections(...)` | ~50 |
| `_drain_injections(spec)` | `drainInjections(spec)` | ~30 |
| `_drop_orphan_tool_results(messages)` | `dropOrphanToolResults(messages)` | ~25 |
| `_backfill_missing_tool_results(messages)` | `backfillMissingToolResults(messages)` | ~30 |
| `_microcompact(messages)` | `microcompact(messages)` — keep 10 recent, compact old | ~25 |
| `_apply_tool_result_budget(spec, messages)` | `applyToolResultBudget(spec, messages)` | ~20 |
| `_snip_history(spec, messages)` | `snipHistory(spec, messages)` — token-budget history trimming | ~50 |
| `_normalize_tool_result(spec, id, name, result)` | `normalizeToolResult(...)` — persist/truncate | ~20 |
| `_try_finalize_after_max_iterations(spec, ...)` | `tryFinalizeAfterMaxIterations(...)` — no-tools summary | ~30 |
| `_partition_tool_batches(spec, tool_calls)` | `partitionToolBatches(...)` — concurrency-safe batching | ~25 |
| `_classify_violation(...)` | `classifyViolation(...)` — SSRF / workspace violation | ~40 |
| `_is_ssrf_violation(text)` | `isSsrfViolation(text)` | ~10 |
| `_is_workspace_violation(text)` | `isWorkspaceViolation(text)` | ~10 |
| `_append_injected_messages(messages, injections)` | `appendInjectedMessages(...)` — merge same-role | ~15 |
| 各种 `_build_*_message` 静态方法 | 内联或工具方法 | ~30 |

**TDD:** Mock LLMProvider → verify tool calls executed in order + response assembled.

---

## Task 10: TurnState + TurnContext（对标 loop.py 77-136行）

**Files:**
- Create: `src/main/java/com/nanobot/agent/loop/TurnState.java`
- Create: `src/main/java/com/nanobot/agent/loop/TurnContext.java`
- Create: `src/main/java/com/nanobot/agent/loop/StateTraceEntry.java`

### TurnState 枚举

```java
public enum TurnState {
    RESTORE, COMPACT, COMMAND, BUILD, RUN, SAVE, RESPOND, DONE;
}
```

### 状态转换表（对标 loop.py 168-177）

```java
static final Map<Map.Entry<TurnState, String>, TurnState> TRANSITIONS = Map.of(
    entry(RESTORE, "ok"), COMPACT,
    entry(COMPACT, "ok"), COMMAND,
    entry(COMMAND, "dispatch"), BUILD,
    entry(COMMAND, "shortcut"), DONE,
    entry(BUILD, "ok"), RUN,
    entry(RUN, "ok"), SAVE,
    entry(SAVE, "ok"), RESPOND,
    entry(RESPOND, "ok"), DONE
);
```

### TurnContext（对标 loop.py 98-135）

```java
public class TurnContext {
    InboundMessage msg;
    String sessionKey;
    TurnState state = TurnState.RESTORE;
    String turnId;
    Session session;            // filled in RESTORE
    List<Map<String, Object>> history;
    List<Map<String, Object>> initialMessages;
    String finalContent;
    List<String> toolsUsed;
    List<Map<String, Object>> allMessages;
    String stopReason;
    boolean hadInjections;
    boolean userPersistedEarly;
    int saveSkip;
    OutboundMessage outbound;   // filled in RESPOND
    boolean suppressResponse;
    // callbacks (Consumer / Runnable)
    Consumer<Map<String, Object>> onProgress;
    Consumer<String> onStream;
    Runnable onStreamEnd;
    Consumer<String> onRetryWait;
    // timing
    long turnWallStartedAt;
    Long visibleRunStartedAt;
    Integer turnLatencyMs;
    List<StateTraceEntry> trace;
    // injection
    BlockingQueue<InboundMessage> pendingQueue;
    String pendingSummary;
    boolean ephemeral;
    ToolRegistry tools;
}
```

- [ ] Commit

---

## Task 11: AgentLoop（对标 agent/loop.py 1780行，最大文件）

**Files:**
- Create: `src/main/java/com/nanobot/agent/loop/AgentLoop.java` (~700 lines)
- Create: `src/test/java/com/nanobot/agent/loop/AgentLoopTest.java`

### 构造器对标（loop.py 179-333）

```java
@Component
public class AgentLoop implements Runnable {
    private final MessageBus bus;
    private final LLMProvider provider;
    private final Path workspace;
    private String model;
    private int maxIterations;
    private int contextWindowTokens;
    private int maxToolResultChars;
    private String providerRetryMode;
    // ...
    private final ContextBuilder context;
    private final SessionManager sessions;
    private final ToolRegistry tools;
    private final AgentRunner runner;
    private final Consolidator consolidator;
    private final CommandRouter commands;
    // Session management
    private final ConcurrentMap<String, ReentrantLock> sessionLocks;
    private final Semaphore concurrencyGate;
    private final ConcurrentMap<String, List<Thread>> activeTasks;  // session_key → threads
    private final ConcurrentMap<String, BlockingQueue<InboundMessage>> pendingQueues;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running;
}
```

### 主要方法对标

| Python 方法 | Java 方法 |
|---|---|
| `run()` — 主消费循环 | `run()` — `while(running) { msg = bus.consumeInbound(); executor.submit(() -> _dispatch(msg)); }` |
| `_dispatch(msg)` — per-session lock + semaphore | `dispatch(msg)` — lock → semaphore → _processMessage |
| `_process_message(msg)` — 状态机驱动 | `processMessage(msg)` — TurnContext → for each state → handler |
| `_state_restore(ctx)` — 恢复会话 + 媒体处理 | `stateRestore(ctx)` |
| `_state_compact(ctx)` — auto-compact check | `stateCompact(ctx)` |
| `_state_command(ctx)` — 命令分发 | `stateCommand(ctx)` |
| `_state_build(ctx)` — 构建 messages + tool context | `stateBuild(ctx)` |
| `_state_run(ctx)` → `_run_agent_loop()` | `stateRun(ctx)` → runner.run(spec) |
| `_state_save(ctx)` — 持久化 turn | `stateSave(ctx)` |
| `_state_respond(ctx)` — 组装 outbound | `stateRespond(ctx)` |
| `_run_agent_loop(...)` — 构建 spec + 调用 runner + 处理结果 | `runAgentLoop(...)` |
| `_assemble_outbound(...)` — 最终消息组装 | `assembleOutbound(...)` |
| `_save_turn(session, messages, skip)` — 保存+截断 tool results | `saveTurn(...)` |
| `_cancel_active_tasks(key)` — 取消所有活跃任务 | `cancelActiveTasks(key)` |
| `_set_tool_context(channel, chat_id, ...)` → RequestContext bind | `setToolContext(...)` — ToolContext.bind() |
| `_build_bus_progress_callback(msg)` | `buildBusProgressCallback(msg)` |
| `_build_retry_wait_callback(msg)` | `buildRetryWaitCallback(msg)` |
| `_persist_user_message_early(msg, session)` | `persistUserMessageEarly(msg, session)` |
| `_build_initial_messages(msg, session, history, ...)` | `buildInitialMessages(...)` |
| `_effective_session_key(msg)` | `effectiveSessionKey(msg)` |
| `_replay_token_budget()` | `replayTokenBudget()` |
| `_restore_runtime_checkpoint(session)` | `restoreRuntimeCheckpoint(session)` |
| `_restore_pending_user_turn(session)` | `restorePendingUserTurn(session)` |
| `_prepare_message_media(content, media)` | `prepareMessageMedia(content, media)` |
| `_sanitize_persisted_blocks(content)` | `sanitizePersistedBlocks(content)` |
| `process_direct(content, session_key, ...)` | `processDirect(...)` — 直接调用不走 bus |
| `stop()` | `stop()` — set running=false |

**TDD:** Mock MessageBus → provide InboundMessage → verify AgentLoop processes it and publishes OutboundMessage.

---

## Task 12: 集成测试 + 回归验证

- [ ] `mvn test` — 全量回归，确保 P1/P2/P3 测试无破坏
- [ ] 新增测试全部通过
- [ ] Commit final

---

## 完整文件清单

```
src/main/java/com/nanobot/agent/
├── session/
│   ├── Session.java           (~250 lines, 对标 session/manager.py Session)
│   └── SessionManager.java    (~250 lines, 对标 session/manager.py SessionManager)
├── channel/
│   ├── Channel.java           (~15 lines, interface)
│   └── ChannelManager.java    (~30 lines)
├── hook/
│   ├── AgentHook.java         (~30 lines, interface)
│   ├── AgentHookContext.java  (~25 lines)
│   └── AgentRunHookContext.java (~25 lines)
├── context/
│   ├── MemoryStore.java       (~200 lines, 对标 agent/memory.py MemoryStore)
│   ├── ContextBuilder.java    (~200 lines, 对标 agent/context.py)
│   └── Consolidator.java      (~300 lines, 对标 agent/memory.py Consolidator)
├── command/
│   ├── CommandContext.java    (~25 lines)
│   ├── CommandRouter.java     (~80 lines, 对标 command/router.py)
│   └── BuiltinCommands.java   (~400 lines, 对标 command/builtin.py)
├── runner/
│   ├── AgentRunSpec.java      (~80 lines)
│   ├── AgentRunResult.java    (~40 lines)
│   └── AgentRunner.java       (~600 lines, 对标 agent/runner.py)
└── loop/
    ├── TurnState.java         (~15 lines)
    ├── StateTraceEntry.java   (~15 lines)
    ├── TurnContext.java       (~80 lines)
    ├── TurnContinuation.java  (~100 lines, 对标 session/turn_continuation.py)
    └── AgentLoop.java         (~700 lines, 对标 agent/loop.py)

主代码合计: ~3,560 lines
测试代码合计: ~1,200 lines
总计: ~4,760 lines
```
