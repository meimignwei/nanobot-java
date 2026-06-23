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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Azure OpenAI provider，使用 Responses API 端点。
 *
 * <p>对标 Python {@code nanobot/providers/azure_openai_provider.py AzureOpenAIProvider}（253 行）。
 * 支持静态 API key 和 AAD（DefaultAzureCredential）token 认证两种模式。
 * 调用 {@code https://{endpoint}/openai/v1/responses} 端点。
 *
 * <p>使用 JDK HttpClient 发起请求，复用 openai_responses 共享的消息/工具/SSE 转换逻辑。
 */
public class AzureOpenAiProvider extends LLMProvider {

    private static final Logger log = LoggerFactory.getLogger(AzureOpenAiProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AZURE_OPENAI_SCOPE = "https://cognitiveservices.azure.com/.default";

    private final String defaultModel;
    private final String apiBase;
    private final boolean aadAuth;
    private final HttpClient httpClient;

    /**
     * 构造 AzureOpenAIProvider。
     *
     * @param apiKey       API key（空字符串表示使用 AAD 认证）
     * @param apiBase      Azure OpenAI 端点 URL（必填）
     * @param defaultModel 默认部署/模型名
     */
    // 对标 Python AzureOpenAIProvider.__init__()
    public AzureOpenAiProvider(String apiKey, String apiBase, String defaultModel) {
        super((apiKey != null && !apiKey.isEmpty()) ? apiKey : "", apiBase);
        this.defaultModel = (defaultModel != null && !defaultModel.isEmpty())
                ? defaultModel : "gpt-5.2-chat";

        if (apiBase == null || apiBase.isEmpty()) {
            throw new IllegalArgumentException("Azure OpenAI api_base is required");
        }
        // 规范化：确保尾部斜杠
        if (!apiBase.endsWith("/")) {
            apiBase += "/";
        }
        this.apiBase = apiBase;
        this.aadAuth = (apiKey == null || apiKey.isEmpty());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public String getDefaultModel() {
        return defaultModel;
    }

    // =========================================================================
    // 认证
    // =========================================================================

    /**
     * 判断是否支持 temperature 参数。
     * GPT-5 / o-series 推理激活时不发送 temperature。
     *
     * @param deploymentName  部署名
     * @param reasoningEffort reasoning 力度
     * @return true 如果可能支持 temperature
     */
    // 对标 Python _supports_temperature()
    static boolean supportsTemperature(String deploymentName, String reasoningEffort) {
        if (reasoningEffort != null && !reasoningEffort.equalsIgnoreCase("none")) {
            return false;
        }
        String name = deploymentName.toLowerCase();
        return !(name.contains("gpt-5") || name.contains("o1")
                || name.contains("o3") || name.contains("o4"));
    }

    /**
     * 获取 Bearer token。非 AAD 模式返回 apiKey，AAD 模式尝试 DefaultAzureCredential。
     *
     * @return Bearer token 字符串
     */
    // 对标 Python _AzureTokenProvider
    private String getAccessToken() {
        if (!aadAuth) return apiKey;

        // 尝试 azure-identity DefaultAzureCredential（反射调用，避免硬依赖）
        try {
            Class<?> dacClass = Class.forName("com.azure.identity.DefaultAzureCredentialBuilder");
            Object credential = dacClass.getMethod("build").invoke(
                    dacClass.getDeclaredConstructor().newInstance());
            Class<?> tokenRequestClass = Class.forName(
                    "com.azure.core.credential.TokenRequestContext");
            Object tokenRequest = tokenRequestClass.getConstructor().newInstance();
            tokenRequestClass.getMethod("addScopes", String[].class)
                    .invoke(tokenRequest, (Object) new String[]{AZURE_OPENAI_SCOPE});
            Object token = credential.getClass().getMethod("getTokenSync", tokenRequestClass)
                    .invoke(credential, tokenRequest);
            return (String) token.getClass().getMethod("getToken").invoke(token);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Azure OpenAI AAD authentication requires azure-identity on classpath. "
                            + "Add com.azure:azure-identity dependency.", e);
        }
    }

    // =========================================================================
    // 构建请求 body
    // =========================================================================

    /**
     * 从 Chat Completions 风格参数构建 Responses API 请求 body。
     *
     * @param messages        消息列表
     * @param tools           工具定义列表
     * @param model           模型/部署名
     * @param maxTokens       最大 token 数
     * @param temperature     温度
     * @param reasoningEffort reasoning 力度
     * @param toolChoice      工具选择
     * @return 请求 body Map
     */
    // 对标 Python _build_body()
    @SuppressWarnings("unchecked")
    Map<String, Object> buildBody(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort,
            Object toolChoice) {
        String deployment = (model != null) ? model : defaultModel;
        Object[] converted = convertMessages(sanitizeEmptyContent(messages));
        Object instructions = converted[0];
        List<Map<String, Object>> inputItems = (List<Map<String, Object>>) converted[1];

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", deployment);
        body.put("instructions",
                instructions instanceof String s && !s.isEmpty() ? s : null);
        body.put("input", inputItems);
        body.put("max_output_tokens", Math.max(1, maxTokens));
        body.put("store", false);
        body.put("stream", false);

        if (supportsTemperature(deployment, reasoningEffort)) {
            body.put("temperature", temperature);
        }

        if (reasoningEffort != null && !reasoningEffort.equalsIgnoreCase("none")) {
            body.put("reasoning", Map.of("effort", reasoningEffort));
            body.put("include", List.of("reasoning.encrypted_content"));
        }

        if (tools != null && !tools.isEmpty()) {
            body.put("tools", convertTools(tools));
            body.put("tool_choice", toolChoice != null ? toolChoice : "auto");
        }

        return body;
    }

    // =========================================================================
    // HTTP 请求
    // =========================================================================

    private HttpResponse<java.io.InputStream> postJson(
            String path, Map<String, Object> body) throws IOException, InterruptedException {
        String token = getAccessToken();
        String jsonBody = JSON.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + "openai/v1/" + path))
                .header("api-key", token)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    // =========================================================================
    // 响应解析
    // =========================================================================

    @SuppressWarnings("unchecked")
    static LLMResponse parseResponse(Map<String, Object> body) {
        StringBuilder contentParts = new StringBuilder();
        List<ToolCallRequest> toolCalls = new ArrayList<>();

        List<Map<String, Object>> outputs = (List<Map<String, Object>>) body.get("output");
        if (outputs != null) {
            for (Map<String, Object> output : outputs) {
                String type = (String) output.get("type");
                if ("message".equals(type)) {
                    List<Map<String, Object>> contentItems =
                            (List<Map<String, Object>>) output.get("content");
                    if (contentItems != null) {
                        for (Map<String, Object> item : contentItems) {
                            if ("output_text".equals(item.get("type"))) {
                                Object text = item.get("text");
                                if (text != null) contentParts.append(text);
                            }
                        }
                    }
                } else if ("function_call".equals(type)) {
                    toolCalls.add(new ToolCallRequest(
                            (String) output.get("call_id"),
                            (String) output.get("name"),
                            ToolArguments.parseToolArguments(output.get("arguments")),
                            null, null, null));
                }
            }
        }

        String finishReason = (String) body.getOrDefault("status",
                "completed".equals(body.get("status")) ? "stop" : "stop");

        Map<String, Integer> usage = Map.of();
        Map<String, Object> usageRaw = (Map<String, Object>) body.get("usage");
        if (usageRaw != null) {
            int input = ((Number) usageRaw.getOrDefault("input_tokens", 0)).intValue();
            int output = ((Number) usageRaw.getOrDefault("output_tokens", 0)).intValue();
            usage = new LinkedHashMap<>();
            usage.put("prompt_tokens", input);
            usage.put("completion_tokens", output);
            usage.put("total_tokens", input + output);
        }

        return new LLMResponse(
                !contentParts.isEmpty() ? contentParts.toString() : null,
                toolCalls, finishReason, usage,
                null, null, null,
                null, null, null, null, null, null);
    }

    // 错误处理
    static LLMResponse handleError(Exception e, String responseBody) {
        String bodyText = (responseBody != null) ? responseBody.strip() : "";
        String msg = !bodyText.isEmpty()
                ? "Error: " + (bodyText.length() > 500 ? bodyText.substring(0, 500) : bodyText)
                : "Error calling Azure OpenAI: " + e.getMessage();

        Double retryAfter = extractRetryAfter(msg);

        return new LLMResponse(
                msg, new ArrayList<>(), "error", new HashMap<>(),
                retryAfter, null, null,
                null, null, null, null,
                retryAfter, null);
    }

    // =========================================================================
    // 消息 & 工具转换（Responses API 格式）
    // =========================================================================

    @SuppressWarnings("unchecked")
    static Object[] convertMessages(List<Map<String, Object>> messages) {
        String instructions = "";
        List<Map<String, Object>> inputItems = new ArrayList<>();

        for (Map<String, Object> msg : messages) {
            String role = (String) msg.getOrDefault("role", "");
            Object content = msg.get("content");

            if ("system".equals(role)) {
                if (content instanceof String s) instructions = s;
                else if (content instanceof List<?> l) {
                    StringBuilder sb = new StringBuilder();
                    for (Object item : l) {
                        if (item instanceof Map<?, ?> m) {
                            Object textVal = m.get("text");
                            if (textVal != null) sb.append(textVal);
                        }
                    }
                    instructions = sb.toString();
                }
                continue;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            if ("assistant".equals(role)) {
                item.put("role", "assistant");
                item.put("content", content instanceof String s ? s : String.valueOf(content));
            } else if ("tool".equals(role)) {
                item.put("role", "tool");
                item.put("call_id", msg.getOrDefault("tool_call_id", ""));
                item.put("content", content instanceof String s ? s : String.valueOf(content));
            } else {
                item.put("role", "user");
                item.put("content", convertUserContent(content));
            }
            inputItems.add(item);
        }

        return new Object[]{instructions, inputItems};
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> convertUserContent(Object content) {
        if (content instanceof String s) {
            return List.of(Map.of("type", "input_text", "text", !s.isEmpty() ? s : "(empty)"));
        }
        if (content == null) {
            return List.of(Map.of("type", "input_text", "text", "(empty)"));
        }
        if (!(content instanceof List<?> items)) {
            return List.of(Map.of("type", "input_text", "text", String.valueOf(content)));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> m) {
                Map<String, Object> block = (Map<String, Object>) m;
                String type = (String) block.get("type");
                if ("image_url".equals(type)) {
                    Map<String, Object> imageUrl =
                            (Map<String, Object>) block.getOrDefault("image_url", Map.of());
                    String url = (String) imageUrl.getOrDefault("url", "");
                    if (url != null && !url.isEmpty()) {
                        result.add(Map.of("type", "input_image", "image_url", url));
                    }
                } else {
                    Object textVal = block.get("text");
                    result.add(Map.of("type", "input_text", "text",
                            textVal != null ? String.valueOf(textVal) : ""));
                }
            } else {
                result.add(Map.of("type", "input_text", "text", String.valueOf(item)));
            }
        }
        return result.isEmpty()
                ? List.of(Map.of("type", "input_text", "text", "(empty)"))
                : result;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> convertTools(List<Map<String, Object>> tools) {
        if (tools == null || tools.isEmpty()) return null;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            Map<String, Object> func = tool.containsKey("function")
                    ? (Map<String, Object>) tool.get("function") : tool;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", "function");
            entry.put("name", func.getOrDefault("name", ""));
            entry.put("parameters", func.getOrDefault("parameters",
                    Map.of("type", "object", "properties", Map.of())));
            Object desc = func.get("description");
            if (desc instanceof String s && !s.isEmpty()) entry.put("description", s);
            result.add(entry);
        }
        return result;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    @Override
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
                reasoningEffort, toolChoice);

        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpResponse<java.io.InputStream> httpResponse = postJson("responses", body);

                if (httpResponse.statusCode() >= 200 && httpResponse.statusCode() < 300) {
                    String responseBody = new String(
                            httpResponse.body().readAllBytes(), StandardCharsets.UTF_8);
                    Map<String, Object> respMap = JSON.readValue(
                            responseBody, new TypeReference<Map<String, Object>>() {});
                    return parseResponse(respMap);
                }

                String errorBody = new String(
                        httpResponse.body().readAllBytes(), StandardCharsets.UTF_8);
                return handleError(
                        new RuntimeException("HTTP " + httpResponse.statusCode()),
                        errorBody);
            } catch (Exception e) {
                return handleError(e, null);
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
                reasoningEffort, toolChoice);
        body.put("stream", true);

        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpResponse<java.io.InputStream> httpResponse = postJson("responses", body);

                if (httpResponse.statusCode() >= 400) {
                    String errorBody = new String(
                            httpResponse.body().readAllBytes(), StandardCharsets.UTF_8);
                    return handleError(
                            new RuntimeException("HTTP " + httpResponse.statusCode()),
                            errorBody);
                }

                // 解析 SSE 流
                StringBuilder contentBuilder = new StringBuilder();
                List<ToolCallRequest> toolCalls = new ArrayList<>();
                String finishReason = "stop";
                Map<String, Integer> usage = Map.of();

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

                            if ("[DONE]".equals(eventData.trim())) break;

                            try {
                                Map<String, Object> event = JSON.readValue(
                                        eventData, new TypeReference<Map<String, Object>>() {});
                                processSSEEvent(event,
                                        onContentDelta, onThinkingDelta, onToolCallDelta,
                                        contentBuilder, toolCalls);
                            } catch (JsonProcessingException ignored) {}
                        }
                    }
                }

                return new LLMResponse(
                        !contentBuilder.isEmpty() ? contentBuilder.toString() : null,
                        toolCalls, finishReason, usage,
                        null, null, null,
                        null, null, null, null, null, null);
            } catch (Exception e) {
                return handleError(e, null);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void processSSEEvent(
            Map<String, Object> event,
            ContentDeltaCallback onContentDelta,
            ContentDeltaCallback onThinkingDelta,
            ToolCallDeltaCallback onToolCallDelta,
            StringBuilder contentBuilder,
            List<ToolCallRequest> toolCalls) {
        String eventType = (String) event.get("type");

        if ("response.output_text.delta".equals(eventType)) {
            String delta = (String) event.get("delta");
            if (delta != null && !delta.isEmpty()) {
                contentBuilder.append(delta);
                if (onContentDelta != null) onContentDelta.onDelta(delta).join();
            }
        } else if ("response.reasoning_text.delta".equals(eventType)) {
            String delta = (String) event.get("delta");
            if (delta != null && !delta.isEmpty() && onThinkingDelta != null) {
                onThinkingDelta.onDelta(delta).join();
            }
        } else if ("response.tool_call_started".equals(eventType)) {
            Map<String, Object> tc = (Map<String, Object>) event.get("tool_call");
            if (tc != null && onToolCallDelta != null) {
                onToolCallDelta.onDelta(Map.of(
                        "call_id", tc.getOrDefault("call_id", ""),
                        "name", tc.getOrDefault("name", ""),
                        "arguments_delta", "")).join();
            }
        } else if ("response.tool_call_arguments.delta".equals(eventType)) {
            String delta = (String) event.get("delta");
            String callId = (String) event.get("call_id");
            if (delta != null && !delta.isEmpty() && onToolCallDelta != null) {
                onToolCallDelta.onDelta(Map.of(
                        "call_id", callId != null ? callId : "",
                        "arguments_delta", delta)).join();
            }
        } else if ("response.completed".equals(eventType)) {
            Map<String, Object> response = (Map<String, Object>) event.get("response");
            if (response != null) {
                List<Map<String, Object>> outputs =
                        (List<Map<String, Object>>) response.get("output");
                if (outputs != null) {
                    for (Map<String, Object> output : outputs) {
                        if ("function_call".equals(output.get("type"))) {
                            toolCalls.add(new ToolCallRequest(
                                    (String) output.get("call_id"),
                                    (String) output.get("name"),
                                    ToolArguments.parseToolArguments(output.get("arguments")),
                                    null, null, null));
                        }
                    }
                }
            }
        }
    }
}
