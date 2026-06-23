package com.nanobot.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 文档文本提取工具——将 media 列表中的图片与文档分离，
 * 提取纯文本文档内容追加到消息文本中。
 *
 * <p>对标 Python {@code nanobot/utils/document.py}（320 行）。
 */
public final class DocumentUtil {

    private static final Logger log = LoggerFactory.getLogger(DocumentUtil.class);

    private static final int MAX_TEXT_LENGTH = 200_000;
    private static final long MAX_EXTRACT_FILE_SIZE = 50 * 1024 * 1024; // 50 MB

    /** 对标 Python SUPPORTED_EXTENSIONS 中的纯文本类型。 */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".txt", ".md", ".csv", ".json", ".xml", ".html", ".htm",
            ".log", ".yaml", ".yml", ".toml", ".ini", ".cfg"
    );

    /** 对标 Python SUPPORTED_EXTENSIONS 中的图片类型。 */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".webp"
    );

    private DocumentUtil() {}

    // ---- 图片检测 ----

    /**
     * 通过魔法字节（文件头 16 字节）检测 + MIME 类型检测 + 扩展名回退
     * 判断文件是否为图片。对标 Python {@code is_image_file(path)}。
     *
     * @param path 文件路径
     * @return 确认为图片时返回 true
     */
    // 对标 Python document.py:234-250 is_image_file()
    public static boolean isImageFile(Path path) {
        // 对标 Python: magic-byte 检测（读取前 16 字节判断文件签名）
        if (Files.isRegularFile(path)) {
            try {
                byte[] header = new byte[16];
                try (var is = Files.newInputStream(path)) {
                    int read = is.read(header);
                    if (read > 0) {
                        String mime = detectImageMime(header);
                        if (mime != null && mime.startsWith("image/")) return true;
                    }
                }
            } catch (IOException ignored) {}
        }
        // 对标 Python: MIME 类型回退（mimetypes.guess_type）
        try {
            String mime = Files.probeContentType(path);
            if (mime != null && mime.startsWith("image/")) return true;
        } catch (IOException ignored) {}
        // 对标 Python: 扩展名回退
        String name = path.getFileName().toString().toLowerCase();
        for (String ext : IMAGE_EXTENSIONS) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    /**
     * 通过文件头字节检测图片 MIME 类型。对标 Python {@code detect_image_mime(header)}。
     *
     * @param header 文件前 16 字节
     * @return MIME 类型字符串，无法识别时返回 null
     */
    // 对标 Python helpers.py detect_image_mime()
    private static String detectImageMime(byte[] header) {
        if (header.length >= 4) {
            // PNG: 89 50 4E 47
            if (header[0] == (byte) 0x89 && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47)
                return "image/png";
            // JPEG: FF D8 FF
            if (header[0] == (byte) 0xFF && header[1] == (byte) 0xD8 && header[2] == (byte) 0xFF)
                return "image/jpeg";
            // GIF: 47 49 46 38
            if (header[0] == 0x47 && header[1] == 0x49 && header[2] == 0x46 && header[3] == 0x38)
                return "image/gif";
            // WebP: 52 49 46 46 ... 57 45 42 50
            if (header.length >= 12 && header[0] == 0x52 && header[1] == 0x49
                    && header[2] == 0x46 && header[3] == 0x46
                    && header[8] == 0x57 && header[9] == 0x45
                    && header[10] == 0x42 && header[11] == 0x50)
                return "image/webp";
            // BMP: 42 4D
            if (header[0] == 0x42 && header[1] == 0x4D)
                return "image/bmp";
        }
        return null;
    }

    /**
     * 通过路径字符串判断是否为图片。
     * 对标 Python {@code is_image_file(path)} 字符串版本。
     */
    public static boolean isImageFile(String path) {
        return isImageFile(Path.of(path));
    }

    // ---- 非图片附件引用（不读取文件内容） ----

    /**
     * 将非图片附件路径转为 {@code [Attachment: path]} 引用追加到 content。
     * 对标 Python {@code reference_non_image_attachments(content, media)}。
     *
     * @param content     原始消息文本
     * @param mediaPaths  附件路径列表
     * @return {@code [content, imagePaths]}，imagePaths 仅包含图片路径
     */
    // 对标 Python document.py:253-271 reference_non_image_attachments()
    public static ContentAndImages referenceNonImageAttachments(
            String content, List<String> mediaPaths) {
        List<String> imagePaths = new ArrayList<>();
        List<String> attachmentRefs = new ArrayList<>();
        for (String p : mediaPaths) {
            if (isImageFile(p)) {
                imagePaths.add(p);
            } else {
                attachmentRefs.add("[Attachment: " + p + "]");
            }
        }
        String result = content != null ? content : "";
        if (!attachmentRefs.isEmpty()) {
            String suffix = String.join("\n", attachmentRefs);
            result = result.isEmpty() ? suffix : result + "\n\n" + suffix;
        }
        return new ContentAndImages(result, imagePaths);
    }

    // ---- 文档文本提取 ----

    /**
     * 从 media 列表中分离图片和文档，提取文档文本追加到消息文本。
     * 对标 Python {@code extract_documents(text, media_paths)}。
     *
     * @param text       原始消息文本
     * @param mediaPaths 附件路径列表
     * @return {@code [content, imagePaths]}，content 含提取的文档文本，imagePaths 仅含图片
     */
    // 对标 Python document.py:274-319 extract_documents()
    public static ContentAndImages extractDocuments(String text, List<String> mediaPaths) {
        List<String> imagePaths = new ArrayList<>();
        List<String> docTexts = new ArrayList<>();

        for (String pathStr : mediaPaths) {
            Path p = Path.of(pathStr);
            if (!Files.isRegularFile(p)) continue;

            try {
                long size = Files.size(p);
                if (size > MAX_EXTRACT_FILE_SIZE) {
                    log.warn("Skipping oversized file for extraction: {} ({} MB > {} MB limit)",
                            p.getFileName(),
                            String.format("%.1f", size / (1024.0 * 1024.0)),
                            MAX_EXTRACT_FILE_SIZE / (1024 * 1024));
                    continue;
                }
            } catch (IOException e) {
                continue;
            }

            if (isImageFile(p)) {
                imagePaths.add(pathStr);
            } else {
                String extracted = extractText(p);
                if (extracted != null && !extracted.startsWith("[error:")) {
                    docTexts.add("[File: " + p.getFileName() + "]\n" + extracted);
                }
            }
        }

        String result = text != null ? text : "";
        if (!docTexts.isEmpty()) {
            result = result.isEmpty()
                    ? String.join("\n\n", docTexts)
                    : result + "\n\n" + String.join("\n\n", docTexts);
        }
        return new ContentAndImages(result, imagePaths);
    }

    /**
     * 从文件中提取文本。对标 Python {@code extract_text(path)}。
     *
     * @param path 文件路径
     * @return 提取的文本，不支持的类型返回 null
     */
    // 对标 Python document.py:42-78 extract_text()
    public static String extractText(Path path) {
        // 对标 Python line 55: 检查文件存在
        if (!Files.exists(path)) {
            return "[error: file not found: " + path + "]";
        }
        String name = path.getFileName().toString().toLowerCase();
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot >= 0) ext = name.substring(dot);

        if (TEXT_EXTENSIONS.contains(ext)) {
            return extractTextFile(path);
        }
        // 图片——返回占位符（对标 Python line 75）
        if (IMAGE_EXTENSIONS.contains(ext)) {
            return "[image: " + path.getFileName() + "]";
        }
        // 文档格式（PDF/DOCX/XLSX/PPTX）——Java 中无对应解析库，返回错误提示
        if (Set.of(".pdf", ".docx", ".xlsx", ".pptx").contains(ext)) {
            return "[error: " + ext.substring(1).toUpperCase()
                    + " parsing requires additional libraries (pypdf/python-docx/openpyxl/python-pptx)]";
        }
        return null;
    }

    /**
     * 从纯文本文件中读取内容。对标 Python {@code _extract_text_file(path)}。
     *
     * @param path 文件路径
     * @return 文件文本内容，读取失败返回错误描述
     */
    /**
     * 从纯文本文件中读取内容，UTF-8 优先，失败时回退到 latin-1。
     * 对标 Python {@code _extract_text_file(path)}（含 UTF-8 → latin-1 回退）。
     *
     * @param path 文件路径
     * @return 文件文本内容，读取失败返回错误描述
     */
    // 对标 Python document.py:187-198 _extract_text_file()
    private static String extractTextFile(Path path) {
        try {
            // 对标 Python: 先尝试 UTF-8，失败回退 latin-1
            try {
                String content = Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
                return truncate(content, MAX_TEXT_LENGTH);
            } catch (IOException utf8Error) {
                // 对标 Python: UnicodeDecodeError 时回退 latin-1
                String content = Files.readString(path, java.nio.charset.StandardCharsets.ISO_8859_1);
                return truncate(content, MAX_TEXT_LENGTH);
            }
        } catch (IOException e) {
            log.debug("Failed to read text file {}", path);
            return "[error: failed to read file: " + e.getMessage() + "]";
        }
    }

    /**
     * 截断文本并在末尾标注截断信息。
     * 对标 Python {@code _truncate(text, max_length)}。
     */
    // 对标 Python document.py:201-205 _truncate()
    static String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength)
                + "... (truncated, " + text.length() + " chars total)";
    }

    // ---- 结果封装 ----

    /**
     * 文档提取结果——包含增强后的文本内容和仅含图片的路径列表。
     * 对标 Python extract_documents / reference_non_image_attachments 的返回元组。
     */
    public record ContentAndImages(String content, List<String> images) {}
}
