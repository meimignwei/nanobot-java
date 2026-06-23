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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * OpenAI Codex OAuth Responses API provider。
 *
 * <p>对标 Python {@code nanobot/providers/openai_codex_provider.py OpenAICodexProvider}（323 行）。
 * 使用 OAuth token 通过原始 HTTP 调用 Codex Responses API（SSE 流式）。
 *
 * <p>SSL 验证失败时自动以 verify=false 重试。OAuth token 获取通过可重写的
 * {@link #getCodexToken()} 方法完成，默认实现从环境变量读取。
 */
public class OpenAICodexProvider extends LLMProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAICodexProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final String DEFAULT_CODEX_URL = "https://chatgpt.com/backend-api/codex/responses";
    static final String DEFAULT_ORIGINATOR = "nanobot";

    private final String defaultModel;

    /**
     * 构造 OpenAICodexProvider。
     *
     * @param defaultModel 默认模型名（可为 null，使用 "openai-codex/gpt-5.1-codex"）
     */
    // 对标 Python OpenAICodexProvider.__init__()
    public OpenAICodexProvider(String defaultModel) {
        super(null, null);
        this.defaultModel = (defaultModel != null) ? defaultModel : "openai-codex/gpt-5.1-codex";
        setSupportsProgressDeltas(true);
    }

    @Override
    public String getDefaultModel() {
        return defaultModel;
    }

    // =========================================================================
    // 核心请求逻辑
    // =========================================================================

    /**
     * chat 与 chat_stream 共享的请求逻辑。
     *
     * @param messages        消息列表
     * @param tools           工具定义列表
     * @param model           模型标识
     * @param reasoningEffort reasoning 力度
     * @param toolChoice      工具选择
     * @param onContentDelta  文本增量回调（chat 为 null）
     * @param onThinkingDelta thinking 增量回调（chat 为 null）
     * @param onToolCallDelta tool call 增量回调（chat 为 null）
     * @return LLMResponse 的 CompletableFuture
     */
    // 对标 Python _call_codex()
    private CompletableFuture<LLMResponse> callCodex(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            String reasoningEffort,
            Object toolChoice,
            ContentDeltaCallback onContentDelta,
            ContentDeltaCallback onThinkingDelta,
            ToolCallDeltaCallback onToolCallDelta) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String effectiveModel = (model != null) ? model : defaultModel;
                Object[] converted = convertMessages(messages);
                Object systemPrompt = converted[0];
                List<Map<String, Object>> inputItems =
                        (List<Map<String, Object>>) converted[1];
                List<Map<String, Object>> convertedTools = convertTools(tools);

                CodexToken token = getCodexToken();
                Map<String, String> headers = buildHeaders(token.accountId(), token.accessToken());

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", stripModelPrefix(effectiveModel));
                body.put("store", false);
                body.put("stream", true);
                body.put("instructions",
                        systemPrompt instanceof String s ? s : JSON.writeValueAsString(systemPrompt));
                body.put("input", inputItems);
                body.put("text", Map.of("verbosity", "medium"));
                body.put("include", List.of("reasoning.encrypted_content"));
                body.put("prompt_cache_key", promptCacheKey(messages));
                body.put("tool_choice", toolChoice != null ? toJsonValue(toolChoice) : "auto");
                body.put("parallel_tool_calls", true);

                Map<String, Object> reasoningOptions = buildReasoningOptions(reasoningEffort);
                if (reasoningOptions != null) {
                    body.put("reasoning", reasoningOptions);
                }
                if (convertedTools != null && !convertedTools.isEmpty()) {
                    body.put("tools", convertedTools);
                }

                try {
                    return requestCodex(DEFAULT_CODEX_URL, headers, body, true,
                            onContentDelta, onThinkingDelta, onToolCallDelta);
                } catch (Exception e) {
                    if (!e.getMessage().contains("CERTIFICATE_VERIFY_FAILED")
                            && !e.getMessage().contains("certificate")
                            && !e.getMessage().contains("SSL")) {
                        throw e;
                    }
                    log.warn("SSL verification failed for Codex API; retrying with verify=false");
                    return requestCodex(DEFAULT_CODEX_URL, headers, body, false,
                            onContentDelta, onThinkingDelta, onToolCallDelta);
                }
            } catch (Exception e) {
                log.warn("Codex API request failed: {}", e.toString());
                return codexErrorResponse(e);
            }
        });
    }

    // =========================================================================
    // OAuth token（可重写以对接实际 token 源）
    // =========================================================================

    /**
     * 获取 Codex OAuth token。默认从环境变量 CODEX_ACCOUNT_ID / CODEX_ACCESS_TOKEN 读取。
     * 对标 Python oauth_cli_kit.get_token()。
     *
     * @return CodexToken
     */
    // 对标 Python oauth_cli_kit.get_token()
    protected CodexToken getCodexToken() {
        String accountId = System.getenv("CODEX_ACCOUNT_ID");
        String access = System.getenv("CODEX_ACCESS_TOKEN");
        if (access == null || access.isEmpty()) {
            throw new RuntimeException(
                    "CODEX_ACCESS_TOKEN not set. Run oauth-cli-kit login or set env var.");
        }
        return new CodexToken(accountId != null ? accountId : "", access);
    }

    /** Codex OAuth token 数据载体。 */
    public record CodexToken(String accountId, String accessToken) {}

    // =========================================================================
    // 模型名 & reasoning
    // =========================================================================

    /**
     * 移除模型名前缀 "openai-codex/" 或 "openai_codex/"。
     *
     * @param model 原始模型名
     * @return 去除前缀后的模型名
     */
    // 对标 Python _strip_model_prefix()
    static String stripModelPrefix(String model) {
        if (model == null) return null;
        if (model.startsWith("openai-codex/") || model.startsWith("openai_codex/")) {
            return model.split("/", 2)[1];
        }
        return model;
    }

    /**
     * 构建 reasoning 选项。
     *
     * @param reasoningEffort reasoning 力度
     * @return reasoning 选项字典，null 表示无需 reasoning
     */
    // 对标 Python _build_reasoning_options()
    static Map<String, Object> buildReasoningOptions(String reasoningEffort) {
        if (reasoningEffort != null && reasoningEffort.equalsIgnoreCase("none")) {
            return Map.of("effort", "none");
        }
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("summary", "auto");
        if (reasoningEffort != null && !reasoningEffort.isEmpty()) {
            options.put("effort", reasoningEffort);
        }
        return options;
    }

    /** 构建 Codex HTTP 请求头部。对标 Python _build_headers() */
    static Map<String, String> buildHeaders(String accountId, String token) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token);
        headers.put("chatgpt-account-id", accountId);
        headers.put("OpenAI-Beta", "responses=experimental");
        headers.put("originator", DEFAULT_ORIGINATOR);
        headers.put("User-Agent", "nanobot (java)");
        headers.put("accept", "text/event-stream");
        headers.put("content-type", "application/json");
        return headers;
    }

    /**
     * 计算前两条消息的 SHA-256 hash 作为 prompt cache key。
     * 对标 Python _prompt_cache_key()。
     */
    static String promptCacheKey(List<Map<String, Object>> messages) {
        try {
            List<Map<String, Object>> head = messages.size() > 2
                    ? messages.subList(0, 2) : new ArrayList<>(messages);
            String raw = JSON.writeValueAsString(head);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            return "";
        }
    }

    // =========================================================================
    // 消息 & 工具转换（OpenAI → Responses API）
    // =========================================================================

    /**
     * 将 OpenAI chat 格式消息转换为 Codex Responses API 格式。
     *
     * @param messages 消息列表
     * @return [systemPrompt, inputItems] 数组
     */
    // 对标 Python openai_responses.convert_messages()
    @SuppressWarnings("unchecked")
    static Object[] convertMessages(List<Map<String, Object>> messages) {
        String systemPrompt = "";
        List<Map<String, Object>> inputItems = new ArrayList<>();

        for (Map<String, Object> msg : messages) {
            String role = (String) msg.getOrDefault("role", "");
            Object content = msg.get("content");

            if ("system".equals(role)) {
                if (content instanceof String s) {
                    systemPrompt = s;
                } else if (content instanceof List<?> l) {
                    StringBuilder sb = new StringBuilder();
                    for (Object item : l) {
                        if (item instanceof Map<?, ?> m && "text".equals(m.get("type"))) {
                            Object textVal = m.get("text");
                            sb.append(textVal != null ? String.valueOf(textVal) : "");
                        }
                    }
                    systemPrompt = sb.toString();
                }
                continue;
            }

            Map<String, Object> inputItem = new LinkedHashMap<>();
            if ("assistant".equals(role)) {
                inputItem.put("role", "assistant");
                inputItem.put("content",
                        content instanceof String s ? s : JSON.valueToTree(content));
            } else if ("tool".equals(role)) {
                inputItem.put("role", "tool");
                inputItem.put("call_id", msg.getOrDefault("tool_call_id", ""));
                inputItem.put("content",
                        content instanceof String s ? s : JSON.valueToTree(content));
            } else {
                // user
                inputItem.put("role", "user");
                List<Map<String, Object>> userContent = convertUserContent(content);
                inputItem.put("content", userContent);
            }
            inputItems.add(inputItem);
        }

        return new Object[]{systemPrompt, inputItems};
    }

    /**
     * 转换 user 消息内容为 Responses API 格式的内容块列表。
     *
     * @param content 原始内容
     * @return 内容块列表
     */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> convertUserContent(Object content) {
        if (content instanceof String s) {
            return List.of(Map.of("type", "input_text", "text",
                    !s.isEmpty() ? s : "(empty)"));
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
                } else if ("text".equals(type)) {
                    result.add(Map.of("type", "input_text", "text",
                            block.getOrDefault("text", "")));
                } else {
                    result.add(Map.of("type", "input_text", "text",
                            block.getOrDefault("text", String.valueOf(block))));
                }
            } else {
                result.add(Map.of("type", "input_text", "text", String.valueOf(item)));
            }
        }
        return result.isEmpty()
                ? List.of(Map.of("type", "input_text", "text", "(empty)"))
                : result;
    }

    /**
     * 将 OpenAI 格式的工具定义转换为 Responses API 格式。
     *
     * @param tools OpenAI 工具定义列表
     * @return Codex 工具定义列表
     */
    // 对标 Python openai_responses.convert_tools()
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
    // HTTP 请求 & SSE 解析
    // =========================================================================

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * 向 Codex Responses API 发送 POST 请求并解析 SSE 流式响应。
     *
     * @param url              API URL
     * @param headers          HTTP 头部
     * @param body             请求体
     * @param verify           是否验证 SSL 证书
     * @param onContentDelta   文本增量回调
     * @param onThinkingDelta  reasoning 增量回调
     * @param onToolCallDelta  tool call 增量回调
     * @return LLMResponse
     */
    // 对标 Python _request_codex() + consume_sse_with_reasoning()
    @SuppressWarnings("unchecked")
    LLMResponse requestCodex(
            String url,
            Map<String, String> headers,
            Map<String, Object> body,
            boolean verify,
            ContentDeltaCallback onContentDelta,
            ContentDeltaCallback onThinkingDelta,
            ToolCallDeltaCallback onToolCallDelta)
            throws IOException, InterruptedException {
        int idleTimeoutS = Integer.parseInt(
                System.getenv().getOrDefault("NANOBOT_STREAM_IDLE_TIMEOUT_S", "90"));

        String jsonBody = JSON.writeValueAsString(body);
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(idleTimeoutS));

        for (var entry : headers.entrySet()) {
            reqBuilder.header(entry.getKey(), entry.getValue());
        }

        HttpRequest request = reqBuilder
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<java.io.InputStream> httpResponse = httpClient.send(
                request, HttpResponse.BodyHandlers.ofInputStream());

        if (httpResponse.statusCode() != 200) {
            String raw = new String(httpResponse.body().readAllBytes(), StandardCharsets.UTF_8);
            Double retryAfter = extractRetryAfterFromHttpHeaders(httpResponse.headers().map());
            String[] typeCode = extractErrorTypeCode(raw);
            throw new CodexHttpError(
                    friendlyError(httpResponse.statusCode(), raw),
                    httpResponse.statusCode(),
                    retryAfter,
                    typeCode[0],
                    typeCode[1],
                    shouldRetryStatus(httpResponse.statusCode(), typeCode[0], typeCode[1], raw));
        }

        // 解析 SSE 流
        StringBuilder contentBuilder = new StringBuilder();
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        String finishReason = "stop";
        Map<String, Integer> usage = Map.of();
        String reasoningContent = null;

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
                    } catch (JsonProcessingException ignored) {
                        // skip unparseable events
                    }
                }
            }
        }

        // 构建最终响应
        return new LLMResponse(
                !contentBuilder.isEmpty() ? contentBuilder.toString() : null,
                toolCalls, finishReason, usage,
                null, reasoningContent, null,
                null, null, null, null, null, null);
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
            Map<String, Object> toolCall = (Map<String, Object>) event.get("tool_call");
            if (toolCall != null) {
                String callId = (String) toolCall.getOrDefault("call_id", "");
                String name = (String) toolCall.getOrDefault("name", "");
                if (onToolCallDelta != null) {
                    onToolCallDelta.onDelta(Map.of(
                            "call_id", callId, "name", name,
                            "arguments_delta", "")).join();
                }
            }
        } else if ("response.tool_call_arguments.delta".equals(eventType)
                || "response.function_call_arguments.delta".equals(eventType)) {
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
                                    ToolArguments.parseToolArguments(
                                            output.get("arguments")),
                                    null, null, null));
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // 错误处理
    // =========================================================================

    /** Codex HTTP 错误异常。对标 Python _CodexHTTPError。 */
    static class CodexHttpError extends RuntimeException {
        final int statusCode;
        final Double retryAfter;
        final String errorType;
        final String errorCode;
        final Boolean shouldRetry;

        CodexHttpError(String message, int statusCode, Double retryAfter,
                       String errorType, String errorCode, Boolean shouldRetry) {
            super(message);
            this.statusCode = statusCode;
            this.retryAfter = retryAfter;
            this.errorType = errorType;
            this.errorCode = errorCode;
            this.shouldRetry = shouldRetry;
        }
    }

    /**
     * 友好错误消息。对标 Python _friendly_error()。
     */
    static String friendlyError(int statusCode, String raw) {
        if (statusCode == 429) {
            return "ChatGPT usage quota exceeded or rate limit triggered. Please try again later.";
        }
        return "HTTP " + statusCode + ": Codex API request failed";
    }

    /**
     * 将 Codex 传输/API 失败转为带结构化元数据的错误 LLMResponse。
     * 对标 Python _codex_error_response()。
     */
    static LLMResponse codexErrorResponse(Exception exc) {
        String excType = exc instanceof CodexHttpError
                ? "CodexHTTPError" : exc.getClass().getSimpleName();
        String detail = exc.getMessage() != null ? exc.getMessage().strip() : "";

        Integer statusCode = null;
        String errorKind = null;
        String defaultDetail = null;
        Boolean shouldRetry = null;
        Double retryAfter = null;
        String errorType = null;
        String errorCode = null;

        if (exc instanceof CodexHttpError ce) {
            statusCode = ce.statusCode;
            shouldRetry = ce.shouldRetry;
            retryAfter = ce.retryAfter;
            errorType = ce.errorType;
            errorCode = ce.errorCode;
            errorKind = "http";
            defaultDetail = "HTTP request failed";
        } else if (exc instanceof java.util.concurrent.TimeoutException
                || exc instanceof java.net.http.HttpTimeoutException) {
            errorKind = "timeout";
            defaultDetail = "timed out waiting for response";
            shouldRetry = true;
        } else if (exc instanceof IOException) {
            errorKind = "connection";
            defaultDetail = "network connection failed";
            shouldRetry = true;
        }

        detail = !detail.isEmpty() ? detail : (defaultDetail != null ? defaultDetail : "unexpected error");
        String message = "Error calling Codex (" + excType + "): " + detail;
        if (retryAfter == null) {
            retryAfter = extractRetryAfter(message);
        }

        return new LLMResponse(
                message,
                new ArrayList<>(), "error", new HashMap<>(),
                retryAfter, null, null,
                statusCode, errorKind, errorType, errorCode,
                retryAfter, shouldRetry);
    }

    /**
     * 判断 HTTP 状态码是否可重试。对标 Python _should_retry_status()。
     */
    // 对标 Python _should_retry_status()
    static boolean shouldRetryStatus(
            int statusCode, String errorType, String errorCode, String content) {
        if (statusCode == 429) {
            return isRetryable429Response(new LLMResponse(
                    content != null ? content : "",
                    new ArrayList<>(), "error", new HashMap<>(),
                    null, null, null,
                    statusCode, errorKindFromInt(statusCode),
                    errorType, errorCode, null, null));
        }
        return (statusCode == 408 || statusCode == 409 || statusCode == 429) || statusCode >= 500;
    }

    // 兼容简化版 LLMResponse 的便捷构造器
    private static String errorKindFromInt(int statusCode) {
        if (statusCode == 429) return "rate_limit";
        if (statusCode >= 500) return "server_error";
        return null;
    }

    /** 从 JDK HttpClient 响应头 Map 提取 retry-after。 */
    private static Double extractRetryAfterFromHttpHeaders(Map<String, List<String>> headers) {
        for (var entry : headers.entrySet()) {
            if ("retry-after".equalsIgnoreCase(entry.getKey())) {
                List<String> values = entry.getValue();
                if (values != null && !values.isEmpty()) {
                    String raw = values.get(0);
                    try {
                        return toRetrySeconds(Integer.parseInt(raw.trim()), "s");
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private Object toJsonValue(Object value) {
        return value;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * 发送非流式 chat 请求，通过 Codex Responses API。
     *
     * <p>对标 Python OpenAICodexProvider.chat()。
     *
     * @param messages        消息列表
     * @param tools           工具定义列表
     * @param model           模型标识
     * @param maxTokens       最大 token 数（Codex ignored）
     * @param temperature     温度（Codex ignored）
     * @param reasoningEffort reasoning 力度
     * @param toolChoice      工具选择
     * @return LLMResponse 的 CompletableFuture
     */
    @Override
    public CompletableFuture<LLMResponse> chat(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort,
            Object toolChoice) {
        return callCodex(messages, tools, model, reasoningEffort, toolChoice,
                null, null, null);
    }

    /**
     * 发送流式 chat 请求，通过 Codex Responses API。
     *
     * <p>对标 Python OpenAICodexProvider.chat_stream()。
     *
     * @param messages        消息列表
     * @param tools           工具定义列表
     * @param model           模型标识
     * @param maxTokens       最大 token 数（Codex ignored）
     * @param temperature     温度（Codex ignored）
     * @param reasoningEffort reasoning 力度
     * @param toolChoice      工具选择
     * @param onContentDelta  文本增量回调
     * @param onThinkingDelta thinking 增量回调
     * @param onToolCallDelta tool call 增量回调
     * @return LLMResponse 的 CompletableFuture
     */
    @Override
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
        return callCodex(messages, tools, model, reasoningEffort, toolChoice,
                onContentDelta, onThinkingDelta, onToolCallDelta);
    }
}
