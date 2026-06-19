package com.nanobot.config;

/**
 * Mirrors Python HeartbeatConfig (backed by cron).
 */
public record HeartbeatProperties(
        Boolean enabled,
        Integer intervalS,
        Integer keepRecentMessages
) {
    public HeartbeatProperties {
        if (enabled == null) enabled = true;
        if (intervalS == null) intervalS = 30 * 60; // 30 minutes
        if (keepRecentMessages == null) keepRecentMessages = 8;
    }
}
