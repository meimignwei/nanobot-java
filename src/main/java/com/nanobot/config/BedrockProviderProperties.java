package com.nanobot.config;

import java.util.Map;

/**
 * AWS Bedrock provider 配置。
 * 对标 Python: {@code nanobot/config/schema.py:189-193 class BedrockProviderConfig(ProviderConfig)}
 */
public record BedrockProviderProperties(
        String apiKey,
        String apiBase,
        String apiType,
        Map<String, String> extraHeaders,
        Map<String, Object> extraBody,
        Map<String, String> extraQuery,
        String region,
        String profile) {

    public BedrockProviderProperties {
        if (apiType == null) apiType = "auto";
        if (extraHeaders == null) extraHeaders = Map.of();
        if (extraBody == null) extraBody = Map.of();
        if (extraQuery == null) extraQuery = Map.of();
    }

    public static final BedrockProviderProperties DEFAULTS = new BedrockProviderProperties(
            null, null, "auto", null, null, null, null, null);

    /**
     * 将 Bedrock 特有配置降级为通用 {@link ProviderProperties}，
     * 丢弃 region/profile 等 Bedrock 专属字段，供 provider 匹配算法统一处理。
     *
     * @return 仅包含通用字段的 ProviderProperties
     */
    public ProviderProperties toGeneric() {
        return new ProviderProperties(apiKey, apiBase, apiType, extraHeaders, extraBody, extraQuery);
    }
}
