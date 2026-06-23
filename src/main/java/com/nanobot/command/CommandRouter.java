package com.nanobot.command;

import com.nanobot.bus.OutboundMessage;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 命令路由器——根据消息内容匹配并分发到对应命令处理器。
 *
 * <p>对标 Python {@code nanobot/command/router.py} CommandRouter。
 */
public interface CommandRouter {

    /**
     * 分发命令到对应处理器。
     *
     * @param ctx 命令上下文
     * @return 命令处理结果（可为 null 表示未匹配）
     */
    CompletableFuture<OutboundMessage> dispatch(CommandContext ctx);

    /**
     * 分发优先级命令（在消息处理之前执行）。
     *
     * @param ctx 命令上下文
     * @return 命令处理结果
     */
    CompletableFuture<OutboundMessage> dispatchPriority(CommandContext ctx);

    /**
     * 判断消息是否为优先级命令。
     *
     * @param raw 消息文本
     * @return true 如果匹配优先级命令
     */
    boolean isPriority(String raw);

    /**
     * 判断消息是否为可分发命令。
     *
     * @param raw 消息文本
     * @return true 如果匹配可分发命令
     */
    boolean isDispatchableCommand(String raw);
}
