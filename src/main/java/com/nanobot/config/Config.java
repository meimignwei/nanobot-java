package com.nanobot.config;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * nanobot 运行时配置的根对象，包含 agents、providers、channels、tools 等所有配置节。
 *
 * <p><b>必须是可变 POJO</b>——Python 中 Config 是 Pydantic BaseModel，字段可在运行时修改
 * （如 SDK 调用时覆盖 workspace），Java 中不能使用不可变 record。
 *
 * <p>对标 Python {@code nanobot/config/schema.py:323-369+ class Config(BaseSettings)}。
 */
public class Config {

    // 对标 Python schema.py:326
    private AgentsProperties agents = new AgentsProperties();

    // 对标 Python schema.py:327
    private ChannelsProperties channels = new ChannelsProperties();

    // 对标 Python schema.py:328
    private TranscriptionProperties transcription = new TranscriptionProperties();

    // 对标 Python schema.py:329
    private ProvidersProperties providers = new ProvidersProperties();

    // 对标 Python schema.py:330
    private ApiProperties api = new ApiProperties();

    // 对标 Python schema.py:331
    private GatewayProperties gateway = new GatewayProperties();

    // 对标 Python schema.py:332
    private ToolsProperties tools = ToolsProperties.DEFAULTS;

    // 对标 Python schema.py:333-336
    @JsonAlias({"modelPresets", "model_presets"})
    private Map<String, ModelPresetProperties> modelPresets = new ConcurrentHashMap<>();

    // --- Getters/Setters ---

    public AgentsProperties getAgents() { return agents; }
    public void setAgents(AgentsProperties agents) { this.agents = agents; }

    public ChannelsProperties getChannels() { return channels; }
    public void setChannels(ChannelsProperties channels) { this.channels = channels; }

    public TranscriptionProperties getTranscription() { return transcription; }
    public void setTranscription(TranscriptionProperties transcription) { this.transcription = transcription; }

    public ProvidersProperties getProviders() { return providers; }
    public void setProviders(ProvidersProperties providers) { this.providers = providers; }

    public ApiProperties getApi() { return api; }
    public void setApi(ApiProperties api) { this.api = api; }

    public GatewayProperties getGateway() { return gateway; }
    public void setGateway(GatewayProperties gateway) { this.gateway = gateway; }

    public ToolsProperties getTools() { return tools; }
    public void setTools(ToolsProperties tools) { this.tools = tools; }

    public Map<String, ModelPresetProperties> getModelPresets() { return modelPresets; }
    public void setModelPresets(Map<String, ModelPresetProperties> modelPresets) { this.modelPresets = modelPresets; }

    // ── 对标 Python Config 运行时方法 ──────────────────────────────────────

    /**
     * 从 agents.defaults 的各字段构建隐式的 "default" preset，
     * 用于未显式设置 model_preset 时的回退。
     *
     * @return 从 AgentDefaults 字段构建的 ModelPresetProperties
     */
    // 对标 Python schema.py:355-362 resolve_default_preset()
    public ModelPresetProperties resolveDefaultPreset() {
        return ModelPresetProperties.fromDefaults(agents.getDefaults());
    }

    /**
     * 按名称解析模型 preset。优先级：name 参数 → agents.defaults.model_preset → 隐式 default。
     *
     * @param name preset 名称，"default" 或 null/空 返回隐式 default
     * @return 解析后的 ModelPresetProperties
     * @throws IllegalArgumentException 指定名称的 preset 不存在时抛出
     */
    // 对标 Python schema.py:364-369 resolve_preset()
    public ModelPresetProperties resolvePreset(String name) {
        String active = agents.getDefaults().getModelPreset();
        String resolvedName = (name == null) ? active : name;
        if (resolvedName == null || resolvedName.isEmpty() || "default".equals(resolvedName)) {
            return resolveDefaultPreset();
        }
        ModelPresetProperties preset = modelPresets.get(resolvedName);
        if (preset == null) {
            throw new IllegalArgumentException(
                    "model_preset '" + resolvedName + "' not found in model_presets");
        }
        return preset;
    }

    /**
     * 根据模型名称和 preset 匹配对应的 provider 配置，返回 (ProviderProperties, specName)。
     *
     * <p>四步匹配逻辑（与 Python 严格一致）：
     * <ol>
     *   <li>显式 provider prefix：model 中的 "xxx/" 前缀匹配 registry</li>
     *   <li>关键词匹配：model 名包含 provider 关键词（如 "claude" → anthropic）</li>
     *   <li>本地 provider base URL 匹配：已配置 apiBase 的本地 provider</li>
     *   <li>任意有 apiKey 的 provider</li>
     * </ol>
     *
     * @param model  模型标识符（如 "anthropic/claude-opus-4-5"），null 时使用 preset 中的 model
     * @param preset 模型 preset，null 时使用默认 preset
     * @return 匹配到的 (ProviderProperties, specName)，无匹配返回 null
     */
    // 对标 Python schema.py _match_provider()
    public ProviderMatchResult matchProvider(String model, ModelPresetProperties preset) {
        ModelPresetProperties resolved = preset != null ? preset : resolvePreset(null);
        String forced = resolved.provider();
        String resolvedModel = (model != null && !model.isEmpty()) ? model : resolved.model();

        if (!"auto".equals(forced)) {
            return matchByExplicitProvider(forced, resolvedModel);
        }

        String modelLower = resolvedModel.toLowerCase();
        String modelPrefix = modelLower.contains("/")
                ? modelLower.substring(0, modelLower.indexOf('/')) : "";
        String normalizedPrefix = modelPrefix.replace("-", "_");

        // Step 1: 显式 provider prefix 匹配
        ProviderMatchResult prefixMatch = matchByPrefix(normalizedPrefix, modelLower);
        if (prefixMatch != null) return prefixMatch;

        // Step 2: 关键词匹配
        ProviderMatchResult keywordMatch = matchByKeyword(modelLower);
        if (keywordMatch != null) return keywordMatch;

        // Step 3: 本地 provider base URL 匹配
        ProviderMatchResult localMatch = matchLocalByBaseUrl();
        if (localMatch != null) return localMatch;

        // Step 4: 有 key 的 provider（gateway 优先）
        ProviderMatchResult keyMatch = matchByAnyAvailableKey();
        if (keyMatch != null) return keyMatch;

        return null;
    }

    /**
     * 获取指定模型对应的 provider 配置。
     *
     * @param model  模型标识符
     * @param preset 模型 preset，null 时使用默认 preset
     * @return ProviderProperties，无匹配返回 null
     */
    /** 对标 Python schema.py get_provider() */
    public ProviderProperties getProvider(String model, ModelPresetProperties preset) {
        ProviderMatchResult r = matchProvider(model, preset);
        return r != null ? r.config() : null;
    }

    /**
     * 获取匹配到的 provider 的 registry 名称（如 "deepseek", "anthropic"）。
     *
     * @param model  模型标识符
     * @param preset 模型 preset，null 时使用默认 preset
     * @return provider registry 名称，无匹配返回 null
     */
    /** 对标 Python schema.py get_provider_name() */
    public String getProviderName(String model, ModelPresetProperties preset) {
        ProviderMatchResult r = matchProvider(model, preset);
        return r != null ? r.specName() : null;
    }

    /**
     * 获取匹配到的 provider 的 API 密钥。
     *
     * @param model  模型标识符
     * @param preset 模型 preset
     * @return API 密钥字符串，无匹配返回 null
     */
    /** 对标 Python schema.py get_api_key() */
    public String getApiKey(String model, ModelPresetProperties preset) {
        ProviderProperties p = getProvider(model, preset);
        return p != null ? p.apiKey() : null;
    }

    /**
     * 获取 API base URL，若 provider 未配置则 fallback 到 spec.defaultApiBase。
     *
     * @param model  模型标识符
     * @param preset 模型 preset
     * @return API base URL，无匹配返回 null
     */
    /** 对标 Python schema.py get_api_base() — fallback 到 spec.default_api_base */
    public String getApiBase(String model, ModelPresetProperties preset) {
        ProviderMatchResult r = matchProvider(model, preset);
        if (r == null) return null;
        ProviderProperties p = r.config();
        if (p != null && p.apiBase() != null) return p.apiBase();
        ProviderSpec spec = ProviderSpec.findByName(r.specName());
        return spec != null ? spec.defaultApiBase() : null;
    }

    /**
     * 解析并展开 workspace 路径中的 ~ 为用户主目录。
     *
     * @return 展开后的绝对 workspace 路径
     */
    /** 对标 Python Config.workspace_path (property) */
    public Path workspacePath() {
        String ws = agents.getDefaults().getWorkspace();
        return Path.of(ws.replaceFirst("^~", System.getProperty("user.home"))).toAbsolutePath();
    }

    // ── 内部匹配方法 ──────────────────────────────────────────────────────

    private ProviderMatchResult matchByExplicitProvider(String providerName, String model) {
        ProviderSpec spec = ProviderSpec.findByName(providerName);
        if (spec == null) return null;
        ProviderProperties p = providers.getByName(spec.name());
        return p != null ? new ProviderMatchResult(p, spec.name()) : null;
    }

    private ProviderMatchResult matchByPrefix(String normalizedPrefix, String modelLower) {
        for (ProviderSpec spec : ProviderSpec.REGISTRY) {
            if (spec.isTranscriptionOnly()) continue;
            ProviderProperties p = providers.getByName(spec.name());
            if (p == null) continue;
            if (!normalizedPrefix.isEmpty() && normalizedPrefix.equals(spec.name())) {
                if (spec.isOauth() || spec.isLocal() || spec.isDirect() || p.apiKey() != null) {
                    return new ProviderMatchResult(p, spec.name());
                }
            }
        }
        return null;
    }

    private ProviderMatchResult matchByKeyword(String modelLower) {
        String modelNormalized = modelLower.replace("-", "_");
        for (ProviderSpec spec : ProviderSpec.REGISTRY) {
            if (spec.isTranscriptionOnly()) continue;
            ProviderProperties p = providers.getByName(spec.name());
            if (p == null) continue;
            boolean matches = spec.keywords().stream().anyMatch(kw -> {
                String kl = kw.toLowerCase();
                return modelLower.contains(kl) || modelNormalized.contains(kl.replace("-", "_"));
            });
            if (matches && (spec.isOauth() || spec.isLocal() || spec.isDirect() || p.apiKey() != null)) {
                return new ProviderMatchResult(p, spec.name());
            }
        }
        return null;
    }

    private ProviderMatchResult matchLocalByBaseUrl() {
        ProviderMatchResult fallback = null;
        for (ProviderSpec spec : ProviderSpec.REGISTRY) {
            if (!spec.isLocal()) continue;
            ProviderProperties p = providers.getByName(spec.name());
            if (p == null || p.apiBase() == null) continue;
            if (spec.detectByBaseKeyword() != null
                    && p.apiBase().contains(spec.detectByBaseKeyword())) {
                return new ProviderMatchResult(p, spec.name());
            }
            if (fallback == null) fallback = new ProviderMatchResult(p, spec.name());
        }
        return fallback;
    }

    private ProviderMatchResult matchByAnyAvailableKey() {
        for (ProviderSpec spec : ProviderSpec.REGISTRY) {
            if (spec.isOauth() || spec.isTranscriptionOnly()) continue;
            ProviderProperties p = providers.getByName(spec.name());
            if (p != null && p.apiKey() != null) {
                return new ProviderMatchResult(p, spec.name());
            }
        }
        return null;
    }
}
