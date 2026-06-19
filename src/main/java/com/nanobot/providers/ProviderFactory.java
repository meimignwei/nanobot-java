package com.nanobot.providers;

import com.nanobot.config.*;
import com.nanobot.providers.base.LLMProvider;
import jakarta.annotation.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Create LLM providers from config. Mirrors Python factory.py exactly.
 */
@Component
public class ProviderFactory {

    private final NanobotProperties config;

    public ProviderFactory(NanobotProperties config) {
        this.config = config;
    }

    /**
     * Create the LLM provider implied by config.
     * Mirrors Python make_provider().
     */
    public LLMProvider makeProvider(
            @Nullable String presetName,
            @Nullable ModelPresetProperties preset,
            @Nullable String model
    ) {
        ModelPresetProperties resolved = resolveModelPreset(presetName, preset);
        model = model != null ? model : resolved.model();
        LLMProvider provider = makeProviderCore(presetName, resolved, model);
        List<ModelPresetProperties> fallbackPresets = resolveFallbackPresets(resolved);

        if (!fallbackPresets.isEmpty()) {
            provider = new FallbackProvider(provider, fallbackPresets,
                    fb -> makeProviderCore(presetName, fb, fb.model()));
        }

        return provider;
    }

    // ---- internal ----

    private ModelPresetProperties resolveModelPreset(
            @Nullable String presetName,
            @Nullable ModelPresetProperties preset
    ) {
        return preset != null ? preset : config.resolvePreset(presetName);
    }

    LLMProvider makeProviderCore(
            @Nullable String presetName,
            ModelPresetProperties resolved,
            @Nullable String model
    ) {
        model = model != null ? model : resolved.model();
        String providerName = config.getProviderName(model, resolved);
        HasProviderConfig p = config.getProvider(model, resolved);
        ProviderSpec spec = ProviderRegistry.findByName(providerName).orElse(null);

        if (spec != null && spec.isTranscriptionOnly()) {
            throw new IllegalArgumentException("Provider '" + providerName + "' only supports transcription.");
        }

        String backend = spec != null ? spec.backend() : "openai_compat";

        // Validation
        if ("azure_openai".equals(backend)) {
            if (p == null || p.apiBase() == null) {
                throw new IllegalArgumentException("Azure OpenAI requires api_base in config.");
            }
        } else if ("openai_compat".equals(backend) && model != null && !model.startsWith("bedrock/")) {
            boolean needsKey = p == null || p.apiKey() == null;
            boolean exempt = spec != null && (spec.isOauth() || spec.isLocal() || spec.isDirect());
            if (needsKey && !exempt) {
                throw new IllegalArgumentException("No API key configured for provider '" + providerName + "'.");
            }
        }

        LLMProvider provider = switch (backend) {
            case "azure_openai" -> {
                assert p != null;
                yield new AzureOpenAiProvider(
                        p.apiKey() != null ? p.apiKey() : "",
                        p.apiBase(),
                        model
                );
            }
            case "anthropic" -> {
                yield new AnthropicProvider(
                        p != null ? p.apiKey() : null,
                        config.getApiBase(model, resolved),
                        model,
                        p != null ? p.extraHeaders() : null
                );
            }
            case "bedrock" -> {
                BedrockProviderProperties bp = p instanceof BedrockProviderProperties bpp ? bpp : null;
                yield new BedrockProvider(
                        p != null ? p.apiKey() : null,
                        p != null ? p.apiBase() : null,
                        model,
                        bp != null ? bp.region() : null,
                        bp != null ? bp.profile() : null,
                        p != null ? p.extraBody() : null
                );
            }
            case "openai_codex" -> new OpenAICodexProvider(
                    model,
                    null  // tokenSupplier: set programmatically or via env
            );
            default -> new OpenAiCompatProvider(
                    p != null ? p.apiKey() : null,
                    config.getApiBase(model, resolved),
                    model,
                    p != null ? p.extraHeaders() : null,
                    spec,
                    p != null ? p.extraBody() : null,
                    p != null && "openai".equals(providerName)
                            ? p.apiType() : "auto",
                    p != null ? p.extraQuery() : null
            );
        };

        provider.generation = new GenerationSettings(
                resolved.temperature(),
                resolved.maxTokens(),
                resolved.reasoningEffort()
        );

        return provider;
    }

    private List<ModelPresetProperties> resolveFallbackPresets(ModelPresetProperties primary) {
        List<ModelPresetProperties> presets = new ArrayList<>();
        for (Object fallback : config.agents().defaults().fallbackModels()) {
            if (fallback instanceof String s) {
                ModelPresetProperties p = config.modelPresets().get(s);
                if (p != null) presets.add(p);
            } else if (fallback instanceof InlineFallbackProperties inline) {
                presets.add(inlineFallbackPreset(primary, inline));
            }
        }
        return presets;
    }

    private ModelPresetProperties inlineFallbackPreset(
            ModelPresetProperties primary,
            InlineFallbackProperties fallback
    ) {
        return new ModelPresetProperties(
                null, // label — not used in fallback context
                fallback.model(),
                fallback.provider(),
                fallback.maxTokens() != null ? fallback.maxTokens() : primary.maxTokens(),
                fallback.contextWindowTokens() != null ? fallback.contextWindowTokens() : primary.contextWindowTokens(),
                fallback.temperature() != null ? fallback.temperature() : primary.temperature(),
                fallback.reasoningEffort()
        );
    }
}
