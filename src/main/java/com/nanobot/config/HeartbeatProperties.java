package com.nanobot.config;

/**
 * 心跳服务配置。对标 Python: {@code nanobot/config/schema.py:249-254 class HeartbeatConfig}
 */
public class HeartbeatProperties {

    // 对标 Python schema.py:252
    private boolean enabled = true;

    // 对标 Python schema.py:253
    private int intervalS = 30 * 60;

    // 对标 Python schema.py:254
    private int keepRecentMessages = 8;

    public static final HeartbeatProperties DEFAULTS = new HeartbeatProperties();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getIntervalS() { return intervalS; }
    public void setIntervalS(int intervalS) { this.intervalS = intervalS; }

    public int getKeepRecentMessages() { return keepRecentMessages; }
    public void setKeepRecentMessages(int keepRecentMessages) { this.keepRecentMessages = keepRecentMessages; }
}
