package com.nanobot.agent.hook;

/**
 * Agent 生命周期钩子接口，所有方法为默认 no-op。
 * 对应 Python AgentHook 类（agent/hook.py）。
 *
 * <p>钩子点：run 级别（beforeRun/afterRun/onError/onFinally）、
 * iteration 级别（beforeIteration/afterIteration/beforeExecuteTools）、
 * 流式（onStream/onStreamEnd）、推理内容（emitReasoning/emitReasoningEnd）、
 * 内容后处理（finalizeContent）。</p>
 */
public interface AgentHook {

    /** 是否期望流式输出。
     *  对应 Python AgentHook.wants_streaming()。 */
    default boolean wantsStreaming() {
        return false;
    }

    /** run 开始前回调。
     *  对应 Python AgentHook.before_run()。 */
    default void beforeRun(AgentRunHookContext ctx) {}

    /** run 结束后回调。
     *  对应 Python AgentHook.after_run()。 */
    default void afterRun(AgentRunHookContext ctx) {}

    /** run 出错时回调。
     *  对应 Python AgentHook.on_error()。 */
    default void onError(AgentRunHookContext ctx) {}

    /** run finally 回调（无论成功/失败）。
     *  对应 Python AgentHook.on_finally()。 */
    default void onFinally(AgentRunHookContext ctx) {}

    /** 每次 iteration 开始前回调。
     *  对应 Python AgentHook.before_iteration()。 */
    default void beforeIteration(AgentHookContext ctx) {}

    /** 流式 delta 回调。
     *  对应 Python AgentHook.on_stream()。 */
    default void onStream(AgentHookContext ctx, String delta) {}

    /** 流式结束回调。
     *  对应 Python AgentHook.on_stream_end()。 */
    default void onStreamEnd(AgentHookContext ctx, boolean resuming) {}

    /** 工具执行前回调。
     *  对应 Python AgentHook.before_execute_tools()。 */
    default void beforeExecuteTools(AgentHookContext ctx) {}

    /** 推理内容回调。
     *  对应 Python AgentHook.emit_reasoning()。 */
    default void emitReasoning(String reasoningContent) {}

    /** 推理结束回调。
     *  对应 Python AgentHook.emit_reasoning_end()。 */
    default void emitReasoningEnd() {}

    /** iteration 结束后回调。
     *  对应 Python AgentHook.after_iteration()。 */
    default void afterIteration(AgentHookContext ctx) {}

    /** 最终内容后处理（如清洗、格式化）。
     *  对应 Python AgentHook.finalize_content()。 */
    default String finalizeContent(AgentHookContext ctx, String content) {
        return content;
    }
}
