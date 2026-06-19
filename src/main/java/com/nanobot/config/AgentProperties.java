package com.nanobot.config;

/**
 * Mirrors Python AgentsConfig.
 */
public record AgentProperties(AgentDefaultsProperties defaults) {
    public AgentProperties {
        if (defaults == null) defaults = new AgentDefaultsProperties(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }
}
