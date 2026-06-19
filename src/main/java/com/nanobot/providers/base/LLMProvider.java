package com.nanobot.providers.base;

import com.nanobot.config.GenerationSettings;
import com.nanobot.providers.ProviderSpec;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM provider 抽象基类，定义了 chat/stream/重试的核心框架。
 * 对应 Python LLMProvider（providers/base.py）。
 *
 * <p>子类只需实现 chat() 和 getDefaultModel()，重试逻辑由基类的
 * runWithRetry() 统一处理。支持标准重试和持久重试两种模式。</p>
 */
public abstract class LLMProvider {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    /** API 密钥 */
    public String apiKey;
    /** API 基地址 */
    public String apiBase;
    /** 生成参数（温度、最大 token 等） */
    public GenerationSettings generation = new GenerationSettings(0.7, 4096, null);
    /** 是否支持进度增量（流式） */
    public boolean supportsProgressDeltas;

    // 重试常量 — 对应 Python 类属性
    /** 标准重试延迟序列（秒） */
    static final List<Double> CHAT_RETRY_DELAYS = List.of(1.0, 2.0, 4.0);
    /** 持久重试最大延迟（秒） */
    static final double PERSISTENT_MAX_DELAY = 60;
    /** 持久重试相同错误上限 */
    static final int PERSISTENT_IDENTICAL_ERROR_LIMIT = 10;
    /** 重试心跳间隔（秒） */
    static final int RETRY_HEARTBEAT_CHUNK = 30;

    /** 瞬态错误标记（匹配错误文本） */
    static final List<String> TRANSIENT_ERROR_MARKERS = List.of(
            "429", "rate limit", "500", "502", "503", "504",
            "overloaded", "timeout", "timed out", "connection",
            "server error", "temporarily unavailable",
            "速率限制", "访问量过大"
    );

    /** 可重试 HTTP 状态码 */
    public static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(408, 409, 429);
    /** 瞬态错误种类 */
    static final Set<String> TRANSIENT_ERROR_KINDS = Set.of("timeout", "connection");

    /** 不可重试的 429 错误 token（配额耗尽等） */
    static final Set<String> NON_RETRYABLE_429_ERROR_TOKENS = Set.of(
            "insufficient_quota", "quota_exceeded", "quota_exhausted",
            "billing_hard_limit_reached", "insufficient_balance",
            "credit_balance_too_low", "billing_not_active", "payment_required"
    );

    /** 可重试的 429 错误 token（速率限制等） */
    static final Set<String> RETRYABLE_429_ERROR_TOKENS = Set.of(
            "rate_limit_exceeded", "rate_limit_error", "too_many_requests",
            "request_limit_exceeded", "requests_limit_exceeded", "overloaded_error"
    );

    /** 不可重试的 429 文本标记 */
    static final List<String> NON_RETRYABLE_429_TEXT_MARKERS = List.of(
            "insufficient_quota", "insufficient quota", "quota exceeded",
            "quota exhausted", "billing hard limit", "billing_hard_limit_reached",
            "billing not active", "insufficient balance", "insufficient_balance",
            "credit balance too low", "payment required", "out of credits",
            "out of quota", "exceeded your current quota"
    );

    /** 可重试的 429 文本标记 */
    static final List<String> RETRYABLE_429_TEXT_MARKERS = List.of(
            "rate limit", "rate_limit", "too many requests", "retry after",
            "try again in", "temporarily unavailable", "overloaded",
            "concurrency limit", "速率限制"
    );

    protected LLMProvider(@Nullable String apiKey, @Nullable String apiBase) {
        this.apiKey = apiKey;
        this.apiBase = apiBase;
    }

    // === 抽象方法 ===

    /** 发送聊天请求。子类必须实现。
     *  对应 Python LLMProvider.chat()。 */
    public abstract LLMResponse chat(
            List<Map<String, Object>> messages,
            @Nullable List<Map<String, Object>> tools,
            @Nullable String model,
            int maxTokens,
            double temperature,
            @Nullable String reasoningEffort,
            @Nullable Object toolChoice
    ) throws Exception;

    /** 获取默认模型名称。子类必须实现。
     *  对应 Python LLMProvider.get_default_model()。 */
    public abstract String getDefaultModel();

    // === Stream：默认降级到 chat ===

    /**
     * 流式聊天。默认实现降级到非流式 chat()。
     * 对应 Python chat_stream()。
     */
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
        LLMResponse response = chat(messages, tools, model, maxTokens, temperature,
                reasoningEffort, toolChoice);
        if (onContentDelta != null && response.content() != null) {
            onContentDelta.accept(response.content());
        }
        return response;
    }

    // === 安全包装器 ===

    /** 安全调用 chat()，异常转为 LLMResponse.error。
     *  对应 Python _safe_chat()。 */
    protected LLMResponse safeChat(Map<String, Object> kwargs) throws InterruptedException {
        try {
            return chat(
                    (List<Map<String, Object>>) kwargs.get("messages"),
                    (List<Map<String, Object>>) kwargs.get("tools"),
                    (String) kwargs.get("model"),
                    (int) kwargs.get("max_tokens"),
                    (double) kwargs.get("temperature"),
                    (String) kwargs.get("reasoning_effort"),
                    kwargs.get("tool_choice")
            );
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            return new LLMResponse("Error calling LLM: " + e, List.of(), "error",
                    Map.of(), null, null, null, null, null, null, null, null, null);
        }
    }

    /** 安全调用 chatStream()，异常转为 LLMResponse.error。
     *  对应 Python _safe_chat_stream()。 */
    protected LLMResponse safeChatStream(Map<String, Object> kwargs) throws Exception {
        try {
            return chatStream(
                    (List<Map<String, Object>>) kwargs.get("messages"),
                    (List<Map<String, Object>>) kwargs.get("tools"),
                    (String) kwargs.get("model"),
                    (int) kwargs.get("max_tokens"),
                    (double) kwargs.get("temperature"),
                    (String) kwargs.get("reasoning_effort"),
                    kwargs.get("tool_choice"),
                    (ThrowingConsumer<String>) kwargs.get("on_content_delta"),
                    (ThrowingConsumer<String>) kwargs.get("on_thinking_delta"),
                    (ThrowingConsumer<Map<String, Object>>) kwargs.get("on_tool_call_delta")
            );
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            return new LLMResponse("Error calling LLM: " + e, List.of(), "error",
                    Map.of(), null, null, null, null, null, null, null, null, null);
        }
    }

    // === chat_with_retry / chat_stream_with_retry ===

    /**
     * 带重试的 chat 调用。
     * 对应 Python chat_with_retry()。
     */
    public LLMResponse chatWithRetry(
            List<Map<String, Object>> messages,
            @Nullable List<Map<String, Object>> tools,
            @Nullable String model,
            @Nullable Integer maxTokens,
            @Nullable Double temperature,
            @Nullable String reasoningEffort,
            @Nullable Object toolChoice,
            String retryMode,
            @Nullable ThrowingConsumer<String> onRetryWait
    ) throws Exception {
        if (maxTokens == null) maxTokens = generation.maxTokens();
        if (temperature == null) temperature = generation.temperature();
        if (reasoningEffort == null) reasoningEffort = generation.reasoningEffort();

        Map<String, Object> kw = new LinkedHashMap<>();
        kw.put("messages", messages);
        kw.put("tools", tools);
        kw.put("model", model);
        kw.put("max_tokens", maxTokens);
        kw.put("temperature", temperature);
        kw.put("reasoning_effort", reasoningEffort);
        kw.put("tool_choice", toolChoice);

        return runWithRetry(this::safeChat, kw, messages, retryMode, onRetryWait,
                null, null);
    }

    /**
     * 带重试的流式 chat 调用。
     * 对应 Python chat_stream_with_retry()。
     */
    public LLMResponse chatStreamWithRetry(
            List<Map<String, Object>> messages,
            @Nullable List<Map<String, Object>> tools,
            @Nullable String model,
            @Nullable Integer maxTokens,
            @Nullable Double temperature,
            @Nullable String reasoningEffort,
            @Nullable Object toolChoice,
            @Nullable ThrowingConsumer<String> onContentDelta,
            @Nullable ThrowingConsumer<String> onThinkingDelta,
            @Nullable ThrowingConsumer<Map<String, Object>> onToolCallDelta,
            @Nullable Runnable onStreamRecover,
            String retryMode,
            @Nullable ThrowingConsumer<String> onRetryWait
    ) throws Exception {
        if (maxTokens == null) maxTokens = generation.maxTokens();
        if (temperature == null) temperature = generation.temperature();
        if (reasoningEffort == null) reasoningEffort = generation.reasoningEffort();

        boolean[] hasStreamedContent = {false};

        ThrowingConsumer<String> trackingDelta = text -> {
            if (text != null && !text.isEmpty()) hasStreamedContent[0] = true;
            if (onContentDelta != null) onContentDelta.accept(text);
        };

        Runnable recoverStream = () -> {
            if (onStreamRecover != null) onStreamRecover.run();
            hasStreamedContent[0] = false;
        };

        Map<String, Object> kw = new LinkedHashMap<>();
        kw.put("messages", messages);
        kw.put("tools", tools);
        kw.put("model", model);
        kw.put("max_tokens", maxTokens);
        kw.put("temperature", temperature);
        kw.put("reasoning_effort", reasoningEffort);
        kw.put("tool_choice", toolChoice);
        kw.put("on_content_delta", onContentDelta != null ? trackingDelta : null);
        kw.put("on_thinking_delta", onThinkingDelta);
        kw.put("on_tool_call_delta", onToolCallDelta);
        if (onStreamRecover != null) {
            kw.put("on_stream_recover", recoverStream);
        }

        return runWithRetry(this::safeChatStream, kw, messages, retryMode, onRetryWait,
                () -> !hasStreamedContent[0], onStreamRecover != null ? recoverStream : null);
    }

    // === 核心重试循环 ===

    /** 聊天调用函数式接口 */
    @FunctionalInterface
    protected interface ChatCall {
        LLMResponse execute(Map<String, Object> kw) throws Exception;
    }

    /** 布尔值提供者接口 */
    @FunctionalInterface
    protected interface BooleanSupplier {
        boolean getAsBoolean();
    }

    /**
     * 核心重试循环，处理瞬态错误重试、图片剥离、流恢复等逻辑。
     * 对应 Python _run_with_retry()。
     */
    @SuppressWarnings("unchecked")
    protected LLMResponse runWithRetry(
            ChatCall call,
            Map<String, Object> kw,
            List<Map<String, Object>> originalMessages,
            String retryMode,
            @Nullable ThrowingConsumer<String> onRetryWait,
            @Nullable BooleanSupplier shouldRetryGuard,
            @Nullable Runnable onStreamRecover
    ) throws InterruptedException {
        int attempt = 0;
        List<Double> delays = new ArrayList<>(CHAT_RETRY_DELAYS);
        boolean persistent = "persistent".equals(retryMode);
        LLMResponse lastResponse = null;
        String lastErrorKey = null;
        int identicalErrorCount = 0;

        while (true) {
            attempt++;
            LLMResponse response;
            try {
                response = call.execute(kw);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                response = new LLMResponse("Error calling LLM: " + e, List.of(), "error",
                        Map.of(), null, null, null, null, null, null, null, null, null);
            }

            if (!"error".equals(response.finishReason())) {
                return response;
            }
            lastResponse = response;

            // 流式 guard 检查
            if (shouldRetryGuard != null && !shouldRetryGuard.getAsBoolean()) {
                boolean isTimeout = "timeout".equalsIgnoreCase(
                        response.errorKind() != null ? response.errorKind() : "");
                if (isTimeout) {
                    if (onStreamRecover != null) {
                        log.warn("LLM stream stalled after content was emitted; starting a new stream segment and retrying");
                        onStreamRecover.run();
                    } else {
                        log.warn("LLM stream stalled after content was emitted; suppressing delta callbacks and retrying");
                        kw.put("on_content_delta", null);
                        kw.put("on_thinking_delta", null);
                        kw.put("on_tool_call_delta", null);
                        shouldRetryGuard = null;
                    }
                } else {
                    log.warn("LLM stream failed after content was emitted; skipping retry");
                    return response;
                }
            }

            // 相同错误计数
            String errorKey = response.content() != null
                    ? response.content().strip().toLowerCase() : null;
            if (errorKey != null && !errorKey.isEmpty() && errorKey.equals(lastErrorKey)) {
                identicalErrorCount++;
            } else {
                lastErrorKey = errorKey;
                identicalErrorCount = (errorKey != null && !errorKey.isEmpty()) ? 1 : 0;
            }

            // 非瞬态错误 → 尝试剥离图片后重试
            if (!isTransientResponse(response)) {
                List<Map<String, Object>> stripped = MessageSanitizer.stripImageContent(originalMessages);
                if (stripped != null && !stripped.equals(kw.get("messages"))) {
                    log.warn("Non-transient LLM error with image content, retrying without images");
                    Map<String, Object> retryKw = new LinkedHashMap<>(kw);
                    retryKw.put("messages", stripped);
                    LLMResponse result;
                    try {
                        result = call.execute(retryKw);
                    } catch (Exception e) {
                        result = new LLMResponse("Error calling LLM: " + e, List.of(), "error",
                                Map.of(), null, null, null, null, null, null, null, null, null);
                    }
                    if (!"error".equals(result.finishReason())) {
                        MessageSanitizer.stripImageContentInPlace(originalMessages);
                    }
                    return result;
                }
                return response;
            }

            // 持久重试相同错误上限
            if (persistent && identicalErrorCount >= PERSISTENT_IDENTICAL_ERROR_LIMIT) {
                log.warn("Stopping persistent retry after {} identical transient errors: {}",
                        identicalErrorCount, limitContent(response));
                if (onRetryWait != null) {
                    try {
                        onRetryWait.accept("Persistent retry stopped after " + identicalErrorCount + " identical errors.");
                    } catch (Exception ignored) {}
                }
                return response;
            }

            // 标准模式重试上限
            if (!persistent && attempt > delays.size()) {
                log.warn("LLM request failed after {} retries, giving up: {}",
                        attempt, limitContent(response));
                if (onRetryWait != null) {
                    try {
                        onRetryWait.accept("Model request failed after " + attempt + " retries, giving up.");
                    } catch (Exception ignored) {}
                }
                break;
            }

            // 计算延迟
            double baseDelay = delays.get(Math.min(attempt - 1, delays.size() - 1));
            Double extracted = extractRetryAfterFromResponse(response);
            double delay = extracted != null ? extracted : baseDelay;
            if (persistent) delay = Math.min(delay, PERSISTENT_MAX_DELAY);

            log.warn("LLM transient error (attempt {}{}{}), retrying in {}s: {}",
                    attempt,
                    persistent && attempt > delays.size() ? "+" : "",
                    persistent && attempt > delays.size() ? "" : "/" + delays.size(),
                    Math.round(delay),
                    limitContent(response));

            sleepWithHeartbeat(delay, attempt, persistent, onRetryWait);
        }

        if (lastResponse != null) return lastResponse;
        try {
            return call.execute(kw);
        } catch (Exception e) {
            return new LLMResponse("Error calling LLM: " + e, List.of(), "error",
                    Map.of(), null, null, null, null, null, null, null, null, null);
        }
    }

    /** 截断响应内容用于日志 */
    private String limitContent(LLMResponse r) {
        String c = r.content() != null ? r.content() : "";
        return c.length() > 120 ? c.substring(0, 120).toLowerCase() : c.toLowerCase();
    }

    // === 心跳等待 ===

    /** 带心跳的重试等待，每 RETRY_HEARTBEAT_CHUNK 秒回调一次 onRetryWait。
     *  对应 Python _sleep_with_heartbeat()。 */
    protected void sleepWithHeartbeat(
            double delay,
            int attempt,
            boolean persistent,
            @Nullable ThrowingConsumer<String> onRetryWait
    ) throws InterruptedException {
        double remaining = Math.max(0.0, delay);
        while (remaining > 0) {
            if (onRetryWait != null) {
                String kind = persistent ? "persistent retry" : "retry";
                try {
                    onRetryWait.accept("Model request failed, " + kind + " in "
                            + Math.max(1, (int) Math.round(remaining)) + "s (attempt " + attempt + ").");
                } catch (Exception ignored) {}
            }
            double chunk = Math.min(remaining, RETRY_HEARTBEAT_CHUNK);
            Thread.sleep((long) (chunk * 1000));
            remaining -= chunk;
        }
    }

    // === 瞬态/可重试错误分类 ===

    /** 判断错误文本是否为瞬态错误。
     *  对应 Python is_transient_error()。 */
    public static boolean isTransientError(@Nullable String content) {
        String err = (content != null ? content : "").toLowerCase();
        return TRANSIENT_ERROR_MARKERS.stream().anyMatch(err::contains);
    }

    /** 判断响应是否为瞬态错误。
     *  对应 Python is_transient_response()。 */
    public static boolean isTransientResponse(LLMResponse response) {
        if (response.errorShouldRetry() != null) {
            return response.errorShouldRetry();
        }
        if (response.errorStatusCode() != null) {
            int status = response.errorStatusCode();
            if (status == 429) return isRetryable429Response(response);
            if (RETRYABLE_STATUS_CODES.contains(status) || status >= 500) return true;
        }
        String kind = response.errorKind() != null ? response.errorKind().strip().toLowerCase() : "";
        if (TRANSIENT_ERROR_KINDS.contains(kind)) return true;
        return isTransientError(response.content());
    }

    /** 判断是否为欠费/配额耗尽响应。
     *  对应 Python is_arrearage_response()。 */
    public static boolean isArrearageResponse(LLMResponse response) {
        if (response.errorStatusCode() != null && response.errorStatusCode() == 402) return true;

        String typeToken = normalizeErrorToken(response.errorType());
        String codeToken = normalizeErrorToken(response.errorCode());
        if (typeToken != null && NON_RETRYABLE_429_ERROR_TOKENS.contains(typeToken)) return true;
        if (codeToken != null && NON_RETRYABLE_429_ERROR_TOKENS.contains(codeToken)) return true;

        String content = response.content() != null ? response.content().toLowerCase() : "";
        return NON_RETRYABLE_429_TEXT_MARKERS.stream().anyMatch(content::contains);
    }

    /** 规范化错误 token */
    static String normalizeErrorToken(Object value) {
        if (value == null) return null;
        String token = value.toString().strip().toLowerCase();
        return token.isEmpty() ? null : token;
    }

    /** 从错误 payload 提取 type 和 code。
     *  对应 Python _extract_error_type_code()。 */
    @SuppressWarnings("unchecked")
    public static String[] extractErrorTypeCode(Object payload) {
        Map<String, Object> data = null;
        if (payload instanceof Map) {
            data = (Map<String, Object>) payload;
        } else if (payload instanceof String text) {
            text = text.strip();
            if (!text.isEmpty()) {
                try {
                    Object parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(text, Map.class);
                    if (parsed instanceof Map) data = (Map<String, Object>) parsed;
                } catch (Exception ignored) {}
            }
        }
        if (data == null) return new String[]{null, null};

        Object errorObj = data.get("error");
        String typeValue = String.valueOf(data.getOrDefault("type", "null"));
        String codeValue = String.valueOf(data.getOrDefault("code", "null"));
        if (errorObj instanceof Map err) {
            if (err.get("type") != null) typeValue = String.valueOf(err.get("type"));
            if (err.get("code") != null) codeValue = String.valueOf(err.get("code"));
        }
        return new String[]{normalizeErrorToken(typeValue), normalizeErrorToken(codeValue)};
    }

    /** 判断 429 响应是否可重试（排除配额耗尽类）。
     *  对应 Python _is_retryable_429_response()。 */
    public static boolean isRetryable429Response(LLMResponse response) {
        String typeToken = normalizeErrorToken(response.errorType());
        String codeToken = normalizeErrorToken(response.errorCode());
        Set<String> semanticTokens = new HashSet<>();
        if (typeToken != null) semanticTokens.add(typeToken);
        if (codeToken != null) semanticTokens.add(codeToken);

        for (String token : semanticTokens) {
            if (NON_RETRYABLE_429_ERROR_TOKENS.contains(token)) return false;
        }

        String content = response.content() != null ? response.content().toLowerCase() : "";
        if (NON_RETRYABLE_429_TEXT_MARKERS.stream().anyMatch(content::contains)) return false;

        for (String token : semanticTokens) {
            if (RETRYABLE_429_ERROR_TOKENS.contains(token)) return true;
        }
        if (RETRYABLE_429_TEXT_MARKERS.stream().anyMatch(content::contains)) return true;

        // 未知 429 默认等待并重试
        return true;
    }

    // === Retry-after 提取 ===

    /** 重试等待时间匹配正则 */
    static final Pattern[] RETRY_AFTER_PATTERNS = {
            Pattern.compile("retry after\\s+(\\d+(?:\\.\\d+)?)\\s*(ms|milliseconds|s|sec|secs|seconds|m|min|minutes)?"),
            Pattern.compile("try again in\\s+(\\d+(?:\\.\\d+)?)\\s*(ms|milliseconds|s|sec|secs|seconds|m|min|minutes)"),
            Pattern.compile("wait\\s+(\\d+(?:\\.\\d+)?)\\s*(ms|milliseconds|s|sec|secs|seconds|m|min|minutes)\\s*before retry"),
            Pattern.compile("retry[_-]?after[\"'\\s:=]+(\\d+(?:\\.\\d+)?)")
    };

    /** 从错误文本中提取重试等待时间。
     *  对应 Python extract_retry_after()。 */
    public static Double extractRetryAfter(@Nullable String content) {
        if (content == null) return null;
        String text = content.toLowerCase();
        for (int i = 0; i < RETRY_AFTER_PATTERNS.length; i++) {
            Matcher m = RETRY_AFTER_PATTERNS[i].matcher(text);
            if (m.find()) {
                double value = Double.parseDouble(m.group(1));
                String unit = i < 3 ? m.group(2) : "s";
                return toRetrySeconds(value, unit);
            }
        }
        return null;
    }

    /** 将重试时间值转为秒 */
    static double toRetrySeconds(double value, @Nullable String unit) {
        String normalized = (unit != null ? unit : "s").toLowerCase();
        if ("ms".equals(normalized) || "milliseconds".equals(normalized)) {
            return Math.max(0.1, value / 1000.0);
        }
        if ("m".equals(normalized) || "min".equals(normalized) || "minutes".equals(normalized)) {
            return Math.max(0.1, value * 60.0);
        }
        return Math.max(0.1, value);
    }

    /** 从 HTTP headers 中提取重试等待时间。
     *  对应 Python extract_retry_after_from_headers()。 */
    @SuppressWarnings("unchecked")
    public static Double extractRetryAfterFromHeaders(Object headers) {
        if (headers == null) return null;

        try {
            // retry-after-ms header
            Object retryMs = getHeaderValue(headers, "retry-after-ms");
            if (retryMs != null) {
                double value = Double.parseDouble(retryMs.toString()) / 1000.0;
                if (value > 0) return value;
            }
        } catch (Exception ignored) {}

        Object retryAfter = getHeaderValue(headers, "retry-after");
        if (retryAfter == null) return null;

        String text = retryAfter.toString().strip();
        if (text.isEmpty()) return null;

        if (text.matches("\\d+(?:\\.\\d+)?")) {
            return toRetrySeconds(Double.parseDouble(text), "s");
        }

        // 尝试 HTTP 日期解析
        try {
            ZonedDateTime retryAt = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME);
            double remaining = Duration.between(ZonedDateTime.now(ZoneOffset.UTC), retryAt).toSeconds();
            return Math.max(0.1, remaining);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 从 headers map 中按名称（大小写不敏感）获取值 */
    private static Object getHeaderValue(Object headers, String name) {
        if (headers instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (entry.getKey().toString().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    /** 从 LLMResponse 中综合提取重试等待时间。
     *  对应 Python extract_retry_after_from_response()。 */
    public static Double extractRetryAfterFromResponse(LLMResponse response) {
        if (response.errorRetryAfterS() != null && response.errorRetryAfterS() > 0) {
            return response.errorRetryAfterS();
        }
        if (response.retryAfter() != null && response.retryAfter() > 0) {
            return response.retryAfter();
        }
        return extractRetryAfter(response.content());
    }
}
