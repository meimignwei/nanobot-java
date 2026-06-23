package com.nanobot.config;

/**
 * Provider 匹配结果。
 * 对标 Python: {@code nanobot/config/schema.py _match_provider() 的返回值 (ProviderConfig, spec_name)}
 */
public record ProviderMatchResult(
        ProviderProperties config,
        String specName) {
}
