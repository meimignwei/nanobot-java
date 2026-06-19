package com.nanobot.providers;

import com.nanobot.providers.base.*;
import com.nanobot.providers.openai_responses.OpenAiResponsesHelper;
import jakarta.annotation.Nullable;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

/**
 * OpenAI Codex Responses Provider.
 * Port of Python OpenAICodexProvider (openai_codex_provider.py, 322 lines).
 *
 * Uses Codex OAuth to call chatgpt.com/backend-api/codex/responses.
 * Always streams (SSE); consumes via {@link OpenAiResponsesHelper#consumeSseIntoState}.
 */
public class OpenAICodexProvider extends LLMProvider {

    static final String DEFAULT_CODEX_URL = "https://chatgpt.com/backend-api/codex/responses";
    static final String DEFAULT_ORIGINATOR = "nanobot";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final String defaultModel;
    private final Supplier<CodexToken> tokenSupplier;

    public OpenAICodexProvider(
            String defaultModel,
            @Nullable Supplier<CodexToken> tokenSupplier
    ) {
        super(null, null);
        this.defaultModel = defaultModel != null ? defaultModel : "openai-codex/gpt-5.1-codex";
        this.tokenSupplier = tokenSupplier;
    }

    // ------------------------------------------------------------------
    // CodexToken
    // ------------------------------------------------------------------

    public record CodexToken(String accountId, String access) {}

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

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
        return callCodex(messages, tools, model, reasoningEffort, toolChoice,
                null, null, null);
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
        return callCodex(messages, tools, model, reasoningEffort, toolChoice,
                onContentDelta, onThinkingDelta, onToolCallDelta);
    }

    @Override
    public String getDefaultModel() {
        return defaultModel;
    }

    // ------------------------------------------------------------------
    // Core: _call_codex
    // ------------------------------------------------------------------

    private LLMResponse callCodex(
            List<Map<String, Object>> messages,
            @Nullable List<Map<String, Object>> tools,
            @Nullable String model,
            @Nullable String reasoningEffort,
            @Nullable Object toolChoice,
            @Nullable ThrowingConsumer<String> onContentDelta,
            @Nullable ThrowingConsumer<String> onThinkingDelta,
            @Nullable ThrowingConsumer<Map<String, Object>> onToolCallDelta
    ) throws Exception {
        String m = model != null ? model : defaultModel;
        Object[] converted = OpenAiResponsesHelper.convertMessages(messages);
        String systemPrompt = (String) converted[0];
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inputItems = (List<Map<String, Object>>) converted[1];

        CodexToken token = tokenSupplier != null ? tokenSupplier.get()
                : new CodexToken("", "");
        if (token.access().isEmpty()) {
            throw new IllegalStateException("Codex OAuth token not available. "
                    + "Provide a tokenSupplier to OpenAICodexProvider.");
        }

        Map<String, String> headers = buildHeaders(token.accountId(), token.access());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", stripModelPrefix(m));
        body.put("store", false);
        body.put("stream", true);
        body.put("instructions", systemPrompt);
        body.put("input", inputItems);
        body.put("text", Map.of("verbosity", "medium"));
        body.put("include", List.of("reasoning.encrypted_content"));
        body.put("prompt_cache_key", promptCacheKey(messages.subList(0, Math.min(2, messages.size()))));
        body.put("tool_choice", toolChoice != null ? toolChoice : "auto");
        body.put("parallel_tool_calls", true);

        Map<String, String> reasoningOptions = buildReasoningOptions(reasoningEffort);
        if (reasoningOptions != null) {
            body.put("reasoning", reasoningOptions);
        }
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", OpenAiResponsesHelper.convertTools(tools));
        }

        try {
            return requestCodex(DEFAULT_CODEX_URL, headers, body, true,
                    onContentDelta, onThinkingDelta, onToolCallDelta);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("CERTIFICATE_VERIFY_FAILED")) {
                return requestCodex(DEFAULT_CODEX_URL, headers, body, false,
                        onContentDelta, onThinkingDelta, onToolCallDelta);
            }
            CodexToken ctk = token; // capture for lambda
            RuntimeErrorResponse errResp = codexErrorResponse(e);
            String excType = e instanceof CodexHTTPError ? "CodexHTTPError" : e.getClass().getSimpleName();
            System.err.println("Codex API request failed: type=" + excType
                    + " kind=" + errResp.errorKind
                    + " retryable=" + errResp.shouldRetry
                    + " status=" + errResp.statusCode
                    + " error_type=" + errResp.errorType
                    + " error_code=" + errResp.errorCode
                    + " retry_after=" + errResp.retryAfter);
            return new LLMResponse(
                    "Error calling Codex (" + excType + "): " + errResp.detail,
                    List.of(), "error", Map.of(),
                    errResp.retryAfter, null, null,
                    errResp.statusCode, errResp.errorKind,
                    errResp.errorType, errResp.errorCode,
                    errResp.retryAfter, errResp.shouldRetry);
        }
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private LLMResponse requestCodex(
            String url,
            Map<String, String> headers,
            Map<String, Object> body,
            boolean verify,
            @Nullable ThrowingConsumer<String> onContentDelta,
            @Nullable ThrowingConsumer<String> onThinkingDelta,
            @Nullable ThrowingConsumer<Map<String, Object>> onToolCallDelta
    ) throws Exception {
        int idleTimeoutS = Integer.parseInt(
                System.getenv().getOrDefault("NANOBOT_STREAM_IDLE_TIMEOUT_S", "90"));

        byte[] json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(body);

        var builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(idleTimeoutS));
        for (var e : headers.entrySet()) {
            builder.header(e.getKey(), e.getValue());
        }
        builder.POST(HttpRequest.BodyPublishers.ofByteArray(json));

        HttpResponse<InputStream> response = HTTP_CLIENT.send(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            byte[] raw = response.body().readAllBytes();
            String text = new String(raw, StandardCharsets.UTF_8);
            String friendly = friendlyError(response.statusCode(), text);
            Double retryAfter = LLMProvider.extractRetryAfterFromHeaders(response.headers());
            String[] typeCode = LLMProvider.extractErrorTypeCode(text);
            throw new CodexHTTPError(friendly,
                    response.statusCode(), retryAfter,
                    typeCode[0], typeCode[1],
                    shouldRetryStatus(response.statusCode(), typeCode[0], typeCode[1], text));
        }

        OpenAiResponsesHelper.SseState state = new OpenAiResponsesHelper.SseState();
        OpenAiResponsesHelper.consumeSseIntoState(response.body(), state,
                onContentDelta, onToolCallDelta, onThinkingDelta);

        return new LLMResponse(
                !state.content.isEmpty() ? state.content : null,
                state.toolCalls,
                state.finishReason,
                state.usage,
                null,
                state.reasoningContent,
                null, null, null, null, null, null, null);
    }

    // ------------------------------------------------------------------
    // Static helpers (port of module-level functions)
    // ------------------------------------------------------------------

    static String stripModelPrefix(String model) {
        if (model.startsWith("openai-codex/") || model.startsWith("openai_codex/")) {
            return model.substring(model.indexOf('/') + 1);
        }
        return model;
    }

    @Nullable
    static Map<String, String> buildReasoningOptions(@Nullable String reasoningEffort) {
        if (reasoningEffort == null) return null;
        if ("none".equalsIgnoreCase(reasoningEffort)) {
            return Map.of("effort", "none");
        }
        Map<String, String> options = new LinkedHashMap<>();
        options.put("summary", "auto");
        options.put("effort", reasoningEffort);
        return options;
    }

    static Map<String, String> buildHeaders(String accountId, String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("chatgpt-account-id", accountId);
        h.put("OpenAI-Beta", "responses=experimental");
        h.put("originator", DEFAULT_ORIGINATOR);
        h.put("User-Agent", "nanobot (java)");
        h.put("accept", "text/event-stream");
        h.put("content-type", "application/json");
        return h;
    }

    static String promptCacheKey(List<Map<String, Object>> messages) {
        try {
            String raw = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(messages);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "nanobot-cache-key";
        }
    }

    static String friendlyError(int statusCode, String raw) {
        if (statusCode == 429) {
            return "ChatGPT usage quota exceeded or rate limit triggered. Please try again later.";
        }
        return "HTTP " + statusCode + ": Codex API request failed";
    }

    // ------------------------------------------------------------------
    // CodexHTTPError
    // ------------------------------------------------------------------

    static class CodexHTTPError extends RuntimeException {
        final int statusCode;
        final Double retryAfter;
        @Nullable final String errorType;
        @Nullable final String errorCode;
        @Nullable final Boolean shouldRetry;

        CodexHTTPError(String message, int statusCode, Double retryAfter,
                       @Nullable String errorType, @Nullable String errorCode,
                       @Nullable Boolean shouldRetry) {
            super(message);
            this.statusCode = statusCode;
            this.retryAfter = retryAfter;
            this.errorType = errorType;
            this.errorCode = errorCode;
            this.shouldRetry = shouldRetry;
        }
    }

    // ------------------------------------------------------------------
    // Error classification
    // ------------------------------------------------------------------

    record RuntimeErrorResponse(
            @Nullable String detail,
            @Nullable Integer statusCode,
            @Nullable String errorKind,
            @Nullable String errorType,
            @Nullable String errorCode,
            @Nullable Double retryAfter,
            @Nullable Boolean shouldRetry
    ) {}

    static RuntimeErrorResponse codexErrorResponse(Exception exc) {
        String detail = exc.getMessage() != null ? exc.getMessage().strip() : "";
        Integer statusCode = null;
        String errorKind = null;
        String defaultDetail = null;
        Boolean shouldRetry = null;

        if (exc instanceof CodexHTTPError ce) {
            errorKind = "http";
            defaultDetail = "HTTP request failed";
            statusCode = ce.statusCode;
            shouldRetry = ce.shouldRetry;
        } else if (exc instanceof java.util.concurrent.TimeoutException
                || exc instanceof java.net.http.HttpTimeoutException) {
            errorKind = "timeout";
            defaultDetail = "timed out waiting for response";
            shouldRetry = shouldRetry == null ? true : shouldRetry;
        } else if (exc instanceof java.io.IOException
                && exc.getMessage() != null
                && (exc.getMessage().contains("protocol") || exc.getMessage().contains("reset"))) {
            errorKind = "connection";
            defaultDetail = "network protocol error while reading response";
            shouldRetry = shouldRetry == null ? true : shouldRetry;
        } else if (exc instanceof java.net.ConnectException
                || exc instanceof java.net.http.HttpConnectTimeoutException) {
            errorKind = "connection";
            defaultDetail = "network connection failed";
            shouldRetry = shouldRetry == null ? true : shouldRetry;
        }

        if (statusCode != null && shouldRetry == null) {
            String errorType = exc instanceof CodexHTTPError ce ? ce.errorType : null;
            String errorCode = exc instanceof CodexHTTPError ce ? ce.errorCode : null;
            String retryContent = (statusCode == 429 && exc instanceof CodexHTTPError) ? null : detail;
            shouldRetry = shouldRetryStatus(statusCode, errorType, errorCode, retryContent);
        }

        detail = !detail.isEmpty() ? detail : (defaultDetail != null ? defaultDetail : "unexpected error");

        Double retryAfter = exc instanceof CodexHTTPError ce ? ce.retryAfter
                : LLMProvider.extractRetryAfter(detail);

        return new RuntimeErrorResponse(
                detail,
                statusCode,
                errorKind,
                exc instanceof CodexHTTPError ce ? ce.errorType : null,
                exc instanceof CodexHTTPError ce ? ce.errorCode : null,
                retryAfter,
                shouldRetry);
    }

    static boolean shouldRetryStatus(int statusCode,
                                      @Nullable String errorType,
                                      @Nullable String errorCode,
                                      @Nullable String content) {
        if (statusCode == 429) {
            return LLMProvider.isRetryable429Response(new LLMResponse(
                    content != null ? content : "",
                    List.of(), "error", Map.of(),
                    null, null, null,
                    statusCode, errorType, errorCode,
                    null, null, null));
        }
        return LLMProvider.RETRYABLE_STATUS_CODES.contains(statusCode) || statusCode >= 500;
    }
}
