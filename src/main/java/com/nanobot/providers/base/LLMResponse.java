package com.nanobot.providers.base;

import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Response from an LLM provider. Mirrors Python LLMResponse dataclass exactly.
 */
public record LLMResponse(
        @Nullable String content,
        List<ToolCallRequest> toolCalls,
        String finishReason,
        Map<String, Integer> usage,
        @Nullable Double retryAfter,
        @Nullable String reasoningContent,
        @Nullable List<Map<String, Object>> thinkingBlocks,
        // Structured error metadata used by retry policy when finish_reason == "error"
        @Nullable Integer errorStatusCode,
        @Nullable String errorKind,
        @Nullable String errorType,
        @Nullable String errorCode,
        @Nullable Double errorRetryAfterS,
        @Nullable Boolean errorShouldRetry
) {

    private static final Set<String> TOOL_EXECUTE_REASONS = Set.of("tool_calls", "function_call", "stop");

    public LLMResponse {
        if (toolCalls == null) toolCalls = List.of();
        if (finishReason == null) finishReason = "stop";
        if (usage == null) usage = Map.of();
    }

    /** Mirrors Python has_tool_calls property. */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /** Mirrors Python should_execute_tools property. */
    public boolean shouldExecuteTools() {
        return hasToolCalls() && TOOL_EXECUTE_REASONS.contains(finishReason);
    }

    // ---- factory methods ----

    public static LLMResponse error(String message, @Nullable String errorKind) {
        return new LLMResponse(message, List.of(), "error", Map.of(),
                null, null, null, null, errorKind, null, null, null, null);
    }

    public static LLMResponse error(String message) {
        return error(message, null);
    }

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
