package com.nanobot.agent;

import com.nanobot.agent.tools.ToolRegistry;
import com.nanobot.bus.InboundMessage;
import com.nanobot.bus.OutboundMessage;
import com.nanobot.session.Session;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 单轮 turn 的可变上下文，贯穿状态机全部阶段。
 *
 * <p>对标 Python {@code nanobot/agent/loop.py} TurnContext 数据类。
 * 实现 TurnContinuationContext 以支持 turn 延续机制。
 */
public class TurnContext implements com.nanobot.session.TurnContinuation.TurnContinuationContext {

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
    private boolean hadInjections;

    private boolean userPersistedEarly;
    private int saveSkip;

    private OutboundMessage outbound;
    private boolean suppressResponse;

    /** 进度回调：接收 (content, metadata)，返回 CompletableFuture。 */
    private BiFunction<String, Map<String, Object>, CompletableFuture<Void>> onProgress;
    /** 流式输出回调：接收 delta 文本。 */
    private Function<String, CompletableFuture<Void>> onStream;
    /** 流式结束回调：接收 resuming 标志。 */
    private Function<Boolean, CompletableFuture<Void>> onStreamEnd;
    /** 重试等待回调：接收等待消息文本。 */
    private Function<String, CompletableFuture<Void>> onRetryWait;

    private BlockingQueue<InboundMessage> pendingQueue;
    private String pendingSummary;

    private boolean ephemeral;
    private ToolRegistry tools;

    private final long turnWallStartedAt;
    private Long visibleRunStartedAt;
    private Integer turnLatencyMs;

    private final List<StateTraceEntry> trace = new ArrayList<>();

    /**
     * 构造 TurnContext。
     *
     * @param msg                入站消息
     * @param sessionKey         会话键
     * @param state              初始状态
     * @param turnId             turn 标识
     * @param turnWallStartedAt  turn 开始时间戳
     */
    public TurnContext(InboundMessage msg, String sessionKey, TurnState state,
                       String turnId, long turnWallStartedAt) {
        this.msg = msg;
        this.sessionKey = sessionKey;
        this.state = state;
        this.turnId = turnId;
        this.turnWallStartedAt = turnWallStartedAt;
    }

    // ==================== getters / setters ====================

    public InboundMessage getMsg() { return msg; }
    public void setMsg(InboundMessage msg) { this.msg = msg; }

    /** 对标 TurnContinuationContext.getOriginalMsg() —— 返回原始入站消息。 */
    @Override public InboundMessage getOriginalMsg() { return msg; }

    /** 对标 TurnContinuationContext.getMessageMetadata() —— 返回消息元数据。 */
    @Override
    public Map<String, Object> getMessageMetadata() {
        return msg != null ? msg.metadata() : null;
    }

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

    /** 对标 Python prepare_save_boundary —— 初始消息数量。 */
    @Override
    public int getInitialMessageCount() { return initialMessages.size(); }

    /** 对标 Python prepare_save_boundary —— session history 数量。 */
    @Override
    public int getHistoryCount() { return history.size(); }

    public int getSaveSkip() { return saveSkip; }
    public void setSaveSkip(int saveSkip) { this.saveSkip = saveSkip; }

    public OutboundMessage getOutbound() { return outbound; }
    public void setOutbound(OutboundMessage outbound) { this.outbound = outbound; }

    public boolean isSuppressResponse() { return suppressResponse; }
    public void setSuppressResponse(boolean suppressResponse) { this.suppressResponse = suppressResponse; }

    public BiFunction<String, Map<String, Object>, CompletableFuture<Void>> getOnProgress() { return onProgress; }
    public void setOnProgress(BiFunction<String, Map<String, Object>, CompletableFuture<Void>> onProgress) { this.onProgress = onProgress; }

    public Function<String, CompletableFuture<Void>> getOnStream() { return onStream; }
    public void setOnStream(Function<String, CompletableFuture<Void>> onStream) { this.onStream = onStream; }

    public Function<Boolean, CompletableFuture<Void>> getOnStreamEnd() { return onStreamEnd; }
    public void setOnStreamEnd(Function<Boolean, CompletableFuture<Void>> onStreamEnd) { this.onStreamEnd = onStreamEnd; }

    public Function<String, CompletableFuture<Void>> getOnRetryWait() { return onRetryWait; }
    public void setOnRetryWait(Function<String, CompletableFuture<Void>> onRetryWait) { this.onRetryWait = onRetryWait; }

    public BlockingQueue<InboundMessage> getPendingQueue() { return pendingQueue; }
    public void setPendingQueue(BlockingQueue<InboundMessage> pendingQueue) { this.pendingQueue = pendingQueue; }

    public String getPendingSummary() { return pendingSummary; }
    public void setPendingSummary(String pendingSummary) { this.pendingSummary = pendingSummary; }

    public boolean isEphemeral() { return ephemeral; }
    public void setEphemeral(boolean ephemeral) { this.ephemeral = ephemeral; }

    public ToolRegistry getTools() { return tools; }
    public void setTools(ToolRegistry tools) { this.tools = tools; }

    public long getTurnWallStartedAt() { return turnWallStartedAt; }

    public Long getVisibleRunStartedAt() { return visibleRunStartedAt; }
    public void setVisibleRunStartedAt(Long visibleRunStartedAt) { this.visibleRunStartedAt = visibleRunStartedAt; }

    public Integer getTurnLatencyMs() { return turnLatencyMs; }
    public void setTurnLatencyMs(Integer turnLatencyMs) { this.turnLatencyMs = turnLatencyMs; }

    public List<StateTraceEntry> getTrace() { return trace; }

    // ==================== builder ====================

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private InboundMessage msg;
        private String sessionKey;
        private TurnState state;
        private String turnId;
        private long turnWallStartedAt;
        private Session session;
        private List<Map<String, Object>> history;
        private List<Map<String, Object>> initialMessages;
        private List<StateTraceEntry> trace;
        private BiFunction<String, Map<String, Object>, CompletableFuture<Void>> onProgress;
        private Function<String, CompletableFuture<Void>> onStream;
        private Function<Boolean, CompletableFuture<Void>> onStreamEnd;
        private Function<String, CompletableFuture<Void>> onRetryWait;
        private BlockingQueue<InboundMessage> pendingQueue;
        private String pendingSummary;
        private ToolRegistry tools;

        public Builder msg(InboundMessage v) { this.msg = v; return this; }
        public Builder sessionKey(String v) { this.sessionKey = v; return this; }
        public Builder state(TurnState v) { this.state = v; return this; }
        public Builder turnId(String v) { this.turnId = v; return this; }
        public Builder turnWallStartedAt(long v) { this.turnWallStartedAt = v; return this; }
        public Builder session(Session v) { this.session = v; return this; }
        public Builder history(List<Map<String, Object>> v) { this.history = v; return this; }
        public Builder initialMessages(List<Map<String, Object>> v) { this.initialMessages = v; return this; }
        public Builder trace(List<StateTraceEntry> v) { this.trace = v; return this; }
        public Builder onProgress(BiFunction<String, Map<String, Object>, CompletableFuture<Void>> v) { this.onProgress = v; return this; }
        public Builder onStream(Function<String, CompletableFuture<Void>> v) { this.onStream = v; return this; }
        public Builder onStreamEnd(Function<Boolean, CompletableFuture<Void>> v) { this.onStreamEnd = v; return this; }
        public Builder onRetryWait(Function<String, CompletableFuture<Void>> v) { this.onRetryWait = v; return this; }
        public Builder pendingQueue(BlockingQueue<InboundMessage> v) { this.pendingQueue = v; return this; }
        public Builder pendingSummary(String v) { this.pendingSummary = v; return this; }
        public Builder tools(ToolRegistry v) { this.tools = v; return this; }

        public TurnContext build() {
            TurnContext ctx = new TurnContext(msg, sessionKey, state, turnId, turnWallStartedAt);
            if (session != null) ctx.setSession(session);
            if (history != null) ctx.setHistory(history);
            if (initialMessages != null) ctx.setInitialMessages(initialMessages);
            if (trace != null) ctx.getTrace().addAll(trace);
            if (onProgress != null) ctx.setOnProgress(onProgress);
            if (onStream != null) ctx.setOnStream(onStream);
            if (onStreamEnd != null) ctx.setOnStreamEnd(onStreamEnd);
            if (onRetryWait != null) ctx.setOnRetryWait(onRetryWait);
            if (pendingQueue != null) ctx.setPendingQueue(pendingQueue);
            if (pendingSummary != null) ctx.setPendingSummary(pendingSummary);
            if (tools != null) ctx.setTools(tools);
            return ctx;
        }
    }
}
