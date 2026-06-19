package com.nanobot.agent.command;

import com.nanobot.bus.InboundMessage;
import com.nanobot.bus.OutboundMessage;
import com.nanobot.agent.session.Session;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class CommandRouterTest {

    private final Session session = new Session("test-session");
    private final InboundMessage sampleMsg = new InboundMessage(
            "discord", "user-1", "chat-1", "content", null, List.of(), null, null);

    private CommandContext makeCtx(String raw) {
        // Extract args: everything after the first space
        var spaceIdx = raw.indexOf(' ');
        var args = spaceIdx >= 0 ? raw.substring(spaceIdx + 1) : "";
        return new CommandContext(sampleMsg, session, "test-session", raw, args);
    }

    // -- priority tier --

    @Test
    void isPriorityReturnsTrueForRegisteredPriority() {
        var router = new CommandRouter();
        router.priority("/stop", ctx -> new OutboundMessage("ch", "cid", "stopped", null, null, null, null));
        assertThat(router.isPriority("/stop")).isTrue();
    }

    @Test
    void isPriorityReturnsFalseForUnregistered() {
        var router = new CommandRouter();
        assertThat(router.isPriority("/stop")).isFalse();
    }

    @Test
    void dispatchPriorityExecutesHandler() {
        var router = new CommandRouter();
        router.priority("/stop", ctx -> new OutboundMessage(ctx.msg().channel(), ctx.msg().chatId(),
                "stopped all", null, null, null, null));
        var result = router.dispatchPriority(makeCtx("/stop"));
        assertThat(result).isNotNull();
        assertThat(result.content()).isEqualTo("stopped all");
    }

    @Test
    void dispatchPriorityReturnsNullForUnknown() {
        var router = new CommandRouter();
        assertThat(router.dispatchPriority(makeCtx("/stop"))).isNull();
    }

    // -- exact tier --

    @Test
    void isDispatchableCommandFindsExactMatch() {
        var router = new CommandRouter();
        router.exact("/new", ctx -> new OutboundMessage("ch", "cid", "new session", null, null, null, null));
        assertThat(router.isDispatchableCommand("/new")).isTrue();
        assertThat(router.isDispatchableCommand("/other")).isFalse();
    }

    @Test
    void dispatchFindsExactMatch() {
        var router = new CommandRouter();
        router.exact("/new", ctx -> new OutboundMessage(ctx.msg().channel(), ctx.msg().chatId(),
                "new session started", null, null, null, null));
        var result = router.dispatch(makeCtx("/new"));
        assertThat(result).isNotNull();
        assertThat(result.content()).isEqualTo("new session started");
    }

    // -- prefix tier --

    @Test
    void isDispatchableCommandFindsPrefixMatch() {
        var router = new CommandRouter();
        router.prefix("/model ", ctx -> null);
        assertThat(router.isDispatchableCommand("/model gpt5")).isTrue();
        assertThat(router.isDispatchableCommand("/mod")).isFalse();
    }

    @Test
    void dispatchFindsPrefixMatchAndSetsArgs() {
        var router = new CommandRouter();
        router.prefix("/model ", ctx -> new OutboundMessage("ch", "cid",
                "switched to " + ctx.args(), null, null, null, null));
        var result = router.dispatch(makeCtx("/model gpt5"));
        assertThat(result).isNotNull();
        assertThat(result.content()).isEqualTo("switched to gpt5");
    }

    @Test
    void dispatchOrderPrefersLongestPrefixFirst() {
        var router = new CommandRouter();
        var captured = new String[]{""};
        router.prefix("/dream-log ", ctx -> { captured[0] = "log:" + ctx.args(); return null; });
        router.prefix("/dream ", ctx -> { captured[0] = "dream:" + ctx.args(); return null; });
        router.dispatch(makeCtx("/dream-log 123"));
        assertThat(captured[0]).isEqualTo("log:123"); // longer prefix matched first
    }

    @Test
    void dispatchReturnsNullWhenNoMatch() {
        var router = new CommandRouter();
        assertThat(router.dispatch(makeCtx("/unknown"))).isNull();
    }

    // -- built-in command specs --

    @Test
    void builtinCommandSpecsAreDefined() {
        var specs = BuiltinCommands.builtinSpecs();
        assertThat(specs).isNotEmpty();
        assertThat(specs).anyMatch(s -> s.get("command").equals("/stop"));
        assertThat(specs).anyMatch(s -> s.get("command").equals("/help"));
    }

    // -- cmdHelp lists commands --

    @Test
    void cmdHelpReturnsAllCommands() {
        var ctx = makeCtx("/help");
        var result = BuiltinCommands.cmdHelp(ctx);
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("/stop");
        assertThat(result.content()).contains("/help");
    }

    // -- cmdStop returns stop message --

    @Test
    void cmdStopReturnsStopMessage() {
        var ctx = makeCtx("/stop");
        var result = BuiltinCommands.cmdStop(ctx);
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("Stopped");
    }

    // -- cmdNew returns new session message --

    @Test
    void cmdNewReturnsNewSessionMessage() {
        var ctx = makeCtx("/new");
        var result = BuiltinCommands.cmdNew(ctx);
        assertThat(result).isNotNull();
        assertThat(result.content().toLowerCase()).contains("new", "session");
    }

    // -- full registration (CommonCommands) --

    @Test
    void registerAllCommandsMakesPriorityDetectable() {
        var router = new CommandRouter();
        BuiltinCommands.registerAll(router);
        assertThat(router.isPriority("/stop")).isTrue();
        assertThat(router.isPriority("/restart")).isTrue();
        assertThat(router.isPriority("/status")).isTrue();
    }

    @Test
    void registerAllCommandsMakesExactDispatchable() {
        var router = new CommandRouter();
        BuiltinCommands.registerAll(router);
        assertThat(router.isDispatchableCommand("/new")).isTrue();
        assertThat(router.isDispatchableCommand("/help")).isTrue();
        assertThat(router.isDispatchableCommand("/dream")).isTrue();
        assertThat(router.isDispatchableCommand("/skill")).isTrue();
        assertThat(router.isDispatchableCommand("/model gpt5")).isTrue();
    }

    // -- cmdStatus --

    @Test
    void cmdStatusReturnsContent() {
        var ctx = makeCtx("/status");
        var result = BuiltinCommands.cmdStatus(ctx);
        assertThat(result).isNotNull();
        // Without a loop, status shows fallback message
        assertThat(result.content()).isNotNull();
    }

    // -- cmdModel --

    @Test
    void cmdModelWithoutArgsShowsCurrentModel() {
        var ctx = makeCtx("/model");
        var result = BuiltinCommands.cmdModel(ctx);
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("## Model", "Current model");
    }

    @Test
    void cmdModelWithArgsShowsSwitchMessage() {
        var ctx = makeCtx("/model gpt5");
        var result = BuiltinCommands.cmdModel(ctx);
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("Switched");
    }

    @Test
    void cmdModelWithMultipleArgsShowsUsage() {
        var ctx = makeCtx("/model a b");
        var result = BuiltinCommands.cmdModel(ctx);
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("Usage");
    }

    // -- cmdHistory --

    @Test
    void cmdHistoryReturnsContent() {
        var ctx = makeCtx("/history");
        var result = BuiltinCommands.cmdHistory(ctx);
        assertThat(result).isNotNull();
        // Empty session shows "no history" message
        assertThat(result.content()).containsIgnoringCase("no conversation history");
    }

    @Test
    void cmdHistoryWithSessionShowsMessages() {
        var session = new Session("test-session");
        session.addMessage("user", "hello");
        session.addMessage("assistant", "hi there");
        var msg = new InboundMessage("discord", "user-1", "chat-1", "/history",
                null, List.of(), null, null);
        var ctx = new CommandContext(msg, session, "test-session", "/history", "");
        var result = BuiltinCommands.cmdHistory(ctx);
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("user", "hello");
    }

    // -- cmdDream --

    @Test
    void cmdDreamReturnsDreamingMessage() {
        var ctx = makeCtx("/dream");
        var result = BuiltinCommands.cmdDream(ctx);
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("Dreaming");
    }

    // -- cmdSkill --

    @Test
    void cmdSkillReturnsContent() {
        var ctx = makeCtx("/skill");
        var result = BuiltinCommands.cmdSkill(ctx);
        assertThat(result).isNotNull();
    }

    // -- cmdGoal --

    @Test
    void cmdGoalWithoutArgsShowsUsage() {
        var ctx = makeCtx("/goal");
        var result = BuiltinCommands.cmdGoal(ctx);
        assertThat(result).isNotNull();
        assertThat(result.content()).contains("Usage");
    }

    @Test
    void cmdGoalWithArgsReturnsNull() {
        var ctx = makeCtx("/goal build something");
        var result = BuiltinCommands.cmdGoal(ctx);
        assertThat(result).isNull(); // rewrites into normal agent turn
    }
}
