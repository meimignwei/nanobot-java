package com.nanobot.session;

import com.nanobot.bus.InboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.BlockingQueue;

/**
 * 预算边界延续策略——当 turn 因 max_iterations 预算用尽且存在活跃持续目标时，
 * 向待处理队列注入内部延续消息。
 *
 * <p>对标 Python {@code nanobot/session/turn_continuation.py}。
 */
public final class TurnContinuation {

    private static final Logger log = LoggerFactory.getLogger(TurnContinuation.class);

    private TurnContinuation() {}

    /**
     * 获取共享单例（所有方法均为静态，实例仅用作依赖注入占位）。
     * 对标 Python: TurnContinuation 在 loop 中作为依赖注入，但所有方法均为模块级函数。
     */
    public static TurnContinuation getInstance() {
        return Holder.INSTANCE;
    }

    private static class Holder {
        static final TurnContinuation INSTANCE = new TurnContinuation();
    }

    /** 内部延续发送者标识。对标 Python _GOAL_CONTINUATION_SENDER。 */
    private static final String GOAL_CONTINUATION_SENDER = "system:continuation";

    /** 内部延续出发时需从 inbound metadata 中清除的键集合。
     *  对标 Python _STRIPPED_INBOUND_META_KEYS。 */
    private static final Set<String> STRIPPED_INBOUND_META_KEYS = Set.of(
            "_stream_id", "_stream_delta", "_stream_end", "_resuming",
            SessionConstants.INTERNAL_CONTINUATION_PENDING_META);

    // ---- 谓词 ----

    /**
     * 消息是否为内部延续产生的入站消息。
     * 对标 Python {@code internal_continuation_inbound(metadata)}。
     */
    public static boolean isInternalContinuation(@Nullable Map<String, Object> meta) {
        return meta != null && Boolean.TRUE.equals(
                meta.get(SessionConstants.INTERNAL_CONTINUATION_META));
    }

    /**
     * 返回内部延续 turn 发起时的时间戳（秒级 epoch）。
     * 对标 Python {@code internal_continuation_run_started_at(metadata)}。
     */
    // 对标 Python turn_continuation.py:49-57
    @Nullable
    public static Double internalContinuationRunStartedAt(@Nullable Map<String, Object> meta) {
        if (meta == null) return null;
        Object v = meta.get(SessionConstants.INTERNAL_CONTINUATION_RUN_STARTED_AT_META);
        if (v instanceof Number n) return n.doubleValue();
        return null;
    }

    /**
     * 消息是否为内部延续产生的入站消息（_state_save 延迟计算使用）。
     * 对标 Python {@code internal_continuation_inbound(metadata)}。
     */
    public static boolean isInternalContinuationInbound(@Nullable Map<String, Object> meta) {
        return isInternalContinuation(meta);
    }

    /**
     * 消息元数据是否标记为内部延续待处理。
     * 对标 Python {@code internal_continuation_pending(metadata)}。
     */
    public static boolean isInternalContinuationPending(@Nullable Map<String, Object> meta) {
        return meta != null && Boolean.TRUE.equals(
                meta.get(SessionConstants.INTERNAL_CONTINUATION_PENDING_META));
    }

    /**
     * 内部延续产生的合成用户消息不应持久化。
     * 对标 Python {@code should_persist_user_message(metadata)}。
     */
    public static boolean shouldPersistUserMessage(@Nullable Map<String, Object> meta) {
        return !isInternalContinuation(meta);
    }

    /**
     * 当 turn 需要最终化（发出 max_iterations 最终响应）时返回 true。
     * 对标 Python {@code should_finalize_on_max_iterations(...)}。
     */
    public static boolean shouldFinalizeOnMaxIterations(
            boolean pendingQueueAvailable,
            @Nullable Map<String, Object> sessionMeta,
            @Nullable Map<String, Object> msgMeta) {
        return !(pendingQueueAvailable && goalContinuationAvailable(sessionMeta, msgMeta));
    }

    /**
     * 判断当前是否应流式输出预算耗尽响应。
     * 对标 Python {@code should_stream_budget_response(...)}。
     */
    public static boolean shouldStreamBudgetResponse(
            String stopReason, boolean pendingAvailable,
            @Nullable Map<String, Object> sessionMeta,
            @Nullable Map<String, Object> msgMeta) {
        return !"max_iterations".equals(stopReason)
                || shouldFinalizeOnMaxIterations(pendingAvailable, sessionMeta, msgMeta);
    }

    /**
     * 清理由内部延续累计的状态（如果持续目标不再活跃则清除轮次计数器）。
     * 对标 Python {@code clear_internal_continuation_state(metadata)}。
     */
    public static void clearInternalContinuationState(Map<String, Object> meta) {
        if (!GoalState.sustainedGoalActive(meta)) {
            meta.remove(SessionConstants.SUSTAINED_GOAL_CONTINUATION_ROUNDS_KEY);
        }
    }

    // ---- 内部谓词 ----

    /**
     * 计算 save_turn 时应跳过的消息数。
     * 对标 Python {@code _save_skip_for_turn(message_metadata, initial_message_count, history_count, user_persisted_early)}。
     *
     * @param messageMetadata      消息元数据
     * @param initialMessageCount  initial_messages 长度
     * @param historyCount         session history 长度
     * @param userPersistedEarly   用户消息是否已提前持久化
     * @return 应跳过的消息数
     */
    // 对标 Python turn_continuation.py:175-185 _save_skip_for_turn()
    static int saveSkipForTurn(@Nullable Map<String, Object> messageMetadata,
                               int initialMessageCount, int historyCount,
                               boolean userPersistedEarly) {
        if (isInternalContinuation(messageMetadata)) {
            return initialMessageCount;
        }
        return 1 + historyCount + (userPersistedEarly ? 1 : 0);
    }

    /**
     * 持续目标延续是否可用（目标活跃 且 轮次未达上限）。
     * 对标 Python {@code _goal_continuation_available(session_metadata, message_metadata)}。
     */
    static boolean goalContinuationAvailable(
            @Nullable Map<String, Object> sessionMeta,
            @Nullable Map<String, Object> msgMeta) {
        if (!GoalState.sustainedGoalTurn(sessionMeta, msgMeta)) return false;
        if (!GoalState.sustainedGoalActive(sessionMeta)) return false;
        int rounds = 0;
        if (sessionMeta != null && sessionMeta.get(
                SessionConstants.SUSTAINED_GOAL_CONTINUATION_ROUNDS_KEY) instanceof Number n) {
            rounds = n.intValue();
        }
        return rounds < SessionConstants.MAX_GOAL_CONTINUATION_ROUNDS;
    }

    /**
     * 整个延续链路是否可用。
     * 对标 Python 中 {@code _continuation_available} 的本地闭包。
     */
    private static boolean continuationAvailable(
            String stopReason, boolean pendingAvailable,
            @Nullable Map<String, Object> sessionMeta,
            @Nullable Map<String, Object> msgMeta) {
        return "max_iterations".equals(stopReason) && pendingAvailable
                && goalContinuationAvailable(sessionMeta, msgMeta);
    }

    // ---- 消息构造 ----

    /**
     * 构建内部延续消息的 metadata。
     * 对标 Python {@code _internal_continuation_metadata(message_metadata, *, run_started_at=None)}。
     *
     * @param msgMeta      原始消息元数据
     * @param runStartedAt 延续发起时的时间戳（秒级 epoch），可为 null
     * @return 内部延续消息的元数据
     */
    /**
     * 构建内部延续消息的 metadata。
     * 对标 Python {@code _internal_continuation_metadata(message_metadata, *, run_started_at=None)}。
     *
     * @param msgMeta      原始消息元数据
     * @param runStartedAt 延续发起时的 epoch millis，可为 null
     * @return 内部延续消息的元数据
     */
    // 对标 Python turn_continuation.py:213-225 _internal_continuation_metadata()
    private static Map<String, Object> buildContinuationMeta(
            @Nullable Map<String, Object> msgMeta,
            @Nullable Long runStartedAt) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (msgMeta != null) meta.putAll(msgMeta);
        meta.put(SessionConstants.INTERNAL_CONTINUATION_META, Boolean.TRUE);
        meta.put(SessionConstants.INTERNAL_CONTINUATION_KIND_META,
                SessionConstants.GOAL_CONTINUATION_KIND);
        // 对标 Python: 记录 run_started_at 时间戳（转为秒级 epoch）
        if (runStartedAt != null) {
            meta.put(SessionConstants.INTERNAL_CONTINUATION_RUN_STARTED_AT_META,
                    runStartedAt / 1000.0);
        }
        for (String k : STRIPPED_INBOUND_META_KEYS) meta.remove(k);
        return meta;
    }

    /**
     * 递增持续目标延续轮次计数器。
     * 对标 Python 中本地函数 {@code _increment_goal_continuation_round}。
     */
    private static void incrementGoalContinuationRound(Map<String, Object> meta) {
        int r = meta.get(SessionConstants.SUSTAINED_GOAL_CONTINUATION_ROUNDS_KEY) instanceof Number n
                ? n.intValue() : 0;
        meta.put(SessionConstants.SUSTAINED_GOAL_CONTINUATION_ROUNDS_KEY, r + 1);
    }

    /**
     * 构建目标延续的 prompt。
     * 对标 Python {@code _goal_continuation_prompt(metadata)}。
     */
    public static String goalContinuationPrompt(@Nullable Map<String, Object> meta) {
        List<String> lines = GoalState.goalStateRuntimeLines(meta);
        if (!lines.isEmpty()) {
            return "Continue the active sustained goal after the previous turn reached its tool-call budget.\n\n"
                    + String.join("\n", lines)
                    + "\n\nContinue from the saved context. Do not mention the continuation "
                    + "boundary to the user. Use tools as needed, and call complete_goal "
                    + "when the objective is truly finished.";
        }
        return "Continue the active sustained goal after the previous turn reached its tool-call budget. "
                + "Continue from the saved context. Do not mention the continuation boundary to the user. "
                + "Use tools as needed, and call complete_goal when the objective is truly finished.";
    }

    /**
     * 去除消息列表末端的合成终止 assistant 消息。
     * 仅当末条为 assistant、无 tool_calls 且 content 匹配 finalContent 时移除。
     * 对标 Python {@code _strip_terminal_assistant(messages, final_content)}。
     *
     * @param messages     完整消息列表
     * @param finalContent 最终内容（用于匹配合成终止消息）
     * @return 若符合条件则返回移除末条后的新列表，否则返回原列表
     */
    public static List<Map<String, Object>> stripTerminalAssistant(
            List<Map<String, Object>> messages, @Nullable String finalContent) {
        if (messages.isEmpty()) return messages;
        int last = messages.size() - 1;
        Map<String, Object> lastMsg = messages.get(last);
        if (!"assistant".equals(lastMsg.get("role"))) return messages;
        if (finalContent == null || !finalContent.equals(lastMsg.get("content"))) return messages;
        if (lastMsg.containsKey("tool_calls")) return messages;
        List<Map<String, Object>> result = new ArrayList<>(messages);
        result.remove(last);
        return result;
    }

    // ---- 核心编排 ----

    /**
     * 在 turn 完成时检查是否需要注入内部延续。
     *
     * <p>若条件满足，则：抑制当前响应、去除合成终止消息、构造内部延续入站消息
     * 并放入待处理队列。
     *
     * <p>对标 Python {@code async def maybe_continue_turn(ctx) -> bool}。
     *
     * @param ctx turn 延续上下文
     * @return 是否成功调度延续
     */
    public static boolean maybeContinueTurn(TurnContinuationContext ctx) {
        if (ctx.getSession() == null || ctx.getPendingQueue() == null) return false;
        if (!continuationAvailable(ctx.getStopReason(), true,
                ctx.getSession().getMetadata(), ctx.getMessageMetadata())) {
            return false;
        }

        // 对标 Python: 传递 visible_run_started_at 时间戳（epoch millis）
        Long runStartedAt = ctx.getVisibleRunStartedAt();
        Map<String, Object> newMeta = buildContinuationMeta(ctx.getMessageMetadata(), runStartedAt);
        String prompt = goalContinuationPrompt(ctx.getSession().getMetadata());
        List<Map<String, Object>> stripped = stripTerminalAssistant(
                ctx.getAllMessages(), ctx.getFinalContent());
        incrementGoalContinuationRound(ctx.getSession().getMetadata());

        log.info("Turn budget reached; scheduling internal continuation (session={})",
                ctx.getSessionKey());

        Map<String, Object> msgMeta = ctx.getMessageMetadata() != null
                ? ctx.getMessageMetadata() : new LinkedHashMap<>();
        msgMeta.put(SessionConstants.INTERNAL_CONTINUATION_PENDING_META, Boolean.TRUE);

        ctx.setFinalContent("");
        ctx.setAllMessages(stripped);
        ctx.setSuppressResponse(true);

        InboundMessage contMsg = new InboundMessage(
                ctx.getOriginalMsg().channel(),
                GOAL_CONTINUATION_SENDER,
                ctx.getOriginalMsg().chatId(),
                prompt,
                null, List.of(), newMeta,
                ctx.getOriginalMsg().sessionKeyOverride() != null
                        ? ctx.getOriginalMsg().sessionKeyOverride()
                        : ctx.getSessionKey());

        try {
            ctx.getPendingQueue().put(contMsg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }

    /**
     * 在持久化前准备保存边界——清理持续目标状态 + 计算 save_skip。
     * 对标 Python {@code prepare_save_boundary(ctx)}。
     */
    // 对标 Python turn_continuation.py:141-151 prepare_save_boundary()
    public static void prepareSaveBoundary(TurnContinuationContext ctx) {
        if (ctx.getSession() != null) {
            clearInternalContinuationState(ctx.getSession().getMetadata());
        }
        // 对标 Python: 计算应跳过的消息数（内部延续 vs 普通 turn）
        int skip = saveSkipForTurn(
                ctx.getMessageMetadata(),
                ctx.getInitialMessageCount(),
                ctx.getHistoryCount(),
                ctx.isUserPersistedEarly());
        ctx.setSaveSkip(skip);
    }

    // ---- 上下文接口 ----

    /**
     * maybeContinueTurn 需要的 turn 状态查询与修改接口。
     * 对标 Python maybe_continue_turn 的 ctx 参数结构。
     */
    public interface TurnContinuationContext {

        Session getSession();

        @Nullable
        BlockingQueue<InboundMessage> getPendingQueue();

        InboundMessage getOriginalMsg();

        @Nullable
        Map<String, Object> getMessageMetadata();

        String getStopReason();

        List<Map<String, Object>> getAllMessages();

        void setAllMessages(List<Map<String, Object>> messages);

        @Nullable
        String getFinalContent();

        void setFinalContent(String content);

        @Nullable
        String getSessionKey();

        void setSuppressResponse(boolean suppress);

        boolean isUserPersistedEarly();

        /** 对标 Python ctx.visible_run_started_at——延续 turn 发起时的时间戳（epoch millis）。 */
        @Nullable
        Long getVisibleRunStartedAt();

        /** 对标 Python len(ctx.initial_messages)——初始消息数量。 */
        int getInitialMessageCount();

        /** 对标 Python len(ctx.history)——session history 数量。 */
        int getHistoryCount();

        /** 对标 Python ctx.save_skip——应在 save_turn 中跳过的消息数。 */
        void setSaveSkip(int skip);
    }
}
