package com.nanobot.agent.tools.impl;

import com.nanobot.agent.tools.*;
import com.nanobot.agent.tools.schema.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 文件写入工具，简单覆盖写入并自动创建父目录。
 *
 * <p>对标 Python {@code nanobot/agent/tools/filesystem.py WriteFileTool}（32 行）。
 */
public class WriteFileTool extends FsTool {

    /** 工具参数 JSON Schema。 */
    // 对标 Python WriteFileTool.parameters
    private static final Map<String, Object> PARAMETERS =
            ToolParametersSchema.create(
                    List.of("path", "content"),
                    null,
                    Map.of(
                            "path", new StringSchema("The file path to write"),
                            "content", new StringSchema(
                                    "The content to write to the file")
                    )
            );

    /**
     * 构造 WriteFileTool。
     */
    public WriteFileTool(Path workspace, Path allowedDir, Path mediaDir,
                         List<Path> extraAllowedDirs, FileStates fileStates,
                         boolean restrictToWorkspace,
                         boolean sandboxRestrictsWorkspace) {
        super(workspace, allowedDir, mediaDir, extraAllowedDirs,
                fileStates, restrictToWorkspace, sandboxRestrictsWorkspace);
    }

    @Override
    public String getName() { return "write_file"; }

    @Override
    public String getDescription() {
        return "Write content to a file in the workspace. "
                + "Creates parent directories if needed.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolParameters.deepCopy(PARAMETERS);
    }

    @Override
    public boolean isReadOnly() { return false; }

    @Override
    public Set<String> getScopes() {
        return Set.of("core", "subagent", "memory");
    }

    /**
     * 将内容写入文件，自动创建父目录，更新 FileStates。
     *
     * @param params 已校验的工具参数
     * @return 确认消息的 CompletableFuture
     */
    @Override
    // 对标 Python WriteFileTool.execute()
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String path = paramString(params, "path");
                String content = paramString(params, "content");
                if (path == null || path.isEmpty()) {
                    return "Error: path is required";
                }
                if (content == null) content = "";

                Path fp = resolve(path);

                // 自动创建父目录
                Path parent = fp.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                // 规范化换行
                String normalized = content.replace("\r\n", "\n");
                Files.writeString(fp, normalized);

                // 记录写入状态
                fileStates().recordWrite(fp);

                long size = Files.size(fp);
                return "File written: " + path + " (" + size + " bytes)";
            } catch (SecurityException e) {
                return "Error: " + e.getMessage();
            } catch (IOException e) {
                return "Error writing file: " + e.getMessage();
            }
        });
    }

    private static String paramString(Map<String, Object> params, String key) {
        Object val = params.get(key);
        return val instanceof String s ? s : null;
    }
}
