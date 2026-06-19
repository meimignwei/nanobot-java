package com.nanobot.agent.command;

import com.nanobot.bus.OutboundMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 纯字典式命令路由器，三级匹配。
 * 对应 Python CommandRouter 类（command/router.py）。
 *
 * <p>三层匹配：</p>
 * <ul>
 *   <li><b>priority</b> — 精确匹配，无 session 锁即可调度（/stop、/restart）</li>
 *   <li><b>exact</b>   — 精确匹配，需持有 session 锁（/new、/help）</li>
 *   <li><b>prefix</b>  — 最长前缀优先匹配（/model gpt5）</li>
 * </ul>
 */
public class CommandRouter {

    /** 优先级命令（无需 session 锁） */
    private final Map<String, Function<CommandContext, OutboundMessage>> priority = new LinkedHashMap<>();
    /** 精确匹配命令 */
    private final Map<String, Function<CommandContext, OutboundMessage>> exact = new LinkedHashMap<>();
    /** 前缀匹配命令，按前缀长度降序排列（最长优先） */
    private final List<PrefixEntry> prefix = new ArrayList<>();

    private record PrefixEntry(String prefix, Function<CommandContext, OutboundMessage> handler) {}

    /** 注册优先级命令（精确匹配，不持锁可调度） */
    public void priority(String cmd, Function<CommandContext, OutboundMessage> handler) {
        priority.put(cmd.toLowerCase(), handler);
    }

    /** 注册精确匹配命令 */
    public void exact(String cmd, Function<CommandContext, OutboundMessage> handler) {
        exact.put(cmd.toLowerCase(), handler);
    }

    /** 注册前缀匹配命令，自动按前缀长度降序排列（最长优先匹配） */
    public void prefix(String pfx, Function<CommandContext, OutboundMessage> handler) {
        prefix.add(new PrefixEntry(pfx.toLowerCase(), handler));
        prefix.sort((a, b) -> Integer.compare(b.prefix.length(), a.prefix.length()));
    }

    /** 检查文本是否匹配已注册的优先级命令 */
    public boolean isPriority(String text) {
        return priority.containsKey(text.strip().toLowerCase());
    }

    /** 检查文本是否可被 dispatch（精确或前缀匹配） */
    public boolean isDispatchableCommand(String text) {
        String cmd = text.strip().toLowerCase();
        if (exact.containsKey(cmd)) return true;
        for (var entry : prefix) {
            if (cmd.startsWith(entry.prefix())) return true;
        }
        return false;
    }

    /** 调度优先级命令（不经 session 锁），未匹配返回 null */
    public OutboundMessage dispatchPriority(CommandContext ctx) {
        var handler = priority.get(ctx.raw().toLowerCase());
        if (handler != null) {
            return handler.apply(ctx);
        }
        return null;
    }

    /**
     * 调度命令：先精确匹配，再前缀匹配。
     * 前缀匹配时自动设置 ctx.args 为前缀之后的部分。
     */
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
