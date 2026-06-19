package com.nanobot.config;

import com.nanobot.providers.ProviderSpec;
import jakarta.annotation.Nullable;

/**
 * Mirrors Python _match_provider return type: (ProviderConfig, spec_name).
 */
public record ProviderMatch(@Nullable HasProviderConfig config, @Nullable String specName) {

    public static ProviderMatch empty() {
        return new ProviderMatch(null, null);
    }
}
