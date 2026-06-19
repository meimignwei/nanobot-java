package com.nanobot.bus;

/**
 * Bus metadata key constants. Mirrors Python events.py module-level constants exactly.
 */
public final class BusConstants {

    private BusConstants() {}

    /** Optional OutboundMessage.metadata key for structured, channel-agnostic UI payloads. */
    public static final String OUTBOUND_META_AGENT_UI = "_agent_ui";

    /** Internal inbound metadata key for runtime control messages (in-process channels). */
    public static final String INBOUND_META_RUNTIME_CONTROL = "_runtime_control";

    /** Runtime control ack value. */
    public static final String RUNTIME_CONTROL_ACK = "_ack";

    /** Runtime control MCP reload value. */
    public static final String RUNTIME_CONTROL_MCP_RELOAD = "mcp_reload";
}
