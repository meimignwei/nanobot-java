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
 * Specification for an agent run. Mirrors Python AgentRunSpec dataclass
 * (runner.py lines 83-113).
 */
public record AgentRunSpec(
        List<Map<String, Object>> initialMessages,
        ToolRegistry tools,
        String model,
        int maxIterations,
        int maxToolResultChars,
        @Nullable Double temperature,
        @Nullable Integer maxTokens,
        @Nullable String reasoningEffort,
        @Nullable AgentHook hook,
        @Nullable String errorMessage,
        @Nullable String maxIterationsMessage,
        boolean concurrentTools,
        boolean failOnToolError,
        @Nullable Path workspace,
        @Nullable String sessionKey,
        @Nullable Integer contextWindowTokens,
        @Nullable Integer contextBlockLimit,
        String providerRetryMode,
        @Nullable Consumer<String> progressCallback,
        boolean streamProgressDeltas,
        @Nullable ThrowingConsumer<String> retryWaitCallback,
        @Nullable Consumer<Map<String, Object>> checkpointCallback,
        @Nullable Supplier<List<Map<String, Object>>> injectionCallback,
        @Nullable Double llmTimeoutS,
        @Nullable BooleanSupplier goalActivePredicate,
        @Nullable String goalContinueMessage,
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
