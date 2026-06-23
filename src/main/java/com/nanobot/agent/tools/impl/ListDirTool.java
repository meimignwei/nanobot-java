package com.nanobot.agent.tools.impl;

import com.nanobot.agent.tools.*;
import com.nanobot.agent.tools.schema.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * 目录列表工具，支持平铺和递归模式，自动忽略 .git、node_modules 等噪音目录。
 *
 * <p>对标 Python {@code nanobot/agent/tools/filesystem.py ListDirTool}（第 939-1022 行）。
 */
public class ListDirTool extends FsTool {

    /** 默认最大条目数，对标 Python _DEFAULT_MAX。 */
    private static final int DEFAULT_MAX = 200;

    /** 默认忽略的目录名，对标 Python ListDirTool._IGNORE_DIRS。 */
    // 对标 Python ListDirTool._IGNORE_DIRS
    private static final Set<String> IGNORE_DIRS = Set.of(
            ".git", "node_modules", "__pycache__", ".venv", "venv",
            "dist", "build", ".tox", ".mypy_cache", ".pytest_cache",
            ".ruff_cache", ".coverage", "htmlcov",
            "target", ".idea", ".gradle"
    );

    /** 工具参数 JSON Schema。 */
    // 对标 Python ListDirTool.parameters
    private static final Map<String, Object> PARAMETERS =
            ToolParametersSchema.create(
                    List.of("path"),
                    null,
                    Map.of(
                            "path", new StringSchema(
                                    "The directory path to list"),
                            "recursive", new BooleanSchema(
                                    "Recursively list all files (default false)",
                                    false, true),
                            "max_entries", new IntegerSchema(200,
                                    "Maximum entries to return (default 200)",
                                    1, null, null, true)
                    )
            );

    public ListDirTool(Path workspace, Path allowedDir, Path mediaDir,
                       List<Path> extraAllowedDirs, FileStates fileStates,
                       boolean restrictToWorkspace,
                       boolean sandboxRestrictsWorkspace) {
        super(workspace, allowedDir, mediaDir, extraAllowedDirs,
                fileStates, restrictToWorkspace, sandboxRestrictsWorkspace);
    }

    @Override
    public String getName() { return "list_dir"; }

    @Override
    public String getDescription() {
        return "List the contents of a directory. "
                + "Set recursive=true to explore nested structure. "
                + "Common noise directories (.git, node_modules, __pycache__, etc.) are auto-ignored.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolParameters.deepCopy(PARAMETERS);
    }

    @Override
    public Set<String> getScopes() {
        return Set.of("core", "subagent");
    }

    /**
     * 列出目录内容，支持递归和条目上限。
     *
     * @param params 已校验的工具参数
     * @return 目录列表字符串的 CompletableFuture
     */
    @Override
    // 对标 Python ListDirTool.execute()
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String path = paramString(params, "path");
                if (path == null || path.isEmpty()) {
                    return "Error: Unknown path";
                }
                boolean recursive = Boolean.TRUE.equals(
                        params.get("recursive"));
                int cap = params.containsKey("max_entries")
                        ? paramInt(params, "max_entries", DEFAULT_MAX)
                        : DEFAULT_MAX;

                Path dp = resolve(path);
                if (!Files.exists(dp)) {
                    return "Error: Directory not found: " + path;
                }
                if (!Files.isDirectory(dp)) {
                    return "Error: Not a directory: " + path;
                }

                List<String> items = new ArrayList<>();
                int total = 0;

                if (recursive) {
                    try (Stream<Path> stream = Files.walk(dp)) {
                        List<Path> sorted = stream
                                .filter(p -> {
                                    // 过滤 IGNORE_DIRS
                                    for (Path part : dp.relativize(p)) {
                                        if (IGNORE_DIRS.contains(
                                                part.getFileName().toString())) {
                                            return false;
                                        }
                                    }
                                    return !p.equals(dp);
                                })
                                .sorted()
                                .toList();
                        for (Path item : sorted) {
                            total++;
                            if (items.size() < cap) {
                                String rel = dp.relativize(item).toString();
                                if (Files.isDirectory(item)) rel += "/";
                                items.add(rel);
                            }
                        }
                    }
                } else {
                    try (Stream<Path> stream = Files.list(dp)) {
                        List<Path> sorted = stream
                                .filter(p -> !IGNORE_DIRS.contains(
                                        p.getFileName().toString()))
                                .sorted(Comparator.comparing(
                                        p -> p.getFileName().toString()))
                                .toList();
                        for (Path item : sorted) {
                            total++;
                            if (items.size() < cap) {
                                String name = item.getFileName().toString();
                                if (Files.isDirectory(item)) {
                                    items.add("📁 " + name);  // 📁 folder
                                } else {
                                    items.add("📄 " + name);  // 📄 file
                                }
                            }
                        }
                    }
                }

                if (items.isEmpty() && total == 0) {
                    return "Directory " + path + " is empty";
                }

                String result = String.join("\n", items);
                if (total > cap) {
                    result += "\n\n(truncated, showing first " + cap
                            + " of " + total + " entries)";
                }
                return result;
            } catch (SecurityException e) {
                return "Error: " + e.getMessage();
            } catch (Exception e) {
                return "Error listing directory: " + e.getMessage();
            }
        });
    }

    private static String paramString(Map<String, Object> params, String key) {
        Object val = params.get(key);
        return val instanceof String s ? s : null;
    }

    private static int paramInt(Map<String, Object> params, String key, int def) {
        Object val = params.get(key);
        if (val instanceof Integer i) return i;
        if (val instanceof Number n) return n.intValue();
        return def;
    }
}
