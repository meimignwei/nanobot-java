package com.nanobot.config;

import jakarta.annotation.Nullable;
import java.util.Map;

/**
 * Common interface for provider configuration. Both ProviderConfig and
 * BedrockProviderProperties implement this, mirroring Python's inheritance
 * where BedrockProviderConfig(ProviderConfig).
 */
public sealed interface HasProviderConfig permits ProviderConfig, BedrockProviderProperties {
    @Nullable String apiKey();
    @Nullable String apiBase();
    String apiType();
    @Nullable Map<String, String> extraHeaders();
    @Nullable Map<String, Object> extraBody();
    @Nullable Map<String, String> extraQuery();
}
