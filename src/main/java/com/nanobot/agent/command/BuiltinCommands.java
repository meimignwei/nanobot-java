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

    // 对应 Python builtin_command_palette() (builtin.py:123)
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

    // 对应 Python build_help_text() (builtin.py:687)
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

    // 对应 Python cmd_help() (builtin.py:677)
    public static OutboundMessage cmdHelp(CommandContext ctx) {
        return replyText(ctx, buildHelpText());
    }

    // -- /stop --

    // 对应 Python cmd_stop() (builtin.py:128)
    public static OutboundMessage cmdStop(CommandContext ctx) {
        var al = loop(ctx);
        int total = al != null ? al.cancelActiveTasks(ctx.key()) : 0;
        var content = "Stopped " + total + " task(s).";
        return reply(ctx, content);
    }

    // -- /restart --

    // 对应 Python cmd_restart() (builtin.py:140)
    public static OutboundMessage cmdRestart(CommandContext ctx) {
        return reply(ctx, "Restarting...");
    }

    // -- /status --

    // 对应 Python cmd_status() (builtin.py:160)
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

    // 对应 Python cmd_new() (builtin.py:205)
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

    // 对应 Python cmd_model() (builtin.py:252)
    public static OutboundMessage cmdModel(CommandContext ctx) {
        var al = loop(ctx);
        var args = ctx.args().strip();
        var meta = textMeta(ctx);

        // Show current model status
        if (args.isEmpty()) {
            return new OutboundMessage(ctx.msg().channel(), ctx.msg().chatId(),
                    modelCommandStatus(al), null, null, meta, null);
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
                        "Could not switch model preset: " + commandErrorMessage(e)
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

    // 对应 Python cmd_history() (builtin.py:568)
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
            var formatted = formatHistoryMessage(msg);
            if (formatted == null) continue;
            var ts = msg.get("timestamp");
            var time = ts != null ? String.valueOf(ts).substring(11, 19) : "??:??:??";
            sb.append("[").append(i + 1).append("] (").append(time)
                    .append(")\n  ").append(formatted).append("\n\n");
        }

        return new OutboundMessage(ctx.msg().channel(), ctx.msg().chatId(),
                sb.toString(), null, null, textMeta(ctx), null);
    }

    // -- /goal --

    // 对应 Python cmd_goal() (builtin.py:614)
    public static OutboundMessage cmdGoal(CommandContext ctx) {
        var goal = ctx.args().strip();
        if (goal.isEmpty()) {
            return replyText(ctx, "Usage: /goal <long-running task description>");
        }
        // cmdGoal returns null to rewrite into a normal agent turn
        return null;
    }

    // -- /dream --

    // 对应 Python cmd_dream() (builtin.py:306)
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

    // 对应 Python cmd_dream_log() (builtin.py:449)
    public static OutboundMessage cmdDreamLog(CommandContext ctx) {
        var al = loop(ctx);
        var args = ctx.args().strip();
        if (al != null && al.context() != null && al.context().getMemory() != null) {
            var mem = al.context().getMemory();
            if (mem.getLastDreamCursor() > 0) {
                // Full implementation requires MemoryStore git raw_archive.
                // For now, show the cursor with the dream-log format.
                var sb = new StringBuilder("## Dream Log\n\n");
                sb.append("- **Last Dream cursor:** ").append(mem.getLastDreamCursor()).append("\n");
                if (!args.isEmpty()) {
                    sb.append("- **Requested SHA:** `").append(args).append("`\n");
                }
                sb.append("\nFull Dream log requires git raw_archive — not yet ported.\n");
                return replyText(ctx, sb.toString());
            }
        }
        return replyText(ctx, "Dream has not run yet.");
    }

    // -- /dream-restore --

    // 对应 Python cmd_dream_restore() (builtin.py:499)
    public static OutboundMessage cmdDreamRestore(CommandContext ctx) {
        var args = ctx.args().strip();
        if (!args.isEmpty()) {
            // Specific SHA restore — requires git raw_archive
            return replyText(ctx, "Dream restore to `" + args
                    + "` requires git raw_archive — not yet ported.");
        }
        // List versions — uses formatDreamRestoreList
        return replyText(ctx, formatDreamRestoreList(java.util.List.of()));
    }

    // -- /skill --

    // 对应 Python cmd_skill() (builtin.py:658)
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

    // ================================================================
    // Formatting helpers (Python builtin.py private helpers)
    // ================================================================

    private static final int HISTORY_DEFAULT_COUNT = 10;
    private static final int HISTORY_MAX_COUNT = 50;
    private static final int HISTORY_MAX_CONTENT_CHARS = 200;

    // -- _format_preset_names (builtin.py:223-224) --

    static String formatPresetNames(java.util.Collection<String> names) {
        if (names == null || names.isEmpty()) return "(none configured)";
        var sb = new StringBuilder();
        boolean first = true;
        for (var name : names) {
            if (!first) sb.append(", ");
            sb.append("`").append(name).append("`");
            first = false;
        }
        return sb.toString();
    }

    // -- _model_preset_names (builtin.py:227-230) --

    static java.util.List<String> modelPresetNames(AgentLoop loop) {
        var names = new java.util.LinkedHashSet<String>();
        names.add("default");
        // model_presets not yet ported — falls back to default only
        var result = new java.util.ArrayList<>(names);
        java.util.Collections.sort(result);
        return result;
    }

    // -- _active_model_preset_name (builtin.py:233-234) --

    static String activeModelPresetName(AgentLoop loop) {
        if (loop == null) return "default";
        var preset = loop.getModelPreset();
        return preset != null ? preset : "default";
    }

    // -- _command_error_message (builtin.py:237-238) --

    // 对应 Python _command_error_message() (builtin.py:237)
    static String commandErrorMessage(Exception exc) {
        if (exc.getMessage() != null) return exc.getMessage();
        return exc.toString();
    }

    // -- _model_command_status (builtin.py:241-249) --

    static String modelCommandStatus(AgentLoop loop) {
        var names = modelPresetNames(loop);
        var active = activeModelPresetName(loop);
        var sb = new StringBuilder("## Model\n");
        if (loop != null) {
            sb.append("- **Current model:** `").append(loop.model()).append("`\n");
            sb.append("- **Context window:** ").append(loop.contextWindowTokens()).append(" tokens\n");
        } else {
            sb.append("- **Current model:** `default`\n");
        }
        sb.append("- **Current preset:** `").append(active).append("`\n");
        sb.append("- **Available presets:** ").append(formatPresetNames(names)).append("\n");
        return sb.toString();
    }

    // -- _format_history_message (builtin.py:550-565) --

    @SuppressWarnings("unchecked")
    // 对应 Python _format_history_message() (builtin.py:550)
    static String formatHistoryMessage(Map<String, Object> msg) {
        var role = msg.get("role");
        if (!"user".equals(role) && !"assistant".equals(role)) return null;
        var content = msg.get("content");
        String contentStr;
        if (content instanceof List<?> blocks) {
            var parts = new java.util.ArrayList<String>();
            for (var b : blocks) {
                if (b instanceof Map<?, ?> bm && "text".equals(bm.get("type"))) {
                    var t = bm.get("text");
                    if (t != null) parts.add(t.toString());
                }
            }
            contentStr = String.join(" ", parts);
        } else {
            contentStr = content != null ? content.toString() : "";
        }
        contentStr = contentStr.strip();
        if (contentStr.isEmpty()) return null;
        if (contentStr.length() > HISTORY_MAX_CONTENT_CHARS) {
            contentStr = contentStr.substring(0, HISTORY_MAX_CONTENT_CHARS) + "...";
        }
        var label = "user".equals(role) ? "You" : "Bot";
        return label + ": " + contentStr;
    }

    // -- _extract_changed_files (builtin.py:377-394) --

    static java.util.List<String> extractChangedFiles(String diff) {
        var files = new java.util.ArrayList<String>();
        var seen = new java.util.HashSet<String>();
        for (var line : diff.split("\n")) {
            if (!line.startsWith("diff --git ")) continue;
            var parts = line.split(" ");
            if (parts.length < 4) continue;
            var path = parts[3];
            if (path.startsWith("b/")) path = path.substring(2);
            if (seen.contains(path)) continue;
            seen.add(path);
            files.add(path);
        }
        return files;
    }

    // -- _format_changed_files (builtin.py:397-401) --

    // 对应 Python _format_changed_files() (builtin.py:397)
    static String formatChangedFiles(String diff) {
        var files = extractChangedFiles(diff);
        if (files.isEmpty()) return "No tracked memory files changed.";
        var sb = new StringBuilder();
        boolean first = true;
        for (var f : files) {
            if (!first) sb.append(", ");
            sb.append("`").append(f).append("`");
            first = false;
        }
        return sb.toString();
    }

    // -- _format_dream_log_content (builtin.py:404-429) --

    static String formatDreamLogContent(String commitSha, String commitTimestamp,
                                         String commitMessage, String diff,
                                         String requestedSha) {
        var filesLine = formatChangedFiles(diff);
        var sb = new StringBuilder();
        sb.append("## Dream Update\n\n");
        sb.append(requestedSha != null
                ? "Here is the selected Dream memory change.\n\n"
                : "Here is the latest Dream memory change.\n\n");
        sb.append("- **Commit:** `").append(commitSha).append("`\n");
        sb.append("- **Time:** ").append(commitTimestamp).append("\n");
        sb.append("- **Changed files:** ").append(filesLine).append("\n");
        if (diff != null && !diff.isEmpty()) {
            sb.append("\nUse `/dream-restore ").append(commitSha)
                    .append("` to undo this change.\n\n");
            sb.append("```diff\n").append(diff.strip()).append("\n```\n");
        } else {
            sb.append("\nDream recorded this version, but there is no file diff to display.\n");
        }
        return sb.toString();
    }

    // -- _format_dream_restore_list (builtin.py:432-446) --

    static String formatDreamRestoreList(java.util.List<?> commits) {
        var sb = new StringBuilder();
        sb.append("## Dream Restore\n\n");
        sb.append("Choose a Dream memory version to restore. Latest first:\n\n");
        for (var c : commits) {
            String sha = "", timestamp = "", message = "";
            if (c instanceof Map<?, ?> cm) {
                var s = cm.get("sha"); sha = s != null ? String.valueOf(s) : "?";
                var t = cm.get("timestamp"); timestamp = t != null ? String.valueOf(t) : "?";
                var m = cm.get("message"); message = m != null ? String.valueOf(m) : "";
            }
            var firstLine = message.split("\n")[0];
            sb.append("- `").append(sha).append("` ").append(timestamp)
                    .append(" - ").append(firstLine).append("\n");
        }
        sb.append("\nPreview a version with `/dream-log <sha>` before restoring it.\n");
        sb.append("Restore a version with `/dream-restore <sha>`.\n");
        return sb.toString();
    }

    // -- translateAndDispatch (Python register_builtin_commands) --

    // 对应 Python register_builtin_commands() (builtin.py:698)
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
