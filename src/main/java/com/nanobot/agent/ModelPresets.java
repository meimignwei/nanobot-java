package com.nanobot.agent;

import com.nanobot.config.GenerationSettings;
import com.nanobot.config.ModelPresetProperties;
import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.ProviderSnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 运行时模型预设选择辅助——将预设配置转换为 {@link ProviderSnapshot}。
 *
 * <p>对标 Python {@code nanobot/agent/model_presets.py}。
 */
public final class ModelPresets {

    private ModelPresets() {}

    /**
     * 预设快照加载器函数式接口。
     * 对标 Python PresetSnapshotLoader = Callable[[str], ProviderSnapshot]。
     */
    @FunctionalInterface
    public interface PresetSnapshotLoader {
        ProviderSnapshot load(String name);
    }

    /**
     * 默认选择签名——截取前 2 个元素用于 LRU 缓存键。
     * 对标 Python default_selection_signature(signature)。
     */
    public static List<Object> defaultSelectionSignature(List<Object> signature) {
        if (signature == null || signature.isEmpty()) return null;
        if (signature.size() <= 2) return signature;
        return signature.subList(0, 2);
    }

    /**
     * 从配置中提取模型预设 Map（含 "default" 预设）。
     * 对标 Python configured_model_presets(config)。
     */
    public static Map<String, ModelPresetProperties> configuredModelPresets(
            Config config) {
        Map<String, ModelPresetProperties> result = new LinkedHashMap<>();
        result.putAll(config.getModelPresets());
        result.put("default", config.resolveDefaultPreset());
        return result;
    }

    /**
     * 构造预设快照加载器。
     * 对标 Python make_preset_snapshot_loader(config, provider_snapshot_loader)。
     */
    public static PresetSnapshotLoader makePresetSnapshotLoader(
            Config config, Function<String, ProviderSnapshot> providerSnapshotLoader) {
        if (providerSnapshotLoader != null) {
            return providerSnapshotLoader::apply;
        }
        return name -> buildProviderSnapshot(config, name);
    }

    /**
     * 构建静态预设快照（修改 provider 的 generation settings）。
     * 对标 Python build_static_preset_snapshot(provider, name, preset)。
     */
    public static ProviderSnapshot buildStaticPresetSnapshot(
            LLMProvider provider, String name, ModelPresetProperties preset) {
        GenerationSettings gs = new GenerationSettings(
                preset.temperature(), preset.maxTokens(), preset.reasoningEffort());
        provider.setGeneration(gs);
        return new ProviderSnapshot(provider, preset.model(),
                preset.contextWindowTokens(),
                List.of("model_preset", name, preset.toString()));
    }

    /**
     * 构建运行时预设快照（通过加载器或静态构建）。
     * 对标 Python build_runtime_preset_snapshot(name, presets, provider, loader)。
     */
    public static ProviderSnapshot buildRuntimePresetSnapshot(
            String name, Map<String, ModelPresetProperties> presets,
            LLMProvider provider, PresetSnapshotLoader loader) {
        if (loader != null) return loader.load(name);
        return buildStaticPresetSnapshot(provider, name, presets.get(name));
    }

    /**
     * 规范化预设名称（校验存在性）。
     * 对标 Python normalize_preset_name(name, presets)。
     *
     * @throws IllegalArgumentException 名称为空或不存在时
     */
    public static String normalizePresetName(String name,
                                              Map<String, ModelPresetProperties> presets) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("model_preset must be a non-empty string");
        }
        name = name.trim();
        if (!presets.containsKey(name)) {
            throw new IllegalArgumentException("model_preset '" + name
                    + "' not found. Available: " + String.join(", ", presets.keySet()));
        }
        return name;
    }

    // ==================== 占位 ====================

    private static ProviderSnapshot buildProviderSnapshot(Config config, String presetName) {
        throw new UnsupportedOperationException(
                "buildProviderSnapshot not implemented — wire ProviderFactory");
    }

    /** 最小配置访问接口。 */
    public interface Config {
        Map<String, ModelPresetProperties> getModelPresets();
        ModelPresetProperties resolveDefaultPreset();
    }
}
