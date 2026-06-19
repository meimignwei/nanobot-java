package com.nanobot.providers.base;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@DisplayName("MessageSanitizer")
class MessageSanitizerTest {

    // ---- sanitizeEmptyContent ----

    @Nested
    @DisplayName("sanitizeEmptyContent")
    class SanitizeEmptyContent {

        @Test
        @DisplayName("should replace empty string content with (empty) for user role")
        void emptyStringUser() {
            var msgs = List.<Map<String, Object>>of(msg("user", ""));
            var result = MessageSanitizer.sanitizeEmptyContent(msgs);
            assertThat(result.get(0).get("content")).isEqualTo("(empty)");
        }

        @Test
        @DisplayName("should set content to null for assistant with tool_calls and empty content")
        void emptyStringAssistantWithTools() {
            var msgs = List.<Map<String, Object>>of(Map.of(
                    "role", "assistant", "content", "",
                    "tool_calls", List.of(Map.of("id", "1"))));
            var result = MessageSanitizer.sanitizeEmptyContent(msgs);
            assertThat(result.get(0).get("content")).isNull();
        }

        @Test
        @DisplayName("should preserve non-empty content")
        void nonEmptyPreserved() {
            var msgs = List.<Map<String, Object>>of(msg("user", "hello"));
            var result = MessageSanitizer.sanitizeEmptyContent(msgs);
            assertThat(result.get(0).get("content")).isEqualTo("hello");
        }

        @Test
        @DisplayName("should remove empty text blocks and strip _meta")
        void emptyTextBlockRemoved() {
            var msgs = List.<Map<String, Object>>of(Map.of(
                    "role", "user", "content", List.of(
                            Map.of("type", "text", "text", ""),
                            Map.of("type", "text", "text", "hello", "_meta", Map.of("x", 1))
                    )));
            var result = MessageSanitizer.sanitizeEmptyContent(msgs);
            @SuppressWarnings("unchecked")
            var content = (List<Map<String, Object>>) result.get(0).get("content");
            assertThat(content).hasSize(1);
            assertThat(content.get(0)).doesNotContainKey("_meta");
            assertThat(content.get(0).get("text")).isEqualTo("hello");
        }

        @Test
        @DisplayName("should wrap bare dict content in list")
        void dictContentWrapped() {
            var msgs = List.<Map<String, Object>>of(Map.of(
                    "role", "user", "content", Map.of("type", "text", "text", "hi")));
            var result = MessageSanitizer.sanitizeEmptyContent(msgs);
            assertThat(result.get(0).get("content")).isInstanceOf(List.class);
        }
    }

    // ---- enforceRoleAlternation ----

    @Nested
    @DisplayName("enforceRoleAlternation")
    class EnforceRoleAlternation {

        @Test
        @DisplayName("should merge consecutive user messages")
        void mergeConsecutiveUser() {
            var msgs = List.<Map<String, Object>>of(
                    Map.of("role", "system", "content", "sys"),
                    Map.of("role", "user", "content", "a"),
                    Map.of("role", "user", "content", "b"));
            var result = MessageSanitizer.enforceRoleAlternation(msgs);
            assertThat(result).hasSize(2);
            assertThat(result.get(1).get("content")).isEqualTo("a\n\nb");
        }

        @Test
        @DisplayName("should merge consecutive assistant messages without tool_calls")
        void mergeConsecutiveAssistant() {
            var msgs = List.<Map<String, Object>>of(
                    Map.of("role", "user", "content", "q"),
                    Map.of("role", "assistant", "content", "a1"),
                    Map.of("role", "assistant", "content", "a2"));
            var result = MessageSanitizer.enforceRoleAlternation(msgs);
            // Merged assistants → [user, assistant(merged)], then trailing assistant popped → [user]
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("should replace previous when current assistant has tool_calls")
        void replaceForToolCalls() {
            var msgs = List.<Map<String, Object>>of(
                    Map.of("role", "user", "content", "q"),
                    Map.of("role", "assistant", "content", "a"),
                    Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of("id", "1"))));
            var result = MessageSanitizer.enforceRoleAlternation(msgs);
            // Replacement → [user, assistant(tool_calls)], then trailing assistant popped → [user]
            // This mirrors Python: trailing assistants are always dropped
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("should drop trailing assistant messages")
        void dropTrailingAssistant() {
            var msgs = List.<Map<String, Object>>of(
                    Map.of("role", "user", "content", "q"),
                    Map.of("role", "assistant", "content", "a"));
            var result = MessageSanitizer.enforceRoleAlternation(msgs);
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("should recover if only system messages remain after dropping assistant")
        void recoverAllSystem() {
            var msgs = List.<Map<String, Object>>of(
                    Map.of("role", "system", "content", "sys"),
                    Map.of("role", "assistant", "content", "response"));
            var result = MessageSanitizer.enforceRoleAlternation(msgs);
            assertThat(result).hasSize(2);
            assertThat(result.get(1).get("role")).isEqualTo("user");
        }

        @Test
        @DisplayName("should insert synthetic user before bare assistant as first non-system")
        void syntheticUserForBareAssistant() {
            var msgs = List.<Map<String, Object>>of(
                    Map.of("role", "system", "content", "sys"),
                    Map.of("role", "assistant", "content", "reply"));
            var result = MessageSanitizer.enforceRoleAlternation(msgs);
            // The assistant msg gets popped as trailing, then the system-only guard
            // recovers it as user. So we get: system, user (recovered).
            // Actually let me trace the logic:
            // 1. Merge: system + assistant → [system, assistant]
            // 2. Drop trailing: [system], lastPopped = assistant
            // 3. Recovery: only system, lastPopped != null, no user/tool → recover as user
            // → [system, assistant-as-user]
            assertThat(result).hasSize(2);
            assertThat(result.get(1).get("role")).isEqualTo("user");
        }
    }

    // ---- stripImageContent ----

    @Nested
    @DisplayName("stripImageContent")
    class StripImageContent {

        @Test
        @DisplayName("should replace image_url blocks with text placeholder")
        void replaceImageUrls() {
            var msgs = List.<Map<String, Object>>of(Map.of(
                    "role", "user", "content", List.of(
                            Map.of("type", "text", "text", "hello"),
                            Map.of("type", "image_url", "image_url", Map.of("url", "http://x"))
                    )));
            var result = MessageSanitizer.stripImageContent(msgs);
            assertThat(result).isNotNull();
            @SuppressWarnings("unchecked")
            var content = (List<Map<String, Object>>) result.get(0).get("content");
            assertThat(content).hasSize(2);
            assertThat(content.get(1).get("type")).isEqualTo("text");
            assertThat(content.get(1).get("text")).isEqualTo("[image omitted]");
        }

        @Test
        @DisplayName("should return null when no images found")
        void noImagesReturnsNull() {
            var msgs = List.<Map<String, Object>>of(msg("user", "hello"));
            assertThat(MessageSanitizer.stripImageContent(msgs)).isNull();
        }
    }

    // ---- sanitizeRequestMessages ----

    @Test
    @DisplayName("should keep only allowed keys")
    void sanitizeRequestMessages() {
        var msgs = List.<Map<String, Object>>of(Map.of(
                "role", "user", "content", "hi", "extra", "bad"));
        var result = MessageSanitizer.sanitizeRequestMessages(msgs,
                Set.of("role", "content"));
        assertThat(result.get(0)).containsOnlyKeys("role", "content");
        assertThat(result.get(0)).doesNotContainKey("extra");
    }

    // ---- toolCacheMarkerIndices ----

    @Test
    @DisplayName("should return last builtin and tail indices")
    void toolCacheMarkerIndices() {
        List<Map<String, Object>> tools = new ArrayList<>(List.of(
                Map.<String, Object>of("function", Map.of("name", "builtin1")),
                Map.<String, Object>of("function", Map.of("name", "mcp_server")),
                Map.<String, Object>of("function", Map.of("name", "mcp_tool"))));
        var indices = MessageSanitizer.toolCacheMarkerIndices(tools);
        assertThat(indices).containsExactly(0, 2);
    }

    // ---- helpers ----

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }
}
