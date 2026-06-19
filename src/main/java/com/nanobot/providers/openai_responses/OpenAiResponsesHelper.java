package com.nanobot.providers.openai_responses;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanobot.providers.base.LLMResponse;
import com.nanobot.providers.base.ThrowingConsumer;
import com.nanobot.providers.base.ToolCallRequest;
import jakarta.annotation.Nullable;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Shared helpers for OpenAI Responses API providers.
 * Ports openai_responses/converters.py and parsing.py exactly.
 */
public final class OpenAiResponsesHelper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final Map<String, String> FINISH_REASON_MAP = Map.of(
            "completed", "stop",
            "incomplete", "length",
            "failed", "error",
            "cancelled", "error"
    );

    private OpenAiResponsesHelper() {}

    // ==================================================================
    // Converters (converters.py)
    // ==================================================================

    /** Returns [systemPrompt, inputItems]. */
    @SuppressWarnings("unchecked")
    public static Object[] convertMessages(List<Map<String, Object>> messages) {
        String systemPrompt = "";
        List<Map<String, Object>> inputItems = new ArrayList<>();
        Set<String> usedItemIds = new HashSet<>();

        for (int idx = 0; idx < messages.size(); idx++) {
            Map<String, Object> msg = messages.get(idx);
            String role = (String) msg.getOrDefault("role", "");
            Object content = msg.get("content");

            if ("system".equals(role)) {
                systemPrompt = content instanceof String s ? s : "";
                continue;
            }
            if ("user".equals(role)) {
                inputItems.add(convertUserMessage(content));
                continue;
            }
            if ("assistant".equals(role)) {
                if (content instanceof String s && !s.isEmpty()) {
                    String messageId = uniqueItemId("msg_" + idx, usedItemIds);
                    inputItems.add(Map.of(
                            "type", "message", "role", "assistant",
                            "content", List.of(Map.of("type", "output_text", "text", s)),
                            "status", "completed", "id", messageId));
                }
                List<Map<String, Object>> tcs = (List<Map<String, Object>>) msg.getOrDefault("tool_calls", List.of());
                for (int i = 0; i < tcs.size(); i++) {
                    Map<String, Object> tc = tcs.get(i);
                    Map<String, Object> fn = (Map<String, Object>) tc.getOrDefault("function", Map.of());
                    String[] split = splitToolCallId(tc.get("id"));
                    String callId = split[0];
                    String itemId = split[1] != null ? split[1] : "fc_" + i;
                    String responseItemId = uniqueItemId(itemId, usedItemIds);
                    inputItems.add(Map.of(
                            "type", "function_call",
                            "id", responseItemId,
                            "call_id", callId != null ? callId : "call_" + i,
                            "name", fn.getOrDefault("name", ""),
                            "arguments", toolArgumentsJsonForReplay(fn.get("arguments"))));
                }
                continue;
            }
            if ("tool".equals(role)) {
                String[] split = splitToolCallId(msg.get("tool_call_id"));
                String callId = split[0];
                String outputText;
                if (content instanceof String s) {
                    outputText = s;
                } else {
                    try {
                        outputText = MAPPER.writeValueAsString(content);
                    } catch (JsonProcessingException e) {
                        outputText = String.valueOf(content);
                    }
                }
                inputItems.add(Map.of("type", "function_call_output", "call_id", callId, "output", outputText));
            }
        }
        return new Object[]{systemPrompt, inputItems};
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> convertUserMessage(Object content) {
        if (content instanceof String s) {
            return Map.of("role", "user", "content", List.of(Map.of("type", "input_text", "text", s)));
        }
        if (content instanceof List<?> items) {
            List<Map<String, Object>> converted = new ArrayList<>();
            for (Object item : items) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> block = (Map<String, Object>) item;
                if ("text".equals(block.get("type"))) {
                    converted.add(Map.of("type", "input_text", "text",
                            String.valueOf(block.getOrDefault("text", ""))));
                } else if ("image_url".equals(block.get("type"))) {
                    Map<String, Object> iu = (Map<String, Object>) block.getOrDefault("image_url", Map.of());
                    Object url = iu.get("url");
                    if (url != null) {
                        converted.add(Map.of("type", "input_image", "image_url", url, "detail", "auto"));
                    }
                }
            }
            if (!converted.isEmpty()) {
                return Map.of("role", "user", "content", converted);
            }
        }
        return Map.of("role", "user", "content", List.of(Map.of("type", "input_text", "text", "")));
    }

    public static List<Map<String, Object>> convertTools(List<Map<String, Object>> tools) {
        List<Map<String, Object>> converted = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fn = "function".equals(tool.get("type"))
                    ? (Map<String, Object>) tool.getOrDefault("function", Map.of())
                    : tool;
            Object name = fn.get("name");
            if (name == null || String.valueOf(name).isEmpty()) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) fn.getOrDefault("parameters", Map.of());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", "function");
            entry.put("name", name);
            entry.put("description", fn.getOrDefault("description", ""));
            entry.put("parameters", params instanceof Map ? params : Map.of());
            converted.add(entry);
        }
        return converted;
    }

    public static String[] splitToolCallId(Object toolCallId) {
        if (toolCallId instanceof String s && !s.isEmpty()) {
            int idx = s.indexOf('|');
            if (idx >= 0) {
                return new String[]{s.substring(0, idx), idx + 1 < s.length() ? s.substring(idx + 1) : null};
            }
            return new String[]{s, null};
        }
        return new String[]{"call_0", null};
    }

    // ==================================================================
    // Parsing (parsing.py)
    // ==================================================================

    public static String mapFinishReason(@Nullable String status) {
        return FINISH_REASON_MAP.getOrDefault(status != null ? status : "completed", "stop");
    }

    public static LLMResponse parseResponseOutput(Object response) {
        Map<String, Object> resp = toMap(response);
        if (resp == null) return LLMResponse.error("Unexpected response type");

        StringBuilder contentParts = new StringBuilder();
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        StringBuilder reasoningContent = new StringBuilder();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> output = (List<Map<String, Object>>) resp.getOrDefault("output", List.of());
        for (Map<String, Object> item : output) {
            String it = (String) item.get("type");
            if ("message".equals(it)) {
                @SuppressWarnings("unchecked")
                var blocks = (List<Map<String, Object>>) item.getOrDefault("content", List.of());
                for (Map<String, Object> block : blocks) {
                    if ("output_text".equals(block.get("type")) && block.get("text") != null) {
                        contentParts.append(block.get("text"));
                    }
                }
            } else if ("reasoning".equals(it)) {
                @SuppressWarnings("unchecked")
                var summaries = (List<Map<String, Object>>) item.getOrDefault("summary", List.of());
                for (Map<String, Object> s : summaries) {
                    if ("summary_text".equals(s.get("type")) && s.get("text") != null) {
                        reasoningContent.append(s.get("text"));
                    }
                }
            } else if ("function_call".equals(it)) {
                String callId = (String) item.getOrDefault("call_id", "");
                String itemId = (String) item.getOrDefault("id", "fc_0");
                Object argsRaw = firstNonEmpty(item.get("arguments"), "{}");
                argsRaw = ToolCallRequest.parseToolArguments(argsRaw);
                toolCalls.add(new ToolCallRequest(
                        callId + "|" + itemId,
                        (String) item.getOrDefault("name", ""),
                        argsRaw, null, null, null));
            }
        }

        String status = (String) resp.get("status");
        return new LLMResponse(
                !contentParts.isEmpty() ? contentParts.toString() : null,
                toolCalls,
                mapFinishReason(status),
                usageFromResponseObj(resp),
                null,
                !reasoningContent.isEmpty() ? reasoningContent.toString() : null,
                null, null, null, null, null, null, null);
    }

    // ==================================================================
    // SSE consumption
    // ==================================================================

    /** Mutable state holder for SSE stream consumption. */
    public static class SseState {
        public String content = "";
        public final List<ToolCallRequest> toolCalls = new ArrayList<>();
        public final Map<String, Map<String, Object>> toolCallBuffers = new LinkedHashMap<>();
        public final Set<String> toolCallArgsEmitted = new HashSet<>();
        public String finishReason = "stop";
        public Map<String, Integer> usage = Map.of();
        public String reasoningContent = null;
        public boolean streamedReasoning = false;
    }

    @SuppressWarnings("unchecked")
    public static void consumeSseIntoState(
            InputStream body,
            SseState state,
            @Nullable ThrowingConsumer<String> onContentDelta,
            @Nullable ThrowingConsumer<Map<String, Object>> onToolCallDelta,
            @Nullable ThrowingConsumer<String> onReasoningDelta
    ) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8))) {
            List<String> buffer = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!buffer.isEmpty()) {
                        Map<String, Object> event = flushSseBuffer(buffer);
                        buffer.clear();
                        if (event != null) {
                            processSseEvent(event, state, onContentDelta, onToolCallDelta, onReasoningDelta);
                        }
                    }
                    continue;
                }
                buffer.add(line);
            }
            if (!buffer.isEmpty()) {
                Map<String, Object> event = flushSseBuffer(buffer);
                if (event != null) {
                    processSseEvent(event, state, onContentDelta, onToolCallDelta, onReasoningDelta);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void processSseEvent(
            Map<String, Object> event,
            SseState state,
            @Nullable ThrowingConsumer<String> onContentDelta,
            @Nullable ThrowingConsumer<Map<String, Object>> onToolCallDelta,
            @Nullable ThrowingConsumer<String> onReasoningDelta
    ) throws Exception {
        String eventType = (String) event.get("type");
        if (eventType == null) return;

        switch (eventType) {
            case "response.output_item.added" -> {
                Map<String, Object> item = (Map<String, Object>) event.getOrDefault("item", Map.of());
                if ("function_call".equals(item.get("type"))) {
                    String callId = (String) item.get("call_id");
                    if (callId == null) break;
                    Object arguments = item.get("arguments");
                    Map<String, Object> buf = new LinkedHashMap<>();
                    buf.put("id", item.getOrDefault("id", "fc_0"));
                    buf.put("name", item.get("name"));
                    buf.put("arguments", arguments != null ? arguments : "");
                    state.toolCallBuffers.put(callId, buf);
                    if (onToolCallDelta != null) {
                        onToolCallDelta.accept(Map.of(
                                "call_id", callId,
                                "name", String.valueOf(item.getOrDefault("name", "")),
                                "arguments_delta", ""));
                    }
                }
            }
            case "response.output_text.delta" -> {
                String deltaText = (String) event.getOrDefault("delta", "");
                state.content += deltaText;
                if (onContentDelta != null && !deltaText.isEmpty()) {
                    onContentDelta.accept(deltaText);
                }
            }
            case "response.reasoning_summary_text.delta" -> {
                String deltaText = (String) event.getOrDefault("delta", "");
                if (!deltaText.isEmpty()) {
                    state.reasoningContent = (state.reasoningContent != null ? state.reasoningContent : "") + deltaText;
                    state.streamedReasoning = true;
                    if (onReasoningDelta != null) onReasoningDelta.accept(deltaText);
                }
            }
            case "response.reasoning_summary_text.done" -> {
                String text = (String) event.getOrDefault("text", "");
                if (!text.isEmpty() && !state.streamedReasoning && state.reasoningContent == null) {
                    state.reasoningContent = text;
                    if (onReasoningDelta != null) onReasoningDelta.accept(text);
                }
            }
            case "response.reasoning_summary_part.done" -> {
                Map<String, Object> part = (Map<String, Object>) event.getOrDefault("part", Map.of());
                String text = "summary_text".equals(part.get("type")) ? (String) part.get("text") : null;
                if (text != null && !state.streamedReasoning && state.reasoningContent == null) {
                    state.reasoningContent = text;
                    if (onReasoningDelta != null) onReasoningDelta.accept(text);
                }
            }
            case "response.function_call_arguments.delta" -> {
                String callId = (String) event.get("call_id");
                if (callId != null && state.toolCallBuffers.containsKey(callId)) {
                    String delta = (String) event.getOrDefault("delta", "");
                    Map<String, Object> buf = state.toolCallBuffers.get(callId);
                    Object cur = buf.get("arguments");
                    String current = cur instanceof String s ? s : "";
                    buf.put("arguments", current + delta);
                    if (onToolCallDelta != null && !delta.isEmpty()) {
                        onToolCallDelta.accept(Map.of(
                                "call_id", callId,
                                "name", String.valueOf(buf.getOrDefault("name", "")),
                                "arguments_delta", delta));
                    }
                }
            }
            case "response.function_call_arguments.done" -> {
                String callId = (String) event.get("call_id");
                if (callId != null && state.toolCallBuffers.containsKey(callId)) {
                    Object arguments = event.get("arguments");
                    state.toolCallBuffers.get(callId).put("arguments", arguments);
                    if (onToolCallDelta != null) {
                        state.toolCallArgsEmitted.add(callId);
                        onToolCallDelta.accept(Map.of(
                                "call_id", callId,
                                "name", String.valueOf(state.toolCallBuffers.get(callId).getOrDefault("name", "")),
                                "arguments", arguments != null ? String.valueOf(arguments) : ""));
                    }
                }
            }
            case "response.output_item.done" -> {
                Map<String, Object> item = (Map<String, Object>) event.getOrDefault("item", Map.of());
                if ("function_call".equals(item.get("type"))) {
                    String callId = (String) item.get("call_id");
                    if (callId == null) break;
                    Map<String, Object> buf = state.toolCallBuffers.getOrDefault(callId, Map.of());
                    Object argsRaw = firstNonEmpty(buf.get("arguments"), item.get("arguments"), "{}");
                    if (onToolCallDelta != null && !state.toolCallArgsEmitted.contains(callId)) {
                        state.toolCallArgsEmitted.add(callId);
                        onToolCallDelta.accept(Map.of(
                                "call_id", callId,
                                "name", String.valueOf(buf.getOrDefault("name",
                                        item.getOrDefault("name", ""))),
                                "arguments", String.valueOf(argsRaw)));
                    }
                    Object args = ToolCallRequest.parseToolArguments(argsRaw);
                    state.toolCalls.add(new ToolCallRequest(
                            callId + "|" + (buf.getOrDefault("id", item.getOrDefault("id", "fc_0"))),
                            (String) buf.getOrDefault("name", item.getOrDefault("name", "")),
                            args, null, null, null));
                } else if ("reasoning".equals(item.get("type")) && state.reasoningContent == null) {
                    String summary = extractReasoningSummaryFromOutput(List.of(item));
                    if (summary != null) {
                        state.reasoningContent = summary;
                        if (onReasoningDelta != null) onReasoningDelta.accept(summary);
                    }
                }
            }
            case "response.completed" -> {
                Map<String, Object> resp = (Map<String, Object>) event.getOrDefault("response", Map.of());
                String status = (String) resp.get("status");
                state.finishReason = mapFinishReason(status);
                state.usage = usageFromResponseObj(resp);
                if (!state.usage.isEmpty()) {
                    // usage already set
                }
                if (state.reasoningContent == null) {
                    @SuppressWarnings("unchecked")
                    var output = (List<Map<String, Object>>) resp.getOrDefault("output", List.of());
                    String summary = extractReasoningSummaryFromOutput(output);
                    if (summary != null) {
                        state.reasoningContent = summary;
                        if (onReasoningDelta != null) onReasoningDelta.accept(summary);
                    }
                }
            }
            case "error", "response.failed" -> {
                Object detail = event.getOrDefault("error", event.getOrDefault("message", event));
                throw new RuntimeException("Response failed: " + String.valueOf(detail).substring(0,
                        Math.min(500, String.valueOf(detail).length())));
            }
        }
    }

    // ==================================================================
    // Internal helpers
    // ==================================================================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> flushSseBuffer(List<String> buffer) {
        List<String> dataLines = new ArrayList<>();
        for (String line : buffer) {
            if (line.startsWith("data:")) {
                dataLines.add(line.substring(5).strip());
            }
        }
        if (dataLines.isEmpty()) return null;
        String data = String.join("\n", dataLines).strip();
        if (data.isEmpty() || "[DONE]".equals(data)) return null;
        try {
            return MAPPER.readValue(data, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    static String uniqueItemId(String itemId, Set<String> used) {
        if (!used.contains(itemId)) {
            used.add(itemId);
            return itemId;
        }
        int suffix = 2;
        while (used.contains(itemId + "_" + suffix)) suffix++;
        String unique = itemId + "_" + suffix;
        used.add(unique);
        return unique;
    }

    static String toolArgumentsJsonForReplay(Object arguments) {
        if (arguments == null) return "{}";
        if (arguments instanceof String s) {
            try {
                Object parsed = MAPPER.readValue(s, Object.class);
                return MAPPER.writeValueAsString(parsed);
            } catch (Exception e) {
                return s;
            }
        }
        try {
            return MAPPER.writeValueAsString(arguments);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Integer> usageFromResponseObj(Object response) {
        Map<String, Object> m = toMap(response);
        Map<String, Object> u = m != null ? (Map<String, Object>) m.get("usage") : null;
        if (u == null) return Map.of();
        int prompt = toInt(u.getOrDefault("input_tokens", u.getOrDefault("prompt_tokens", 0)));
        int completion = toInt(u.getOrDefault("output_tokens", u.getOrDefault("completion_tokens", 0)));
        int total = toInt(u.getOrDefault("total_tokens", prompt + completion));
        return new LinkedHashMap<>(Map.of("prompt_tokens", prompt, "completion_tokens", completion, "total_tokens", total));
    }

    static int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    static Object firstNonEmpty(Object... values) {
        for (Object v : values) {
            if (v == null) continue;
            if (v instanceof String s && s.isBlank()) continue;
            return v;
        }
        return "{}";
    }

    @SuppressWarnings("unchecked")
    static String extractReasoningSummaryFromOutput(List<Map<String, Object>> output) {
        StringBuilder parts = new StringBuilder();
        for (Map<String, Object> item : output) {
            if (!"reasoning".equals(item.get("type"))) continue;
            for (Map<String, Object> summary : (List<Map<String, Object>>) item.getOrDefault("summary", List.of())) {
                if ("summary_text".equals(summary.get("type")) && summary.get("text") != null) {
                    parts.append(summary.get("text"));
                }
            }
        }
        return !parts.isEmpty() ? parts.toString() : null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> toMap(Object obj) {
        if (obj instanceof Map m) return (Map<String, Object>) m;
        return null;
    }
}
