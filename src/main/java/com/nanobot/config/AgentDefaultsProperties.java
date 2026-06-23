package com.nanobot.config;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;

/**
 * Agent 默认配置。
 * 对标 Python: {@code nanobot/config/schema.py:121-169 class AgentDefaults(Base)}
 */
public class AgentDefaultsProperties {

    // 对标 Python schema.py:124 workspace
    private String workspace = "~/.nanobot/workspace";

    // 对标 Python schema.py:125 model_preset
    private String modelPreset;

    // 对标 Python schema.py:126 model
    private String model = "anthropic/claude-opus-4-5";

    // 对标 Python schema.py:127-129 provider
    private String provider = "auto";

    // 对标 Python schema.py:130 max_tokens
    private int maxTokens = 8192;

    // 对标 Python schema.py:131 context_window_tokens
    private int contextWindowTokens = 65536;

    // 对标 Python schema.py:132 context_block_limit
    private Integer contextBlockLimit;

    // 对标 Python schema.py:133 temperature
    private double temperature = 0.1;

    // 对标 Python schema.py:134 fallback_models
    private List<Object> fallbackModels = List.of();

    // 对标 Python schema.py:135 max_tool_iterations
    private int maxToolIterations = 200;

    // 对标 Python schema.py:136 max_concurrent_subagents
    private int maxConcurrentSubagents = 1;

    // 对标 Python schema.py:137 max_tool_result_chars
    private int maxToolResultChars = 16000;

    // 对标 Python schema.py:138 provider_retry_mode
    private String providerRetryMode = "standard";

    // 对标 Python schema.py:139-145 tool_hint_max_length
    @JsonAlias("toolHintMaxLength")
    private int toolHintMaxLength = 40;

    // 对标 Python schema.py:146 reasoning_effort
    private String reasoningEffort;

    // 对标 Python schema.py:147 timezone
    private String timezone = "UTC";

    // 对标 Python schema.py:148 bot_name
    private String botName = "nanobot";

    // 对标 Python schema.py:149 bot_icon
    private String botIcon = "🐈";

    // 对标 Python schema.py:150 unified_session
    private boolean unifiedSession;

    // 对标 Python schema.py:151 disabled_skills
    private List<String> disabledSkills = List.of();

    // 对标 Python schema.py:152-157 session_ttl_minutes
    @JsonAlias({"idleCompactAfterMinutes", "sessionTtlMinutes"})
    private int sessionTtlMinutes;

    // 对标 Python schema.py:158-161 max_messages
    private int maxMessages = 120;

    // 对标 Python schema.py:162-168 consolidation_ratio
    @JsonAlias("consolidationRatio")
    private double consolidationRatio = 0.5;

    // 对标 Python schema.py:169 dream
    private DreamProperties dream = DreamProperties.DEFAULTS;

    public static final AgentDefaultsProperties DEFAULTS = new AgentDefaultsProperties();

    // --- Getters/Setters ---

    public String getWorkspace() { return workspace; }
    public void setWorkspace(String workspace) { this.workspace = workspace; }

    public String getModelPreset() { return modelPreset; }
    public void setModelPreset(String modelPreset) { this.modelPreset = modelPreset; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public int getContextWindowTokens() { return contextWindowTokens; }
    public void setContextWindowTokens(int contextWindowTokens) { this.contextWindowTokens = contextWindowTokens; }

    public Integer getContextBlockLimit() { return contextBlockLimit; }
    public void setContextBlockLimit(Integer contextBlockLimit) { this.contextBlockLimit = contextBlockLimit; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }

    public List<Object> getFallbackModels() { return fallbackModels; }
    public void setFallbackModels(List<Object> fallbackModels) { this.fallbackModels = fallbackModels; }

    public int getMaxToolIterations() { return maxToolIterations; }
    public void setMaxToolIterations(int maxToolIterations) { this.maxToolIterations = maxToolIterations; }

    public int getMaxConcurrentSubagents() { return maxConcurrentSubagents; }
    public void setMaxConcurrentSubagents(int maxConcurrentSubagents) { this.maxConcurrentSubagents = maxConcurrentSubagents; }

    public int getMaxToolResultChars() { return maxToolResultChars; }
    public void setMaxToolResultChars(int maxToolResultChars) { this.maxToolResultChars = maxToolResultChars; }

    public String getProviderRetryMode() { return providerRetryMode; }
    public void setProviderRetryMode(String providerRetryMode) { this.providerRetryMode = providerRetryMode; }

    public int getToolHintMaxLength() { return toolHintMaxLength; }
    public void setToolHintMaxLength(int toolHintMaxLength) { this.toolHintMaxLength = toolHintMaxLength; }

    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public String getBotName() { return botName; }
    public void setBotName(String botName) { this.botName = botName; }

    public String getBotIcon() { return botIcon; }
    public void setBotIcon(String botIcon) { this.botIcon = botIcon; }

    public boolean isUnifiedSession() { return unifiedSession; }
    public void setUnifiedSession(boolean unifiedSession) { this.unifiedSession = unifiedSession; }

    public List<String> getDisabledSkills() { return disabledSkills; }
    public void setDisabledSkills(List<String> disabledSkills) { this.disabledSkills = disabledSkills; }

    public int getSessionTtlMinutes() { return sessionTtlMinutes; }
    public void setSessionTtlMinutes(int sessionTtlMinutes) { this.sessionTtlMinutes = sessionTtlMinutes; }

    public int getMaxMessages() { return maxMessages; }
    public void setMaxMessages(int maxMessages) { this.maxMessages = maxMessages; }

    public double getConsolidationRatio() { return consolidationRatio; }
    public void setConsolidationRatio(double consolidationRatio) { this.consolidationRatio = consolidationRatio; }

    public DreamProperties getDream() { return dream; }
    public void setDream(DreamProperties dream) { this.dream = dream; }

    /**
     * 将 Agent 默认配置中的 temperature、maxTokens、reasoningEffort 提取为
     * {@link GenerationSettings}，供 LLM 调用时使用。
     *
     * @return 包含当前默认生成参数的 GenerationSettings
     */
    // 对标 Python ModelPresetConfig.to_generation_settings()
    public GenerationSettings toGenerationSettings() {
        return new GenerationSettings(temperature, maxTokens, reasoningEffort);
    }
}
