package com.nanobot.config;

/**
 * Agent 配置容器。
 * 对标 Python: {@code nanobot/config/schema.py:172-175 class AgentsConfig}
 */
public class AgentsProperties {

    // 对标 Python schema.py:175 defaults
    private AgentDefaultsProperties defaults = new AgentDefaultsProperties();

    public AgentDefaultsProperties getDefaults() { return defaults; }
    public void setDefaults(AgentDefaultsProperties defaults) { this.defaults = defaults; }
}
