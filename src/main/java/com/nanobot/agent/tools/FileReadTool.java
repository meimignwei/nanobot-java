package com.nanobot.agent.tools;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Read file contents with optional line-based pagination.
 * Port of Python ReadFileTool (filesystem.py lines 162-384).
 *
 * Differences from Python (to be addressed in later phases):
 * - No PDF support (requires pymupdf / PDFBox)
 * - No Office document support (.docx/.xlsx/.pptx — requires POI)
 * - No image content blocks (requires MIME detection + base64)
 * - No file-state deduplication (requires FileStates infrastructure)
 * - Simplified workspace guard (no _FsTool base class, no sandbox integration)
 */
@Component
public class FileReadTool extends Tool {

    @SuppressWarnings("unused")
    private static final Set<String> _scopes = Set.of("core", "subagent", "memory");

    private static final int MAX_CHARS = 128_000;
    private static final int DEFAULT_LIMIT = 2000;

    // Port of Python _BLOCKED_DEVICE_PATHS
    private static final Set<String> BLOCKED_DEVICE_PATHS = Set.of(
            "/dev/zero", "/dev/random", "/dev/urandom", "/dev/full",
            "/dev/stdin", "/dev/stdout", "/dev/stderr",
            "/dev/tty", "/dev/console",
            "/dev/fd/0", "/dev/fd/1", "/dev/fd/2"
    );

    private static final Pattern PROC_FD_PATTERN =
            Pattern.compile("/proc/\\d+/fd/[012]$|/proc/self/fd/[012]$");

    private final String workspace;

    public FileReadTool() {
        this(null);
    }

    public FileReadTool(String workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() { return "read_file"; }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public String description() {
        return "Read a file (text or image). "
                + "Text output format: LINE_NUM|CONTENT. "
                + "Images return visual content for analysis. "
                + "Supports PDF, DOCX, XLSX, PPTX documents. "
                + "Use find_files/list_dir first when the path is uncertain. "
                + "Read the relevant range before editing so replacements or patches "
                + "are based on current content. "
                + "Use offset and limit for large text files. "
                + "Use force=true to re-read content even if unchanged. "
                + "Reads exceeding ~128K chars are truncated.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Schema.toolParametersSchema(
                List.of("path"),
                "File read parameters",
                Map.of(
                        "path", Map.of("type", "string", "description", "The file path to read"),
                        "offset", Map.of("type", "integer", "description",
                                "Line number to start reading from (1-indexed, default 1)", "minimum", 1),
                        "limit", Map.of("type", "integer", "description",
                                "Maximum number of lines to read (default 2000)", "minimum", 1),
                        "pages", Map.of("type", "string", "description",
                                "Page range for PDF files, e.g. '1-5' (default: all, max 20 pages)"),
                        "force", Map.of("type", "boolean", "description",
                                "Bypass same-file read deduplication and return content again")
                )
        );
    }

    @Override
    public Object execute(Map<String, Object> params, ToolContext ctx) throws Exception {
        String pathStr = (String) params.get("path");
        int offset = params.get("offset") instanceof Number n ? n.intValue() : 1;
        int limit = params.get("limit") instanceof Number n ? n.intValue() : DEFAULT_LIMIT;
        String pages = (String) params.get("pages");
        boolean force = Boolean.TRUE.equals(params.get("force"));

        if (pathStr == null || pathStr.isBlank()) {
            return "Error reading file: Unknown path";
        }

        // Device path blacklist — check before workspace guard (Python order)
        Path rawPath = Path.of(pathStr);
        if (isBlockedDevice(rawPath)) {
            return "Error: Reading " + pathStr + " is blocked (device path that could hang or produce infinite output).";
        }

        // Resolve path with workspace guard
        Path fp = resolvePath(pathStr, ctx);
        if (fp == null) {
            return "Error: File path is outside the workspace: " + pathStr;
        }
        if (isBlockedDevice(fp)) {
            return "Error: Reading " + fp + " is blocked (device path that could hang or produce infinite output).";
        }

        if (!Files.exists(fp)) {
            return "Error: File not found: " + pathStr;
        }
        if (!Files.isRegularFile(fp)) {
            return "Error: Not a file: " + pathStr;
        }

        // PDF support (basic — delegates to external check)
        String fileName = fp.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".pdf")) {
            return "Error: PDF reading requires pymupdf. Install with: pip install pymupdf";
        }
        if (fileName.endsWith(".docx") || fileName.endsWith(".xlsx") || fileName.endsWith(".pptx")) {
            return "Error: Office document reading not yet supported in Java port";
        }

        byte[] raw;
        try {
            raw = Files.readAllBytes(fp);
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }

        if (raw.length == 0) {
            return "(Empty file: " + pathStr + ")";
        }

        // Image detection — basic MIME check
        String mime = probeMime(raw, fileName);
        if (mime != null && mime.startsWith("image/")) {
            return "(Image file: " + pathStr + ")";
        }

        // UTF-8 decode
        String textContent;
        try {
            textContent = new String(raw).replace("\r\n", "\n");
        } catch (Exception e) {
            return "Error: Cannot read binary file " + pathStr
                    + " (MIME: " + (mime != null ? mime : "unknown") + "). "
                    + "Only UTF-8 text and images are supported.";
        }

        String[] allLines = textContent.split("\n", -1);
        int total = allLines.length;

        if (offset < 1) offset = 1;
        if (offset > total) {
            return "Error: offset " + offset + " is beyond end of file (" + total + " lines)";
        }

        int start = offset - 1;
        int end = Math.min(start + limit, total);

        StringBuilder result = new StringBuilder();
        for (int i = start; i < end; i++) {
            result.append(i + 1).append("| ").append(allLines[i]).append("\n");
        }

        String output = result.toString();
        if (output.length() > MAX_CHARS) {
            int charCount = 0;
            int truncEnd = start;
            for (int i = start; i < end; i++) {
                int lineLen = String.valueOf(i + 1).length() + 2 + allLines[i].length() + 1;
                if (charCount + lineLen > MAX_CHARS) break;
                charCount += lineLen;
                truncEnd = i + 1;
            }
            StringBuilder truncated = new StringBuilder();
            for (int i = start; i < truncEnd; i++) {
                truncated.append(i + 1).append("| ").append(allLines[i]).append("\n");
            }
            output = truncated.toString();
            end = truncEnd;
        }

        if (end < total) {
            output += "\n(Showing lines " + offset + "-" + end + " of " + total
                    + ". Use offset=" + (end + 1) + " to continue.)";
        } else {
            output += "\n(End of file — " + total + " lines total)";
        }

        return output;
    }

    // ---- Path resolution ----

    private Path resolvePath(String pathStr, ToolContext ctx) {
        Path p = Path.of(pathStr);
        Path ws = getWorkspace(ctx);

        if (p.isAbsolute()) {
            // Check if within workspace
            if (ws != null && p.normalize().startsWith(ws.normalize())) {
                return p.normalize();
            }
            if (ws != null) return null; // outside workspace
            return p.normalize(); // no workspace restriction
        }

        // Relative path → resolve against workspace
        Path resolved = (ws != null ? ws : Path.of(System.getProperty("user.dir")))
                .resolve(p).normalize();
        if (ws != null && !resolved.startsWith(ws.normalize())) {
            return null; // path traversal
        }
        return resolved;
    }

    private Path getWorkspace(ToolContext ctx) {
        if (workspace != null) return Path.of(workspace);
        if (ctx != null && ctx.workspace() != null) return Path.of(ctx.workspace());
        return null;
    }

    // ---- Device path check ----

    static boolean isBlockedDevice(Path fp) {
        String raw = fp.toString();
        String resolved;
        try {
            resolved = fp.toRealPath().toString();
        } catch (Exception e) {
            resolved = raw;
        }
        if (BLOCKED_DEVICE_PATHS.contains(raw) || BLOCKED_DEVICE_PATHS.contains(resolved)) {
            return true;
        }
        if (PROC_FD_PATTERN.matcher(raw).matches() || PROC_FD_PATTERN.matcher(resolved).matches()) {
            return true;
        }
        return resolved.startsWith("/dev/");
    }

    // ---- Basic MIME detection ----

    private static String probeMime(byte[] data, String fileName) {
        if (data == null || data.length < 4) {
            return fileName != null ? guessByExtension(fileName) : null;
        }
        // Magic bytes
        if (data[0] == (byte) 0xFF && data[1] == (byte) 0xD8) return "image/jpeg";
        if (data[0] == (byte) 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') return "image/png";
        if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F') return "image/gif";
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F') return "image/webp";
        if (data[0] == (byte) 0x25 && data[1] == 'P' && data[2] == 'D' && data[3] == 'F') return "application/pdf";
        return fileName != null ? guessByExtension(fileName) : null;
    }

    private static String guessByExtension(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".pdf")) return "application/pdf";
        return null;
    }
}
