package com.nanobot.agent.tools.impl;

import com.nanobot.agent.tools.*;
import com.nanobot.agent.tools.schema.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 文件发现工具，支持按 glob 模式、类型简写和查询关键词搜索文件，
 * 支持按路径或修改时间排序以及分页。
 *
 * <p>对标 Python {@code nanobot/agent/tools/search.py FindFilesTool}（122-277 行）。
 */
public class FindFilesTool extends SearchTool {

    /** 默认 head_limit，对标 Python _DEFAULT_FILE_HEAD_LIMIT。 */
    private static final int DEFAULT_FILE_HEAD_LIMIT = 200;

    /** 工具参数 JSON Schema。 */
    // 对标 Python FindFilesTool.parameters
    private static final Map<String, Object> PARAMETERS =
            ToolParametersSchema.create(
                    null, null,
                    Map.of(
                            "path", new StringSchema(
                                    "Directory or file to search in (default '.')"),
                            "query", new StringSchema(
                                    "Optional case-insensitive path fragment search. "
                                            + "Whitespace-separated terms must all be present.",
                                    null, null, null, true),
                            "glob", new StringSchema(
                                    "Optional file filter, e.g. '*.py' or "
                                            + "'tests/**/test_*.py'",
                                    null, null, null, true),
                            "type", new StringSchema(
                                    "Optional file type shorthand, e.g. 'py', "
                                            + "'ts', 'md', 'json'",
                                    null, null, null, true),
                            "include_dirs", new BooleanSchema(
                                    "Include matching directories as well as "
                                            + "files (default false)",
                                    false, true),
                            "sort", new StringSchema(
                                    "Sort by path or most recently modified "
                                            + "first (default path)",
                                    null, null,
                                    List.of("path", "modified"), true),
                            "head_limit", new IntegerSchema(200,
                                    "Maximum number of paths to return "
                                            + "(default 200, 0 for all, max 1000)",
                                    0, 1000, null, true),
                            "offset", new IntegerSchema(0,
                                    "Skip the first N results before applying "
                                            + "head_limit",
                                    0, 100_000, null, true)
                    )
            );

    public FindFilesTool(Path workspace, Path allowedDir, Path mediaDir,
                         List<Path> extraAllowedDirs, FileStates fileStates,
                         boolean restrictToWorkspace,
                         boolean sandboxRestrictsWorkspace) {
        super(workspace, allowedDir, mediaDir, extraAllowedDirs,
                fileStates, restrictToWorkspace, sandboxRestrictsWorkspace);
    }

    @Override
    public String getName() { return "find_files"; }

    @Override
    public String getDescription() {
        return "Find files by path fragment, glob, or file type. "
                + "Use this before read_file when you need to locate files. "
                + "Returns workspace-relative paths and skips common "
                + "dependency/build directories.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolParameters.deepCopy(PARAMETERS);
    }

    /**
     * 按 glob / 类型 / 查询关键词搜索文件，支持排序和分页。
     *
     * @param params 已校验的工具参数
     * @return 搜索结果字符串的 CompletableFuture
     */
    @Override
    // 对标 Python FindFilesTool.execute()
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String path = paramString(params, "path");
                String query = paramString(params, "query");
                String glob = paramString(params, "glob");
                String type = paramString(params, "type");
                boolean includeDirs = Boolean.TRUE.equals(
                        params.get("include_dirs"));
                String sort = paramString(params, "sort", "path");
                int offset = paramInt(params, "offset", 0);

                // 解析目标路径
                Path target = (path != null && !path.isEmpty())
                        ? resolve(path) : (allowedDir != null
                                ? allowedDir : workspace);
                if (!Files.exists(target)) {
                    return "Error: Path not found: "
                            + (path != null ? path : ".");
                }
                if (!(Files.isDirectory(target) || Files.isRegularFile(target))) {
                    return "Error: Unsupported path: "
                            + (path != null ? path : ".");
                }
                if (!Set.of("path", "modified").contains(sort)) {
                    return "Error: sort must be 'path' or 'modified'";
                }

                // 解析 head_limit：null 用默认 200，0 表示无限制
                Integer limit = null;
                if (params.containsKey("head_limit")) {
                    int hl = paramInt(params, "head_limit",
                            DEFAULT_FILE_HEAD_LIMIT);
                    limit = (hl == 0) ? null : hl;
                } else {
                    limit = DEFAULT_FILE_HEAD_LIMIT;
                }

                Path root = Files.isDirectory(target) ? target : target.getParent();

                // 收集所有匹配路径及其 mtime
                List<PathItem> matches = new ArrayList<>();
                for (Path candidate : iterPaths(target, includeDirs)) {
                    if (Files.isDirectory(candidate) && !includeDirs) continue;
                    String relPath = root.relativize(candidate).toString()
                            .replace(java.io.File.separatorChar, '/');
                    String displayPath = displayPath(candidate, root);
                    String name = candidate.getFileName().toString();

                    if (glob != null && !matchGlob(relPath, name, glob)) {
                        continue;
                    }
                    if (Files.isRegularFile(candidate)
                            && !matchesType(name, type)) {
                        continue;
                    }
                    if (Files.isDirectory(candidate) && type != null
                            && !type.isEmpty()) {
                        continue;
                    }
                    if (!matchesQuery(displayPath, query)) {
                        continue;
                    }
                    long mtime;
                    try {
                        mtime = Files.getLastModifiedTime(candidate).toMillis();
                    } catch (IOException e) {
                        mtime = 0L;
                    }
                    String suffix = Files.isDirectory(candidate) ? "/" : "";
                    matches.add(new PathItem(displayPath + suffix, mtime));
                }

                // 排序
                if ("modified".equals(sort)) {
                    matches.sort(Comparator
                            .<PathItem>comparingLong(p -> -p.mtime)
                            .thenComparing(p -> p.path));
                } else {
                    matches.sort(Comparator.comparing(p -> p.path));
                }

                List<String> paths = matches.stream()
                        .map(p -> p.path).toList();
                Paginated<String> paged = paginate(paths, limit, offset);
                if (paged.items().isEmpty()) {
                    return "No files found";
                }

                String result = String.join("\n", paged.items());
                String note = paginationNote(limit, offset, paged.truncated());
                if (note != null) {
                    result += "\n\n" + note;
                }
                return result;
            } catch (SecurityException e) {
                return "Error: " + e.getMessage();
            } catch (Exception e) {
                return "Error finding files: " + e.getMessage();
            }
        });
    }

    /** 路径与修改时间对，用于排序。 */
    private record PathItem(String path, long mtime) {}

    /**
     * 遍历目录下的所有文件（及可选目录），自动忽略 IGNORE_DIRS。
     *
     * @param root        搜索根路径
     * @param includeDirs 是否同时产出目录
     * @return 按名称排序的路径列表
     */
    // 对标 Python FindFilesTool._iter_paths()
    private List<Path> iterPaths(Path root, boolean includeDirs)
            throws IOException {
        List<Path> result = new ArrayList<>();
        if (Files.isRegularFile(root)) {
            result.add(root);
            return result;
        }
        if (includeDirs) {
            result.add(root);
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path dir, BasicFileAttributes attrs) {
                if (IGNORE_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (includeDirs && !dir.equals(root)) {
                    result.add(dir);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(
                    Path file, BasicFileAttributes attrs) {
                result.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(
                    Path file, IOException exc) {
                return FileVisitResult.SKIP_SUBTREE;
            }
        });
        result.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return result;
    }
}
