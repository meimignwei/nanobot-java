package com.nanobot.providers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanobot.config.GenerationSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM provider 抽象基类，提供重试引擎、错误分类、消息清理及重试延迟提取等通用能力。
 *
 * <p>对标 Python {@code nanobot/providers/base.py LLMProvider(ABC)}（935 行）。
 * 子类需实现 {@link #chat} 和 {@link #getDefaultModel()}。
 *
 * <p>关键转换：Python {@code async def} → Java {@link CompletableFuture}；
 * Python {@code asyncio.sleep()} → 虚拟线程上的 {@code Thread.sleep()}。
 */
public abstract class LLMProvider {

    private static final Logger log = LoggerFactory.getLogger(LLMProvider.class);

    // —— Sentinel：区分「未传」和「显式传 null」 ——
    public static final Object SENTINEL = new Object();

    // —— 重试配置 ——
    protected static final int[] CHAT_RETRY_DELAYS = {1, 2, 4};
    protected static final int PERSISTENT_MAX_DELAY = 60;
    protected static final int PERSISTENT_IDENTICAL_ERROR_LIMIT = 10;
    protected static final int RETRY_HEARTBEAT_CHUNK = 30;

    // —— 瞬态错误标记（文本匹配） ——
    private static final Set<String> TRANSIENT_ERROR_MARKERS = Set.of(
            "429", "rate limit", "500", "502", "503", "504",
            "overloaded", "timeout", "timed out", "connection",
            "server error", "temporarily unavailable", "速率限制", "访问量过大"
    );

    // —— 可重试 HTTP 状态码 ——
    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(408, 409, 429);

    // —— 瞬态错误种类 ——
    private static final Set<String> TRANSIENT_ERROR_KINDS = Set.of("timeout", "connection");

    // —— 不可重试的 429 错误 token（计费/配额） ——
    private static final Set<String> NON_RETRYABLE_429_ERROR_TOKENS = Set.of(
            "insufficient_quota", "quota_exceeded", "quota_exhausted",
            "billing_hard_limit_reached", "insufficient_balance",
            "credit_balance_too_low", "billing_not_active", "payment_required"
    );

    // —— 可重试的 429 错误 token ——
    private static final Set<String> RETRYABLE_429_ERROR_TOKENS = Set.of(
            "rate_limit_exceeded", "rate_limit_error", "too_many_requests",
            "request_limit_exceeded", "requests_limit_exceeded", "overloaded_error"
    );

    // —— 不可重试的 429 文本标记 ——
    private static final List<String> NON_RETRYABLE_429_TEXT_MARKERS = List.of(
            "insufficient_quota", "insufficient quota", "quota exceeded",
            "quota exhausted", "billing hard limit", "billing_hard_limit_reached",
            "billing not active", "insufficient balance", "insufficient_balance",
            "credit balance too low", "payment required", "out of credits",
            "out of quota", "exceeded your current quota"
    );

    // —— 可重试的 429 文本标记 ——
    private static final List<String> RETRYABLE_429_TEXT_MARKERS = List.of(
            "rate limit", "rate_limit", "too many requests",
            "retry after", "try again in", "temporarily unavailable",
            "overloaded", "concurrency limit", "速率限制"
    );

    /** 合成 user 消息内容，对标 Python _SYNTHETIC_USER_CONTENT */
    private static final String SYNTHETIC_USER_CONTENT = "(conversation continued)";

    // —— Retry-After 提取正则 ——
    private static final Pattern[] RETRY_AFTER_PATTERNS = {
            Pattern.compile("retry after\\s+(\\d+(?:\\.\\d+)?)\\s*(ms|milliseconds|s|sec|secs|seconds|m|min|minutes)?",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("try again in\\s+(\\d+(?:\\.\\d+)?)\\s*(ms|milliseconds|s|sec|secs|seconds|m|min|minutes)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("wait\\s+(\\d+(?:\\.\\d+)?)\\s*(ms|milliseconds|s|sec|secs|seconds|m|min|minutes)\\s*before retry",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("retry[_-]?after[\"'\\s:=]+(\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE)
    };

    protected static final ObjectMapper JSON = new ObjectMapper();

    // —— 实例字段 ——

    /** API 密钥 */
    protected String apiKey;
    /** API base URL */
    protected String apiBase;
    /** 生成参数默认值 */
    private GenerationSettings generation = GenerationSettings.DEFAULTS;

    public GenerationSettings getGeneration() { return generation; }
    public void setGeneration(GenerationSettings value) { this.generation = value; }

    /** 是否支持增量进度回调 */
    private boolean supportsProgressDeltas = false;

    public void setSupportsProgressDeltas(boolean v) { this.supportsProgressDeltas = v; }
    public boolean supportsProgressDeltas() { return supportsProgressDeltas; }

    /**
     * 构造 provider，设置 API 密钥与 base URL。
     *
     * @param apiKey  API 密钥（可为 null，对 oauth/local/direct provider 非必须）
     * @param apiBase API base URL（可为 null，使用默认值）
     */
    protected LLMProvider(String apiKey, String apiBase) {
        this.apiKey = apiKey;
        this.apiBase = apiBase;
    }

    // =========================================================================
    // 抽象方法
    // =========================================================================

    /**
     * 发送 chat completion 请求。
     *
     * @param messages        消息列表，每项含 role 和 content
     * @param tools           工具定义列表（可为 null）
     * @param model           模型标识（provider 特定）
     * @param maxTokens       响应最大 token 数
     * @param temperature     采样温度
     * @param reasoningEffort reasoning 力度（可为 null）
     * @param toolChoice      工具选择策略（"auto"/"required"/特定 tool dict，可为 null）
     * @return LLMResponse 的 CompletableFuture
     */
    // 对标 Python base.py async def chat()
    public abstract CompletableFuture<LLMResponse> chat(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort,
            Object toolChoice);

    /**
     * 返回此 provider 的默认模型。
     *
     * @return 默认模型名
     */
    // 对标 Python base.py abstract get_default_model()
    public abstract String getDefaultModel();

    // =========================================================================
    // 流式（默认回退到 chat）
    // =========================================================================

    /**
     * 流式 chat completion，为每个文本增量调用 onContentDelta。
     * 默认实现回退到 {@link #chat} 并将全部内容作为单个增量发送。
     *
     * @param messages         消息列表
     * @param tools            工具定义列表
     * @param model            模型标识
     * @param maxTokens        响应最大 token 数
     * @param temperature      采样温度
     * @param reasoningEffort  reasoning 力度
     * @param toolChoice       工具选择策略
     * @param onContentDelta   文本增量回调
     * @param onThinkingDelta  thinking 增量回调（预留）
     * @param onToolCallDelta  tool call 增量回调（预留）
     * @return LLMResponse 的 CompletableFuture
     */
    // 对标 Python base.py async def chat_stream()
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
        return chat(messages, tools, model, maxTokens, temperature, reasoningEffort, toolChoice)
                .thenCompose(response -> {
                    if (onContentDelta != null && response.content() != null) {
                        return onContentDelta.onDelta(response.content())
                                .thenApply(v -> response);
                    }
                    return CompletableFuture.completedFuture(response);
                });
    }

    // =========================================================================
    // 安全调用包装器（异常 → error response）
    // =========================================================================

    /**
     * 调用 {@link #chat} 并将意外异常转为 error response。
     *
     * @param messages        消息列表
     * @param tools           工具定义列表
     * @param model           模型标识
     * @param maxTokens       响应最大 token 数
     * @param temperature     采样温度
     * @param reasoningEffort reasoning 力度
     * @param toolChoice      工具选择策略
     * @return LLMResponse 的 CompletableFuture（永不以异常完成）
     */
    // 对标 Python base.py async def _safe_chat()
    protected CompletableFuture<LLMResponse> safeChat(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort,
            Object toolChoice) {
        return chat(messages, tools, model, maxTokens, temperature, reasoningEffort, toolChoice)
                .exceptionally(exc -> {
                    if (exc instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    return new LLMResponse("Error calling LLM: " + exc.getMessage(), "error");
                });
    }

    /**
     * 调用 {@link #chatStream} 并将意外异常转为 error response。
     *
     * @param messages         消息列表
     * @param tools            工具定义列表
     * @param model            模型标识
     * @param maxTokens        响应最大 token 数
     * @param temperature      采样温度
     * @param reasoningEffort  reasoning 力度
     * @param toolChoice       工具选择策略
     * @param onContentDelta   文本增量回调
     * @param onThinkingDelta  thinking 增量回调
     * @param onToolCallDelta  tool call 增量回调
     * @return LLMResponse 的 CompletableFuture（永不以异常完成）
     */
    // 对标 Python base.py async def _safe_chat_stream()
    protected CompletableFuture<LLMResponse> safeChatStream(
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
        return chatStream(messages, tools, model, maxTokens, temperature,
                reasoningEffort, toolChoice,
                onContentDelta, onThinkingDelta, onToolCallDelta)
                .exceptionally(exc -> {
                    if (exc instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    return new LLMResponse("Error calling LLM: " + exc.getMessage(), "error");
                });
    }

    // =========================================================================
    // chatWithRetry / chatStreamWithRetry
    // =========================================================================

    /**
     * 调用 {@link #chat} 并在瞬态失败时重试。参数默认值来自 {@link #generation}。
     *
     * @param messages         消息列表
     * @param tools            工具定义列表
     * @param model            模型标识
     * @param maxTokensRaw     maxTokens（Integer 或 SENTINEL）
     * @param temperatureRaw   temperature（Double 或 SENTINEL）
     * @param reasoningEffortRaw reasoningEffort（String 或 SENTINEL）
     * @param toolChoice       工具选择策略
     * @param retryMode        重试模式（"standard"/"persistent"）
     * @param onRetryWait      重试等待回调
     * @return LLMResponse 的 CompletableFuture
     */
    // 对标 Python base.py async def chat_with_retry()
    public CompletableFuture<LLMResponse> chatWithRetry(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            Object maxTokensRaw,
            Object temperatureRaw,
            Object reasoningEffortRaw,
            Object toolChoice,
            String retryMode,
            RetryWaitCallback onRetryWait) {
        int maxTokens = resolveMaxTokens(maxTokensRaw);
        double temperature = resolveTemperature(temperatureRaw);
        String reasoningEffort = resolveReasoningEffort(reasoningEffortRaw);

        return runWithRetry(
                (msgs, t, mdl, mt, temp, re, tc) ->
                        safeChat(msgs, t, mdl, mt, temp, re, tc),
                messages, tools, model, maxTokens, temperature, reasoningEffort, toolChoice,
                retryMode, onRetryWait, null, null);
    }

    /**
     * 调用 {@link #chatStream} 并在瞬态失败时重试。参数默认值来自 {@link #generation}。
     *
     * @param messages          消息列表
     * @param tools             工具定义列表
     * @param model             模型标识
     * @param maxTokensRaw      maxTokens（Integer 或 SENTINEL）
     * @param temperatureRaw    temperature（Double 或 SENTINEL）
     * @param reasoningEffortRaw reasoningEffort（String 或 SENTINEL）
     * @param toolChoice        工具选择策略
     * @param onContentDelta    文本增量回调
     * @param onThinkingDelta   thinking 增量回调
     * @param onToolCallDelta   tool call 增量回调
     * @param onStreamRecover   流恢复回调
     * @param retryMode         重试模式（"standard"/"persistent"）
     * @param onRetryWait       重试等待回调
     * @return LLMResponse 的 CompletableFuture
     */
    // 对标 Python base.py async def chat_stream_with_retry()
    public CompletableFuture<LLMResponse> chatStreamWithRetry(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            Object maxTokensRaw,
            Object temperatureRaw,
            Object reasoningEffortRaw,
            Object toolChoice,
            ContentDeltaCallback onContentDelta,
            ContentDeltaCallback onThinkingDelta,
            ToolCallDeltaCallback onToolCallDelta,
            Supplier<CompletableFuture<Void>> onStreamRecover,
            String retryMode,
            RetryWaitCallback onRetryWait) {
        int maxTokens = resolveMaxTokens(maxTokensRaw);
        double temperature = resolveTemperature(temperatureRaw);
        String reasoningEffort = resolveReasoningEffort(reasoningEffortRaw);

        boolean[] hasStreamedContent = {false};

        ContentDeltaCallback trackingDelta = text -> {
            if (text != null && !text.isEmpty()) hasStreamedContent[0] = true;
            if (onContentDelta != null) return onContentDelta.onDelta(text);
            return CompletableFuture.completedFuture(null);
        };

        ContentDeltaCallback effectiveDelta = (onContentDelta != null) ? trackingDelta : null;

        Supplier<CompletableFuture<Void>> recoverStream = () -> {
            if (onStreamRecover != null) {
                return onStreamRecover.get().thenRun(() -> hasStreamedContent[0] = false);
            }
            hasStreamedContent[0] = false;
            return CompletableFuture.completedFuture(null);
        };

        ChatStreamCallable streamCall = (msgs, t, mdl, mt, temp, re, tc) ->
                safeChatStream(msgs, t, mdl, mt, temp, re, tc,
                        effectiveDelta, onThinkingDelta, onToolCallDelta);

        return runWithRetry(
                streamCall,
                messages, tools, model, maxTokens, temperature, reasoningEffort, toolChoice,
                retryMode, onRetryWait,
                () -> !hasStreamedContent[0],
                onStreamRecover != null ? recoverStream : null);
    }

    // —— 默认值解析辅助 ——

    private int resolveMaxTokens(Object maxTokensRaw) {
        if (maxTokensRaw == SENTINEL || maxTokensRaw == null) {
            return getGeneration().maxTokens();
        }
        return (Integer) maxTokensRaw;
    }

    private double resolveTemperature(Object temperatureRaw) {
        if (temperatureRaw == SENTINEL || temperatureRaw == null) {
            return getGeneration().temperature();
        }
        return (Double) temperatureRaw;
    }

    private String resolveReasoningEffort(Object reasoningEffortRaw) {
        if (reasoningEffortRaw == SENTINEL || reasoningEffortRaw == null) {
            return getGeneration().reasoningEffort();
        }
        return (String) reasoningEffortRaw;
    }

    // =========================================================================
    // 核心重试引擎
    // =========================================================================

    /** 对标 Python _run_with_retry 中 chat/chatStream 的可调用签名 */
    @FunctionalInterface
    protected interface ChatStreamCallable {
        CompletableFuture<LLMResponse> call(
                List<Map<String, Object>> messages,
                List<Map<String, Object>> tools,
                String model,
                int maxTokens,
                double temperature,
                String reasoningEffort,
                Object toolChoice);
    }

    /** 文本增量回调，对标 Python {@code Callable[[str], Awaitable[None]]} */
    @FunctionalInterface
    public interface ContentDeltaCallback {
        CompletableFuture<Void> onDelta(String text);
    }

    /** tool call 增量回调，对标 Python {@code Callable[[dict], Awaitable[None]]} */
    @FunctionalInterface
    public interface ToolCallDeltaCallback {
        CompletableFuture<Void> onDelta(Map<String, Object> delta);
    }

    /** 重试等待通知回调，对标 Python {@code Callable[[str], Awaitable[None]]} */
    @FunctionalInterface
    public interface RetryWaitCallback {
        CompletableFuture<Void> onWait(String message);
    }

    /**
     * 核心重试引擎。在虚拟线程上同步执行重试循环，利用虚拟线程的阻塞友好特性。
     *
     * @param call              实际 LLM 调用
     * @param originalMessages  原始消息（用于镜像剥离重试）
     * @param tools             工具定义
     * @param model             模型标识
     * @param maxTokens         max tokens
     * @param temperature       温度
     * @param reasoningEffort   reasoning 力度
     * @param toolChoice        工具选择
     * @param retryMode         重试模式（"standard"/"persistent"）
     * @param onRetryWait       重试等待回调
     * @param shouldRetryGuard  流内容保护守卫（已流式输出内容后限制重试）
     * @param onStreamRecover   流恢复回调
     * @return LLMResponse 的 CompletableFuture
     */
    // 对标 Python base.py async def _run_with_retry()
    protected CompletableFuture<LLMResponse> runWithRetry(
            ChatStreamCallable call,
            List<Map<String, Object>> originalMessages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort,
            Object toolChoice,
            String retryMode,
            RetryWaitCallback onRetryWait,
            Supplier<Boolean> shouldRetryGuard,
            Supplier<CompletableFuture<Void>> onStreamRecover) {
        // 用 AtomicReference 包装以便在 lambda 内修改（对标 Python mutable closure）
        AtomicReference<Supplier<Boolean>> guardRef = new AtomicReference<>(shouldRetryGuard);

        return CompletableFuture.supplyAsync(() -> {
            boolean persistent = "persistent".equals(retryMode);
            int attempt = 0;
            LLMResponse lastResponse = null;
            String lastErrorKey = null;
            int identicalErrorCount = 0;

            while (true) {
                attempt++;
                LLMResponse response = call.call(originalMessages, tools, model,
                        maxTokens, temperature,
                        reasoningEffort, toolChoice).join();

                if (!"error".equals(response.finishReason())) {
                    return response;
                }

                lastResponse = response;

                // 已流式输出内容后的守卫检查
                Supplier<Boolean> currentGuard = guardRef.get();
                if (currentGuard != null && !currentGuard.get()) {
                    boolean isTimeout = "timeout".equals(
                            response.errorKind() != null ? response.errorKind().toLowerCase() : "");
                    if (isTimeout) {
                        if (onStreamRecover != null) {
                            log.warn("LLM stream stalled after content was emitted; " +
                                    "starting a new stream segment and retrying");
                            onStreamRecover.get().join();
                        } else {
                            log.warn("LLM stream stalled after content was emitted; " +
                                    "suppressing delta callbacks and retrying");
                            guardRef.set(null);
                        }
                    } else {
                        log.warn("LLM stream failed after content was emitted; skipping retry");
                        return response;
                    }
                }

                // 跟踪 persistent 模式的相同错误计数
                String errorKey = (response.content() != null)
                        ? response.content().strip().toLowerCase() : null;
                if (errorKey != null && !errorKey.isEmpty() && errorKey.equals(lastErrorKey)) {
                    identicalErrorCount++;
                } else {
                    lastErrorKey = errorKey;
                    identicalErrorCount = (errorKey != null && !errorKey.isEmpty()) ? 1 : 0;
                }

                // 非瞬态错误不重试
                if (!isTransientResponse(response)) {
                    List<Map<String, Object>> stripped = stripImageContent(originalMessages);
                    if (stripped != null && !stripped.equals(originalMessages)) {
                        log.warn("Non-transient LLM error with image content, retrying without images");
                        LLMResponse result = call.call(stripped, tools, model,
                                maxTokens, temperature,
                                reasoningEffort, toolChoice).join();
                        if (!"error".equals(result.finishReason())) {
                            stripImageContentInplace(originalMessages);
                        }
                        return result;
                    }
                    return response;
                }

                // Persistent 模式：相同错误过多后停止
                if (persistent && identicalErrorCount >= PERSISTENT_IDENTICAL_ERROR_LIMIT) {
                    log.warn("Stopping persistent retry after {} identical transient errors: {}",
                            identicalErrorCount,
                            truncate(response.content(), 120));
                    if (onRetryWait != null) {
                        onRetryWait.onWait("Persistent retry stopped after " +
                                identicalErrorCount + " identical errors.").join();
                    }
                    return response;
                }

                // Standard 模式：耗尽延迟后停止
                if (!persistent && attempt > CHAT_RETRY_DELAYS.length) {
                    log.warn("LLM request failed after {} retries, giving up: {}",
                            attempt, truncate(response.content(), 120));
                    if (onRetryWait != null) {
                        onRetryWait.onWait("Model request failed after " + attempt +
                                " retries, giving up.").join();
                    }
                    break;
                }

                double baseDelay = CHAT_RETRY_DELAYS[Math.min(attempt - 1, CHAT_RETRY_DELAYS.length - 1)];
                Double extracted = extractRetryAfterFromResponse(response);
                double delay = (extracted != null && extracted > 0) ? extracted : baseDelay;
                if (persistent) delay = Math.min(delay, PERSISTENT_MAX_DELAY);

                log.warn("LLM transient error (attempt {}{}), retrying in {}s: {}",
                        attempt,
                        (persistent && attempt > CHAT_RETRY_DELAYS.length) ? "+" : "/" + CHAT_RETRY_DELAYS.length,
                        (int) Math.round(delay),
                        truncate(response.content(), 120));

                sleepWithHeartbeat(delay, attempt, persistent, onRetryWait).join();
            }

            return lastResponse != null ? lastResponse : call.call(
                    originalMessages, tools, model, maxTokens, temperature, reasoningEffort, toolChoice).join();
        });
    }

    // =========================================================================
    // 分段 sleep + 心跳
    // =========================================================================

    /**
     * 分段 sleep 并通过 onRetryWait 回调发送心跳通知。
     * 对标 Python {@code async def _sleep_with_heartbeat()} —— 在虚拟线程上用 Thread.sleep()。
     *
     * @param delay        总延迟秒数
     * @param attempt      当前尝试次数
     * @param persistent   是否为 persistent 模式
     * @param onRetryWait  心跳回调
     * @return CompletableFuture<Void>
     */
    // 对标 Python base.py async def _sleep_with_heartbeat()
    protected CompletableFuture<Void> sleepWithHeartbeat(
            double delay,
            int attempt,
            boolean persistent,
            RetryWaitCallback onRetryWait) {
        return CompletableFuture.runAsync(() -> {
            double remaining = Math.max(0.0, delay);
            while (remaining > 0) {
                if (onRetryWait != null) {
                    String kind = persistent ? "persistent retry" : "retry";
                    try {
                        onRetryWait.onWait(
                                "Model request failed, " + kind + " in " +
                                        Math.max(1, (int) Math.round(remaining)) + "s " +
                                        "(attempt " + attempt + ")."
                        ).join();
                    } catch (Exception ignored) {
                    }
                }
                double chunk = Math.min(remaining, RETRY_HEARTBEAT_CHUNK);
                try {
                    Thread.sleep((long) (chunk * 1000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                remaining -= chunk;
            }
        });
    }

    // =========================================================================
    // 错误分类
    // =========================================================================

    /**
     * 综合判断响应错误是否为瞬态（可重试）。
     * 优先级：结构化 error_should_retry → HTTP 状态码 → error_kind → 文本匹配。
     *
     * @param response LLM 响应
     * @return true 表示应重试
     */
    // 对标 Python base.py _is_transient_response()
    public static boolean isTransientResponse(LLMResponse response) {
        if (response.errorShouldRetry() != null) {
            return response.errorShouldRetry();
        }

        if (response.errorStatusCode() != null) {
            int status = response.errorStatusCode();
            if (status == 429) {
                return isRetryable429Response(response);
            }
            if (RETRYABLE_STATUS_CODES.contains(status) || status >= 500) {
                return true;
            }
        }

        String kind = (response.errorKind() != null) ? response.errorKind().strip().toLowerCase() : "";
        if (TRANSIENT_ERROR_KINDS.contains(kind)) {
            return true;
        }

        return isTransientError(response.content());
    }

    /**
     * 纯文本模式匹配判断错误内容是否为瞬态。
     *
     * @param content 错误文本（可为 null）
     * @return true 表示匹配瞬态错误标记
     */
    // 对标 Python base.py _is_transient_error()
    public static boolean isTransientError(String content) {
        if (content == null) return false;
        String err = content.toLowerCase();
        return TRANSIENT_ERROR_MARKERS.stream().anyMatch(m -> err.contains(m.toLowerCase()));
    }

    /**
     * 检测 API key 欠费/配额/计费错误，此类错误重试无法消除。
     * 检测 HTTP 402 及计费语义 token/文本标记。
     *
     * @param response LLM 响应
     * @return true 表示欠费/配额相关错误
     */
    // 对标 Python base.py is_arrearage_response()
    public static boolean isArrearageResponse(LLMResponse response) {
        if (response.errorStatusCode() != null && response.errorStatusCode() == 402) {
            return true;
        }

        String typeToken = normalizeErrorToken(response.errorType());
        String codeToken = normalizeErrorToken(response.errorCode());
        if (NON_RETRYABLE_429_ERROR_TOKENS.contains(typeToken)
                || NON_RETRYABLE_429_ERROR_TOKENS.contains(codeToken)) {
            return true;
        }

        String content = (response.content() != null) ? response.content().toLowerCase() : "";
        return NON_RETRYABLE_429_TEXT_MARKERS.stream().anyMatch(content::contains);
    }

    /**
     * 区分可重试/不可重试的 429 错误。
     * 不可重试 token（计费/配额）优先，其次可重试 token，未知 429 默认重试。
     *
     * @param response LLM 响应
     * @return true 表示此 429 应重试
     */
    // 对标 Python base.py _is_retryable_429_response()
    public static boolean isRetryable429Response(LLMResponse response) {
        String typeToken = normalizeErrorToken(response.errorType());
        String codeToken = normalizeErrorToken(response.errorCode());

        if (NON_RETRYABLE_429_ERROR_TOKENS.contains(typeToken)
                || NON_RETRYABLE_429_ERROR_TOKENS.contains(codeToken)) {
            return false;
        }

        String content = (response.content() != null) ? response.content().toLowerCase() : "";
        if (NON_RETRYABLE_429_TEXT_MARKERS.stream().anyMatch(content::contains)) {
            return false;
        }

        if (RETRYABLE_429_ERROR_TOKENS.contains(typeToken)
                || RETRYABLE_429_ERROR_TOKENS.contains(codeToken)) {
            return true;
        }
        if (RETRYABLE_429_TEXT_MARKERS.stream().anyMatch(content::contains)) {
            return true;
        }
        return true;
    }

    /**
     * 规范化错误 token 值：trim + 小写，空字符串返回 null。
     *
     * @param value 原始值
     * @return 规范化后的 token，或 null
     */
    // 对标 Python base.py _normalize_error_token()
    private static String normalizeErrorToken(Object value) {
        if (value == null) return null;
        String token = String.valueOf(value).strip().toLowerCase();
        return token.isEmpty() ? null : token;
    }

    // =========================================================================
    // 从响应 payload 提取 error type/code
    // =========================================================================

    /**
     * 从结构化错误 payload 提取 (error_type, error_code)。
     * 同时处理 {"error": {"type": "..."}} 和 {"type": "..."} 两种格式。
     *
     * @param payload 错误 payload（Map、JSON 字符串或 null）
     * @return [errorType, errorCode] 数组，两个元素均可为 null
     */
    // 对标 Python base.py _extract_error_type_code()
    @SuppressWarnings("unchecked")
    public static String[] extractErrorTypeCode(Object payload) {
        if (payload == null) return new String[]{null, null};

        Map<String, Object> data = null;
        if (payload instanceof Map<?, ?> m) {
            data = (Map<String, Object>) m;
        } else if (payload instanceof String text) {
            String trimmed = text.strip();
            if (!trimmed.isEmpty()) {
                try {
                    Object parsed = JSON.readValue(trimmed, Object.class);
                    if (parsed instanceof Map<?, ?> m) data = (Map<String, Object>) m;
                } catch (JsonProcessingException ignored) {
                }
            }
        }

        if (data == null) return new String[]{null, null};

        String typeValue = (String) data.get("type");
        String codeValue = (String) data.get("code");
        Map<String, Object> errorObj = data.get("error") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : null;

        if (errorObj != null) {
            if (errorObj.get("type") instanceof String t) typeValue = t;
            if (errorObj.get("code") instanceof String c) codeValue = c;
        }

        return new String[]{normalizeErrorToken(typeValue), normalizeErrorToken(codeValue)};
    }

    // =========================================================================
    // Retry-After 提取
    // =========================================================================

    /**
     * 从响应内容文本中提取 retry-after 秒数。
     * 匹配 "retry after X seconds"、"try again in X ms" 等模式。
     *
     * @param content 响应文本（可为 null）
     * @return retry-after 秒数，未找到返回 null
     */
    // 对标 Python base.py _extract_retry_after()
    public static Double extractRetryAfter(String content) {
        if (content == null) return null;
        String text = content.toLowerCase();

        for (int i = 0; i < RETRY_AFTER_PATTERNS.length; i++) {
            Matcher m = RETRY_AFTER_PATTERNS[i].matcher(text);
            if (m.find()) {
                double value = Double.parseDouble(m.group(1));
                String unit = (i < 3 && m.groupCount() >= 2) ? m.group(2) : "s";
                return toRetrySeconds(value, unit);
            }
        }
        return null;
    }

    /**
     * 从 HTTP headers 提取 retry-after。
     * 依次检查：Retry-After-Ms（毫秒）→ Retry-After（秒或 HTTP-date）。
     *
     * @param headers HTTP 响应头 Map
     * @return retry-after 秒数，未找到返回 null
     */
    // 对标 Python base.py _extract_retry_after_from_headers()
    public static Double extractRetryAfterFromHeaders(Map<String, String> headers) {
        if (headers == null) return null;

        String retryMs = findHeaderIgnoreCase(headers, "retry-after-ms");
        if (retryMs != null) {
            try {
                double value = Double.parseDouble(retryMs) / 1000.0;
                if (value > 0) return value;
            } catch (NumberFormatException ignored) {
            }
        }

        String retryAfter = findHeaderIgnoreCase(headers, "retry-after");
        if (retryAfter == null) return null;

        String text = retryAfter.strip();
        if (text.isEmpty()) return null;

        if (text.matches("\\d+(?:\\.\\d+)?")) {
            return toRetrySeconds(Double.parseDouble(text), "s");
        }

        // HTTP-date 解析（RFC 2822），对标 Python parsedate_to_datetime
        try {
            Instant retryAt = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(text));
            double remaining = (retryAt.toEpochMilli() - System.currentTimeMillis()) / 1000.0;
            return Math.max(0.1, remaining);
        } catch (DateTimeParseException ignored) {
        }

        return null;
    }

    /**
     * 从 LLMResponse 提取 retry-after。
     * 优先级：error_retry_after_s → retry_after → 文本提取。
     *
     * @param response LLM 响应
     * @return retry-after 秒数，未找到返回 null
     */
    // 对标 Python base.py _extract_retry_after_from_response()
    public static Double extractRetryAfterFromResponse(LLMResponse response) {
        if (response.errorRetryAfterS() != null && response.errorRetryAfterS() > 0) {
            return response.errorRetryAfterS();
        }
        if (response.retryAfter() != null && response.retryAfter() > 0) {
            return response.retryAfter();
        }
        return extractRetryAfter(response.content());
    }

    /**
     * 将值和单位统一转换为秒。
     *
     * @param value 数值
     * @param unit  单位（"ms"/"s"/"m" 等，默认 "s"）
     * @return 秒数（最小 0.1）
     */
    // 对标 Python base.py _to_retry_seconds()
    public static double toRetrySeconds(double value, String unit) {
        String normalizedUnit = (unit != null) ? unit.toLowerCase() : "s";
        return switch (normalizedUnit) {
            case "ms", "milliseconds" -> Math.max(0.1, value / 1000.0);
            case "m", "min", "minutes" -> Math.max(0.1, value * 60.0);
            default -> Math.max(0.1, value);
        };
    }

    private static String findHeaderIgnoreCase(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    // =========================================================================
    // 消息清理（共享工具方法）
    // =========================================================================

    /**
     * 合并连续同角色消息并删除尾部 assistant 消息。
     * 部分 provider 拒绝最后一条消息为 assistant 的请求。
     *
     * @param messages 消息列表
     * @return 清理后的消息列表
     */
    // 对标 Python base.py _enforce_role_alternation()
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> enforceRoleAlternation(
            List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return messages;

        List<Map<String, Object>> merged = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            String role = (String) msg.get("role");

            if (!merged.isEmpty()
                    && !"system".equals(role)
                    && !"tool".equals(role)
                    && role.equals(merged.get(merged.size() - 1).get("role"))
                    && ("user".equals(role) || "assistant".equals(role))) {

                Map<String, Object> prev = merged.get(merged.size() - 1);
                if ("assistant".equals(role)) {
                    boolean prevHasTools = prev.get("tool_calls") instanceof List<?> l && !l.isEmpty();
                    boolean currHasTools = msg.get("tool_calls") instanceof List<?> l && !l.isEmpty();
                    if (currHasTools) {
                        merged.set(merged.size() - 1, new LinkedHashMap<>(msg));
                        continue;
                    }
                    if (prevHasTools) continue;
                }
                Object prevContent = prev.get("content");
                Object currContent = msg.get("content");
                if (prevContent instanceof String ps && currContent instanceof String cs) {
                    prev.put("content", (ps + "\n\n" + cs).strip());
                } else {
                    merged.set(merged.size() - 1, new LinkedHashMap<>(msg));
                }
            } else {
                merged.add(new LinkedHashMap<>(msg));
            }
        }

        Map<String, Object> lastPopped = null;
        while (!merged.isEmpty() && "assistant".equals(merged.get(merged.size() - 1).get("role"))) {
            lastPopped = merged.remove(merged.size() - 1);
        }

        if (!merged.isEmpty() && lastPopped != null) {
            boolean hasUserOrTool = merged.stream()
                    .anyMatch(m -> {
                        String r = (String) m.get("role");
                        return "user".equals(r) || "tool".equals(r);
                    });
            if (!hasUserOrTool) {
                Map<String, Object> recovered = new LinkedHashMap<>(lastPopped);
                recovered.put("role", "user");
                merged.add(recovered);
            }
        }

        for (int i = 0; i < merged.size(); i++) {
            String role = (String) merged.get(i).get("role");
            if (!"system".equals(role)) {
                if ("assistant".equals(role) && !hasToolCalls(merged.get(i))) {
                    merged.add(i, Map.of("role", "user", "content", SYNTHETIC_USER_CONTENT));
                }
                break;
            }
        }

        return merged;
    }

    /**
     * 清理消息中的空内容块并剥离内部 _meta 字段。
     * 空字符串 content 转为 "(empty)" 或 null（带 tool_calls 的 assistant）。
     * dict 类型的 content（如 Bedrock thinking）包装为列表。
     *
     * @param messages 消息列表
     * @return 清理后的消息列表
     */
    // 对标 Python base.py _sanitize_empty_content()
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> sanitizeEmptyContent(
            List<Map<String, Object>> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            Object content = msg.get("content");

            if (content instanceof String s && s.isEmpty()) {
                Map<String, Object> clean = new LinkedHashMap<>(msg);
                boolean hasToolCalls = clean.get("tool_calls") instanceof List<?> l && !l.isEmpty();
                clean.put("content", ("assistant".equals(clean.get("role")) && hasToolCalls)
                        ? null : "(empty)");
                result.add(clean);
                continue;
            }

            if (content instanceof List<?> items) {
                List<Object> newItems = new ArrayList<>();
                boolean changed = false;
                for (Object item : items) {
                    if (item instanceof Map<?, ?> m) {
                        Map<String, Object> itemMap = (Map<String, Object>) m;
                        String type = (String) itemMap.get("type");
                        if (("text".equals(type) || "input_text".equals(type) || "output_text".equals(type))
                                && !(itemMap.get("text") instanceof String s && !s.isEmpty())) {
                            changed = true;
                            continue;
                        }
                        if (itemMap.containsKey("_meta")) {
                            Map<String, Object> filtered = new LinkedHashMap<>();
                            for (Map.Entry<String, Object> e : itemMap.entrySet()) {
                                if (!"_meta".equals(e.getKey())) filtered.put(e.getKey(), e.getValue());
                            }
                            newItems.add(filtered);
                            changed = true;
                        } else {
                            newItems.add(item);
                        }
                    } else {
                        newItems.add(item);
                    }
                }
                if (changed) {
                    Map<String, Object> clean = new LinkedHashMap<>(msg);
                    if (!newItems.isEmpty()) {
                        clean.put("content", newItems);
                    } else {
                        boolean hasToolCalls = clean.get("tool_calls") instanceof List<?> l && !l.isEmpty();
                        clean.put("content", ("assistant".equals(clean.get("role")) && hasToolCalls)
                                ? null : "(empty)");
                    }
                    result.add(clean);
                    continue;
                }
            }

            if (content instanceof Map) {
                Map<String, Object> clean = new LinkedHashMap<>(msg);
                clean.put("content", List.of(content));
                result.add(clean);
                continue;
            }

            result.add(msg);
        }
        return result;
    }

    /**
     * 用文本占位符替换 image_url 内容块。返回新列表，不修改原始消息。
     *
     * @param messages 消息列表
     * @return 剥离后的消息列表，无图片时返回 null（调用者可跳过重试）
     */
    // 对标 Python base.py _strip_image_content()
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> stripImageContent(
            List<Map<String, Object>> messages) {
        boolean found = false;
        List<Map<String, Object>> result = new ArrayList<>();

        for (Map<String, Object> msg : messages) {
            Object content = msg.get("content");
            if (content instanceof List<?> blocks) {
                List<Object> newContent = new ArrayList<>();
                for (Object b : blocks) {
                    if (b instanceof Map<?, ?> bm) {
                        Map<String, Object> block = (Map<String, Object>) bm;
                        if ("image_url".equals(block.get("type"))) {
                            Map<String, Object> meta = block.get("_meta") instanceof Map<?, ?> m
                                    ? (Map<String, Object>) m : Map.of();
                            String path = (String) meta.getOrDefault("path", "");
                            String placeholder = !path.isEmpty()
                                    ? "[image: " + path + "]"
                                    : "[image omitted]";
                            newContent.add(Map.of("type", "text", "text", placeholder));
                            found = true;
                        } else {
                            newContent.add(b);
                        }
                    } else {
                        newContent.add(b);
                    }
                }
                Map<String, Object> newMsg = new LinkedHashMap<>(msg);
                newMsg.put("content", newContent);
                result.add(newMsg);
            } else {
                result.add(msg);
            }
        }
        return found ? result : null;
    }

    /**
     * 原地替换 image_url 内容块为文本占位符。
     *
     * @param messages 消息列表（原地修改）
     * @return true 表示找到并替换了图片
     */
    // 对标 Python base.py _strip_image_content_inplace()
    @SuppressWarnings("unchecked")
    public static boolean stripImageContentInplace(List<Map<String, Object>> messages) {
        boolean found = false;
        for (Map<String, Object> msg : messages) {
            Object content = msg.get("content");
            if (content instanceof List<?> blocks) {
                List<Object> mutableBlocks = (List<Object>) blocks;
                for (int i = 0; i < mutableBlocks.size(); i++) {
                    Object b = mutableBlocks.get(i);
                    if (b instanceof Map<?, ?> bm) {
                        Map<String, Object> block = (Map<String, Object>) bm;
                        if ("image_url".equals(block.get("type"))) {
                            Map<String, Object> meta = block.get("_meta") instanceof Map<?, ?> m
                                    ? (Map<String, Object>) m : Map.of();
                            String path = (String) meta.getOrDefault("path", "");
                            String placeholder = !path.isEmpty()
                                    ? "[image: " + path + "]"
                                    : "[image omitted]";
                            mutableBlocks.set(i, Map.of("type", "text", "text", placeholder));
                            found = true;
                        }
                    }
                }
            }
        }
        return found;
    }

    /**
     * 仅保留 provider 安全的消息 key 并规范化 assistant content。
     *
     * @param messages    消息列表
     * @param allowedKeys 允许保留的 key 集合
     * @return 清理后的消息列表
     */
    // 对标 Python base.py _sanitize_request_messages()
    public static List<Map<String, Object>> sanitizeRequestMessages(
            List<Map<String, Object>> messages, Set<String> allowedKeys) {
        List<Map<String, Object>> sanitized = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            Map<String, Object> clean = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : msg.entrySet()) {
                if (allowedKeys.contains(e.getKey())) {
                    clean.put(e.getKey(), e.getValue());
                }
            }
            if ("assistant".equals(clean.get("role")) && !clean.containsKey("content")) {
                clean.put("content", null);
            }
            sanitized.add(clean);
        }
        return sanitized;
    }

    // =========================================================================
    // 工具辅助
    // =========================================================================

    /**
     * 从 OpenAI 或 Anthropic 风格的工具 schema 中提取工具名。
     *
     * @param tool 工具定义 Map
     * @return 工具名，未找到返回 ""
     */
    // 对标 Python base.py _tool_name()
    @SuppressWarnings("unchecked")
    public static String toolName(Map<String, Object> tool) {
        Object name = tool.get("name");
        if (name instanceof String s) return s;
        Object fn = tool.get("function");
        if (fn instanceof Map<?, ?> m) {
            Object fname = ((Map<String, Object>) m).get("name");
            if (fname instanceof String s) return s;
        }
        return "";
    }

    /**
     * 返回 cache 标记索引：builtin/MCP 边界索引和尾部索引。
     * 用于 Anthropic prompt caching 的 cache_control 断点标记。
     *
     * @param tools 工具定义列表
     * @return 索引列表（最多 2 个：last_builtin_idx 和 tail_idx）
     */
    // 对标 Python base.py _tool_cache_marker_indices()
    public static List<Integer> toolCacheMarkerIndices(List<Map<String, Object>> tools) {
        if (tools == null || tools.isEmpty()) return List.of();

        int tailIdx = tools.size() - 1;
        Integer lastBuiltinIdx = null;
        for (int i = tailIdx; i >= 0; i--) {
            if (!toolName(tools.get(i)).startsWith("mcp_")) {
                lastBuiltinIdx = i;
                break;
            }
        }

        List<Integer> result = new ArrayList<>();
        for (Integer idx : new Integer[]{lastBuiltinIdx, tailIdx}) {
            if (idx != null && !result.contains(idx)) result.add(idx);
        }
        return result;
    }

    // —— 内部工具方法 ——

    private static boolean hasToolCalls(Map<String, Object> msg) {
        return msg.get("tool_calls") instanceof List<?> l && !l.isEmpty();
    }

    private static String truncate(String content, int maxLen) {
        if (content == null) return "";
        return content.length() <= maxLen ? content : content.substring(0, maxLen);
    }
}
