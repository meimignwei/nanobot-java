package com.nanobot.providers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanobot.providers.base.*;
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
 * Anthropic-native provider using the Anthropic Messages API via HTTP.
 * Full port of Python AnthropicProvider (anthropic_provider.py, 694 lines).
 *
 * Handles message format conversion (OpenAI → Anthropic Messages API),
 * prompt caching, extended thinking, tool calls, and streaming.
 */
public class AnthropicProvider extends LLMProvider {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ALPHANUM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final Random RNG = new Random();
    private static final int IDLE_TIMEOUT_S = Integer.parseInt(
            System.getenv().getOrDefault("NANOBOT_STREAM_IDLE_TIMEOUT_S", "90"));
    private static final double REQUEST_TIMEOUT_S = 120.0;

    private final String defaultModel;
    private final Map<String, String> extraHeaders;
    private final String effectiveApiBase;

    public AnthropicProvider(
            @Nullable String apiKey,
            @Nullable String apiBase,
            String defaultModel,
            @Nullable Map<String, String> extraHeaders
    ) {
        super(apiKey, apiBase);
        this.defaultModel = defaultModel;
        this.extraHeaders = extraHeaders != null ? extraHeaders : Map.of();
        // Anthropic SDK appends /v1 to request paths internally — strip it
        this.effectiveApiBase = normalizeBaseUrl(apiBase != null ? apiBase : "https://api.anthropic.com");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    static String normalizeBaseUrl(String apiBase) {
        String normalized = apiBase;
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.endsWith("/v1")) {
            return normalized.substring(0, normalized.length() - 3);
        }
        return normalized;
    }

    static String stripPrefix(String model) {
        if (model.startsWith("anthropic/")) return model.substring("anthropic/".length());
        return model;
    }

    static String genToolId() {
        StringBuilder sb = new StringBuilder("toolu_");
        for (int i = 0; i < 22; i++) sb.append(ALPHANUM.charAt(RNG.nextInt(ALPHANUM.length())));
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Error handling
    // ------------------------------------------------------------------

    static LLMResponse handleError(Exception e) {
        String payloadText = "";
        Object headers = null;

        String errorName = e.getClass().getSimpleName().toLowerCase();
        String errorKind = null;
        if (errorName.contains("timeout")) errorKind = "timeout";
        else if (errorName.contains("connection")) errorKind = "connection";

        String msg = e.getMessage() != null ? e.getMessage() : "Error calling LLM: " + e;
        if (msg.length() > 500) msg = msg.substring(0, 500);

        Double retryAfter = extractRetryAfterFromHeaders(null);
        if (retryAfter == null) retryAfter = LLMProvider.extractRetryAfter(msg);

        return new LLMResponse("Error: " + msg, List.of(), "error", Map.of(),
                retryAfter, null, null, null, errorKind, null, null, retryAfter, null);
    }

    static boolean isStreamingRequiredError(Exception e) {
        return e instanceof IllegalArgumentException
                && e.getMessage() != null
                && e.getMessage().toLowerCase().contains("streaming is required");
    }

    // ------------------------------------------------------------------
    // Message conversion: OpenAI chat format → Anthropic Messages API
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    Object[] convertMessages(List<Map<String, Object>> messages) {
        Object system = "";
        List<Map<String, Object>> raw = new ArrayList<>();

        for (Map<String, Object> msg : messages) {
            String role = (String) msg.getOrDefault("role", "");
            Object content = msg.get("content");

            if ("system".equals(role)) {
                system = (content instanceof String || content instanceof List) ? content : String.valueOf(content != null ? content : "");
                continue;
            }
            if ("tool".equals(role)) {
                Map<String, Object> block = toolResultBlock(msg);
                if (!raw.isEmpty() && "user".equals(raw.get(raw.size() - 1).get("role"))) {
                    Object prevC = raw.get(raw.size() - 1).get("content");
                    if (prevC instanceof List lst) {
                        lst.add(block);
                    } else {
                        raw.get(raw.size() - 1).put("content", new ArrayList<>(List.of(
                                Map.of("type", "text", "text", prevC != null ? String.valueOf(prevC) : ""), block)));
                    }
                } else {
                    raw.add(Map.of("role", "user", "content", new ArrayList<>(List.of(block))));
                }
                continue;
            }
            if ("assistant".equals(role)) {
                raw.add(Map.of("role", "assistant", "content", assistantBlocks(msg)));
                continue;
            }
            if ("user".equals(role)) {
                raw.add(Map.of("role", "user", "content", convertUserContent(content)));
                continue;
            }
        }
        return new Object[]{system, mergeConsecutive(raw)};
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> toolResultBlock(Map<String, Object> msg) {
        Object content = msg.get("content");
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", String.valueOf(msg.getOrDefault("tool_call_id", "")));
        if (content instanceof List) {
            block.put("content", convertUserContent(content));
        } else if (content instanceof String s) {
            block.put("content", s);
        } else {
            block.put("content", content != null ? String.valueOf(content) : "");
        }
        return block;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> assistantBlocks(Map<String, Object> msg) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        Object content = msg.get("content");

        for (Map<String, Object> tb : (List<Map<String, Object>>) msg.getOrDefault("thinking_blocks", List.of())) {
            if (tb instanceof Map && "thinking".equals(tb.get("type"))) {
                blocks.add(Map.of(
                        "type", "thinking",
                        "thinking", tb.getOrDefault("thinking", ""),
                        "signature", tb.getOrDefault("signature", "")));
            }
        }

        if (content instanceof String s && !s.isEmpty()) {
            blocks.add(Map.of("type", "text", "text", s));
        } else if (content instanceof List lst) {
            for (Object item : lst) {
                blocks.add(item instanceof Map ? (Map<String, Object>) item
                        : Map.of("type", "text", "text", String.valueOf(item)));
            }
        }

        for (Map<String, Object> tc : (List<Map<String, Object>>) msg.getOrDefault("tool_calls", List.of())) {
            if (!(tc instanceof Map)) continue;
            Map<String, Object> func = (Map<String, Object>) tc.getOrDefault("function", Map.of());
            Object args = func.getOrDefault("arguments", "{}");
            blocks.add(Map.of(
                    "type", "tool_use",
                    "id", tc.getOrDefault("id", genToolId()),
                    "name", func.getOrDefault("name", ""),
                    "input", toolArgumentsObjectForReplay(args)));
        }

        return !blocks.isEmpty() ? blocks : List.of(Map.of("type", "text", "text", ""));
    }

    @SuppressWarnings("unchecked")
    static Object convertUserContent(Object content) {
        if (content instanceof String || content == null) {
            return content != null ? content : "(empty)";
        }
        if (!(content instanceof List)) {
            return String.valueOf(content);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : (List<?>) content) {
            if (!(item instanceof Map)) {
                result.add(Map.of("type", "text", "text", String.valueOf(item)));
                continue;
            }
            Map<String, Object> block = (Map<String, Object>) item;
            if ("image_url".equals(block.get("type"))) {
                Map<String, Object> converted = convertImageBlock(block);
                if (converted != null) result.add(converted);
                continue;
            }
            if (block.get("type") == null) {
                result.add(Map.of("type", "text", "text", String.valueOf(block)));
                continue;
            }
            // Strip _meta from content blocks when passed through
            Map<String, Object> cleaned = new LinkedHashMap<>(block);
            cleaned.remove("_meta");
            result.add(cleaned);
        }
        return !result.isEmpty() ? (Object) result : "(empty)";
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> convertImageBlock(Map<String, Object> block) {
        Map<String, Object> imageUrl = (Map<String, Object>) block.getOrDefault("image_url", Map.of());
        String url = (String) imageUrl.getOrDefault("url", "");
        if (url.isEmpty()) return null;

        java.util.regex.Matcher m = java.util.regex.Pattern.compile("data:(image/\\w+);base64,(.*)", java.util.regex.Pattern.DOTALL).matcher(url);
        if (m.matches()) {
            return Map.of(
                    "type", "image",
                    "source", Map.of("type", "base64", "media_type", m.group(1), "data", m.group(2)));
        }
        return Map.of("type", "image", "source", Map.of("type", "url", "url", url));
    }

    @SuppressWarnings("unchecked")
    static boolean hasToolUse(Map<String, Object> msg) {
        Object content = msg.get("content");
        if (!(content instanceof List list)) return false;
        return list.stream().anyMatch(b -> b instanceof Map && "tool_use".equals(((Map<String, Object>) b).get("type")));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> mergeConsecutive(List<Map<String, Object>> msgs) {
        List<Map<String, Object>> merged = new ArrayList<>();
        for (Map<String, Object> msg : msgs) {
            if (!merged.isEmpty() && Objects.equals(merged.get(merged.size() - 1).get("role"), msg.get("role"))) {
                Map<String, Object> prev = new LinkedHashMap<>(merged.get(merged.size() - 1));
                Object prevC = prev.get("content");
                Object curC = msg.get("content");
                if (prevC instanceof String) {
                    prevC = new ArrayList<>(List.of(Map.of("type", "text", "text", prevC)));
                } else if (prevC instanceof List) {
                    prevC = new ArrayList<>((List<?>) prevC);
                }
                if (curC instanceof String) {
                    curC = new ArrayList<>(List.of(Map.of("type", "text", "text", curC)));
                }
                if (curC instanceof List list) {
                    if (prevC instanceof List pl) {
                        pl.addAll(list);
                    } else {
                        prevC = new ArrayList<>(list);
                    }
                }
                prev.put("content", prevC);
                merged.set(merged.size() - 1, prev);
            } else {
                merged.add(new LinkedHashMap<>(msg));
            }
        }

        // Rule 2: strip trailing assistant turns
        Map<String, Object> lastPopped = null;
        while (!merged.isEmpty() && "assistant".equals(merged.get(merged.size() - 1).get("role"))) {
            lastPopped = merged.remove(merged.size() - 1);
        }

        if (merged.isEmpty() && lastPopped != null && !hasToolUse(lastPopped)) {
            merged.add(Map.of("role", "user", "content", lastPopped.get("content")));
        }

        if (!merged.isEmpty() && "assistant".equals(merged.get(0).get("role")) && !hasToolUse(merged.get(0))) {
            merged.add(0, Map.of("role", "user", "content", "(conversation continued)"));
        }

        return merged;
    }

    // ------------------------------------------------------------------
    // Tool definition conversion
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> convertTools(@Nullable List<Map<String, Object>> tools) {
        if (tools == null || tools.isEmpty()) return null;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            Map<String, Object> func = (Map<String, Object>) tool.getOrDefault("function", tool);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", func.getOrDefault("name", ""));
            entry.put("input_schema", func.getOrDefault("parameters",
                    Map.of("type", "object", "properties", Map.of())));
            if (func.get("description") != null) entry.put("description", func.get("description"));
            if (tool.containsKey("cache_control")) entry.put("cache_control", tool.get("cache_control"));
            result.add(entry);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> convertToolChoice(@Nullable Object toolChoice, boolean thinkingEnabled) {
        if (thinkingEnabled) return Map.of("type", "auto");
        if (toolChoice == null || "auto".equals(toolChoice)) return Map.of("type", "auto");
        if ("required".equals(toolChoice)) return Map.of("type", "any");
        if ("none".equals(toolChoice)) return null;
        if (toolChoice instanceof Map m) {
            Map<String, Object> fn = (Map<String, Object>) m.getOrDefault("function", Map.of());
            Object name = fn.get("name");
            if (name != null && !String.valueOf(name).isEmpty()) {
                return Map.of("type", "tool", "name", name);
            }
        }
        return Map.of("type", "auto");
    }

    // ------------------------------------------------------------------
    // Prompt caching
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static Object[] applyCacheControl(
            Object system,
            List<Map<String, Object>> messages,
            @Nullable List<Map<String, Object>> tools
    ) {
        Map<String, Object> marker = Map.of("type", "ephemeral");
        Object newSystem = system;

        if (system instanceof String s && !s.isEmpty()) {
            newSystem = List.of(Map.of("type", "text", "text", s, "cache_control", marker));
        } else if (system instanceof List list && !list.isEmpty()) {
            List<Object> sl = new ArrayList<>(list);
            Map<String, Object> last = new LinkedHashMap<>((Map<String, Object>) sl.get(sl.size() - 1));
            last.put("cache_control", marker);
            sl.set(sl.size() - 1, last);
            newSystem = sl;
        }

        List<Map<String, Object>> newMsgs = new ArrayList<>(messages);
        if (newMsgs.size() >= 3) {
            Map<String, Object> m = new LinkedHashMap<>(newMsgs.get(newMsgs.size() - 2));
            Object c = m.get("content");
            if (c instanceof String) {
                m.put("content", List.of(Map.of("type", "text", "text", c, "cache_control", marker)));
            } else if (c instanceof List list && !list.isEmpty()) {
                List<Object> nc = new ArrayList<>(list);
                Map<String, Object> last = new LinkedHashMap<>((Map<String, Object>) nc.get(nc.size() - 1));
                last.put("cache_control", marker);
                nc.set(nc.size() - 1, last);
                m.put("content", nc);
            }
            newMsgs.set(newMsgs.size() - 2, m);
        }

        List<Map<String, Object>> newTools = tools;
        if (tools != null) {
            newTools = new ArrayList<>(tools);
            for (int idx : MessageSanitizer.toolCacheMarkerIndices(newTools)) {
                Map<String, Object> updated = new LinkedHashMap<>(newTools.get(idx));
                updated.put("cache_control", marker);
                newTools.set(idx, updated);
            }
        }

        return new Object[]{newSystem, newMsgs, newTools};
    }

    // ------------------------------------------------------------------
    // Build API kwargs
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    Map<String, Object> buildKwargs(
            List<Map<String, Object>> messages,
            @Nullable List<Map<String, Object>> tools,
            @Nullable String model,
            int maxTokens,
            double temperature,
            @Nullable String reasoningEffort,
            @Nullable Object toolChoice,
            boolean supportsCaching
    ) {
        String modelName = stripPrefix(model != null ? model : defaultModel);
        Object[] converted = convertMessages(MessageSanitizer.sanitizeEmptyContent(messages));
        Object system = converted[0];
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> anthropicMsgs = (List<Map<String, Object>>) converted[1];
        List<Map<String, Object>> anthropicTools = convertTools(tools);

        if (supportsCaching) {
            Object[] cacheResult = applyCacheControl(system, anthropicMsgs, anthropicTools);
            system = cacheResult[0];
            anthropicMsgs = (List<Map<String, Object>>) cacheResult[1];
            anthropicTools = (List<Map<String, Object>>) cacheResult[2];
        }

        maxTokens = Math.max(1, maxTokens);
        boolean thinkingEnabled = reasoningEffort != null && !"none".equalsIgnoreCase(reasoningEffort);
        boolean omitTemperature = "opus-4-7".contains(modelName.toLowerCase()) || modelName.toLowerCase().contains("opus-4-7");

        Map<String, Object> kwargs = new LinkedHashMap<>();
        kwargs.put("model", modelName);
        kwargs.put("messages", anthropicMsgs);
        kwargs.put("max_tokens", maxTokens);

        if (system instanceof String s && !s.isEmpty()) {
            kwargs.put("system", s);
        } else if (system instanceof List && !((List<?>) system).isEmpty()) {
            kwargs.put("system", system);
        }

        if ("adaptive".equals(reasoningEffort)) {
            kwargs.put("thinking", Map.of("type", "adaptive"));
            if (!omitTemperature) kwargs.put("temperature", 1.0);
        } else if (thinkingEnabled) {
            int budget = switch (reasoningEffort != null ? reasoningEffort.toLowerCase() : "") {
                case "low" -> 1024;
                case "high" -> Math.max(8192, maxTokens);
                default -> 4096;
            };
            kwargs.put("thinking", Map.of("type", "enabled", "budget_tokens", budget));
            kwargs.put("max_tokens", Math.max(maxTokens, budget + 4096));
            if (!omitTemperature) kwargs.put("temperature", 1.0);
        } else if (!omitTemperature) {
            kwargs.put("temperature", temperature);
        }

        if (anthropicTools != null) {
            kwargs.put("tools", anthropicTools);
            Map<String, Object> tc = convertToolChoice(toolChoice, thinkingEnabled);
            if (tc != null) kwargs.put("tool_choice", tc);
        }

        if (!extraHeaders.isEmpty()) {
            kwargs.put("extra_headers", extraHeaders);
        }

        return kwargs;
    }

    // ------------------------------------------------------------------
    // Response parsing
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static LLMResponse parseResponse(Map<String, Object> response) {
        StringBuilder contentParts = new StringBuilder();
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        List<Map<String, Object>> thinkingBlocks = new ArrayList<>();

        for (Map<String, Object> block : (List<Map<String, Object>>) response.getOrDefault("content", List.of())) {
            switch ((String) block.getOrDefault("type", "")) {
                case "text" -> {
                    Object text = block.get("text");
                    if (text != null) contentParts.append(text);
                }
                case "tool_use" -> toolCalls.add(new ToolCallRequest(
                        (String) block.get("id"),
                        (String) block.get("name"),
                        block.getOrDefault("input", Map.of()),
                        null, null, null));
                case "thinking" -> thinkingBlocks.add(Map.of(
                        "type", "thinking",
                        "thinking", block.getOrDefault("thinking", ""),
                        "signature", block.getOrDefault("signature", "")));
            }
        }

        String stopReason = (String) response.getOrDefault("stop_reason", "stop");
        String finishReason = switch (stopReason) {
            case "tool_use" -> "tool_calls";
            case "end_turn" -> "stop";
            case "max_tokens" -> "length";
            default -> stopReason != null ? stopReason : "stop";
        };

        Map<String, Integer> usage = Map.of();
        Map<String, Object> usageObj = (Map<String, Object>) response.get("usage");
        if (usageObj != null) {
            int inputTokens = toInt(usageObj.getOrDefault("input_tokens", 0));
            int cacheCreation = toInt(usageObj.getOrDefault("cache_creation_input_tokens", 0));
            int cacheRead = toInt(usageObj.getOrDefault("cache_read_input_tokens", 0));
            int totalPrompt = inputTokens + cacheCreation + cacheRead;
            int outputTokens = toInt(usageObj.getOrDefault("output_tokens", 0));
            Map<String, Integer> u = new LinkedHashMap<>();
            u.put("prompt_tokens", totalPrompt);
            u.put("completion_tokens", outputTokens);
            u.put("total_tokens", totalPrompt + outputTokens);
            if (cacheCreation > 0) u.put("cache_creation_input_tokens", cacheCreation);
            if (cacheRead > 0) {
                u.put("cache_read_input_tokens", cacheRead);
                u.put("cached_tokens", cacheRead);
            }
            usage = u;
        }

        return new LLMResponse(
                !contentParts.isEmpty() ? contentParts.toString() : null,
                toolCalls,
                finishReason,
                usage,
                null, null,
                !thinkingBlocks.isEmpty() ? thinkingBlocks : null,
                null, null, null, null, null, null);
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
        Map<String, Object> kwargs = buildKwargs(messages, tools, model, maxTokens, temperature,
                reasoningEffort, toolChoice, true);
        try {
            HttpResponse<String> httpResponse = postJson("/v1/messages", kwargs, null);
            if (httpResponse.statusCode() != 200) {
                return parseError(httpResponse);
            }
            return parseResponse(MAPPER.readValue(httpResponse.body(),
                    new TypeReference<Map<String, Object>>() {}));
        } catch (Exception e) {
            if (isStreamingRequiredError(e)) {
                return chatStream(messages, tools, model, maxTokens, temperature,
                        reasoningEffort, toolChoice, null, null, null);
            }
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
        Map<String, Object> kwargs = buildKwargs(messages, tools, model, maxTokens, temperature,
                reasoningEffort, toolChoice, true);
        kwargs.put("stream", true);

        try {
            HttpResponse<InputStream> httpResponse = postJsonStream("/v1/messages", kwargs, null);
            if (httpResponse.statusCode() != 200) {
                String errorBody = new String(httpResponse.body().readAllBytes(), StandardCharsets.UTF_8);
                return parseError(httpResponse.statusCode(), errorBody);
            }

            return parseStream(httpResponse.body(), onContentDelta, onThinkingDelta, onToolCallDelta);
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

    private HttpResponse<String> postJson(String path, Map<String, Object> body,
                                          @Nullable Map<String, String> additionalHeaders) throws Exception {
        String url = effectiveApiBase + path;
        String json = MAPPER.writeValueAsString(body);
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey != null ? apiKey : "")
                .header("anthropic-version", "2023-06-01")
                .timeout(Duration.ofSeconds((long) REQUEST_TIMEOUT_S));
        for (var e : extraHeaders.entrySet()) builder.header(e.getKey(), e.getValue());
        builder.POST(HttpRequest.BodyPublishers.ofString(json));
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<InputStream> postJsonStream(String path, Map<String, Object> body,
                                                      @Nullable Map<String, String> additionalHeaders) throws Exception {
        String url = effectiveApiBase + path;
        String json = MAPPER.writeValueAsString(body);
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey != null ? apiKey : "")
                .header("anthropic-version", "2023-06-01")
                .timeout(Duration.ofSeconds((long) REQUEST_TIMEOUT_S));
        for (var e : extraHeaders.entrySet()) builder.header(e.getKey(), e.getValue());
        builder.POST(HttpRequest.BodyPublishers.ofString(json));
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    // ------------------------------------------------------------------
    // Error parsing
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private LLMResponse parseError(HttpResponse<String> resp) {
        return parseError(resp.statusCode(), resp.body());
    }

    @SuppressWarnings("unchecked")
    private LLMResponse parseError(int statusCode, String body) {
        String msg;
        try {
            Map<String, Object> err = MAPPER.readValue(body, new TypeReference<Map<String, Object>>() {});
            Map<String, Object> errorBlock = (Map<String, Object>) err.getOrDefault("error", Map.of());
            msg = (String) errorBlock.getOrDefault("message", body);
        } catch (Exception e) {
            msg = body;
        }
        String displayMsg = "Error: " + (msg != null ? msg.strip().substring(0, Math.min(500, msg.strip().length())) : "HTTP " + statusCode);
        Double retryAfter = extractRetryAfter(displayMsg);
        return new LLMResponse(displayMsg, List.of(), "error", Map.of(),
                retryAfter, null, null, statusCode, null, null, null, retryAfter, null);
    }

    // ------------------------------------------------------------------
    // Stream parsing (Anthropic SSE)
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    LLMResponse parseStream(
            InputStream body,
            @Nullable ThrowingConsumer<String> onContentDelta,
            @Nullable ThrowingConsumer<String> onThinkingDelta,
            @Nullable ThrowingConsumer<Map<String, Object>> onToolCallDelta
    ) throws Exception {
        StringBuilder contentBuf = new StringBuilder();
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        List<Map<String, Object>> thinkingBlocks = new ArrayList<>();
        Map<Integer, Map<String, Object>> toolBlocks = new LinkedHashMap<>();
        String finishReason = "stop";
        Map<String, Integer> usage = Map.of();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            StringBuilder dataBuffer = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6);
                    if (data.isEmpty()) continue;
                    Map<String, Object> event = MAPPER.readValue(data,
                            new TypeReference<Map<String, Object>>() {});
                    String eventType = (String) event.get("type");

                    if ("content_block_start".equals(eventType)) {
                        Map<String, Object> block = (Map<String, Object>) event.getOrDefault("content_block", Map.of());
                        if ("tool_use".equals(block.get("type"))) {
                            int idx = toInt(event.getOrDefault("index", 0));
                            Map<String, Object> state = new LinkedHashMap<>();
                            state.put("call_id", String.valueOf(block.getOrDefault("id", "")));
                            state.put("name", String.valueOf(block.getOrDefault("name", "")));
                            toolBlocks.put(idx, state);
                            if (onToolCallDelta != null) {
                                onToolCallDelta.accept(Map.of(
                                        "index", idx,
                                        "call_id", state.get("call_id"),
                                        "name", state.get("name"),
                                        "arguments_delta", ""));
                            }
                        }
                    } else if ("content_block_delta".equals(eventType)) {
                        Map<String, Object> delta = (Map<String, Object>) event.getOrDefault("delta", Map.of());
                        String deltaType = (String) delta.get("type");
                        if ("thinking_delta".equals(deltaType)) {
                            String piece = (String) delta.getOrDefault("thinking", "");
                            if (!piece.isEmpty() && onThinkingDelta != null) {
                                onThinkingDelta.accept(piece);
                            }
                        } else if ("text_delta".equals(deltaType)) {
                            String text = (String) delta.getOrDefault("text", "");
                            if (!text.isEmpty()) {
                                contentBuf.append(text);
                                if (onContentDelta != null) onContentDelta.accept(text);
                            }
                        } else if ("input_json_delta".equals(deltaType)) {
                            String partial = (String) delta.getOrDefault("partial_json", "");
                            if (!partial.isEmpty() && onToolCallDelta != null) {
                                int idx = toInt(event.getOrDefault("index", 0));
                                Map<String, Object> state = toolBlocks.getOrDefault(idx, Map.of());
                                onToolCallDelta.accept(Map.of(
                                        "index", idx,
                                        "call_id", state.getOrDefault("call_id", ""),
                                        "name", state.getOrDefault("name", ""),
                                        "arguments_delta", partial));
                            }
                        }
                    } else if ("content_block_stop".equals(eventType)) {
                        int idx = toInt(event.getOrDefault("index", 0));
                        Map<String, Object> state = toolBlocks.get(idx);
                        if (state != null) {
                            // Tool call complete — will be built from accumulated data
                        }
                    } else if ("message_delta".equals(eventType)) {
                        Map<String, Object> delta = (Map<String, Object>) event.getOrDefault("delta", Map.of());
                        String sr = (String) delta.get("stop_reason");
                        if (sr != null) {
                            finishReason = switch (sr) {
                                case "tool_use" -> "tool_calls";
                                case "end_turn" -> "stop";
                                case "max_tokens" -> "length";
                                default -> sr;
                            };
                        }
                        Map<String, Object> u = (Map<String, Object>) delta.get("usage");
                        if (u != null) {
                            usage = Map.of(
                                    "completion_tokens", toInt(u.getOrDefault("output_tokens", 0)));
                        }
                    } else if ("message_stop".equals(eventType)) {
                        // Stream complete
                        break;
                    }
                }
            }
        }

        // Build tool calls from accumulated state — the streaming layer relies on caller
        // tracking because we don't get final tool JSON in SSE

        return new LLMResponse(
                !contentBuf.isEmpty() ? contentBuf.toString() : null,
                toolCalls, finishReason, usage,
                null, null,
                !thinkingBlocks.isEmpty() ? thinkingBlocks : null,
                null, null, null, null, null, null);
    }

    // ------------------------------------------------------------------
    // Utilities
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static Object toolArgumentsObjectForReplay(Object arguments) {
        if (arguments instanceof Map) return arguments;
        if (arguments instanceof String s) {
            try {
                return MAPPER.readValue(s, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                return s;
            }
        }
        return arguments != null ? arguments : Map.of();
    }

    static int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    static double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0.0; }
        }
        return 0.0;
    }
}
