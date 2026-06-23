package com.nanobot.providers.image_generation;

import com.nanobot.config.Config;

import java.util.List;

/**
 * 图像生成 provider。对标 Python: {@code nanobot/providers/image_generation.py}
 *
 * <p>完整实现见 05-providers-impl.md。此处为 root 包编译所需的最小 stub。
 */
public final class ImageGenerationProvider {

    private ImageGenerationProvider() {}

    /** 对标 Python image_generation.py 中的 image_gen_provider_configs() — 同步 */
    public static List<Object> providerConfigs(Config config) {
        return List.of();
    }
}
