package com.nanobot.providers;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM tool 调用请求，包含函数名、参数和 provider 特有字段。
 *
 * <p>对标 Python {@code nanobot/providers/base.py ToolCallRequest @dataclass}。
 */
public record ToolCallRequest(
        String id,
        String name,
        Object arguments,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Map<String, Object> extraContent,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("provider_specific_fields")
        Map<String, Object> providerSpecificFields,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("function_provider_specific_fields")
        Map<String, Object> functionProviderSpecificFields) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 最小字段构造器，不含 provider 特有扩展字段。
     *
     * @param id        工具调用 ID
     * @param name      函数名
     * @param arguments 参数（Map 或 JSON 字符串）
     */
    public ToolCallRequest(String id, String name, Object arguments) {
        this(id, name, arguments, null, null, null);
    }

    /**
     * 序列化为 OpenAI 风格的 tool_call payload。
     *
     * @return 含 id/type/function 结构的 Map
     */
    public Map<String, Object> toOpenAiToolCall() {
        String argumentsStr;
        if (arguments instanceof String s) {
            argumentsStr = s;
        } else {
            try {
                argumentsStr = MAPPER.writeValueAsString(arguments);
            } catch (JsonProcessingException e) {
                argumentsStr = "{}";
            }
        }
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", id);
        toolCall.put("type", "function");
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("arguments", argumentsStr);
        toolCall.put("function", function);

        if (extraContent != null)
            toolCall.put("extra_content", extraContent);
        if (providerSpecificFields != null)
            toolCall.put("provider_specific_fields", providerSpecificFields);
        if (functionProviderSpecificFields != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> func = (Map<String, Object>) toolCall.get("function");
            func.put("provider_specific_fields", functionProviderSpecificFields);
        }
        return toolCall;
    }
}
