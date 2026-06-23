package com.nanobot.config;

/**
 * 内联 fallback 模型配置（FallbackCandidate 的对象形式）。
 * 对标 Python: {@code nanobot/config/schema.py:87-95 class InlineFallbackConfig}
 */
public record InlineFallbackProperties(
        String model,
        String provider,
        Integer maxTokens,
        Integer contextWindowTokens,
        Double temperature,
        String reasoningEffort) {
}
