package com.nanobot.agent.tools;

/**
 * 标记接口，表示工具需要接收每次请求的路由元数据。
 *
 * <p>对标 Python {@code nanobot/agent/tools/context.py ContextAware Protocol}。
 * 在工具执行前，agent loop 检查 {@code tool instanceof ContextAware}
 * 并调用 {@link #setContext(RequestContext)}，使工具能路由消息或访问会话作用域状态。
 *
 * <p>例如：SpawnTool 发送后续消息时需要当前请求的 channel 名称和 chat ID。
 */
// 对标 Python @runtime_checkable ContextAware Protocol
public interface ContextAware {
    /**
     * 设置当前请求的上下文。
     *
     * @param ctx 当前请求上下文
     */
    // 对标 Python ContextAware.set_context()
    void setContext(RequestContext ctx);
}
