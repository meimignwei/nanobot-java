package com.nanobot.config;

/**
 * 命名的模型 + 生成参数 preset。
 * 对标 Python: {@code nanobot/config/schema.py:101-118 class ModelPresetConfig}
 */
public record ModelPresetProperties(
        String label,
        String model,
        String provider,
        int maxTokens,
        int contextWindowTokens,
        double temperature,
        String reasoningEffort) {

    public ModelPresetProperties {
        if (provider == null) provider = "auto";
        if (maxTokens == 0) maxTokens = 8192;
        if (contextWindowTokens == 0) contextWindowTokens = 65536;
        if (temperature == 0.0) temperature = 0.1;
    }

    /**
     * 从 AgentDefaultsProperties 构建隐式的 "default" preset，
     * 将 agent 的 model/provider/maxTokens/contextWindowTokens/temperature/reasoningEffort
     * 映射为 ModelPresetProperties。
     *
     * @param d agent 默认配置
     * @return 等价的 ModelPresetProperties
     */
    // 对标 Python Config.resolve_default_preset()
    public static ModelPresetProperties fromDefaults(AgentDefaultsProperties d) {
        return new ModelPresetProperties(
                null, d.getModel(), d.getProvider(), d.getMaxTokens(),
                d.getContextWindowTokens(), d.getTemperature(), d.getReasoningEffort());
    }

    /**
     * 将 preset 中的 temperature、maxTokens、reasoningEffort 提取为
     * {@link GenerationSettings}，供 LLM 调用时使用。
     *
     * @return 包含当前 preset 生成参数的 GenerationSettings
     */
    // 对标 Python ModelPresetConfig.to_generation_settings()
    public GenerationSettings toGenerationSettings() {
        return new GenerationSettings(temperature, maxTokens, reasoningEffort);
    }
}
