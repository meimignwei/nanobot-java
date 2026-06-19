package com.nanobot.agent.tools;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件写入工具。
 * 对应 Python WriteFileTool（filesystem.py 行 399-430）。
 *
 * <p>与 Python 的差异（后续阶段处理）：
 * - 无 file-state 追踪（需要 FileStates 基础设施）
 * - 简化工作区守卫</p>
 */
@Component
public class FileWriteTool extends Tool {

    @SuppressWarnings("unused")
    private static final Set<String> _scopes = Set.of("core", "subagent", "memory");

    private final String workspace;

    public FileWriteTool() {
        this(null);
    }

    public FileWriteTool(String workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() { return "write_file"; }

    @Override
    public String description() {
        return "Create a new file or intentionally replace an entire file with "
                + "the provided content. Overwrites existing files and creates parent "
                + "directories as needed. For code changes or partial edits, prefer "
                + "apply_patch; use edit_file only for small exact replacements.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Schema.toolParametersSchema(
                List.of("path", "content"),
                "File write parameters",
                Map.of(
                        "path", Map.of("type", "string", "description", "The file path to write to"),
                        "content", Map.of("type", "string", "description", "The content to write")
                )
        );
    }

    /** 执行文件写入。对应 Python WriteFileTool.execute()。 */
    @Override
    public Object execute(Map<String, Object> params, ToolContext ctx) throws Exception {
        String pathStr = (String) params.get("path");
        String content = (String) params.get("content");

        if (pathStr == null || pathStr.isBlank()) {
            return "Error writing file: Unknown path";
        }
        if (content == null) {
            return "Error writing file: Unknown content";
        }

        Path fp = resolvePath(pathStr, ctx);
        if (fp == null) {
            return "Error: File path is outside the workspace: " + pathStr;
        }

        // 禁止写入 device 路径
        if (FileReadTool.isBlockedDevice(fp)) {
            return "Error: Writing to " + fp + " is blocked (device path).";
        }

        try {
            Files.createDirectories(fp.getParent());
            Files.writeString(fp, content);
            return "Successfully wrote " + content.length() + " characters to " + fp;
        } catch (IOException e) {
            return "Error writing file: " + e.getMessage();
        }
    }

    // ---- 路径解析（与 FileReadTool 相同逻辑） ----

    /** 解析路径，确保在工作区内 */
    private Path resolvePath(String pathStr, ToolContext ctx) {
        Path p = Path.of(pathStr);
        Path ws = getWorkspace(ctx);

        if (p.isAbsolute()) {
            if (ws != null && p.normalize().startsWith(ws.normalize())) {
                return p.normalize();
            }
            if (ws != null) return null;
            return p.normalize();
        }

        Path resolved = (ws != null ? ws : Path.of(System.getProperty("user.dir")))
                .resolve(p).normalize();
        if (ws != null && !resolved.startsWith(ws.normalize())) {
            return null;
        }
        return resolved;
    }

    private Path getWorkspace(ToolContext ctx) {
        if (workspace != null) return Path.of(workspace);
        if (ctx != null && ctx.workspace() != null) return Path.of(ctx.workspace());
        return null;
    }
}
