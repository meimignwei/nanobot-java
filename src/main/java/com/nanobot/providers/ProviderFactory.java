package com.nanobot.providers;

import com.nanobot.config.*;
import com.nanobot.providers.impl.FallbackProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 从配置创建 LLMProvider 实例的工厂，按 ProviderSpec.backend 字符串分派到具体构造函数。
 *
 * <p>对标 Python {@code nanobot/providers/factory.py}（~150 行）。
 * 使用反射创建具体 provider 实例，因为各 provider 实现在后续包中逐步添加。
 */
public class ProviderFactory {

    private static final Logger log = LoggerFactory.getLogger(ProviderFactory.class);

    private final Config config;

    /**
     * 构造工厂。
     *
     * @param config nanobot 运行时配置
     */
    public ProviderFactory(Config config) {
        this.config = config;
    }

    /**
     * 创建配置隐含的 LLM provider。当 model 给出时覆盖解析到的 preset 模型。
     * 若有 fallback_models 则包装为 FallbackProvider。
     *
     * @param presetName preset 名称（可为 null，使用默认 preset）
     * @param preset     显式 preset（可为 null，从 config 解析）
     * @param model      覆盖模型（可为 null）
     * @return 配置好的 LLMProvider
     */
    // 对标 Python factory.py make_provider()
    public LLMProvider makeProvider(String presetName, ModelPresetProperties preset, String model) {
        ModelPresetProperties resolved = resolveModelPreset(presetName, preset);
        List<ModelPresetProperties> fallbackPresets = resolveFallbackPresets(resolved);

        if (fallbackPresets.isEmpty()) {
            return makeProviderCore(presetName, preset, model);
        }

        LLMProvider primary = makeProviderCore(presetName, preset, model);
        return new FallbackProvider(primary, fallbackPresets,
                fb -> makeProviderCore(presetName, fb, fb.model()));
    }

    /**
     * 创建不含 failover 包装的纯 LLMProvider。
     *
     * @param presetName preset 名称
     * @param preset     显式 preset
     * @param model      覆盖模型
     * @return LLMProvider 实例
     */
    // 对标 Python factory.py _make_provider_core()
    protected LLMProvider makeProviderCore(String presetName, ModelPresetProperties preset, String model) {
        ModelPresetProperties resolved = resolveModelPreset(presetName, preset);
        String effectiveModel = (model != null) ? model : resolved.model();
        String providerName = config.getProviderName(effectiveModel, resolved);
        ProviderProperties providerProps = config.getProvider(effectiveModel, resolved);

        Optional<ProviderSpec> specOpt = ProviderRegistry.findByName(
                providerName != null ? providerName : "");
        ProviderSpec spec = specOpt.orElse(null);

        if (spec != null && spec.isTranscriptionOnly()) {
            throw new IllegalArgumentException(
                    "Provider '" + providerName + "' only supports transcription.");
        }

        String backend = (spec != null) ? spec.backend() : "openai_compat";

        // Azure OpenAI 需要 api_base
        if ("azure_openai".equals(backend)) {
            if (providerProps == null || providerProps.apiBase() == null) {
                throw new IllegalArgumentException("Azure OpenAI requires api_base in config.");
            }
        }

        // API key 校验
        if ("openai_compat".equals(backend) && !effectiveModel.startsWith("bedrock/")) {
            boolean needsKey = (providerProps == null || providerProps.apiKey() == null);
            boolean exempt = spec != null && (spec.isOauth() || spec.isLocal() || spec.isDirect());
            if (needsKey && !exempt) {
                throw new IllegalArgumentException(
                        "No API key configured for provider '" + providerName + "'.");
            }
        }

        LLMProvider provider = dispatchProvider(backend, effectiveModel, providerProps, resolved, spec, providerName);

        provider.setGeneration(resolved.toGenerationSettings());
        return provider;
    }

    /**
     * 构建 provider 快照：provider + model + context window + 配置指纹。
     *
     * @param presetName preset 名称
     * @param preset     显式 preset
     * @return ProviderSnapshot
     */
    // 对标 Python factory.py build_provider_snapshot()
    public ProviderSnapshot buildProviderSnapshot(String presetName, ModelPresetProperties preset) {
        ModelPresetProperties resolved = resolveModelPreset(presetName, preset);
        List<ModelPresetProperties> fallbackPresets = resolveFallbackPresets(resolved);

        int minContextWindow = resolved.contextWindowTokens();
        for (ModelPresetProperties fb : fallbackPresets) {
            minContextWindow = Math.min(minContextWindow, fb.contextWindowTokens());
        }

        LLMProvider provider = makeProvider(presetName, resolved, null);
        Object signature = computeProviderSignature(resolved, fallbackPresets);

        return new ProviderSnapshot(provider, resolved.model(), minContextWindow, signature);
    }

    // —— 私有辅助方法 ——

    /**
     * 解析 preset：优先使用传入的 preset，否则从 config 按名称解析。
     *
     * @param presetName preset 名称
     * @param preset     显式 preset
     * @return 解析后的 ModelPresetProperties
     */
    private ModelPresetProperties resolveModelPreset(String presetName, ModelPresetProperties preset) {
        if (preset != null) return preset;
        return config.resolvePreset(presetName);
    }

    /**
     * 解析 fallback preset 列表，支持字符串引用和内联 fallback 配置。
     *
     * @param primary 主 preset
     * @return fallback preset 列表
     */
    // 对标 Python factory.py _resolve_fallback_presets()
    private List<ModelPresetProperties> resolveFallbackPresets(ModelPresetProperties primary) {
        List<ModelPresetProperties> result = new ArrayList<>();
        List<Object> fallbackModels = config.getAgents().getDefaults().getFallbackModels();
        if (fallbackModels == null) return result;

        for (Object raw : fallbackModels) {
            if (raw instanceof String name) {
                ModelPresetProperties fb = config.getModelPresets().get(name);
                if (fb != null) result.add(fb);
            } else if (raw instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> inline = (Map<String, Object>) m;
                result.add(new ModelPresetProperties(
                        null,
                        (String) inline.getOrDefault("model", primary.model()),
                        (String) inline.getOrDefault("provider", null),
                        inline.containsKey("maxTokens") ? ((Number) inline.get("maxTokens")).intValue() : primary.maxTokens(),
                        inline.containsKey("contextWindowTokens") ? ((Number) inline.get("contextWindowTokens")).intValue() : primary.contextWindowTokens(),
                        inline.containsKey("temperature") ? ((Number) inline.get("temperature")).doubleValue() : primary.temperature(),
                        (String) inline.getOrDefault("reasoningEffort", primary.reasoningEffort())));
            }
        }
        return result;
    }

    /**
     * 按 backend 字符串分派到具体 provider 构造函数。使用反射创建实例，
     * 因为各 provider 实现在后续包（05-antic, 06-openai 等）中逐步添加。
     *
     * @param backend        后端标识
     * @param effectiveModel 有效模型名
     * @param providerProps  provider 配置
     * @param resolved       解析后的 preset
     * @param spec           provider 规范
     * @return LLMProvider 实例
     */
    // 对标 Python factory.py _make_provider_core() 中的 backend 分派
    private LLMProvider dispatchProvider(
            String backend,
            String effectiveModel,
            ProviderProperties providerProps,
            ModelPresetProperties resolved,
            ProviderSpec spec,
            String providerName) {
        return switch (backend) {
            case "openai_codex" -> createByReflection(
                    "com.nanobot.providers.impl.OpenAiCodexProvider",
                    new Class<?>[]{String.class},
                    new Object[]{effectiveModel});
            case "azure_openai" -> {
                String apiKey = (providerProps != null && providerProps.apiKey() != null)
                        ? providerProps.apiKey() : "";
                String apiBase = (providerProps != null) ? providerProps.apiBase() : "";
                yield createByReflection(
                        "com.nanobot.providers.impl.AzureOpenAiProvider",
                        new Class<?>[]{String.class, String.class, String.class},
                        new Object[]{apiKey, apiBase, effectiveModel});
            }
            case "github_copilot" -> createByReflection(
                    "com.nanobot.providers.impl.GitHubCopilotProvider",
                    new Class<?>[]{String.class},
                    new Object[]{effectiveModel});
            case "anthropic" -> {
                String apiKey = (providerProps != null) ? providerProps.apiKey() : null;
                String apiBase = config.getApiBase(effectiveModel, resolved);
                Map<String, String> extraHeaders = (providerProps != null)
                        ? providerProps.extraHeaders() : null;
                yield createByReflection(
                        "com.nanobot.providers.impl.AnthropicProvider",
                        new Class<?>[]{String.class, String.class, String.class, Map.class},
                        new Object[]{apiKey, apiBase, effectiveModel, extraHeaders});
            }
            case "bedrock" -> {
                // Bedrock 特有字段 region/profile 需从 BedrockProviderProperties 获取
                BedrockProviderProperties bedrockProps = (providerName != null)
                        ? config.getProviders().getBedrock() : null;
                String apiKey = (providerProps != null) ? providerProps.apiKey() : null;
                String apiBase = (providerProps != null) ? providerProps.apiBase() : null;
                String region = (bedrockProps != null) ? bedrockProps.region() : null;
                String profile = (bedrockProps != null) ? bedrockProps.profile() : null;
                Map<String, Object> extraBody = (providerProps != null)
                        ? providerProps.extraBody() : null;
                yield createByReflection(
                        "com.nanobot.providers.impl.BedrockProvider",
                        new Class<?>[]{String.class, String.class, String.class,
                                String.class, String.class, Map.class},
                        new Object[]{apiKey, apiBase, effectiveModel, region, profile, extraBody});
            }
            default -> {  // "openai_compat"
                String apiKey = (providerProps != null) ? providerProps.apiKey() : null;
                String apiBase = config.getApiBase(effectiveModel, resolved);
                Map<String, String> extraHeaders = (providerProps != null)
                        ? providerProps.extraHeaders() : null;
                Map<String, Object> extraBody = (providerProps != null)
                        ? providerProps.extraBody() : null;
                String apiType = (providerProps != null
                        && providerProps.apiType() != null
                        && "openai".equals(config.getProviderName(effectiveModel, resolved)))
                        ? providerProps.apiType() : "auto";
                Map<String, String> extraQuery = (providerProps != null)
                        ? providerProps.extraQuery() : null;
                yield createByReflection(
                        "com.nanobot.providers.impl.OpenAiCompatProvider",
                        new Class<?>[]{String.class, String.class, String.class, Map.class,
                                ProviderSpec.class, Map.class, String.class, Map.class},
                        new Object[]{apiKey, apiBase, effectiveModel, extraHeaders,
                                spec, extraBody, apiType, extraQuery});
            }
        };
    }

    /**
     * 通过反射创建 provider 实例。若类不存在则抛出明确错误。
     *
     * @param className  全限定类名
     * @param paramTypes 构造参数类型
     * @param args       构造参数值
     * @return LLMProvider 实例
     */
    private LLMProvider createByReflection(String className, Class<?>[] paramTypes, Object[] args) {
        try {
            Class<?> clz = Class.forName(className);
            return (LLMProvider) clz.getConstructor(paramTypes).newInstance(args);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(
                    "Provider implementation not found: " + className
                            + ". This provider will be implemented in a later package.", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create provider: " + className, e);
        }
    }

    /**
     * 计算 provider 配置指纹，用于热重载时检测配置变更。
     * 对标 Python factory.py provider_signature()（含 extra_headers/extra_body/api_type/region/profile/fallback）。
     *
     * @param resolved  主 preset
     * @param fallbacks fallback preset 列表
     * @return 配置指纹对象
     */
    // 对标 Python factory.py provider_signature()
    private Object computeProviderSignature(ModelPresetProperties resolved,
                                            List<ModelPresetProperties> fallbacks) {
        List<Object> parts = new ArrayList<>();
        String providerName = config.getProviderName(resolved.model(), resolved);
        ProviderProperties p = config.getProvider(resolved.model(), resolved);

        parts.add(resolved.model());
        parts.add(resolved.provider());
        parts.add(providerName);
        parts.add(config.getApiKey(resolved.model(), resolved));
        parts.add(config.getApiBase(resolved.model(), resolved));
        parts.add(p != null ? p.extraHeaders() : null);
        parts.add(p != null ? p.extraBody() : null);
        parts.add(p != null ? p.apiType() : "auto");
        parts.add(p != null ? p.extraQuery() : null);
        // Bedrock 特有字段
        BedrockProviderProperties bp = "bedrock".equals(providerName)
                ? config.getProviders().getBedrock() : null;
        parts.add(bp != null ? bp.region() : null);
        parts.add(bp != null ? bp.profile() : null);
        parts.add(resolved.maxTokens());
        parts.add(resolved.temperature());
        parts.add(resolved.reasoningEffort());
        parts.add(resolved.contextWindowTokens());
        // Fallback 签名
        for (ModelPresetProperties fb : fallbacks) {
            parts.add(computeFallbackSignature(fb));
        }
        return parts;
    }

    private Object computeFallbackSignature(ModelPresetProperties fb) {
        ProviderProperties fp = config.getProvider(fb.model(), fb);
        String fbProviderName = config.getProviderName(fb.model(), fb);
        BedrockProviderProperties bp = "bedrock".equals(fbProviderName)
                ? config.getProviders().getBedrock() : null;
        return List.of(
                fb.model(),
                fb.provider(),
                fbProviderName,
                config.getApiKey(fb.model(), fb),
                config.getApiBase(fb.model(), fb),
                fp != null ? fp.extraHeaders() : null,
                fp != null ? fp.extraBody() : null,
                fp != null ? fp.apiType() : "auto",
                fp != null ? fp.extraQuery() : null,
                bp != null ? bp.region() : null,
                bp != null ? bp.profile() : null,
                fb.maxTokens(),
                fb.temperature(),
                fb.reasoningEffort(),
                fb.contextWindowTokens());
    }
}
