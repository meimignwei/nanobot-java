package com.nanobot.bus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * turn 作用域运行时事件的便捷发布器。
 * 对应 Python RuntimeEventPublisher（bus/runtime_events.py:136-235）。
 *
 * <p>封装事件上下文构建、turn 延迟/运行时追踪、模型变更通知。</p>
 */
public class RuntimeEventPublisher {

    private final RuntimeEventBus bus;
    private final Map<String, Integer> turnLatencyMs = new ConcurrentHashMap<>();
    private final Map<String, Object> turnRuntime = new ConcurrentHashMap<>();

    public RuntimeEventPublisher(RuntimeEventBus bus) {
        this.bus = bus != null ? bus : new RuntimeEventBus();
    }

    /** 构建事件上下文字典。
     *  对应 Python RuntimeEventPublisher._context()。 */
    static Map<String, Object> context(String channel, String chatId,
                                        String sessionKey, Map<String, Object> metadata) {
        var ctx = new java.util.LinkedHashMap<String, Object>();
        ctx.put("channel", channel);
        ctx.put("chat_id", chatId);
        ctx.put("session_key", sessionKey);
        ctx.put("metadata", metadata != null ? Map.copyOf(metadata) : Map.of());
        return ctx;
    }

    /** 记录 turn 延迟。
     *  对应 Python record_turn_latency()。 */
    public void recordTurnLatency(String sessionKey, Integer latencyMs) {
        if (latencyMs != null) this.turnLatencyMs.put(sessionKey, latencyMs);
    }

    /** 清理 turn 状态。
     *  对应 Python clear_turn()。 */
    public void clearTurn(String sessionKey) {
        turnLatencyMs.remove(sessionKey);
        turnRuntime.remove(sessionKey);
    }

    /** 发布 session turn 开始事件。
     *  对应 Python session_turn_started()。 */
    public void sessionTurnStarted(String channel, String chatId,
                                    String sessionKey, Map<String, Object> metadata) {
        bus.publish(new RuntimeEventBus.SessionTurnStarted(
                context(channel, chatId, sessionKey, metadata)));
    }

    /** 发布运行状态变更事件。
     *  对应 Python run_status_changed()。 */
    public void runStatusChanged(String channel, String chatId,
                                  String sessionKey, String status,
                                  Map<String, Object> metadata, Double startedAt) {
        bus.publish(new RuntimeEventBus.TurnRunStatusChanged(
                context(channel, chatId, sessionKey, metadata), status, startedAt));
    }

    /** 发布 turn 完成事件。
     *  对应 Python turn_completed()。 */
    public void turnCompleted(String channel, String chatId,
                               String sessionKey, Map<String, Object> metadata) {
        bus.publish(new RuntimeEventBus.TurnCompleted(
                context(channel, chatId, sessionKey, metadata),
                turnLatencyMs.remove(sessionKey),
                turnRuntime.remove(sessionKey)));
    }

    /** 发布模型变更事件。
     *  对应 Python runtime_model_changed()。 */
    public void runtimeModelChanged(String model, String modelPreset) {
        bus.publishNowait(new RuntimeEventBus.RuntimeModelChanged(model, modelPreset));
    }

    public RuntimeEventBus bus() { return bus; }
}
