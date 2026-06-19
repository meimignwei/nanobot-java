package com.nanobot.config;

import jakarta.annotation.Nullable;
import java.util.Map;

/**
 * Mirrors Python ProviderConfig. Individual LLM provider API settings.
 */
public record ProviderConfig(
        @Nullable String apiKey,
        @Nullable String apiBase,
        String apiType,
        @Nullable Map<String, String> extraHeaders,
        @Nullable Map<String, Object> extraBody,
        @Nullable Map<String, String> extraQuery
) implements HasProviderConfig {
    public ProviderConfig {
        if (apiType == null) apiType = "auto";
    }

    public static ProviderConfig defaults() {
        return new ProviderConfig(null, null, "auto", null, null, null);
    }
}
