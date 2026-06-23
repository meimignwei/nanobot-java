package com.nanobot.providers;

/**
 * 已构建 provider 链的不可变快照，包含 provider 实例、模型名、context window 和配置指纹。
 *
 * <p>对标 Python {@code nanobot/providers/factory.py ProviderSnapshot frozen dataclass}。
 */
public record ProviderSnapshot(
        LLMProvider provider,
        String model,
        int contextWindowTokens,
        Object signature) {
}
