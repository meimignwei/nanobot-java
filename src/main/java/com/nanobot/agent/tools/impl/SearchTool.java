package com.nanobot.agent.tools.impl;

import com.nanobot.agent.tools.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Stream;

/**
 * 搜索工具抽象基类，共享工作空间解析、FileStates、
 * 分页、类型匹配、glob 匹配和二进制检测等工具方法。
 *
 * <p>对标 Python {@code nanobot/agent/tools/search.py _SearchTool} +
 * 模块级工具函数。
 */
public abstract class SearchTool extends FsTool {

    /** 默认忽略的目录名，对标 Python ListDirTool._IGNORE_DIRS。 */
    // 对标 Python _SearchTool._IGNORE_DIRS
    static final Set<String> IGNORE_DIRS = Set.of(
            ".git", "node_modules", "__pycache__", ".venv", "venv",
            ".mypy_cache", ".pytest_cache", ".ruff_cache",
            "target", ".idea", ".gradle", "build", "dist"
    );

    /** 文件类型到 glob 的映射，对标 Python _TYPE_GLOB_MAP。 */
    // 对标 Python _TYPE_GLOB_MAP
    static final Map<String, List<String>> TYPE_GLOB_MAP = Map.ofEntries(
            Map.entry("py", List.of("*.py", "*.pyi")),
            Map.entry("python", List.of("*.py", "*.pyi")),
            Map.entry("js", List.of("*.js", "*.jsx", "*.mjs", "*.cjs")),
            Map.entry("ts", List.of("*.ts", "*.tsx", "*.mts", "*.cts")),
            Map.entry("tsx", List.of("*.tsx")),
            Map.entry("jsx", List.of("*.jsx")),
            Map.entry("json", List.of("*.json")),
            Map.entry("md", List.of("*.md", "*.mdx")),
            Map.entry("markdown", List.of("*.md", "*.mdx")),
            Map.entry("go", List.of("*.go")),
            Map.entry("rs", List.of("*.rs")),
            Map.entry("rust", List.of("*.rs")),
            Map.entry("java", List.of("*.java")),
            Map.entry("sh", List.of("*.sh", "*.bash")),
            Map.entry("yaml", List.of("*.yaml", "*.yml")),
            Map.entry("yml", List.of("*.yaml", "*.yml")),
            Map.entry("toml", List.of("*.toml")),
            Map.entry("sql", List.of("*.sql")),
            Map.entry("html", List.of("*.html", "*.htm")),
            Map.entry("css", List.of("*.css", "*.scss", "*.sass"))
    );

    /** 构造 SearchTool，注入工作空间相关的路径和策略配置。 */
    // 对标 Python _SearchTool.__init__()
    public SearchTool(Path workspace, Path allowedDir, Path mediaDir,
                      List<Path> extraAllowedDirs, FileStates fileStates,
                      boolean restrictToWorkspace,
                      boolean sandboxRestrictsWorkspace) {
        super(workspace, allowedDir, mediaDir, extraAllowedDirs,
                fileStates, restrictToWorkspace, sandboxRestrictsWorkspace);
    }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public Set<String> getScopes() {
        return Set.of("core", "subagent");
    }

    // ==================== 共享工具方法 ====================

    /**
     * 规范化 glob 模式字符串，去除空白并将反斜杠转为正斜杠。
     *
     * @param pattern 原始 glob 模式
     * @return 规范化后的模式字符串
     */
    // 对标 Python _normalize_pattern()
    static String normalizePattern(String pattern) {
        return pattern.strip().replace("\\", "/");
    }

    /**
     * 判断相对路径是否匹配指定的 glob 模式。
     * 如果模式包含 "/" 或以 "**" 开头则使用完整路径匹配，
     * 否则使用 fnmatch 风格的纯文件名匹配。
     *
     * @param relPath 文件相对于搜索根的相对路径（使用 / 分隔符）
     * @param name    文件名
     * @param pattern glob 模式
     * @return 是否匹配
     */
    // 对标 Python _match_glob()
    static boolean matchGlob(String relPath, String name, String pattern) {
        String normalized = normalizePattern(pattern);
        if (normalized.isEmpty()) return false;
        if (normalized.contains("/") || normalized.startsWith("**")) {
            return Path.of(relPath).getFileSystem()
                    .getPathMatcher("glob:" + normalized)
                    .matches(Path.of(relPath));
        }
        // fnmatch 风格：仅匹配文件名
        return Path.of(name).getFileSystem()
                .getPathMatcher("glob:" + normalized)
                .matches(Path.of(name));
    }

    /**
     * 判断字节内容是否为二进制文件。
     * 先检查 null 字节，再检查前 4096 字节中控制字符的比例是否超过 20%。
     *
     * @param raw 文件原始字节
     * @return 可能是二进制返回 true
     */
    // 对标 Python _is_binary()
    static boolean isBinary(byte[] raw) {
        for (byte b : raw) {
            if (b == 0) return true;
        }
        int sampleLen = Math.min(raw.length, 4096);
        if (sampleLen == 0) return false;
        int nonText = 0;
        for (int i = 0; i < sampleLen; i++) {
            int b = raw[i] & 0xFF;
            if (b < 9 || (b > 13 && b < 32)) nonText++;
        }
        return (double) nonText / sampleLen > 0.2;
    }

    /** 分页结果记录，包含切片后的列表和是否被截断。 */
    // 对标 Python _paginate() 返回的 tuple
    record Paginated<T>(List<T> items, boolean truncated) {}

    /**
     * 对列表进行分页切片。
     *
     * @param items  原始列表
     * @param limit  最大返回数（null 表示不限制）
     * @param offset 跳过的数量
     * @param <T>    元素类型
     * @return 分页结果
     */
    // 对标 Python _paginate()
    static <T> Paginated<T> paginate(List<T> items, Integer limit, int offset) {
        int from = Math.min(offset, items.size());
        if (limit == null) {
            return new Paginated<>(items.subList(from, items.size()), false);
        }
        int to = Math.min(from + limit, items.size());
        boolean truncated = items.size() > offset + limit;
        return new Paginated<>(items.subList(from, to), truncated);
    }

    /**
     * 生成分页提示文本。
     *
     * @param limit     最大返回数（null 表示无限制）
     * @param offset    跳过的数量
     * @param truncated 是否被截断
     * @return 分页提示字符串，无需提示返回 null
     */
    // 对标 Python _pagination_note()
    static String paginationNote(Integer limit, int offset, boolean truncated) {
        if (truncated) {
            if (limit == null) return "(pagination: offset=" + offset + ")";
            return "(pagination: limit=" + limit + ", offset=" + offset + ")";
        }
        if (offset > 0) return "(pagination: offset=" + offset + ")";
        return null;
    }

    /**
     * 判断文件名是否匹配指定的类型简写。
     *
     * @param name     文件名
     * @param fileType 类型简写（如 "py"、"java"），null 或空表示匹配全部
     * @return 是否匹配
     */
    // 对标 Python _matches_type()
    static boolean matchesType(String name, String fileType) {
        if (fileType == null || fileType.isEmpty()) return true;
        String lowered = fileType.strip().toLowerCase();
        if (lowered.isEmpty()) return true;
        List<String> patterns = TYPE_GLOB_MAP.getOrDefault(lowered,
                List.of("*." + lowered));
        String nameLower = name.toLowerCase();
        return patterns.stream().anyMatch(p ->
                matchGlob(nameLower, nameLower, p.toLowerCase()));
    }

    /**
     * 判断相对路径是否包含所有查询关键词（空白分隔，大小写不敏感）。
     *
     * @param relPath 文件相对路径
     * @param query   查询字符串（空白分隔的多词 AND 逻辑）
     * @return 是否匹配
     */
    // 对标 Python _matches_query()
    static boolean matchesQuery(String relPath, String query) {
        if (query == null || query.isEmpty()) return true;
        String haystack = relPath.toLowerCase();
        String[] terms = query.toLowerCase().split("\\s+");
        for (String term : terms) {
            if (!term.isEmpty() && !haystack.contains(term)) return false;
        }
        return true;
    }

    /**
     * 获取文件相对于工作空间或搜索根的显示路径。
     *
     * @param target 目标文件路径
     * @param root   搜索根路径
     * @return 用于显示的相对路径字符串（/ 分隔）
     */
    // 对标 Python _SearchTool._display_path()
    protected String displayPath(Path target, Path root) {
        if (workspace != null) {
            try {
                return workspace.relativize(target).toString()
                        .replace(java.io.File.separatorChar, '/');
            } catch (IllegalArgumentException e) {
                // target 不在 workspace 下，回退到相对于 root
            }
        }
        return root.relativize(target).toString()
                .replace(java.io.File.separatorChar, '/');
    }

    /**
     * 遍历目录下的所有文件，自动忽略 IGNORE_DIRS。
     *
     * @param root 搜索根路径
     * @return 按名称排序的文件路径迭代器
     */
    // 对标 Python _SearchTool._iter_files()
    List<Path> iterFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.isRegularFile(root)) {
            files.add(root);
            return files;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path dir, BasicFileAttributes attrs) {
                if (IGNORE_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(
                    Path file, BasicFileAttributes attrs) {
                files.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(
                    Path file, IOException exc) {
                return FileVisitResult.SKIP_SUBTREE;
            }
        });
        files.sort(Comparator.comparing(
                p -> p.getFileName().toString()));
        return files;
    }

    // ==================== 参数辅助 ====================

    static String paramString(Map<String, Object> params, String key, String def) {
        Object val = params.get(key);
        return (val instanceof String s && !s.isEmpty()) ? s : def;
    }

    static String paramString(Map<String, Object> params, String key) {
        Object val = params.get(key);
        return val instanceof String s ? s : null;
    }

    static int paramInt(Map<String, Object> params, String key, int def) {
        Object val = params.get(key);
        if (val instanceof Integer i) return i;
        if (val instanceof Number n) return n.intValue();
        return def;
    }
}
