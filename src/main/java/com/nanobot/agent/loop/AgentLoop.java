package com.nanobot.agent.loop;

import com.nanobot.agent.command.CommandContext;
import com.nanobot.agent.command.CommandRouter;
import com.nanobot.agent.context.ContextBuilder;
import com.nanobot.agent.context.Consolidator;
import com.nanobot.agent.runner.AgentRunSpec;
import com.nanobot.agent.runner.AgentRunner;
import com.nanobot.agent.session.Session;
import com.nanobot.agent.session.SessionManager;
import com.nanobot.agent.tools.ToolRegistry;
import com.nanobot.bus.InboundMessage;
import com.nanobot.bus.MessageBus;
import com.nanobot.bus.OutboundMessage;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

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
        lock.lock();
        try {
            concurrencyGate.acquire();
        } catch (InterruptedException e) {
            lock.unlock();
            Thread.currentThread().interrupt();
            return;
        }

        try {
            processMessage(msg);
        } catch (Exception e) {
            log.error("Error processing message for session {}", key, e);
            try {
                bus.publishOutbound(new OutboundMessage(
                        msg.channel(), msg.chatId(), "Error processing message: " + e.getMessage(),
                        null, null, null, null));
            } catch (InterruptedException ignored) {}
        } finally {
            concurrencyGate.release();
            lock.unlock();
        }
    }

    // -- processMessage --

    public void processMessage(InboundMessage msg) {
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
    }

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

        var spec = new AgentRunSpec(
                ctx.initialMessages(), tools, model, maxIterations, maxToolResultChars,
                null, null, null, null, null, null,
                false, false, workspace, ctx.sessionKey(),
                contextWindowTokens, null, providerRetryMode,
                null, true, null, null, null, null, null,
                null, true);

        var runCtx = new com.nanobot.agent.hook.AgentRunHookContext();
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
        } catch (Exception e) {
            log.error("Agent run failed for {}", ctx.sessionKey(), e);
            ctx.setFinalContent("An error occurred during processing.");
            ctx.setStopReason("error");
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
        if (ctx.suppressResponse()) return "ok";

        var msg = ctx.msg();
        var content = ctx.finalContent();
        if (content == null || content.isBlank()) {
            content = "[No response generated]";
        }

        var outbound = new OutboundMessage(
                msg.channel(), msg.chatId(), content,
                null, null,
                msg.metadata() != null ? new LinkedHashMap<>(msg.metadata()) : Map.of(),
                null);
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

        // Append new messages to session
        int existingCount = session.messages().size();
        for (int i = existingCount; i < allMessages.size(); i++) {
            var msg = allMessages.get(i);
            var role = msg.get("role");
            var content = msg.get("content");
            if (role != null) {
                var extra = new LinkedHashMap<String, Object>();
                for (var entry : msg.entrySet()) {
                    if (!"role".equals(entry.getKey()) && !"content".equals(entry.getKey())) {
                        extra.put(entry.getKey(), entry.getValue());
                    }
                }
                session.addMessage(role.toString(),
                        content != null ? content.toString() : "",
                        extra.isEmpty() ? null : extra);
            }
        }
        sessions.save(session);
    }

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
