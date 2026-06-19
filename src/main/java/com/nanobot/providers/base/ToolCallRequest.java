package com.nanobot.providers.base;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 返回的工具调用请求。
 * 对应 Python ToolCallRequest dataclass（providers/base.py）。
 */
public record ToolCallRequest(
        /** 工具调用唯一 ID */
        String id,
        /** 工具名称 */
        String name,
        /** 工具参数（Map 或 JSON 字符串） */
        Object arguments,
        /** 额外内容（如 Anthropic cache 标记） */
        @Nullable Map<String, Object> extraContent,
        /** provider 特有字段 */
        @Nullable Map<String, Object> providerSpecificFields,
        /** function 级别 provider 特有字段 */
        @Nullable Map<String, Object> functionProviderSpecificFields
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 序列化为 OpenAI 风格的 tool_call 负载。
     *  对应 Python ToolCallRequest.to_openai_tool_call()。 */
    public Map<String, Object> toOpenAiToolCall() {
        String argsStr;
        if (arguments instanceof String s) {
            argsStr = s;
        } else {
            try {
                argsStr = MAPPER.writeValueAsString(arguments);
            } catch (JsonProcessingException e) {
                argsStr = "{}";
            }
        }
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("arguments", argsStr);
        if (functionProviderSpecificFields != null) {
            function.put("provider_specific_fields", functionProviderSpecificFields);
        }

        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", id);
        toolCall.put("type", "function");
        toolCall.put("function", function);
        if (extraContent != null) {
            toolCall.put("extra_content", extraContent);
        }
        if (providerSpecificFields != null) {
            toolCall.put("provider_specific_fields", providerSpecificFields);
        }
        return toolCall;
    }

    /** 解析工具参数：JSON 字符串 → Map，非法 JSON 原样返回字符串。
     *  对应 Python parse_tool_arguments()。 */
    public static Object parseToolArguments(Object arguments) {
        if (arguments == null) return Map.of();
        if (!(arguments instanceof String s)) return arguments;

        String stripped = s.strip();
        if (stripped.isEmpty()) return Map.of();

        try {
            Object parsed = MAPPER.readValue(stripped, Object.class);
            return parsed == null ? s : parsed;
        } catch (Exception e) {
            return s;
        }
    }
}
