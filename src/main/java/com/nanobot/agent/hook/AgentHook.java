package com.nanobot.agent.hook;

import java.util.concurrent.CompletableFuture;

/**
 * Agent 运行生命周期钩子接口，允许在 runner 的各个阶段注入自定义逻辑（日志、指标收集、UI 更新等）。
 *
 * <p>所有 async 回调方法返回 {@link CompletableFuture}{@code <Void>}，由 runner 按序 await。
 * 同步方法（{@code wantsStreaming}、{@code finalizeContent}）保持同步返回类型，
 * 与 Python 源码的 async/sync 边界一一对应。
 *
 * <p>对标 Python {@code nanobot/agent/hook.py:47-96 class AgentHook}。
 */
public interface AgentHook {

    /**
     * 是否将 hook 内部异常向外传播（而非静默吞掉）。默认 {@code false}，异常仅记日志不终止 runner。
     * 对标 Python {@code hook.py:50-51 __init__(self, reraise=False)}。
     */
    default boolean reraise() {
        return false;
    }

    /**
     * 是否需要流式输出（delta 回调）。返回 {@code true} 时 runner 会为每次 LLM 增量文本调用
     * {@link #onStream}。
     * 对标 Python {@code hook.py:53-54 wants_streaming()}。
     */
    default boolean wantsStreaming() {
        return false;
    }

    /**
     * runner 即将启动，在所有迭代之前调用一次。
     *
     * @param context run 级别的初始上下文快照
     * 对标 Python {@code hook.py:56-57 before_run()}
     */
    default CompletableFuture<Void> beforeRun(AgentRunHookContext context) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * runner 完成（正常或异常），在所有迭代结束后调用一次。用于收集最终统计信息。
     *
     * @param context run 级别的最终上下文快照（含 tools_used、usage 等汇总数据）
     * 对标 Python {@code hook.py:59-60 after_run()}
     */
    default CompletableFuture<Void> afterRun(AgentRunHookContext context) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * runner 发生未处理异常时调用。
     *
     * @param context 异常发生时的上下文快照（含 error、exception 字段）
     * 对标 Python {@code hook.py:62-63 on_error()}
     */
    default CompletableFuture<Void> onError(AgentRunHookContext context) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * runner 结束时必然调用（类似 finally 块），无论成功、失败还是取消。
     *
     * @param context run 级别的最终上下文快照
     * 对标 Python {@code hook.py:65-66 on_finally()}
     */
    default CompletableFuture<Void> onFinally(AgentRunHookContext context) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 每次 LLM 迭代开始前调用（一轮迭代 = 一次 LLM 调用 + 可能的 tool 执行）。
     *
     * @param context 当前迭代的上下文（含 iteration 序号、messages 等）
     * 对标 Python {@code hook.py:68-69 before_iteration()}
     */
    default CompletableFuture<Void> beforeIteration(AgentHookContext context) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * LLM 流式输出时，每收到一个增量文本片段就调用一次。
     *
     * @param context 当前迭代上下文
     * @param delta   LLM 返回的增量文本（可能为空字符串）
     * 对标 Python {@code hook.py:71-72 on_stream()}
     */
    default CompletableFuture<Void> onStream(AgentHookContext context, String delta) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * LLM 流式文本输出结束时调用。
     *
     * @param context  当前迭代上下文
     * @param resuming 是否为续写恢复（而非首轮生成）
     * 对标 Python {@code hook.py:74-75 on_stream_end()}
     */
    default CompletableFuture<Void> onStreamEnd(AgentHookContext context, boolean resuming) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * runner 即将执行 tool call 之前调用。可用于记录或拦截工具调用。
     *
     * @param context 当前迭代上下文（含 tool_calls 列表）
     * 对标 Python {@code hook.py:77-78 before_execute_tools()}
     */
    default CompletableFuture<Void> beforeExecuteTools(AgentHookContext context) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 接收到模型推理内容时调用（如 Claude 的 extended thinking、DeepSeek-R1 的 reasoning）。
     *
     * @param reasoningContent 推理文本内容，可为 {@code null}
     * 对标 Python {@code hook.py:80-81 emit_reasoning()}
     */
    default CompletableFuture<Void> emitReasoning(String reasoningContent) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 标记推理内容流的结束。缓冲型 hook 应在此刷新并输出缓存的推理文本。
     * 对标 Python {@code hook.py:83-89 emit_reasoning_end()}
     */
    default CompletableFuture<Void> emitReasoningEnd() {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 每次 LLM 迭代结束后调用。runner 会原地修改 {@code context.messages}，
     * 因此 hook 可在此时刷新消息快照。
     *
     * @param context 当前迭代完成后的上下文（含 response、tool_calls、tool_results 等）
     * 对标 Python {@code hook.py:91-92 after_iteration()}
     */
    default CompletableFuture<Void> afterIteration(AgentHookContext context) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 对 runner 产生的最终文本进行后处理（同步管道模式，无错误隔离）。
     * 多个 hook 链式调用：每个 hook 的输出作为下一个 hook 的输入。
     *
     * @param context 当前迭代上下文
     * @param content 待处理的最终文本，可为 {@code null}
     * @return 处理后的文本
     * 对标 Python {@code hook.py:94-95 finalize_content()}
     */
    default String finalizeContent(AgentHookContext context, String content) {
        return content;
    }
}
