package com.nanobot.config;

/**
 * LLM 生成参数。
 * 对标 Python: {@code nanobot/providers/base.py:142-148 @dataclass(frozen=True) class GenerationSettings}
 */
public record GenerationSettings(
        double temperature,
        int maxTokens,
        String reasoningEffort) {

    public static final GenerationSettings DEFAULTS = new GenerationSettings(0.7, 4096, null);
}
