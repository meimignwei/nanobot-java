package com.nanobot.config;

/**
 * OpenAI 兼容 API 服务配置。对标 Python: {@code nanobot/config/schema.py:257-262 class ApiConfig}
 */
public class ApiProperties {

    // 对标 Python schema.py:260
    private String host = "127.0.0.1";

    // 对标 Python schema.py:261
    private int port = 8900;

    // 对标 Python schema.py:262
    private double timeout = 120.0;

    public static final ApiProperties DEFAULTS = new ApiProperties();

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public double getTimeout() { return timeout; }
    public void setTimeout(double timeout) { this.timeout = timeout; }
}
