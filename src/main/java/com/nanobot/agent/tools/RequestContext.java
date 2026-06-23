package com.nanobot.agent.tools;

import java.util.Map;

/**
 * 每次请求的路由上下文，在消息处理时注入到工具中。
 *
 * <p>对标 Python {@code nanobot/agent/tools/context.py RequestContext}（8 行）。
 * 不可变 record，通过 {@link ThreadLocal} 绑定到虚拟线程。
 * Java 虚拟线程天然 ThreadLocal-safe：每个虚拟线程有独立值，互不干扰。
 *
 * @param channel    来源 channel 名（如 "telegram"、"websocket"）
 * @param chatId     聊天/房间/用户标识
 * @param messageId  可选的消息 ID，用于回复线程
 * @param sessionKey 会话路由 key（如 "telegram:12345"）
 * @param metadata   channel 附带的任意键值元数据
 */
// 对标 Python @dataclass(frozen=True) RequestContext
public record RequestContext(
        String channel,
        String chatId,
        String messageId,
        String sessionKey,
        Map<String, Object> metadata
) {

    /** ThreadLocal 存储当前请求上下文。 */
    // 对标 Python _CURRENT_REQUEST_CONTEXT ContextVar
    private static final ThreadLocal<RequestContext> CURRENT =
            ThreadLocal.withInitial(() -> null);

    /**
     * 将 RequestContext 绑定到当前虚拟线程，返回先前的上下文（用于嵌套恢复）。
     *
     * @param ctx 要绑定的请求上下文
     * @return 先前的上下文，可能为 null
     */
    // 对标 Python bind_request_context()
    public static RequestContext bind(RequestContext ctx) {
        RequestContext previous = CURRENT.get();
        CURRENT.set(ctx);
        return previous;
    }

    /** 解除当前请求上下文的绑定。 */
    // 对标 Python reset_request_context()
    public static void unbind() {
        CURRENT.remove();
    }

    /**
     * 获取绑定到当前虚拟线程的 RequestContext。
     *
     * @return 当前请求上下文，未绑定时为 null
     */
    // 对标 Python current_request_context()
    public static RequestContext current() {
        return CURRENT.get();
    }

    /**
     * 从当前上下文获取 session key 的便捷方法。
     *
     * @return session key，未绑定时为 null
     */
    // 对标 Python current_request_session_key()
    public static String currentSessionKey() {
        RequestContext ctx = current();
        return (ctx != null) ? ctx.sessionKey() : null;
    }
}
