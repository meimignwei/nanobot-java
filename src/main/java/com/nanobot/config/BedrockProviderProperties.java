package com.nanobot.config;

import jakarta.annotation.Nullable;
import java.util.Map;

/**
 * Mirrors Python BedrockProviderConfig — extends ProviderConfig with region and profile.
 */
public record BedrockProviderProperties(
        @Nullable String apiKey,
        @Nullable String apiBase,
        String apiType,
        @Nullable Map<String, String> extraHeaders,
        @Nullable Map<String, Object> extraBody,
        @Nullable Map<String, String> extraQuery,
        @Nullable String region,
        @Nullable String profile
) implements HasProviderConfig {
    public BedrockProviderProperties {
        if (apiType == null) apiType = "auto";
    }

    public static BedrockProviderProperties defaults() {
        return new BedrockProviderProperties(null, null, "auto", null, null, null, null, null);
    }
}
