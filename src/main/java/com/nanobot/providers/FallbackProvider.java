package com.nanobot.providers;

import com.nanobot.config.GenerationSettings;
import com.nanobot.config.ModelPresetProperties;
import com.nanobot.providers.base.LLMProvider;
import com.nanobot.providers.base.LLMResponse;
import com.nanobot.providers.base.ThrowingConsumer;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;

/**
 * Provider wrapper that transparently fails over to fallback models on error.
 * Mirrors Python FallbackProvider (fallback_provider.py) exactly.
 */
public class FallbackProvider extends LLMProvider {

    private final Logger log = LoggerFactory.getLogger(FallbackProvider.class);

    private static final int PRIMARY_FAILURE_THRESHOLD = 3;
    private static final int PRIMARY_COOLDOWN_S = 60;

    static final Set<String> FALLBACK_ERROR_KINDS = Set.of(
            "timeout", "connection", "server_error", "rate_limit", "overloaded"
    );
    static final Set<String> NON_FALLBACK_ERROR_KINDS = Set.of(
            "authentication", "auth", "permission", "content_filter", "refusal",
            "context_length", "invalid_request"
    );
    static final Set<Integer> NON_FALLBACK_STATUSES = Set.of(400, 401, 403, 404, 422);
    static final Set<Integer> FALLBACK_STATUSES = Set.of(408, 409, 429);
    static final List<String> FALLBACK_ERROR_TOKENS = List.of(
            "rate_limit", "rate limit", "too_many_requests", "too many requests",
            "overloaded", "server_error", "server error", "temporarily unavailable",
            "timeout", "timed out", "connection", "insufficient_quota",
            "insufficient quota", "quota_exceeded", "quota exceeded",
            "quota_exhausted", "quota exhausted", "billing_hard_limit",
            "insufficient_balance", "balance", "out of credits"
    );

    public boolean supportsStreamRecoverCallback = true;

    private final LLMProvider primary;
    private final List<ModelPresetProperties> fallbackPresets;
    private final Function<ModelPresetProperties, LLMProvider> providerFactory;
    private boolean hasFallbacks;
    private int primaryFailures;
    private Long primaryTrippedAt; // epoch millis

    public FallbackProvider(
            LLMProvider primary,
            List<ModelPresetProperties> fallbackPresets,
            Function<ModelPresetProperties, LLMProvider> providerFactory
    ) {
        super(primary.apiKey, primary.apiBase);
        this.primary = primary;
        this.fallbackPresets = new ArrayList<>(fallbackPresets);
        this.providerFactory = providerFactory;
        this.hasFallbacks = !fallbackPresets.isEmpty();
        this.generation = primary.generation;
    }

    @Override
    public String getDefaultModel() {
        return primary.getDefaultModel();
    }

    @Override
    public LLMResponse chat(
            List<Map<String, Object>> messages,
            @Nullable List<Map<String, Object>> tools,
            @Nullable String model,
            int maxTokens,
            double temperature,
            @Nullable String reasoningEffort,
            @Nullable Object toolChoice
    ) throws Exception {
        if (!hasFallbacks) {
            return primary.chat(messages, tools, model, maxTokens, temperature,
                    reasoningEffort, toolChoice);
        }
        return tryWithFallback(messages, tools, model, maxTokens, temperature,
                reasoningEffort, toolChoice, null, null, null, null, null);
    }

    @Override
    public LLMResponse chatStream(
            List<Map<String, Object>> messages,
            @Nullable List<Map<String, Object>> tools,
            @Nullable String model,
            int maxTokens,
            double temperature,
            @Nullable String reasoningEffort,
            @Nullable Object toolChoice,
            @Nullable ThrowingConsumer<String> onContentDelta,
            @Nullable ThrowingConsumer<String> onThinkingDelta,
            @Nullable ThrowingConsumer<Map<String, Object>> onToolCallDelta
    ) throws Exception {
        if (!hasFallbacks) {
            return primary.chatStream(messages, tools, model, maxTokens, temperature,
                    reasoningEffort, toolChoice, onContentDelta, onThinkingDelta, onToolCallDelta);
        }

        boolean[] hasStreamed = {false};
        ThrowingConsumer<String> trackingDelta = text -> {
            if (text != null && !text.isEmpty()) hasStreamed[0] = true;
            if (onContentDelta != null) onContentDelta.accept(text);
        };

        return tryWithFallback(messages, tools, model, maxTokens, temperature,
                reasoningEffort, toolChoice, trackingDelta, onThinkingDelta, onToolCallDelta,
                hasStreamed, null);
    }

    private LLMResponse tryWithFallback(
            List<Map<String, Object>> messages,
            @Nullable List<Map<String, Object>> tools,
            @Nullable String model,
            int maxTokens,
            double temperature,
            @Nullable String reasoningEffort,
            @Nullable Object toolChoice,
            @Nullable ThrowingConsumer<String> onContentDelta,
            @Nullable ThrowingConsumer<String> onThinkingDelta,
            @Nullable ThrowingConsumer<Map<String, Object>> onToolCallDelta,
            @Nullable boolean[] hasStreamed,
            @Nullable Runnable onStreamRecover
    ) throws Exception {
        String primaryModel = model != null ? model : primary.getDefaultModel();
        boolean isStream = hasStreamed != null;

        if (primaryAvailable()) {
            LLMResponse response;
            if (isStream) {
                response = primary.chatStream(messages, tools, model, maxTokens, temperature,
                        reasoningEffort, toolChoice, onContentDelta, onThinkingDelta, onToolCallDelta);
            } else {
                response = primary.chat(messages, tools, model, maxTokens, temperature,
                        reasoningEffort, toolChoice);
            }

            if (!"error".equals(response.finishReason())) {
                primaryFailures = 0;
                primaryTrippedAt = null;
                return response;
            }

            if (isStream && hasStreamed[0]) {
                boolean isTimeout = "timeout".equalsIgnoreCase(
                        response.errorKind() != null ? response.errorKind() : "");
                if (isTimeout) {
                    log.warn("Primary model '{}' stream stalled after content was emitted; attempting failover anyway",
                            primaryModel);
                    hasStreamed[0] = false;
                    if (onStreamRecover != null) onStreamRecover.run();
                    else onContentDelta = null;
                } else {
                    log.warn("Primary model error but content already streamed; skipping failover");
                    return response;
                }
            }

            if (!shouldFallback(response)) {
                log.warn("Primary model '{}' returned non-fallbackable error: {}",
                        primaryModel, limit(response.content()));
                return response;
            }

            primaryFailures++;
            if (primaryFailures >= PRIMARY_FAILURE_THRESHOLD) {
                primaryTrippedAt = System.currentTimeMillis();
                log.warn("Primary model '{}' circuit open after {} consecutive failures",
                        primaryModel, primaryFailures);
            }
        } else {
            log.debug("Primary model '{}' circuit open; skipping", primaryModel);
        }

        LLMResponse lastResponse = null;
        boolean primarySkipped = !primaryAvailable();
        for (int i = 0; i < fallbackPresets.size(); i++) {
            ModelPresetProperties fallback = fallbackPresets.get(i);
            String fallbackModel = fallback.model();

            if (isStream && hasStreamed[0]) {
                boolean isTimeout = lastResponse != null
                        && "timeout".equalsIgnoreCase(
                                lastResponse.errorKind() != null ? lastResponse.errorKind() : "");
                if (isTimeout && onStreamRecover != null) {
                    log.warn("Fallback model stream stalled after content; trying next fallback");
                    hasStreamed[0] = false;
                    onStreamRecover.run();
                } else {
                    break;
                }
            }

            logFallbackAttempt(i, primarySkipped, primaryModel, fallbackModel);

            LLMProvider fallbackProvider;
            try {
                fallbackProvider = providerFactory.apply(fallback);
            } catch (Exception e) {
                log.warn("Failed to create provider for fallback '{}': {}", fallbackModel, e.getMessage());
                continue;
            }

            LLMResponse fallbackResponse;
            if (isStream) {
                fallbackResponse = fallbackProvider.chatStream(messages, tools, fallbackModel,
                        fallback.maxTokens(), fallback.temperature(),
                        fallback.reasoningEffort(), toolChoice,
                        onContentDelta, onThinkingDelta, onToolCallDelta);
            } else {
                fallbackResponse = fallbackProvider.chat(messages, tools, fallbackModel,
                        fallback.maxTokens(), fallback.temperature(),
                        fallback.reasoningEffort(), toolChoice);
            }

            if (!"error".equals(fallbackResponse.finishReason())) {
                log.info("Fallback '{}' succeeded after primary '{}' failed", fallbackModel, primaryModel);
                return fallbackResponse;
            }

            lastResponse = fallbackResponse;
            log.warn("Fallback '{}' also failed: {}", fallbackModel, limit(fallbackResponse.content()));
        }

        log.warn("All {} fallback model(s) failed", fallbackPresets.size());
        if (lastResponse != null) return lastResponse;
        return LLMResponse.error("Primary model '" + primaryModel
                + "' circuit open and no fallbacks available");
    }

    private void logFallbackAttempt(int idx, boolean primarySkipped, String primaryModel, String fallbackModel) {
        if (idx == 0 && primarySkipped) {
            log.info("Primary model '{}' circuit open, trying fallback '{}'", primaryModel, fallbackModel);
        } else if (idx == 0) {
            log.info("Primary model '{}' failed, trying fallback '{}'", primaryModel, fallbackModel);
        } else {
            log.info("Fallback '{}' also failed, trying next fallback '{}'",
                    fallbackPresets.get(idx - 1).model(), fallbackModel);
        }
    }

    private boolean primaryAvailable() {
        if (primaryTrippedAt == null) return true;
        return (System.currentTimeMillis() - primaryTrippedAt) / 1000.0 >= PRIMARY_COOLDOWN_S;
    }

    public static boolean shouldFallback(LLMResponse response) {
        if (response.errorShouldRetry() != null && !response.errorShouldRetry()) return false;

        Integer status = response.errorStatusCode();
        String kind = response.errorKind() != null ? response.errorKind().toLowerCase() : "";
        String errorType = response.errorType() != null ? response.errorType().toLowerCase() : "";
        String code = response.errorCode() != null ? response.errorCode().toLowerCase() : "";
        String text = response.content() != null ? response.content().toLowerCase() : "";

        if (status != null && NON_FALLBACK_STATUSES.contains(status)) return false;
        if (NON_FALLBACK_ERROR_KINDS.contains(kind)) return false;
        for (String nfk : NON_FALLBACK_ERROR_KINDS) {
            if (kind.contains(nfk) || errorType.contains(nfk) || code.contains(nfk)) return false;
        }

        if (response.errorShouldRetry() != null && response.errorShouldRetry()) return true;

        if (status != null && (FALLBACK_STATUSES.contains(status) || (status >= 500 && status <= 599))) return true;
        if (FALLBACK_ERROR_KINDS.contains(kind)) return true;

        return FALLBACK_ERROR_TOKENS.stream().anyMatch(t ->
                kind.contains(t) || errorType.contains(t) || code.contains(t) || text.contains(t));
    }

    private static String limit(String s) {
        if (s == null) return "";
        return s.length() > 120 ? s.substring(0, 120) : s;
    }
}
