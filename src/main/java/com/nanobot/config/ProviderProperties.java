package com.nanobot.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.Map;

/**
 * 单个 LLM provider 配置。
 * 对标 Python: {@code nanobot/config/schema.py:178-186 class ProviderConfig}
 */
public record ProviderProperties(
        String apiKey,
        String apiBase,
        String apiType,
        Map<String, String> extraHeaders,
        Map<String, Object> extraBody,
        Map<String, String> extraQuery) {

    public ProviderProperties {
        if (apiType == null) apiType = "auto";
        if (extraHeaders == null) extraHeaders = Map.of();
        if (extraBody == null) extraBody = Map.of();
        if (extraQuery == null) extraQuery = Map.of();
    }

    public static final ProviderProperties DEFAULTS = new ProviderProperties(
            null, null, "auto", null, null, null);
}
