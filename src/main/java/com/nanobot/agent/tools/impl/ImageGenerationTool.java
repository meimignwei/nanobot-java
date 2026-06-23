package com.nanobot.agent.tools.impl;

import com.nanobot.agent.tools.Tool;
import com.nanobot.agent.tools.ToolParameters;
import com.nanobot.agent.tools.schema.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 图像生成工具，通过配置的 ImageGenerationProvider 生成持久化图像 artifact。
 *
 * <p>对标 Python {@code nanobot/agent/tools/image_generation.py ImageGenerationTool}（210 行）。
 * 支持 prompt、reference_images、aspect_ratio、image_size、count（最多 8 张），
 * 将生成的图像存储为工作空间中的持久化 artifact 并返回路径。
 *
 * <p>依赖 package 05 的 ImageGenerationProvider，当前为桩实现。
 */
public class ImageGenerationTool extends Tool {

    /** 工具参数 JSON Schema。 */
    // 对标 Python ImageGenerationTool.parameters
    private static final Map<String, Object> PARAMETERS =
            ToolParametersSchema.create(
                    List.of("prompt"),
                    null,
                    Map.of(
                            "prompt", new StringSchema(
                                    "Detailed image generation or edit prompt. "
                                            + "Include style, subject, composition, "
                                            + "colors, and constraints.",
                                    1, null, null, false),
                            "reference_images", new ArraySchema(
                                    new StringSchema(
                                            "Local path of an existing image artifact "
                                                    + "or user-provided image to use as "
                                                    + "an edit reference."),
                                    "Optional local image paths. Use generated artifact "
                                            + "paths for iterative edits.",
                                    null, null, true),
                            "aspect_ratio", new StringSchema(
                                    "Optional output aspect ratio, e.g. 1:1, 16:9, "
                                            + "9:16, 4:3.",
                                    null, null, null, true),
                            "image_size", new StringSchema(
                                    "Optional output size hint supported by the "
                                            + "configured provider, e.g. 1K, 2K, 4K, "
                                            + "or 1024x1024.",
                                    null, null, null, true),
                            "count", new IntegerSchema(1,
                                    "Number of images to generate in this turn.",
                                    1, 8, null, true)
                    )
            );

    public ImageGenerationTool() {}

    @Override
    public String getName() { return "generate_image"; }

    @Override
    public String getDescription() {
        return "Generate or edit images and store them as persistent artifacts. "
                + "Returns artifact ids and local paths. For edits, pass prior "
                + "generated image paths or user image paths as reference_images.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolParameters.deepCopy(PARAMETERS);
    }

    @Override
    public boolean isReadOnly() { return false; }

    @Override
    public Set<String> getScopes() { return Set.of("core"); }

    @Override
    public String getConfigKey() { return "image_generation"; }

    /**
     * 生成图像：委托给配置的 ImageGenerationProvider。
     *
     * @param params 已校验的工具参数
     * @return 生成结果的 CompletableFuture
     */
    @Override
    // 对标 Python ImageGenerationTool.execute()
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            String prompt = paramString(params, "prompt");
            if (prompt == null || prompt.isEmpty()) {
                return "Error: prompt parameter is required";
            }
            return "Image generation '" + prompt + "': "
                    + "[ImageGenerationProvider integration pending - package 05]";
        });
    }

    private static String paramString(Map<String, Object> params, String key) {
        Object val = params.get(key);
        return val instanceof String s ? s : null;
    }
}
