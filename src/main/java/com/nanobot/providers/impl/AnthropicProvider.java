package com.nanobot.providers.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.LLMResponse;
import com.nanobot.providers.ToolCallRequest;
import com.nanobot.providers.ToolArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * LLM provider，使用 Anthropic Messages API 调用 Claude 模型。
 *
 * <p>对标 Python {@code nanobot/providers/anthropic_provider.py AnthropicProvider}（694 行）。
 * 处理消息格式转换（OpenAI → Anthropic Messages API）、prompt 缓存、extended thinking、
 * 工具调用、流式处理。
 *
 * <p>使用 JDK HttpClient 发起 HTTP 请求（对标 Python 的 httpx/AsyncAnthropic SDK）。
 * 所有 public API 方法返回 CompletableFuture 以忠实复刻 Python async def 语义。
 */
public class AnthropicProvider extends LLMProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ALPHANUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, String> STOP_MAP = Map.of(
            "tool_use", "tool_calls", "end_turn", "stop", "max_tokens", "length");
    private static final Map<String, Integer> THINKING_BUDGET_MAP = Map.of(
            "low", 1024, "medium", 4096, "high", 8192);
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String DEFAULT_API_BASE = "https://api.anthropic.com";

    private final String defaultModel;
    private final Map<String, String> extraHeaders;
    private final String baseUrl;
    private final HttpClient httpClient;

    /**
     * 构造 AnthropicProvider。
     *
     * @param apiKey       Anthropic API 密钥
     * @param apiBase      自定义 API base URL（可为 null，使用默认值）
     * @param defaultModel 默认模型名
     * @param extraHeaders 额外 HTTP 头部
     */
    // 对标 Python AnthropicProvider.__init__()
    public AnthropicProvider(
            String apiKey,
            String apiBase,
            String defaultModel,
            Map<String, String> extraHeaders) {
        super(apiKey, apiBase);
        this.defaultModel = (defaultModel != null) ? defaultModel : "claude-sonnet-4-20250514";
        this.extraHeaders = (extraHeaders != null) ? new HashMap<>(extraHeaders) : Map.of();
        this.baseUrl = normalizeBaseUrl(
                (apiBase != null && !apiBase.isEmpty()) ? apiBase : DEFAULT_API_BASE);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Anthropic SDK 在内部追加 /v1 到请求路径，此处规范化。
     * Python httpx base_url 行为与 HttpClient 不同——直接使用原始 URL。
     */
    private static String normalizeBaseUrl(String apiBase) {
        String normalized = apiBase.replaceAll("/+$", "");
        if (normalized.endsWith("/v1")) {
            return normalized.substring(0, normalized.length() - 3);
        }
        return normalized;
    }

    @Override
    public String getDefaultModel() {
        return defaultModel;
    }

    // =========================================================================
    // 消息转换：OpenAI chat 格式 → Anthropic Messages API
    // =========================================================================

    @SuppressWarnings("unchecked")
    Object[] convertMessages(List<Map<String, Object>> messages) {
        Object system = "";
        List<Map<String, Object>> raw = new ArrayList<>();

        for (Map<String, Object> msg : messages) {
            String role = (String) msg.getOrDefault("role", "");
            Object content = msg.get("content");

            switch (role) {
                case "system" -> system = (content instanceof String || content instanceof List)
                        ? content : String.valueOf(content != null ? content : "");
                case "tool" -> {
                    Map<String, Object> block = toolResultBlock(msg);
                    if (!raw.isEmpty() && "user".equals(raw.get(raw.size() - 1).get("role"))) {
                        Map<String, Object> prev = raw.get(raw.size() - 1);
                        Object prevC = prev.get("content");
                        if (prevC instanceof List<?> l) {
                            ((List<Object>) l).add(block);
                        } else {
                            List<Object> newContent = new ArrayList<>();
                            newContent.add(Map.of("type", "text", "text",
                                    prevC != null ? String.valueOf(prevC) : ""));
                            newContent.add(block);
                            prev.put("content", newContent);
                        }
                    } else {
                        raw.add(Map.of("role", "user", "content", new ArrayList<>(List.of(block))));
                    }
                }
                case "assistant" ->
                        raw.add(Map.of("role", "assistant", "content", assistantBlocks(msg)));
                case "user" ->
                        raw.add(Map.of("role", "user", "content", convertUserContent(content)));
            }
        }
        return new Object[]{system, mergeConsecutive(raw)};
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> toolResultBlock(Map<String, Object> msg) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", msg.getOrDefault("tool_call_id", ""));
        Object content = msg.get("content");
        if (content instanceof List<?>) {
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

        List<Map<String, Object>> thinkingBlocks =
                (List<Map<String, Object>>) msg.get("thinking_blocks");
        if (thinkingBlocks != null) {
            for (Map<String, Object> tb : thinkingBlocks) {
                if ("thinking".equals(tb.get("type"))) {
                    Map<String, Object> block = new LinkedHashMap<>();
                    block.put("type", "thinking");
                    block.put("thinking", tb.getOrDefault("thinking", ""));
                    block.put("signature", tb.getOrDefault("signature", ""));
                    blocks.add(block);
                }
            }
        }

        if (content instanceof String s && !s.isEmpty()) {
            blocks.add(Map.of("type", "text", "text", s));
        } else if (content instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> m) blocks.add((Map<String, Object>) m);
                else blocks.add(Map.of("type", "text", "text", String.valueOf(item)));
            }
        }

        List<Map<String, Object>> toolCalls =
                (List<Map<String, Object>>) msg.get("tool_calls");
        if (toolCalls != null) {
            for (Map<String, Object> tc : toolCalls) {
                Map<String, Object> func = (Map<String, Object>)
                        tc.getOrDefault("function", Map.of());
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "tool_use");
                block.put("id", tc.getOrDefault("id", genToolId()));
                block.put("name", func.getOrDefault("name", ""));
                block.put("input", ToolArguments.toolArgumentsObjectForReplay(
                        func.get("arguments")));
                blocks.add(block);
            }
        }
        return blocks.isEmpty() ? List.of(Map.of("type", "text", "text", "")) : blocks;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> convertUserContent(Object content) {
        if (content instanceof String s) {
            return List.of(Map.of("type", "text", "text",
                    !s.isEmpty() ? s : "(empty)"));
        }
        if (content == null) {
            return List.of(Map.of("type", "text", "text", "(empty)"));
        }
        if (!(content instanceof List<?> items)) {
            return List.of(Map.of("type", "text", "text", String.valueOf(content)));
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> m)) {
                result.add(Map.of("type", "text", "text", String.valueOf(item)));
                continue;
            }
            Map<String, Object> block = (Map<String, Object>) m;
            if ("image_url".equals(block.get("type"))) {
                Map<String, Object> converted = convertImageBlock(block);
                if (converted != null) result.add(converted);
                continue;
            }
            if (block.get("type") == null) {
                result.add(Map.of("type", "text", "text", String.valueOf(block)));
                continue;
            }
            result.add(block);
        }
        return result.isEmpty() ? List.of(Map.of("type", "text", "text", "(empty)")) : result;
    }

    private static final java.util.regex.Pattern DATA_URL =
            java.util.regex.Pattern.compile("data:(image/\\w+);base64,(.+)",
                    java.util.regex.Pattern.DOTALL);

    @SuppressWarnings("unchecked")
    static Map<String, Object> convertImageBlock(Map<String, Object> block) {
        Map<String, Object> imageUrl = (Map<String, Object>)
                block.getOrDefault("image_url", Map.of());
        String url = (String) imageUrl.getOrDefault("url", "");
        if (url == null || url.isEmpty()) return null;
        java.util.regex.Matcher m = DATA_URL.matcher(url);
        if (m.matches()) {
            return Map.of("type", "image", "source", Map.of(
                    "type", "base64", "media_type", m.group(1), "data", m.group(2)));
        }
        return Map.of("type", "image", "source", Map.of("type", "url", "url", url));
    }

    // =========================================================================
    // 连续消息合并
    // =========================================================================

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> mergeConsecutive(List<Map<String, Object>> msgs) {
        List<Map<String, Object>> merged = new ArrayList<>();
        for (Map<String, Object> msg : msgs) {
            if (!merged.isEmpty()
                    && Objects.equals(merged.get(merged.size() - 1).get("role"), msg.get("role"))) {
                Map<String, Object> prev = merged.get(merged.size() - 1);
                Object prevC = prev.get("content");
                Object curC = msg.get("content");
                if (prevC instanceof String s) {
                    prevC = new ArrayList<>(List.of(Map.of("type", "text", "text", s)));
                    prev.put("content", prevC);
                }
                if (curC instanceof String s) {
                    curC = new ArrayList<>(List.of(Map.of("type", "text", "text", s)));
                }
                if (curC instanceof List<?> l) ((List<Object>) prevC).addAll(l);
            } else {
                merged.add(new LinkedHashMap<>(msg));
            }
        }

        Map<String, Object> lastPopped = null;
        while (!merged.isEmpty()
                && "assistant".equals(merged.get(merged.size() - 1).get("role"))) {
            lastPopped = merged.remove(merged.size() - 1);
        }
        if (merged.isEmpty() && lastPopped != null && !hasToolUse(lastPopped)) {
            merged.add(Map.of("role", "user", "content", lastPopped.get("content")));
        }
        if (!merged.isEmpty()
                && "assistant".equals(merged.get(0).get("role"))
                && !hasToolUse(merged.get(0))) {
            merged.add(0, Map.of("role", "user", "content", "(conversation continued)"));
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    private static boolean hasToolUse(Map<String, Object> msg) {
        Object content = msg.get("content");
        if (!(content instanceof List<?> list)) return false;
        return list.stream().anyMatch(b ->
                b instanceof Map<?, ?> m && "tool_use".equals(((Map<String, Object>) m).get("type")));
    }

    // =========================================================================
    // 工具转换
    // =========================================================================

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> convertTools(List<Map<String, Object>> tools) {
        if (tools == null || tools.isEmpty()) return null;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            Map<String, Object> func = tool.containsKey("function")
                    ? (Map<String, Object>) tool.get("function") : tool;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", func.getOrDefault("name", ""));
            entry.put("input_schema", func.getOrDefault("parameters",
                    Map.of("type", "object", "properties", Map.of())));
            Object desc = func.get("description");
            if (desc instanceof String s && !s.isEmpty()) entry.put("description", s);
            if (tool.containsKey("cache_control")) {
                entry.put("cache_control", tool.get("cache_control"));
            }
            result.add(entry);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> convertToolChoice(Object toolChoice, boolean thinkingEnabled) {
        if (thinkingEnabled) return Map.of("type", "auto");
        if (toolChoice == null || "auto".equals(toolChoice)) return Map.of("type", "auto");
        if ("required".equals(toolChoice)) return Map.of("type", "any");
        if ("none".equals(toolChoice)) return null;
        if (toolChoice instanceof Map<?, ?> m) {
            Map<String, Object> tcMap = (Map<String, Object>) m;
            Map<String, Object> func = (Map<String, Object>) tcMap.get("function");
            if (func != null && func.get("name") instanceof String name) {
                return Map.of("type", "tool", "name", name);
            }
        }
        return Map.of("type", "auto");
    }

    // =========================================================================
    // Prompt 缓存
    // =========================================================================

    @SuppressWarnings("unchecked")
    static Object[] applyCacheControl(
            Object system, List<Map<String, Object>> messages,
            List<Map<String, Object>> tools) {
        Map<String, Object> marker = Map.of("type", "ephemeral");

        Object cachedSystem = system;
        if (system instanceof String s && !s.isEmpty()) {
            cachedSystem = List.of(Map.of("type", "text", "text", s, "cache_control", marker));
        } else if (system instanceof List<?> l && !l.isEmpty()) {
            List<Map<String, Object>> sysList = new ArrayList<>((List<Map<String, Object>>) l);
            Map<String, Object> last = new LinkedHashMap<>(sysList.get(sysList.size() - 1));
            last.put("cache_control", marker);
            sysList.set(sysList.size() - 1, last);
            cachedSystem = sysList;
        }

        List<Map<String, Object>> newMsgs = new ArrayList<>(messages);
        if (newMsgs.size() >= 3) {
            int idx = newMsgs.size() - 2;
            Map<String, Object> msg = new LinkedHashMap<>(newMsgs.get(idx));
            Object c = msg.get("content");
            if (c instanceof String s) {
                msg.put("content", List.of(
                        Map.of("type", "text", "text", s, "cache_control", marker)));
            } else if (c instanceof List<?> l) {
                List<Object> nc = new ArrayList<>(l);
                Map<String, Object> lastBlock = new LinkedHashMap<>((Map<String, Object>) nc.get(nc.size() - 1));
                lastBlock.put("cache_control", marker);
                nc.set(nc.size() - 1, lastBlock);
                msg.put("content", nc);
            }
            newMsgs.set(idx, msg);
        }

        List<Map<String, Object>> newTools = (tools != null) ? new ArrayList<>(tools) : null;
        if (newTools != null) {
            for (int idx : toolCacheMarkerIndices(newTools)) {
                Map<String, Object> tool = new LinkedHashMap<>(newTools.get(idx));
                tool.put("cache_control", marker);
                newTools.set(idx, tool);
            }
        }
        return new Object[]{cachedSystem, newMsgs, newTools};
    }

    public static List<Integer> toolCacheMarkerIndices(List<Map<String, Object>> tools) {
        if (tools == null || tools.isEmpty()) return List.of();
        int tailIdx = tools.size() - 1;
        Integer lastBuiltinIdx = null;
        for (int i = tailIdx; i >= 0; i--) {
            String name = toolName(tools.get(i));
            if (!name.startsWith("mcp_")) { lastBuiltinIdx = i; break; }
        }
        List<Integer> result = new ArrayList<>();
        for (Integer idx : new Integer[]{lastBuiltinIdx, tailIdx}) {
            if (idx != null && !result.contains(idx)) result.add(idx);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static String toolName(Map<String, Object> tool) {
        Map<String, Object> func = tool.containsKey("function")
                ? (Map<String, Object>) tool.get("function") : tool;
        return (String) func.getOrDefault("name", "");
    }

    // =========================================================================
    // 构建 API 请求 body
    // =========================================================================

    @SuppressWarnings("unchecked")
    Map<String, Object> buildBody(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort,
            Object toolChoice,
            boolean supportsCaching) {
        String modelName = stripPrefix((model != null) ? model : defaultModel);
        Object[] converted = convertMessages(sanitizeEmptyContent(messages));
        Object system = converted[0];
        List<Map<String, Object>> anthropicMsgs = (List<Map<String, Object>>) converted[1];
        List<Map<String, Object>> anthropicTools = convertTools(tools);

        if (supportsCaching) {
            Object[] cached = applyCacheControl(system, anthropicMsgs, anthropicTools);
            system = cached[0];
            anthropicMsgs = (List<Map<String, Object>>) cached[1];
            anthropicTools = (List<Map<String, Object>>) cached[2];
        }

        int effectiveMaxTokens = Math.max(1, maxTokens);
        boolean thinkingEnabled = (reasoningEffort != null)
                && !reasoningEffort.equalsIgnoreCase("none");
        boolean omitTemperature = modelName.contains("opus-4-7");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("messages", anthropicMsgs);
        body.put("max_tokens", effectiveMaxTokens);

        if (system instanceof String s && !s.isEmpty()) {
            body.put("system", s);
        } else if (system instanceof List && !((List<?>) system).isEmpty()) {
            body.put("system", system);
        }

        if ("adaptive".equals(reasoningEffort)) {
            body.put("thinking", Map.of("type", "adaptive"));
            if (!omitTemperature) body.put("temperature", 1.0);
        } else if (thinkingEnabled) {
            int budget = THINKING_BUDGET_MAP.getOrDefault(reasoningEffort.toLowerCase(), 4096);
            if ("high".equalsIgnoreCase(reasoningEffort)) {
                budget = Math.max(8192, effectiveMaxTokens);
            }
            body.put("thinking", Map.of("type", "enabled", "budget_tokens", budget));
            body.put("max_tokens", Math.max(effectiveMaxTokens, budget + 4096));
            if (!omitTemperature) body.put("temperature", 1.0);
        } else if (!omitTemperature) {
            body.put("temperature", temperature);
        }

        if (anthropicTools != null && !anthropicTools.isEmpty()) {
            body.put("tools", anthropicTools);
            Map<String, Object> tc = convertToolChoice(toolChoice, thinkingEnabled);
            if (tc != null) body.put("tool_choice", tc);
        }

        return body;
    }

    static String stripPrefix(String model) {
        if (model != null && model.startsWith("anthropic/")) {
            return model.substring("anthropic/".length());
        }
        return model;
    }

    private static String genToolId() {
        StringBuilder sb = new StringBuilder("toolu_");
        for (int i = 0; i < 22; i++) sb.append(ALPHANUM.charAt(RANDOM.nextInt(ALPHANUM.length())));
        return sb.toString();
    }

    // =========================================================================
    // HTTP 请求构建 & 发送
    // =========================================================================

    private HttpRequest.Builder newRequest(String pathSuffix) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/messages" + pathSuffix))
                .header("x-api-key", apiKey != null ? apiKey : "")
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120));
    }

    private void addExtraHeaders(HttpRequest.Builder builder) {
        for (var entry : extraHeaders.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
    }

    // =========================================================================
    // 响应解析
    // =========================================================================

    @SuppressWarnings("unchecked")
    static LLMResponse parseResponse(Map<String, Object> responseBody) {
        StringBuilder contentParts = new StringBuilder();
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        List<Map<String, Object>> thinkingBlocks = new ArrayList<>();

        List<Map<String, Object>> content = (List<Map<String, Object>>) responseBody.get("content");
        if (content != null) {
            for (Map<String, Object> block : content) {
                String type = (String) block.get("type");
                if ("text".equals(type)) {
                    contentParts.append((String) block.getOrDefault("text", ""));
                } else if ("tool_use".equals(type)) {
                    toolCalls.add(new ToolCallRequest(
                            (String) block.get("id"),
                            (String) block.get("name"),
                            ToolArguments.parseToolArguments(block.get("input")),
                            null, null, null));
                } else if ("thinking".equals(type)) {
                    Map<String, Object> tb = new LinkedHashMap<>();
                    tb.put("type", "thinking");
                    tb.put("thinking", block.getOrDefault("thinking", ""));
                    tb.put("signature", block.getOrDefault("signature", ""));
                    thinkingBlocks.add(tb);
                }
            }
        }

        String stopReason = (String) responseBody.getOrDefault("stop_reason", "stop");
        String finishReason = STOP_MAP.getOrDefault(stopReason, stopReason);

        Map<String, Integer> usage = Map.of();
        Map<String, Object> usageRaw = (Map<String, Object>) responseBody.get("usage");
        if (usageRaw != null) {
            int inputTokens = ((Number) usageRaw.getOrDefault("input_tokens", 0)).intValue();
            int cacheCreation = ((Number) usageRaw.getOrDefault("cache_creation_input_tokens", 0)).intValue();
            int cacheRead = ((Number) usageRaw.getOrDefault("cache_read_input_tokens", 0)).intValue();
            int outputTokens = ((Number) usageRaw.getOrDefault("output_tokens", 0)).intValue();
            int totalPrompt = inputTokens + cacheCreation + cacheRead;
            Map<String, Integer> usageMap = new LinkedHashMap<>();
            usageMap.put("prompt_tokens", totalPrompt);
            usageMap.put("completion_tokens", outputTokens);
            usageMap.put("total_tokens", totalPrompt + outputTokens);
            if (cacheCreation > 0) usageMap.put("cache_creation_input_tokens", cacheCreation);
            if (cacheRead > 0) {
                usageMap.put("cache_read_input_tokens", cacheRead);
                usageMap.put("cached_tokens", cacheRead);
            }
            usage = usageMap;
        }

        return new LLMResponse(
                !contentParts.isEmpty() ? contentParts.toString() : null,
                toolCalls, finishReason, usage,
                null, null,
                thinkingBlocks.isEmpty() ? null : thinkingBlocks,
                null, null, null, null, null, null);
    }

    // =========================================================================
    // 错误处理
    // =========================================================================

    static LLMResponse handleError(Exception e, Integer statusCode, String responseBody) {
        String errorKind = null;
        String errorName = e.getClass().getSimpleName().toLowerCase();
        if (errorName.contains("timeout")) errorKind = "timeout";
        else if (errorName.contains("connection") || errorName.contains("io")) errorKind = "connection";

        String msg;
        if (responseBody != null && !responseBody.isEmpty()) {
            msg = "Error: " + responseBody;
        } else {
            msg = "Error calling LLM: " + e.getMessage();
        }
        if (msg.length() > 500) msg = msg.substring(0, 500);

        Double retryAfter = extractRetryAfter(msg);
        String[] typeCode = extractErrorTypeCode(responseBody != null ? responseBody : "");

        return new LLMResponse(
                msg, new ArrayList<>(), "error", new HashMap<>(),
                retryAfter, null, null,
                statusCode, errorKind, typeCode[0], typeCode[1],
                retryAfter, null);
    }

    private static boolean isStreamingRequiredError(String responseBody) {
        return responseBody != null
                && responseBody.toLowerCase().contains("streaming is required");
    }

    // =========================================================================
    // chat / chatStream
    // =========================================================================

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<LLMResponse> chat(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort,
            Object toolChoice) {
        Map<String, Object> body = buildBody(
                messages, tools, model, maxTokens, temperature,
                reasoningEffort, toolChoice, true);

        return CompletableFuture.supplyAsync(() -> {
            try {
                String jsonBody = JSON.writeValueAsString(body);
                HttpRequest.Builder reqBuilder = newRequest("");
                addExtraHeaders(reqBuilder);
                HttpRequest request = reqBuilder
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();

                HttpResponse<String> httpResponse = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString());

                if (httpResponse.statusCode() >= 200 && httpResponse.statusCode() < 300) {
                    Map<String, Object> respBody = JSON.readValue(
                            httpResponse.body(), new TypeReference<Map<String, Object>>() {});
                    return parseResponse(respBody);
                }

                // 检查 streaming required 错误
                if (httpResponse.statusCode() == 400
                        && isStreamingRequiredError(httpResponse.body())) {
                    return chatStream(messages, tools, model, maxTokens, temperature,
                            reasoningEffort, toolChoice, null, null, null).join();
                }

                return handleError(
                        new RuntimeException("HTTP " + httpResponse.statusCode()),
                        httpResponse.statusCode(), httpResponse.body());
            } catch (JsonProcessingException e) {
                return handleError(e, null, e.getMessage());
            } catch (IOException | InterruptedException e) {
                return handleError((Exception) e, null, e.getMessage());
            }
        });
    }

    @Override
    @SuppressWarnings("unchecked")
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
        Map<String, Object> body = buildBody(
                messages, tools, model, maxTokens, temperature,
                reasoningEffort, toolChoice, true);
        body.put("stream", true);

        int idleTimeoutS = Integer.parseInt(
                System.getenv().getOrDefault("NANOBOT_STREAM_IDLE_TIMEOUT_S", "90"));

        return CompletableFuture.supplyAsync(() -> {
            try {
                String jsonBody = JSON.writeValueAsString(body);
                HttpRequest.Builder reqBuilder = newRequest("");
                addExtraHeaders(reqBuilder);
                HttpRequest request = reqBuilder
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();

                HttpResponse<java.io.InputStream> httpResponse = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofInputStream());

                if (httpResponse.statusCode() >= 400) {
                    return handleError(
                            new RuntimeException("HTTP " + httpResponse.statusCode()),
                            httpResponse.statusCode(),
                            new String(httpResponse.body().readAllBytes()));
                }

                // 解析 SSE 流
                Map<Integer, Map<String, String>> toolBlocks = new HashMap<>();
                List<Map<String, Object>> contentAcc = new ArrayList<>();
                String stopReason = "stop";
                Map<String, Object> usageRaw = null;

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(httpResponse.body()))) {
                    String line;
                    StringBuilder dataBuf = new StringBuilder();

                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            dataBuf.append(line.substring(6));
                        } else if (line.isEmpty() && dataBuf.length() > 0) {
                            String eventData = dataBuf.toString();
                            dataBuf.setLength(0);

                            Map<String, Object> event = JSON.readValue(
                                    eventData, new TypeReference<Map<String, Object>>() {});

                            String eventType = (String) event.get("type");
                            if ("content_block_start".equals(eventType)) {
                                Map<String, Object> contentBlock =
                                        (Map<String, Object>) event.get("content_block");
                                contentAcc.add(contentBlock);
                                if (contentBlock != null
                                        && "tool_use".equals(contentBlock.get("type"))) {
                                    int index = ((Number) event.getOrDefault("index", 0)).intValue();
                                    toolBlocks.put(index, Map.of(
                                            "call_id", (String) contentBlock.getOrDefault("id", ""),
                                            "name", (String) contentBlock.getOrDefault("name", "")));
                                    if (onToolCallDelta != null) {
                                        onToolCallDelta.onDelta(Map.of(
                                                "index", index,
                                                "call_id", contentBlock.getOrDefault("id", ""),
                                                "name", contentBlock.getOrDefault("name", ""),
                                                "arguments_delta", "")).join();
                                    }
                                }
                            } else if ("content_block_delta".equals(eventType)) {
                                Map<String, Object> delta =
                                        (Map<String, Object>) event.get("delta");
                                if (delta == null) continue;
                                String deltaType = (String) delta.get("type");
                                if ("thinking_delta".equals(deltaType)) {
                                    String thinking = (String) delta.get("thinking");
                                    if (thinking != null && !thinking.isEmpty()
                                            && onThinkingDelta != null) {
                                        onThinkingDelta.onDelta(thinking).join();
                                    }
                                } else if ("text_delta".equals(deltaType)) {
                                    String text = (String) delta.get("text");
                                    if (text != null && !text.isEmpty()
                                            && onContentDelta != null) {
                                        onContentDelta.onDelta(text).join();
                                    }
                                } else if ("input_json_delta".equals(deltaType)) {
                                    String partialJson = (String) delta.get("partial_json");
                                    if (partialJson != null && !partialJson.isEmpty()
                                            && onToolCallDelta != null) {
                                        int index = ((Number) event.getOrDefault("index", 0)).intValue();
                                        Map<String, String> state = toolBlocks.getOrDefault(
                                                index, Map.of());
                                        onToolCallDelta.onDelta(Map.of(
                                                "index", index,
                                                "call_id", state.getOrDefault("call_id", ""),
                                                "name", state.getOrDefault("name", ""),
                                                "arguments_delta", partialJson)).join();
                                    }
                                }
                            } else if ("content_block_stop".equals(eventType)) {
                                int index = ((Number) event.getOrDefault("index", 0)).intValue();
                                if (index < contentAcc.size() && toolBlocks.containsKey(index)) {
                                    // 工具调用块可能有后续内容，由 message_delta 汇总
                                }
                            } else if ("message_delta".equals(eventType)) {
                                Map<String, Object> delta =
                                        (Map<String, Object>) event.get("delta");
                                if (delta != null) {
                                    stopReason = (String) delta.getOrDefault(
                                            "stop_reason", stopReason);
                                }
                                Map<String, Object> u = (Map<String, Object>) event.get("usage");
                                if (u != null) usageRaw = u;
                            } else if ("message_stop".equals(eventType)) {
                                break;
                            }
                        }
                    }
                }

                // 从累积内容构建最终响应
                return buildFinalResponse(contentAcc, stopReason, usageRaw);
            } catch (JsonProcessingException e) {
                return handleError(e, null, e.getMessage());
            } catch (IOException e) {
                return handleError(e, null, e.getMessage());
            } catch (InterruptedException e) {
                return handleError(e, null, e.getMessage());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static LLMResponse buildFinalResponse(
            List<Map<String, Object>> contentAcc, String stopReason,
            Map<String, Object> usageRaw) {
        StringBuilder contentParts = new StringBuilder();
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        List<Map<String, Object>> thinkingBlocks = new ArrayList<>();

        for (Map<String, Object> block : contentAcc) {
            String type = (String) block.get("type");
            if ("text".equals(type)) {
                contentParts.append((String) block.getOrDefault("text", ""));
            } else if ("tool_use".equals(type)) {
                toolCalls.add(new ToolCallRequest(
                        (String) block.get("id"),
                        (String) block.get("name"),
                        ToolArguments.parseToolArguments(block.get("input")),
                        null, null, null));
            } else if ("thinking".equals(type)) {
                Map<String, Object> tb = new LinkedHashMap<>();
                tb.put("type", "thinking");
                tb.put("thinking", block.getOrDefault("thinking", ""));
                tb.put("signature", block.getOrDefault("signature", ""));
                thinkingBlocks.add(tb);
            }
        }

        String finishReason = STOP_MAP.getOrDefault(stopReason, stopReason);

        Map<String, Integer> usage = Map.of();
        if (usageRaw != null) {
            int inputTokens = ((Number) usageRaw.getOrDefault("input_tokens", 0)).intValue();
            int outputTokens = ((Number) usageRaw.getOrDefault("output_tokens", 0)).intValue();
            usage = Map.of("prompt_tokens", inputTokens,
                    "completion_tokens", outputTokens,
                    "total_tokens", inputTokens + outputTokens);
        }

        return new LLMResponse(
                !contentParts.isEmpty() ? contentParts.toString() : null,
                toolCalls, finishReason, usage,
                null, null,
                thinkingBlocks.isEmpty() ? null : thinkingBlocks,
                null, null, null, null, null, null);
    }
}
