package com.nanobot.agent.runner;

import com.nanobot.agent.hook.AgentHook;
import com.nanobot.agent.tools.ToolRegistry;
import com.nanobot.providers.base.ThrowingConsumer;
import jakarta.annotation.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Agent run 的规格参数，一条 record 包含所有配置。
 * 对应 Python AgentRunSpec dataclass（runner.py 行 83-113）。
 */
public record AgentRunSpec(
        /** 初始消息列表（含 system prompt） */
        List<Map<String, Object>> initialMessages,
        /** 工具注册表 */
        ToolRegistry tools,
        /** 模型名称 */
        String model,
        /** 最大迭代次数 */
        int maxIterations,
        /** 工具结果最大字符数 */
        int maxToolResultChars,
        /** 温度参数 */
        @Nullable Double temperature,
        /** 最大输出 token 数 */
        @Nullable Integer maxTokens,
        /** 推理强度（如 "low"、"high"） */
        @Nullable String reasoningEffort,
        /** Agent 生命周期钩子 */
        @Nullable AgentHook hook,
        /** 错误消息模板 */
        @Nullable String errorMessage,
        /** 达到最大迭代次数消息模板 */
        @Nullable String maxIterationsMessage,
        /** 是否并发执行工具调用 */
        boolean concurrentTools,
        /** 工具执行失败是否终止 */
        boolean failOnToolError,
        /** 工作区路径 */
        @Nullable Path workspace,
        /** 会话 key */
        @Nullable String sessionKey,
        /** 上下文窗口 token 数 */
        @Nullable Integer contextWindowTokens,
        /** 上下文块数量限制 */
        @Nullable Integer contextBlockLimit,
        /** provider 重试模式 */
        String providerRetryMode,
        /** 进度回调 */
        @Nullable Consumer<String> progressCallback,
        /** 是否流式传输进度增量 */
        boolean streamProgressDeltas,
        /** 重试等待回调 */
        @Nullable ThrowingConsumer<String> retryWaitCallback,
        /** 检查点回调 */
        @Nullable Consumer<Map<String, Object>> checkpointCallback,
        /** 注入消息回调 */
        @Nullable Supplier<List<Map<String, Object>>> injectionCallback,
        /** LLM 超时时间（秒） */
        @Nullable Double llmTimeoutS,
        /** 目标活跃判断谓词 */
        @Nullable BooleanSupplier goalActivePredicate,
        /** 目标继续消息模板 */
        @Nullable String goalContinueMessage,
        /** 达到最大迭代次数时是否执行 finalize */
        boolean finalizeOnMaxIterations
) {
    public AgentRunSpec {
        if (providerRetryMode == null) providerRetryMode = "standard";
        if (errorMessage == null) errorMessage =
                "An error occurred. Please try again or contact support if the problem persists.";
        if (maxIterationsMessage == null) maxIterationsMessage =
                "Maximum iterations reached. Task may be incomplete — send another message to continue.";
        if (goalContinueMessage == null) goalContinueMessage =
                "The goal task was paused mid-turn to let you inspect or intervene. "
                + "Click Continue to resume or send a new message.";
    }
}
