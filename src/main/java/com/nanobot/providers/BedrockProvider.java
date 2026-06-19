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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * AWS Bedrock Converse provider.
 * Full port of Python BedrockProvider (bedrock_provider.py, 755 lines).
 *
 * Uses java.net.http.HttpClient with AWS SigV4 signing to call the
 * Bedrock Converse and ConverseStream APIs.
 */
public class BedrockProvider extends LLMProvider {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double REQUEST_TIMEOUT_S = 120.0;
    private static final int IDLE_TIMEOUT_S = Integer.parseInt(
            System.getenv().getOrDefault("NANOBOT_STREAM_IDLE_TIMEOUT_S", "90"));

    private static final Pattern IMAGE_DATA_URL = Pattern.compile(
            "^data:image/([a-zA-Z0-9.+-]+);base64,(.*)$", Pattern.DOTALL);
    private static final Set<String> TEXT_BLOCK_TYPES = Set.of("text", "input_text", "output_text");
    private static final Set<String> TEMPERATURE_UNSUPPORTED_TOKENS = Set.of("claude-opus-4-7");
    private static final Set<String> ADAPTIVE_THINKING_ONLY_TOKENS = Set.of("claude-opus-4-7");
    private static final String NOOP_TOOL_NAME = "nanobot_noop";

    private final String defaultModel;
    private final String region;
    @Nullable
    private final String profile;
    private final Map<String, Object> extraBody;
    private final String accessKey;
    private final String secretKey;
    @Nullable
    private final String sessionToken;

    public BedrockProvider(
            @Nullable String apiKey,
            @Nullable String apiBase,
            String defaultModel,
            @Nullable String region,
            @Nullable String profile,
            @Nullable Map<String, Object> extraBody
    ) {
        super(apiKey, apiBase);
        this.defaultModel = defaultModel;
        this.region = region != null ? region
                : System.getenv().getOrDefault("AWS_REGION",
                        System.getenv().getOrDefault("AWS_DEFAULT_REGION", "us-east-1"));
        this.profile = profile;
        this.extraBody = extraBody != null ? extraBody : Map.of();

        // Resolve AWS credentials from env vars (matching boto3 resolution chain)
        this.accessKey = System.getenv().getOrDefault("AWS_ACCESS_KEY_ID",
                System.getenv().getOrDefault("AWS_ACCESS_KEY", ""));
        this.secretKey = System.getenv().getOrDefault("AWS_SECRET_ACCESS_KEY",
                System.getenv().getOrDefault("AWS_SECRET_KEY", ""));
        String tok = System.getenv().get("AWS_SESSION_TOKEN");
        this.sessionToken = (tok != null && !tok.isEmpty()) ? tok : null;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    static String stripPrefix(String model) {
        if (model.startsWith("bedrock/")) return model.substring("bedrock/".length());
        return model;
    }

    static boolean matchesModelToken(String model, Set<String> tokens) {
        String ml = model.toLowerCase();
        return tokens.stream().anyMatch(ml::contains);
    }

    static boolean supportsTemperature(String model) {
        return !matchesModelToken(model, TEMPERATURE_UNSUPPORTED_TOKENS);
    }

    static boolean usesAdaptiveThinkingOnly(String model) {
        return matchesModelToken(model, ADAPTIVE_THINKING_ONLY_TOKENS);
    }

    // ------------------------------------------------------------------
    // Content block conversion
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static Map<String, Object> imageUrlBlock(Map<String, Object> block) {
        Map<String, Object> iu = (Map<String, Object>) block.getOrDefault("image_url", Map.of());
        String url = (String) iu.getOrDefault("url", "");
        if (url == null || url.isEmpty()) return null;

        var m = IMAGE_DATA_URL.matcher(url);
        if (!m.matches()) return Map.of("text", "(image URL: " + url + ")");

        String fmt = m.group(1).toLowerCase();
        if ("jpg".equals(fmt)) fmt = "jpeg";
        byte[] data;
        try {
            data = Base64.getDecoder().decode(m.group(2));
        } catch (Exception e) {
            return Map.of("text", "(invalid image data)");
        }
        return Map.of("image", Map.of("format", fmt, "source", Map.of("bytes", data)));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> contentBlocks(Object content, boolean forToolResult) {
        if (content instanceof String || content == null) {
            return List.of(Map.of("text", content != null ? content : "(empty)"));
        }
        if (!(content instanceof List<?> items)) {
            if (forToolResult && content instanceof Map) {
                return List.of(Map.of("json", content));
            }
            return List.of(Map.of("text", String.valueOf(content)));
        }

        List<Map<String, Object>> blocks = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map)) {
                blocks.add(Map.of("text", String.valueOf(item)));
                continue;
            }
            Map<String, Object> block = (Map<String, Object>) item;
            String itemType = (String) block.get("type");
            if (itemType != null && TEXT_BLOCK_TYPES.contains(itemType) || block.containsKey("text")) {
                Object text = block.get("text");
                if (text != null) blocks.add(Map.of("text", String.valueOf(text)));
                continue;
            }
            if ("image_url".equals(itemType)) {
                Map<String, Object> converted = imageUrlBlock(block);
                if (converted != null) blocks.add(converted);
                continue;
            }
            // Preserve already-Bedrock-shaped content where possible
            boolean found = false;
            for (String key : new String[]{"text", "image", "document", "video", "json", "searchResult"}) {
                if (block.containsKey(key)) {
                    blocks.add(Map.of(key, block.get(key)));
                    found = true;
                    break;
                }
            }
            if (!found) {
                if (forToolResult) {
                    blocks.add(Map.of("json", block));
                } else {
                    try {
                        blocks.add(Map.of("text", MAPPER.writeValueAsString(block)));
                    } catch (JsonProcessingException e) {
                        blocks.add(Map.of("text", String.valueOf(block)));
                    }
                }
            }
        }
        return !blocks.isEmpty() ? blocks : List.of(Map.of("text", "(empty)"));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> systemBlocks(Object content) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> block : contentBlocks(content, false)) {
            if (block.containsKey("text") || block.containsKey("cachePoint") || block.containsKey("guardContent")) {
                result.add(block);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> toolResultBlock(Map<String, Object> msg) {
        return Map.of("toolResult", Map.of(
                "toolUseId", String.valueOf(msg.getOrDefault("tool_call_id", "")),
                "content", contentBlocks(msg.get("content"), true),
                "status", "success"));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> toolUseBlock(Map<String, Object> toolCall) {
        Map<String, Object> function = (Map<String, Object>) toolCall.get("function");
        if (!(function instanceof Map)) return null;
        Object args = toolArgumentsObjectForReplay(function.getOrDefault("arguments", Map.of()));
        return Map.of("toolUse", Map.of(
                "toolUseId", String.valueOf(toolCall.getOrDefault("id", "")),
                "name", String.valueOf(function.getOrDefault("name", "")),
                "input", args));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> reasoningBlock(Map<String, Object> block) {
        String type = (String) block.get("type");
        if (!Set.of("thinking", "reasoning", "redacted_thinking").contains(type)) return null;
        Object text = block.getOrDefault("thinking", block.get("text"));
        Object signature = block.get("signature");
        if (text instanceof String t && !t.isEmpty() && signature instanceof String s && !s.isEmpty()) {
            return Map.of("reasoningContent", Map.of(
                    "reasoningText", Map.of("text", t, "signature", s)));
        }
        Object redacted = block.get("redactedContent");
        if (redacted == null && block.get("redactedContentBase64") instanceof String b64) {
            try {
                redacted = Base64.getDecoder().decode(b64);
            } catch (Exception e) {
                redacted = null;
            }
        }
        if (redacted != null) {
            return Map.of("reasoningContent", Map.of("redactedContent", redacted));
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> assistantBlocks(Map<String, Object> msg) {
        List<Map<String, Object>> blocks = new ArrayList<>();

        for (Map<String, Object> thinking : (List<Map<String, Object>>) msg.getOrDefault("thinking_blocks", List.of())) {
            if (thinking instanceof Map) {
                Map<String, Object> rb = reasoningBlock(thinking);
                if (rb != null) blocks.add(rb);
            }
        }

        Object content = msg.get("content");
        if (content instanceof String s && !s.isEmpty()) {
            blocks.add(Map.of("text", s));
        } else if (content instanceof List lst) {
            for (Object item : lst) {
                if (item instanceof Map m && m.containsKey("text")) {
                    blocks.add(m);
                }
            }
        }

        for (Map<String, Object> tc : (List<Map<String, Object>>) msg.getOrDefault("tool_calls", List.of())) {
            if (tc instanceof Map) {
                Map<String, Object> block = toolUseBlock(tc);
                if (block != null) blocks.add(block);
            }
        }

        return !blocks.isEmpty() ? blocks : List.of(Map.of("text", ""));
    }

    @SuppressWarnings("unchecked")
    static boolean hasToolUse(Map<String, Object> msg) {
        Object content = msg.get("content");
        return content instanceof List list && list.stream().anyMatch(
                b -> b instanceof Map && ((Map<String, Object>) b).containsKey("toolUse"));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> mergeConsecutive(List<Map<String, Object>> messages) {
        List<Map<String, Object>> merged = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            if (!merged.isEmpty() && Objects.equals(merged.get(merged.size() - 1).get("role"), msg.get("role"))) {
                Map<String, Object> prev = new LinkedHashMap<>(merged.get(merged.size() - 1));
                prev.putIfAbsent("content", new ArrayList<>());
                List<Object> prevContent = new ArrayList<>((List<Object>) prev.get("content"));
                Object cur = msg.getOrDefault("content", List.of());
                if (cur instanceof List l) {
                    prevContent.addAll(l);
                } else {
                    prevContent.add(Map.of("text", String.valueOf(cur)));
                }
                prev.put("content", prevContent);
                merged.set(merged.size() - 1, prev);
            } else {
                merged.add(new LinkedHashMap<>(msg));
            }
        }

        Map<String, Object> lastPopped = null;
        while (!merged.isEmpty() && "assistant".equals(merged.get(merged.size() - 1).get("role"))) {
            lastPopped = merged.remove(merged.size() - 1);
        }
        if (merged.isEmpty() && lastPopped != null && !hasToolUse(lastPopped)) {
            merged.add(Map.of("role", "user", "content",
                    lastPopped.getOrDefault("content", List.of(Map.of("text", "(empty)")))));
        }
        if (!merged.isEmpty() && "assistant".equals(merged.get(0).get("role")) && !hasToolUse(merged.get(0))) {
            merged.add(0, Map.of("role", "user", "content", List.of(Map.of("text", "(conversation continued)"))));
        }
        return merged;
    }

    // ------------------------------------------------------------------
    // Message conversion
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    Object[] convertMessages(List<Map<String, Object>> messages) {
        List<Map<String, Object>> system = new ArrayList<>();
        List<Map<String, Object>> converted = new ArrayList<>();

        for (Map<String, Object> msg : messages) {
            String role = (String) msg.getOrDefault("role", "");
            Object content = msg.get("content");

            if ("system".equals(role)) {
                system.addAll(systemBlocks(content));
                continue;
            }
            if ("tool".equals(role)) {
                Map<String, Object> block = toolResultBlock(msg);
                if (!converted.isEmpty() && "user".equals(converted.get(converted.size() - 1).get("role"))) {
                    converted.get(converted.size() - 1).computeIfAbsent("content", k -> new ArrayList<>());
                    ((List<Object>) converted.get(converted.size() - 1).get("content")).add(block);
                } else {
                    converted.add(Map.of("role", "user", "content", new ArrayList<>(List.of(block))));
                }
                continue;
            }
            if ("assistant".equals(role)) {
                converted.add(Map.of("role", "assistant", "content", assistantBlocks(msg)));
                continue;
            }
            if ("user".equals(role)) {
                converted.add(Map.of("role", "user", "content", contentBlocks(content, false)));
            }
        }
        return new Object[]{system, mergeConsecutive(converted)};
    }

    // ------------------------------------------------------------------
    // Tool conversion
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> convertTools(@Nullable List<Map<String, Object>> tools) {
        if (tools == null || tools.isEmpty()) return null;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            Map<String, Object> func = "function".equals(tool.get("type")) && tool.get("function") instanceof Map
                    ? (Map<String, Object>) tool.get("function")
                    : tool;
            if (!(func instanceof Map)) continue;
            String name = String.valueOf(func.getOrDefault("name", ""));
            if (name.isEmpty()) continue;
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("name", name);
            spec.put("inputSchema", Map.of("json",
                    func.getOrDefault("parameters", Map.of("type", "object", "properties", Map.of()))));
            if (func.get("description") != null) spec.put("description", String.valueOf(func.get("description")));
            Object strict = func.getOrDefault("strict", tool.get("strict"));
            if (strict instanceof Boolean) spec.put("strict", strict);
            result.add(Map.of("toolSpec", spec));
        }
        return !result.isEmpty() ? result : null;
    }

    @SuppressWarnings("unchecked")
    static boolean containsToolBlocks(List<Map<String, Object>> messages) {
        for (Map<String, Object> msg : messages) {
            Object content = msg.get("content");
            if (!(content instanceof List list)) continue;
            for (Object block : list) {
                if (block instanceof Map m && (m.containsKey("toolUse") || m.containsKey("toolResult"))) {
                    return true;
                }
            }
        }
        return false;
    }

    static Map<String, Object> noopTool() {
        return Map.of("toolSpec", Map.of(
                "name", NOOP_TOOL_NAME,
                "description", "Internal placeholder for Bedrock tool history validation.",
                "inputSchema", Map.of("json", Map.of("type", "object", "properties", Map.of()))));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> convertToolChoice(@Nullable Object toolChoice) {
        if (toolChoice == null || "auto".equals(toolChoice)) return Map.of("auto", Map.of());
        if ("required".equals(toolChoice)) return Map.of("any", Map.of());
        if ("none".equals(toolChoice)) return null;
        if (toolChoice instanceof Map m) {
            Map<String, Object> fn = (Map<String, Object>) m.getOrDefault("function", Map.of());
            Object name = fn.get("name");
            if (name != null && !String.valueOf(name).isEmpty()) {
                return Map.of("tool", Map.of("name", String.valueOf(name)));
            }
        }
        return Map.of("auto", Map.of());
    }

    static Map<String, Object> adaptiveThinking(@Nullable String reasoningEffort) {
        if (reasoningEffort == null) return null;
        String effort = reasoningEffort.toLowerCase();
        if ("none".equals(effort)) return null;
        Map<String, Object> thinking = new LinkedHashMap<>();
        thinking.put("type", "adaptive");
        if (!"adaptive".equals(effort)) thinking.put("effort", effort);
        return thinking;
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
            @Nullable Object toolChoice
    ) {
        String modelId = stripPrefix(model != null ? model : defaultModel);
        Object[] converted = convertMessages(MessageSanitizer.sanitizeEmptyContent(messages));
        List<Map<String, Object>> system = (List<Map<String, Object>>) converted[0];
        List<Map<String, Object>> bedrockMessages = (List<Map<String, Object>>) converted[1];

        if (bedrockMessages.isEmpty()) {
            bedrockMessages = List.of(Map.of("role", "user",
                    "content", List.of(Map.of("text", "(empty)"))));
        }

        Map<String, Object> kwargs = new LinkedHashMap<>();
        kwargs.put("modelId", modelId);
        kwargs.put("messages", bedrockMessages);
        kwargs.put("inferenceConfig", Map.of("maxTokens", Math.max(1, maxTokens)));
        if (!system.isEmpty()) kwargs.put("system", system);
        if (supportsTemperature(modelId)) {
            kwargs.put("inferenceConfig", new LinkedHashMap<>(Map.of(
                    "maxTokens", Math.max(1, maxTokens),
                    "temperature", temperature)));
        }

        Map<String, Object> additional = new LinkedHashMap<>();
        if (usesAdaptiveThinkingOnly(modelId)) {
            Map<String, Object> thinking = adaptiveThinking(reasoningEffort);
            if (thinking != null) additional.put("thinking", thinking);
        }
        if (!extraBody.isEmpty()) additional.putAll(extraBody);
        if (!additional.isEmpty()) kwargs.put("additionalModelRequestFields", additional);

        List<Map<String, Object>> bedrockTools = convertTools(tools);
        Map<String, Object> toolConfig = null;
        if (bedrockTools != null) {
            toolConfig = new LinkedHashMap<>();
            toolConfig.put("tools", bedrockTools);
            Map<String, Object> choice = convertToolChoice(toolChoice);
            if (choice != null) toolConfig.put("toolChoice", choice);
        } else if (containsToolBlocks(bedrockMessages)) {
            toolConfig = Map.of("tools", List.of(noopTool()));
        }

        if (toolConfig != null) kwargs.put("toolConfig", toolConfig);

        return kwargs;
    }

    // ------------------------------------------------------------------
    // Response parsing
    // ------------------------------------------------------------------

    static String finishReason(@Nullable String stopReason) {
        return switch (stopReason != null ? stopReason : "") {
            case "end_turn" -> "stop";
            case "tool_use" -> "tool_calls";
            case "max_tokens" -> "length";
            default -> stopReason != null ? stopReason : "stop";
        };
    }

    @SuppressWarnings("unchecked")
    static Map<String, Integer> usage(Map<String, Object> usage) {
        if (usage == null) return Map.of();
        int prompt = toInt(usage.getOrDefault("inputTokens", 0));
        int completion = toInt(usage.getOrDefault("outputTokens", 0));
        int total = toInt(usage.getOrDefault("totalTokens", prompt + completion));
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("prompt_tokens", prompt);
        result.put("completion_tokens", completion);
        result.put("total_tokens", total);
        int cacheRead = toInt(usage.getOrDefault("cacheReadInputTokens", 0));
        int cacheWrite = toInt(usage.getOrDefault("cacheWriteInputTokens", 0));
        if (cacheRead > 0) {
            result.put("cached_tokens", cacheRead);
            result.put("cache_read_input_tokens", cacheRead);
        }
        if (cacheWrite > 0) result.put("cache_creation_input_tokens", cacheWrite);
        return result;
    }

    @SuppressWarnings("unchecked")
    static Object[] parseReasoning(Map<String, Object> block) {
        Map<String, Object> reasoning = (Map<String, Object>) block.get("reasoningContent");
        if (!(reasoning instanceof Map)) return new Object[]{null, null};
        Map<String, Object> textObj = (Map<String, Object>) reasoning.get("reasoningText");
        if (textObj instanceof Map) {
            Object text = textObj.get("text");
            if (text instanceof String s) {
                return new Object[]{s, Map.of(
                        "type", "thinking",
                        "thinking", s,
                        "signature", textObj.getOrDefault("signature", ""))};
            }
        }
        Object redacted = reasoning.get("redactedContent");
        if (redacted != null) {
            if (redacted instanceof byte[] bytes) {
                String encoded = Base64.getEncoder().encodeToString(bytes);
                return new Object[]{null, Map.of("type", "redacted_thinking", "redactedContentBase64", encoded)};
            }
            return new Object[]{null, Map.of("type", "redacted_thinking", "redactedContent", redacted)};
        }
        return new Object[]{null, null};
    }

    @SuppressWarnings("unchecked")
    static LLMResponse parseResponse(Map<String, Object> response) {
        StringBuilder contentParts = new StringBuilder();
        StringBuilder reasoningParts = new StringBuilder();
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        List<Map<String, Object>> thinkingBlocks = new ArrayList<>();

        Map<String, Object> output = (Map<String, Object>) response.getOrDefault("output", Map.of());
        Map<String, Object> message = (Map<String, Object>) output.getOrDefault("message", Map.of());

        for (Map<String, Object> block : (List<Map<String, Object>>) message.getOrDefault("content", List.of())) {
            if (!(block instanceof Map)) continue;
            if (block.get("text") instanceof String s) {
                contentParts.append(s);
            }
            Map<String, Object> toolUse = (Map<String, Object>) block.get("toolUse");
            if (toolUse instanceof Map) {
                toolCalls.add(new ToolCallRequest(
                        String.valueOf(toolUse.getOrDefault("toolUseId", "")),
                        String.valueOf(toolUse.getOrDefault("name", "")),
                        toolUse.getOrDefault("input", Map.of()),
                        null, null, null));
            }
            Object[] reasoningResult = parseReasoning(block);
            if (reasoningResult[0] instanceof String s) reasoningParts.append(s);
            if (reasoningResult[1] instanceof Map m) thinkingBlocks.add(m);
        }

        return new LLMResponse(
                !contentParts.isEmpty() ? contentParts.toString() : null,
                toolCalls,
                finishReason((String) response.get("stopReason")),
                usage((Map<String, Object>) response.get("usage")),
                null,
                !reasoningParts.isEmpty() ? reasoningParts.toString() : null,
                !thinkingBlocks.isEmpty() ? thinkingBlocks : null,
                null, null, null, null, null, null);
    }

    // ------------------------------------------------------------------
    // Stream parsing
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static String parseStreamEvent(
            Map<String, Object> event,
            StringBuilder contentParts,
            StringBuilder reasoningParts,
            List<Map<String, Object>> thinkingBlocks,
            Map<Integer, Map<String, Object>> toolBuffers,
            Map<String, Object> state
    ) {
        if (event.containsKey("contentBlockStart")) {
            Map<String, Object> data = (Map<String, Object>) event.get("contentBlockStart");
            int idx = toInt(data.getOrDefault("contentBlockIndex", 0));
            Map<String, Object> start = (Map<String, Object>) data.getOrDefault("start", Map.of());
            Map<String, Object> toolUse = (Map<String, Object>) start.get("toolUse");
            if (toolUse instanceof Map) {
                toolBuffers.put(idx, new LinkedHashMap<>(Map.of(
                        "id", String.valueOf(toolUse.getOrDefault("toolUseId", "")),
                        "name", String.valueOf(toolUse.getOrDefault("name", "")),
                        "input", "")));
            }
            return null;
        }

        if (event.containsKey("contentBlockDelta")) {
            Map<String, Object> data = (Map<String, Object>) event.get("contentBlockDelta");
            int idx = toInt(data.getOrDefault("contentBlockIndex", 0));
            Map<String, Object> delta = (Map<String, Object>) data.getOrDefault("delta", Map.of());
            Object text = delta.get("text");
            if (text instanceof String s) {
                contentParts.append(s);
                return s;
            }
            Map<String, Object> toolDelta = (Map<String, Object>) delta.get("toolUse");
            if (toolDelta instanceof Map) {
                Map<String, Object> buf = toolBuffers.computeIfAbsent(idx,
                        k -> new LinkedHashMap<>(Map.of("id", "", "name", "", "input", "")));
                if (toolDelta.get("input") instanceof String s) {
                    buf.put("input", buf.getOrDefault("input", "") + s);
                }
            }
            Map<String, Object> reasoning = (Map<String, Object>) delta.get("reasoningContent");
            if (reasoning instanceof Map) {
                Map<String, Object> reasoningBufs = (Map<String, Object>) state.computeIfAbsent(
                        "reasoning_buffers", k -> new LinkedHashMap<>());
                Map<String, Object> buf = (Map<String, Object>) reasoningBufs.computeIfAbsent(
                        String.valueOf(idx), k -> new LinkedHashMap<>(Map.of(
                                "text", "", "signature", "", "redactedContent", null)));
                if (reasoning.get("text") instanceof String s) {
                    buf.put("text", buf.getOrDefault("text", "") + s);
                    reasoningParts.append(s);
                }
                if (reasoning.get("signature") instanceof String s) {
                    buf.put("signature", s);
                }
                if (reasoning.containsKey("redactedContent")) {
                    buf.put("redactedContent", reasoning.get("redactedContent"));
                }
            }
            return null;
        }

        if (event.containsKey("contentBlockStop")) {
            Map<String, Object> data = (Map<String, Object>) event.get("contentBlockStop");
            int idx = toInt(data.getOrDefault("contentBlockIndex", 0));
            Map<String, Object> reasoningBufs = (Map<String, Object>) state.get("reasoning_buffers");
            if (reasoningBufs != null) {
                Map<String, Object> buf = (Map<String, Object>) reasoningBufs.remove(String.valueOf(idx));
                if (buf != null) {
                    Object rbText = buf.get("text");
                    if (rbText instanceof String s && !s.isEmpty()) {
                        thinkingBlocks.add(Map.of(
                                "type", "thinking",
                                "thinking", s,
                                "signature", buf.getOrDefault("signature", "")));
                    } else if (buf.get("redactedContent") != null) {
                        Object rc = buf.get("redactedContent");
                        if (rc instanceof byte[] bytes) {
                            thinkingBlocks.add(Map.of(
                                    "type", "redacted_thinking",
                                    "redactedContentBase64", Base64.getEncoder().encodeToString(bytes)));
                        } else {
                            thinkingBlocks.add(Map.of(
                                    "type", "redacted_thinking",
                                    "redactedContent", rc));
                        }
                    }
                }
            }
            return null;
        }

        if (event.containsKey("messageStop")) {
            Map<String, Object> data = (Map<String, Object>) event.get("messageStop");
            state.put("stop_reason", data != null ? data.get("stopReason") : null);
            return null;
        }

        if (event.containsKey("metadata")) {
            Map<String, Object> metadata = (Map<String, Object>) event.get("metadata");
            if (metadata != null && metadata.get("usage") instanceof Map) {
                state.put("usage", metadata.get("usage"));
            }
            return null;
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    static LLMResponse streamResult(
            StringBuilder contentParts,
            StringBuilder reasoningParts,
            List<Map<String, Object>> thinkingBlocks,
            Map<Integer, Map<String, Object>> toolBuffers,
            Map<String, Object> state
    ) {
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        for (Map<String, Object> buf : toolBuffers.values()) {
            Object input = buf.getOrDefault("input", "{}");
            Object args = ToolCallRequest.parseToolArguments(input);
            toolCalls.add(new ToolCallRequest(
                    String.valueOf(buf.getOrDefault("id", "")),
                    String.valueOf(buf.getOrDefault("name", "")),
                    args, null, null, null));
        }

        return new LLMResponse(
                !contentParts.isEmpty() ? contentParts.toString() : null,
                toolCalls,
                finishReason((String) state.get("stop_reason")),
                usage((Map<String, Object>) state.get("usage")),
                null,
                !reasoningParts.isEmpty() ? reasoningParts.toString() : null,
                !thinkingBlocks.isEmpty() ? thinkingBlocks : null,
                null, null, null, null, null, null);
    }

    // ------------------------------------------------------------------
    // Error handling
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static LLMResponse handleError(Exception e) {
        String errorName = e.getClass().getSimpleName().toLowerCase();
        String errorKind = null;
        if (errorName.contains("timeout")) errorKind = "timeout";
        else if (errorName.contains("connection") || errorName.contains("endpoint")) errorKind = "connection";

        String body = e.getMessage() != null ? e.getMessage() : String.valueOf(e);
        Double retryAfter = LLMProvider.extractRetryAfter(body);

        return new LLMResponse(
                "Error: " + (body.length() > 500 ? body.substring(0, 500) : body),
                List.of(), "error", Map.of(),
                retryAfter, null, null, null, errorKind, null, null, retryAfter, null);
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
            Map<String, Object> kwargs = buildKwargs(messages, tools, model, maxTokens, temperature,
                    reasoningEffort, toolChoice);
            String modelId = stripPrefix(model != null ? model : defaultModel);
            String url = apiBase != null
                    ? (apiBase + "/model/" + modelId + "/converse")
                    : ("https://bedrock-runtime." + region + ".amazonaws.com/model/" + modelId + "/converse");

            HttpResponse<String> resp = postSignedJson(url, kwargs);
            if (resp.statusCode() != 200) {
                return parseError(resp);
            }
            return parseResponse(MAPPER.readValue(resp.body(),
                    new TypeReference<Map<String, Object>>() {}));
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
        try {
            Map<String, Object> kwargs = buildKwargs(messages, tools, model, maxTokens, temperature,
                    reasoningEffort, toolChoice);
            String modelId = stripPrefix(model != null ? model : defaultModel);
            String url = apiBase != null
                    ? (apiBase + "/model/" + modelId + "/converse-stream")
                    : ("https://bedrock-runtime." + region + ".amazonaws.com/model/" + modelId + "/converse-stream");

            HttpResponse<InputStream> resp = postSignedJsonStream(url, kwargs);
            if (resp.statusCode() != 200) {
                String errorBody = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                return parseError(resp.statusCode(), errorBody);
            }

            StringBuilder contentParts = new StringBuilder();
            StringBuilder reasoningParts = new StringBuilder();
            List<Map<String, Object>> thinkingBlocks = new ArrayList<>();
            Map<Integer, Map<String, Object>> toolBuffers = new LinkedHashMap<>();
            Map<String, Object> state = new LinkedHashMap<>();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                // Bedrock ConverseStream returns JSON events separated by newlines
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    Map<String, Object> event = MAPPER.readValue(line,
                            new TypeReference<Map<String, Object>>() {});
                    String delta = parseStreamEvent(event, contentParts, reasoningParts,
                            thinkingBlocks, toolBuffers, state);
                    if (delta != null && onContentDelta != null) {
                        onContentDelta.accept(delta);
                    }
                }
            }

            return streamResult(contentParts, reasoningParts, thinkingBlocks, toolBuffers, state);
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
    // HTTP helpers with SigV4 signing
    // ------------------------------------------------------------------

    private HttpResponse<String> postSignedJson(String url, Map<String, Object> body) throws Exception {
        String json = MAPPER.writeValueAsString(body);
        URI uri = URI.create(url);
        Map<String, String> sigHeaders = AwsSigV4Signer.sign(
                accessKey, secretKey, sessionToken, region, "bedrock",
                "POST", uri, Map.of("Content-Type", "application/json"), json.getBytes(StandardCharsets.UTF_8));

        var builder = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds((long) REQUEST_TIMEOUT_S));
        sigHeaders.forEach(builder::header);
        builder.POST(HttpRequest.BodyPublishers.ofString(json));
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<InputStream> postSignedJsonStream(String url, Map<String, Object> body) throws Exception {
        String json = MAPPER.writeValueAsString(body);
        URI uri = URI.create(url);
        Map<String, String> sigHeaders = AwsSigV4Signer.sign(
                accessKey, secretKey, sessionToken, region, "bedrock",
                "POST", uri, Map.of("Content-Type", "application/json"), json.getBytes(StandardCharsets.UTF_8));

        var builder = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds((long) REQUEST_TIMEOUT_S));
        sigHeaders.forEach(builder::header);
        builder.POST(HttpRequest.BodyPublishers.ofString(json));
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    @SuppressWarnings("unchecked")
    private LLMResponse parseError(HttpResponse<String> resp) {
        return parseError(resp.statusCode(), resp.body());
    }

    @SuppressWarnings("unchecked")
    private LLMResponse parseError(int statusCode, String body) {
        String msg;
        try {
            Map<String, Object> err = MAPPER.readValue(body, new TypeReference<Map<String, Object>>() {});
            msg = String.valueOf(err.getOrDefault("message", body));
        } catch (Exception e) {
            msg = body != null ? body : "HTTP " + statusCode;
        }
        String displayMsg = "Error: " + (msg != null ? msg.strip().substring(0, Math.min(500, msg.strip().length())) : "HTTP " + statusCode);
        Double retryAfter = extractRetryAfter(displayMsg);
        return new LLMResponse(displayMsg, List.of(), "error", Map.of(),
                retryAfter, null, null, statusCode, null, null, null, retryAfter, null);
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
}
