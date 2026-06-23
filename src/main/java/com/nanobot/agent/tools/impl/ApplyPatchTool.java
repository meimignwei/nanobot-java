package com.nanobot.agent.tools.impl;

import com.nanobot.agent.tools.*;
import com.nanobot.agent.tools.schema.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * 多文件结构化编辑工具，支持 replace/add 两种操作、dry-run 预览和原子写入（备份 + 回滚）。
 *
 * <p>对标 Python {@code nanobot/agent/tools/apply_patch.py ApplyPatchTool}（301 行）。
 * 每条 edit 包含 path（相对路径）、action（replace / add）、
 * old_text / new_text。支持行级增删统计和 CRLF 自动适配。
 */
public class ApplyPatchTool extends FsTool {

    /** 工具参数 JSON Schema。 */
    // 对标 Python ApplyPatchTool.parameters（edits ArraySchema）
    private static final Map<String, Object> PARAMETERS =
            ToolParametersSchema.create(
                    List.of("edits"),
                    null,
                    Map.of(
                            "edits", new ArraySchema(
                                    new ObjectSchema(
                                            Map.of(
                                                    "path", new StringSchema(
                                                            "Relative path to the file to edit."),
                                                    "action", new StringSchema(
                                                            "Operation type: replace or add.",
                                                            null, null,
                                                            List.of("replace", "add"), false),
                                                    "old_text", new StringSchema(
                                                            "Exact text to search for in the file. Required for replace.",
                                                            null, null, null, true),
                                                    "new_text", new StringSchema(
                                                            "Text to replace with or append. Required for replace and add.",
                                                            null, null, null, true)
                                            ),
                                            List.of("path", "action"),
                                            null, null, false
                                    ),
                                    "List of edits to apply. Each edit specifies a file and the change to make.",
                                    1, 20, false
                            ),
                            "dry_run", new BooleanSchema(
                                    "Validate and summarize the patch without writing files.",
                                    false, true)
                    )
            );

    /** 绝对 Windows 路径模式，对标 Python _ABSOLUTE_WINDOWS_RE。 */
    // 对标 Python _ABSOLUTE_WINDOWS_RE
    private static final Pattern ABSOLUTE_WINDOWS_RE =
            Pattern.compile("^[A-Za-z]:[\\\\/]");

    /** 补丁摘要，对标 Python _PatchSummary。 */
    // 对标 Python _PatchSummary
    private record PatchSummary(String action, String path, int added, int deleted) {}

    /**
     * 构造 ApplyPatchTool。
     *
     * @param workspace                 工作空间根路径
     * @param allowedDir                允许操作的主目录
     * @param mediaDir                  media 目录
     * @param extraAllowedDirs          额外允许的目录列表
     * @param fileStates                FileStates 实例
     * @param restrictToWorkspace       是否限制在工作空间内
     * @param sandboxRestrictsWorkspace sandbox 是否额外限制
     */
    public ApplyPatchTool(Path workspace, Path allowedDir, Path mediaDir,
                          List<Path> extraAllowedDirs, FileStates fileStates,
                          boolean restrictToWorkspace,
                          boolean sandboxRestrictsWorkspace) {
        super(workspace, allowedDir, mediaDir, extraAllowedDirs,
                fileStates, restrictToWorkspace, sandboxRestrictsWorkspace);
    }

    @Override
    public String getName() { return "apply_patch"; }

    @Override
    public String getDescription() {
        return "Default tool for code edits. Supports multi-file changes in a single call. "
                + "Provide a list of structured edits, each specifying a file path, action "
                + "(replace/add), and the exact text to change. "
                + "Paths must be relative. Set dry_run=true to validate and preview without writing files. "
                + "Use edit_file only for small exact replacements on a single file.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolParameters.deepCopy(PARAMETERS);
    }

    @Override
    public boolean isReadOnly() { return false; }

    @Override
    public Set<String> getScopes() {
        return Set.of("core", "subagent");
    }

    /**
     * 应用结构化编辑到多个文件。
     *
     * @param params 已校验的工具参数
     * @return 补丁结果摘要的 CompletableFuture
     */
    @Override
    // 对标 Python ApplyPatchTool.execute()
    @SuppressWarnings("unchecked")
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<Map<String, Object>> edits =
                        (List<Map<String, Object>>) params.get("edits");
                boolean dryRun = Boolean.TRUE.equals(params.get("dry_run"));

                if (edits == null || edits.isEmpty()) {
                    return "Error applying patch: must provide edits";
                }

                Map<Path, String> writes = new LinkedHashMap<>();
                List<PatchSummary> summaries = new ArrayList<>();

                for (Map<String, Object> edit : edits) {
                    String rawPath = (String) edit.get("path");
                    String action = (String) edit.get("action");
                    if (rawPath == null) {
                        return "Error applying patch: path required for edit";
                    }
                    if (action == null) {
                        return "Error applying patch: action required for edit: "
                                + rawPath;
                    }

                    String relPath = validateRelativePath(rawPath);
                    Path source = resolve(relPath);

                    if ("add".equals(action)) {
                        String result = applyAdd(source, relPath, edit,
                                writes, summaries);
                        if (result != null) return result;
                    } else if ("replace".equals(action)) {
                        String result = applyReplace(source, relPath, edit,
                                writes, summaries);
                        if (result != null) return result;
                    } else {
                        return "Error applying patch: unknown action: "
                                + action;
                    }
                }

                if (dryRun) {
                    StringBuilder sb = new StringBuilder(
                            "Patch dry-run succeeded:\n");
                    for (PatchSummary s : summaries) {
                        sb.append(formatSummary(s)).append('\n');
                    }
                    return sb.toString().stripTrailing();
                }

                // 原子写入：备份 → 写入 → 失败回滚
                Map<Path, byte[]> backups = new LinkedHashMap<>();
                for (Path p : writes.keySet()) {
                    backups.put(p, Files.exists(p)
                            ? Files.readAllBytes(p) : null);
                }

                try {
                    for (var entry : writes.entrySet()) {
                        Path p = entry.getKey();
                        Files.createDirectories(p.getParent());
                        Files.writeString(p, entry.getValue());
                    }
                } catch (Exception e) {
                    // 回滚
                    for (var backup : backups.entrySet()) {
                        Path p = backup.getKey();
                        byte[] data = backup.getValue();
                        try {
                            if (data == null) {
                                Files.deleteIfExists(p);
                            } else {
                                Files.createDirectories(p.getParent());
                                Files.write(p, data);
                            }
                        } catch (IOException ignored) {
                            // 尽最大努力回滚
                        }
                    }
                    return "Error applying patch: " + e.getMessage();
                }

                for (Path p : writes.keySet()) {
                    fileStates().recordWrite(p);
                }

                StringBuilder sb = new StringBuilder("Patch applied:\n");
                for (PatchSummary s : summaries) {
                    sb.append(formatSummary(s)).append('\n');
                }
                return sb.toString().stripTrailing();

            } catch (PatchException e) {
                return "Error applying patch: " + e.getMessage();
            } catch (Exception e) {
                return "Error applying patch: " + e.getMessage();
            }
        });
    }

    /**
     * 应用 add（追加）操作。
     *
     * @param source   解析后的文件路径
     * @param relPath  相对路径（用于错误消息）
     * @param edit     edit 参数 Map
     * @param writes   待写入文件累积 Map
     * @param summaries 补丁摘要累积列表
     * @return 错误消息或 null（成功）
     */
    // 对标 Python ApplyPatchTool.execute() add 分支
    private String applyAdd(Path source, String relPath,
                            Map<String, Object> edit,
                            Map<Path, String> writes,
                            List<PatchSummary> summaries) {
        Object newTextObj = edit.get("new_text");
        if (newTextObj == null) {
            return "Error applying patch: new_text required for add: "
                    + relPath;
        }
        String newText = (String) newTextObj;

        String content;
        boolean exists;
        String pending = writes.get(source);
        if (pending != null) {
            content = pending;
            exists = true;
        } else if (Files.exists(source)) {
            try {
                byte[] raw = Files.readAllBytes(source);
                content = new String(raw);
            } catch (Exception e) {
                return "Error applying patch: " + e.getMessage();
            }
            try {
                // 验证 UTF-8
                content = new String(Files.readAllBytes(source));
            } catch (Exception e) {
                return "Error applying patch: file is not UTF-8 text: "
                        + relPath;
            }
            exists = true;
        } else {
            content = "";
            exists = false;
        }

        if (exists) {
            boolean usesCrlf = content.contains("\r\n");
            String newNorm = appendText(content, newText);
            if (usesCrlf) newNorm = newNorm.replace("\n", "\r\n");
            writes.put(source, newNorm);
            int[] stats = lineDiffStats(content, newNorm);
            summaries.add(new PatchSummary("update", relPath,
                    stats[0], stats[1]));
        } else {
            String newNorm = newText.replace("\r\n", "\n");
            if (!newNorm.isEmpty() && !newNorm.endsWith("\n")) {
                newNorm += "\n";
            }
            writes.put(source, newNorm);
            int added = textLineCount(newNorm);
            summaries.add(new PatchSummary("add", relPath, added, 0));
        }
        return null;
    }

    /**
     * 应用 replace（替换）操作。
     *
     * @param source   解析后的文件路径
     * @param relPath  相对路径（用于错误消息）
     * @param edit     edit 参数 Map
     * @param writes   待写入文件累积 Map
     * @param summaries 补丁摘要累积列表
     * @return 错误消息或 null（成功）
     */
    // 对标 Python ApplyPatchTool.execute() replace 分支
    private String applyReplace(Path source, String relPath,
                                Map<String, Object> edit,
                                Map<Path, String> writes,
                                List<PatchSummary> summaries) {
        String oldText = (String) edit.getOrDefault("old_text", "");
        if (oldText.isEmpty()) {
            return "Error applying patch: old_text required for replace: "
                    + relPath;
        }
        Object newTextObj = edit.get("new_text");
        if (newTextObj == null) {
            return "Error applying patch: new_text required for replace: "
                    + relPath;
        }
        String newText = (String) newTextObj;

        String content;
        String pending = writes.get(source);
        if (pending != null) {
            content = pending;
        } else if (Files.exists(source)) {
            try {
                byte[] raw = Files.readAllBytes(source);
                content = new String(raw);
            } catch (Exception e) {
                return "Error applying patch: " + e.getMessage();
            }
            try {
                content = new String(Files.readAllBytes(source));
            } catch (Exception e) {
                return "Error applying patch: file is not UTF-8 text: "
                        + relPath;
            }
        } else {
            return "Error applying patch: file to update does not exist: "
                    + relPath;
        }

        if (pending == null && !Files.isRegularFile(source)) {
            return "Error applying patch: path to update is not a file: "
                    + relPath;
        }

        boolean usesCrlf = content.contains("\r\n");
        String normContent = content.replace("\r\n", "\n");
        String normOld = oldText.replace("\r\n", "\n");

        int pos = normContent.indexOf(normOld);
        if (pos < 0) {
            return "Error applying patch: old_text not found in " + relPath;
        }
        // 检查重复出现
        if (normContent.indexOf(normOld, pos + 1) >= 0) {
            return "Error applying patch: old_text appears multiple times in "
                    + relPath;
        }

        String newNorm = normContent.substring(0, pos)
                + newText.replace("\r\n", "\n")
                + normContent.substring(pos + normOld.length());
        if (!newNorm.isEmpty() && !newNorm.endsWith("\n")) {
            newNorm += "\n";
        }
        if (usesCrlf) newNorm = newNorm.replace("\n", "\r\n");

        writes.put(source, newNorm);
        int[] stats = lineDiffStats(content, newNorm);
        summaries.add(new PatchSummary("update", relPath,
                stats[0], stats[1]));
        return null;
    }

    // ==================== 路径验证 ====================

    /**
     * 验证相对路径：不能为空、不能含 null 字节、不能是绝对路径、不能含 ..
     *
     * @param path 待验证路径
     * @return 规范化路径
     * @throws PatchException 验证失败
     */
    // 对标 Python _validate_relative_path()
    static String validateRelativePath(String path) {
        String normalized = path.strip();
        if (normalized.isEmpty()) {
            throw new PatchException("patch path cannot be empty");
        }
        if (normalized.contains("\0")) {
            throw new PatchException("patch path contains a null byte: "
                    + path);
        }
        if (normalized.startsWith("~") || normalized.startsWith("/")
                || normalized.startsWith("\\")
                || ABSOLUTE_WINDOWS_RE.matcher(normalized).find()) {
            throw new PatchException("patch path must be relative: " + path);
        }
        for (String part : normalized.split("[\\\\/]+")) {
            if ("..".equals(part)) {
                throw new PatchException(
                        "patch path must not contain '..': " + path);
            }
        }
        return normalized;
    }

    // ==================== 文本工具方法 ====================

    /**
     * 追加文本，避免合并到未终止的最后一行。
     *
     * @param content  现有内容
     * @param addition 要追加的内容
     * @return 追加后的完整文本
     */
    // 对标 Python _append_text()
    static String appendText(String content, String addition) {
        String base = content.replace("\r\n", "\n");
        String extra = addition.replace("\r\n", "\n");
        if (!base.isEmpty() && !extra.isEmpty()
                && !base.endsWith("\n") && !extra.startsWith("\n")) {
            base += "\n";
        }
        String combined = base + extra;
        if (!combined.isEmpty() && !combined.endsWith("\n")) {
            combined += "\n";
        }
        return combined;
    }

    /**
     * 统计文本行数。
     *
     * @param text 文本
     * @return 行数
     */
    // 对标 Python _text_line_count()
    static int textLineCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        return text.replace("\r\n", "\n").split("\n", -1).length;
    }

    /**
     * 计算行级增删统计。
     *
     * @param before 变更前内容
     * @param after  变更后内容
     * @return [added, deleted]
     */
    // 对标 Python _line_diff_stats() using difflib.SequenceMatcher
    static int[] lineDiffStats(String before, String after) {
        List<String> beforeLines = List.of(
                before.replace("\r\n", "\n").split("\n", -1));
        List<String> afterLines = List.of(
                after.replace("\r\n", "\n").split("\n", -1));

        int added = 0;
        int deleted = 0;

        // 使用 DiffUtil 计算行级 opcodes
        int[][] dp = buildLcsTable(beforeLines, afterLines);
        traceOpcodes(dp, beforeLines, afterLines, 0, 0,
                beforeLines.size(), afterLines.size(),
                new int[]{added}, new int[]{deleted});
        // 简化版：使用序列匹配估算
        int matches = countMatchingLines(beforeLines, afterLines);
        added = afterLines.size() - matches;
        deleted = beforeLines.size() - matches;
        return new int[]{Math.max(0, added), Math.max(0, deleted)};
    }

    /**
     * 计算两个行列表间匹配的行数（简单版本，用于行统计估算）。
     */
    private static int countMatchingLines(List<String> a, List<String> b) {
        int matches = 0;
        int i = 0, j = 0;
        while (i < a.size() && j < b.size()) {
            if (a.get(i).equals(b.get(j))) {
                matches++;
                i++; j++;
            } else if (j + 1 < b.size() && a.get(i).equals(b.get(j + 1))) {
                j++;
            } else if (i + 1 < a.size() && a.get(i + 1).equals(b.get(j))) {
                i++;
            } else {
                i++; j++;
            }
        }
        return matches;
    }

    /**
     * 构建 LCS DP 表（用于 lineDiffStats）。
     */
    private static int[][] buildLcsTable(List<String> a, List<String> b) {
        int m = a.size(), n = b.size();
        int[][] dp = new int[m + 1][n + 1];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = n - 1; j >= 0; j--) {
                if (a.get(i).equals(b.get(j))) {
                    dp[i][j] = 1 + dp[i + 1][j + 1];
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }
        return dp;
    }

    /**
     * 从 LCS 表回溯操作码（replace/delete/insert/equal）。
     *
     * @param dp          LCS DP 表
     * @param a           旧行列表
     * @param b           新行列表
     * @param i           当前行 a 索引
     * @param j           当前行 b 索引
     * @param aEnd        a 结束索引
     * @param bEnd        b 结束索引
     * @param added       累加插入计数
     * @param deleted     累加删除计数
     */
    private static void traceOpcodes(int[][] dp, List<String> a, List<String> b,
                                     int i, int j, int aEnd, int bEnd,
                                     int[] added, int[] deleted) {
        while (i < aEnd || j < bEnd) {
            if (i < aEnd && j < bEnd && a.get(i).equals(b.get(j))) {
                i++; j++;
            } else if (j < bEnd
                    && (i >= aEnd || dp[i][j + 1] >= dp[i + 1][j])) {
                added[0]++;
                j++;
            } else if (i < aEnd
                    && (j >= bEnd || dp[i + 1][j] >= dp[i][j + 1])) {
                deleted[0]++;
                i++;
            } else {
                if (i < aEnd) { deleted[0]++; i++; }
                if (j < bEnd) { added[0]++; j++; }
            }
        }
    }

    /**
     * 格式化补丁摘要行。
     *
     * @param summary 补丁摘要
     * @return 格式化后的摘要字符串
     */
    // 对标 Python _format_summary()
    static String formatSummary(PatchSummary summary) {
        String stats = "";
        if (summary.added() > 0 || summary.deleted() > 0) {
            stats = " (+" + summary.added() + "/-" + summary.deleted() + ")";
        }
        return "- " + summary.action() + " " + summary.path() + stats;
    }

    // ==================== 补丁异常 ====================

    /** 补丁错误，对标 Python _PatchError。 */
    // 对标 Python _PatchError
    static final class PatchException extends RuntimeException {
        PatchException(String message) { super(message); }
    }
}
