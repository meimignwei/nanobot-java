package com.nanobot.config;

/**
 * Mirrors Python GatewayConfig.
 */
public record GatewayProperties(
        String host,
        Integer port,
        HeartbeatProperties heartbeat
) {
    public GatewayProperties {
        if (host == null) host = "127.0.0.1";
        if (port == null) port = 18790;
        if (heartbeat == null) heartbeat = new HeartbeatProperties(null, null, null);
    }
}
