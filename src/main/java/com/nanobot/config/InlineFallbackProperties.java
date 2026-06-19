package com.nanobot.config;

import jakarta.annotation.Nullable;
import java.util.List;

/**
 * Mirrors Python InlineFallbackConfig.
 */
public record InlineFallbackProperties(
        String model,
        String provider,
        @Nullable Integer maxTokens,
        @Nullable Integer contextWindowTokens,
        @Nullable Double temperature,
        @Nullable String reasoningEffort
) {
    public InlineFallbackProperties {
        if (provider == null) provider = "auto";
    }
}
