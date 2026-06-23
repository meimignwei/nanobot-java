package com.nanobot.agent.tools.impl;

import com.nanobot.agent.tools.*;
import com.nanobot.agent.tools.schema.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 正则表达式文本搜索工具，支持多种输出模式、上下文、
 * 分页和二进制/大文件跳过。
 *
 * <p>对标 Python {@code nanobot/agent/tools/search.py GrepTool}（279-584 行）。
 */
public class GrepTool extends SearchTool {

    /** 输出最大字符数，对标 Python _MAX_RESULT_CHARS。 */
    // 对标 Python GrepTool._MAX_RESULT_CHARS
    private static final int MAX_RESULT_CHARS = 128_000;

    /** 单个文件最大读取字节数，对标 Python _MAX_FILE_BYTES。 */
    // 对标 Python GrepTool._MAX_FILE_BYTES
    private static final int MAX_FILE_BYTES = 2_000_000;

    /** 默认 head_limit，对标 Python _DEFAULT_HEAD_LIMIT。 */
    private static final int DEFAULT_HEAD_LIMIT = 250;

    /** 工具参数 JSON Schema。 */
    // 对标 Python GrepTool.parameters
    private static final Map<String, Object> PARAMETERS = buildParameters();

    private static Map<String, Object> buildParameters() {
        Map<String, Schema> props = new LinkedHashMap<>();
        props.put("pattern", new StringSchema(
                "Regex or plain text pattern to search for",
                1, null, null, false));
        props.put("path", new StringSchema(
                "File or directory to search in (default '.')",
                null, null, null, true));
        props.put("glob", new StringSchema(
                "Optional file filter, e.g. '*.py' or 'tests/**/test_*.py'",
                null, null, null, true));
        props.put("type", new StringSchema(
                "Optional file type shorthand, e.g. 'py', 'ts', 'md', 'json'",
                null, null, null, true));
        props.put("case_insensitive", new BooleanSchema(
                "Case-insensitive search (default false)", false, true));
        props.put("fixed_strings", new BooleanSchema(
                "Treat pattern as plain text instead of regex (default false)",
                false, true));
        props.put("output_mode", new StringSchema(
                "content: matching lines with optional context; "
                        + "files_with_matches: only matching file paths; "
                        + "count: matching line counts per file. "
                        + "Default: files_with_matches",
                null, null,
                List.of("content", "files_with_matches", "count"), true));
        props.put("context_before", new IntegerSchema(0,
                "Number of lines of context before each match",
                0, 20, null, true));
        props.put("context_after", new IntegerSchema(0,
                "Number of lines of context after each match",
                0, 20, null, true));
        props.put("max_matches", new IntegerSchema(0,
                "Legacy alias for head_limit in content mode",
                1, 1000, null, true));
        props.put("max_results", new IntegerSchema(0,
                "Legacy alias for head_limit in files_with_matches or count mode",
                1, 1000, null, true));
        props.put("head_limit", new IntegerSchema(250,
                "Maximum number of results to return. In content mode this "
                        + "limits matching line blocks; in other modes it "
                        + "limits file entries. Default 250",
                0, 1000, null, true));
        props.put("offset", new IntegerSchema(0,
                "Skip the first N results before applying head_limit",
                0, 100_000, null, true));
        return ToolParametersSchema.create(List.of("pattern"), null, props);
    }

    public GrepTool(Path workspace, Path allowedDir, Path mediaDir,
                    List<Path> extraAllowedDirs, FileStates fileStates,
                    boolean restrictToWorkspace,
                    boolean sandboxRestrictsWorkspace) {
        super(workspace, allowedDir, mediaDir, extraAllowedDirs,
                fileStates, restrictToWorkspace, sandboxRestrictsWorkspace);
    }

    @Override
    public String getName() { return "grep"; }

    @Override
    public String getDescription() {
        return "Search file contents with a regex pattern. "
                + "Default output_mode is files_with_matches (file paths only); "
                + "use content mode for matching lines with context. "
                + "Skips binary and files >2 MB. Supports glob/type filtering.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolParameters.deepCopy(PARAMETERS);
    }

    /**
     * 按正则表达式搜索文件内容，支持 content/files_with_matches/count 三种输出模式，
     * 自动跳过二进制和大于 2MB 的文件，支持分页和结果截断。
     *
     * @param params 已校验的工具参数
     * @return 搜索结果字符串的 CompletableFuture
     */
    @Override
    // 对标 Python GrepTool.execute()
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String patternStr = paramString(params, "pattern");
                if (patternStr == null || patternStr.isEmpty()) {
                    return "Error: pattern is required";
                }

                String path = paramString(params, "path");
                String outputMode = paramString(
                        params, "output_mode", "files_with_matches");
                int contextBefore = Math.max(0,
                        paramInt(params, "context_before", 0));
                int contextAfter = Math.max(0,
                        paramInt(params, "context_after", 0));
                boolean caseInsensitive = Boolean.TRUE.equals(
                        params.get("case_insensitive"));
                boolean fixedStrings = Boolean.TRUE.equals(
                        params.get("fixed_strings"));
                String glob = paramString(params, "glob");
                String type = paramString(params, "type");
                int offset = paramInt(params, "offset", 0);

                // 解析 head_limit，兼容旧别名 max_matches / max_results
                Integer limit;
                if (params.containsKey("head_limit")) {
                    int hl = paramInt(params, "head_limit", DEFAULT_HEAD_LIMIT);
                    limit = (hl == 0) ? null : hl;
                } else if ("content".equals(outputMode)
                        && params.containsKey("max_matches")) {
                    limit = paramInt(params, "max_matches", DEFAULT_HEAD_LIMIT);
                } else if (!"content".equals(outputMode)
                        && params.containsKey("max_results")) {
                    limit = paramInt(params, "max_results", DEFAULT_HEAD_LIMIT);
                } else {
                    limit = DEFAULT_HEAD_LIMIT;
                }

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

                // 编译正则表达式
                int flags = Pattern.MULTILINE;
                if (caseInsensitive) flags |= Pattern.CASE_INSENSITIVE;
                Pattern regex;
                try {
                    String needle = fixedStrings
                            ? Pattern.quote(patternStr) : patternStr;
                    regex = Pattern.compile(needle, flags);
                } catch (PatternSyntaxException e) {
                    return "Error: invalid regex pattern: " + e.getMessage();
                }

                Path root = Files.isDirectory(target) ? target : target.getParent();

                List<String> blocks = new ArrayList<>();
                int resultChars = 0;
                int seenContentMatches = 0;
                boolean truncated = false;
                boolean sizeTruncated = false;
                int skippedBinary = 0;
                int skippedLarge = 0;
                List<String> matchingFiles = new ArrayList<>();
                Map<String, Integer> counts = new LinkedHashMap<>();
                Map<String, Long> fileMtimes = new LinkedHashMap<>();

                List<Path> files = iterFiles(target);
                root = (root != null) ? root : target;

                for (Path filePath : files) {
                    String relPath = root.relativize(filePath).toString()
                            .replace(java.io.File.separatorChar, '/');
                    String fileName = filePath.getFileName().toString();

                    if (glob != null && !matchGlob(relPath, fileName, glob)) {
                        continue;
                    }
                    if (!matchesType(fileName, type)) {
                        continue;
                    }

                    byte[] raw;
                    try {
                        raw = Files.readAllBytes(filePath);
                    } catch (IOException e) {
                        skippedBinary++;
                        continue;
                    }
                    if (raw.length > MAX_FILE_BYTES) {
                        skippedLarge++;
                        continue;
                    }
                    if (isBinary(raw)) {
                        skippedBinary++;
                        continue;
                    }

                    long mtime;
                    try {
                        mtime = Files.getLastModifiedTime(filePath).toMillis();
                    } catch (IOException e) {
                        mtime = 0L;
                    }

                    String content;
                    try {
                        content = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        skippedBinary++;
                        continue;
                    }

                    String[] lines = content.split("\n", -1);
                    String displayPath = displayPath(filePath, root);
                    boolean fileHadMatch = false;

                    for (int idx = 1; idx <= lines.length; idx++) {
                        String line = lines[idx - 1];
                        if (!regex.matcher(line).find()) continue;
                        fileHadMatch = true;

                        if ("count".equals(outputMode)) {
                            counts.merge(displayPath, 1, Integer::sum);
                            continue;
                        }
                        if ("files_with_matches".equals(outputMode)) {
                            if (!matchingFiles.contains(displayPath)) {
                                matchingFiles.add(displayPath);
                                fileMtimes.put(displayPath, mtime);
                            }
                            break;
                        }

                        // content 模式
                        seenContentMatches++;
                        if (seenContentMatches <= offset) continue;
                        if (limit != null && blocks.size() >= limit) {
                            truncated = true;
                            break;
                        }
                        String block = formatBlock(displayPath, lines, idx,
                                contextBefore, contextAfter);
                        int extraSep = blocks.isEmpty() ? 0 : 2;
                        if (resultChars + extraSep + block.length()
                                > MAX_RESULT_CHARS) {
                            sizeTruncated = true;
                            break;
                        }
                        blocks.add(block);
                        resultChars += extraSep + block.length();
                    }

                    if ("count".equals(outputMode) && fileHadMatch) {
                        if (!matchingFiles.contains(displayPath)) {
                            matchingFiles.add(displayPath);
                            fileMtimes.put(displayPath, mtime);
                        }
                    }
                    if (List.of("count", "files_with_matches")
                            .contains(outputMode) && fileHadMatch) {
                        continue;
                    }
                    if (truncated || sizeTruncated) break;
                }

                String result;
                if ("files_with_matches".equals(outputMode)) {
                    if (matchingFiles.isEmpty()) {
                        result = "No matches found for pattern '"
                                + patternStr + "' in "
                                + (path != null ? path : ".");
                    } else {
                        matchingFiles.sort(Comparator
                                .<String, Long>comparing(
                                        f -> -fileMtimes.getOrDefault(f, 0L))
                                .thenComparing(f -> f));
                        Paginated<String> paged = paginate(
                                matchingFiles, limit, offset);
                        result = String.join("\n", paged.items());
                    }
                } else if ("count".equals(outputMode)) {
                    if (counts.isEmpty()) {
                        result = "No matches found for pattern '"
                                + patternStr + "' in "
                                + (path != null ? path : ".");
                    } else {
                        matchingFiles.sort(Comparator
                                .<String, Long>comparing(
                                        f -> -fileMtimes.getOrDefault(f, 0L))
                                .thenComparing(f -> f));
                        Paginated<String> ord = paginate(
                                matchingFiles, limit, offset);
                        String[] countLines = ord.items().stream()
                                .map(name -> name + ": " + counts.get(name))
                                .toArray(String[]::new);
                        result = String.join("\n", countLines);
                    }
                } else {
                    if (blocks.isEmpty()) {
                        result = "No matches found for pattern '"
                                + patternStr + "' in "
                                + (path != null ? path : ".");
                    } else {
                        result = String.join("\n\n", blocks);
                    }
                }

                // 构建注释行
                List<String> notes = new ArrayList<>();
                if ("content".equals(outputMode) && truncated) {
                    notes.add("(pagination: limit=" + limit
                            + ", offset=" + offset + ")");
                } else if ("content".equals(outputMode) && sizeTruncated) {
                    notes.add("(output truncated due to size)");
                } else if (truncated && List.of("count", "files_with_matches")
                        .contains(outputMode)) {
                    notes.add("(pagination: limit=" + limit
                            + ", offset=" + offset + ")");
                } else if (List.of("count", "files_with_matches")
                        .contains(outputMode) && offset > 0) {
                    notes.add("(pagination: offset=" + offset + ")");
                } else if ("content".equals(outputMode)
                        && offset > 0 && !blocks.isEmpty()) {
                    notes.add("(pagination: offset=" + offset + ")");
                }
                if (skippedBinary > 0) {
                    notes.add("(skipped " + skippedBinary
                            + " binary/unreadable files)");
                }
                if (skippedLarge > 0) {
                    notes.add("(skipped " + skippedLarge + " large files)");
                }
                if ("count".equals(outputMode) && !counts.isEmpty()) {
                    int total = counts.values().stream()
                            .mapToInt(Integer::intValue).sum();
                    notes.add("(total matches: " + total
                            + " in " + counts.size() + " files)");
                }
                if (!notes.isEmpty()) {
                    result += "\n\n" + String.join("\n", notes);
                }
                return result;

            } catch (SecurityException e) {
                return "Error: " + e.getMessage();
            } catch (Exception e) {
                return "Error searching files: " + e.getMessage();
            }
        });
    }

    /**
     * 格式化单个匹配块，包含文件路径:行号 标头和上下文行。
     * 匹配行前缀 ">"，上下文行前缀 " "。
     *
     * @param displayPath 显示路径
     * @param lines       文件所有行
     * @param matchLine   匹配行号（1-based）
     * @param before      上下文前行数
     * @param after       上下文后行数
     * @return 格式化后的匹配块字符串
     */
    // 对标 Python GrepTool._format_block()
    static String formatBlock(String displayPath, String[] lines,
                               int matchLine, int before, int after) {
        int start = Math.max(1, matchLine - before);
        int end = Math.min(lines.length, matchLine + after);
        StringBuilder sb = new StringBuilder();
        sb.append(displayPath).append(":").append(matchLine);
        for (int lineNo = start; lineNo <= end; lineNo++) {
            char marker = (lineNo == matchLine) ? '>' : ' ';
            sb.append('\n').append(marker).append(' ')
                    .append(lineNo).append("| ")
                    .append(lines[lineNo - 1]);
        }
        return sb.toString();
    }
}
