package com.nanobot.config;

import jakarta.annotation.Nullable;

/**
 * Mirrors Python ModelPresetConfig. A named set of model + generation parameters.
 */
public record ModelPresetProperties(
        @Nullable String label,
        String model,
        String provider,
        Integer maxTokens,
        Integer contextWindowTokens,
        Double temperature,
        @Nullable String reasoningEffort
) {
    public ModelPresetProperties {
        if (provider == null) provider = "auto";
        if (maxTokens == null) maxTokens = 8192;
        if (contextWindowTokens == null) contextWindowTokens = 65_536;
        if (temperature == null) temperature = 0.1;
    }

    /** Mirrors Python to_generation_settings(). */
    public GenerationSettings toGenerationSettings() {
        return new GenerationSettings(temperature, maxTokens, reasoningEffort);
    }
}
