package com.nanobot.agent.context;

import com.nanobot.agent.session.Session;
import com.nanobot.agent.session.SessionManager;
import com.nanobot.providers.base.LLMResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class ConsolidatorTest {

    private Path workspace;
    private MemoryStore store;
    private SessionManager sessions;
    private Consolidator consolidator;
    private List<Map<String, Object>> lastProviderMessages;
    private String lastProviderModel;
    private LLMResponse mockResponse;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        workspace = tempDir;
        store = new MemoryStore(workspace, 100);
        sessions = new SessionManager(workspace);

        lastProviderMessages = null;
        lastProviderModel = null;
        mockResponse = new LLMResponse("summary text", List.of(), "stop",
                Map.of("prompt_tokens", 100, "completion_tokens", 20),
                null, null, null, null, null, null, null, null, null);

        var provider = new ConsolidatorProvider() {
            @Override
            public LLMResponse chat(String model, List<Map<String, Object>> messages) throws Exception {
                lastProviderModel = model;
                lastProviderMessages = new ArrayList<>(messages);
                return mockResponse;
            }

            @Override
            public int estimatePromptTokens(List<Map<String, Object>> messages,
                                            List<Map<String, Object>> toolDefs) {
                // Simple char/4 estimation for testing
                int total = 0;
                for (var msg : messages) {
                    total += Session.estimateMessageTokens(msg);
                }
                return Math.max(1, total);
            }
        };

        consolidator = new Consolidator(
                store, provider, "test-model", sessions,
                128_000, 4096, 0.5, false,
                (history, currentMessage, channel, chatId, senderId, sessionSummary,
                 sessionMetadata, sessionKey, unifiedSession) -> {
                    // Simple buildMessages stub for testing
                    var msgs = new ArrayList<Map<String, Object>>();
                    msgs.add(Map.of("role", "system", "content", "test system prompt"));
                    for (var h : history) {
                        msgs.add(h);
                    }
                    msgs.add(Map.of("role", "user", "content", currentMessage));
                    return msgs;
                },
                List::of);
    }

    // -- archive --

    @Test
    void archiveCallsProviderWithSummarizationPrompt() throws Exception {
        var messages = List.<Map<String, Object>>of(
                Map.of("role", "user", "content", "hello"),
                Map.of("role", "assistant", "content", "hi there")
        );

        String summary = consolidator.archive(messages, "test-key");

        assertThat(summary).isNotNull();
        assertThat(lastProviderMessages).isNotNull();
        assertThat(lastProviderMessages).hasSize(2);
        assertThat((String) lastProviderMessages.get(0).get("role")).isEqualTo("system");
        // Formatted messages passed as user content to the LLM
        assertThat((String) lastProviderMessages.get(1).get("content")).contains("hello");
        // Verify summary was appended to history
        var history = store.readUnprocessedHistory(0);
        assertThat(history).isNotEmpty();
    }

    @Test
    void archiveReturnsNullForEmptyMessages() throws Exception {
        assertThat(consolidator.archive(List.of(), null)).isNull();
        assertThat(lastProviderMessages).isNull(); // provider never called
    }

    @Test
    void archiveRawArchivesOnProviderError() throws Exception {
        var badProvider = new ConsolidatorProvider() {
            @Override
            public LLMResponse chat(String model, List<Map<String, Object>> messages) throws Exception {
                throw new RuntimeException("LLM down");
            }
        };
        var c = new Consolidator(store, badProvider, "bad-model", sessions,
                128_000, 4096, 0.5, false,
                (h, cm, ch, ci, si, ss, sm, sk, us) -> {
                    var msgs = new ArrayList<Map<String, Object>>();
                    msgs.add(Map.of("role", "user", "content", cm));
                    return msgs;
                }, List::of);

        String result = c.archive(List.of(Map.of("role", "user", "content", "test")), null);
        assertThat(result).isNull(); // fallback, no summary
        // Should have raw-archived
        var history = store.readUnprocessedHistory(0);
        assertThat(history).isNotEmpty();
    }

    // -- pickConsolidationBoundary --

    @Test
    void pickBoundaryFindsUserTurnWithEnoughTokens() {
        var session = new Session("boundary-test");
        session.addMessage("user", "hello world"); // ~2 tokens
        session.addMessage("assistant", "hi there, how can I help you today?"); // ~7 tokens
        session.addMessage("user", "I need help with a complex problem"); // ~7 tokens
        session.addMessage("assistant", "Sure, let me help with that"); // ~6 tokens

        var result = consolidator.pickConsolidationBoundary(session, 10);
        assertThat(result).isNotNull();
        // Should find the second user turn boundary (index 2, after removing first user+assistant)
        assertThat(result[0]).isEqualTo(2); // 2 messages removed (index 0, 1)
    }

    @Test
    void pickBoundaryReturnsNullWhenNoBoundaryFound() {
        var session = new Session("no-boundary");
        session.addMessage("assistant", "just an assistant message");
        session.addMessage("assistant", "another one");

        var result = consolidator.pickConsolidationBoundary(session, 100);
        assertThat(result).isNull();
    }

    // -- estimateSessionPromptTokens --

    @Test
    void estimateSessionPromptTokensReturnsPositive() {
        var session = new Session("est-test");
        session.addMessage("user", "hello");
        session.addMessage("assistant", "hi");

        int[] result = consolidator.estimateSessionPromptTokens(session);
        assertThat(result[0]).isPositive();
        // Source is "estimation" when buildMessages succeeds
    }

    // -- truncateToTokenBudget --

    @Test
    void truncateToTokenBudgetPreservesShortText() {
        String text = "short text";
        String result = consolidator.truncateToTokenBudget(text);
        assertThat(result).isEqualTo(text);
    }

    @Test
    void truncateToTokenBudgetTruncatesLongText() {
        var sb = new StringBuilder();
        for (int i = 0; i < 500_000; i++) sb.append("x");
        String text = sb.toString();
        String result = consolidator.truncateToTokenBudget(text);
        assertThat(result.length()).isLessThan(text.length());
    }

    // -- inputTokenBudget --

    @Test
    void inputTokenBudgetReservesCompletionAndSafety() {
        int budget = consolidator.inputTokenBudget();
        assertThat(budget).isEqualTo(128_000 - 4096 - 1024); // ctx - completion - safety
    }

    // -- compactIdleSession --

    @Test
    void compactIdleSessionReturnsEmptyForEmptySession() throws Exception {
        var session = sessions.getOrCreate("idle-test");
        String result = consolidator.compactIdleSession("idle-test", 8);
        assertThat(result).isEmpty();
    }

    @Test
    void compactIdleSessionArchivesOldMessages() throws Exception {
        var session = sessions.getOrCreate("compact-test");
        for (int i = 0; i < 15; i++) {
            session.addMessage("user", "message " + i);
            session.addMessage("assistant", "response " + i);
        }
        sessions.save(session);
        sessions.invalidate("compact-test");

        String result = consolidator.compactIdleSession("compact-test", 4);
        // Should have archived some messages, kept only suffix
        assertThat(lastProviderMessages).isNotNull();
    }
}
