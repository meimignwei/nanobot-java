package com.nanobot.providers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM provider 响应，包含文本内容、工具调用、用量及结构化错误元数据。
 *
 * <p>对标 Python {@code nanobot/providers/base.py LLMResponse @dataclass}（16 个字段）。
 */
public record LLMResponse(
        String content,
        List<ToolCallRequest> toolCalls,
        String finishReason,
        Map<String, Integer> usage,
        Double retryAfter,
        String reasoningContent,
        List<Map<String, Object>> thinkingBlocks,
        // 结构化错误元数据（finishReason == "error" 时填充）
        Integer errorStatusCode,
        String errorKind,
        String errorType,
        String errorCode,
        Double errorRetryAfterS,
        Boolean errorShouldRetry) {

    /** Compact constructor 提供默认值，对标 Python dataclass 的 default_factory */
    public LLMResponse {
        if (toolCalls == null) toolCalls = new ArrayList<>();
        if (finishReason == null) finishReason = "stop";
        if (usage == null) usage = new HashMap<>();
    }

    /** 成功文本响应便捷构造器 */
    public LLMResponse(String content, String finishReason) {
        this(content, new ArrayList<>(), finishReason, new HashMap<>(),
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * 检查是否有待执行的工具调用。
     *
     * @return true 如果 toolCalls 非空
     */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /**
     * 判断是否应执行工具：有 toolCalls 且 finishReason 为允许工具执行的终止原因。
     * 排除网关注入的 refusal/content_filter/error 下的调用。
     *
     * @return true 应执行工具
     */
    public boolean shouldExecuteTools() {
        if (!hasToolCalls()) return false;
        return "tool_calls".equals(finishReason)
                || "function_call".equals(finishReason)
                || "stop".equals(finishReason);
    }

    /**
     * 是否为错误响应。
     *
     * @return true 如果 finishReason 为 "error"
     */
    public boolean isError() {
        return "error".equals(finishReason);
    }
}
