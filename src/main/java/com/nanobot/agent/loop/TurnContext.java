package com.nanobot.agent.loop;

import com.nanobot.agent.session.Session;
import com.nanobot.agent.tools.ToolRegistry;
import com.nanobot.bus.InboundMessage;
import com.nanobot.bus.OutboundMessage;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.function.Consumer;

/**
 * Mutable per-turn context carried through the state machine.
 * Mirrors Python TurnContext dataclass (loop.py lines 97-135).
 */
public class TurnContext {

    private final InboundMessage msg;
    private final String sessionKey;
    private TurnState state;
    private final String turnId;
    @Nullable
    private Session session;

    private List<Map<String, Object>> history = new ArrayList<>();
    private List<Map<String, Object>> initialMessages = new ArrayList<>();

    @Nullable
    private String finalContent;
    private List<String> toolsUsed = new ArrayList<>();
    private List<Map<String, Object>> allMessages = new ArrayList<>();
    private String stopReason = "";
    private boolean hadInjections;

    private boolean userPersistedEarly;
    private int saveSkip;

    @Nullable
    private OutboundMessage outbound;
    private boolean suppressResponse;

    @Nullable
    private Consumer<Map<String, Object>> onProgress;
    @Nullable
    private Consumer<String> onStream;
    @Nullable
    private Runnable onStreamEnd;
    @Nullable
    private Consumer<String> onRetryWait;

    @Nullable
    private BlockingQueue<InboundMessage> pendingQueue;
    @Nullable
    private String pendingSummary;

    private boolean ephemeral;
    @Nullable
    private ToolRegistry tools;

    private final double turnWallStartedAt = System.currentTimeMillis() / 1000.0;
    @Nullable
    private Double visibleRunStartedAt;
    @Nullable
    private Integer turnLatencyMs;

    private final List<StateTraceEntry> trace = new ArrayList<>();

    public TurnContext(InboundMessage msg, String sessionKey, TurnState state, String turnId) {
        this.msg = msg;
        this.sessionKey = sessionKey;
        this.state = state;
        this.turnId = turnId;
    }

    // -- accessors --

    public InboundMessage msg() { return msg; }
    public String sessionKey() { return sessionKey; }
    public TurnState state() { return state; }
    public void setState(TurnState state) { this.state = state; }
    public String turnId() { return turnId; }
    @Nullable
    public Session session() { return session; }
    public void setSession(@Nullable Session session) { this.session = session; }

    public List<Map<String, Object>> history() { return history; }
    public void setHistory(List<Map<String, Object>> history) { this.history = history; }

    public List<Map<String, Object>> initialMessages() { return initialMessages; }
    public void setInitialMessages(List<Map<String, Object>> v) { this.initialMessages = v; }

    @Nullable
    public String finalContent() { return finalContent; }
    public void setFinalContent(@Nullable String v) { this.finalContent = v; }

    public List<String> toolsUsed() { return toolsUsed; }
    public void setToolsUsed(List<String> v) { this.toolsUsed = v; }

    public List<Map<String, Object>> allMessages() { return allMessages; }
    public void setAllMessages(List<Map<String, Object>> v) { this.allMessages = v; }

    public String stopReason() { return stopReason; }
    public void setStopReason(String v) { this.stopReason = v; }

    public boolean hadInjections() { return hadInjections; }
    public void setHadInjections(boolean v) { this.hadInjections = v; }

    public boolean userPersistedEarly() { return userPersistedEarly; }
    public void setUserPersistedEarly(boolean v) { this.userPersistedEarly = v; }

    public int saveSkip() { return saveSkip; }
    public void setSaveSkip(int v) { this.saveSkip = v; }

    @Nullable
    public OutboundMessage outbound() { return outbound; }
    public void setOutbound(@Nullable OutboundMessage v) { this.outbound = v; }

    public boolean suppressResponse() { return suppressResponse; }
    public void setSuppressResponse(boolean v) { this.suppressResponse = v; }

    @Nullable
    public Consumer<Map<String, Object>> onProgress() { return onProgress; }
    public void setOnProgress(@Nullable Consumer<Map<String, Object>> v) { this.onProgress = v; }

    @Nullable
    public Consumer<String> onStream() { return onStream; }
    public void setOnStream(@Nullable Consumer<String> v) { this.onStream = v; }

    @Nullable
    public Runnable onStreamEnd() { return onStreamEnd; }
    public void setOnStreamEnd(@Nullable Runnable v) { this.onStreamEnd = v; }

    @Nullable
    public Consumer<String> onRetryWait() { return onRetryWait; }
    public void setOnRetryWait(@Nullable Consumer<String> v) { this.onRetryWait = v; }

    @Nullable
    public BlockingQueue<InboundMessage> pendingQueue() { return pendingQueue; }
    public void setPendingQueue(@Nullable BlockingQueue<InboundMessage> v) { this.pendingQueue = v; }

    @Nullable
    public String pendingSummary() { return pendingSummary; }
    public void setPendingSummary(@Nullable String v) { this.pendingSummary = v; }

    public boolean ephemeral() { return ephemeral; }
    public void setEphemeral(boolean v) { this.ephemeral = v; }

    @Nullable
    public ToolRegistry tools() { return tools; }
    public void setTools(@Nullable ToolRegistry v) { this.tools = v; }

    public double turnWallStartedAt() { return turnWallStartedAt; }

    @Nullable
    public Double visibleRunStartedAt() { return visibleRunStartedAt; }
    public void setVisibleRunStartedAt(@Nullable Double v) { this.visibleRunStartedAt = v; }

    @Nullable
    public Integer turnLatencyMs() { return turnLatencyMs; }
    public void setTurnLatencyMs(@Nullable Integer v) { this.turnLatencyMs = v; }

    public List<StateTraceEntry> trace() { return trace; }
}
