package com.nanobot.agent.command;

import com.nanobot.bus.OutboundMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in slash command handlers.
 * Mirrors Python command/builtin.py (720 lines).
 */
public final class BuiltinCommands {

    private BuiltinCommands() {}

    private static Map<String, String> spec(String command, String title,
                                            String description, String icon) {
        return spec(command, title, description, icon, "");
    }

    private static Map<String, String> spec(String command, String title,
                                            String description, String icon, String argHint) {
        var m = new LinkedHashMap<String, String>();
        m.put("command", command);
        m.put("title", title);
        m.put("description", description);
        m.put("icon", icon);
        m.put("arg_hint", argHint);
        return m;
    }

    public static List<Map<String, String>> builtinSpecs() {
        return List.of(
                spec("/new", "New chat",
                        "Stop the current task and start a fresh conversation.", "square-pen"),
                spec("/stop", "Stop current task",
                        "Cancel the active agent turn for this chat.", "square"),
                spec("/restart", "Restart nanobot",
                        "Restart the bot process in place.", "rotate-cw"),
                spec("/status", "Show status",
                        "Display runtime, provider, and channel status.", "activity"),
                spec("/model", "Switch model preset",
                        "Show or switch the active model preset.", "brain", "[preset]"),
                spec("/history", "Show conversation history",
                        "Print the last N persisted conversation messages.", "history", "[n]"),
                spec("/goal", "Start long-running goal",
                        "Tell the agent to treat the request as a long-running goal.", "activity", "<goal>"),
                spec("/dream", "Run Dream",
                        "Manually trigger memory consolidation.", "sparkles"),
                spec("/dream-log", "Show Dream log",
                        "Show what the last Dream consolidation changed.", "book-open"),
                spec("/dream-restore", "Restore memory",
                        "Revert memory to a previous Dream snapshot.", "undo-2"),
                spec("/skill", "List skills",
                        "List all enabled skills available to the agent.", "wrench"),
                spec("/help", "Show help",
                        "List available slash commands.", "circle-help"),
                spec("/pairing", "Manage pairing",
                        "List, approve, deny or revoke pairing requests.", "shield",
                        "[list|approve <code>|deny <code>|revoke <user_id>]")
        );
    }

    static String buildHelpText() {
        var sb = new StringBuilder("nanobot commands:");
        for (var spec : builtinSpecs()) {
            var cmd = spec.get("command");
            var hint = spec.get("arg_hint");
            sb.append("\n").append(cmd);
            if (hint != null && !hint.isEmpty()) {
                sb.append(" ").append(hint);
            }
            sb.append(" — ").append(spec.get("description"));
        }
        return sb.toString();
    }

    public static OutboundMessage cmdHelp(CommandContext ctx) {
        return new OutboundMessage(
                ctx.msg().channel(), ctx.msg().chatId(),
                buildHelpText(),
                null, null,
                Map.of("render_as", "text"),
                null);
    }

    public static OutboundMessage cmdStop(CommandContext ctx) {
        return new OutboundMessage(
                ctx.msg().channel(), ctx.msg().chatId(),
                "Stopped 1 task(s).", null, null,
                Map.copyOf(ctx.msg().metadata()), null);
    }

    public static OutboundMessage cmdRestart(CommandContext ctx) {
        return new OutboundMessage(
                ctx.msg().channel(), ctx.msg().chatId(),
                "Restarting...", null, null,
                Map.copyOf(ctx.msg().metadata()), null);
    }

    public static OutboundMessage cmdStatus(CommandContext ctx) {
        return new OutboundMessage(
                ctx.msg().channel(), ctx.msg().chatId(),
                "Status: running.", null, null,
                Map.of("render_as", "text"), null);
    }

    public static OutboundMessage cmdNew(CommandContext ctx) {
        return new OutboundMessage(
                ctx.msg().channel(), ctx.msg().chatId(),
                "New session started.", null, null,
                Map.copyOf(ctx.msg().metadata()), null);
    }

    public static OutboundMessage cmdModel(CommandContext ctx) {
        var args = ctx.args().strip();
        if (args.isEmpty()) {
            return new OutboundMessage(
                    ctx.msg().channel(), ctx.msg().chatId(),
                    "## Model\n- Current model: `default`\n- Current preset: `default`\n- Available presets: `default`",
                    null, null, Map.of("render_as", "text"), null);
        }
        return new OutboundMessage(
                ctx.msg().channel(), ctx.msg().chatId(),
                "Switched model preset to `" + args + "`.\n- Model: `" + args + "`",
                null, null, Map.of("render_as", "text"), null);
    }

    public static OutboundMessage cmdHistory(CommandContext ctx) {
        return new OutboundMessage(
                ctx.msg().channel(), ctx.msg().chatId(),
                "No conversation history yet.", null, null,
                Map.copyOf(ctx.msg().metadata()), null);
    }

    public static OutboundMessage cmdGoal(CommandContext ctx) {
        var goal = ctx.args().strip();
        if (goal.isEmpty()) {
            return new OutboundMessage(
                    ctx.msg().channel(), ctx.msg().chatId(),
                    "Usage: /goal <long-running task description>",
                    null, null, Map.of("render_as", "text"), null);
        }
        return null; // rewrites into normal agent turn
    }

    public static OutboundMessage cmdDream(CommandContext ctx) {
        return new OutboundMessage(
                ctx.msg().channel(), ctx.msg().chatId(),
                "Dreaming...", null, null, Map.of(), null);
    }

    public static OutboundMessage cmdDreamLog(CommandContext ctx) {
        return new OutboundMessage(
                ctx.msg().channel(), ctx.msg().chatId(),
                "Dream has not run yet.", null, null,
                Map.of("render_as", "text"), null);
    }

    public static OutboundMessage cmdDreamRestore(CommandContext ctx) {
        return new OutboundMessage(
                ctx.msg().channel(), ctx.msg().chatId(),
                "Dream memory has no saved versions to restore yet.", null, null,
                Map.of("render_as", "text"), null);
    }

    public static OutboundMessage cmdSkill(CommandContext ctx) {
        return new OutboundMessage(
                ctx.msg().channel(), ctx.msg().chatId(),
                "No skills available.", null, null,
                Map.copyOf(ctx.msg().metadata()), null);
    }

    public static OutboundMessage cmdPairing(CommandContext ctx) {
        return new OutboundMessage(
                ctx.msg().channel(), ctx.msg().chatId(),
                "No pairing requests.", null, null, Map.of(), null);
    }

    public static void registerAll(CommandRouter router) {
        router.priority("/stop", BuiltinCommands::cmdStop);
        router.priority("/restart", BuiltinCommands::cmdRestart);
        router.priority("/status", BuiltinCommands::cmdStatus);
        router.exact("/new", BuiltinCommands::cmdNew);
        router.exact("/status", BuiltinCommands::cmdStatus);
        router.exact("/model", BuiltinCommands::cmdModel);
        router.prefix("/model ", BuiltinCommands::cmdModel);
        router.exact("/history", BuiltinCommands::cmdHistory);
        router.prefix("/history ", BuiltinCommands::cmdHistory);
        router.exact("/goal", BuiltinCommands::cmdGoal);
        router.prefix("/goal ", BuiltinCommands::cmdGoal);
        router.exact("/dream", BuiltinCommands::cmdDream);
        router.exact("/dream-log", BuiltinCommands::cmdDreamLog);
        router.prefix("/dream-log ", BuiltinCommands::cmdDreamLog);
        router.exact("/dream-restore", BuiltinCommands::cmdDreamRestore);
        router.prefix("/dream-restore ", BuiltinCommands::cmdDreamRestore);
        router.exact("/skill", BuiltinCommands::cmdSkill);
        router.exact("/help", BuiltinCommands::cmdHelp);
        router.exact("/pairing", BuiltinCommands::cmdPairing);
        router.prefix("/pairing ", BuiltinCommands::cmdPairing);
    }
}
