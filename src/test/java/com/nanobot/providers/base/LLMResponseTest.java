package com.nanobot.providers.base;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("LLMResponse / ToolCallRequest")
class LLMResponseTest {

    @Nested
    @DisplayName("LLMResponse")
    class ResponseTests {

        @Test
        @DisplayName("should detect tool calls")
        void hasToolCalls() {
            var with = new LLMResponse(null, List.of(
                    new ToolCallRequest("1", "test", Map.of(), null, null, null)),
                    "tool_calls", Map.of(), null, null, null, null, null, null, null, null, null);
            assertThat(with.hasToolCalls()).isTrue();

            var without = new LLMResponse("hi", List.of(), "stop", Map.of(),
                    null, null, null, null, null, null, null, null, null);
            assertThat(without.hasToolCalls()).isFalse();
        }

        @Test
        @DisplayName("should execute tools only for valid finish reasons")
        void shouldExecuteTools() {
            var withToolCalls = new LLMResponse(null, List.of(
                    new ToolCallRequest("1", "f", Map.of(), null, null, null)),
                    "tool_calls", Map.of(), null, null, null, null, null, null, null, null, null);
            assertThat(withToolCalls.shouldExecuteTools()).isTrue();

            var stopWithTools = new LLMResponse(null, List.of(
                    new ToolCallRequest("1", "f", Map.of(), null, null, null)),
                    "stop", Map.of(), null, null, null, null, null, null, null, null, null);
            assertThat(stopWithTools.shouldExecuteTools()).isTrue();

            var errorWithTools = new LLMResponse(null, List.of(
                    new ToolCallRequest("1", "f", Map.of(), null, null, null)),
                    "error", Map.of(), null, null, null, null, null, null, null, null, null);
            assertThat(errorWithTools.shouldExecuteTools()).isFalse();

            var refusal = new LLMResponse(null, List.of(
                    new ToolCallRequest("1", "f", Map.of(), null, null, null)),
                    "refusal", Map.of(), null, null, null, null, null, null, null, null, null);
            assertThat(refusal.shouldExecuteTools()).isFalse();
        }

        @Test
        @DisplayName("should default finishReason to stop and empty collections")
        void defaults() {
            var resp = new LLMResponse("hi", null, null, null, null, null, null,
                    null, null, null, null, null, null);
            assertThat(resp.finishReason()).isEqualTo("stop");
            assertThat(resp.toolCalls()).isEmpty();
            assertThat(resp.usage()).isEmpty();
        }

        @Test
        @DisplayName("should use builder")
        void builder() {
            var resp = LLMResponse.builder()
                    .content("hi")
                    .finishReason("stop")
                    .usage(Map.of("total_tokens", 10))
                    .build();
            assertThat(resp.content()).isEqualTo("hi");
            assertThat(resp.usage().get("total_tokens")).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("ToolCallRequest.parseToolArguments")
    class ParseToolArguments {

        @Test
        @DisplayName("should return empty map for null")
        void nullReturnsEmpty() {
            assertThat(ToolCallRequest.parseToolArguments(null)).isEqualTo(Map.of());
        }

        @Test
        @DisplayName("should parse valid JSON object")
        void parseValidJson() {
            var result = ToolCallRequest.parseToolArguments("{\"key\": \"value\"}");
            assertThat(result).isInstanceOf(Map.class);
            assertThat(((Map<?, ?>) result).get("key")).isEqualTo("value");
        }

        @Test
        @DisplayName("should return empty map for empty string")
        void emptyStringReturnsEmpty() {
            assertThat(ToolCallRequest.parseToolArguments("   ")).isEqualTo(Map.of());
        }

        @Test
        @DisplayName("should pass through non-string objects")
        void passThroughMaps() {
            var map = Map.of("a", 1);
            assertThat(ToolCallRequest.parseToolArguments(map)).isSameAs(map);
        }

        @Test
        @DisplayName("should return original string for malformed JSON")
        void malformedJson() {
            var result = ToolCallRequest.parseToolArguments("not json");
            assertThat(result).isEqualTo("not json");
        }
    }

    @Nested
    @DisplayName("ToolCallRequest.toOpenAiToolCall")
    class ToOpenAiToolCall {

        @Test
        @DisplayName("should serialize to OpenAI format")
        void openAiFormat() {
            var tc = new ToolCallRequest("call_123", "my_func", Map.of("x", 1),
                    null, null, null);
            var result = tc.toOpenAiToolCall();
            assertThat(result.get("id")).isEqualTo("call_123");
            assertThat(result.get("type")).isEqualTo("function");

            @SuppressWarnings("unchecked")
            var fn = (Map<String, Object>) result.get("function");
            assertThat(fn.get("name")).isEqualTo("my_func");
            assertThat(fn.get("arguments")).isEqualTo("{\"x\":1}");
        }

        @Test
        @DisplayName("should handle string arguments directly")
        void stringArguments() {
            var tc = new ToolCallRequest("1", "f", "{\"a\":1}", null, null, null);
            var result = tc.toOpenAiToolCall();
            @SuppressWarnings("unchecked")
            var fn = (Map<String, Object>) result.get("function");
            assertThat(fn.get("arguments")).isEqualTo("{\"a\":1}");
        }
    }
}
