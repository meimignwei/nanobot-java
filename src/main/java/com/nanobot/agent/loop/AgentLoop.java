package com.nanobot.agent.loop;

import com.nanobot.agent.command.CommandContext;
import com.nanobot.agent.command.CommandRouter;
import com.nanobot.agent.context.ContextBuilder;
import com.nanobot.agent.context.Consolidator;
import com.nanobot.agent.runner.AgentRunSpec;
import com.nanobot.agent.runner.AgentRunner;
import com.nanobot.agent.session.Session;
import com.nanobot.agent.session.SessionManager;
import com.nanobot.agent.tools.ToolContext;
import com.nanobot.agent.tools.ToolRegistry;
import com.nanobot.bus.InboundMessage;
import com.nanobot.bus.MessageBus;
import com.nanobot.bus.OutboundMessage;
import com.nanobot.providers.base.ThrowingConsumer;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Core agent processing engine with turn state machine.
 * Mirrors Python AgentLoop class (agent/loop.py 1780 lines).
 *
 * Consumes InboundMessages from MessageBus, runs them through the
 * state machine (RESTORE→COMPACT→COMMAND→BUILD→RUN→SAVE→RESPOND→DONE),
 * and publishes OutboundMessages back.
 */
public class AgentLoop implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    public static final Map<Map.Entry<TurnState, String>, TurnState> TRANSITIONS;

    static {
        var m = new LinkedHashMap<Map.Entry<TurnState, String>, TurnState>();
        m.put(Map.entry(TurnState.RESTORE, "ok"), TurnState.COMPACT);
        m.put(Map.entry(TurnState.COMPACT, "ok"), TurnState.COMMAND);
        m.put(Map.entry(TurnState.COMMAND, "dispatch"), TurnState.BUILD);
        m.put(Map.entry(TurnState.COMMAND, "shortcut"), TurnState.DONE);
        m.put(Map.entry(TurnState.BUILD, "ok"), TurnState.RUN);
        m.put(Map.entry(TurnState.RUN, "ok"), TurnState.SAVE);
        m.put(Map.entry(TurnState.SAVE, "ok"), TurnState.RESPOND);
        m.put(Map.entry(TurnState.RESPOND, "ok"), TurnState.DONE);
        TRANSITIONS = Collections.unmodifiableMap(m);
    }

    private static final String UNIFIED_SESSION_KEY = "unified:default";

    private final MessageBus bus;
    private final AgentRunner runner;
    private final ContextBuilder context;
    private final CommandRouter commands;
    private final Consolidator consolidator;
    private final SessionManager sessions;
    private final Path workspace;

    private String model;
    private int maxIterations;
    private int contextWindowTokens;
    private int maxToolResultChars;
    private String providerRetryMode;

    private final ConcurrentMap<String, ReentrantLock> sessionLocks;
    private final Semaphore concurrencyGate;
    private final ConcurrentMap<String, List<Thread>> activeTasks;
    private final ConcurrentMap<String, BlockingQueue<InboundMessage>> pendingQueues;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running;
    private final ToolRegistry tools;

    private final Map<String, Integer> lastUsage = new ConcurrentHashMap<>();

    public AgentLoop(
            MessageBus bus,
            AgentRunner runner,
            ContextBuilder context,
            CommandRouter commands,
            Consolidator consolidator,
            SessionManager sessions,
            Path workspace,
            String model,
            int maxIterations,
            int contextWindowTokens,
            int maxToolResultChars,
            String providerRetryMode,
            ConcurrentMap<String, ReentrantLock> sessionLocks,
            Semaphore concurrencyGate,
            ConcurrentMap<String, List<Thread>> activeTasks,
            ConcurrentMap<String, BlockingQueue<InboundMessage>> pendingQueues) {
        this.bus = bus;
        this.runner = runner;
        this.context = context;
        this.commands = commands;
        this.consolidator = consolidator;
        this.sessions = sessions;
        this.workspace = workspace;
        this.model = model;
        this.maxIterations = maxIterations;
        this.contextWindowTokens = contextWindowTokens;
        this.maxToolResultChars = maxToolResultChars;
        this.providerRetryMode = providerRetryMode;
        this.sessionLocks = sessionLocks;
        this.concurrencyGate = concurrencyGate;
        this.activeTasks = activeTasks;
        this.pendingQueues = pendingQueues;
        this.tools = new ToolRegistry();
    }

    // -- accessors --

    public boolean isRunning() { return running; }
    public void setRunning(boolean running) { this.running = running; }
    public CommandRouter commands() { return commands; }
    public ToolRegistry tools() { return tools; }
    public String model() { return model; }
    public int contextWindowTokens() { return contextWindowTokens; }
    public SessionManager sessions() { return sessions; }
    public Consolidator consolidator() { return consolidator; }

    // -- run --

    @Override
    public void run() {
        running = true;
        log.info("Agent loop started");
        try {
            while (running) {
                InboundMessage msg;
                try {
                    msg = bus.consumeInbound();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                executor.submit(() -> dispatch(msg));
            }
        } finally {
            running = false;
            log.info("Agent loop stopped");
        }
    }

    public void stop() {
        running = false;
        executor.shutdownNow();
    }

    // -- dispatch --

    void dispatch(InboundMessage msg) {
        var key = effectiveSessionKey(msg);
        if (!key.equals(msg.sessionKey())) {
            msg = msg.withSessionKey(key);
        }

        // Check priority commands — dispatched without session lock
        if (commands.isPriority(msg.content())) {
            var ctx = new CommandContext(msg, null, key, msg.content(), "");
            var result = commands.dispatchPriority(ctx);
            if (result != null) {
                try { bus.publishOutbound(result); } catch (InterruptedException ignored) {}
            }
            return;
        }

        // Acquire session lock + concurrency gate
        var lock = sessionLocks.computeIfAbsent(key, k -> new ReentrantLock());
        BlockingQueue<InboundMessage> pending = null;
        lock.lock();
        try {
            concurrencyGate.acquire();
        } catch (InterruptedException e) {
            lock.unlock();
            Thread.currentThread().interrupt();
            return;
        }

        try {
            // Only the task that owns the session lock may publish the
            // active mid-turn injection queue for this session.
            pending = new LinkedBlockingQueue<>(20);
            pendingQueues.put(key, pending);

            OutboundMessage response = null;
            try {
                response = processMessage(msg);
            } catch (Exception e) {
                log.error("Error processing message for session {}", key, e);
                try {
                    bus.publishOutbound(new OutboundMessage(
                            msg.channel(), msg.chatId(),
                            "Sorry, I encountered an error.",
                            null, null, null, null));
                } catch (InterruptedException ignored) {}
            }

            if (response != null) {
                try { bus.publishOutbound(response); } catch (InterruptedException ignored) {}
            } else if ("cli".equals(msg.channel())) {
                try {
                    bus.publishOutbound(new OutboundMessage(
                            msg.channel(), msg.chatId(), "",
                            null, null, null, null));
                } catch (InterruptedException ignored) {}
            }
        } finally {
            // Drain pending queue and re-publish leftovers
            if (pendingQueues.get(key) == pending) {
                pendingQueues.remove(key);
            }
            if (pending != null) {
                var leftover = new ArrayList<InboundMessage>();
                pending.drainTo(leftover);
                for (var item : leftover) {
                    try { bus.publishInbound(item); } catch (InterruptedException ignored) {}
                }
                if (!leftover.isEmpty()) {
                    log.info("Re-published {} leftover message(s) to bus for session {}",
                            leftover.size(), key);
                }
            }
            concurrencyGate.release();
            lock.unlock();
        }
    }

    // -- processMessage --

    public OutboundMessage processMessage(InboundMessage msg) {
        var key = effectiveSessionKey(msg);
        var turnId = UUID.randomUUID().toString();
        var ctx = new TurnContext(msg, key, TurnState.RESTORE, turnId);

        String event = "start";

        while (ctx.state() != TurnState.DONE) {
            var next = TRANSITIONS.get(Map.entry(ctx.state(), event));
            if (next != null) {
                ctx.setState(next);
            }
            event = switch (ctx.state()) {
                case RESTORE -> stateRestore(ctx);
                case COMPACT -> stateCompact(ctx);
                case COMMAND -> stateCommand(ctx);
                case BUILD -> stateBuild(ctx);
                case RUN -> stateRun(ctx);
                case SAVE -> stateSave(ctx);
                case RESPOND -> stateRespond(ctx);
                default -> "ok";
            };
            if (event == null) break;
        }

        return ctx.outbound();
    }

    // -- runAgentLoop (Python _run_agent_loop) --

    RunLoopResult runAgentLoop(
            TurnContext ctx,
            Runnable progressCallback,
            java.util.function.Consumer<String> streamCallback,
            Runnable streamEndCallback,
            java.util.function.Consumer<String> retryWaitCallback) {

        var session = ctx.session();
        var channel = ctx.msg().channel();
        var chatId = ctx.msg().chatId();
        var metadata = ctx.msg().metadata();
        var sessionKey = ctx.sessionKey();
        ThrowingConsumer<String> retryCb = retryWaitCallback != null
                ? s -> retryWaitCallback.accept(s) : null;
        Consumer<String> progressCb = progressCallback != null
                ? s -> progressCallback.run() : null;

        var spec = new AgentRunSpec(
                ctx.initialMessages(), tools, model, maxIterations, maxToolResultChars,
                null, null, null, null, null, null,
                true, false, workspace, sessionKey,
                contextWindowTokens, null, providerRetryMode,
                progressCb, streamCallback != null, retryCb,
                null, null, null, null, null, true);

        try {
            var result = runner.run(spec);
            ctx.setFinalContent(result.finalContent());
            ctx.setToolsUsed(new ArrayList<>(result.toolsUsed()));
            ctx.setAllMessages(result.messages());
            ctx.setStopReason(result.stopReason());
            ctx.setHadInjections(result.hadInjections());
            if (result.usage() != null) {
                lastUsage.putAll(result.usage());
            }
            return new RunLoopResult(result.finalContent(), result.toolsUsed(),
                    result.messages(), result.stopReason(), result.hadInjections());
        } catch (Exception e) {
            log.error("Agent run failed for {}", sessionKey, e);
            ctx.setFinalContent("An error occurred during processing.");
            ctx.setStopReason("error");
            return new RunLoopResult("An error occurred during processing.",
                    List.of(), ctx.allMessages() != null ? ctx.allMessages() : List.of(),
                    "error", false);
        }
    }

    record RunLoopResult(String finalContent, List<String> toolsUsed,
                         List<Map<String, Object>> messages, String stopReason,
                         boolean hadInjections) {}

    // -- state handlers --

    String stateRestore(TurnContext ctx) {
        var session = sessions.getOrCreate(ctx.sessionKey());
        ctx.setSession(session);

        // Handle pending queue
        var pendingQ = pendingQueues.get(ctx.sessionKey());
        if (pendingQ != null) {
            var pending = new ArrayList<InboundMessage>();
            pendingQ.drainTo(pending);
            if (!pending.isEmpty()) {
                ctx.setPendingSummary("Pending: " + pending.size() + " messages");
            }
        }

        // Persist user message early
        persistUserMessageEarly(ctx.msg(), session);

        return "ok";
    }

    String stateCompact(TurnContext ctx) {
        var session = ctx.session();
        if (session == null) return "ok";

        try {
            consolidator.maybeConsolidateByTokens(session, null);
        } catch (Exception e) {
            log.warn("Consolidation check failed for {}", ctx.sessionKey(), e);
        }
        return "ok";
    }

    String stateCommand(TurnContext ctx) {
        var msg = ctx.msg();
        var session = ctx.session();

        if (commands.isDispatchableCommand(msg.content())) {
            var cmdCtx = new CommandContext(msg, session, ctx.sessionKey(),
                    msg.content(), "", this);
            var result = commands.dispatch(cmdCtx);
            if (result != null) {
                ctx.setOutbound(result);
                ctx.setSuppressResponse(true);
                try { bus.publishOutbound(result); } catch (InterruptedException ignored) {}
                return "shortcut";
            }
            // cmdGoal returns null → normal dispatch
            if ("/goal".equals(msg.content().split(" ")[0].toLowerCase())) {
                // Rewrite message content for goal processing
                return "dispatch";
            }
        }

        return "dispatch";
    }

    String stateBuild(TurnContext ctx) {
        var session = ctx.session();
        if (session == null) return "ok";

        var history = buildInitialMessages(ctx.msg(), session);
        ctx.setHistory(history);
        ctx.setInitialMessages(history);

        return "ok";
    }

    String stateRun(TurnContext ctx) {
        var session = ctx.session();
        if (session == null) return "ok";

        var result = runAgentLoop(ctx, null, null, null, null);
        ctx.setFinalContent(result.finalContent());
        ctx.setToolsUsed(new ArrayList<>(result.toolsUsed()));
        ctx.setAllMessages(new ArrayList<>(result.messages()));
        ctx.setStopReason(result.stopReason());
        ctx.setHadInjections(result.hadInjections());

        if ("max_iterations".equals(result.stopReason())) {
            log.warn("Max iterations ({}) reached", maxIterations);
        } else if ("error".equals(result.stopReason())) {
            log.error("LLM returned error: {}",
                    (result.finalContent() != null ? result.finalContent() : "").substring(
                            0, Math.min(200, result.finalContent() != null ? result.finalContent().length() : 0)));
        }

        return "ok";
    }

    String stateSave(TurnContext ctx) {
        var session = ctx.session();
        if (session == null) return "ok";

        saveTurn(session, ctx.allMessages(), ctx.saveSkip());
        return "ok";
    }

    String stateRespond(TurnContext ctx) {
        if (ctx.suppressResponse()) {
            ctx.setOutbound(null);
            return "ok";
        }

        var msg = ctx.msg();
        var content = ctx.finalContent();
        if (content == null || content.isBlank()) {
            content = "[No response generated]";
        }

        var meta = msg.metadata() != null ? new LinkedHashMap<>(msg.metadata()) : new LinkedHashMap<String, Object>();
        if (!"error".equals(ctx.stopReason()) && !"tool_error".equals(ctx.stopReason())) {
            meta.put("_streamed", true);
        }

        var outbound = new OutboundMessage(
                msg.channel(), msg.chatId(), content,
                null, null, meta, null);
        ctx.setOutbound(outbound);

        try {
            bus.publishOutbound(outbound);
        } catch (InterruptedException ignored) {}

        return "ok";
    }

    // -- helpers --

    String effectiveSessionKey(InboundMessage msg) {
        return msg.sessionKey();
    }

    void persistUserMessageEarly(InboundMessage msg, Session session) {
        if (msg.content() == null || msg.content().isBlank()) return;
        if (msg.content().startsWith("/")) return; // commands are not persisted early

        session.addMessage("user", msg.content(),
                msg.media() != null && !msg.media().isEmpty()
                        ? Map.of("media", msg.media())
                        : null);
        sessions.save(session);
    }

    List<Map<String, Object>> buildInitialMessages(InboundMessage msg, Session session) {
        var history = session.getHistory(maxIterations * 4, 0, false);
        // Add current user message
        history.add(Map.of("role", "user", "content",
                msg.content() != null ? msg.content() : "",
                "timestamp", java.time.Instant.now().toString()));
        return history;
    }

    void saveTurn(Session session, List<Map<String, Object>> allMessages, int saveSkip) {
        if (allMessages == null || allMessages.isEmpty()) return;

        // Append new-turn messages with truncation for large tool results
        for (int i = saveSkip; i < allMessages.size(); i++) {
            var msg = allMessages.get(i);
            var role = msg.get("role");
            var content = msg.get("content");
            if (role == null) continue;

            // Skip empty assistant messages without tool_calls — they poison session context
            if ("assistant".equals(role) && (content == null || "".equals(content))
                    && !msg.containsKey("tool_calls")) {
                continue;
            }

            var entry = new LinkedHashMap<>(msg);

            // Truncate large tool results
            if ("tool".equals(role)) {
                if (content instanceof String s && s.length() > maxToolResultChars) {
                    entry.put("content", s.substring(0, maxToolResultChars) + "\n... (truncated)");
                } else if (content instanceof List<?> blocks) {
                    var filtered = sanitizePersistedBlocks(blocks, true);
                    entry.put("content", filtered);
                }
            }

            var extra = new LinkedHashMap<String, Object>();
            for (var e : entry.entrySet()) {
                if (!"role".equals(e.getKey()) && !"content".equals(e.getKey())) {
                    extra.put(e.getKey(), e.getValue());
                }
            }
            session.addMessage(role.toString(),
                    entry.get("content") != null ? entry.get("content").toString() : "",
                    extra.isEmpty() ? null : extra);
        }
        sessions.save(session);
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> sanitizePersistedBlocks(List<?> blocks, boolean shouldTruncate) {
        var filtered = new ArrayList<Map<String, Object>>();
        for (var block : blocks) {
            if (!(block instanceof Map<?, ?> bm)) {
                if (block instanceof Map) filtered.add((Map<String, Object>) block);
                continue;
            }
            var b = (Map<String, Object>) bm;

            // Strip inline image data URIs
            if ("image_url".equals(b.get("type"))) {
                var imageUrl = b.get("image_url");
                if (imageUrl instanceof Map<?, ?> ium) {
                    var urlObj = ium.get("url");
                    var url = urlObj != null ? urlObj.toString() : "";
                    if (url.startsWith("data:image/")) {
                        filtered.add(Map.of("type", "text", "text", "[image]"));
                        continue;
                    }
                }
            }

            // Truncate long text blocks
            if ("text".equals(b.get("type")) && b.get("text") instanceof String text) {
                if (shouldTruncate && text.length() > maxToolResultChars) {
                    var truncated = new LinkedHashMap<>(b);
                    truncated.put("text", text.substring(0, maxToolResultChars) + "\n... (truncated)");
                    filtered.add(truncated);
                    continue;
                }
            }

            filtered.add(b);
        }
        return filtered;
    }

    // -- tool context --

    void setToolContext(String channel, String chatId, @Nullable String messageId,
                        @Nullable Map<String, Object> metadata, String sessionKey,
                        Path workspace) {
        ToolContext build = ToolContext.builder()
                .config(Map.of("channel", channel, "chat_id", chatId))
                .workspace(workspace.toString())
                .bus(bus)
                .sessions(sessions)
                .timezone("UTC")
                .build();
        com.nanobot.agent.tools.ToolContext.bind(build);
    }

    void clearToolContext() {
        com.nanobot.agent.tools.ToolContext.unbind();
    }

    // -- default tool registration --

    void registerDefaultTools() {
        // Register standard built-in tools. These are the tools available
        // to every agent session by default. Port of Python _register_default_tools.
        // Tools are lazy-loaded by ToolLoader; registration here ensures they
        // appear in tool_definitions for the LLM.
        // For now, the ToolRegistry is populated externally via the ToolLoader.
    }

    // -- background tasks --

    void scheduleBackground(Runnable task) {
        executor.submit(task);
    }

    // -- checkpoint management --

    void setRuntimeCheckpoint(Session session, Map<String, Object> payload) {
        if (session == null) return;
        session.metadata().put("runtime_checkpoint", payload);
    }

    @SuppressWarnings("unchecked")
    boolean restoreRuntimeCheckpoint(Session session) {
        if (session == null) return false;
        var checkpoint = session.metadata().get("runtime_checkpoint");
        if (checkpoint instanceof Map<?, ?> cp) {
            var phase = cp.get("phase");
            if ("awaiting_tools".equals(phase) || "tools_completed".equals(phase)
                    || "final_response".equals(phase)) {
                var messages = cp.get("assistant_message");
                if (messages instanceof Map<?, ?> msg) {
                    var typedMsg = (Map<String, Object>) msg;
                    var content = typedMsg.get("content");
                    session.addMessage("assistant",
                            content != null ? content.toString() : "",
                            Map.of("tool_calls", typedMsg.getOrDefault("tool_calls", List.of())));
                    return true;
                }
            }
        }
        return false;
    }

    void markPendingUserTurn(Session session) {
        if (session == null) return;
        session.metadata().put("_pending_user_turn", true);
    }

    void clearPendingUserTurn(Session session) {
        if (session == null) return;
        session.metadata().remove("_pending_user_turn");
    }

    // -- assembleOutbound --

    OutboundMessage assembleOutbound(InboundMessage msg, String finalContent,
                                     List<Map<String, Object>> allMessages,
                                     String stopReason, boolean hadInjections) {
        var preview = finalContent != null && finalContent.length() > 120
                ? finalContent.substring(0, 120) + "..."
                : finalContent;
        log.info("Response to {}:{}: {}", msg.channel(), msg.senderId(), preview);

        var meta = msg.metadata() != null ? new LinkedHashMap<>(msg.metadata()) : new LinkedHashMap<String, Object>();
        if (!"error".equals(stopReason) && !"tool_error".equals(stopReason)) {
            meta.put("_streamed", true);
        }

        return new OutboundMessage(
                msg.channel(), msg.chatId(), finalContent,
                null, null, meta, null);
    }

    // -- cancelActiveTasks --

    public int cancelActiveTasks(String sessionKey) {
        var tasks = activeTasks.get(sessionKey);
        if (tasks == null) return 0;
        int count = 0;
        for (var t : tasks) {
            if (t.isAlive()) {
                t.interrupt();
                count++;
            }
        }
        tasks.clear();
        return count;
    }
}
