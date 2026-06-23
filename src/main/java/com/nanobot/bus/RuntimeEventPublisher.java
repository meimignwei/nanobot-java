package com.nanobot.bus;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * turn 级运行时事件的便捷发布器。
 *
 * <p>Agent 代码决定状态转换时机，此 helper 负责构建事件上下文、管理 per-session 元数据
 * （turn 延迟、LLM runtime 引用）并发布到 {@link RuntimeEventBus}。
 *
 * <p>对标 Python {@code nanobot/bus/runtime_events.py:136-235 class RuntimeEventPublisher}。
 */
public class RuntimeEventPublisher {

    private final RuntimeEventBus bus;

    /** 每个 session 的 turn 延迟（毫秒），对标 Python _turn_latency_ms: dict[str, int] */
    private final ConcurrentHashMap<String, Integer> turnLatencyMs;

    /** 每个 session 的 LLM runtime 引用（供 title generation 使用），对标 Python _turn_runtime: dict[str, Any] */
    private final ConcurrentHashMap<String, Object> turnRuntime;

    /**
     * 使用指定总线创建发布器。
     *
     * @param bus 运行时事件总线
     */
    public RuntimeEventPublisher(RuntimeEventBus bus) {
        this.bus = bus;
        this.turnLatencyMs = new ConcurrentHashMap<>();
        this.turnRuntime = new ConcurrentHashMap<>();
    }

    /** 创建带默认总线的发布器。对标 Python {@code RuntimeEventPublisher(bus=None)} */
    public RuntimeEventPublisher() {
        this(new RuntimeEventBus());
    }

    /**
     * 构建路由上下文（静态方法，对标 Python _context()）。
     *
     * @param channel    channel 标识
     * @param chatId     会话标识
     * @param sessionKey session 键
     * @param metadata   元数据，可为 null
     * @return 不可变的 RuntimeEventContext
     */
    // 对标 Python runtime_events.py:148-161 _context()
    private static RuntimeEventContext context(
            String channel,
            String chatId,
            String sessionKey,
            Map<String, Object> metadata) {
        Map<String, Object> metaCopy = (metadata != null)
                ? Collections.unmodifiableMap(metadata)
                : Collections.emptyMap();
        return new RuntimeEventContext(channel, chatId, sessionKey, metaCopy);
    }

    /**
     * 记录 session 的 LLM runtime 引用，供 title generation 模块后续使用。
     *
     * @param sessionKey session 键
     * @param runtime    LLM runtime 对象
     */
    // 对标 Python runtime_events.py:163-164 record_turn_runtime()
    public void recordTurnRuntime(String sessionKey, Object runtime) {
        turnRuntime.put(sessionKey, runtime);
    }

    /**
     * 记录 session 的 turn 延迟（毫秒）。
     *
     * @param sessionKey session 键
     * @param latencyMs  延迟毫秒数，null 时忽略
     */
    // 对标 Python runtime_events.py:166-168 record_turn_latency()
    public void recordTurnLatency(String sessionKey, Integer latencyMs) {
        if (latencyMs != null) {
            turnLatencyMs.put(sessionKey, latencyMs);
        }
    }

    /**
     * 清除 session 的 per-turn 状态。
     *
     * @param sessionKey session 键
     */
    // 对标 Python runtime_events.py:170-172 clear_turn()
    public void clearTurn(String sessionKey) {
        turnLatencyMs.remove(sessionKey);
        turnRuntime.remove(sessionKey);
    }

    /**
     * 发布 {@link SessionTurnStarted} 事件。
     * 对标 Python {@code async def session_turn_started(msg, session_key)}，
     * Java 中 publish() 会 join 等待异步 handler 完成后返回（同步语义）。
     *
     * @param msg        入站消息
     * @param sessionKey session 键
     */
    // 对标 Python runtime_events.py:174-188 session_turn_started()
    public void sessionTurnStarted(InboundMessage msg, String sessionKey) {
        RuntimeEventContext ctx = context(
                msg.channel(), msg.chatId(), sessionKey, msg.metadata());
        bus.publish(new SessionTurnStarted(ctx));
    }

    /**
     * 发布 {@link TurnRunStatusChanged} 事件。
     *
     * @param msg        入站消息
     * @param sessionKey session 键
     * @param status     状态描述（如 "running"、"done"、"error"）
     * @param startedAt  epoch 秒数，可为 null
     */
    // 对标 Python runtime_events.py:190-209 run_status_changed()
    public void runStatusChanged(
            InboundMessage msg,
            String sessionKey,
            String status,
            Double startedAt) {
        RuntimeEventContext ctx = context(
                msg.channel(), msg.chatId(), sessionKey, msg.metadata());
        bus.publish(new TurnRunStatusChanged(ctx, status, startedAt));
    }

    /**
     * 发布 {@link TurnCompleted} 事件，消费已存储的延迟和 runtime。
     *
     * @param channel    channel 标识
     * @param chatId     会话标识
     * @param sessionKey session 键
     * @param metadata   元数据，可为 null
     */
    // 对标 Python runtime_events.py:211-230 turn_completed()
    public void turnCompleted(
            String channel,
            String chatId,
            String sessionKey,
            Map<String, Object> metadata) {
        RuntimeEventContext ctx = context(channel, chatId, sessionKey, metadata);
        Integer latency = turnLatencyMs.remove(sessionKey);
        Object runtime = turnRuntime.remove(sessionKey);
        bus.publish(new TurnCompleted(ctx, latency, runtime));
    }

    /**
     * 异步发布 {@link RuntimeModelChanged} 事件（不阻塞调用方）。
     *
     * @param model       模型标识符
     * @param modelPreset preset 名称，可为 null
     */
    // 对标 Python runtime_events.py:232-235 runtime_model_changed()
    public void runtimeModelChanged(String model, String modelPreset) {
        bus.publishNowait(new RuntimeModelChanged(model, modelPreset));
    }
}
