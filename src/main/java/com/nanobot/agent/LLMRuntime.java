package com.nanobot.agent;

import com.nanobot.providers.LLMProvider;

/**
 * 活跃的 LLM provider/model 对，用于运行时追踪。
 *
 * <p>对标 Python {@code nanobot/utils/llm_runtime.py} LLMRuntime 数据类。
 */
public class LLMRuntime {

    private final LLMProvider provider;
    private final String model;

    public LLMRuntime(LLMProvider provider, String model) {
        this.provider = provider;
        this.model = model;
    }

    /** 对标 Python LLMRuntime.provider。 */
    public LLMProvider provider() { return provider; }

    /** 对标 Python LLMRuntime.model。 */
    public String model() { return model; }

    @Override
    public String toString() {
        return "LLMRuntime{provider=" + provider.getClass().getSimpleName()
                + ", model=" + model + "}";
    }
}
