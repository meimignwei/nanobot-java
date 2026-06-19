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
