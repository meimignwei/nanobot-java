package com.nanobot.providers.base;

import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LLM provider 的统一响应结构。
 * 对应 Python LLMResponse dataclass（providers/base.py）。
 *
 * <p>统一了 OpenAI、Anthropic、Ollama 等各类 provider 的返回格式，
 * 包含内容、工具调用、token 用量、错误信息等字段。</p>
 */
public record LLMResponse(
        /** 回复文本内容 */
        @Nullable String content,
        /** 工具调用请求列表 */
        List<ToolCallRequest> toolCalls,
        /** 完成原因：stop、tool_calls、error 等 */
        String finishReason,
        /** token 用量（prompt_tokens, completion_tokens, total_tokens） */
        Map<String, Integer> usage,
        /** 重试等待时间（秒） */
        @Nullable Double retryAfter,
        /** 推理/thinking 内容 */
        @Nullable String reasoningContent,
        /** thinking blocks 结构化列表 */
        @Nullable List<Map<String, Object>> thinkingBlocks,
        /** 错误 HTTP 状态码 */
        @Nullable Integer errorStatusCode,
        /** 错误种类 */
        @Nullable String errorKind,
        /** 错误类型 */
        @Nullable String errorType,
        /** 错误码 */
        @Nullable String errorCode,
        /** 错误建议重试等待（秒） */
        @Nullable Double errorRetryAfterS,
        /** 是否应重试 */
        @Nullable Boolean errorShouldRetry
) {

    /** 需要执行工具调用的完成原因 */
    private static final Set<String> TOOL_EXECUTE_REASONS = Set.of("tool_calls", "function_call", "stop");

    public LLMResponse {
        if (toolCalls == null) toolCalls = List.of();
        if (finishReason == null) finishReason = "stop";
        if (usage == null) usage = Map.of();
    }

    /** 是否有工具调用。对应 Python has_tool_calls property。 */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /** 是否应执行工具调用。对应 Python should_execute_tools property。 */
    public boolean shouldExecuteTools() {
        return hasToolCalls() && TOOL_EXECUTE_REASONS.contains(finishReason);
    }

    // ---- 工厂方法 ----

    /** 构建错误响应 */
    public static LLMResponse error(String message, @Nullable String errorKind) {
        return new LLMResponse(message, List.of(), "error", Map.of(),
                null, null, null, null, errorKind, null, null, null, null);
    }

    /** 构建错误响应（无 errorKind） */
    public static LLMResponse error(String message) {
        return error(message, null);
    }

    /** Builder 模式构建器 */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String content;
        private List<ToolCallRequest> toolCalls = List.of();
        private String finishReason = "stop";
        private Map<String, Integer> usage = Map.of();
        private Double retryAfter;
        private String reasoningContent;
        private List<Map<String, Object>> thinkingBlocks;
        private Integer errorStatusCode;
        private String errorKind;
        private String errorType;
        private String errorCode;
        private Double errorRetryAfterS;
        private Boolean errorShouldRetry;

        public Builder content(String v) { this.content = v; return this; }
        public Builder toolCalls(List<ToolCallRequest> v) { this.toolCalls = v; return this; }
        public Builder finishReason(String v) { this.finishReason = v; return this; }
        public Builder usage(Map<String, Integer> v) { this.usage = v; return this; }
        public Builder retryAfter(Double v) { this.retryAfter = v; return this; }
        public Builder reasoningContent(String v) { this.reasoningContent = v; return this; }
        public Builder thinkingBlocks(List<Map<String, Object>> v) { this.thinkingBlocks = v; return this; }
        public Builder errorStatusCode(Integer v) { this.errorStatusCode = v; return this; }
        public Builder errorKind(String v) { this.errorKind = v; return this; }
        public Builder errorType(String v) { this.errorType = v; return this; }
        public Builder errorCode(String v) { this.errorCode = v; return this; }
        public Builder errorRetryAfterS(Double v) { this.errorRetryAfterS = v; return this; }
        public Builder errorShouldRetry(Boolean v) { this.errorShouldRetry = v; return this; }

        public LLMResponse build() {
            return new LLMResponse(content, toolCalls, finishReason, usage,
                    retryAfter, reasoningContent, thinkingBlocks,
                    errorStatusCode, errorKind, errorType, errorCode,
                    errorRetryAfterS, errorShouldRetry);
        }
    }
}
