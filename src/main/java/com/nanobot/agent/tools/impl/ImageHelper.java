package com.nanobot.agent.tools.impl;

import java.util.List;
import java.util.Map;

/**
 * 图像内容块构建工具，后续在 image_generation 包中完善。
 * 当前为占位实现。
 */
public final class ImageHelper {

    private ImageHelper() {}

    /**
     * 构建图像内容块列表，用于多模态返回。
     *
     * @param raw      图像字节
     * @param mime     MIME 类型
     * @param filePath 文件路径
     * @param fallback 回退文本
     * @return 内容块列表
     */
    public static List<Map<String, Object>> buildImageContentBlocks(
            byte[] raw, String mime, String filePath, String fallback) {
        // 占位：后续在 image_generation 工具中完善 base64 编码和 multi-modal 格式
        return List.of(Map.of("type", "text", "text", fallback));
    }
}
