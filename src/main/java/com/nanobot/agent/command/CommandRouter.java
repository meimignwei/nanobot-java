package com.nanobot.agent.command;

import com.nanobot.bus.OutboundMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Pure dict-based command dispatch with three tiers.
 * Mirrors Python CommandRouter class (command/router.py).
 *
 * Tier 1: priority — exact-match, dispatched WITHOUT session lock
 * Tier 2: exact — exact-match, dispatched WITH session lock
 * Tier 3: prefix — longest-prefix-first match
 */
public class CommandRouter {

    private final Map<String, Function<CommandContext, OutboundMessage>> priority = new LinkedHashMap<>();
    private final Map<String, Function<CommandContext, OutboundMessage>> exact = new LinkedHashMap<>();
    private final List<PrefixEntry> prefix = new ArrayList<>();

    private record PrefixEntry(String prefix, Function<CommandContext, OutboundMessage> handler) {}

    public void priority(String cmd, Function<CommandContext, OutboundMessage> handler) {
        priority.put(cmd.toLowerCase(), handler);
    }

    public void exact(String cmd, Function<CommandContext, OutboundMessage> handler) {
        exact.put(cmd.toLowerCase(), handler);
    }

    public void prefix(String pfx, Function<CommandContext, OutboundMessage> handler) {
        prefix.add(new PrefixEntry(pfx.toLowerCase(), handler));
        prefix.sort((a, b) -> Integer.compare(b.prefix.length(), a.prefix.length()));
    }

    public boolean isPriority(String text) {
        return priority.containsKey(text.strip().toLowerCase());
    }

    public boolean isDispatchableCommand(String text) {
        String cmd = text.strip().toLowerCase();
        if (exact.containsKey(cmd)) return true;
        for (var entry : prefix) {
            if (cmd.startsWith(entry.prefix())) return true;
        }
        return false;
    }

    public OutboundMessage dispatchPriority(CommandContext ctx) {
        var handler = priority.get(ctx.raw().toLowerCase());
        if (handler != null) {
            return handler.apply(ctx);
        }
        return null;
    }

    public OutboundMessage dispatch(CommandContext ctx) {
        var cmd = ctx.raw().toLowerCase();

        var exactHandler = exact.get(cmd);
        if (exactHandler != null) {
            return exactHandler.apply(ctx);
        }

        for (var entry : prefix) {
            if (cmd.startsWith(entry.prefix())) {
                ctx.setArgs(ctx.raw().substring(entry.prefix().length()));
                return entry.handler().apply(ctx);
            }
        }

        return null;
    }
}
