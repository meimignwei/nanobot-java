package com.nanobot.bus;

/**
 * Message bus 模块常量。
 * 对标 Python: {@code nanobot/bus/events.py:9-15} 中的模块级常量
 */
public final class BusConstants {

    private BusConstants() {}

    /** 出站消息 metadata 中的 channel 无关 UI payload 键，用于向前端透传 UI 状态 */
    // 对标 Python events.py:9
    public static final String OUTBOUND_META_AGENT_UI = "_agent_ui";

    /** 入站消息 metadata 中的运行时控制键，用于传递 ack、mcp_reload 等控制指令 */
    // 对标 Python events.py:13
    public static final String INBOUND_META_RUNTIME_CONTROL = "_runtime_control";

    /** 运行时控制消息中的确认回执值 */
    // 对标 Python events.py:14
    public static final String RUNTIME_CONTROL_ACK = "_ack";

    /** 运行时控制消息中的 MCP 重载指令值 */
    // 对标 Python events.py:15
    public static final String RUNTIME_CONTROL_MCP_RELOAD = "mcp_reload";
}
