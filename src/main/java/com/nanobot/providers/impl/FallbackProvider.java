package com.nanobot.providers.impl;

import com.nanobot.config.ModelPresetProperties;
import com.nanobot.config.GenerationSettings;
import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.LLMResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 包装主 provider，在错误时透明地将请求转移到 fallback 模型的包装器。
 *
 * <p>对标 Python {@code nanobot/providers/fallback_provider.py FallbackProvider}（~310 行）。
 * 关键设计：
 * <ul>
 *   <li>故障转移是请求范围的（wrapper 本身在轮次之间无状态）</li>
 *   <li>已流式输出内容后跳过转移（避免重复输出），timeout 恢复除外</li>
 *   <li>递归转移通过 factory 返回纯 provider 来防止</li>
 *   <li>主 provider 在连续失败后熔断，避免浪费请求到已知不良端点</li>
 * </ul>
 */
public class FallbackProvider extends LLMProvider {

    private static final Logger log = LoggerFactory.getLogger(FallbackProvider.class);

    private static final int PRIMARY_FAILURE_THRESHOLD = 3;
    private static final long PRIMARY_COOLDOWN_MS = 60_000;
    private static final Object MISSING = new Object();

    private static final Set<String> FALLBACK_ERROR_KINDS = Set.of(
            "timeout", "connection", "server_error", "rate_limit", "overloaded");

    private static final Set<String> NON_FALLBACK_ERROR_KINDS = Set.of(
            "authentication", "auth", "permission", "content_filter",
            "refusal", "context_length", "invalid_request");

    private static final Set<Integer> NON_FALLBACK_HTTP_STATUSES = Set.of(400, 401, 403, 404, 422);

    private static final List<String> FALLBACK_ERROR_TOKENS = List.of(
            "rate_limit", "rate limit", "too_many_requests", "too many requests",
            "overloaded", "server_error", "server error", "temporarily unavailable",
            "timeout", "timed out", "connection", "insufficient_quota",
            "insufficient quota", "quota_exceeded", "quota exceeded",
            "quota_exhausted", "quota exhausted", "billing_hard_limit",
            "insufficient_balance", "balance", "out of credits");

    // 对标 Python supports_stream_recover_callback = True
    public boolean supportsStreamRecoverCallback = true;

    private final LLMProvider primary;
    private final List<ModelPresetProperties> fallbackPresets;
    private final Function<ModelPresetProperties, LLMProvider> providerFactory;
    private final boolean hasFallbacks;
    private int primaryFailures;
    private Long primaryTrippedAt;

    /**
     * 构造 FallbackProvider。
     *
     * @param primary         主 provider
     * @param fallbackPresets fallback preset 列表
     * @param providerFactory 按 preset 创建 provider 的工厂回调
     */
    // 对标 Python FallbackProvider.__init__()
    public FallbackProvider(
            LLMProvider primary,
            List<ModelPresetProperties> fallbackPresets,
            Function<ModelPresetProperties, LLMProvider> providerFactory) {
        super(null, null);
        this.primary = primary;
        this.fallbackPresets = new ArrayList<>(fallbackPresets);
        this.providerFactory = providerFactory;
        this.hasFallbacks = !fallbackPresets.isEmpty();
        this.primaryFailures = 0;
        this.primaryTrippedAt = null;
    }

    @Override
    public GenerationSettings getGeneration() { return primary.getGeneration(); }

    @Override
    public void setGeneration(GenerationSettings value) { primary.setGeneration(value); }

    @Override
    public String getDefaultModel() {
        return primary.getDefaultModel();
    }

    @Override
    public boolean supportsProgressDeltas() {
        return primary.supportsProgressDeltas();
    }

    /**
     * 主 provider 是否处于非熔断状态。
     *
     * @return true 如果主 provider 可用（含半开探测）
     */
    // 对标 Python _primary_available()
    private boolean primaryAvailable() {
        if (primaryTrippedAt == null) return true;
        if (System.currentTimeMillis() - primaryTrippedAt >= PRIMARY_COOLDOWN_MS) {
            return true; // 半开：允许一次探测
        }
        return false;
    }

    // =========================================================================
    // chat / chatStream 委托
    // =========================================================================

    /**
     * 发送 chat 请求，必要时 failover。
     *
     * @param messages     消息列表
     * @param tools        工具定义列表
     * @param model        模型标识
     * @param maxTokens    最大 token 数
     * @param temperature  温度
     * @param reasoningEffort reasoning 力度
     * @param toolChoice   工具选择
     * @return LLMResponse 的 CompletableFuture
     */
    // 对标 Python FallbackProvider.chat()
    @Override
    public CompletableFuture<LLMResponse> chat(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort,
            Object toolChoice) {
        if (!hasFallbacks) {
            return primary.chat(messages, tools, model, maxTokens, temperature, reasoningEffort, toolChoice);
        }
        return tryWithFallback(
                (p, msgs, t, mdl, mt, temp, re, tc) ->
                        p.chat(msgs, t, mdl, mt, temp, re, tc),
                messages, tools, model, maxTokens, temperature, reasoningEffort, toolChoice,
                null, null);
    }

    /**
     * 发送流式 chat 请求，必要时 failover。
     *
     * @param messages         消息列表
     * @param tools            工具定义列表
     * @param model            模型标识
     * @param maxTokens        最大 token 数
     * @param temperature      温度
     * @param reasoningEffort  reasoning 力度
     * @param toolChoice       工具选择
     * @param onContentDelta   文本增量回调
     * @param onThinkingDelta  thinking 增量回调
     * @param onToolCallDelta  tool call 增量回调
     * @return LLMResponse 的 CompletableFuture
     */
    // 对标 Python FallbackProvider.chat_stream()
    @Override
    public CompletableFuture<LLMResponse> chatStream(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort,
            Object toolChoice,
            ContentDeltaCallback onContentDelta,
            ContentDeltaCallback onThinkingDelta,
            ToolCallDeltaCallback onToolCallDelta) {
        if (!hasFallbacks) {
            return primary.chatStream(messages, tools, model, maxTokens, temperature,
                    reasoningEffort, toolChoice, onContentDelta, onThinkingDelta, onToolCallDelta);
        }

        boolean[] hasStreamed = {false};
        ContentDeltaCallback trackingDelta = text -> {
            if (text != null && !text.isEmpty()) hasStreamed[0] = true;
            if (onContentDelta != null) return onContentDelta.onDelta(text);
            return CompletableFuture.completedFuture(null);
        };

        return tryWithFallback(
                (p, msgs, t, mdl, mt, temp, re, tc) ->
                        p.chatStream(msgs, t, mdl, mt, temp, re, tc,
                                trackingDelta, onThinkingDelta, onToolCallDelta),
                messages, tools, model, maxTokens, temperature, reasoningEffort, toolChoice,
                hasStreamed, null);
    }

    // =========================================================================
    // 核心 failover 逻辑
    // =========================================================================

    @FunctionalInterface
    private interface ProviderCall {
        CompletableFuture<LLMResponse> call(
                LLMProvider p,
                List<Map<String, Object>> messages,
                List<Map<String, Object>> tools,
                String model,
                int maxTokens,
                double temperature,
                String reasoningEffort,
                Object toolChoice);
    }

    /**
     * 尝试主 provider，失败时遍历 fallback 列表。
     *
     * @param call         LLM 调用封装
     * @param messages     消息列表
     * @param tools        工具定义列表
     * @param model        模型标识
     * @param maxTokens    最大 token 数
     * @param temperature  温度
     * @param reasoningEffort reasoning 力度
     * @param toolChoice   工具选择
     * @param hasStreamed  流内容标记数组（chat 为 null）
     * @param onStreamRecover 流恢复回调（chat 为 null）
     * @return LLMResponse 的 CompletableFuture
     */
    // 对标 Python _try_with_fallback()
    private CompletableFuture<LLMResponse> tryWithFallback(
            ProviderCall call,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort,
            Object toolChoice,
            boolean[] hasStreamed,
            Supplier<CompletableFuture<Void>> onStreamRecover) {
        return CompletableFuture.supplyAsync(() -> {
            String primaryModel = (model != null) ? model : primary.getDefaultModel();

            if (primaryAvailable()) {
                LLMResponse response = call.call(primary, messages, tools, model,
                        maxTokens, temperature, reasoningEffort, toolChoice).join();

                if (!"error".equals(response.finishReason())) {
                    primaryFailures = 0;
                    primaryTrippedAt = null;
                    return response;
                }

                if (hasStreamed != null && hasStreamed[0]) {
                    boolean isTimeout = "timeout".equals(
                            response.errorKind() != null ? response.errorKind().toLowerCase() : "");
                    if (isTimeout) {
                        log.warn("Primary model '{}' stream stalled after content was emitted; " +
                                "attempting failover anyway", primaryModel);
                        hasStreamed[0] = false;
                        if (onStreamRecover != null) {
                            onStreamRecover.get().join();
                        }
                    } else {
                        log.warn("Primary model error but content already streamed; skipping failover");
                        return response;
                    }
                }

                if (!shouldFallback(response)) {
                    log.warn("Primary model '{}' returned non-fallbackable error: {}",
                            primaryModel, truncate(response.content(), 120));
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
            for (int idx = 0; idx < fallbackPresets.size(); idx++) {
                ModelPresetProperties fallback = fallbackPresets.get(idx);
                String fallbackModel = fallback.model();

                if (hasStreamed != null && hasStreamed[0]) {
                    boolean isTimeout = lastResponse != null
                            && "timeout".equals(
                                    lastResponse.errorKind() != null ? lastResponse.errorKind().toLowerCase() : "");
                    if (isTimeout && onStreamRecover != null) {
                        String prevModel = (idx > 0) ? fallbackPresets.get(idx - 1).model() : primaryModel;
                        log.warn("Fallback model '{}' stream stalled after content was emitted; " +
                                "starting a new stream segment and trying next fallback", prevModel);
                        hasStreamed[0] = false;
                        onStreamRecover.get().join();
                    } else {
                        break;
                    }
                }

                if (idx == 0 && primarySkipped) {
                    log.info("Primary model '{}' circuit open, trying fallback '{}'",
                            primaryModel, fallbackModel);
                } else if (idx == 0) {
                    log.info("Primary model '{}' failed, trying fallback '{}'",
                            primaryModel, fallbackModel);
                } else {
                    log.info("Fallback '{}' also failed, trying next fallback '{}'",
                            fallbackPresets.get(idx - 1).model(), fallbackModel);
                }

                LLMProvider fallbackProvider;
                try {
                    fallbackProvider = providerFactory.apply(fallback);
                } catch (Exception e) {
                    log.warn("Failed to create provider for fallback '{}': {}", fallbackModel, e.toString());
                    continue;
                }

                // 保存并覆盖原始参数值
                Object origModel = MISSING;
                Object origMaxTokens = MISSING;
                Object origTemperature = MISSING;
                Object origReasoningEffort = MISSING;

                LLMResponse fallbackResponse;
                try {
                    fallbackResponse = call.call(fallbackProvider, messages, tools,
                            fallbackModel, fallback.maxTokens(), fallback.temperature(),
                            fallback.reasoningEffort(), toolChoice).join();
                } catch (Exception e) {
                    log.warn("Fallback '{}' call failed: {}", fallbackModel, e.toString());
                    continue;
                }

                if (!"error".equals(fallbackResponse.finishReason())) {
                    log.info("Fallback '{}' succeeded after primary '{}' failed",
                            fallbackModel, primaryModel);
                    return fallbackResponse;
                }

                lastResponse = fallbackResponse;
                log.warn("Fallback '{}' also failed: {}",
                        fallbackModel, truncate(fallbackResponse.content(), 120));
            }

            log.warn("All {} fallback model(s) failed", fallbackPresets.size());
            if (lastResponse != null) {
                return lastResponse;
            }
            return new LLMResponse(
                    "Primary model '" + primaryModel + "' circuit open and no fallbacks available",
                    "error");
        });
    }

    // =========================================================================
    // Fallback 判定
    // =========================================================================

    /**
     * 判断是否应对该错误进行 failover。
     *
     * @param response LLM 响应
     * @return true 表示应尝试 fallback
     */
    // 对标 Python _should_fallback()
    static boolean shouldFallback(LLMResponse response) {
        if (response.errorShouldRetry() != null && !response.errorShouldRetry()) {
            return false;
        }

        Integer status = response.errorStatusCode();
        String kind = (response.errorKind() != null) ? response.errorKind().toLowerCase() : "";
        String errorType = (response.errorType() != null) ? response.errorType().toLowerCase() : "";
        String code = (response.errorCode() != null) ? response.errorCode().toLowerCase() : "";
        String text = (response.content() != null) ? response.content().toLowerCase() : "";

        if (status != null && NON_FALLBACK_HTTP_STATUSES.contains(status)) {
            return false;
        }
        if (NON_FALLBACK_ERROR_KINDS.contains(kind)) {
            return false;
        }
        for (String token : NON_FALLBACK_ERROR_KINDS) {
            if (kind.contains(token) || errorType.contains(token) || code.contains(token)) {
                return false;
            }
        }

        if (response.errorShouldRetry() != null && response.errorShouldRetry()) {
            return true;
        }
        if (status != null && (status == 408 || status == 409 || status == 429
                || (status >= 500 && status <= 599))) {
            return true;
        }
        if (FALLBACK_ERROR_KINDS.contains(kind)) {
            return true;
        }
        for (String token : FALLBACK_ERROR_TOKENS) {
            if (kind.contains(token) || errorType.contains(token)
                    || code.contains(token) || text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
