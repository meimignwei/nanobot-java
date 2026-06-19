package com.nanobot.agent.hook;

/**
 * Minimal lifecycle surface for shared runner customization.
 * Mirrors Python AgentHook class — all methods are default no-ops.
 */
public interface AgentHook {

    default boolean wantsStreaming() {
        return false;
    }

    default void beforeRun(AgentRunHookContext ctx) {}

    default void afterRun(AgentRunHookContext ctx) {}

    default void onError(AgentRunHookContext ctx) {}

    default void onFinally(AgentRunHookContext ctx) {}

    default void beforeIteration(AgentHookContext ctx) {}

    default void onStream(AgentHookContext ctx, String delta) {}

    default void onStreamEnd(AgentHookContext ctx, boolean resuming) {}

    default void beforeExecuteTools(AgentHookContext ctx) {}

    default void emitReasoning(String reasoningContent) {}

    default void emitReasoningEnd() {}

    default void afterIteration(AgentHookContext ctx) {}

    default String finalizeContent(AgentHookContext ctx, String content) {
        return content;
    }
}
