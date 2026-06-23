package com.nanobot.session;

import com.nanobot.bus.*;
import com.nanobot.providers.LLMProvider;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 将运行时事件翻译为 WebUI/WebSocket wire messages。
 * 订阅全部 5 种 RuntimeEvent 类型。
 *
 * <p>对标 Python {@code nanobot/session/webui_turns.py:32-449 class WebuiTurnCoordinator}。
 */
public class WebuiTurnCoordinator {

    /** 对标 Python WEBUI_SESSION_METADATA_KEY = "webui"。 */
    public static final String WEBUI_SESSION_META = "webui";

    /** 对标 Python websocket_turn_wall_started_at 模块级 dict。 */
    private static final ConcurrentHashMap<String, Double> WEBSOCKET_TURN_WALL_STARTED_AT =
            new ConcurrentHashMap<>();

    private final MessageBus bus;
    private final SessionManager sessions;
    private final Consumer<Runnable> scheduleBackground;

    /** 对标 Python _title_contexts: dict[str, LLMRuntime]。 */
    private final ConcurrentHashMap<String, LLMRuntime> titleContexts;

    public WebuiTurnCoordinator(MessageBus bus, SessionManager sessions,
                                Consumer<Runnable> scheduleBackground) {
        this.bus = bus;
        this.sessions = sessions;
        this.scheduleBackground = scheduleBackground;
        this.titleContexts = new ConcurrentHashMap<>();
    }

    /**
     * 订阅所有运行时事件，返回取消订阅的回调。
     * 对标 Python WebuiTurnCoordinator.subscribe(runtime_events) → unsubscribe callback。
     */
    public Runnable subscribe(RuntimeEventBus events) {
        SyncRuntimeEventHandler h1 = e -> onSessionTurnStarted((SessionTurnStarted) e);
        SyncRuntimeEventHandler h2 = e -> onRunStatusChanged((TurnRunStatusChanged) e);
        SyncRuntimeEventHandler h3 = e -> onTurnCompleted((TurnCompleted) e);
        SyncRuntimeEventHandler h4 = e -> onGoalStateChanged((GoalStateChanged) e);
        SyncRuntimeEventHandler h5 = e -> onRuntimeModelChanged((RuntimeModelChanged) e);
        Runnable u1 = events.subscribe(h1, SessionTurnStarted.class);
        Runnable u2 = events.subscribe(h2, TurnRunStatusChanged.class);
        Runnable u3 = events.subscribe(h3, TurnCompleted.class);
        Runnable u4 = events.subscribe(h4, GoalStateChanged.class);
        Runnable u5 = events.subscribe(h5, RuntimeModelChanged.class);
        return () -> {
            u5.run(); u4.run(); u3.run(); u2.run(); u1.run();
        };
    }

    /**
     * 捕获标题生成所需的 LLM 运行时引用。
     * 对标 Python capture_title_context(session_key, msg, llm_runtime)。
     */
    public void captureTitleContext(String sessionKey, InboundMessage msg, LLMRuntime llm) {
        if ("websocket".equals(msg.channel())
                && Boolean.TRUE.equals(msg.metadata().get(WEBUI_SESSION_META))) {
            titleContexts.put(sessionKey, llm);
        }
    }

    /** 丢弃标题上下文。对标 Python discard(session_key)。 */
    public void discard(String sessionKey) {
        titleContexts.remove(sessionKey);
    }

    // ==================== event handlers ====================

    /** 对标 Python _handle_session_turn_started。 */
    private void onSessionTurnStarted(SessionTurnStarted event) {
        if (!isWs(event.context())) return;
        Session s = sessions.getOrCreate(event.context().sessionKey());
        if (Boolean.TRUE.equals(event.context().metadata().get(WEBUI_SESSION_META))) {
            s.getMetadata().put(WEBUI_SESSION_META, Boolean.TRUE);
        }
    }

    /** 对标 Python _handle_run_status_changed。 */
    private void onRunStatusChanged(TurnRunStatusChanged event) {
        if (!isWs(event.context())) return;
        publishTurnRunStatus(event.status(), event.startedAt(), event.context());
    }

    /** 对标 Python _handle_turn_completed_event。 */
    private void onTurnCompleted(TurnCompleted event) {
        if (!isWs(event.context())) return;
        handleTurnEnd(event.context(), event.latencyMs());
        scheduleTitleUpdateFromEvent(event);
    }

    /** 对标 Python _handle_goal_state_changed。 */
    private void onGoalStateChanged(GoalStateChanged event) {
        if (!isWs(event.context())) return;
        String cid = event.context().chatId();
        if (cid == null || cid.isBlank()) return;
        publish(cid, "websocket", Map.of(
                "_goal_state_sync", Boolean.TRUE,
                "goal_state", GoalState.goalStateWsBlob(event.sessionMetadata())));
    }

    /** 对标 Python _handle_runtime_model_changed。 */
    private void onRuntimeModelChanged(RuntimeModelChanged event) {
        publish("*", "websocket", Map.of(
                "_runtime_model_updated", Boolean.TRUE,
                "model", event.model(),
                "model_preset", Objects.toString(event.modelPreset(), "")));
    }

    // ==================== public helpers ====================

    /**
     * 发布 turn 运行状态变更。
     * 对标 Python publish_turn_run_status(bus, msg, status, *, started_at=None)。
     */
    public void publishTurnRunStatus(String status, Double startedAt,
                                     RuntimeEventContext ctx) {
        if (!"websocket".equals(ctx.channel())) return;
        String cid = ctx.chatId();
        Map<String, Object> meta = new LinkedHashMap<>(ctx.metadata());
        meta.put("_goal_status", Boolean.TRUE);
        meta.put("goal_status", status);
        if ("running".equals(status)) {
            double t0 = (startedAt != null && startedAt > 0)
                    ? startedAt : currentEpoch();
            meta.put("started_at", t0);
            WEBSOCKET_TURN_WALL_STARTED_AT.put(cid, t0);
        } else {
            WEBSOCKET_TURN_WALL_STARTED_AT.remove(cid);
        }
        try {
            bus.publishOutbound(new OutboundMessage(
                    ctx.channel(), cid, "", null, List.of(), meta, List.of()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 处理 turn 结束——发布 _turn_end 元数据和 goal_state。
     * 对标 Python handle_turn_end(msg, session_key, *, latency_ms=None)。
     */
    public void handleTurnEnd(RuntimeEventContext ctx, Integer latencyMs) {
        if (!"websocket".equals(ctx.channel())) return;
        Map<String, Object> turnMeta = new LinkedHashMap<>(ctx.metadata());
        turnMeta.put("_turn_end", Boolean.TRUE);
        if (latencyMs != null) turnMeta.put("latency_ms", latencyMs);
        Session s = sessions.getOrCreate(ctx.sessionKey());
        turnMeta.put("goal_state", GoalState.goalStateWsBlob(s.getMetadata()));
        publish(ctx.chatId(), ctx.channel(), turnMeta);
        scheduleTitleUpdate(ctx, ctx.sessionKey());
    }

    // ==================== title scheduling ====================

    /**
     * 在 turn 结束后调度标题生成（fire-and-forget）。
     * 对标 Python _schedule_title_update。
     */
    public void scheduleTitleUpdate(RuntimeEventContext ctx, String sessionKey) {
        LLMRuntime titleContext = titleContexts.remove(sessionKey);
        if (!Boolean.TRUE.equals(ctx.metadata().get(WEBUI_SESSION_META))
                || titleContext == null) {
            return;
        }

        scheduleBackground.accept(() -> {
            LLMProvider provider = titleContext.getProvider();
            String model = titleContext.getModel();
            boolean generated = WebuiTitleGenerator.maybeGenerateTitleAfterTurn(
                    ctx.channel(), ctx.metadata(), sessions,
                    sessionKey, provider, model);
            if (generated) {
                Map<String, Object> meta = new LinkedHashMap<>(ctx.metadata());
                meta.put("_session_updated", Boolean.TRUE);
                meta.put("_session_update_scope", "metadata");
                publish(ctx.chatId(), ctx.channel(), meta);
            }
        });
    }

    /**
     * 从 TurnCompleted 事件调度标题生成。
     * 对标 Python _schedule_title_update_from_event。
     */
    public void scheduleTitleUpdateFromEvent(TurnCompleted event) {
        if (!Boolean.TRUE.equals(event.context().metadata().get(WEBUI_SESSION_META))) {
            return;
        }
        Object runtime = event.runtime();
        if (runtime == null || !(runtime instanceof LLMRuntime titleContext)) {
            return;
        }

        scheduleBackground.accept(() -> {
            boolean generated = WebuiTitleGenerator.maybeGenerateTitleAfterTurn(
                    event.context().channel(), event.context().metadata(),
                    sessions, event.context().sessionKey(),
                    titleContext.getProvider(), titleContext.getModel());
            if (generated) {
                Map<String, Object> meta = new LinkedHashMap<>(event.context().metadata());
                meta.put("_session_updated", Boolean.TRUE);
                meta.put("_session_update_scope", "metadata");
                publish(event.context().chatId(), event.context().channel(), meta);
            }
        });
    }

    // ==================== static helpers ====================

    /**
     * 获取 WebSocket turn wall started_at（进程全局）。
     * 对标 Python websocket_turn_wall_started_at(chat_id)。
     */
    public static Double websocketTurnWallStartedAt(String chatId) {
        return WEBSOCKET_TURN_WALL_STARTED_AT.get(chatId);
    }

    /**
     * 构建发布到 bus 的进度回调。
     * 对标 Python build_bus_progress_callback(bus, msg)。
     */
    public static BusProgressCallback buildBusProgressCallback(MessageBus bus, InboundMessage msg) {
        return BusProgressCallback.create(bus, msg);
    }

    private void publish(String chatId, String channel, Map<String, Object> meta) {
        try {
            bus.publishOutbound(new OutboundMessage(
                    channel, chatId, "", null, List.of(), meta, List.of()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isWs(RuntimeEventContext c) {
        return "websocket".equals(c.channel());
    }

    private static double currentEpoch() {
        return System.currentTimeMillis() / 1000.0;
    }

    // ==================== LLMRuntime placeholder ====================

    /**
     * 标题生成所需的 LLM provider + model 引用的持有者。
     * 对标 Python LLMRuntime（简化）。
     */
    public record LLMRuntime(LLMProvider getProvider, String getModel) {}
}
