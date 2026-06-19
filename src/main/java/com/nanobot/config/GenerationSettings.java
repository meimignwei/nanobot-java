package com.nanobot.config;

import jakarta.annotation.Nullable;

/**
 * Mirrors Python GenerationSettings from nanobot.providers.base.
 * A value object for model generation parameters.
 */
public record GenerationSettings(
        double temperature,
        int maxTokens,
        @Nullable String reasoningEffort
) {}
