package com.nanobot.session;

import java.util.List;
import java.util.Set;

/**
 * 会话模块全局常量。
 *
 * <p>对标 Python {@code nanobot/session/manager.py} 模块级常量和
 * {@code nanobot/session/turn_continuation.py} 元数据键常量。
 */
public final class SessionConstants {

    private SessionConstants() {}

    /** 文件存储最大消息数。对标 Python FILE_MAX_MESSAGES。 */
    public static final int FILE_MAX_MESSAGES = 2000;

    /** 会话列表预览最大字符数。对标 Python _SESSION_PREVIEW_MAX_CHARS。 */
    public static final int SESSION_PREVIEW_MAX_CHARS = 120;

    /** 供 LLM 透传的消息键列表。对标 Python _LLM_MESSAGE_KEYS。 */
    public static final List<String> LLM_MESSAGE_KEYS = List.of(
            "tool_calls", "tool_call_id", "name", "reasoning_content", "thinking_blocks");

    /** fork 时清除的易失性 metadata key 集。对标 Python _FORK_VOLATILE_METADATA_KEYS。 */
    public static final Set<String> FORK_VOLATILE_METADATA_KEYS = Set.of(
            "goal_state", "pending_user_turn", "runtime_checkpoint",
            "thread_goal", "title", "title_user_edited");

    /** 内部延续标识。对标 Python INTERNAL_CONTINUATION_META。 */
    public static final String INTERNAL_CONTINUATION_META = "_internal_continuation";

    /** 内部延续种类标识。对标 Python INTERNAL_CONTINUATION_KIND_META。 */
    public static final String INTERNAL_CONTINUATION_KIND_META = "_internal_continuation_kind";

    /** 内部延续待处理标识。对标 Python INTERNAL_CONTINUATION_PENDING_META。 */
    public static final String INTERNAL_CONTINUATION_PENDING_META = "_internal_continuation_pending";

    /** 持续目标延续轮次计数键。对标 Python _sustained_goal_continuation_rounds。 */
    public static final String SUSTAINED_GOAL_CONTINUATION_ROUNDS_KEY = "_sustained_goal_continuation_rounds";

    /** 最大持续目标延续轮次。对标 Python _MAX_GOAL_CONTINUATION_ROUNDS。 */
    public static final int MAX_GOAL_CONTINUATION_ROUNDS = 12;

    /** 目标延续类目。对标 Python _GOAL_CONTINUATION_KIND。 */
    public static final String GOAL_CONTINUATION_KIND = "sustained_goal";

    /** 内部延续 run_started_at 时间戳键。对标 Python INTERNAL_CONTINUATION_RUN_STARTED_AT_META。 */
    public static final String INTERNAL_CONTINUATION_RUN_STARTED_AT_META =
            "_internal_continuation_run_started_at";
}
