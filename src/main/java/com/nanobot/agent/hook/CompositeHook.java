package com.nanobot.agent.hook;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 将同一个生命周期回调广播到一组有序 hook 的 Fan-out 代理。
 *
 * <p>错误隔离：async 方法通过 exceptionally 捕获并记录每个子 hook 的异常，
 * 防止单个故障 hook 导致 agent loop 崩溃。但 finalizeContent 是同步管道模式（无隔离），
 * 前一个 hook 的输出直接作为下一个 hook 的输入。
 *
 * <p>对标 Python {@code nanobot/agent/hook.py:98-162 class CompositeHook}。
 */
public class CompositeHook implements AgentHook {

    /** 有序子 hook 列表，对标 Python hook.py:106 __slots__ = ("_hooks",) */
    private final List<AgentHook> hooks;

    /** 任意子 hook 设置了 reraise 则为 true，对标 Python hook.py:117 */
    private final boolean reraise;

    /**
     * @param hooks 子 hook 列表，按此顺序依次调用
     */
    public CompositeHook(List<AgentHook> hooks) {
        this.hooks = List.copyOf(hooks);
        this.reraise = hooks.stream().anyMatch(AgentHook::reraise);
    }

    @Override
    public boolean reraise() {
        return reraise;
    }

    /**
     * 只要任一子 hook 需要流式输出，则返回 true。
     *
     * @return 是否需要流式输出
     */
    @Override
    public boolean wantsStreaming() {
        return hooks.stream().anyMatch(AgentHook::wantsStreaming);
    }

    /**
     * 遍历所有子 hook 并调用指定方法。若 hook 未设置 reraise，异常被捕获并记日志后继续；
     * 若设置了 reraise，异常直接向上传播。
     *
     * @param methodName 要调用的 hook 方法名
     * @param args       方法参数
     * @return 所有子 hook 调用完成后完成的 CompletableFuture
     */
    // 对标 Python hook.py:115-124 _for_each_hook_safe()
    private CompletableFuture<Void> forEachHookSafe(String methodName, Object... args) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (AgentHook h : hooks) {
            chain = chain.thenCompose(v -> {
                try {
                    CompletableFuture<Void> result = invokeHook(h, methodName, args);
                    if (h.reraise()) {
                        return result;
                    }
                    return result.exceptionally(ex -> {
                        System.err.println("AgentHook." + methodName + " error in "
                                + h.getClass().getSimpleName() + ": " + ex.getMessage());
                        return null;
                    });
                } catch (Exception ex) {
                    if (h.reraise()) {
                        return CompletableFuture.failedFuture(ex);
                    }
                    System.err.println("AgentHook." + methodName + " error in "
                            + h.getClass().getSimpleName() + ": " + ex.getMessage());
                    return CompletableFuture.completedFuture(null);
                }
            });
        }
        return chain;
    }

    /** 通过反射式 switch 将方法名路由到具体的 hook 方法调用 */
    private CompletableFuture<Void> invokeHook(AgentHook h, String methodName, Object[] args) {
        return switch (methodName) {
            case "beforeRun" -> h.beforeRun((AgentRunHookContext) args[0]);
            case "afterRun" -> h.afterRun((AgentRunHookContext) args[0]);
            case "onError" -> h.onError((AgentRunHookContext) args[0]);
            case "onFinally" -> h.onFinally((AgentRunHookContext) args[0]);
            case "beforeIteration" -> h.beforeIteration((AgentHookContext) args[0]);
            case "onStream" -> h.onStream((AgentHookContext) args[0], (String) args[1]);
            case "onStreamEnd" -> h.onStreamEnd((AgentHookContext) args[0], (Boolean) args[1]);
            case "beforeExecuteTools" -> h.beforeExecuteTools((AgentHookContext) args[0]);
            case "emitReasoning" -> h.emitReasoning((String) args[0]);
            case "emitReasoningEnd" -> h.emitReasoningEnd();
            case "afterIteration" -> h.afterIteration((AgentHookContext) args[0]);
            default -> throw new IllegalArgumentException("Unknown hook method: " + methodName);
        };
    }

    @Override
    public CompletableFuture<Void> beforeRun(AgentRunHookContext context) {
        return forEachHookSafe("beforeRun", context);
    }

    @Override
    public CompletableFuture<Void> afterRun(AgentRunHookContext context) {
        return forEachHookSafe("afterRun", context);
    }

    @Override
    public CompletableFuture<Void> onError(AgentRunHookContext context) {
        return forEachHookSafe("onError", context);
    }

    @Override
    public CompletableFuture<Void> onFinally(AgentRunHookContext context) {
        return forEachHookSafe("onFinally", context);
    }

    @Override
    public CompletableFuture<Void> beforeIteration(AgentHookContext context) {
        return forEachHookSafe("beforeIteration", context);
    }

    @Override
    public CompletableFuture<Void> onStream(AgentHookContext context, String delta) {
        return forEachHookSafe("onStream", context, delta);
    }

    @Override
    public CompletableFuture<Void> onStreamEnd(AgentHookContext context, boolean resuming) {
        return forEachHookSafe("onStreamEnd", context, resuming);
    }

    @Override
    public CompletableFuture<Void> beforeExecuteTools(AgentHookContext context) {
        return forEachHookSafe("beforeExecuteTools", context);
    }

    @Override
    public CompletableFuture<Void> emitReasoning(String reasoningContent) {
        return forEachHookSafe("emitReasoning", reasoningContent);
    }

    @Override
    public CompletableFuture<Void> emitReasoningEnd() {
        return forEachHookSafe("emitReasoningEnd");
    }

    @Override
    public CompletableFuture<Void> afterIteration(AgentHookContext context) {
        return forEachHookSafe("afterIteration", context);
    }

    /**
     * 管道模式遍历所有子 hook 的 finalizeContent，每个 hook 的输出作为下一个的输入。
     *
     * @param context 当前迭代上下文
     * @param content 待处理的文本
     * @return 经所有子 hook 依次处理后的文本
     */
    @Override
    public String finalizeContent(AgentHookContext context, String content) {
        String result = content;
        for (AgentHook h : hooks) {
            result = h.finalizeContent(context, result);
        }
        return result;
    }
}
