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
 * 每轮 turn 的可变上下文，贯穿状态机各阶段。
 * 对应 Python TurnContext dataclass（loop.py 行 97-135）。
 *
 * <p>包含：入站消息、session 引用、历史消息、工具使用记录、流式回调、
 * trace 追踪、pending 队列等全部 turn 生命周期状态。</p>
 */
public class TurnContext {

    /** 入站消息 */
    private final InboundMessage msg;
    /** 会话 key */
    private final String sessionKey;
    /** 当前状态机状态 */
    private TurnState state;
    /** turn 唯一标识 */
    private final String turnId;
    /** 当前会话引用 */
    @Nullable
    private Session session;

    /** 清洗后的历史消息列表 */
    private List<Map<String, Object>> history = new ArrayList<>();
    /** 初始消息列表（不含当前用户输入） */
    private List<Map<String, Object>> initialMessages = new ArrayList<>();

    /** 最终回复内容 */
    @Nullable
    private String finalContent;
    /** 本轮使用的工具列表 */
    private List<String> toolsUsed = new ArrayList<>();
    /** 全部消息（含 system 提示词） */
    private List<Map<String, Object>> allMessages = new ArrayList<>();
    /** 停止原因 */
    private String stopReason = "";
    /** 是否有注入消息 */
    private boolean hadInjections;

    /** 用户消息是否已提前持久化 */
    private boolean userPersistedEarly;
    /** SAVE 阶段跳过次数 */
    private int saveSkip;

    /** 出站回复消息 */
    @Nullable
    private OutboundMessage outbound;
    /** 是否抑制回复发送 */
    private boolean suppressResponse;

    // 回调
    /** 进度回调（map 含 type, content 等字段） */
    @Nullable
    private Consumer<Map<String, Object>> onProgress;
    /** 流式 delta 回调 */
    @Nullable
    private Consumer<String> onStream;
    /** 流式结束回调 */
    @Nullable
    private Runnable onStreamEnd;
    /** 重试等待回调 */
    @Nullable
    private Consumer<String> onRetryWait;

    /** pending 消息队列（turn 期间到达的新消息暂存于此） */
    @Nullable
    private BlockingQueue<InboundMessage> pendingQueue;
    /** pending 摘要文本 */
    @Nullable
    private String pendingSummary;

    /** 是否临时会话 */
    private boolean ephemeral;
    /** 工具注册表 */
    @Nullable
    private ToolRegistry tools;

    /** turn 整体开始时间（epoch 秒） */
    private final double turnWallStartedAt = System.currentTimeMillis() / 1000.0;
    /** 可见运行开始时间（epoch 秒） */
    @Nullable
    private Double visibleRunStartedAt;
    /** turn 延迟（毫秒） */
    @Nullable
    private Integer turnLatencyMs;

    /** 状态机追踪记录 */
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
