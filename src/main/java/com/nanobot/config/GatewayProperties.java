package com.nanobot.config;

/**
 * Gateway/server 配置。对标 Python: {@code nanobot/config/schema.py:265-270 class GatewayConfig}
 */
public class GatewayProperties {

    // 对标 Python schema.py:268
    private String host = "127.0.0.1";

    // 对标 Python schema.py:269
    private int port = 18790;

    // 对标 Python schema.py:270
    private HeartbeatProperties heartbeat = new HeartbeatProperties();

    public static final GatewayProperties DEFAULTS = new GatewayProperties();

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public HeartbeatProperties getHeartbeat() { return heartbeat; }
    public void setHeartbeat(HeartbeatProperties heartbeat) { this.heartbeat = heartbeat; }
}
