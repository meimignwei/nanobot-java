package com.nanobot.providers;

import com.nanobot.providers.base.LLMProvider;

/**
 * Provider + model + context-window 的不可变快照，用于运行时热切换。
 * 对应 Python ProviderSnapshot dataclass（providers/factory.py:14-19）。
 *
 * @param provider            LLM provider 实例
 * @param model               模型名称
 * @param contextWindowTokens 上下文窗口 token 数
 * @param signature           唯一签名，用于判断是否需要切换
 */
public record ProviderSnapshot(
        LLMProvider provider,
        String model,
        int contextWindowTokens,
        java.util.List<Object> signature
) {}
