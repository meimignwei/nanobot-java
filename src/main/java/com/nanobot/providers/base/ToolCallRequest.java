package com.nanobot.providers.base;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tool call request from the LLM. Mirrors Python ToolCallRequest dataclass exactly.
 */
public record ToolCallRequest(
        String id,
        String name,
        Object arguments, // Map<String, Object> or String (JSON)
        @Nullable Map<String, Object> extraContent,
        @Nullable Map<String, Object> providerSpecificFields,
        @Nullable Map<String, Object> functionProviderSpecificFields
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Serialize to an OpenAI-style tool_call payload. Mirrors to_openai_tool_call(). */
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

    /** Mirrors Python parse_tool_arguments(). */
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
