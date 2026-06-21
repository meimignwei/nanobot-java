# 04 — providers 包：Provider 核心基础设施

**对标 Python：** `nanobot/providers/base.py` (935行), `registry.py` (~300行), `factory.py` (~150行)

## Python 源码分析

### `base.py` — LLM 提供商抽象基类

```
LLMProvider (ABC)
  __init__(api_key, api_base)
    ├── self.api_key, self.api_base
    └── self.generation: GenerationSettings

  抽象方法:
    async chat(messages, tools, model, ...) → LLMResponse
    abstract get_default_model() → str

  具体方法:
    chat_stream(...) → 默认回退到 chat()，子类可覆盖
    chat_with_retry(...) → 对 chat() 包装重试
    chat_stream_with_retry(...) → 对 chat_stream() 包装重试
    _run_with_retry(...) → 核心重试引擎
      - "standard" 模式: 3次重试, 延迟 1s/2s/4s
      - "persistent" 模式: 无限重试至 60s 延迟, 10次相同错误后停止
    _safe_chat(...) / _safe_chat_stream(...) → 异常→error response 包装

  静态/类方法 (静态工具):
    _enforce_role_alternation(messages) → 合并连续同角色、删除尾部assistant
    _sanitize_empty_content(messages) → 修复空内容块
    _strip_image_content(messages) → 用文本替换 image_url
    _sanitize_request_messages(messages, allowed_keys) → 过滤不安全键

  错误分类 (类变量):
    _TRANSIENT_ERROR_MARKERS = ("429","rate limit","500","502","503","504","overloaded",...)
    _NON_RETRYABLE_429_ERROR_TOKENS = ("insufficient_quota","quota_exceeded",...)
    _RETRYABLE_429_ERROR_TOKENS = ("rate_limit_exceeded","too_many_requests",...)

  重试判定方法:
    _is_transient_response(response) → 综合判断 (结构化 error_should_retry → HTTP状态 → error_kind → 文本匹配)
    _is_transient_error(content) → 纯文本模式匹配
    is_arrearage_response(response) → 检查计费/欠费错误 (HTTP 402 / insufficient_quota)
    _is_retryable_429_response(response) → 区分可重试/不可重试的429错误

  延迟提取:
    _extract_retry_after(content) → 正则匹配 "retry after X seconds" 等模式
    _extract_retry_after_from_headers(headers) → Retry-After, Retry-After-Ms, x-should-retry
    _extract_retry_after_from_response(response) → 综合优先级: error_retry_after_s → retry_after → 文本匹配
    _to_retry_seconds(value, unit) → 统一转换为秒
    _sleep_with_heartbeat(delay, attempt, persistent, on_retry_wait) → 分段sleep+心跳回调

LLMResponse dataclass (16个字段):
  content: str | None           → 响应文本
  tool_calls: list[ToolCallRequest] → 工具调用
  finish_reason: str = "stop"   → stop|tool_calls|length|error|content_filter
  usage: dict[str, int]         → prompt_tokens/completion_tokens/total_tokens
  retry_after: float | None     → 提供商要求重试等待秒数
  reasoning_content: str | None → Kimi/DeepSeek-R1/Minimax 推理内容
  thinking_blocks: list[dict]   → Anthropic extended thinking 块
  # 结构化错误元数据 (finish_reason="error" 时):
  error_status_code: int | None
  error_kind: str | None        → "timeout"|"connection"
  error_type: str | None        → 提供商标识, e.g. "insufficient_quota"
  error_code: str | None        → 提供商错误码, e.g. "rate_limit_exceeded"
  error_retry_after_s: float | None
  error_should_retry: bool | None

ToolCallRequest dataclass:
  id: str                       → 工具调用ID (provider-specific)
  name: str                     → 函数名
  arguments: Any                → dict 或 JSON 字符串
  extra_content: dict | None    → Gemini 额外内容
  provider_specific_fields: dict | None
  function_provider_specific_fields: dict | None

GenerationSettings frozen dataclass:
  temperature: float = 0.7
  max_tokens: int = 4096
  reasoning_effort: str | None = None

独立工具函数:
  parse_tool_arguments(arguments) → JSON解析，回退到原始字符串
  tool_arguments_object_for_replay(arguments) → JSON解析 + json_repair修复
  tool_arguments_json_for_replay(arguments) → 返回JSON字符串形式，用于provider历史replay
```

### `registry.py` — 提供商注册表

```
ProviderSpec frozen dataclass (30+字段):
  标识: name, keywords(tuple), env_key, display_name
  后端: backend ("openai_compat"|"anthropic"|"azure_openai"|"bedrock"|"openai_codex"|"github_copilot")
  环境: env_extras(tuple), default_api_base
  探测: detect_by_key_prefix, detect_by_base_keyword
  特性: is_gateway, is_local, is_oauth, is_direct, is_transcription_only
  高级: supports_prompt_caching, thinking_style, gateway_reasoning_style
        model_overrides, strip_model_prefix, supports_max_completion_tokens
        reasoning_as_content

PROVIDERS: tuple[ProviderSpec, ...] → 约30个按优先级排列的规格
  Order: Custom → Azure → Bedrock → Gateways (OpenRouter, HuggingFace...) → 标准提供商 (Anthropic, OpenAI, DeepSeek...) → 本地 (vLLM, Ollama...) → 辅助 (Groq, AssemblyAI)

find_by_name(name) → ProviderSpec | None   (按配置字段名查找)
```

### `factory.py` — 提供商工厂

```
ProviderSnapshot frozen dataclass:
  provider: LLMProvider, model: str, context_window_tokens: int, signature: tuple

make_provider(config, preset_name, preset, model) → LLMProvider
  1. 解析 preset → 获取 provider_name, 查找 ProviderSpec
  2. 检查转录仅提供商 (is_transcription_only)
  3. 验证 API key 要求 (oauth/local/direct 豁免)
  4. 按 backend 字符串分发到具体构造函数:
     "openai_codex" → OpenAICodexProvider
     "azure_openai"  → AzureOpenAIProvider
     "github_copilot" → GitHubCopilotProvider
     "anthropic"     → AnthropicProvider
     "bedrock"       → BedrockProvider
     默认            → OpenAICompatProvider
  5. 设置 provider.generation
  6. 如有 fallback_models → 包装为 FallbackProvider

build_provider_snapshot(config) → ProviderSnapshot
  → 计算 min context_window_tokens (含fallbacks)
  → 计算 provider_signature (版本指纹, 用于检测配置变更)
```

## Java 实现方案

### 1. 核心类型体系

Python 的 `@dataclass` → Java `record`，Python 的抽象类 → Java `abstract class`。

```java
// com.nanobot.providers.GenerationSettings.java
package com.nanobot.providers;

public record GenerationSettings(
    double temperature,
    int maxTokens,
    String reasoningEffort
) {
    public static final GenerationSettings DEFAULT = new GenerationSettings(0.7, 4096, null);

    public GenerationSettings() {
        this(0.7, 4096, null);
    }
}
```

```java
// com.nanobot.providers.ToolCallRequest.java
package com.nanobot.providers;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Objects;

public record ToolCallRequest(
    String id,
    String name,
    Object arguments,               // Map<String, Object> or raw JSON String
    @JsonInclude(JsonInclude.Include.NON_NULL)
    Map<String, Object> extraContent,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("provider_specific_fields")
    Map<String, Object> providerSpecificFields,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("function_provider_specific_fields")
    Map<String, Object> functionProviderSpecificFields
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Serialize to an OpenAI-style tool_call payload.
     */
    public Map<String, Object> toOpenAiToolCall() {
        String argumentsStr;
        if (arguments instanceof String s) {
            argumentsStr = s;
        } else {
            try {
                argumentsStr = MAPPER.writeValueAsString(arguments);
            } catch (JsonProcessingException e) {
                argumentsStr = "{}";
            }
        }
        Map<String, Object> toolCall = new java.util.LinkedHashMap<>();
        toolCall.put("id", id);
        toolCall.put("type", "function");
        Map<String, Object> function = new java.util.LinkedHashMap<>();
        function.put("name", name);
        function.put("arguments", argumentsStr);
        toolCall.put("function", function);

        if (extraContent != null) toolCall.put("extra_content", extraContent);
        if (providerSpecificFields != null) toolCall.put("provider_specific_fields", providerSpecificFields);
        if (functionProviderSpecificFields != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> func = (Map<String, Object>) toolCall.get("function");
            func.put("provider_specific_fields", functionProviderSpecificFields);
        }
        return toolCall;
    }
}
```

```java
// com.nanobot.providers.LLMResponse.java
package com.nanobot.providers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record LLMResponse(
    String content,
    List<ToolCallRequest> toolCalls,
    String finishReason,
    Map<String, Integer> usage,
    Double retryAfter,              // Provider-supplied retry wait in seconds
    String reasoningContent,        // Kimi, DeepSeek-R1, MiMo etc.
    List<Map<String, Object>> thinkingBlocks, // Anthropic extended thinking

    // Structured error metadata (populated when finishReason == "error")
    Integer errorStatusCode,
    String errorKind,               // "timeout", "connection"
    String errorType,               // e.g. "insufficient_quota"
    String errorCode,               // e.g. "rate_limit_exceeded"
    Double errorRetryAfterS,
    Boolean errorShouldRetry
) {
    /** Compact constructor with defaults.
     *  Uses mutable collections to match Python dataclass default_factory=list/dict.
     */
    public LLMResponse {
        if (toolCalls == null) toolCalls = new ArrayList<>();
        if (finishReason == null) finishReason = "stop";
        if (usage == null) usage = new HashMap<>();
    }

    /** Convenience: successful text-only response. */
    public LLMResponse(String content, String finishReason) {
        this(content, new ArrayList<>(), finishReason, new HashMap<>(),
             null, null, null, null, null, null, null, null, null);
    }

    /** Convenience: error response. */
    public LLMResponse(String content, String finishReason, Integer errorStatusCode,
                       String errorKind, String errorType, String errorCode,
                       Double retryAfter, Boolean errorShouldRetry) {
        this(content, new ArrayList<>(), finishReason, new HashMap<>(),
             retryAfter, null, null,
             errorStatusCode, errorKind, errorType, errorCode,
             retryAfter, errorShouldRetry);
    }

    // --- Accessors matching Python semantics ---

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /**
     * Tools execute only when hasToolCalls AND finish_reason is a tool-capable stop.
     * Blocks gateway-injected calls under refusal/content_filter/error.
     */
    public boolean shouldExecuteTools() {
        if (!hasToolCalls()) return false;
        return "tool_calls".equals(finishReason)
            || "function_call".equals(finishReason)
            || "stop".equals(finishReason);
    }

    /**
     * Check if this response represents an error.
     *
     * Note: This convenience method is not present in the Python source;
     * it is added in Java for ergonomic error checks.
     */
    public boolean isError() {
        return "error".equals(finishReason);
    }
}
```

### 2. `LLMProvider.java` 抽象基类

关键转换：Python 的 `async/await` → Java `CompletableFuture`。Python 的 `asyncio.sleep()` → `CompletableFuture.delayedExecutor` 或 `Thread.sleep` inside `CompletableFuture.runAsync`。

```java
// com.nanobot.providers.LLMProvider.java
package com.nanobot.providers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class LLMProvider {

    private static final Logger log = LoggerFactory.getLogger(LLMProvider.class);

    // --- Sentinel for "not set" default detection ---
    private static final Object SENTINEL = new Object();

    // --- Retry configuration ---
    private static final int[] CHAT_RETRY_DELAYS = {1, 2, 4};          // seconds
    private static final int PERSISTENT_MAX_DELAY = 60;                // seconds
    private static final int PERSISTENT_IDENTICAL_ERROR_LIMIT = 10;
    private static final int RETRY_HEARTBEAT_CHUNK = 30;               // seconds per heartbeat notify

    // --- Transient error markers (text-based matching) ---
    private static final Set<String> TRANSIENT_ERROR_MARKERS = Set.of(
        "429", "rate limit", "500", "502", "503", "504",
        "overloaded", "timeout", "timed out", "connection",
        "server error", "temporarily unavailable", "速率限制", "访问量过大"
    );

    // --- Retryable HTTP status codes ---
    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(408, 409, 429);

    // --- Transient error kinds ---
    private static final Set<String> TRANSIENT_ERROR_KINDS = Set.of("timeout", "connection");

    // --- Non-retryable 429 error tokens (billing/quota) ---
    private static final Set<String> NON_RETRYABLE_429_ERROR_TOKENS = Set.of(
        "insufficient_quota", "quota_exceeded", "quota_exhausted",
        "billing_hard_limit_reached", "insufficient_balance",
        "credit_balance_too_low", "billing_not_active", "payment_required"
    );

    // --- Retryable 429 error tokens ---
    private static final Set<String> RETRYABLE_429_ERROR_TOKENS = Set.of(
        "rate_limit_exceeded", "rate_limit_error", "too_many_requests",
        "request_limit_exceeded", "requests_limit_exceeded", "overloaded_error"
    );

    // --- Non-retryable 429 text markers ---
    private static final List<String> NON_RETRYABLE_429_TEXT_MARKERS = List.of(
        "insufficient_quota", "insufficient quota", "quota exceeded",
        "quota exhausted", "billing hard limit", "billing_hard_limit_reached",
        "billing not active", "insufficient balance", "insufficient_balance",
        "credit balance too low", "payment required", "out of credits",
        "out of quota", "exceeded your current quota"
    );

    // --- Retryable 429 text markers ---
    private static final List<String> RETRYABLE_429_TEXT_MARKERS = List.of(
        "rate limit", "rate_limit", "too many requests",
        "retry after", "try again in", "temporarily unavailable",
        "overloaded", "concurrency limit", "速率限制"
    );

    private static final String SYNTHETIC_USER_CONTENT = "(conversation continued)";

    private static final Pattern[] RETRY_AFTER_PATTERNS = {
        Pattern.compile("retry after\\s+(\\d+(?:\\.\\d+)?)\\s*(ms|milliseconds|s|sec|secs|seconds|m|min|minutes)?",
                        Pattern.CASE_INSENSITIVE),
        Pattern.compile("try again in\\s+(\\d+(?:\\.\\d+)?)\\s*(ms|milliseconds|s|sec|secs|seconds|m|min|minutes)",
                        Pattern.CASE_INSENSITIVE),
        Pattern.compile("wait\\s+(\\d+(?:\\.\\d+)?)\\s*(ms|milliseconds|s|sec|secs|seconds|m|min|minutes)\\s*before retry",
                        Pattern.CASE_INSENSITIVE),
        Pattern.compile("retry[_-]?after[\"'\\s:=]+(\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE)
    };

    private static final ObjectMapper JSON = new ObjectMapper();

    // --- Instance fields ---
    protected String apiKey;
    protected String apiBase;
    protected GenerationSettings generation = GenerationSettings.DEFAULT;

    public boolean supportsProgressDeltas = false;

    protected LLMProvider(String apiKey, String apiBase) {
        this.apiKey = apiKey;
        this.apiBase = apiBase;
    }

    // --- Abstract methods ---

    /**
     * Send a chat completion request.
     *
     * python_analog: async def chat(...)
     */
    public abstract CompletableFuture<LLMResponse> chat(
        List<Map<String, Object>> messages,
        List<Map<String, Object>> tools,
        String model,
        int maxTokens,
        double temperature,
        String reasoningEffort,
        Object toolChoice
    );

    /** Get the default model for this provider. */
    public abstract String getDefaultModel();

    // --- Streaming (default falls back to chat) ---

    /**
     * Stream a chat completion.
     *
     * python_analog: async def chat_stream(...)
     */
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
        ToolCallDeltaCallback onToolCallDelta
    ) {
        return chat(messages, tools, model, maxTokens, temperature, reasoningEffort, toolChoice)
            .thenCompose(response -> {
                if (onContentDelta != null && response.content() != null) {
                    return onContentDelta.onDelta(response.content())
                        .thenApply(v -> response);
                }
                return CompletableFuture.completedFuture(response);
            });
    }

    // --- Safe call wrappers (convert exceptions to error responses) ---

    /**
     * Call chat() and convert unexpected exceptions to error responses.
     *
     * python_analog: async def _safe_chat(self, **kwargs)
     */
    protected CompletableFuture<LLMResponse> safeChat(
        List<Map<String, Object>> messages,
        List<Map<String, Object>> tools,
        String model,
        int maxTokens,
        double temperature,
        String reasoningEffort,
        Object toolChoice
    ) {
        return chat(messages, tools, model, maxTokens, temperature, reasoningEffort, toolChoice)
            .exceptionally(exc -> {
                if (exc instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                // Note: Python re-raises asyncio.CancelledError instead of converting
                // to an error response. Java cannot distinguish cancellation in
                // CompletableFuture.exceptionally, so we convert to error response.
                return new LLMResponse("Error calling LLM: " + exc.getMessage(), "error");
            });
    }

    /**
     * Call chatStream() and convert unexpected exceptions to error responses.
     *
     * python_analog: async def _safe_chat_stream(self, **kwargs)
     */
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
        ToolCallDeltaCallback onToolCallDelta
    ) {
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
     * Call chat() with retry on transient provider failures.
     *
     * python_analog: async def chat_with_retry(...)
     */
    public CompletableFuture<LLMResponse> chatWithRetry(
        List<Map<String, Object>> messages,
        List<Map<String, Object>> tools,
        String model,
        Object maxTokensRaw,          // Integer or SENTINEL
        Object temperatureRaw,        // Double or SENTINEL
        Object reasoningEffortRaw,    // String or SENTINEL
        Object toolChoice,
        String retryMode,
        RetryWaitCallback onRetryWait
    ) {
        int maxTokens = resolveMaxTokens(maxTokensRaw);
        double temperature = resolveTemperature(temperatureRaw);
        String reasoningEffort = resolveReasoningEffort(reasoningEffortRaw);

        return runWithRetry(
            (msgs, t, mdl, mt, temp, re, tc) ->
                safeChat(msgs, t, mdl, mt, temp, re, tc),
            messages, tools, model, maxTokens, temperature, reasoningEffort, toolChoice,
            retryMode, onRetryWait, null, null
        );
    }

    /**
     * Call chatStream() with retry on transient provider failures.
     *
     * python_analog: async def chat_stream_with_retry(...)
     */
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
        RetryWaitCallback onRetryWait
    ) {
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
            () -> !hasStreamedContent[0],  // shouldRetryGuard
            onStreamRecover != null ? recoverStream : null
        );
    }

    // --- Default resolution helpers ---

    private int resolveMaxTokens(Object maxTokensRaw) {
        if (maxTokensRaw == SENTINEL || maxTokensRaw == null) {
            return generation.maxTokens();
        }
        return (Integer) maxTokensRaw;
    }

    private double resolveTemperature(Object temperatureRaw) {
        if (temperatureRaw == SENTINEL || temperatureRaw == null) {
            return generation.temperature();
        }
        return (Double) temperatureRaw;
    }

    private String resolveReasoningEffort(Object reasoningEffortRaw) {
        if (reasoningEffortRaw == SENTINEL) {
            return generation.reasoningEffort();
        }
        return (String) reasoningEffortRaw;
    }

    // =========================================================================
    // Core retry engine — runWithRetry
    // =========================================================================

    @FunctionalInterface
    protected interface ChatStreamCallable {
        CompletableFuture<LLMResponse> call(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort,
            Object toolChoice
        );
    }

    @FunctionalInterface
    public interface ContentDeltaCallback {
        /**
         * Receive a text delta chunk.
         *
         * python_analog: Callable[[str], Awaitable[None]] (async callback)
         */
        CompletableFuture<Void> onDelta(String text);
    }

    @FunctionalInterface
    public interface ToolCallDeltaCallback {
        /**
         * Receive a tool-call delta chunk.
         *
         * python_analog: Callable[[dict], Awaitable[None]] (async callback)
         */
        CompletableFuture<Void> onDelta(Map<String, Object> delta);
    }

    @FunctionalInterface
    public interface RetryWaitCallback {
        /**
         * Notify caller before each retry sleep chunk.
         *
         * python_analog: Callable[[str], Awaitable[None]] (async callback)
         */
        CompletableFuture<Void> onWait(String message);
    }

    /**
     * Core retry engine.
     *
     * python_analog: async def _run_with_retry(...)
     */
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
        Supplier<CompletableFuture<Void>> onStreamRecover
    ) {
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
                    return response;  // success
                }

                lastResponse = response;

                // Handle "has streamed content" guard
                if (shouldRetryGuard != null && !shouldRetryGuard.get()) {
                    boolean isTimeout = "timeout".equals(
                        (response.errorKind() != null) ? response.errorKind().toLowerCase() : "");
                    if (isTimeout) {
                        if (onStreamRecover != null) {
                            log.warn("LLM stream stalled after content was emitted; " +
                                     "starting a new stream segment and retrying");
                            onStreamRecover.get().join();
                        } else {
                            log.warn("LLM stream stalled after content was emitted; " +
                                     "suppressing delta callbacks and retrying");
                            shouldRetryGuard = null;
                        }
                    } else {
                        log.warn("LLM stream failed after content was emitted; skipping retry");
                        return response;
                    }
                }

                // Track identical error count for persistent mode
                String errorKey = (response.content() != null)
                    ? response.content().strip().toLowerCase() : null;
                if (errorKey != null && !errorKey.isEmpty() && errorKey.equals(lastErrorKey)) {
                    identicalErrorCount++;
                } else {
                    lastErrorKey = errorKey;
                    identicalErrorCount = (errorKey != null && !errorKey.isEmpty()) ? 1 : 0;
                }

                // Non-transient errors are NOT retried
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

                // Persistent mode: stop after too many identical errors
                if (persistent && identicalErrorCount >= PERSISTENT_IDENTICAL_ERROR_LIMIT) {
                    log.warn("Stopping persistent retry after {} identical transient errors: {}",
                             identicalErrorCount,
                             truncateContent(response.content(), 120));
                    if (onRetryWait != null) {
                        onRetryWait.onWait("Persistent retry stopped after " +
                                           identicalErrorCount + " identical errors.").join();
                    }
                    return response;
                }

                // Standard mode: stop after exhausting delays
                if (!persistent && attempt > CHAT_RETRY_DELAYS.length) {
                    log.warn("LLM request failed after {} retries, giving up: {}",
                             attempt, truncateContent(response.content(), 120));
                    if (onRetryWait != null) {
                        onRetryWait.onWait("Model request failed after " + attempt +
                                           " retries, giving up.").join();
                    }
                    break;
                }

                double baseDelay = CHAT_RETRY_DELAYS[Math.min(attempt - 1, CHAT_RETRY_DELAYS.length - 1)];
                double delay = extractRetryAfterFromResponse(response);
                if (delay <= 0) delay = baseDelay;
                if (persistent) delay = Math.min(delay, PERSISTENT_MAX_DELAY);

                log.warn("LLM transient error (attempt {}{}), retrying in {}s: {}",
                         attempt,
                         (persistent && attempt > CHAT_RETRY_DELAYS.length) ? "+" : "/" + CHAT_RETRY_DELAYS.length,
                         (int) Math.round(delay),
                         truncateContent(response.content(), 120));

                sleepWithHeartbeat(delay, attempt, persistent, onRetryWait).join();
            }

            return lastResponse != null ? lastResponse : call.call(
                originalMessages, tools, model, maxTokens, temperature, reasoningEffort, toolChoice).join();
        });
    }

    // =========================================================================
    // Sleep with heartbeat (Python: _sleep_with_heartbeat / asyncio.sleep)
    // Java: Thread.sleep() on virtual threads — fine since virtual threads
    // are designed for blocking operations.
    // =========================================================================

    /**
     * Sleep with heartbeat notifications.
     *
     * python_analog: async def _sleep_with_heartbeat(...)
     */
    protected CompletableFuture<Void> sleepWithHeartbeat(
        double delay,
        int attempt,
        boolean persistent,
        RetryWaitCallback onRetryWait
    ) {
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
                    } catch (Exception ignored) {}
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
    // Error classification
    // =========================================================================

    /**
     * Check if a response error is transient (should be retried).
     * Prioritizes structured error metadata → HTTP status → error kind → text markers.
     */
    public static boolean isTransientResponse(LLMResponse response) {
        // Structured should_retry wins
        if (response.errorShouldRetry() != null) {
            return response.errorShouldRetry();
        }

        // HTTP status-based
        if (response.errorStatusCode() != null) {
            int status = response.errorStatusCode();
            if (status == 429) {
                return isRetryable429Response(response);
            }
            if (RETRYABLE_STATUS_CODES.contains(status) || status >= 500) {
                return true;
            }
        }

        // Error kind
        String kind = (response.errorKind() != null) ? response.errorKind().strip().toLowerCase() : "";
        if (TRANSIENT_ERROR_KINDS.contains(kind)) {
            return true;
        }

        // Fallback to text matching
        return isTransientError(response.content());
    }

    public static boolean isTransientError(String content) {
        if (content == null) return false;
        String err = content.toLowerCase();
        return TRANSIENT_ERROR_MARKERS.stream().anyMatch(marker -> err.contains(marker.toLowerCase()));
    }

    /**
     * Detect API-key arrearage / quota / billing errors that won't clear on retry.
     */
    public static boolean isArrearageResponse(LLMResponse response) {
        if (response.errorStatusCode() != null && response.errorStatusCode() == 402) {
            return true;
        }

        String typeToken = normalizeErrorToken(response.errorType());
        String codeToken = normalizeErrorToken(response.errorCode());
        if (NON_RETRYABLE_429_ERROR_TOKENS.contains(typeToken) ||
            NON_RETRYABLE_429_ERROR_TOKENS.contains(codeToken)) {
            return true;
        }

        String content = (response.content() != null) ? response.content().toLowerCase() : "";
        return NON_RETRYABLE_429_TEXT_MARKERS.stream().anyMatch(content::contains);
    }

    public static boolean isRetryable429Response(LLMResponse response) {
        String typeToken = normalizeErrorToken(response.errorType());
        String codeToken = normalizeErrorToken(response.errorCode());

        if (NON_RETRYABLE_429_ERROR_TOKENS.contains(typeToken) ||
            NON_RETRYABLE_429_ERROR_TOKENS.contains(codeToken)) {
            return false;
        }

        String content = (response.content() != null) ? response.content().toLowerCase() : "";
        if (NON_RETRYABLE_429_TEXT_MARKERS.stream().anyMatch(content::contains)) {
            return false;
        }

        if (RETRYABLE_429_ERROR_TOKENS.contains(typeToken) ||
            RETRYABLE_429_ERROR_TOKENS.contains(codeToken)) {
            return true;
        }
        if (RETRYABLE_429_TEXT_MARKERS.stream().anyMatch(content::contains)) {
            return true;
        }
        // Unknown 429 defaults to WAIT+retry.
        return true;
    }

    private static String normalizeErrorToken(Object value) {
        if (value == null) return null;
        String token = String.valueOf(value).strip().toLowerCase();
        return token.isEmpty() ? null : token;
    }

    // =========================================================================
    // Extract error type/code from response payload
    // =========================================================================

    /**
     * Extract (error_type, error_code) from a structured error payload.
     * Handles both {"error": {"type": "..."}} and {"type": "..."} shapes.
     */
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
                } catch (JsonProcessingException ignored) {}
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
    // Rate-limit detection from response headers
    // =========================================================================

    /**
     * Extract retry-after seconds from response content text.
     * Pattern matches: "retry after X seconds", "try again in X ms", etc.
     */
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
     * Extract retry-after from HTTP headers.
     * Checks: Retry-After-Ms → Retry-After → HTTP-date value.
     */
    public static Double extractRetryAfterFromHeaders(Map<String, String> headers) {
        if (headers == null) return null;

        // retry-after-ms (milliseconds)
        String retryMs = findHeaderIgnoreCase(headers, "retry-after-ms");
        if (retryMs != null) {
            try {
                double value = Double.parseDouble(retryMs) / 1000.0;
                if (value > 0) return value;
            } catch (NumberFormatException ignored) {}
        }

        // retry-after (seconds or HTTP-date)
        String retryAfter = findHeaderIgnoreCase(headers, "retry-after");
        if (retryAfter == null) return null;

        String text = retryAfter.strip();
        if (text.isEmpty()) return null;

        // Numeric seconds
        if (text.matches("\\d+(?:\\.\\d+)?")) {
            return toRetrySeconds(Double.parseDouble(text), "s");
        }

        // HTTP-date parsing (RFC 2822)
        try {
            Instant retryAt = Instant.parse(text);
            double remaining = (retryAt.toEpochMilli() - System.currentTimeMillis()) / 1000.0;
            return Math.max(0.1, remaining);
        } catch (Exception ignored) {}

        return null;
    }

    /**
     * Extract retry-after from an LLMResponse's structured error metadata.
     * Priority: error_retry_after_s → retry_after → text extraction from content.
     */
    public static Double extractRetryAfterFromResponse(LLMResponse response) {
        if (response.errorRetryAfterS() != null && response.errorRetryAfterS() > 0) {
            return response.errorRetryAfterS();
        }
        if (response.retryAfter() != null && response.retryAfter() > 0) {
            return response.retryAfter();
        }
        return extractRetryAfter(response.content());
    }

    public static double toRetrySeconds(double value, String unit) {
        String normalizedUnit = (unit != null) ? unit.toLowerCase() : "s";
        return switch (normalizedUnit) {
            case "ms", "milliseconds" -> Math.max(0.1, value / 1000.0);
            case "m", "min", "minutes" -> Math.max(0.1, value * 60.0);
            default -> Math.max(0.1, value);  // default: seconds
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
    // Message sanitization (shared utility methods)
    // =========================================================================

    /**
     * Merge consecutive same-role messages and drop trailing assistant messages.
     * Some providers reject requests where the last message is 'assistant'.
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> enforceRoleAlternation(
        List<Map<String, Object>> messages
    ) {
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

        // Drop trailing assistant messages
        Map<String, Object> lastPopped = null;
        while (!merged.isEmpty() && "assistant".equals(merged.get(merged.size() - 1).get("role"))) {
            lastPopped = merged.remove(merged.size() - 1);
        }

        // Recovery: if only system messages remain, convert last popped to user
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

        // Safety net: ensure first non-system message isn't bare assistant
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
     * Sanitize empty content blocks, strip internal _meta fields.
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> sanitizeEmptyContent(
        List<Map<String, Object>> messages
    ) {
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

            // Content is a dict (e.g. Bedrock thinking): wrap in list
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
     * Strip image_url content blocks, replacing with text placeholders.
     * Returns null if no images found (caller can skip retry).
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> stripImageContent(
        List<Map<String, Object>> messages
    ) {
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
     * In-place version: mutates the original message list.
     * Returns true if any images were found and replaced.
     */
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
     * Keep only provider-safe message keys and normalize assistant content.
     */
    public static List<Map<String, Object>> sanitizeRequestMessages(
        List<Map<String, Object>> messages, Set<String> allowedKeys
    ) {
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

    // --- Tool helpers ---

    /**
     * Extract tool name from either OpenAI or Anthropic-style tool schemas.
     */
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
     * Return cache marker indices: builtin/MCP boundary and tail index.
     */
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

    // --- Utility ---

    private static boolean hasToolCalls(Map<String, Object> msg) {
        return msg.get("tool_calls") instanceof List<?> l && !l.isEmpty();
    }

    private static String truncateContent(String content, int maxLen) {
        if (content == null) return "";
        return content.length() <= maxLen ? content : content.substring(0, maxLen);
    }
}
```

### 3. `ProviderSpec.java` record + `ProviderRegistry.java`

```java
// com.nanobot.providers.ProviderSpec.java
package com.nanobot.providers;

import java.util.List;
import java.util.Map;

/**
 * Immutable metadata for one LLM provider.
 * Mirrors Python's frozen dataclass ProviderSpec with 30+ fields.
 */
public record ProviderSpec(
    // Identity
    String name,                        // config field name, e.g. "dashscope"
    List<String> keywords,              // model-name keywords for matching (lowercase)
    String envKey,                      // env var for API key, e.g. "DASHSCOPE_API_KEY"
    String displayName,                 // shown in status display

    // Backend selection
    String backend,                     // "openai_compat" | "anthropic" | "azure_openai" |
                                        //   "openai_codex" | "github_copilot" | "bedrock"

    // Environment extras — pairs of (env_var_name, value_template_with_{api_key})
    List<Map.Entry<String, String>> envExtras,

    // Gateway / local detection
    boolean isGateway,                  // routes any model (OpenRouter, AiHubMix)
    boolean isLocal,                    // local deployment (vLLM, Ollama)
    String detectByKeyPrefix,           // match api_key prefix, e.g. "sk-or-"
    String detectByKeyword,             // match substring in api_base URL
    String defaultApiBase,              // OpenAI-compatible base URL

    // Gateway behavior
    boolean stripModelPrefix,           // strip "provider/" before sending
    boolean supportsMaxCompletionTokens,

    // Per-model parameter overrides: pairs of (model_pattern, param_overrides)
    List<Map.Entry<String, Map<String, Object>>> modelOverrides,

    // Auth mode
    boolean isOauth,                    // OAuth-based (Codex, Copilot)
    boolean isDirect,                   // skip API-key validation
    boolean isTranscriptionOnly,        // cannot serve chat completions

    // Advanced features
    boolean supportsPromptCaching,      // cache_control on content blocks
    String thinkingStyle,               // "" | "thinking_type" | "enable_thinking" | "reasoning_split"
    String gatewayReasoningStyle,       // "reasoning_effort" (OpenRouter)
    boolean reasoningAsContent          // treat "reasoning" field as formal content
) {
    /** Compact constructor with defaults for optional fields. */
    public ProviderSpec {
        if (keywords == null) keywords = List.of();
        if (displayName == null) displayName = "";
        if (backend == null) backend = "openai_compat";
        if (envExtras == null) envExtras = List.of();
        if (defaultApiBase == null) defaultApiBase = "";
        if (detectByKeyPrefix == null) detectByKeyPrefix = "";
        if (detectByKeyword == null) detectByKeyword = "";
        if (modelOverrides == null) modelOverrides = List.of();
        if (thinkingStyle == null) thinkingStyle = "";
        if (gatewayReasoningStyle == null) gatewayReasoningStyle = "";
    }

    public String label() {
        return (displayName != null && !displayName.isEmpty())
            ? displayName
            : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    // --- Builder pattern for readability ---
    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static class Builder {
        private final String name;
        private final List<String> keywords = new ArrayList<>();
        private String envKey = "";
        private String displayName = "";
        private String backend = "openai_compat";
        private final List<Map.Entry<String, String>> envExtras = new ArrayList<>();
        private boolean isGateway = false;
        private boolean isLocal = false;
        private String detectByKeyPrefix = "";
        private String detectByKeyword = "";
        private String defaultApiBase = "";
        private boolean stripModelPrefix = false;
        private boolean supportsMaxCompletionTokens = false;
        private final List<Map.Entry<String, Map<String, Object>>> modelOverrides = new ArrayList<>();
        private boolean isOauth = false;
        private boolean isDirect = false;
        private boolean isTranscriptionOnly = false;
        private boolean supportsPromptCaching = false;
        private String thinkingStyle = "";
        private String gatewayReasoningStyle = "";
        private boolean reasoningAsContent = false;

        public Builder(String name) { this.name = name; }

        public Builder keywords(String... values) { keywords.addAll(List.of(values)); return this; }
        public Builder envKey(String value) { this.envKey = value; return this; }
        public Builder displayName(String value) { this.displayName = value; return this; }
        public Builder backend(String value) { this.backend = value; return this; }
        public Builder envExtra(String envName, String template) {
            envExtras.add(Map.entry(envName, template)); return this;
        }
        public Builder isGateway(boolean value) { this.isGateway = value; return this; }
        public Builder isLocal(boolean value) { this.isLocal = value; return this; }
        public Builder detectByKeyPrefix(String value) { this.detectByKeyPrefix = value; return this; }
        public Builder detectByKeyword(String value) { this.detectByKeyword = value; return this; }
        public Builder defaultApiBase(String value) { this.defaultApiBase = value; return this; }
        public Builder stripModelPrefix(boolean value) { this.stripModelPrefix = value; return this; }
        public Builder supportsMaxCompletionTokens(boolean value) { this.supportsMaxCompletionTokens = value; return this; }
        public Builder modelOverride(String pattern, Map<String, Object> overrides) {
            modelOverrides.add(Map.entry(pattern, overrides)); return this;
        }
        public Builder isOauth(boolean value) { this.isOauth = value; return this; }
        public Builder isDirect(boolean value) { this.isDirect = value; return this; }
        public Builder isTranscriptionOnly(boolean value) { this.isTranscriptionOnly = value; return this; }
        public Builder supportsPromptCaching(boolean value) { this.supportsPromptCaching = value; return this; }
        public Builder thinkingStyle(String value) { this.thinkingStyle = value; return this; }
        public Builder gatewayReasoningStyle(String value) { this.gatewayReasoningStyle = value; return this; }
        public Builder reasoningAsContent(boolean value) { this.reasoningAsContent = value; return this; }

        public ProviderSpec build() {
            return new ProviderSpec(
                name, List.copyOf(keywords), envKey, displayName,
                backend, List.copyOf(envExtras),
                isGateway, isLocal, detectByKeyPrefix, detectByKeyword, defaultApiBase,
                stripModelPrefix, supportsMaxCompletionTokens,
                List.copyOf(modelOverrides),
                isOauth, isDirect, isTranscriptionOnly,
                supportsPromptCaching, thinkingStyle, gatewayReasoningStyle, reasoningAsContent
            );
        }
    }
}
```

```java
// com.nanobot.providers.ProviderRegistry.java
package com.nanobot.providers;

import java.util.*;

/**
 * Static registry of all supported LLM providers.
 * Mirrors Python's PROVIDERS tuple — order = match priority.
 */
public final class ProviderRegistry {

    private ProviderRegistry() {} // utility class

    /** Order = priority. Gateways first. */
    public static final List<ProviderSpec> PROVIDERS = List.of(
        // --- Custom (direct OpenAI-compatible endpoint) ---
        ProviderSpec.builder("custom")
            .displayName("Custom")
            .backend("openai_compat")
            .isDirect(true)
            .build(),

        // --- Azure OpenAI ---
        ProviderSpec.builder("azure_openai")
            .keywords("azure", "azure-openai")
            .displayName("Azure OpenAI")
            .backend("azure_openai")
            .isDirect(true)
            .build(),

        // --- AWS Bedrock ---
        ProviderSpec.builder("bedrock")
            .keywords("bedrock", "anthropic.claude", "amazon.nova",
                      "meta.", "mistral.", "cohere.", "qwen.",
                      "deepseek.", "openai.gpt-oss", "ai21.",
                      "moonshot.", "writer.", "zai.")
            .envKey("AWS_BEARER_TOKEN_BEDROCK")
            .displayName("AWS Bedrock")
            .backend("bedrock")
            .isDirect(true)
            .build(),

        // --- Gateways ---
        // OpenRouter
        ProviderSpec.builder("openrouter")
            .keywords("openrouter")
            .envKey("OPENROUTER_API_KEY")
            .displayName("OpenRouter")
            .backend("openai_compat")
            .isGateway(true)
            .detectByKeyPrefix("sk-or-")
            .detectByKeyword("openrouter")
            .defaultApiBase("https://openrouter.ai/api/v1")
            .supportsPromptCaching(true)
            .gatewayReasoningStyle("reasoning_effort")
            .build(),

        // Hugging Face
        ProviderSpec.builder("huggingface")
            .keywords("huggingface", "hugging-face")
            .envKey("HF_TOKEN")
            .displayName("Hugging Face")
            .backend("openai_compat")
            .isGateway(true)
            .detectByKeyPrefix("hf_")
            .detectByKeyword("huggingface")
            .defaultApiBase("https://router.huggingface.co/v1")
            .build(),

        // SiliconFlow
        ProviderSpec.builder("siliconflow")
            .keywords("siliconflow")
            .envKey("OPENAI_API_KEY")
            .displayName("SiliconFlow")
            .backend("openai_compat")
            .isGateway(true)
            .detectByKeyword("siliconflow")
            .defaultApiBase("https://api.siliconflow.cn/v1")
            .build(),

        // AiHubMix
        ProviderSpec.builder("aihubmix")
            .keywords("aihubmix")
            .envKey("OPENAI_API_KEY")
            .displayName("AiHubMix")
            .backend("openai_compat")
            .isGateway(true)
            .detectByKeyword("aihubmix")
            .defaultApiBase("https://aihubmix.com/v1")
            .stripModelPrefix(true)
            .build(),

        // VolcEngine
        ProviderSpec.builder("volcengine")
            .keywords("volcengine", "volces", "ark")
            .envKey("OPENAI_API_KEY")
            .displayName("VolcEngine")
            .backend("openai_compat")
            .isGateway(true)
            .detectByKeyword("volces")
            .defaultApiBase("https://ark.cn-beijing.volces.com/api/v3")
            .thinkingStyle("thinking_type")
            .supportsMaxCompletionTokens(true)
            .build(),

        // DeepSeek
        ProviderSpec.builder("deepseek")
            .keywords("deepseek")
            .envKey("DEEPSEEK_API_KEY")
            .displayName("DeepSeek")
            .backend("openai_compat")
            .defaultApiBase("https://api.deepseek.com")
            .thinkingStyle("thinking_type")
            .build(),

        // OpenAI
        ProviderSpec.builder("openai")
            .keywords("openai", "gpt")
            .envKey("OPENAI_API_KEY")
            .displayName("OpenAI")
            .backend("openai_compat")
            .supportsMaxCompletionTokens(true)
            .build(),

        // OpenAI Codex
        ProviderSpec.builder("openai_codex")
            .keywords("openai-codex")
            .displayName("OpenAI Codex")
            .backend("openai_codex")
            .detectByKeyword("codex")
            .defaultApiBase("https://chatgpt.com/backend-api")
            .isOauth(true)
            .build(),

        // GitHub Copilot
        ProviderSpec.builder("github_copilot")
            .keywords("github_copilot", "copilot")
            .displayName("Github Copilot")
            .backend("github_copilot")
            .defaultApiBase("https://api.githubcopilot.com")
            .stripModelPrefix(true)
            .isOauth(true)
            .supportsMaxCompletionTokens(true)
            .build(),

        // Anthropic
        ProviderSpec.builder("anthropic")
            .keywords("anthropic", "claude")
            .envKey("ANTHROPIC_API_KEY")
            .displayName("Anthropic")
            .backend("anthropic")
            .supportsPromptCaching(true)
            .build(),

        // Gemini
        ProviderSpec.builder("gemini")
            .keywords("gemini", "gemma")
            .envKey("GEMINI_API_KEY")
            .displayName("Gemini")
            .backend("openai_compat")
            .defaultApiBase("https://generativelanguage.googleapis.com/v1beta/openai/")
            .build(),

        // Zhipu
        ProviderSpec.builder("zhipu")
            .keywords("zhipu", "glm", "zai")
            .envKey("ZAI_API_KEY")
            .displayName("Zhipu AI")
            .backend("openai_compat")
            .envExtra("ZHIPUAI_API_KEY", "{api_key}")
            .defaultApiBase("https://open.bigmodel.cn/api/paas/v4")
            .build(),

        // DashScope (Qwen)
        ProviderSpec.builder("dashscope")
            .keywords("qwen", "dashscope")
            .envKey("DASHSCOPE_API_KEY")
            .displayName("DashScope")
            .backend("openai_compat")
            .defaultApiBase("https://dashscope.aliyuncs.com/compatible-mode/v1")
            .thinkingStyle("enable_thinking")
            .build(),

        // Moonshot (Kimi)
        ProviderSpec.builder("moonshot")
            .keywords("moonshot", "kimi")
            .envKey("MOONSHOT_API_KEY")
            .displayName("Moonshot")
            .backend("openai_compat")
            .defaultApiBase("https://api.moonshot.ai/v1")
            .modelOverride("kimi-k2.5", Map.of("temperature", 1.0))
            .modelOverride("kimi-k2.6", Map.of("temperature", 1.0))
            .build(),

        // MiniMax
        ProviderSpec.builder("minimax")
            .keywords("minimax")
            .envKey("MINIMAX_API_KEY")
            .displayName("MiniMax")
            .backend("openai_compat")
            .defaultApiBase("https://api.minimax.io/v1")
            .thinkingStyle("reasoning_split")
            .build(),

        // MiniMax Anthropic-compatible
        ProviderSpec.builder("minimax_anthropic")
            .keywords("minimax_anthropic")
            .envKey("MINIMAX_API_KEY")
            .displayName("MiniMax (Anthropic)")
            .backend("anthropic")
            .defaultApiBase("https://api.minimax.io/anthropic")
            .build(),

        // Mistral
        ProviderSpec.builder("mistral")
            .keywords("mistral")
            .envKey("MISTRAL_API_KEY")
            .displayName("Mistral")
            .backend("openai_compat")
            .defaultApiBase("https://api.mistral.ai/v1")
            .build(),

        // StepFun
        ProviderSpec.builder("stepfun")
            .keywords("stepfun", "step")
            .envKey("STEPFUN_API_KEY")
            .displayName("Step Fun")
            .backend("openai_compat")
            .defaultApiBase("https://api.stepfun.com/v1")
            .reasoningAsContent(true)
            .build(),

        // --- Local deployment ---
        ProviderSpec.builder("vllm")
            .keywords("vllm")
            .envKey("HOSTED_VLLM_API_KEY")
            .displayName("vLLM")
            .backend("openai_compat")
            .isLocal(true)
            .build(),

        ProviderSpec.builder("ollama")
            .keywords("ollama", "nemotron")
            .envKey("OLLAMA_API_KEY")
            .displayName("Ollama")
            .backend("openai_compat")
            .isLocal(true)
            .detectByKeyword("11434")
            .defaultApiBase("http://localhost:11434/v1")
            .build(),

        ProviderSpec.builder("lm_studio")
            .keywords("lm-studio", "lmstudio", "lm_studio")
            .envKey("LM_STUDIO_API_KEY")
            .displayName("LM Studio")
            .backend("openai_compat")
            .isLocal(true)
            .detectByKeyword("1234")
            .defaultApiBase("http://localhost:1234/v1")
            .build(),

        // --- Auxiliary ---
        ProviderSpec.builder("groq")
            .keywords("groq")
            .envKey("GROQ_API_KEY")
            .displayName("Groq")
            .backend("openai_compat")
            .defaultApiBase("https://api.groq.com/openai/v1")
            .build(),

        ProviderSpec.builder("assemblyai")
            .keywords("assemblyai")
            .envKey("ASSEMBLYAI_API_KEY")
            .displayName("AssemblyAI")
            .backend("openai_compat")
            .defaultApiBase("https://api.assemblyai.com/v2")
            .isTranscriptionOnly(true)
            .build(),

        // Qianfan
        ProviderSpec.builder("qianfan")
            .keywords("qianfan", "ernie")
            .envKey("QIANFAN_API_KEY")
            .displayName("Qianfan")
            .backend("openai_compat")
            .defaultApiBase("https://qianfan.baidubce.com/v2")
            .build()
    );

    /** Mutable list used during bootstrap (adds plugin providers). */
    private static final List<ProviderSpec> REGISTRY = new ArrayList<>(PROVIDERS);

    // --- Lookup helpers ---

    public static Optional<ProviderSpec> findByName(String name) {
        String normalized = toSnake(name.replace("-", "_"));
        for (ProviderSpec spec : REGISTRY) {
            if (spec.name().equals(normalized)) {
                return Optional.of(spec);
            }
        }
        return Optional.empty();
    }

    public static List<ProviderSpec> all() {
        return List.copyOf(REGISTRY);
    }

    /** Register a plugin provider (called at startup). */
    public static synchronized void register(ProviderSpec spec) {
        REGISTRY.add(spec);
    }

    private static String toSnake(String camel) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) result.append('_');
                result.append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}
```

### 4. `ProviderFactory.java` — 按后端字符串分派

```java
// com.nanobot.providers.ProviderFactory.java
package com.nanobot.providers;

import com.nanobot.config.NanobotProperties;
import com.nanobot.config.ProviderProperties;
import com.nanobot.config.ModelPreset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;

/**
 * Creates LLMProvider instances from configuration.
 * Dispatches on ProviderSpec.backend string to concrete constructors.
 */
public class ProviderFactory {

    private static final Logger log = LoggerFactory.getLogger(ProviderFactory.class);

    private final NanobotProperties properties;

    public ProviderFactory(NanobotProperties properties) {
        this.properties = properties;
    }

    /**
     * Create the LLM provider implied by config.
     * When model is given, it overrides the resolved preset model.
     */
    public LLMProvider makeProvider(String presetName, ModelPreset preset, String model) {
        ModelPreset resolved = resolveModelPreset(presetName, preset);

        // Fallback presets for failover wrapping
        List<ModelPreset> fallbackPresets = resolveFallbackPresets(resolved);

        if (fallbackPresets.isEmpty()) {
            return makeProviderCore(presetName, preset, model);
        }

        // Wrap in FallbackProvider
        LLMProvider primary = makeProviderCore(presetName, preset, model);
        return new FallbackProvider(
            primary,
            fallbackPresets,
            fb -> makeProviderCore(presetName, fb, fb.model())  // factory lambda
        );
    }

    /**
     * Create a plain LLMProvider without failover wrapping.
     */
    protected LLMProvider makeProviderCore(String presetName, ModelPreset preset, String model) {
        ModelPreset resolved = resolveModelPreset(presetName, preset);
        String effectiveModel = (model != null) ? model : resolved.model();
        String providerName = properties.getProviderName(effectiveModel, resolved);
        ProviderProperties providerProps = properties.getProvider(effectiveModel, resolved);

        Optional<ProviderSpec> specOpt = ProviderRegistry.findByName(
            providerName != null ? providerName : "");
        ProviderSpec spec = specOpt.orElse(null);

        if (spec != null && spec.isTranscriptionOnly()) {
            throw new IllegalArgumentException(
                "Provider '" + providerName + "' only supports transcription.");
        }

        String backend = (spec != null) ? spec.backend() : "openai_compat";

        // API key validation
        validateApiKey(backend, effectiveModel, providerProps, spec);

        // Dispatch on backend
        LLMProvider provider = switch (backend) {
            case "openai_codex" -> {
                try {
                    Class<?> clz = Class.forName("com.nanobot.providers.impl.OpenAiCodexProvider");
                    yield (LLMProvider) clz.getConstructor(String.class)
                        .newInstance(effectiveModel);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to create OpenAICodexProvider", e);
                }
            }
            case "azure_openai" -> {
                try {
                    Class<?> clz = Class.forName("com.nanobot.providers.impl.AzureOpenAiProvider");
                    String apiKey = (providerProps != null && providerProps.apiKey() != null)
                        ? providerProps.apiKey() : "";
                    String apiBase = (providerProps != null) ? providerProps.apiBase() : "";
                    yield (LLMProvider) clz.getConstructor(String.class, String.class, String.class)
                        .newInstance(apiKey, apiBase, effectiveModel);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to create AzureOpenAIProvider", e);
                }
            }
            case "github_copilot" -> {
                try {
                    Class<?> clz = Class.forName("com.nanobot.providers.impl.GitHubCopilotProvider");
                    yield (LLMProvider) clz.getConstructor(String.class)
                        .newInstance(effectiveModel);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to create GitHubCopilotProvider", e);
                }
            }
            case "anthropic" -> {
                try {
                    Class<?> clz = Class.forName("com.nanobot.providers.impl.AnthropicProvider");
                    String apiKey = (providerProps != null) ? providerProps.apiKey() : null;
                    String apiBase = properties.getApiBase(effectiveModel, resolved);
                    Map<String, String> extraHeaders = (providerProps != null)
                        ? providerProps.extraHeaders() : null;
                    yield (LLMProvider) clz.getConstructor(
                        String.class, String.class, String.class, Map.class
                    ).newInstance(apiKey, apiBase, effectiveModel, extraHeaders);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to create AnthropicProvider", e);
                }
            }
            case "bedrock" -> {
                try {
                    Class<?> clz = Class.forName("com.nanobot.providers.impl.BedrockProvider");
                    String apiKey = (providerProps != null) ? providerProps.apiKey() : null;
                    String apiBase = (providerProps != null) ? providerProps.apiBase() : null;
                    String region = (providerProps instanceof com.nanobot.config.BedrockProviderProperties bp)
                        ? bp.region() : null;
                    String profile = (providerProps instanceof com.nanobot.config.BedrockProviderProperties bp)
                        ? bp.profile() : null;
                    Map<String, Object> extraBody = (providerProps != null)
                        ? providerProps.extraBody() : null;
                    yield (LLMProvider) clz.getConstructor(
                        String.class, String.class, String.class, String.class, String.class, Map.class
                    ).newInstance(apiKey, apiBase, effectiveModel, region, profile, extraBody);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to create BedrockProvider", e);
                }
            }
            default -> {  // "openai_compat"
                try {
                    Class<?> clz = Class.forName("com.nanobot.providers.impl.OpenAiCompatProvider");
                    String apiKey = (providerProps != null) ? providerProps.apiKey() : null;
                    String apiBase = properties.getApiBase(effectiveModel, resolved);
                    Map<String, String> extraHeaders = (providerProps != null)
                        ? providerProps.extraHeaders() : null;
                    Map<String, Object> extraBody = (providerProps != null)
                        ? providerProps.extraBody() : null;
                    String apiType = (providerProps != null
                        && providerProps.apiType() != null
                        && "openai".equals(providerName))
                        ? providerProps.apiType() : "auto";
                    Map<String, String> extraQuery = (providerProps != null)
                        ? providerProps.extraQuery() : null;
                    yield (LLMProvider) clz.getConstructor(
                        String.class, String.class, String.class, Map.class,
                        ProviderSpec.class, Map.class, String.class, Map.class
                    ).newInstance(apiKey, apiBase, effectiveModel, extraHeaders,
                                  spec, extraBody, apiType, extraQuery);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to create OpenAICompatProvider", e);
                }
            }
        };

        // Apply generation settings from preset
        provider.generation = resolved.toGenerationSettings();
        return provider;
    }

    /**
     * Build a provider snapshot: provider + model + context window + fingerprint.
     */
    public ProviderSnapshot buildProviderSnapshot(String presetName, ModelPreset preset) {
        ModelPreset resolved = resolveModelPreset(presetName, preset);
        List<ModelPreset> fallbackPresets = resolveFallbackPresets(resolved);

        int minContextWindow = resolved.contextWindowTokens();
        for (ModelPreset fb : fallbackPresets) {
            minContextWindow = Math.min(minContextWindow, fb.contextWindowTokens());
        }

        LLMProvider provider = makeProvider(presetName, resolved, null);
        Object signature = computeProviderSignature(resolved, fallbackPresets);

        return new ProviderSnapshot(provider, resolved.model(), minContextWindow, signature);
    }

    // --- Private helpers ---

    private ModelPreset resolveModelPreset(String presetName, ModelPreset preset) {
        if (preset != null) return preset;
        return properties.resolvePreset(presetName);
    }

    private List<ModelPreset> resolveFallbackPresets(ModelPreset primary) {
        List<ModelPreset> result = new ArrayList<>();
        for (Object raw : properties.getAgents().getDefaults().fallbackModels()) {
            if (raw instanceof String name) {
                ModelPreset preset = properties.getModelPresets().get(name);
                if (preset != null) result.add(preset);
            } else if (raw instanceof Map<?, ?> m) {
                // inline fallback config
                @SuppressWarnings("unchecked")
                Map<String, Object> inline = (Map<String, Object>) m;
                result.add(new ModelPreset(
                    (String) inline.getOrDefault("model", primary.model()),
                    (String) inline.getOrDefault("provider", null),
                    (Integer) inline.getOrDefault("maxTokens", primary.maxTokens()),
                    (Integer) inline.getOrDefault("contextWindowTokens", primary.contextWindowTokens()),
                    (Double) inline.getOrDefault("temperature", primary.temperature()),
                    (String) inline.getOrDefault("reasoningEffort", primary.reasoningEffort())
                ));
            }
        }
        return result;
    }

    private void validateApiKey(String backend, String model, ProviderProperties pp, ProviderSpec spec) {
        if (backend.equals("openai_compat")
            && !model.startsWith("bedrock/")
            && (pp == null || pp.apiKey() == null)) {
            boolean exempt = (spec != null)
                && (spec.isOauth() || spec.isLocal() || spec.isDirect());
            if (!exempt) {
                throw new IllegalArgumentException(
                    "No API key configured for provider. Set it in config or environment.");
            }
        }
    }

    private Object computeProviderSignature(ModelPreset resolved, List<ModelPreset> fallbacks) {
        // Returns a hashable fingerprint for detecting config changes
        // that affect provider behavior (used for hot-reload).
        List<Object> parts = new ArrayList<>();
        parts.add(resolved.model());
        parts.add(resolved.provider());
        parts.add(resolved.maxTokens());
        parts.add(resolved.temperature());
        parts.add(resolved.reasoningEffort());
        parts.add(resolved.contextWindowTokens());
        for (ModelPreset fb : fallbacks) {
            parts.add(List.of(fb.model(), fb.provider(), fb.maxTokens(),
                              fb.temperature(), fb.reasoningEffort()));
        }
        return parts;
    }
}
```

### 5. `ProviderSnapshot.java` record

```java
// com.nanobot.providers.ProviderSnapshot.java
package com.nanobot.providers;

/**
 * Immutable snapshot of a constructed provider chain.
 * Carries the provider instance, selected model, effective context window,
 * and a configuration fingerprint for detecting changes.
 */
public record ProviderSnapshot(
    LLMProvider provider,
    String model,
    int contextWindowTokens,
    Object signature               // fingerprint for config-change detection
) {}
```

### 6. 独立工具函数 `ToolArguments.java`

```java
// com.nanobot.providers.ToolArguments.java
package com.nanobot.providers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Static utility methods for parsing/repairing tool call arguments.
 * Mirrors Python's parse_tool_arguments, tool_arguments_object_for_replay, etc.
 */
public final class ToolArguments {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolArguments() {}

    /**
     * Parse provider tool arguments without guessing executable parameters.
     * Valid JSON object strings become Maps. Empty strings become empty maps.
     * Malformed JSON and non-object JSON values are preserved as-is so
     * ToolRegistry can reject them before execution.
     */
    public static Object parseToolArguments(Object arguments) {
        if (arguments == null) return Map.of();
        if (!(arguments instanceof String s)) return arguments;

        String stripped = s.strip();
        if (stripped.isEmpty()) return Map.of();

        try {
            Object parsed = JSON.readValue(stripped, Object.class);
            return (parsed == null) ? s : parsed;
        } catch (JsonProcessingException e) {
            return s;  // preserve raw string for rejection by ToolRegistry
        }
    }

    /**
     * Return object-shaped arguments for provider history replay only.
     * May repair malformed JSON — only for shaping existing conversation
     * history, NOT for new tool calls.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toolArgumentsObjectForReplay(Object arguments) {
        if (arguments == null) return Map.of();
        if (arguments instanceof Map<?, ?> m) return (Map<String, Object>) m;
        if (!(arguments instanceof String s)) return Map.of();

        String stripped = s.strip();
        if (stripped.isEmpty()) return Map.of();

        try {
            Object parsed = JSON.readValue(stripped, Object.class);
            return (parsed instanceof Map<?, ?> m) ? (Map<String, Object>) m : Map.of();
        } catch (JsonProcessingException e) {
            return Map.of();  // unresolvable
        }
    }

    public static String toolArgumentsJsonForReplay(Object arguments) {
        try {
            return JSON.writeValueAsString(toolArgumentsObjectForReplay(arguments));
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
```

## 关键设计决策

### 异步 → 虚拟线程

Python 使用 `async/await + asyncio.sleep()` 处理重试延迟。Java 使用虚拟线程（`Executors.newVirtualThreadPerTaskExecutor()`）+ `Thread.sleep()`。虚拟线程在 `Thread.sleep()` 期间会自动让出底层 OS 线程，因此不会消耗平台线程资源。与传统的平台线程不同，数千个虚拟线程可以同时 `Thread.sleep()` 而不会耗尽线程池。

```java
// Python: await asyncio.sleep(2.5)
// Java: Thread.sleep(2500) on a virtual thread — same semantics, zero overhead
```

### 错误分类从 Python 原样移植

所有的错误分类常量（`_TRANSIENT_ERROR_MARKERS`、`_NON_RETRYABLE_429_ERROR_TOKENS` 等）和判断逻辑（`isTransientResponse`、`isArrearageResponse`、`isRetryable429Response`）从 Python 精确移植。这些是运行时行为的核心——更改它们会破坏与数十个提供商的兼容性。

### ProviderSpec 用 Builder 模式而非紧凑构造函数

30+ 个字段的 record 构造函数在调用端会难以阅读。Builder 模式允许命名参数风格，同时保持 record 的不可变性优势不变。

### ProviderRegistry 支持运行时扩展

虽然基础 `PROVIDERS` 列表是 `List.of()`（不可变），但注册表内部维护一个 `REGISTRY` 的 `ArrayList` 副本。这使得插件提供商可以通过 `register()` 在启动时注册，而不需要修改 core providers 列表。

## 验证标准

```bash
cd nanobot-java
# 编译
mvn compile -pl nanobot-providers

# 单元测试: 重试逻辑
mvn test -pl nanobot-providers -Dtest=LLMProviderTest

# 单元测试: 错误分类
mvn test -pl nanobot-providers -Dtest=ErrorClassificationTest

# 单元测试: 提供商注册查找
mvn test -pl nanobot-providers -Dtest=ProviderRegistryTest

# 单元测试: 提供商工厂分派
mvn test -pl nanobot-providers -Dtest=ProviderFactoryTest
```

## 代码量估算

| 文件 | 行数 | 对标 Python |
|------|------|-------------|
| LLMResponse.java (record) | ~90 | LLMResponse dataclass |
| ToolCallRequest.java (record) | ~60 | ToolCallRequest dataclass |
| ToolArguments.java (utility) | ~70 | parse_tool_arguments + helpers |
| GenerationSettings.java (record) | ~20 | GenerationSettings dataclass |
| LLMProvider.java (abstract class) | ~600 | base.py (935行) |
| ProviderSpec.java (record + builder) | ~180 | ProviderSpec dataclass |
| ProviderRegistry.java | ~250 | registry.py PROVIDERS tuple |
| ProviderFactory.java | ~200 | factory.py (150行) |
| ProviderSnapshot.java (record) | ~20 | ProviderSnapshot dataclass |
| **合计** | **~1,490** | |
