package com.nanobot.providers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanobot.providers.base.*;
import com.nanobot.providers.openai_responses.OpenAiResponsesHelper;
import jakarta.annotation.Nullable;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Azure OpenAI provider backed by the Responses API.
 * Full port of Python AzureOpenAIProvider (azure_openai_provider.py, 253 lines).
 *
 * Uses HTTP to call the Responses API directly. AAD auth is supported via
 * the api_key being passed as a bearer token (alternatively, set api_key to
 * blank and provide an AZURE_BEARER_TOKEN env var).
 */
public class AzureOpenAiProvider extends LLMProvider {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double REQUEST_TIMEOUT_S = 120.0;

    private final String defaultModel;
    private final String normalizedApiBase;

    public AzureOpenAiProvider(
            String apiKey,
            @Nullable String apiBase,
            String defaultModel
    ) {
        super(apiKey, apiBase);
        this.defaultModel = defaultModel;
        if (apiBase == null || apiBase.isEmpty()) {
            throw new IllegalArgumentException("Azure OpenAI api_base is required");
        }
        this.normalizedApiBase = apiBase.endsWith("/") ? apiBase : apiBase + "/";
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    static boolean supportsTemperature(String deploymentName, @Nullable String reasoningEffort) {
        if (reasoningEffort != null && !"none".equalsIgnoreCase(reasoningEffort)) {
            return false;
        }
        String name = deploymentName.toLowerCase();
        return !(name.contains("gpt-5") || name.contains("o1") || name.contains("o3") || name.contains("o4"));
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> buildBody(
            List<Map<String, Object>> messages,
            @Nullable List<Map<String, Object>> tools,
            @Nullable String model,
            int maxTokens,
            double temperature,
            @Nullable String reasoningEffort,
            @Nullable Object toolChoice
    ) {
        String deployment = model != null ? model : defaultModel;
        Object[] converted = OpenAiResponsesHelper.convertMessages(
                MessageSanitizer.sanitizeEmptyContent(messages));
        String instructions = (String) converted[0];
        List<Map<String, Object>> inputItems = (List<Map<String, Object>>) converted[1];

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", deployment);
        body.put("instructions", instructions != null && !instructions.isEmpty() ? instructions : null);
        body.put("input", inputItems);
        body.put("max_output_tokens", Math.max(1, maxTokens));
        body.put("store", false);
        body.put("stream", false);

        if (supportsTemperature(deployment, reasoningEffort)) {
            body.put("temperature", temperature);
        }

        if (reasoningEffort != null && !"none".equalsIgnoreCase(reasoningEffort)) {
            body.put("reasoning", Map.of("effort", reasoningEffort));
            body.put("include", List.of("reasoning.encrypted_content"));
        }

        if (tools != null && !tools.isEmpty()) {
            body.put("tools", OpenAiResponsesHelper.convertTools(tools));
            body.put("tool_choice", toolChoice != null ? toolChoice : "auto");
        }

        return body;
    }

    // ------------------------------------------------------------------
    // Error handling
    // ------------------------------------------------------------------

    static LLMResponse handleError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : "Error calling Azure OpenAI: " + e;
        if (msg.length() > 500) msg = msg.substring(0, 500);
        Double retryAfter = LLMProvider.extractRetryAfter(msg);
        return new LLMResponse("Error: " + msg, List.of(), "error", Map.of(),
                retryAfter, null, null, null, null, null, null, retryAfter, null);
    }

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
        Map<String, Object> body = buildBody(messages, tools, model, maxTokens, temperature,
                reasoningEffort, toolChoice);

        try {
            HttpResponse<String> resp = postJson(normalizedApiBase + "openai/v1/responses", body);
            if (resp.statusCode() != 200) {
                return parseError(resp);
            }
            return OpenAiResponsesHelper.parseResponseOutput(
                    MAPPER.readValue(resp.body(), new TypeReference<Map<String, Object>>() {}));
        } catch (Exception e) {
            return handleError(e);
        }
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
        Map<String, Object> body = buildBody(messages, tools, model, maxTokens, temperature,
                reasoningEffort, toolChoice);
        body.put("stream", true);

        try {
            HttpResponse<InputStream> resp = postJsonStream(
                    normalizedApiBase + "openai/v1/responses", body);
            if (resp.statusCode() != 200) {
                String errorBody = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                return parseError(resp.statusCode(), errorBody);
            }

            OpenAiResponsesHelper.SseState state = new OpenAiResponsesHelper.SseState();
            OpenAiResponsesHelper.consumeSseIntoState(
                    resp.body(), state,
                    onContentDelta,
                    onToolCallDelta,
                    onThinkingDelta);

            return new LLMResponse(
                    !state.content.isEmpty() ? state.content : null,
                    state.toolCalls,
                    state.finishReason,
                    state.usage,
                    null,
                    state.reasoningContent,
                    null,
                    null, null, null, null, null, null);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            return handleError(e);
        }
    }

    @Override
    public String getDefaultModel() {
        return defaultModel;
    }

    // ------------------------------------------------------------------
    // HTTP helpers
    // ------------------------------------------------------------------

    private HttpResponse<String> postJson(String url, Map<String, Object> body) throws Exception {
        String json = MAPPER.writeValueAsString(body);
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("x-session-affinity", UUID.randomUUID().toString().replace("-", ""))
                .timeout(Duration.ofSeconds((long) REQUEST_TIMEOUT_S));
        if (apiKey != null && !apiKey.isEmpty()) {
            builder.header("api-key", apiKey);
        }
        builder.POST(HttpRequest.BodyPublishers.ofString(json));
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<InputStream> postJsonStream(String url, Map<String, Object> body) throws Exception {
        String json = MAPPER.writeValueAsString(body);
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("x-session-affinity", UUID.randomUUID().toString().replace("-", ""))
                .timeout(Duration.ofSeconds((long) REQUEST_TIMEOUT_S));
        if (apiKey != null && !apiKey.isEmpty()) {
            builder.header("api-key", apiKey);
        }
        builder.POST(HttpRequest.BodyPublishers.ofString(json));
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    @SuppressWarnings("unchecked")
    private LLMResponse parseError(HttpResponse<String> resp) {
        return parseError(resp.statusCode(), resp.body());
    }

    @SuppressWarnings("unchecked")
    private LLMResponse parseError(int statusCode, String body) {
        String msg = body != null && !body.isEmpty()
                ? "Error: " + body.strip().substring(0, Math.min(500, body.strip().length()))
                : "Error: HTTP " + statusCode;
        Double retryAfter = extractRetryAfter(msg);
        return new LLMResponse(msg, List.of(), "error", Map.of(),
                retryAfter, null, null, statusCode, null, null, null, retryAfter, null);
    }
}
