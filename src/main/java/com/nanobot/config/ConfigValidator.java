package com.nanobot.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 配置校验器。对标 Python Pydantic 的 {@code @model_validator(mode="after")}。
 * 源码文件：nanobot/config/schema.py:343-369
 *
 * <p>Python 中校验在 Pydantic model 构造时自动运行；Java 中通过 Spring {@code @PostConstruct} 在 bean 初始化后执行。
 */
@Component
public class ConfigValidator {

    private final Config config;

    public ConfigValidator(Config config) {
        this.config = config;
    }

    /**
     * 在 Spring bean 初始化后执行配置完整性校验：
     * <ol>
     *   <li>"default" 不可作为显式 model_preset 名称（保留给 agents.defaults）</li>
     *   <li>agents.defaults.model_preset 引用的 preset 必须在 model_presets 中存在</li>
     *   <li>fallback_models 中每个字符串引用必须在 model_presets 中存在</li>
     *   <li>非 openai 的 provider 不可设置 api_type（仅 openai 支持）</li>
     * </ol>
     *
     * @throws IllegalStateException 任一校验失败时抛出
     */
    // 对标 Python schema.py:343-369 model_validator
    @PostConstruct
    public void validate() {
        // 对标 Python schema.py:345-346 — "default" 保留名
        if (config.getModelPresets().containsKey("default")) {
            throw new IllegalStateException("model_preset name 'default' is reserved for agents.defaults");
        }

        // 对标 Python schema.py:347-349 — 激活的 preset 必须存在
        String presetName = config.getAgents().getDefaults().getModelPreset();
        if (presetName != null && !"default".equals(presetName)
                && !config.getModelPresets().containsKey(presetName)) {
            throw new IllegalStateException("model_preset '" + presetName + "' not found in model_presets");
        }

        // 对标 Python schema.py:350-352 — fallback_models 引用必须存在
        for (Object fallback : config.getAgents().getDefaults().getFallbackModels()) {
            if (fallback instanceof String s && !config.getModelPresets().containsKey(s)) {
                throw new IllegalStateException("fallback_models entry '" + s + "' not found in model_presets");
            }
        }

        // 对标 Python schema.py:238-246 — api_type 校验
        // 只有 openai 允许 api_type != "auto"
        List<String> allProviderNames = ProviderSpec.REGISTRY.stream()
                .map(ProviderSpec::name).toList();
        for (String name : allProviderNames) {
            if ("openai".equals(name)) continue;
            ProviderProperties p = config.getProviders().getByName(name);
            if (p != null && p.apiType() != null && !"auto".equals(p.apiType())) {
                throw new IllegalStateException(
                        "providers." + name + ".api_type is only supported for providers.openai");
            }
        }
    }
}
