package com.nanobot.providers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("BedrockProvider")
class BedrockProviderTest {

    // ---- stripPrefix ----

    @Test
    @DisplayName("should strip bedrock/ prefix")
    void stripPrefix() {
        assertThat(BedrockProvider.stripPrefix("bedrock/global.anthropic.claude-opus-4-7"))
                .isEqualTo("global.anthropic.claude-opus-4-7");
        assertThat(BedrockProvider.stripPrefix("claude-sonnet-4-6"))
                .isEqualTo("claude-sonnet-4-6");
    }

    // ---- supportsTemperature / usesAdaptiveThinkingOnly ----

    @Test
    @DisplayName("should detect temperature-unsupported models")
    void temperatureSupport() {
        assertThat(BedrockProvider.supportsTemperature("claude-opus-4-7")).isFalse();
        assertThat(BedrockProvider.supportsTemperature("claude-sonnet-4-6")).isTrue();
    }

    @Test
    @DisplayName("should detect adaptive-thinking-only models")
    void adaptiveThinkingOnly() {
        assertThat(BedrockProvider.usesAdaptiveThinkingOnly("claude-opus-4-7")).isTrue();
        assertThat(BedrockProvider.usesAdaptiveThinkingOnly("claude-sonnet-4-6")).isFalse();
    }

    // ---- contentBlocks ----

    @Nested
    @DisplayName("contentBlocks")
    class ContentBlocks {

        @Test
        @DisplayName("should return text block for string content")
        void stringContent() {
            var blocks = BedrockProvider.contentBlocks("hello", false);
            assertThat(blocks).hasSize(1);
            assertThat(blocks.get(0).get("text")).isEqualTo("hello");
        }

        @Test
        @DisplayName("should return (empty) for null content")
        void nullContent() {
            var blocks = BedrockProvider.contentBlocks(null, false);
            assertThat(blocks.get(0).get("text")).isEqualTo("(empty)");
        }

        @Test
        @DisplayName("should convert text blocks")
        void textBlocks() {
            var blocks = BedrockProvider.contentBlocks(
                    List.of(Map.of("type", "text", "text", "hi")), false);
            assertThat(blocks).hasSize(1);
            assertThat(blocks.get(0).get("text")).isEqualTo("hi");
        }

        @Test
        @DisplayName("should convert json content for tool results")
        void jsonForToolResult() {
            var blocks = BedrockProvider.contentBlocks(Map.of("key", "value"), true);
            assertThat(blocks).hasSize(1);
            assertThat(blocks.get(0)).containsKey("json");
        }

        @Test
        @DisplayName("should preserve existing bedrock-shaped blocks")
        void preserveBedrockShaped() {
            var blocks = BedrockProvider.contentBlocks(
                    List.of(Map.of("document", Map.of("name", "file.txt"))), false);
            assertThat(blocks).hasSize(1);
            assertThat(blocks.get(0)).containsKey("document");
        }
    }

    // ---- imageUrlBlock ----

    @Nested
    @DisplayName("imageUrlBlock")
    class ImageUrlBlock {

        @Test
        @DisplayName("should decode base64 data URL")
        void base64Image() {
            var result = BedrockProvider.imageUrlBlock(Map.of(
                    "image_url", Map.of("url", "data:image/png;base64,aGVsbG8=")));
            assertThat(result).isNotNull();
            assertThat(result).containsKey("image");
            @SuppressWarnings("unchecked")
            var img = (Map<String, Object>) result.get("image");
            assertThat(img.get("format")).isEqualTo("png");
        }

        @Test
        @DisplayName("should convert jpg to jpeg format")
        void jpgToJpeg() {
            var result = BedrockProvider.imageUrlBlock(Map.of(
                    "image_url", Map.of("url", "data:image/jpg;base64,aGVsbG8=")));
            @SuppressWarnings("unchecked")
            var img = (Map<String, Object>) result.get("image");
            assertThat(img.get("format")).isEqualTo("jpeg");
        }

        @Test
        @DisplayName("should fallback to text for URL images")
        void urlImage() {
            var result = BedrockProvider.imageUrlBlock(Map.of(
                    "image_url", Map.of("url", "https://example.com/img.png")));
            assertThat(result).containsKey("text");
        }

        @Test
        @DisplayName("should handle invalid base64 gracefully")
        void invalidBase64() {
            var result = BedrockProvider.imageUrlBlock(Map.of(
                    "image_url", Map.of("url", "data:image/png;base64,!!!invalid!!!")));
            assertThat(result).containsKey("text");
            assertThat(String.valueOf(result.get("text"))).contains("invalid image");
        }
    }

    // ---- assistantBlocks ----

    @Nested
    @DisplayName("assistantBlocks")
    class AssistantBlocks {

        @Test
        @DisplayName("should convert tool_calls to toolUse blocks")
        void toolCallsToToolUse() {
            var msg = new LinkedHashMap<String, Object>();
            msg.put("role", "assistant");
            msg.put("content", "");
            msg.put("tool_calls", List.of(Map.of(
                    "id", "call_1",
                    "function", Map.of("name", "get_weather", "arguments", Map.of("city", "NYC")))));
            var blocks = BedrockProvider.assistantBlocks(msg);
            assertThat(blocks.stream().anyMatch(b -> b.containsKey("toolUse"))).isTrue();
        }

        @Test
        @DisplayName("should include reasoning blocks from thinking_blocks")
        void thinkingToReasoning() {
            var msg = new LinkedHashMap<String, Object>();
            msg.put("role", "assistant");
            msg.put("content", "done");
            msg.put("thinking_blocks", List.of(Map.of(
                    "type", "thinking", "thinking", "hmm", "signature", "sig")));
            var blocks = BedrockProvider.assistantBlocks(msg);
            assertThat(blocks.stream().anyMatch(b -> b.containsKey("reasoningContent"))).isTrue();
        }
    }

    // ---- mergeConsecutive ----

    @Nested
    @DisplayName("mergeConsecutive")
    class MergeConsecutive {

        @Test
        @DisplayName("should strip trailing assistant turns")
        void stripTrailingAssistant() {
            var msgs = List.<Map<String, Object>>of(
                    Map.of("role", "user", "content", List.of(Map.of("text", "q"))),
                    Map.of("role", "assistant", "content", List.of(Map.of("text", "a"))));
            var result = BedrockProvider.mergeConsecutive(msgs);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).get("role")).isEqualTo("user");
        }
    }

    // ---- convertTools ----

    @Test
    @DisplayName("should convert to toolSpec format")
    void convertTools() {
        var tools = List.<Map<String, Object>>of(Map.of(
                "type", "function",
                "function", Map.of("name", "search", "parameters", Map.of())));
        var result = BedrockProvider.convertTools(tools);
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsKey("toolSpec");
    }

    @Test
    @DisplayName("should return null for empty tools")
    void emptyTools() {
        assertThat(BedrockProvider.convertTools(null)).isNull();
        assertThat(BedrockProvider.convertTools(List.of())).isNull();
    }

    // ---- convertToolChoice ----

    @Nested
    @DisplayName("convertToolChoice")
    class ConvertToolChoice {

        @Test
        @DisplayName("should convert auto")
        void autoChoice() {
            assertThat(BedrockProvider.convertToolChoice("auto"))
                    .isEqualTo(Map.of("auto", Map.of()));
        }

        @Test
        @DisplayName("should convert required to any")
        void requiredChoice() {
            assertThat(BedrockProvider.convertToolChoice("required"))
                    .isEqualTo(Map.of("any", Map.of()));
        }

        @Test
        @DisplayName("should return null for none")
        void noneChoice() {
            assertThat(BedrockProvider.convertToolChoice("none")).isNull();
        }
    }

    // ---- adaptiveThinking ----

    @Test
    @DisplayName("should return adaptive thinking config")
    void adaptiveThinking() {
        assertThat(BedrockProvider.adaptiveThinking("low"))
                .isEqualTo(Map.of("type", "adaptive", "effort", "low"));
        assertThat(BedrockProvider.adaptiveThinking("adaptive"))
                .isEqualTo(Map.of("type", "adaptive"));
        assertThat(BedrockProvider.adaptiveThinking("none")).isNull();
    }

    // ---- finishReason ----

    @Test
    @DisplayName("should map stop reasons")
    void finishReason() {
        assertThat(BedrockProvider.finishReason("end_turn")).isEqualTo("stop");
        assertThat(BedrockProvider.finishReason("tool_use")).isEqualTo("tool_calls");
        assertThat(BedrockProvider.finishReason("max_tokens")).isEqualTo("length");
    }

    // ---- containsToolBlocks ----

    @Test
    @DisplayName("should detect tool blocks in messages")
    void containsToolBlocks() {
        var with = List.<Map<String, Object>>of(Map.of(
                "role", "assistant",
                "content", List.of(Map.of("toolUse", Map.of("name", "f")))));
        assertThat(BedrockProvider.containsToolBlocks(with)).isTrue();

        var without = List.<Map<String, Object>>of(Map.of(
                "role", "user", "content", List.of(Map.of("text", "hi"))));
        assertThat(BedrockProvider.containsToolBlocks(without)).isFalse();
    }

    // ---- noopTool ----

    @Test
    @DisplayName("should create noop tool placeholder")
    void noopTool() {
        var tool = BedrockProvider.noopTool();
        assertThat(tool).containsKey("toolSpec");
        @SuppressWarnings("unchecked")
        var spec = (Map<String, Object>) tool.get("toolSpec");
        assertThat(spec.get("name")).isEqualTo("nanobot_noop");
    }
}
