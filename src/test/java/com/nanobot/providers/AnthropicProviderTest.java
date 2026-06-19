package com.nanobot.providers;

import com.nanobot.providers.base.LLMResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("AnthropicProvider")
class AnthropicProviderTest {

    private static Map<String, Object> msg(String role, Object content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> textBlock(String text) {
        return Map.of("type", "text", "text", text);
    }

    // ---- normalizeBaseUrl ----

    @Test
    @DisplayName("should strip /v1 suffix from api_base")
    void normalizeBaseUrl() {
        assertThat(AnthropicProvider.normalizeBaseUrl("https://api.anthropic.com/v1"))
                .isEqualTo("https://api.anthropic.com");
        assertThat(AnthropicProvider.normalizeBaseUrl("https://api.anthropic.com"))
                .isEqualTo("https://api.anthropic.com");
        assertThat(AnthropicProvider.normalizeBaseUrl("https://api.anthropic.com/v1/"))
                .isEqualTo("https://api.anthropic.com");
    }

    // ---- stripPrefix ----

    @Test
    @DisplayName("should strip anthropic/ prefix")
    void stripPrefix() {
        assertThat(AnthropicProvider.stripPrefix("anthropic/claude-sonnet-4-6"))
                .isEqualTo("claude-sonnet-4-6");
        assertThat(AnthropicProvider.stripPrefix("claude-opus-4-7"))
                .isEqualTo("claude-opus-4-7");
    }

    // ---- convertUserContent ----

    @Nested
    @DisplayName("convertUserContent")
    class ConvertUserContent {

        @Test
        @DisplayName("should return string as-is")
        void stringContent() {
            assertThat(AnthropicProvider.convertUserContent("hello")).isEqualTo("hello");
        }

        @Test
        @DisplayName("should return (empty) for null")
        void nullContent() {
            assertThat(AnthropicProvider.convertUserContent(null)).isEqualTo("(empty)");
        }

        @Test
        @DisplayName("should convert text blocks")
        void textBlocks() {
            var result = AnthropicProvider.convertUserContent(List.of(
                    Map.of("type", "text", "text", "hi")));
            assertThat(result).isInstanceOf(List.class);
            @SuppressWarnings("unchecked")
            var list = (List<Map<String, Object>>) result;
            assertThat(list).hasSize(1);
            assertThat(list.get(0).get("text")).isEqualTo("hi");
        }

        @Test
        @DisplayName("should strip _meta field from user content blocks")
        void stripsMetaField() {
            var result = AnthropicProvider.convertUserContent(List.of(
                    Map.of("type", "text", "text", "test", "_meta", Map.of("x", 1))));
            @SuppressWarnings("unchecked")
            var list = (List<Map<String, Object>>) result;
            assertThat(list).hasSize(1);
            assertThat(list.get(0)).doesNotContainKey("_meta");
        }

        @Test
        @DisplayName("should convert image_url blocks")
        void imageUrlBlock() {
            var result = AnthropicProvider.convertUserContent(List.of(
                    Map.of("type", "image_url", "image_url", Map.of("url", "https://example.com/img.png"))));
            @SuppressWarnings("unchecked")
            var list = (List<Map<String, Object>>) result;
            assertThat(list).hasSize(1);
            assertThat(list.get(0).get("type")).isEqualTo("image");
        }

        @Test
        @DisplayName("should coerce bare dict blocks to text")
        void bareDictCoerced() {
            var result = AnthropicProvider.convertUserContent(List.of(
                    Map.of("key", "value")));
            assertThat(result).isInstanceOf(List.class);
            @SuppressWarnings("unchecked")
            var list = (List<Map<String, Object>>) result;
            assertThat(list.get(0).get("type")).isEqualTo("text");
        }
    }

    // ---- convertImageBlock ----

    @Nested
    @DisplayName("convertImageBlock")
    class ConvertImageBlock {

        @Test
        @DisplayName("should convert base64 data URL to Anthropic image block")
        void base64Image() {
            var result = AnthropicProvider.convertImageBlock(Map.of(
                    "image_url", Map.of("url", "data:image/png;base64,aGVsbG8=")));
            assertThat(result).isNotNull();
            assertThat(result.get("type")).isEqualTo("image");
            @SuppressWarnings("unchecked")
            var source = (Map<String, Object>) result.get("source");
            assertThat(source.get("type")).isEqualTo("base64");
            assertThat(source.get("media_type")).isEqualTo("image/png");
        }

        @Test
        @DisplayName("should convert URL images")
        void urlImage() {
            var result = AnthropicProvider.convertImageBlock(Map.of(
                    "image_url", Map.of("url", "https://example.com/img.png")));
            assertThat(result).isNotNull();
            assertThat(result.get("type")).isEqualTo("image");
            @SuppressWarnings("unchecked")
            var source = (Map<String, Object>) result.get("source");
            assertThat(source.get("type")).isEqualTo("url");
        }

        @Test
        @DisplayName("should return null for empty URL")
        void emptyUrl() {
            assertThat(AnthropicProvider.convertImageBlock(Map.of(
                    "image_url", Map.of("url", "")))).isNull();
        }
    }

    // ---- assistantBlocks ----

    @Nested
    @DisplayName("assistantBlocks")
    class AssistantBlocks {

        @Test
        @DisplayName("should convert string content to text block")
        void stringContent() {
            var blocks = AnthropicProvider.assistantBlocks(msg("assistant", "hello"));
            assertThat(blocks).hasSize(1);
            assertThat(blocks.get(0).get("type")).isEqualTo("text");
            assertThat(blocks.get(0).get("text")).isEqualTo("hello");
        }

        @Test
        @DisplayName("should convert tool_calls to tool_use blocks")
        void toolCalls() {
            var m = new LinkedHashMap<String, Object>();
            m.put("role", "assistant");
            m.put("content", "");
            m.put("tool_calls", List.of(Map.of(
                    "id", "call_1",
                    "function", Map.of("name", "get_weather", "arguments", "{\"city\":\"NYC\"}"))));
            var blocks = AnthropicProvider.assistantBlocks(m);
            assertThat(blocks.stream().anyMatch(b -> "tool_use".equals(b.get("type")))).isTrue();
        }

        @Test
        @DisplayName("should include thinking_blocks")
        void thinkingBlocks() {
            var m = new LinkedHashMap<String, Object>();
            m.put("role", "assistant");
            m.put("content", "done");
            m.put("thinking_blocks", List.of(Map.of(
                    "type", "thinking", "thinking", "hmm", "signature", "sig")));
            var blocks = AnthropicProvider.assistantBlocks(m);
            assertThat(blocks.stream().anyMatch(b -> "thinking".equals(b.get("type")))).isTrue();
        }
    }

    // ---- mergeConsecutive ----

    @Nested
    @DisplayName("mergeConsecutive")
    class MergeConsecutive {

        @Test
        @DisplayName("should merge consecutive same-role messages")
        void mergeConsecutive() {
            var msgs = List.<Map<String, Object>>of(
                    Map.of("role", "user", "content", List.of(textBlock("a"))),
                    Map.of("role", "user", "content", List.of(textBlock("b"))));
            var result = AnthropicProvider.mergeConsecutive(msgs);
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("should strip trailing assistant turns")
        void stripTrailingAssistant() {
            var msgs = List.<Map<String, Object>>of(
                    Map.of("role", "user", "content", List.of(textBlock("q"))),
                    Map.of("role", "assistant", "content", List.of(textBlock("a"))));
            var result = AnthropicProvider.mergeConsecutive(msgs);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).get("role")).isEqualTo("user");
        }

        @Test
        @DisplayName("should recover last popped assistant as user when all stripped")
        void recoverAsUser() {
            var msgs = List.<Map<String, Object>>of(
                    Map.of("role", "assistant", "content", "reply"));
            var result = AnthropicProvider.mergeConsecutive(msgs);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).get("role")).isEqualTo("user");
        }

        @Test
        @DisplayName("should inject synthetic user before leading assistant")
        void syntheticUserPrefix() {
            var msgs = List.<Map<String, Object>>of(
                    Map.of("role", "assistant", "content", List.of(textBlock("started"))),
                    Map.of("role", "user", "content", List.of(textBlock("ok"))));
            var result = AnthropicProvider.mergeConsecutive(msgs);
            assertThat(result.get(0).get("role")).isEqualTo("user");
        }
    }

    // ---- convertTools ----

    @Nested
    @DisplayName("convertTools")
    class ConvertTools {

        @Test
        @DisplayName("should convert OpenAI tools to Anthropic format")
        void convertTools() {
            var tools = List.<Map<String, Object>>of(Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", "get_weather",
                            "description", "Get weather",
                            "parameters", Map.of("type", "object", "properties", Map.of()))));
            var result = AnthropicProvider.convertTools(tools);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).get("name")).isEqualTo("get_weather");
            assertThat(result.get(0)).containsKey("input_schema");
        }

        @Test
        @DisplayName("should return null for empty/null tools")
        void emptyTools() {
            assertThat(AnthropicProvider.convertTools(null)).isNull();
            assertThat(AnthropicProvider.convertTools(List.of())).isNull();
        }
    }

    // ---- convertToolChoice ----

    @Nested
    @DisplayName("convertToolChoice")
    class ConvertToolChoice {

        @Test
        @DisplayName("should convert auto")
        void autoChoice() {
            assertThat(AnthropicProvider.convertToolChoice("auto", false))
                    .isEqualTo(Map.of("type", "auto"));
        }

        @Test
        @DisplayName("should convert required to any")
        void requiredChoice() {
            assertThat(AnthropicProvider.convertToolChoice("required", false))
                    .isEqualTo(Map.of("type", "any"));
        }

        @Test
        @DisplayName("should return null for none")
        void noneChoice() {
            assertThat(AnthropicProvider.convertToolChoice("none", false)).isNull();
        }

        @Test
        @DisplayName("should convert named tool choice")
        void namedToolChoice() {
            var result = AnthropicProvider.convertToolChoice(
                    Map.of("function", Map.of("name", "my_tool")), false);
            assertThat(result).isEqualTo(Map.of("type", "tool", "name", "my_tool"));
        }

        @Test
        @DisplayName("should return auto when thinking enabled")
        void thinkingEnabled() {
            assertThat(AnthropicProvider.convertToolChoice("required", true))
                    .isEqualTo(Map.of("type", "auto"));
        }
    }

    // ---- hasToolUse ----

    @Test
    @DisplayName("should detect tool_use blocks in content")
    void hasToolUse() {
        var with = Map.of("content", (Object) List.of(
                Map.of("type", "tool_use", "id", "1", "name", "f", "input", Map.of())));
        assertThat(AnthropicProvider.hasToolUse(with)).isTrue();

        var without = Map.of("content", (Object) List.of(Map.of("type", "text", "text", "hi")));
        assertThat(AnthropicProvider.hasToolUse(without)).isFalse();
    }

    // ---- toolArgumentsObjectForReplay ----

    @Test
    @DisplayName("should parse JSON string arguments to Map")
    void toolArgumentsJsonString() {
        var result = AnthropicProvider.toolArgumentsObjectForReplay("{\"key\":\"value\"}");
        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        var map = (Map<String, Object>) result;
        assertThat(map.get("key")).isEqualTo("value");
    }

    @Test
    @DisplayName("should pass through Map arguments")
    void toolArgumentsMap() {
        var map = Map.<String, Object>of("a", 1);
        assertThat(AnthropicProvider.toolArgumentsObjectForReplay(map)).isSameAs(map);
    }
}
