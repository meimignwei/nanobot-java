package com.nanobot.agent.tools.impl;

import com.nanobot.agent.tools.*;
import com.nanobot.agent.tools.schema.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * 文件读取工具，支持文本、PDF、Office 文档和图像文件。
 *
 * <p>对标 Python {@code nanobot/agent/tools/filesystem.py ReadFileTool}。
 * 功能：带行号的文本读取、offset/limit 分页、UTF-8 解码、
 * 二进制检测 + 图像 MIME 处理、读去重（通过 FileStates）、设备路径拦截。
 */
public class ReadFileTool extends FsTool {

    private static final Logger log = LoggerFactory.getLogger(ReadFileTool.class);

    /** 工具参数 JSON Schema。 */
    // 对标 Python ReadFileTool.parameters
    private static final Map<String, Object> PARAMETERS =
            ToolParametersSchema.create(
                    List.of("path"),
                    null,
                    Map.of(
                            "path", new StringSchema("The file path to read"),
                            "offset", new IntegerSchema(1,
                                    "Line number to start reading from (1-indexed, default 1)",
                                    1, null, null, false),
                            "limit", new IntegerSchema(2000,
                                    "Max lines to read (default 2000)",
                                    1, null, null, false),
                            "pages", new StringSchema(
                                    "Page range for PDF, e.g. '1-5'"),
                            "force", new BooleanSchema(
                                    "Bypass deduplication and force re-read",
                                    false, false)
                    )
            );

    private static final int MAX_CHARS = 128_000;
    private static final int DEFAULT_LIMIT = 2000;

    /** 被拦截的设备路径（对标 Python _BLOCKED_DEVICE_PATHS）。 */
    // 对标 Python ReadFileTool._BLOCKED_DEVICE_PATHS
    private static final Set<String> BLOCKED_DEVICE_PATHS = Set.of(
            "/dev/zero", "/dev/random", "/dev/urandom", "/dev/full",
            "/dev/stdin", "/dev/stdout", "/dev/stderr",
            "/dev/tty", "/dev/console",
            "/dev/fd/0", "/dev/fd/1", "/dev/fd/2"
    );

    // 对标 Python ReadFileTool._PROC_FD_PATTERN
    private static final Pattern PROC_FD_PATTERN =
            Pattern.compile("/proc/(?:self|\\d+)/fd/[012]$");

    /**
     * 构造 ReadFileTool。
     *
     * @param workspace                 工作空间根路径
     * @param allowedDir                允许操作的主目录
     * @param mediaDir                  media 目录
     * @param extraAllowedDirs          额外允许的目录列表
     * @param fileStates                FileStates 实例（可为 null）
     * @param restrictToWorkspace       是否限制在工作空间内
     * @param sandboxRestrictsWorkspace sandbox 是否额外限制
     */
    public ReadFileTool(Path workspace, Path allowedDir, Path mediaDir,
                        List<Path> extraAllowedDirs, FileStates fileStates,
                        boolean restrictToWorkspace,
                        boolean sandboxRestrictsWorkspace) {
        super(workspace, allowedDir, mediaDir, extraAllowedDirs,
                fileStates, restrictToWorkspace, sandboxRestrictsWorkspace);
    }

    @Override
    public String getName() { return "read_file"; }

    @Override
    public String getDescription() {
        return "Read a file from the workspace. "
                + "Supports text, PDF, Office docs, and images.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolParameters.deepCopy(PARAMETERS);
    }

    @Override
    public Set<String> getScopes() {
        return Set.of("core", "subagent", "memory");
    }

    /**
     * 读取文件，支持文本、图像和二进制检测。
     *
     * @param params 已校验的工具参数
     * @return 文件内容或错误消息的 CompletableFuture
     */
    @Override
    // 对标 Python ReadFileTool.execute()
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String path = paramString(params, "path");
                int offset = paramInt(params, "offset", 1);
                Integer limit = params.containsKey("limit")
                        ? paramInt(params, "limit", DEFAULT_LIMIT) : null;
                String pages = paramString(params, "pages");
                boolean force = Boolean.TRUE.equals(params.get("force"));

                if (path == null || path.isEmpty()) {
                    return "Error reading file: Unknown path";
                }

                // 1. 设备路径拦截
                if (isBlockedDevice(path)) {
                    return "Error: Reading " + path
                            + " is blocked (device path)";
                }

                // 2. 解析并验证路径
                Path fp = resolve(path);
                if (isBlockedDevice(fp.toString())) {
                    return "Error: Reading " + fp
                            + " is blocked (device path)";
                }
                if (!Files.exists(fp)) {
                    return "Error: File not found: " + path;
                }
                if (!Files.isRegularFile(fp)) {
                    return "Error: Not a file: " + path;
                }

                // 3. 读去重（force 模式跳过）
                if (!force) {
                    String dedupResult = checkDedup(fp, offset, limit, path);
                    if (dedupResult != null) return dedupResult;
                }

                // 4. 读取并检测 MIME 类型
                byte[] raw = Files.readAllBytes(fp);
                if (raw.length == 0) return "(Empty file: " + path + ")";

                String mime = detectMimeType(raw, path);
                if (mime != null && mime.startsWith("image/")) {
                    return ImageHelper.buildImageContentBlocks(
                            raw, mime, fp.toString(),
                            "(Image file: " + path + ")");
                }

                // 5. PDF 支持（占位）
                String fileName = fp.getFileName().toString().toLowerCase();
                if (fileName.endsWith(".pdf")) {
                    return "PDF support requires pdfbox dependency. "
                            + "File: " + path;
                }
                if (fileName.endsWith(".docx") || fileName.endsWith(".xlsx")
                        || fileName.endsWith(".pptx")) {
                    return "Office document support requires Apache POI "
                            + "dependency. File: " + path;
                }

                // 6. 文本读取
                return readAsText(raw, fp, offset, limit, path);

            } catch (SecurityException e) {
                return "Error: " + e.getMessage();
            } catch (Exception e) {
                log.error("ReadFileTool error", e);
                return "Error reading file: " + e.getMessage();
            }
        });
    }

    /**
     * 读去重检查：若文件未变且参数相同，跳过重复读取。
     *
     * @param fp     文件路径
     * @param offset 起始行
     * @param limit  读取行数限制
     * @param path   原始用户路径
     * @return 去重消息或 null（表示需要实际读取）
     */
    // 对标 Python ReadFileTool dedup 逻辑
    private String checkDedup(Path fp, int offset, Integer limit, String path) {
        FileStates.ReadState entry = fileStates().get(fp);
        if (entry != null && entry.canDedup()
                && entry.offset() == offset
                && Objects.equals(entry.limit(), limit)) {
            try {
                double currentMtime = Files.getLastModifiedTime(fp)
                        .toMillis() / 1000.0;
                if (currentMtime == entry.mtime()) {
                    String currentHash = FileStates.sha256Hex(fp);
                    if (currentHash != null
                            && currentHash.equals(entry.contentHash())) {
                        return "[File unchanged since last read: "
                                + path + "]";
                    }
                }
                // 文件已变更，继续完整读取（由 readAsText 记录新状态）
            } catch (IOException e) {
                // 回退到完整读取
            }
        }
        return null;
    }

    /**
     * 以文本方式读取文件内容，添加行号并处理 offset/limit 分页。
     *
     * @param raw    文件字节
     * @param fp     文件路径
     * @param offset 起始行
     * @param limit  读取行数限制
     * @param path   原始用户路径
     * @return 格式化后的文本内容
     */
    // 对标 Python ReadFileTool 文本读取逻辑
    private String readAsText(byte[] raw, Path fp, int offset,
                               Integer limit, String path) {
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            String content = decoder.decode(ByteBuffer.wrap(raw)).toString();
            content = content.replace("\r\n", "\n");

            List<String> allLines = List.of(content.split("\n", -1));
            int total = allLines.size();

            int start = Math.max(0, offset - 1);
            if (start >= total) {
                return "Error: offset " + offset
                        + " is beyond end of file (" + total + " lines)";
            }

            int actualLimit = (limit != null) ? limit : DEFAULT_LIMIT;
            int end = Math.min(start + actualLimit, total);

            StringBuilder result = new StringBuilder();
            for (int i = start; i < end; i++) {
                result.append(i + 1).append("| ")
                        .append(allLines.get(i)).append('\n');
            }

            // 截断过长输出
            if (result.length() > MAX_CHARS) {
                List<String> lines = result.toString().lines().toList();
                StringBuilder truncated = new StringBuilder();
                int charCount = 0;
                int truncatedEnd = start;
                for (String line : lines) {
                    if (charCount + line.length() + 1 > MAX_CHARS) break;
                    truncated.append(line).append('\n');
                    charCount += line.length() + 1;
                    truncatedEnd++;
                }
                result = truncated;
                end = truncatedEnd;
            }

            if (end < total) {
                result.append("\n(Showing lines ").append(offset).append("-")
                        .append(end).append(" of ").append(total)
                        .append(". Use offset=").append(end + 1)
                        .append(" to continue.)");
            } else {
                result.append("\n(End of file — ").append(total)
                        .append(" lines total)");
            }

            fileStates().recordRead(fp, offset, limit);
            return result.toString();

        } catch (CharacterCodingException e) {
            String mime = detectMimeType(raw, path);
            if (mime != null && mime.startsWith("image/")) {
                return ImageHelper.buildImageContentBlocks(raw, mime,
                        fp.toString(), "(Image file: " + path + ")")
                        .toString();
            }
            return "Error: Cannot read binary file " + path
                    + " (MIME: " + (mime != null ? mime : "unknown") + ")";
        }
    }

    /**
     * 检测给定字节的 MIME 类型。
     *
     * @param raw  文件字节
     * @param path 文件路径
     * @return MIME 类型字符串
     */
    // 对标 Python magic 库的 MIME 检测
    static String detectMimeType(byte[] raw, String path) {
        // 使用 JDK 内置的 probeContentType
        try {
            Path tmp = Files.createTempFile("nanobot-mime-", ".bin");
            Files.write(tmp, raw);
            String mime = Files.probeContentType(tmp);
            Files.deleteIfExists(tmp);
            if (mime != null && !mime.isEmpty()) return mime;
        } catch (IOException e) {
            // fallback to magic bytes
        }

        // Magic bytes 检测常见格式
        if (raw.length >= 4) {
            int hdr = ((raw[0] & 0xFF) << 24) | ((raw[1] & 0xFF) << 16)
                    | ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF);
            // PNG
            if (hdr == 0x89504E47) return "image/png";
            // JPEG
            if ((hdr & 0xFFFFFF00) == 0xFFD8FF00) return "image/jpeg";
            // GIF
            if (hdr == 0x47494638) return "image/gif";
            // PDF
            if (hdr == 0x25504446) return "application/pdf";
            // ZIP (DOCX/XLSX/PPTX)
            if ((hdr & 0xFFFF) == 0x504B) {
                String lower = path.toLowerCase();
                if (lower.endsWith(".docx"))
                    return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                if (lower.endsWith(".xlsx"))
                    return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                if (lower.endsWith(".pptx"))
                    return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
                return "application/zip";
            }
        }
        return null;
    }

    /**
     * 判断是否为被拦截的设备路径。
     *
     * @param path 路径字符串
     * @return 是设备路径返回 true
     */
    // 对标 Python ReadFileTool._is_blocked_device()
    static boolean isBlockedDevice(String path) {
        String raw = path.strip();
        if (BLOCKED_DEVICE_PATHS.contains(raw)) return true;
        if (PROC_FD_PATTERN.matcher(raw).find()) return true;
        try {
            String resolved = Path.of(raw).toRealPath().toString();
            if (BLOCKED_DEVICE_PATHS.contains(resolved)) return true;
            if (PROC_FD_PATTERN.matcher(resolved).find()) return true;
            if (resolved.startsWith("/dev/")) return true;
        } catch (IOException e) {
            // 路径不存在，不是设备
        }
        return false;
    }

    // ==================== 参数辅助方法 ====================

    private static String paramString(Map<String, Object> params, String key) {
        Object val = params.get(key);
        return val instanceof String s ? s : null;
    }

    private static int paramInt(Map<String, Object> params, String key,
                                 int def) {
        Object val = params.get(key);
        if (val instanceof Integer i) return i;
        if (val instanceof Number n) return n.intValue();
        return def;
    }
}
