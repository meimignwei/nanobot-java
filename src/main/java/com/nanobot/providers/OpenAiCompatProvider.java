package com.nanobot.providers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanobot.providers.base.*;
import com.nanobot.providers.openai_responses.OpenAiResponsesHelper;
import jakarta.annotation.Nullable;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Unified provider for all OpenAI-compatible APIs.
 * Mirrors Python OpenAICompatProvider (openai_compat_provider.py) — Chat Completions + Responses API.
 * Uses java.net.http.HttpClient; under virtual threads send() blocking suspends the carrier.
 */
public class OpenAiCompatProvider extends LLMProvider {

    private static final int RESPONSES_FAILURE_THRESHOLD = 3;
    private static final int RESPONSES_PROBE_INTERVAL_S = 300;

    // Responses API circuit breaker
    private final Map<String, Integer> responsesFailures = new HashMap<>();
    private final Map<String, Long> responsesTrippedAt = new HashMap<>();

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> ALLOWED_MSG_KEYS = Set.of(
            "role", "content", "tool_calls", "tool_call_id", "name",
            "reasoning_content", "extra_content"
    );

    static final double REQUEST_TIMEOUT_S = 120.0;

    private final String defaultModel;
    private final Map<String, String> extraHeaders;
    @Nullable
    private final ProviderSpec spec;
    private final Map<String, Object> extraBody;
    private final String apiType;
    private final Map<String, String> extraQuery;
    private final String effectiveBase;

    public OpenAiCompatProvider(
            @Nullable String apiKey,
            @Nullable String apiBase,
            String defaultModel,
            @Nullable Map<String, String> extraHeaders,
            @Nullable ProviderSpec spec,
            @Nullable Map<String, Object> extraBody,
            String apiType,
            @Nullable Map<String, String> extraQuery
    ) {
        super(apiKey, apiBase);
        this.defaultModel = defaultModel;
        this.extraHeaders = extraHeaders != null ? extraHeaders : Map.of();
        this.spec = spec;
        this.extraBody = extraBody != null ? extraBody : Map.of();
        this.apiType = (spec != null && "openai".equals(spec.name())) ? apiType : "auto";
        this.extraQuery = extraQuery != null ? extraQuery : Map.of();
        this.effectiveBase = apiBase != null ? apiBase
                : (spec != null ? spec.defaultApiBase() : null);
    }

    // Convenience constructor
    public OpenAiCompatProvider(String apiKey, String apiBase, String defaultModel) {
        this(apiKey, apiBase, defaultModel, null, null, null, "auto", null);
    }

    // ------------------------------------------------------------------
    // Responses API path (mirrors Python _should_use_responses_api etc.)
    // ------------------------------------------------------------------

    boolean shouldUseResponsesApi(@Nullable String model, @Nullable String reasoningEffort) {
        if ("chat_completions".equals(apiType)) return false;
        if (spec != null && !Set.of("openai", "github_copilot").contains(spec.name())) return false;
        if ("responses".equals(apiType)) return true;
        if (spec == null || !"github_copilot".equals(spec.name())) {
            if (!isDirectOpenAiBase(effectiveBase)) return false;
        }

        String modelName = (model != null ? model : defaultModel).toLowerCase();
        boolean wants = false;
        if (reasoningEffort != null && !"none".equalsIgnoreCase(reasoningEffort)) wants = true;
        else if (modelName.contains("gpt-5") || modelName.contains("o1") || modelName.contains("o3") || modelName.contains("o4")) {
            wants = true;
        }
        if (!wants) return false;

        return responsesCircuitAllowsProbe(model, reasoningEffort);
    }

    private boolean responsesCircuitAllowsProbe(@Nullable String model, @Nullable String reasoningEffort) {
        String key = responsesCircuitKey(model, reasoningEffort);
        int failures = responsesFailures.getOrDefault(key, 0);
        if (failures >= RESPONSES_FAILURE_THRESHOLD) {
            long tripped = responsesTrippedAt.getOrDefault(key, 0L);
            if ((System.currentTimeMillis() - tripped) / 1000.0 < RESPONSES_PROBE_INTERVAL_S) {
                return false;
            }
        }
        return true;
    }

    void recordResponsesFailure(@Nullable String model, @Nullable String reasoningEffort) {
        String key = responsesCircuitKey(model, reasoningEffort);
        int count = responsesFailures.merge(key, 1, Integer::sum);
        if (count >= RESPONSES_FAILURE_THRESHOLD) {
            responsesTrippedAt.put(key, System.currentTimeMillis());
        }
    }

    void recordResponsesSuccess(@Nullable String model, @Nullable String reasoningEffort) {
        String key = responsesCircuitKey(model, reasoningEffort);
        responsesFailures.remove(key);
        responsesTrippedAt.remove(key);
    }

    private String responsesCircuitKey(@Nullable String model, @Nullable String reasoningEffort) {
        String mn = (model != null ? model : defaultModel).toLowerCase();
        String effort = reasoningEffort != null ? reasoningEffort.toLowerCase() : "";
        return mn + ":" + effort;
    }

    static boolean isDirectOpenAiBase(@Nullable String apiBase) {
        if (apiBase == null) return true;
        String normalized = apiBase.strip().toLowerCase();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized.contains("api.openai.com") && !normalized.contains("openrouter");
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> buildResponsesBody(
            List<Map<String, Object>> messages,
            @Nullable List<Map<String, Object>> tools,
            @Nullable String model,
            int maxTokens,
            double temperature,
            @Nullable String reasoningEffort,
            @Nullable Object toolChoice
    ) {
        String modelName = model != null ? model : defaultModel;
        if (spec != null && spec.stripModelPrefix()) {
            int slash = modelName.lastIndexOf('/');
            if (slash >= 0) modelName = modelName.substring(slash + 1);
        }
        List<Map<String, Object>> sanitized = sanitizeMessages(
                MessageSanitizer.sanitizeEmptyContent(messages));
        Object[] converted = com.nanobot.providers.openai_responses.OpenAiResponsesHelper.convertMessages(sanitized);
        String instructions = (String) converted[0];
        List<Map<String, Object>> inputItems = (List<Map<String, Object>>) converted[1];

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("instructions", instructions != null && !instructions.isEmpty() ? instructions : null);
        body.put("input", inputItems);
        body.put("max_output_tokens", Math.max(1, maxTokens));
        body.put("store", false);
        body.put("stream", false);

        if (supportsTemperature(modelName, reasoningEffort)) {
            body.put("temperature", temperature);
        }
        if (reasoningEffort != null && !"none".equalsIgnoreCase(reasoningEffort)) {
            body.put("reasoning", Map.of("effort", reasoningEffort));
            body.put("include", List.of("reasoning.encrypted_content"));
        }
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", com.nanobot.providers.openai_responses.OpenAiResponsesHelper.convertTools(tools));
            body.put("tool_choice", toolChoice != null ? toolChoice : "auto");
        }
        if (!extraBody.isEmpty()) {
            body = deepMerge(body, extraBody);
        }
        return body;
    }

    // ------------------------------------------------------------------
    // Responses API fallback helpers
    // ------------------------------------------------------------------

    static boolean shouldFallbackFromResponsesError(int statusCode, String body) {
        if (statusCode != 400 && statusCode != 404 && statusCode != 422) return false;
        String text = body != null ? body.toLowerCase() : "";
        String[] markers = {"responses", "response api", "max_output_tokens", "instructions",
                "previous_response", "unsupported", "not supported",
                "unknown parameter", "unrecognized request argument"};
        for (String m : markers) {
            if (text.contains(m)) return true;
        }
        return false;
    }

    static boolean shouldFallbackFromResponsesError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        int statusCode = 0;
        if (msg.contains("400")) statusCode = 400;
        else if (msg.contains("404")) statusCode = 404;
        else if (msg.contains("422")) statusCode = 422;
        return shouldFallbackFromResponsesError(statusCode, msg);
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
        try {
            if (shouldUseResponsesApi(model, reasoningEffort)) {
                try {
                    Map<String, Object> body = buildResponsesBody(
                            messages, tools, model, maxTokens, temperature,
                            reasoningEffort, toolChoice);
                    String url = (effectiveBase != null ? effectiveBase : "https://api.openai.com/v1")
                            + "/responses";
                    HttpRequest request = buildRequest(url, body);
                    HttpResponse<String> httpResponse = HTTP_CLIENT.send(request,
                            HttpResponse.BodyHandlers.ofString());
                    if (httpResponse.statusCode() != 200) {
                        int sc = httpResponse.statusCode();
                        String errorBody = httpResponse.body();
                        if (shouldFallbackFromResponsesError(sc, errorBody)) {
                            recordResponsesFailure(model, reasoningEffort);
                        } else {
                            return parseErrorResponse(httpResponse);
                        }
                    } else {
                        recordResponsesSuccess(model, reasoningEffort);
                        return OpenAiResponsesHelper.parseResponseOutput(
                                MAPPER.readValue(httpResponse.body(),
                                        new TypeReference<Map<String, Object>>() {}));
                    }
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception responsesError) {
                    if (spec != null && "github_copilot".equals(spec.name())) {
                        throw responsesError;
                    }
                    if ("responses".equals(apiType)) {
                        throw responsesError;
                    }
                    if (!shouldFallbackFromResponsesError(responsesError)) {
                        throw responsesError;
                    }
                    recordResponsesFailure(model, reasoningEffort);
                }
            }

            Map<String, Object> body = buildKwargs(messages, tools, model, maxTokens,
                    temperature, reasoningEffort, toolChoice);
            String url = (effectiveBase != null ? effectiveBase : "https://api.openai.com/v1")
                    + "/chat/completions";

            HttpRequest request = buildRequest(url, body);
            HttpResponse<String> httpResponse = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (httpResponse.statusCode() != 200) {
                return parseErrorResponse(httpResponse);
            }
            return parse(MAPPER.readValue(httpResponse.body(), new TypeReference<Map<String, Object>>() {}));
        } catch (InterruptedException e) {
            throw e;
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
        int idleTimeoutS = Integer.parseInt(
                System.getenv().getOrDefault("NANOBOT_STREAM_IDLE_TIMEOUT_S", "90"));

        try {
            if (shouldUseResponsesApi(model, reasoningEffort)) {
                try {
                    Map<String, Object> body = buildResponsesBody(
                            messages, tools, model, maxTokens, temperature,
                            reasoningEffort, toolChoice);
                    body.put("stream", true);
                    String url = (effectiveBase != null ? effectiveBase : "https://api.openai.com/v1")
                            + "/responses";
                    HttpRequest request = buildRequest(url, body);
                    HttpResponse<InputStream> httpResponse = HTTP_CLIENT.send(request,
                            HttpResponse.BodyHandlers.ofInputStream());
                    if (httpResponse.statusCode() != 200) {
                        int sc = httpResponse.statusCode();
                        String errorBody = new String(httpResponse.body().readAllBytes(), StandardCharsets.UTF_8);
                        if (shouldFallbackFromResponsesError(sc, errorBody)) {
                            recordResponsesFailure(model, reasoningEffort);
                        } else {
                            return parseErrorResponse(sc, errorBody);
                        }
                    } else {
                        recordResponsesSuccess(model, reasoningEffort);
                        OpenAiResponsesHelper.SseState state = new OpenAiResponsesHelper.SseState();
                        OpenAiResponsesHelper.consumeSseIntoState(
                                httpResponse.body(), state,
                                onContentDelta, onToolCallDelta,
                                onThinkingDelta);
                        return new LLMResponse(
                                !state.content.isEmpty() ? state.content : null,
                                state.toolCalls,
                                state.finishReason,
                                state.usage,
                                null,
                                state.reasoningContent,
                                null, null, null, null, null, null, null);
                    }
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception responsesError) {
                    if (spec != null && "github_copilot".equals(spec.name())) {
                        throw responsesError;
                    }
                    if ("responses".equals(apiType)) {
                        throw responsesError;
                    }
                    if (!shouldFallbackFromResponsesError(responsesError)) {
                        throw responsesError;
                    }
                    recordResponsesFailure(model, reasoningEffort);
                }
            }

            Map<String, Object> body = buildKwargs(messages, tools, model, maxTokens,
                    temperature, reasoningEffort, toolChoice);
            body.put("stream", true);
            body.put("stream_options", Map.of("include_usage", true));

            String url = (effectiveBase != null ? effectiveBase : "https://api.openai.com/v1")
                    + "/chat/completions";

            HttpRequest request = buildRequest(url, body);
            HttpResponse<InputStream> httpResponse = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (httpResponse.statusCode() != 200) {
                String errorBody = new String(httpResponse.body().readAllBytes(), StandardCharsets.UTF_8);
                return parseErrorResponse(httpResponse.statusCode(), errorBody);
            }

            List<Object> chunks = new ArrayList<>();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(httpResponse.body(), StandardCharsets.UTF_8));
            String line;

            try {
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6);
                        if ("[DONE]".equals(data)) break;
                        try {
                            Map<String, Object> chunk = MAPPER.readValue(data,
                                    new TypeReference<Map<String, Object>>() {});
                            chunks.add(chunk);
                            fireDeltas(chunk, onContentDelta, onThinkingDelta, onToolCallDelta);
                        } catch (JsonProcessingException ignored) {}
                    }
                }
            } finally {
                reader.close();
            }

            return parseChunks(chunks);
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
    // Build request
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    Map<String, Object> buildKwargs(
            List<Map<String, Object>> messages,
            @Nullable List<Map<String, Object>> tools,
            @Nullable String model,
            int maxTokens,
            double temperature,
            @Nullable String reasoningEffort,
            @Nullable Object toolChoice
    ) {
        String modelName = model != null ? model : defaultModel;
        ProviderSpec s = this.spec;

        // Apply prompt caching for Anthropic models via compatible gateways
        if (s != null && s.supportsPromptCaching()) {
            String ml = modelName.toLowerCase();
            if (ml.startsWith("anthropic/") || ml.startsWith("claude")) {
                var result = applyCacheControl(messages, tools);
                messages = (List<Map<String, Object>>) result[0];
                tools = (List<Map<String, Object>>) result[1];
            }
        }

        // Strip model prefix
        if (s != null && s.stripModelPrefix()) {
            int slash = modelName.lastIndexOf('/');
            if (slash >= 0) modelName = modelName.substring(slash + 1);
        }

        Map<String, Object> kwargs = new LinkedHashMap<>();
        kwargs.put("model", modelName);
        kwargs.put("messages", sanitizeMessages(MessageSanitizer.sanitizeEmptyContent(messages)));

        // Temperature: skip for reasoning models when reasoning_effort is active
        if (supportsTemperature(modelName, reasoningEffort)) {
            kwargs.put("temperature", temperature);
        }

        if ((s != null && s.supportsMaxCompletionTokens()) || requiresMaxCompletionTokens(modelName)) {
            kwargs.put("max_completion_tokens", Math.max(1, maxTokens));
        } else {
            kwargs.put("max_tokens", Math.max(1, maxTokens));
        }

        // Model overrides
        if (s != null) {
            String ml = modelName.toLowerCase();
            for (ModelOverride override : s.modelOverrides()) {
                if (ml.contains(override.model())) {
                    kwargs.putAll(override.overrides());
                    break;
                }
            }
        }

        // Reasoning effort
        String semanticEffort = null;
        String wireEffort = reasoningEffort;
        if (reasoningEffort != null) {
            semanticEffort = reasoningEffort.toLowerCase();
            if ("minimum".equals(semanticEffort)) semanticEffort = "minimal";
            if (s != null && "dashscope".equals(s.name()) && "minimal".equals(semanticEffort)) {
                wireEffort = "minimum";
            }
        }

        if (wireEffort != null && !"none".equals(semanticEffort)) {
            kwargs.put("reasoning_effort", wireEffort);
        }

        // Thinking controls
        if (reasoningEffort != null) {
            boolean thinkingEnabled = semanticEffort != null
                    && !"none".equals(semanticEffort)
                    && !"minimal".equals(semanticEffort);
            for (String thinkingStyle : thinkingStylesFor(s, modelName)) {
                Map<String, Object> extra = thinkingExtraBody(thinkingStyle, thinkingEnabled);
                if (extra != null) {
                    kwargs.computeIfAbsent("extra_body", k -> new LinkedHashMap<>());
                    ((Map<String, Object>) kwargs.get("extra_body")).putAll(extra);
                }
            }
            String gatewayStyle = s != null ? s.gatewayReasoningStyle() : "";
            if (!gatewayStyle.isEmpty() && modelThinkingStyle(modelName) != null) {
                Map<String, Object> extra = gatewayReasoningExtraBody(gatewayStyle, semanticEffort);
                if (extra != null) {
                    kwargs.computeIfAbsent("extra_body", k -> new LinkedHashMap<>());
                    ((Map<String, Object>) kwargs.get("extra_body")).putAll(extra);
                }
            }
        }

        if (tools != null && !tools.isEmpty()) {
            kwargs.put("tools", tools);
            kwargs.put("tool_choice", toolChoice != null ? toolChoice : "auto");
        }

        // Merge user extra_body
        if (!extraBody.isEmpty()) {
            Map<String, Object> existing = (Map<String, Object>) kwargs.get("extra_body");
            if (existing != null) {
                kwargs.put("extra_body", deepMerge(existing, extraBody));
            } else {
                kwargs.put("extra_body", new LinkedHashMap<>(extraBody));
            }
        }

        return kwargs;
    }

    private HttpRequest buildRequest(String url, Map<String, Object> body) throws JsonProcessingException {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds((long) REQUEST_TIMEOUT_S));

        if (apiKey != null && !apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        for (var entry : extraHeaders.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }

        builder.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        return builder.build();
    }

    // ------------------------------------------------------------------
    // Message sanitization
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> sanitizeMessages(List<Map<String, Object>> messages) {
        List<Map<String, Object>> sanitized = MessageSanitizer.sanitizeRequestMessages(messages, ALLOWED_MSG_KEYS);
        boolean forceStringContent = spec != null && "deepseek".equals(spec.name());

        for (Map<String, Object> clean : sanitized) {
            if (!forceStringContent || ("assistant".equals(clean.get("role")) && clean.get("tool_calls") != null)) {
                continue;
            }
            clean.put("content", coerceContentToString(clean.get("content")));
        }
        return MessageSanitizer.enforceRoleAlternation(sanitized);
    }

    static String coerceContentToString(Object content) {
        if (content == null || content instanceof String) return (String) content;
        String text = extractTextContent(content);
        if (text != null && !text.isEmpty()) return text;
        try {
            return MAPPER.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            return String.valueOf(content);
        }
    }

    // ------------------------------------------------------------------
    // Response parsing
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    LLMResponse parse(Map<String, Object> responseMap) {
        List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.getOrDefault("choices", List.of());
        if (choices.isEmpty()) {
            String content = extractTextContent(
                    responseMap.getOrDefault("content", responseMap.get("output_text")));
            String reasoningContent = extractTextContent(responseMap.get("reasoning_content"));
            if (content != null) {
                return new LLMResponse(content, List.of(),
                        String.valueOf(responseMap.getOrDefault("finish_reason", "stop")),
                        extractUsage(responseMap), null, reasoningContent, null,
                        null, null, null, null, null, null);
            }
            return LLMResponse.error("Error: API returned empty choices.");
        }

        Map<String, Object> choice0 = (Map<String, Object>) choices.get(0);
        Map<String, Object> msg0 = (Map<String, Object>) choice0.getOrDefault("message", Map.of());
        String content = extractTextContent(msg0.get("content"));
        String finishReason = String.valueOf(choice0.getOrDefault("finish_reason", "stop"));

        // StepFun: fallback to reasoning when content is empty
        if (content == null && msg0.get("reasoning") != null && spec != null && spec.reasoningAsContent()) {
            content = extractTextContent(msg0.get("reasoning"));
        }

        String reasoningContent = (String) msg0.get("reasoning_content");
        if (reasoningContent == null && msg0.get("reasoning") != null) {
            reasoningContent = extractTextContent(msg0.get("reasoning"));
        }

        // Collect tool calls from all choices
        List<Map<String, Object>> rawToolCalls = new ArrayList<>();
        for (Map<String, Object> ch : choices) {
            Map<String, Object> m = (Map<String, Object>) ch.getOrDefault("message", Map.of());
            List<Map<String, Object>> tcs = (List<Map<String, Object>>) m.get("tool_calls");
            if (tcs != null) {
                rawToolCalls.addAll(tcs);
                if (Set.of("tool_calls", "stop").contains(ch.get("finish_reason"))) {
                    finishReason = String.valueOf(ch.get("finish_reason"));
                }
            }
            if (content == null) content = extractTextContent(m.get("content"));
            if (reasoningContent == null) reasoningContent = (String) m.get("reasoning_content");
        }

        List<ToolCallRequest> parsedToolCalls = new ArrayList<>();
        for (Map<String, Object> tc : rawToolCalls) {
            Map<String, Object> fn = (Map<String, Object>) tc.getOrDefault("function", Map.of());
            Object args = ToolCallRequest.parseToolArguments(fn.getOrDefault("arguments", Map.of()));
            parsedToolCalls.add(new ToolCallRequest(
                    String.valueOf(tc.getOrDefault("id", shortToolId())),
                    String.valueOf(fn.getOrDefault("name", "")),
                    args, null, null, null
            ));
        }

        return new LLMResponse(content, parsedToolCalls, finishReason,
                extractUsage(responseMap), null, reasoningContent, null,
                null, null, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    static LLMResponse parseChunks(List<Object> chunks) {
        StringBuilder contentBuf = new StringBuilder();
        StringBuilder reasoningBuf = new StringBuilder();
        Map<Integer, Map<String, Object>> tcBufs = new LinkedHashMap<>();
        String finishReason = "stop";
        Map<String, Integer> usage = Map.of();

        for (Object chunk : chunks) {
            if (chunk instanceof String s) {
                contentBuf.append(s);
                continue;
            }
            Map<String, Object> cm = (Map<String, Object>) chunk;
            List<Map<String, Object>> choices = (List<Map<String, Object>>) cm.getOrDefault("choices", List.of());
            if (choices.isEmpty()) {
                usage = extractUsage(cm);
                continue;
            }
            Map<String, Object> choice = choices.get(0);
            if (choice.get("finish_reason") != null) {
                finishReason = String.valueOf(choice.get("finish_reason"));
            }
            Map<String, Object> delta = (Map<String, Object>) choice.getOrDefault("delta", Map.of());
            String text = extractTextContent(delta.get("content"));
            if (text != null) contentBuf.append(text);

            String thinking = extractTextContent(
                    delta.getOrDefault("reasoning_content", delta.get("reasoning")));
            if (thinking != null) reasoningBuf.append(thinking);

            List<Map<String, Object>> toolDeltas = (List<Map<String, Object>>) delta.get("tool_calls");
            if (toolDeltas != null) {
                for (Map<String, Object> tc : toolDeltas) {
                    int idx = tc.containsKey("index") ? ((Number) tc.get("index")).intValue() : 0;
                    tcBufs.computeIfAbsent(idx, k -> new LinkedHashMap<>(Map.of(
                            "id", "", "name", "", "arguments", ""
                    )));
                    Map<String, Object> buf = tcBufs.get(idx);
                    if (tc.get("id") != null) buf.put("id", String.valueOf(tc.get("id")));
                    Map<String, Object> fn = (Map<String, Object>) tc.get("function");
                    if (fn != null) {
                        if (fn.get("name") != null) buf.put("name", String.valueOf(fn.get("name")));
                        if (fn.get("arguments") != null) buf.put("arguments",
                                buf.get("arguments") + String.valueOf(fn.get("arguments")));
                    }
                }
            }
            usage = extractUsage(cm);
        }

        List<ToolCallRequest> toolCalls = new ArrayList<>();
        for (Map<String, Object> buf : tcBufs.values()) {
            String id = (String) buf.get("id");
            if (id == null || id.isEmpty()) id = shortToolId();
            Object args = ToolCallRequest.parseToolArguments(buf.get("arguments"));
            toolCalls.add(new ToolCallRequest(id, (String) buf.get("name"), args,
                    null, null, null));
        }

        return new LLMResponse(
                !contentBuf.isEmpty() ? contentBuf.toString() : null,
                toolCalls, finishReason, usage, null,
                !reasoningBuf.isEmpty() ? reasoningBuf.toString() : null,
                null, null, null, null, null, null, null);
    }

    // ------------------------------------------------------------------
    // Error handling
    // ------------------------------------------------------------------

    LLMResponse parseErrorResponse(HttpResponse<String> resp) {
        return parseErrorResponse(resp.statusCode(), resp.body());
    }

    @SuppressWarnings("unchecked")
    LLMResponse parseErrorResponse(int statusCode, String body) {
        String msg = "Error: " + (body != null ? body.strip().substring(0, Math.min(500, body.strip().length())) : "HTTP " + statusCode);
        Map<String, Object> errorMeta = new LinkedHashMap<>();
        errorMeta.put("error_status_code", statusCode);
        return new LLMResponse(msg, List.of(), "error", Map.of(),
                extractRetryAfter(msg), null, null,
                statusCode, null, null, null,
                extractRetryAfterFromHeaders(null), null);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> extractErrorMetadata(Exception e) {
        Map<String, Object> meta = new LinkedHashMap<>();
        String errorName = e.getClass().getSimpleName().toLowerCase();
        if (errorName.contains("timeout")) meta.put("error_kind", "timeout");
        else if (errorName.contains("connection")) meta.put("error_kind", "connection");
        return meta;
    }

    LLMResponse handleError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : "Error calling LLM: " + e;
        if (msg.length() > 500) msg = msg.substring(0, 500);
        Double retryAfter = extractRetryAfter(msg);

        Map<String, Object> errMeta = extractErrorMetadata(e);
        return new LLMResponse("Error: " + msg, List.of(), "error", Map.of(),
                retryAfter, null, null,
                null,
                (String) errMeta.get("error_kind"),
                null, null,
                extractRetryAfterFromHeaders(null), null);
    }

    // ------------------------------------------------------------------
    // Usage extraction
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static Map<String, Integer> extractUsage(Object response) {
        Map<String, Object> rm = toMap(response);
        if (rm == null) return Map.of();
        Map<String, Object> usageObj = (Map<String, Object>) rm.get("usage");
        if (usageObj == null) return Map.of();

        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("prompt_tokens", toInt(usageObj.get("prompt_tokens")));
        result.put("completion_tokens", toInt(usageObj.get("completion_tokens")));
        result.put("total_tokens", toInt(usageObj.get("total_tokens")));

        // cached_tokens normalization
        Integer cached = getNestedInt(usageObj, "prompt_tokens_details", "cached_tokens");
        if (cached == 0) cached = getNestedInt(usageObj, "cached_tokens");
        if (cached == 0) cached = getNestedInt(usageObj, "prompt_cache_hit_tokens");
        if (cached > 0) result.put("cached_tokens", cached);

        return result;
    }

    // ------------------------------------------------------------------
    // Helpers (static, package-visible for testing)
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static Map<String, Object> toMap(Object obj) {
        if (obj instanceof Map) return (Map<String, Object>) obj;
        return null;
    }

    static int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    static int getNestedInt(Map<String, Object> map, String... keys) {
        Object current = map;
        for (String key : keys) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(key);
            } else {
                return 0;
            }
        }
        return current instanceof Number ? ((Number) current).intValue() : 0;
    }

    static String extractTextContent(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return s;
        if (value instanceof List<?> items) {
            StringBuilder sb = new StringBuilder();
            for (Object item : items) {
                Map<String, Object> im = toMap(item);
                if (im != null) {
                    Object text = im.get("text");
                    if (text instanceof String s) sb.append(s);
                } else if (item instanceof String s) {
                    sb.append(s);
                }
            }
            return !sb.isEmpty() ? sb.toString() : null;
        }
        return value.toString();
    }

    static String shortToolId() {
        String alphanum = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(9);
        Random rng = new Random();
        for (int i = 0; i < 9; i++) {
            sb.append(alphanum.charAt(rng.nextInt(alphanum.length())));
        }
        return sb.toString();
    }

    // ---- thinking helpers ----

    static final Set<String> KIMI_THINKING_MODELS = Set.of("kimi-k2.5", "kimi-k2.6", "k2.6-code-preview");

    static String modelSlug(String modelName) {
        String lower = modelName.toLowerCase();
        int slash = lower.lastIndexOf('/');
        return slash >= 0 ? lower.substring(slash + 1) : lower;
    }

    static boolean requiresMaxCompletionTokens(String modelName) {
        String slug = modelSlug(modelName);
        return slug.contains("gpt-5") || slug.startsWith("o1") || slug.startsWith("o3") || slug.startsWith("o4");
    }

    static boolean supportsTemperature(String modelName, @Nullable String reasoningEffort) {
        if (reasoningEffort != null && !"none".equalsIgnoreCase(reasoningEffort)) return false;
        String lower = modelName.toLowerCase();
        return !(lower.contains("gpt-5") || lower.contains("o1") || lower.contains("o3") || lower.contains("o4"));
    }

    static String modelThinkingStyle(String modelName) {
        String slug = modelSlug(modelName);
        if (KIMI_THINKING_MODELS.contains(slug)) return "thinking_type";
        return null;
    }

    static List<String> thinkingStylesFor(@Nullable ProviderSpec spec, String modelName) {
        List<String> styles = new ArrayList<>();
        if (spec != null && !spec.thinkingStyle().isEmpty()) styles.add(spec.thinkingStyle());
        String modelStyle = modelThinkingStyle(modelName);
        if (modelStyle != null && !styles.contains(modelStyle)) styles.add(modelStyle);
        return styles;
    }

    static Map<String, Object> thinkingExtraBody(String style, boolean thinkingEnabled) {
        return switch (style) {
            case "thinking_type" -> Map.of("thinking", Map.of("type", thinkingEnabled ? "enabled" : "disabled"));
            case "enable_thinking" -> Map.of("enable_thinking", thinkingEnabled);
            case "reasoning_split" -> Map.of("reasoning_split", thinkingEnabled);
            default -> null;
        };
    }

    static Map<String, Object> gatewayReasoningExtraBody(String style, @Nullable String effort) {
        if (effort == null) return null;
        if ("reasoning_effort".equals(style)) {
            return Map.of("reasoning", Map.of("effort", effort));
        }
        return null;
    }

    // ---- cache control ----

    @SuppressWarnings("unchecked")
    static Object[] applyCacheControl(
            List<Map<String, Object>> messages,
            @Nullable List<Map<String, Object>> tools
    ) {
        Map<String, Object> cacheMarker = Map.of("type", "ephemeral");
        List<Map<String, Object>> newMessages = new ArrayList<>(messages);

        if (!newMessages.isEmpty() && "system".equals(newMessages.get(0).get("role"))) {
            newMessages.set(0, markCache(newMessages.get(0), cacheMarker));
        }
        if (newMessages.size() >= 3) {
            newMessages.set(newMessages.size() - 2, markCache(newMessages.get(newMessages.size() - 2), cacheMarker));
        }

        List<Map<String, Object>> newTools = tools;
        if (tools != null) {
            newTools = new ArrayList<>(tools);
            for (int idx : MessageSanitizer.toolCacheMarkerIndices(newTools)) {
                Map<String, Object> updated = new LinkedHashMap<>(newTools.get(idx));
                updated.put("cache_control", cacheMarker);
                newTools.set(idx, updated);
            }
        }
        return new Object[]{newMessages, newTools};
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> markCache(Map<String, Object> msg, Map<String, Object> marker) {
        Object content = msg.get("content");
        if (content instanceof String s) {
            Map<String, Object> updated = new LinkedHashMap<>(msg);
            updated.put("content", List.of(Map.of("type", "text", "text", s, "cache_control", marker)));
            return updated;
        }
        if (content instanceof List<?> items && !items.isEmpty()) {
            List<Object> nc = new ArrayList<>(items);
            Map<String, Object> last = new LinkedHashMap<>((Map<String, Object>) nc.get(nc.size() - 1));
            last.put("cache_control", marker);
            nc.set(nc.size() - 1, last);
            Map<String, Object> updated = new LinkedHashMap<>(msg);
            updated.put("content", nc);
            return updated;
        }
        return msg;
    }

    // ---- deep merge ----

    @SuppressWarnings("unchecked")
    static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> merged = new LinkedHashMap<>(base);
        for (var entry : override.entrySet()) {
            if (merged.containsKey(entry.getKey())
                    && merged.get(entry.getKey()) instanceof Map
                    && entry.getValue() instanceof Map) {
                merged.put(entry.getKey(),
                        deepMerge((Map<String, Object>) merged.get(entry.getKey()),
                                (Map<String, Object>) entry.getValue()));
            } else {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        return merged;
    }

    // ---- Stream delta firing ----

    @SuppressWarnings("unchecked")
    private void fireDeltas(
            Map<String, Object> chunk,
            @Nullable ThrowingConsumer<String> onContentDelta,
            @Nullable ThrowingConsumer<String> onThinkingDelta,
            @Nullable ThrowingConsumer<Map<String, Object>> onToolCallDelta
    ) throws Exception {
        List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.getOrDefault("choices", List.of());
        if (choices.isEmpty()) return;

        Map<String, Object> choice = choices.get(0);
        Map<String, Object> delta = (Map<String, Object>) choice.getOrDefault("delta", Map.of());

        if (onContentDelta != null) {
            String text = extractTextContent(delta.get("content"));
            if (text != null) onContentDelta.accept(text);
        }
        if (onThinkingDelta != null) {
            String thinking = extractTextContent(
                    delta.getOrDefault("reasoning_content", delta.get("reasoning")));
            if (thinking != null) onThinkingDelta.accept(thinking);
        }
        if (onToolCallDelta != null) {
            List<Map<String, Object>> toolDeltas = (List<Map<String, Object>>) delta.get("tool_calls");
            if (toolDeltas != null) {
                int idx = 0;
                for (Map<String, Object> tc : toolDeltas) {
                    Map<String, Object> fn = (Map<String, Object>) tc.get("function");
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("index", tc.getOrDefault("index", idx));
                    info.put("call_id", String.valueOf(tc.getOrDefault("id", "")));
                    info.put("name", fn != null ? String.valueOf(fn.getOrDefault("name", "")) : "");
                    info.put("arguments_delta", fn != null ? String.valueOf(fn.getOrDefault("arguments", "")) : "");
                    onToolCallDelta.accept(info);
                    idx++;
                }
            }
        }
    }
}
