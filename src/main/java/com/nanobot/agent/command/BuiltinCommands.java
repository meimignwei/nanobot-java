package com.nanobot.agent.command;

import com.nanobot.agent.loop.AgentLoop;
import com.nanobot.agent.session.Session;
import com.nanobot.bus.OutboundMessage;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in slash command handlers.
 * Mirrors Python command/builtin.py (720 lines).
 *
 * Each handler receives a CommandContext with access to the AgentLoop
 * instance via {@code ctx.loop()}.
 */
public final class BuiltinCommands {

    private BuiltinCommands() {}

    // -- spec helpers --

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

    // -- buildHelpText --

    static String buildHelpText() {
        var sb = new StringBuilder("nanobot commands:");
        for (var spec : builtinSpecs()) {
            var cmd = spec.get("command");
            var hint = spec.get("arg_hint");
            sb.append("\n").append(cmd);
            if (hint != null && !hint.isEmpty()) {
                sb.append(" ").append(hint);
            }
            sb.append("\n  ").append(spec.get("description"));
        }
        return sb.toString();
    }

    // -- helper: retrieve AgentLoop from context --

    private static AgentLoop loop(CommandContext ctx) {
        if (ctx.loop() instanceof AgentLoop al) return al;
        return null;
    }

    // -- helper: reply metadata --

    private static Map<String, Object> replyMeta(CommandContext ctx) {
        return ctx.msg().metadata() != null
                ? new LinkedHashMap<>(ctx.msg().metadata())
                : new LinkedHashMap<>();
    }

    private static Map<String, Object> textMeta(CommandContext ctx) {
        var meta = replyMeta(ctx);
        meta.put("render_as", "text");
        return meta;
    }

    private static OutboundMessage reply(CommandContext ctx, String content) {
        return new OutboundMessage(ctx.msg().channel(), ctx.msg().chatId(),
                content, null, null, Map.copyOf(ctx.msg().metadata()), null);
    }

    private static OutboundMessage replyText(CommandContext ctx, String content) {
        return new OutboundMessage(ctx.msg().channel(), ctx.msg().chatId(),
                content, null, null, textMeta(ctx), null);
    }

    // ================================================================
    // Command handlers
    // ================================================================

    // -- /help --

    public static OutboundMessage cmdHelp(CommandContext ctx) {
        return replyText(ctx, buildHelpText());
    }

    // -- /stop --

    public static OutboundMessage cmdStop(CommandContext ctx) {
        var al = loop(ctx);
        int total = al != null ? al.cancelActiveTasks(ctx.key()) : 0;
        var content = "Stopped " + total + " task(s).";
        return reply(ctx, content);
    }

    // -- /restart --

    public static OutboundMessage cmdRestart(CommandContext ctx) {
        return reply(ctx, "Restarting...");
    }

    // -- /status --

    public static OutboundMessage cmdStatus(CommandContext ctx) {
        var al = loop(ctx);
        if (al == null) {
            return replyText(ctx, "Status: no agent loop available.");
        }

        var sb = new StringBuilder("## Status\n\n");
        sb.append("- **Model:** `").append(al.model()).append("`\n");
        sb.append("- **Context window:** ").append(al.contextWindowTokens()).append(" tokens\n");
        sb.append("- **Max iterations:** ").append(
                al.maxIterations() > 0 ? String.valueOf(al.maxIterations()) : "unlimited").append("\n");

        // Session info
        var session = ctx.session();
        if (session == null && al.sessions() != null) {
            session = al.sessions().getOrCreate(ctx.key());
        }
        if (session != null) {
            int msgCount = session.messages().size();
            sb.append("- **Session messages:** ").append(msgCount).append("\n");
            sb.append("- **Session age:** ").append(formatDuration(
                    Duration.between(session.createdAt(), Instant.now()))).append("\n");
        }

        // Tools
        var tools = al.tools();
        if (tools != null) {
            var names = tools.toolNames();
            sb.append("- **Tools registered:** ").append(names.size());
            if (!names.isEmpty()) {
                sb.append(" (").append(String.join(", ", names)).append(")");
            }
            sb.append("\n");
        }

        // Memory
        var mem = al.context() != null && al.context().getMemory() != null
                ? al.context().getMemory()
                : null;
        if (mem != null) {
            sb.append("- **Dream cursor:** ").append(mem.getLastDreamCursor()).append("\n");
        }

        return new OutboundMessage(ctx.msg().channel(), ctx.msg().chatId(),
                sb.toString(), null, null, textMeta(ctx), null);
    }

    // -- /new --

    public static OutboundMessage cmdNew(CommandContext ctx) {
        var al = loop(ctx);
        if (al != null) al.cancelActiveTasks(ctx.key());

        var session = ctx.session();
        if (session == null && al != null && al.sessions() != null) {
            session = al.sessions().getOrCreate(ctx.key());
        }
        if (session != null) {
            session.clear();
            if (al != null && al.sessions() != null) {
                al.sessions().save(session);
            }
        }

        return reply(ctx, "New session started.");
    }

    // -- /model --

    public static OutboundMessage cmdModel(CommandContext ctx) {
        var al = loop(ctx);
        var args = ctx.args().strip();
        var meta = textMeta(ctx);

        // Show current model status
        if (args.isEmpty()) {
            var sb = new StringBuilder("## Model\n");
            if (al != null) {
                sb.append("- **Current model:** `").append(al.model()).append("`\n");
                sb.append("- **Context window:** ").append(al.contextWindowTokens()).append(" tokens\n");
                var preset = al.getModelPreset();
                sb.append("- **Current preset:** `").append(preset != null ? preset : "default").append("`\n");
                sb.append("- **Available presets:** `default`\n");
            } else {
                sb.append("- **Current model:** `default`\n");
                sb.append("- **Current preset:** `default`\n");
                sb.append("- **Available presets:** `default`\n");
            }
            return new OutboundMessage(ctx.msg().channel(), ctx.msg().chatId(),
                    sb.toString(), null, null, meta, null);
        }

        // Switch preset
        var parts = args.split("\\s+");
        if (parts.length != 1) {
            return new OutboundMessage(ctx.msg().channel(), ctx.msg().chatId(),
                    "Usage: `/model [preset]`", null, null, meta, null);
        }

        if (al != null) {
            try {
                al.setModelPreset(parts[0]);
            } catch (Exception e) {
                return new OutboundMessage(ctx.msg().channel(), ctx.msg().chatId(),
                        "Could not switch model preset: " + e.getMessage()
                                + "\n\nAvailable presets: `default`",
                        null, null, meta, null);
            }
        }

        var sb = new StringBuilder();
        sb.append("Switched model preset to `").append(parts[0]).append("`.\n");
        if (al != null) {
            sb.append("- **Model:** `").append(al.model()).append("`\n");
            sb.append("- **Context window:** ").append(al.contextWindowTokens()).append(" tokens\n");
        } else {
            sb.append("- **Model:** `").append(parts[0]).append("`\n");
        }
        return new OutboundMessage(ctx.msg().channel(), ctx.msg().chatId(),
                sb.toString(), null, null, meta, null);
    }

    // -- /history --

    public static OutboundMessage cmdHistory(CommandContext ctx) {
        var session = ctx.session();
        if (session == null) {
            var al = loop(ctx);
            if (al != null && al.sessions() != null) {
                session = al.sessions().getOrCreate(ctx.key());
            }
        }

        if (session == null || session.messages().isEmpty()) {
            return replyText(ctx, "No conversation history yet.");
        }

        var args = ctx.args().strip();
        int n = 10; // default: show last 10
        if (!args.isEmpty()) {
            try {
                n = Integer.parseInt(args.split("\\s+")[0]);
            } catch (NumberFormatException ignored) {}
        }
        n = Math.max(1, Math.min(n, 100));

        var msgs = session.messages();
        int from = Math.max(0, msgs.size() - n);
        var sb = new StringBuilder("## History (last " + (msgs.size() - from) + " of " + msgs.size() + ")\n\n");

        for (int i = from; i < msgs.size(); i++) {
            var msg = msgs.get(i);
            var role = String.valueOf(msg.getOrDefault("role", "?"));
            var content = msg.get("content");
            String contentStr;
            if (content instanceof String s) {
                contentStr = s.length() > 200 ? s.substring(0, 200) + "..." : s;
            } else if (content instanceof List<?> blocks) {
                contentStr = "[content blocks: " + blocks.size() + " parts]";
            } else {
                contentStr = String.valueOf(content);
            }
            var ts = msg.get("timestamp");
            var time = ts != null ? String.valueOf(ts).substring(11, 19) : "??:??:??";
            sb.append("[").append(i + 1).append("] **").append(role).append("** (").append(time)
                    .append(")\n  ").append(contentStr.replace("\n", "\n  ")).append("\n\n");
        }

        return new OutboundMessage(ctx.msg().channel(), ctx.msg().chatId(),
                sb.toString(), null, null, textMeta(ctx), null);
    }

    // -- /goal --

    public static OutboundMessage cmdGoal(CommandContext ctx) {
        var goal = ctx.args().strip();
        if (goal.isEmpty()) {
            return replyText(ctx, "Usage: /goal <long-running task description>");
        }
        // cmdGoal returns null to rewrite into a normal agent turn
        return null;
    }

    // -- /dream --

    public static OutboundMessage cmdDream(CommandContext ctx) {
        var al = loop(ctx);
        if (al != null && al.consolidator() != null && ctx.session() != null) {
            al.scheduleBackground(() -> {
                try {
                    al.consolidator().maybeConsolidateByTokens(ctx.session(), null);
                } catch (Exception ignored) {}
            });
        }
        return reply(ctx, "Dreaming...");
    }

    // -- /dream-log --

    public static OutboundMessage cmdDreamLog(CommandContext ctx) {
        var al = loop(ctx);
        if (al != null && al.context() != null && al.context().getMemory() != null) {
            var mem = al.context().getMemory();
            if (mem.getLastDreamCursor() > 0) {
                return replyText(ctx, "Dream has run. Cursor: " + mem.getLastDreamCursor());
            }
        }
        return replyText(ctx, "Dream has not run yet.");
    }

    // -- /dream-restore --

    public static OutboundMessage cmdDreamRestore(CommandContext ctx) {
        return replyText(ctx, "Dream memory has no saved versions to restore yet.");
    }

    // -- /skill --

    public static OutboundMessage cmdSkill(CommandContext ctx) {
        var al = loop(ctx);
        if (al != null && al.tools() != null) {
            var names = al.tools().toolNames();
            if (!names.isEmpty()) {
                var sb = new StringBuilder("## Skills & Tools\n\n");
                for (var name : names) {
                    var tool = al.tools().get(name);
                    var desc = tool != null ? tool.description() : "";
                    sb.append("- **").append(name).append("**");
                    if (desc != null && !desc.isEmpty()) {
                        sb.append(": ").append(desc);
                    }
                    sb.append("\n");
                }
                return new OutboundMessage(ctx.msg().channel(), ctx.msg().chatId(),
                        sb.toString(), null, null, textMeta(ctx), null);
            }
        }
        return reply(ctx, "No skills available.");
    }

    // -- /pairing --

    public static OutboundMessage cmdPairing(CommandContext ctx) {
        var args = ctx.args().strip().toLowerCase();
        if (args.isEmpty() || "list".equals(args)) {
            return replyText(ctx, "No pairing requests.");
        }
        return replyText(ctx, "No pairing requests to manage.");
    }

    // -- translateAndDispatch (Python register_builtin_commands) --

    public static void registerAll(CommandRouter router) {
        // Priority tier — dispatched WITHOUT session lock
        router.priority("/stop", BuiltinCommands::cmdStop);
        router.priority("/restart", BuiltinCommands::cmdRestart);
        router.priority("/status", BuiltinCommands::cmdStatus);

        // Exact tier — dispatched WITH session lock
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

    // -- helpers --

    static String formatDuration(Duration d) {
        long days = d.toDays();
        long hours = d.toHours() % 24;
        long minutes = d.toMinutes() % 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m";
        return d.toSeconds() + "s";
    }
}
