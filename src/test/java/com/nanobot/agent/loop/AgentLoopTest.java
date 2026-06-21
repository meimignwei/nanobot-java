package com.nanobot.agent.loop;

import com.nanobot.agent.session.SessionManager;
import com.nanobot.bus.InboundMessage;
import com.nanobot.bus.MessageBus;
import com.nanobot.bus.OutboundMessage;
import com.nanobot.providers.base.LLMProvider;
import com.nanobot.providers.base.LLMResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class AgentLoopTest {

    private Path workspace;
    private SessionManager sessions;
    private AgentLoop loop;
    private TestMessageBus bus;

    /** 创建 AgentLoop 的便捷工厂，注入测试 bus/provider/sessions。
     *  对标 Python AgentLoop.__init__() 全参构造器。 */
    private AgentLoop createLoop(MessageBus bus, LLMProvider provider,
                                  Path workspace, SessionManager sessions,
                                  String model, int maxIterations,
                                  int contextWindowTokens) {
        return new AgentLoop(
                bus, provider, workspace,
                model, maxIterations,
                null, // maxConcurrentSubagents
                contextWindowTokens,
                null, // contextBlockLimit
                16_000, // maxToolResultChars
                "standard", // providerRetryMode
                null, // toolHintMaxLength
                true, // restrictToWorkspace
                sessions,
                null, // mcpServers
                null, // channelsConfig
                null, // timezone
                60, // sessionTtlMinutes
                0.5, // consolidationRatio
                120, // maxMessages
                false, // unifiedSession
                null, // disabledSkills
                null, // toolsConfig
                null, // nanobotConfig
                null, // providerSnapshotLoader
                null, // providerSignature
                null, // modelPresets
                null, // modelPreset
                null, // presetSnapshotLoader
                null, // runtimeEvents
                null  // runtimeModelPublisher
        );
    }

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        workspace = tempDir;
        sessions = new SessionManager(workspace);
        bus = new TestMessageBus();
        var provider = new StubProvider();

        loop = createLoop(bus, provider, workspace, sessions,
                "test-model", 10, 128_000);
        loop.setRunning(true);
    }

    @Test
    void agentLoopIsInitialized() {
        assertThat(loop).isNotNull();
        assertThat(loop.isRunning()).isTrue();
    }

    @Test
    void processSimpleMessageProducesOutbound() throws Exception {
        var msg = new InboundMessage("test", "user-1", "chat-1", "hello",
                null, List.of(), null, null);
        bus.enqueueInbound(msg);

        loop.processMessage(msg);

        assertThat(bus.lastPublished).isNotNull();
        assertThat(bus.lastPublished.content()).isNotNull();
    }

    @Test
    void processEmptyMessageSkips() throws Exception {
        var msg = new InboundMessage("test", "user-1", "chat-1", "",
                null, List.of(), null, null);
        bus.enqueueInbound(msg);

        loop.processMessage(msg);

        // Should not crash; empty message handled gracefully
        assertThat(loop.isRunning()).isTrue();
    }

    @Test
    void turnStateTransitions() {
        // Verify the transition table
        assertThat(AgentLoop.TRANSITIONS.get(
                Map.entry(TurnState.RESTORE, "ok"))).isEqualTo(TurnState.COMPACT);
        assertThat(AgentLoop.TRANSITIONS.get(
                Map.entry(TurnState.COMPACT, "ok"))).isEqualTo(TurnState.COMMAND);
        assertThat(AgentLoop.TRANSITIONS.get(
                Map.entry(TurnState.COMMAND, "dispatch"))).isEqualTo(TurnState.BUILD);
        assertThat(AgentLoop.TRANSITIONS.get(
                Map.entry(TurnState.COMMAND, "shortcut"))).isEqualTo(TurnState.DONE);
    }

    @Test
    void priorityCommandDispatchesWithoutLock() {
        var msg = new InboundMessage("test", "user-1", "chat-1", "/stop",
                null, List.of(), null, null);
        bus.enqueueInbound(msg);

        assertThat(loop.commands().isPriority("/stop")).isTrue();
        assertThat(loop.commands().isDispatchableCommand("/help")).isTrue();
    }

    // -- replayTokenBudget --

    @Test
    void replayTokenBudgetReturnsPositiveBudget() {
        int budget = loop.replayTokenBudget();
        assertThat(budget).isPositive();
        assertThat(budget).isLessThan(loop.contextWindowTokens());
    }

    @Test
    void replayTokenBudgetUsesHalfWindowAsFloor() {
        // With a small context window, should fall back to half
        var smallLoop = createLoop(bus, new StubProvider(),
                workspace, sessions, "test", 10, 100);
        int budget = smallLoop.replayTokenBudget();
        // 100 - 1 - 1024 = -925, so floor should be max(128, 100/2) = 128
        assertThat(budget).isEqualTo(128);
    }

    // -- persistSubagentFollowup --

    @Test
    void persistSubagentFollowupAddsMessage() {
        var session = sessions.getOrCreate("test:chat");
        var msg = new InboundMessage("system", "subagent", "test:chat",
                "Subagent result: done", null, List.of(),
                Map.of("subagent_task_id", "task-1"), null);

        boolean added = loop.persistSubagentFollowup(session, msg);
        assertThat(added).isTrue();
        assertThat(session.messages()).hasSize(1);
        assertThat(session.messages().get(0).get("injected_event")).isEqualTo("subagent_result");
    }

    @Test
    void persistSubagentFollowupDedupesByTaskId() {
        var session = sessions.getOrCreate("test:chat");
        var msg = new InboundMessage("system", "subagent", "test:chat",
                "result", null, List.of(),
                Map.of("subagent_task_id", "task-1"), null);

        loop.persistSubagentFollowup(session, msg);
        boolean second = loop.persistSubagentFollowup(session, msg);
        assertThat(second).isFalse();
        assertThat(session.messages()).hasSize(1);
    }

    @Test
    void persistSubagentFollowupSkipsEmptyContent() {
        var session = sessions.getOrCreate("test:chat");
        var msg = new InboundMessage("system", "subagent", "test:chat",
                "", null, List.of(),
                Map.of("subagent_task_id", "task-1"), null);

        boolean added = loop.persistSubagentFollowup(session, msg);
        assertThat(added).isFalse();
        assertThat(session.messages()).isEmpty();
    }

    // -- restorePendingUserTurn --

    @Test
    void restorePendingUserTurnAppendsErrorForIncompleteTurn() {
        var session = sessions.getOrCreate("test:chat");
        session.addMessage("user", "hello");
        session.metadata().put("_pending_user_turn", true);

        boolean restored = loop.restorePendingUserTurn(session);
        assertThat(restored).isTrue();
        assertThat(session.messages()).hasSize(2);
        assertThat(session.messages().get(1).get("role")).isEqualTo("assistant");
        assertThat((String) session.messages().get(1).get("content")).contains("interrupted");
        assertThat(session.metadata().get("_pending_user_turn")).isNull();
    }

    @Test
    void restorePendingUserTurnSkipsWhenNoFlag() {
        var session = sessions.getOrCreate("test:chat");
        session.addMessage("user", "hello");

        boolean restored = loop.restorePendingUserTurn(session);
        assertThat(restored).isFalse();
        assertThat(session.messages()).hasSize(1);
    }

    // -- clearRuntimeCheckpoint --

    @Test
    void clearRuntimeCheckpointRemovesKey() {
        var session = sessions.getOrCreate("test:chat");
        session.metadata().put("runtime_checkpoint", Map.of("phase", "awaiting_tools"));

        loop.clearRuntimeCheckpoint(session);
        assertThat(session.metadata().get("runtime_checkpoint")).isNull();
    }

    @Test
    void clearRuntimeCheckpointHandlesNull() {
        loop.clearRuntimeCheckpoint(null); // should not throw
    }

    // -- processMessage routes system channel --

    @Test
    void processMessageRoutesSystemChannelToSystemHandler() {
        var msg = new InboundMessage("system", "subagent", "test:chat",
                "Subagent done", null, List.of(), Map.of(), null);

        var result = loop.processMessage(msg);
        assertThat(result).isNotNull();
        assertThat(result.channel()).isEqualTo("test");
        assertThat(result.content()).isNotNull();
    }

    // -- checkpointMessageKey --

    @Test
    void checkpointMessageKeyReturnsExpectedFields() {
        var msg = Map.<String, Object>of(
                "role", "assistant",
                "content", "Hello",
                "tool_call_id", "call-1",
                "name", "read_file",
                "tool_calls", List.of(),
                "reasoning_content", "thinking...",
                "thinking_blocks", List.of());
        var key = AgentLoop.checkpointMessageKey(msg);
        assertThat(key).hasSize(7);
        assertThat(key.get(0)).isEqualTo("assistant");
        assertThat(key.get(1)).isEqualTo("Hello");
        assertThat(key.get(2)).isEqualTo("call-1");
        assertThat(key.get(3)).isEqualTo("read_file");
    }

    @Test
    void checkpointMessageKeyHandlesNullValues() {
        var msg = Map.<String, Object>of("role", "user");
        var key = AgentLoop.checkpointMessageKey(msg);
        assertThat(key).hasSize(7);
        assertThat(key.get(0)).isEqualTo("user");
        assertThat(key.get(1)).isNull();
    }

    @Test
    void checkpointMessageKeysEqualForEqualMessages() {
        var msg1 = Map.<String, Object>of("role", "tool", "content", "result",
                "tool_call_id", "id-1", "name", "grep");
        var msg2 = Map.<String, Object>of("role", "tool", "content", "result",
                "tool_call_id", "id-1", "name", "grep");
        assertThat(AgentLoop.checkpointMessageKey(msg1))
                .isEqualTo(AgentLoop.checkpointMessageKey(msg2));
    }

    // -- referenceNonImageAttachments --

    @Test
    void referenceNonImageAttachmentsAppendsNonImageRefs() {
        var result = AgentLoop.referenceNonImageAttachments("hello",
                List.of("file.pdf", "photo.png", "data.csv"));
        assertThat(result).contains("[Attachment: file.pdf]");
        assertThat(result).contains("[Attachment: data.csv]");
        assertThat(result).doesNotContain("[Attachment: photo.png]");
    }

    @Test
    void referenceNonImageAttachmentsHandlesEmptyMedia() {
        var result = AgentLoop.referenceNonImageAttachments("hello", List.of());
        assertThat(result).isEqualTo("hello");
    }

    @Test
    void referenceNonImageAttachmentsHandlesNullMedia() {
        var result = AgentLoop.referenceNonImageAttachments("hello", null);
        assertThat(result).isEqualTo("hello");
    }

    @Test
    void referenceNonImageAttachmentsOnlyImagesReturnsOriginal() {
        var result = AgentLoop.referenceNonImageAttachments("test",
                List.of("a.png", "b.jpg", "c.webp"));
        assertThat(result).isEqualTo("test");
    }

    // -- isImageFile --

    @Test
    void isImageFileRecognizesCommonFormats() {
        assertThat(AgentLoop.isImageFile("photo.png")).isTrue();
        assertThat(AgentLoop.isImageFile("photo.jpg")).isTrue();
        assertThat(AgentLoop.isImageFile("photo.jpeg")).isTrue();
        assertThat(AgentLoop.isImageFile("photo.gif")).isTrue();
        assertThat(AgentLoop.isImageFile("photo.webp")).isTrue();
        assertThat(AgentLoop.isImageFile("photo.svg")).isTrue();
    }

    @Test
    void isImageFileRejectsNonImageFiles() {
        assertThat(AgentLoop.isImageFile("doc.pdf")).isFalse();
        assertThat(AgentLoop.isImageFile("data.csv")).isFalse();
        assertThat(AgentLoop.isImageFile("script.py")).isFalse();
    }

    // -- runtimeChatId --

    @Test
    void runtimeChatIdUsesContextChatIdWhenPresent() {
        var msg = new InboundMessage("slack", "user-1", "chat-1", "hi",
                null, List.of(), Map.of("context_chat_id", "thread-42"), null);
        assertThat(AgentLoop.runtimeChatId(msg)).isEqualTo("thread-42");
    }

    @Test
    void runtimeChatIdFallsBackToChatId() {
        var msg = new InboundMessage("discord", "user-1", "chat-99", "hi",
                null, List.of(), null, null);
        assertThat(AgentLoop.runtimeChatId(msg)).isEqualTo("chat-99");
    }

    // -- restoreRuntimeCheckpointFull --

    @Test
    void restoreRuntimeCheckpointFullMaterializesPendingToolCalls() {
        var session = sessions.getOrCreate("test:chat");
        session.metadata().put("runtime_checkpoint", Map.of(
                "assistant_message", Map.of("role", "assistant", "content", "Let me check"),
                "completed_tool_results", List.of(),
                "pending_tool_calls", List.of(Map.of(
                        "id", "call-1",
                        "function", Map.of("name", "grep")))));

        loop.restoreRuntimeCheckpointFull(session);

        var msgs = session.messages();
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(0).get("content")).isEqualTo("Let me check");
        assertThat(msgs.get(1).get("role")).isEqualTo("tool");
        assertThat(msgs.get(1).get("content")).isEqualTo(
                "Error: Task interrupted before this tool finished.");
    }

    @Test
    void restoreRuntimeCheckpointFullNoopForMissingCheckpoint() {
        var session = sessions.getOrCreate("test:chat");
        assertThat(loop.restoreRuntimeCheckpointFull(session)).isFalse();
    }

    @Test
    void restoreRuntimeCheckpointFullDetectsOverlap() {
        var session = sessions.getOrCreate("test:chat");
        // Pre-populate with one message that matches the checkpoint
        session.addMessage("assistant", "Let me check",
                Map.of("tool_calls", List.of(Map.of("id", "call-1",
                        "function", Map.of("name", "grep")))));

        session.metadata().put("runtime_checkpoint", Map.of(
                "assistant_message", Map.of("role", "assistant", "content", "Let me check",
                        "tool_calls", List.of(Map.of("id", "call-1",
                                "function", Map.of("name", "grep")))),
                "completed_tool_results", List.of(),
                "pending_tool_calls", List.of(Map.of(
                        "id", "call-1",
                        "function", Map.of("name", "grep")))));

        loop.restoreRuntimeCheckpointFull(session);

        // Only the pending tool call should be added (assistant message overlaps)
        var msgs = session.messages();
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(1).get("role")).isEqualTo("tool");
    }

    // -- helpers --

    static class TestMessageBus extends MessageBus {
        OutboundMessage lastPublished;

        void enqueueInbound(InboundMessage msg) {
            try {
                publishInbound(msg);
            } catch (InterruptedException ignored) {}
        }

        @Override
        public void publishOutbound(OutboundMessage msg) throws InterruptedException {
            this.lastPublished = msg;
            super.publishOutbound(msg);
        }
    }

    static class StubProvider extends LLMProvider {
        StubProvider() { super(null, null); }

        @Override
        public LLMResponse chat(List<Map<String, Object>> messages,
                                List<Map<String, Object>> tools, String model,
                                int maxTokens, double temperature,
                                String reasoningEffort, Object toolChoice) {
            return new LLMResponse("Hello from stub!", List.of(), "stop",
                    Map.of(), null, null, null, null, null, null, null, null, null);
        }

        @Override
        public String getDefaultModel() { return "stub"; }
    }
}
