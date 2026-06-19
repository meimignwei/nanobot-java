package com.nanobot.agent.runner;

import com.nanobot.agent.tools.ToolRegistry;
import com.nanobot.providers.base.LLMProvider;
import com.nanobot.providers.base.LLMResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class AgentRunnerTest {

    // -- simple completion (no tools) --

    @Test
    void runReturnsFinalContentForSimpleResponse() throws Exception {
        var provider = new StubProvider() {
            @Override
            public LLMResponse chat(List<Map<String, Object>> messages,
                                    List<Map<String, Object>> tools,
                                    String model, int maxTokens,
                                    double temperature, String reasoningEffort,
                                    Object toolChoice) {
                calls.add(messages);
                return new LLMResponse("Hello, world!", List.of(), "stop",
                        Map.of(), null, null, null, null, null, null, null, null, null);
            }
        };
        var runner = new AgentRunner(provider);
        var spec = new AgentRunSpec(
                List.of(Map.of("role", "user", "content", "hi")),
                new ToolRegistry(), "test-model", 10, 16_000,
                null, null, null, null, null, null,
                false, false, null, null, null, null,
                "standard", null, true, null, null, null, null,
                null, null, true);

        var result = runner.run(spec);

        assertThat(result).isNotNull();
        assertThat(result.finalContent()).isEqualTo("Hello, world!");
        assertThat(result.stopReason()).isEqualTo("completed");
    }

    // -- max iterations --

    @Test
    void runStopsAtMaxIterations() throws Exception {
        var provider = new StubProvider() {
            @Override
            public LLMResponse chat(List<Map<String, Object>> messages,
                                    List<Map<String, Object>> tools,
                                    String model, int maxTokens,
                                    double temperature, String reasoningEffort,
                                    Object toolChoice) {
                calls.add(messages);
                // Always return tool calls to keep the loop going
                return new LLMResponse("", List.of(
                        new com.nanobot.providers.base.ToolCallRequest(
                                "id1", "test_tool", Map.of(), null, null, null)),
                        "tool_calls", Map.of(),
                        null, null, null, null, null, null, null, null, null);
            }
        };
        var runner = new AgentRunner(provider);
        var spec = new AgentRunSpec(
                List.of(Map.of("role", "user", "content", "hi")),
                new ToolRegistry(), "test-model", 2, 16_000,
                null, null, null, null, null, null,
                false, false, null, null, null, null,
                "standard", null, true, null, null, null, null,
                null, null, true);

        var result = runner.run(spec);

        assertThat(result.stopReason()).isEqualTo("max_iterations");
    }

    // -- error handling --

    @Test
    void runReturnsErrorOnProviderFailure() throws Exception {
        var provider = new StubProvider() {
            @Override
            public LLMResponse chat(List<Map<String, Object>> messages,
                                    List<Map<String, Object>> tools,
                                    String model, int maxTokens,
                                    double temperature, String reasoningEffort,
                                    Object toolChoice) {
                return new LLMResponse("model error", List.of(), "error",
                        Map.of(), null, null, null, null, null, null, null, null, null);
            }
        };
        var runner = new AgentRunner(provider);
        var spec = new AgentRunSpec(
                List.of(Map.of("role", "user", "content", "hi")),
                new ToolRegistry(), "test-model", 10, 16_000,
                null, null, null, null, null, null,
                false, false, null, null, null, null,
                "standard", null, true, null, null, null, null,
                null, null, true);

        var result = runner.run(spec);

        assertThat(result.stopReason()).isEqualTo("error");
        assertThat(result.error()).isNotNull();
    }

    // -- dropOrphanToolResults --

    @Test
    void dropOrphanToolResultsRemovesUnmatchedTools() {
        var messages = new ArrayList<Map<String, Object>>();
        messages.add(Map.of("role", "user", "content", "hello"));
        messages.add(Map.of("role", "assistant", "content", "let me help",
                "tool_calls", List.of(Map.of("id", "call_1", "function", Map.of("name", "read")))));
        messages.add(Map.of("role", "tool", "tool_call_id", "orphan_99", "content", "orphan result"));
        messages.add(Map.of("role", "tool", "tool_call_id", "call_1", "content", "good result"));

        AgentRunner.dropOrphanToolResults(messages);
        assertThat(messages).hasSize(3);
        // orphan_99 removed; call_1 kept
        assertThat(messages.get(2).get("tool_call_id")).isEqualTo("call_1");
    }

    // -- buildAssistantMessage --

    @Test
    void buildAssistantMessageCreatesProperMessage() {
        var toolCall = new com.nanobot.providers.base.ToolCallRequest(
                "t1", "echo", Map.of("text", "hi"), null, null, null);
        var msg = AgentRunner.buildAssistantMessage("response text",
                List.of(toolCall), null, null);
        assertThat(msg.get("role")).isEqualTo("assistant");
        assertThat(msg.get("content")).isEqualTo("response text");
        assertThat(msg.get("tool_calls")).isNotNull();
    }

    // -- security checks --

    @Test
    void isSsrfViolationDetectsMarkers() {
        assertThat(AgentRunner.isSsrfViolation("internal/private URL detected")).isTrue();
        assertThat(AgentRunner.isSsrfViolation("Private/Internal Address blocked")).isTrue();
        assertThat(AgentRunner.isSsrfViolation("this is a private address issue")).isTrue();
        assertThat(AgentRunner.isSsrfViolation("normal response")).isFalse();
        assertThat(AgentRunner.isSsrfViolation("")).isFalse();
        assertThat(AgentRunner.isSsrfViolation(null)).isFalse();
    }

    @Test
    void isWorkspaceViolationDetectsBothSsrfAndWorkspaceMarkers() {
        assertThat(AgentRunner.isWorkspaceViolation("outside the configured workspace")).isTrue();
        assertThat(AgentRunner.isWorkspaceViolation("path outside working dir")).isTrue();
        assertThat(AgentRunner.isWorkspaceViolation("path traversal detected")).isTrue();
        assertThat(AgentRunner.isWorkspaceViolation("working_dir could not be resolved")).isTrue();
        // SSRF markers also trigger workspace violation
        assertThat(AgentRunner.isWorkspaceViolation("internal/private URL detected")).isTrue();
        assertThat(AgentRunner.isWorkspaceViolation("normal output")).isFalse();
    }

    @Test
    void ssrfSoftPayloadAppendsBoundaryNote() {
        var result = AgentRunner.ssrfSoftPayload("blocked: internal address");
        assertThat(result).contains("blocked: internal address");
        assertThat(result).contains("non-bypassable security boundary");
    }

    @Test
    void ssrfSoftPayloadHandlesEmptyText() {
        var result = AgentRunner.ssrfSoftPayload("");
        assertThat(result).contains("blocked by SSRF guard");
    }

    @Test
    void eventDetailTruncatesToLimit() {
        var result = AgentRunner.eventDetail("prefix: ", "short text", 200);
        assertThat(result).startsWith("prefix: ");
        assertThat(result).contains("short text");
    }

    @Test
    void eventDetailTruncatesLongText() {
        var longText = "a".repeat(200);
        var result = AgentRunner.eventDetail("err: ", longText, 80);
        assertThat(result.length()).isLessThanOrEqualTo(80);
        assertThat(result).startsWith("err: ");
    }

    // -- usage tracking --

    @Test
    void usageDictConvertsStringValuesToInt() {
        var usage = Map.of("prompt_tokens", 100, "completion_tokens", "50");
        var result = AgentRunner.usageDict(usage);
        assertThat(result).containsEntry("prompt_tokens", 100);
        assertThat(result).containsEntry("completion_tokens", 50);
    }

    @Test
    void usageDictHandlesNullAndInvalid() {
        assertThat(AgentRunner.usageDict(null)).isEmpty();
        assertThat(AgentRunner.usageDict(Map.of("bad", "NaN"))).isEmpty();
    }

    @Test
    void usageTotalSumsPromptAndCompletion() {
        var usage = Map.of("prompt_tokens", 100, "completion_tokens", 50);
        assertThat(AgentRunner.usageTotal(usage)).isEqualTo(150);
    }

    @Test
    void usageTotalUsesTotalTokensIfPresent() {
        var usage = Map.of("total_tokens", 200, "prompt_tokens", 100, "completion_tokens", 50);
        assertThat(AgentRunner.usageTotal(usage)).isEqualTo(200);
    }

    @Test
    void accumulateUsageAddsValues() {
        var target = new java.util.LinkedHashMap<>(Map.of("prompt_tokens", 100));
        AgentRunner.accumulateUsage(target, Map.of("prompt_tokens", 50, "completion_tokens", 30));
        assertThat(target).containsEntry("prompt_tokens", 150);
        assertThat(target).containsEntry("completion_tokens", 30);
    }

    @Test
    void mergeUsageCreatesNewMapWithSummedValues() {
        var left = Map.of("prompt_tokens", 100);
        var right = Map.of("completion_tokens", 50);
        var result = AgentRunner.mergeUsage(left, right);
        assertThat(result).containsEntry("prompt_tokens", 100);
        assertThat(result).containsEntry("completion_tokens", 50);
    }

    // -- message helpers --

    @Test
    void appendFinalMessageAddsWhenEmpty() {
        var messages = new ArrayList<Map<String, Object>>();
        AgentRunner.appendFinalMessage(messages, "hello");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("role")).isEqualTo("assistant");
        assertThat(messages.get(0).get("content")).isEqualTo("hello");
    }

    @Test
    void appendFinalMessageReplacesLastAssistantWithoutToolCalls() {
        var messages = new ArrayList<Map<String, Object>>();
        messages.add(Map.of("role", "user", "content", "hi"));
        messages.add(Map.of("role", "assistant", "content", "old"));
        AgentRunner.appendFinalMessage(messages, "new");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).get("content")).isEqualTo("new");
    }

    @Test
    void appendFinalMessageSkipsDuplicateContent() {
        var messages = new ArrayList<Map<String, Object>>();
        messages.add(Map.of("role", "assistant", "content", "same"));
        AgentRunner.appendFinalMessage(messages, "same");
        assertThat(messages).hasSize(1);
    }

    @Test
    void appendFinalMessageSkipsNullContent() {
        var messages = new ArrayList<Map<String, Object>>();
        AgentRunner.appendFinalMessage(messages, null);
        AgentRunner.appendFinalMessage(messages, "");
        assertThat(messages).isEmpty();
    }

    @Test
    void appendModelErrorPlaceholderAddsWhenLastIsNotBareAssistant() {
        var messages = new ArrayList<Map<String, Object>>();
        messages.add(Map.of("role", "user", "content", "hi"));
        AgentRunner.appendModelErrorPlaceholder(messages);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).get("role")).isEqualTo("assistant");
    }

    @Test
    void appendModelErrorPlaceholderSkipsWhenAlreadyPlaceholder() {
        var messages = new ArrayList<Map<String, Object>>();
        messages.add(Map.of("role", "assistant", "content", "no tool_calls"));
        AgentRunner.appendModelErrorPlaceholder(messages);
        assertThat(messages).hasSize(1);
    }

    @Test
    void mergeMessageContentConcatenatesStrings() {
        var result = AgentRunner.mergeMessageContent("hello", "world");
        assertThat(result).isInstanceOf(String.class);
        assertThat((String) result).contains("hello").contains("world");
    }

    @Test
    void mergeMessageContentConvertsToBlocks() {
        var result = AgentRunner.mergeMessageContent(
                List.of(Map.of("type", "text", "text", "a")),
                List.of(Map.of("type", "text", "text", "b")));
        assertThat(result).isInstanceOf(List.class);
        assertThat((List<?>) result).hasSize(2);
    }

    // -- findLegalMessageStart --

    @Test
    void findLegalMessageStartReturnsZeroForValidSequence() {
        var messages = List.<Map<String, Object>>of(
                Map.of("role", "assistant", "tool_calls",
                        List.of(Map.of("id", "call-1", "function", Map.of("name", "grep")))),
                Map.of("role", "tool", "tool_call_id", "call-1", "content", "result"));
        assertThat(AgentRunner.findLegalMessageStart(messages)).isEqualTo(0);
    }

    @Test
    void findLegalMessageStartSkipsOrphanToolResult() {
        var messages = List.<Map<String, Object>>of(
                Map.of("role", "tool", "tool_call_id", "orphan-1", "content", "orphan"),
                Map.of("role", "assistant", "tool_calls",
                        List.of(Map.of("id", "call-2", "function", Map.of("name", "grep")))),
                Map.of("role", "tool", "tool_call_id", "call-2", "content", "result"));
        assertThat(AgentRunner.findLegalMessageStart(messages)).isEqualTo(1);
    }

    @Test
    void findLegalMessageStartHandlesEmptyList() {
        assertThat(AgentRunner.findLegalMessageStart(List.of())).isEqualTo(0);
    }

    // -- snipHistory --

    @Test
    void snipHistoryPreservesSystemMessages() {
        var provider = new StubProvider() {
            @Override
            public LLMResponse chat(List<Map<String, Object>> messages,
                                    List<Map<String, Object>> tools, String model,
                                    int maxTokens, double temperature,
                                    String reasoningEffort, Object toolChoice) {
                calls.add(messages);
                return new LLMResponse("ok", List.of(), "stop", Map.of(), null, null, null, null, null, null, null, null, null);
            }
        };
        var runner = new AgentRunner(provider);
        var tools = new com.nanobot.agent.tools.ToolRegistry();
        var spec = new com.nanobot.agent.runner.AgentRunSpec(
                new ArrayList<>(List.of(
                        Map.<String, Object>of("role", "system", "content", "You are helpful."),
                        Map.<String, Object>of("role", "user", "content", "hi"))),
                tools, "test", 5, 16_000,
                null, null, null, null, null, null,
                false, false, null, "test",
                500,  // Small window to force snip
                null, "standard",
                null, false, null,
                null, null, null, null, null, false);

        runner.snipHistory(spec, spec.initialMessages());
        // System message should be preserved
        assertThat(spec.initialMessages().stream()
                .anyMatch(m -> "system".equals(m.get("role")))).isTrue();
    }

    @Test
    void snipHistoryNoopForLargeBudget() {
        var runner = new AgentRunner(new StubProvider() {
            @Override
            public LLMResponse chat(List<Map<String, Object>> messages,
                                    List<Map<String, Object>> tools, String model,
                                    int maxTokens, double temperature,
                                    String reasoningEffort, Object toolChoice) {
                return new LLMResponse("ok", List.of(), "stop", Map.of(), null, null, null, null, null, null, null, null, null);
            }
        });
        var messages = new ArrayList<>(List.<Map<String, Object>>of(
                Map.of("role", "system", "content", "sys"),
                Map.of("role", "user", "content", "hi")));
        var spec = new com.nanobot.agent.runner.AgentRunSpec(
                messages, new com.nanobot.agent.tools.ToolRegistry(),
                "test", 5, 128_000,
                null, null, null, null, null, null,
                false, false, null, "test",
                128_000, null, "standard",
                null, false, null,
                null, null, null, null, null, false);

        runner.snipHistory(spec, messages);
        // Should not snip — message list intact
        assertThat(messages).hasSize(2);
    }

    /** Abstract stub that records chat calls. */
    private abstract static class StubProvider extends LLMProvider {
        final List<List<Map<String, Object>>> calls = new ArrayList<>();

        StubProvider() {
            super(null, null);
        }

        @Override
        public String getDefaultModel() {
            return "stub";
        }
    }
}
