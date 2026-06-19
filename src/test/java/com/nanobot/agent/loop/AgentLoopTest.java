package com.nanobot.agent.loop;

import com.nanobot.agent.command.CommandRouter;
import com.nanobot.agent.command.BuiltinCommands;
import com.nanobot.agent.context.ContextBuilder;
import com.nanobot.agent.context.Consolidator;
import com.nanobot.agent.context.MemoryStore;
import com.nanobot.agent.runner.AgentRunner;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.*;

class AgentLoopTest {

    private Path workspace;
    private SessionManager sessions;
    private AgentLoop loop;
    private TestMessageBus bus;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        workspace = tempDir;
        sessions = new SessionManager(workspace);
        bus = new TestMessageBus();
        var provider = new StubProvider();
        var runner = new AgentRunner(provider);
        var store = new MemoryStore(workspace, 100);
        var context = new ContextBuilder(workspace, null);
        var commands = new CommandRouter();
        BuiltinCommands.registerAll(commands);
        var consolidator = new Consolidator(
                store, new com.nanobot.agent.context.ConsolidatorProvider() {
            @Override
            public LLMResponse chat(String m, List<Map<String, Object>> msgs) {
                return new LLMResponse("ok", List.of(), "stop", Map.of(), null, null, null, null, null, null, null, null, null);
            }
        }, "test", sessions, 128_000, 4096, 0.5, false,
                (h, cm, ch, ci, si, ss, sm, sk, us) -> {
                    var msgs = new java.util.ArrayList<Map<String, Object>>();
                    msgs.add(Map.of("role", "system", "content", "test"));
                    msgs.addAll(h);
                    msgs.add(Map.of("role", "user", "content", cm));
                    return msgs;
                }, List::of);

        loop = new AgentLoop(bus, runner, context, commands, consolidator,
                sessions, workspace, "test-model", 10, 128_000, 16_000,
                "standard", new ConcurrentHashMap<>(), new Semaphore(10),
                new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
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
        var smallLoop = new AgentLoop(bus, new AgentRunner(new StubProvider()),
                new ContextBuilder(workspace, null),
                new CommandRouter(), null, sessions, workspace,
                "test", 10, 100, 16_000, "standard",
                new ConcurrentHashMap<>(), new Semaphore(10),
                new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
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
