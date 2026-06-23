package com.nanobot.config;

import com.nanobot.providers.ProviderRegistry;

import java.util.Set;

/**
 * LLM provider 规范描述（最小字段集，供 Config 中的 matchProvider 算法使用）。
 *
 * <p>对标 Python: {@code nanobot/providers/registry.py} 中的 provider 元数据。
 * 完整 ProviderSpec（30+ 字段）见 {@link com.nanobot.providers.ProviderSpec}。
 * REGISTRY 和 findByName 均委托到 providers 包的 {@link ProviderRegistry}，确保数据源唯一。
 */
public record ProviderSpec(
        String name,
        Set<String> keywords,
        String defaultApiBase,
        Boolean local,
        Boolean oauth,
        Boolean direct,
        Boolean transcriptionOnly,
        String detectByBaseKeyword) {

    public boolean isLocal() { return local != null && local; }
    public boolean isOauth() { return oauth != null && oauth; }
    public boolean isDirect() { return direct != null && direct; }
    public boolean isTranscriptionOnly() { return transcriptionOnly != null && transcriptionOnly; }
    public Set<String> keywords() { return keywords; }

    /**
     * 在 REGISTRY 中按名称查找 provider 规范定义，兼容 snake_case 和 camelCase 两种写法。
     * 委托到 {@link ProviderRegistry#findByName(String)}。
     *
     * @param name provider 名称（如 "azure_openai" 或 "azureOpenai"）
     * @return 匹配的 config.ProviderSpec，未找到返回 null
     */
    public static ProviderSpec findByName(String name) {
        return ProviderRegistry.findByName(name)
                .map(ProviderSpec::fromProvidersSpec)
                .orElse(null);
    }

    /**
     * 从 providers 包的完整 ProviderSpec 转换为 config 包的最小 ProviderSpec。
     *
     * @param spec providers 包的 ProviderSpec
     * @return config 包的 ProviderSpec
     */
    private static ProviderSpec fromProvidersSpec(com.nanobot.providers.ProviderSpec spec) {
        return new ProviderSpec(
                spec.name(),
                Set.copyOf(spec.keywords()),
                spec.defaultApiBase(),
                spec.isLocal(),
                spec.isOauth(),
                spec.isDirect(),
                spec.isTranscriptionOnly(),
                spec.detectByBaseKeyword());
    }

    /**
     * 从 providers 包的 PROVIDERS 列表构建的不可变 registry，
     * 委托到 {@link ProviderRegistry#PROVIDERS}。
     */
    public static final java.util.List<ProviderSpec> REGISTRY =
            ProviderRegistry.PROVIDERS.stream()
                    .map(ProviderSpec::fromProvidersSpec)
                    .toList();
}
