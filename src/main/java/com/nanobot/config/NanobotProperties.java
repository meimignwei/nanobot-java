package com.nanobot.config;

import com.nanobot.providers.ProviderRegistry;
import com.nanobot.providers.ProviderSpec;
import jakarta.annotation.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Root configuration — mirrors Python Config(BaseSettings).
 *
 * Env binding: Spring Boot relaxed binding converts NANOBOT_xxx to nanobot.* properties.
 * e.g. NANOBOT_AGENTS_DEFAULTS_MODEL → nanobot.agents.defaults.model
 */
@ConfigurationProperties(prefix = "nanobot")
@Validated
public record NanobotProperties(
        AgentProperties agents,
        ChannelsProperties channels,
        TranscriptionProperties transcription,
        ProvidersProperties providers,
        ApiProperties api,
        GatewayProperties gateway,
        ToolsProperties tools,
        Map<String, ModelPresetProperties> modelPresets
) {

    public NanobotProperties {
        if (agents == null) agents = new AgentProperties(null);
        if (channels == null) channels = new ChannelsProperties(null, null, null, null, null, null, null, null);
        if (transcription == null) transcription = new TranscriptionProperties(null, null, null, null, null, null);
        if (providers == null) providers = new ProvidersProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        if (api == null) api = new ApiProperties(null, null, null);
        if (gateway == null) gateway = new GatewayProperties(null, null, null);
        if (tools == null) tools = new ToolsProperties(null, null, null, null, null, null, null, null, null);
        if (modelPresets == null) modelPresets = Map.of();
    }

    // ---- Preset resolution (mirrors resolve_default_preset / resolve_preset) ----

    public ModelPresetProperties resolveDefaultPreset() {
        var d = agents.defaults();
        return new ModelPresetProperties(
                null, d.model(), d.provider(), d.maxTokens(),
                d.contextWindowTokens(), d.temperature(), d.reasoningEffort()
        );
    }

    public ModelPresetProperties resolvePreset(@Nullable String name) {
        if (name == null) name = agents.defaults().modelPreset();
        if (name == null || name.isEmpty() || "default".equals(name)) {
            return resolveDefaultPreset();
        }
        if (!modelPresets.containsKey(name)) {
            throw new IllegalArgumentException("model_preset '" + name + "' not found in model_presets");
        }
        return modelPresets.get(name);
    }

    // ---- Provider matching (mirrors _match_provider exactly) ----

    public ProviderMatch matchProvider(@Nullable String model, @Nullable ModelPresetProperties preset) {
        var resolved = preset != null ? preset : resolvePreset(null);
        String forced = resolved.provider();

        // 1. Explicit provider (not "auto")
        if (!"auto".equals(forced)) {
            var spec = ProviderRegistry.findByName(forced).orElse(null);
            if (spec != null) {
                var p = providers.getByName(spec.name());
                return p != null ? new ProviderMatch(p, spec.name()) : ProviderMatch.empty();
            }
            return ProviderMatch.empty();
        }

        String modelLower = (model != null ? model : resolved.model()).toLowerCase();
        String modelNormalized = modelLower.replace("-", "_");
        String modelPrefix = modelLower.contains("/") ? modelLower.substring(0, modelLower.indexOf('/')) : "";
        String normalizedPrefix = modelPrefix.replace("-", "_");

        // 2. Prefix match
        for (var spec : ProviderRegistry.PROVIDERS) {
            if (spec.isTranscriptionOnly()) continue;
            var p = providers.getByName(spec.name());
            if (p != null && !modelPrefix.isEmpty() && normalizedPrefix.equals(spec.name())) {
                if (spec.isOauth() || spec.isLocal() || spec.isDirect() || p.apiKey() != null) {
                    return new ProviderMatch(p, spec.name());
                }
            }
        }

        // 3. Keyword match
        for (var spec : ProviderRegistry.PROVIDERS) {
            if (spec.isTranscriptionOnly()) continue;
            var p = providers.getByName(spec.name());
            if (p != null && keywordMatches(spec, modelLower, modelNormalized)) {
                if (spec.isOauth() || spec.isLocal() || spec.isDirect() || p.apiKey() != null) {
                    return new ProviderMatch(p, spec.name());
                }
            }
        }

        // 4. Local fallback (prefer detect_by_base_keyword match)
        ProviderMatch localFallback = null;
        for (var spec : ProviderRegistry.PROVIDERS) {
            if (!spec.isLocal()) continue;
            var p = providers.getByName(spec.name());
            if (p == null || p.apiBase() == null) continue;
            if (!spec.detectByBaseKeyword().isEmpty() && p.apiBase().contains(spec.detectByBaseKeyword())) {
                return new ProviderMatch(p, spec.name());
            }
            if (localFallback == null) {
                localFallback = new ProviderMatch(p, spec.name());
            }
        }
        if (localFallback != null) return localFallback;

        // 5. Final fallback: gateways first (by PROVIDERS order), then any with api_key
        for (var spec : ProviderRegistry.PROVIDERS) {
            if (spec.isOauth() || spec.isTranscriptionOnly()) continue;
            var p = providers.getByName(spec.name());
            if (p != null && p.apiKey() != null) {
                return new ProviderMatch(p, spec.name());
            }
        }

        return ProviderMatch.empty();
    }

    private static boolean keywordMatches(ProviderSpec spec, String modelLower, String modelNormalized) {
        return spec.keywords().stream().anyMatch(kw -> {
            String kwLower = kw.toLowerCase();
            return modelLower.contains(kwLower)
                    || modelNormalized.contains(kwLower.replace("-", "_"));
        });
    }

    // ---- Convenience methods (mirrors get_provider, get_provider_name, get_api_key, get_api_base) ----

    @Nullable
    public HasProviderConfig getProvider(@Nullable String model, @Nullable ModelPresetProperties preset) {
        var match = matchProvider(model, preset);
        return match.config();
    }

    @Nullable
    public String getProviderName(@Nullable String model, @Nullable ModelPresetProperties preset) {
        return matchProvider(model, preset).specName();
    }

    @Nullable
    public String getApiKey(@Nullable String model, @Nullable ModelPresetProperties preset) {
        var p = getProvider(model, preset);
        return p != null ? p.apiKey() : null;
    }

    @Nullable
    public String getApiBase(@Nullable String model, @Nullable ModelPresetProperties preset) {
        var match = matchProvider(model, preset);
        if (match.config() != null && match.config().apiBase() != null) {
            return match.config().apiBase();
        }
        if (match.specName() != null) {
            var spec = ProviderRegistry.findByName(match.specName()).orElse(null);
            if (spec != null && !spec.defaultApiBase().isEmpty()) {
                return spec.defaultApiBase();
            }
        }
        return null;
    }

    // ---- workspace_path property (mirrors Python Config.workspace_path) ----

    public Path workspacePath() {
        return Path.of(agents.defaults().workspace().replaceFirst("^~", System.getProperty("user.home")));
    }

    // ---- Validation (mirrors _validate_model_preset + _validate_api_type_scope) ----

    public void validate() {
        if (modelPresets.containsKey("default")) {
            throw new IllegalStateException("model_preset name 'default' is reserved for agents.defaults");
        }
        String presetName = agents.defaults().modelPreset();
        if (presetName != null && !presetName.isEmpty() && !"default".equals(presetName)
                && !modelPresets.containsKey(presetName)) {
            throw new IllegalStateException(
                    "model_preset '" + presetName + "' not found in model_presets");
        }
        for (var fallback : agents.defaults().fallbackModels()) {
            if (fallback instanceof String name && !modelPresets.containsKey(name)) {
                throw new IllegalStateException(
                        "fallback_models entry '" + name + "' not found in model_presets");
            }
        }
        providers.validateApiTypeScope();
    }
}
