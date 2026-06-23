package com.nanobot.agent.tools.impl;

import com.nanobot.agent.tools.*;
import com.nanobot.agent.tools.schema.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 智能文本替换工具，使用多级回退匹配策略、引号保留、缩进对齐和近匹配诊断。
 *
 * <p>对标 Python {@code nanobot/agent/tools/filesystem.py EditFileTool}
 * （第 433-932 行，约 500 行逻辑）。
 */
public class EditFileTool extends FsTool {

    /** 最大编辑文件大小：1 GiB，对标 Python _MAX_EDIT_FILE_SIZE。 */
    // 对标 Python EditFileTool._MAX_EDIT_FILE_SIZE
    private static final long MAX_EDIT_FILE_SIZE = 1024L * 1024 * 1024;

    /** Markdown 扩展名集合，不执行尾部空白剥离。 */
    // 对标 Python EditFileTool._MARKDOWN_EXTS
    private static final Set<String> MARKDOWN_EXTS = Set.of(".md", ".mdx", ".markdown");

    /** 工具参数 JSON Schema，对标 Python EditFileTool.parameters。 */
    // 对标 Python @tool_parameters EditFileTool.parameters
    private static final Map<String, Object> PARAMETERS =
            ToolParametersSchema.create(
                    List.of("path", "old_text", "new_text"),
                    null,
                    Map.of(
                            "path", new StringSchema("The file path to edit"),
                            "old_text", new StringSchema(
                                    "The text to find and replace"),
                            "new_text", new StringSchema(
                                    "The text to replace with"),
                            "replace_all", new BooleanSchema(
                                    "Replace all occurrences (default false)",
                                    false, true),
                            "occurrence", new IntegerSchema(1,
                                    "Optional 1-based occurrence to replace when "
                                            + "old_text appears multiple times.",
                                    1, null, null, true),
                            "line_hint", new IntegerSchema(1,
                                    "Optional 1-based line hint used to choose "
                                            + "the nearest match.",
                                    1, null, null, true),
                            "expected_replacements", new IntegerSchema(1,
                                    "Optional guard for the number of "
                                            + "replacements that must be made.",
                                    1, null, null, true)
                    )
            );

    /** 匹配区间记录，含行号。 */
    // 对标 Python _MatchSpan
    private record MatchSpan(int start, int end, String text, int line) {}

    /** 最佳窗口结果。 */
    private record BestWindow(double ratio, int start, List<String> lines,
                              List<String> hints) {}

    public EditFileTool(Path workspace, Path allowedDir, Path mediaDir,
                        List<Path> extraAllowedDirs, FileStates fileStates,
                        boolean restrictToWorkspace,
                        boolean sandboxRestrictsWorkspace) {
        super(workspace, allowedDir, mediaDir, extraAllowedDirs,
                fileStates, restrictToWorkspace, sandboxRestrictsWorkspace);
    }

    @Override
    public String getName() { return "edit_file"; }

    @Override
    public String getDescription() {
        return "Perform a small, exact replacement in one file by replacing "
                + "old_text with new_text. Use this for narrow text "
                + "substitutions with old_text copied from read_file. "
                + "Shows closest-match diagnostics on failure.";
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
     * 执行文本替换，含多级回退匹配、引号保留、缩进对齐和诊断。
     *
     * @param params 已校验的工具参数
     * @return 替换结果消息的 CompletableFuture
     */
    @Override
    // 对标 Python EditFileTool.execute()
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String path = paramStr(params, "path");
                String oldText = paramStr(params, "old_text");
                String newText = paramStr(params, "new_text");
                boolean replaceAll = Boolean.TRUE.equals(params.get("replace_all"));
                Integer occurrence = params.containsKey("occurrence")
                        ? paramInt(params, "occurrence", 1) : null;
                Integer lineHint = params.containsKey("line_hint")
                        ? paramInt(params, "line_hint", 1) : null;
                Integer expectedReplacements = params.containsKey("expected_replacements")
                        ? paramInt(params, "expected_replacements", 1) : null;

                if (path == null || path.isEmpty())
                    return "Error: Unknown path";
                if (oldText == null)
                    return "Error: Unknown old_text";
                if (newText == null)
                    return "Error: Unknown new_text";
                if (occurrence != null && occurrence < 1)
                    return "Error: occurrence must be >= 1.";
                if (lineHint != null && lineHint < 1)
                    return "Error: line_hint must be >= 1.";
                if (expectedReplacements != null && expectedReplacements < 1)
                    return "Error: expected_replacements must be >= 1.";

                Path fp = resolve(path);

                // 创建文件语义：old_text="" 且文件不存在 → 创建
                if (!Files.exists(fp)) {
                    if (oldText.isEmpty()) {
                        Files.createDirectories(fp.getParent());
                        Files.writeString(fp, newText);
                        fileStates().recordWrite(fp);
                        return "Successfully created " + fp;
                    }
                    return fileNotFoundMsg(path, fp);
                }

                // 文件大小保护
                long fsize;
                try { fsize = Files.size(fp); } catch (IOException e) { fsize = 0; }
                if (fsize > MAX_EDIT_FILE_SIZE) {
                    return String.format(
                            "Error: File too large to edit (%.1f GiB). "
                                    + "Maximum is 1 GiB.",
                            fsize / (1024.0 * 1024 * 1024));
                }

                // old_text="" 但文件存在且非空 → 拒绝
                byte[] raw = Files.readAllBytes(fp);
                String content = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
                if (oldText.isEmpty()) {
                    if (!content.strip().isEmpty()) {
                        return "Error: Cannot create file — " + path
                                + " already exists and is not empty.";
                    }
                    Files.writeString(fp, newText);
                    fileStates().recordWrite(fp);
                    return "Successfully edited " + fp;
                }

                // 读前检查
                String warning = fileStates().checkRead(fp);

                boolean usesCrlf = raw.length > 0 && content.contains("\r\n");
                content = content.replace("\r\n", "\n");
                String normOld = oldText.replace("\r\n", "\n");
                List<MatchSpan> matches = findMatches(content, normOld);

                if (matches.isEmpty()) {
                    return notFoundMsg(normOld, content, path);
                }

                int count = matches.size();
                if (replaceAll && occurrence != null)
                    return "Error: occurrence cannot be used with replace_all=true.";
                if (replaceAll && lineHint != null)
                    return "Error: line_hint cannot be used with replace_all=true.";
                if (occurrence != null && lineHint != null)
                    return "Error: line_hint cannot be used with occurrence.";

                List<MatchSpan> selected;
                if (count > 1 && !replaceAll) {
                    if (occurrence != null) {
                        if (occurrence > count) {
                            return "Error: occurrence " + occurrence
                                    + " is out of range; old_text appears "
                                    + count + " times.";
                        }
                        selected = List.of(matches.get(occurrence - 1));
                    } else if (lineHint != null) {
                        MatchSpan nearest = matches.stream()
                                .min(Comparator.comparingInt(
                                        m -> Math.abs(m.line() - lineHint)))
                                .orElseThrow();
                        int distance = Math.abs(nearest.line() - lineHint);
                        long sameCount = matches.stream()
                                .filter(m -> Math.abs(m.line() - lineHint) == distance)
                                .count();
                        if (sameCount > 1) {
                            return "Error: line_hint " + lineHint
                                    + " is ambiguous; old_text appears "
                                    + count + " times.";
                        }
                        selected = List.of(nearest);
                    } else {
                        List<Integer> lineNumbers = matches.stream()
                                .map(MatchSpan::line).toList();
                        String preview = lineNumbers.stream()
                                .limit(3).map(String::valueOf)
                                .collect(Collectors.joining(", ", "line ", ""));
                        if (lineNumbers.size() > 3) preview += ", ...";
                        return "Warning: old_text appears " + count
                                + " times at " + preview + ". "
                                + "Provide more context, set occurrence to "
                                + "choose one match, or set replace_all=true.";
                    }
                } else if (occurrence != null && occurrence > count) {
                    return "Error: occurrence " + occurrence
                            + " is out of range; old_text appears "
                            + count + " time.";
                } else if (replaceAll) {
                    selected = matches;
                } else {
                    selected = List.of(matches.get(occurrence != null
                            ? occurrence - 1 : 0));
                }

                if (expectedReplacements != null
                        && selected.size() != expectedReplacements) {
                    return "Error: expected " + expectedReplacements
                            + " replacements but would make "
                            + selected.size() + ".";
                }

                String normNew = newText.replace("\r\n", "\n");

                // 非 Markdown 文件剥离尾部空白
                String ext = fp.getFileName().toString().toLowerCase();
                int dot = ext.lastIndexOf('.');
                String suffix = dot >= 0 ? ext.substring(dot) : "";
                if (!MARKDOWN_EXTS.contains(suffix)) {
                    normNew = stripTrailingWs(normNew);
                }

                // 从后往前替换以保持位置有效
                List<MatchSpan> reversed = new ArrayList<>(selected);
                reversed.sort(Comparator.comparing(MatchSpan::start).reversed());

                String newContent = content;
                for (MatchSpan m : reversed) {
                    String replacement = preserveQuoteStyle(normOld, m.text(), normNew);
                    replacement = reindentLikeMatch(normOld, m.text(), replacement);

                    // 删除行清理：new_text="" 时消耗尾部换行
                    int end = m.end();
                    if (replacement.isEmpty() && !m.text().endsWith("\n")
                            && end < newContent.length()
                            && newContent.charAt(end) == '\n') {
                        end++;
                    }

                    newContent = newContent.substring(0, m.start())
                            + replacement + newContent.substring(end);
                }

                if (usesCrlf) newContent = newContent.replace("\n", "\r\n");
                Files.writeString(fp, newContent);
                fileStates().recordWrite(fp);

                String msg = "Successfully edited " + fp;
                if (warning != null) msg = warning + "\n" + msg;
                return msg;

            } catch (SecurityException e) {
                return "Error: " + e.getMessage();
            } catch (Exception e) {
                return "Error editing file: " + e.getMessage();
            }
        });
    }

    // ==================== 引号处理 ====================

    /**
     * 将弯引号/智能引号标准化为直引号。
     *
     * @param s 原始文本
     * @return 标准化后的文本
     */
    // 对标 Python _normalize_quotes()
    static String normalizeQuotes(String s) {
        return s
                .replace('‘', '\'')  // ‘ → '
                .replace('’', '\'')  // ’ → '
                .replace('“', '"')   // " → "
                .replace('”', '"');  // " → "
    }

    /**
     * 将双引号替换为弯双引号（交替开/闭）。
     *
     * @param text 原始文本
     * @return 弯引号文本
     */
    // 对标 Python _curly_double_quotes()
    static String curlyDoubleQuotes(String text) {
        StringBuilder sb = new StringBuilder();
        boolean opening = true;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') {
                sb.append(opening ? '“' : '”');
                opening = !opening;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /**
     * 将单引号替换为弯单引号（智能判断所有格 vs 开闭引号）。
     *
     * @param text 原始文本
     * @return 弯引号文本
     */
    // 对标 Python _curly_single_quotes()
    static String curlySingleQuotes(String text) {
        StringBuilder sb = new StringBuilder();
        boolean opening = true;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch != '\'') {
                sb.append(ch);
                continue;
            }
            char prevCh = i > 0 ? text.charAt(i - 1) : 0;
            char nextCh = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if (Character.isLetterOrDigit(prevCh)
                    && Character.isLetterOrDigit(nextCh)) {
                sb.append('’'); // 所有格
                continue;
            }
            sb.append(opening ? '‘' : '’');
            opening = !opening;
        }
        return sb.toString();
    }

    /**
     * 当引号标准化后匹配时，保留原文的弯引号风格。
     *
     * @param oldText    原始搜索文本
     * @param actualText 实际匹配到的文本
     * @param newText    替换文本
     * @return 调整引号风格后的替换文本
     */
    // 对标 Python _preserve_quote_style()
    static String preserveQuoteStyle(String oldText, String actualText,
                                      String newText) {
        if (normalizeQuotes(oldText.strip()).equals(
                normalizeQuotes(actualText.strip()))
                && !oldText.equals(actualText)) {
            String styled = newText;
            if (actualText.chars().anyMatch(
                    c -> c == '“' || c == '”')
                    && styled.contains("\"")) {
                styled = curlyDoubleQuotes(styled);
            }
            if (actualText.chars().anyMatch(
                    c -> c == '‘' || c == '’')
                    && styled.contains("'")) {
                styled = curlySingleQuotes(styled);
            }
            return styled;
        }
        return newText;
    }

    // ==================== 缩进处理 ====================

    /**
     * 提取行首空白字符。
     *
     * @param line 输入行
     * @return 行首空白
     */
    // 对标 Python _leading_ws()
    static String leadingWs(String line) {
        int i = 0;
        while (i < line.length()
                && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    /**
     * 按实际匹配块的缩进对齐替换文本，保持外部缩进差异。
     *
     * @param oldText    原始搜索文本
     * @param actualText 实际匹配到的文本
     * @param newText    替换文本
     * @return 重缩进后的替换文本
     */
    // 对标 Python _reindent_like_match()
    static String reindentLikeMatch(String oldText, String actualText,
                                     String newText) {
        String[] oldLines = oldText.split("\n", -1);
        String[] actualLines = actualText.split("\n", -1);
        if (oldLines.length != actualLines.length) return newText;

        record Pair(String oldLine, String actualLine) {}
        List<Pair> comparable = new ArrayList<>();
        for (int i = 0; i < oldLines.length; i++) {
            if (!oldLines[i].strip().isEmpty()
                    && !actualLines[i].strip().isEmpty()) {
                comparable.add(new Pair(oldLines[i], actualLines[i]));
            }
        }
        if (comparable.isEmpty()) return newText;
        for (Pair p : comparable) {
            if (!normalizeQuotes(p.oldLine().strip())
                    .equals(normalizeQuotes(p.actualLine().strip()))) {
                return newText;
            }
        }

        String oldWs = leadingWs(comparable.get(0).oldLine());
        String actualWs = leadingWs(comparable.get(0).actualLine());
        if (actualWs.equals(oldWs)) return newText;

        String delta;
        if (!oldWs.isEmpty()) {
            if (!actualWs.startsWith(oldWs)) return newText;
            delta = actualWs.substring(oldWs.length());
        } else {
            delta = actualWs;
        }
        if (delta.isEmpty()) return newText;

        String[] newLines = newText.split("\n", -1);
        for (int i = 0; i < newLines.length; i++) {
            if (!newLines[i].isEmpty()) {
                newLines[i] = delta + newLines[i];
            }
        }
        return String.join("\n", newLines);
    }

    // ==================== 匹配策略 ====================

    /**
     * 多级回退定位所有匹配。
     *
     * @param content 文件内容（已规范化换行为 \n）
     * @param search  搜索文本
     * @return 匹配 Span 列表（可能为空）
     */
    // 对标 Python _find_matches()
    static List<MatchSpan> findMatches(String content, String search) {
        List<MatchSpan> matches;
        matches = findExactMatches(content, search);
        if (!matches.isEmpty()) return matches;
        matches = findTrimMatches(content, search, false);
        if (!matches.isEmpty()) return matches;
        matches = findTrimMatches(content, search, true);
        if (!matches.isEmpty()) return matches;
        return findQuoteMatches(content, search);
    }

    /**
     * 策略 1：精确子串匹配，返回所有匹配位点。
     *
     * @param content 文件内容
     * @param search  搜索文本
     * @return 匹配列表
     */
    // 对标 Python _find_exact_matches()
    static List<MatchSpan> findExactMatches(String content, String search) {
        List<MatchSpan> matches = new ArrayList<>();
        int start = 0;
        while (true) {
            int idx = content.indexOf(search, start);
            if (idx == -1) break;
            int line = (int) content.substring(0, idx).chars()
                    .filter(c -> c == '\n').count() + 1;
            matches.add(new MatchSpan(idx, idx + search.length(),
                    content.substring(idx, idx + search.length()), line));
            start = idx + Math.max(1, search.length());
        }
        return matches;
    }

    /**
     * 策略 2/3：行修剪滑动窗口匹配，可选引号标准化。
     * 使用 keepends 偏移量精确计算原始字符串中的位置。
     *
     * @param content         文件内容（\n 换行）
     * @param search          搜索文本
     * @param normalizeQuotes 是否标准化引号
     * @return 匹配列表
     */
    // 对标 Python _find_trim_matches()
    static List<MatchSpan> findTrimMatches(String content, String search,
                                            boolean normalizeQuotes) {
        String[] oldLines = search.split("\n", -1);
        if (oldLines.length == 0) return List.of();

        String[] contentLines = content.split("\n", -1);
        String[] contentLinesKeepends = content.split("\n", -1);
        if (contentLines.length < oldLines.length) return List.of();

        // 构建 keepends 偏移量
        int[] offsets = new int[contentLinesKeepends.length + 1];
        int pos = 0;
        for (int i = 0; i < contentLinesKeepends.length; i++) {
            offsets[i] = pos;
            pos += contentLinesKeepends[i].length() + 1; // +1 for \n
        }
        offsets[contentLinesKeepends.length] = pos;

        List<String> strippedOld = new ArrayList<>();
        for (String line : oldLines) {
            strippedOld.add(normalizeQuotes
                    ? normalizeQuotes(line.strip())
                    : line.strip());
        }

        List<MatchSpan> matches = new ArrayList<>();
        int windowSize = strippedOld.size();
        for (int i = 0; i <= contentLines.length - windowSize; i++) {
            boolean match = true;
            for (int j = 0; j < windowSize; j++) {
                String cl = normalizeQuotes
                        ? normalizeQuotes(contentLines[i + j].strip())
                        : contentLines[i + j].strip();
                if (!cl.equals(strippedOld.get(j))) {
                    match = false;
                    break;
                }
            }
            if (!match) continue;

            int matchStart = offsets[i];
            int matchEnd = offsets[i + windowSize];
            // 如果最后一个窗口行以 \n 结尾，移除尾部 \n
            if (matchEnd > matchStart
                    && matchEnd <= content.length()
                    && content.charAt(matchEnd - 1) == '\n') {
                matchEnd--;
            }
            if (matchEnd > content.length()) matchEnd = content.length();
            matches.add(new MatchSpan(matchStart, matchEnd,
                    content.substring(matchStart, matchEnd), i + 1));
        }
        return matches;
    }

    /**
     * 策略 4：全文引号标准化后精确匹配。
     *
     * @param content 文件内容
     * @param search  搜索文本
     * @return 匹配列表
     */
    // 对标 Python _find_quote_matches()
    static List<MatchSpan> findQuoteMatches(String content, String search) {
        String normContent = normalizeQuotes(content);
        String normSearch = normalizeQuotes(search);
        List<MatchSpan> matches = new ArrayList<>();
        int start = 0;
        while (true) {
            int idx = normContent.indexOf(normSearch, start);
            if (idx == -1) break;
            int line = (int) content.substring(0, idx).chars()
                    .filter(c -> c == '\n').count() + 1;
            int end = idx + search.length();
            if (end > content.length()) end = content.length();
            matches.add(new MatchSpan(idx, end,
                    content.substring(idx, end), line));
            start = idx + Math.max(1, normSearch.length());
        }
        return matches;
    }

    // ==================== 诊断工具 ====================

    /**
     * 折叠行内多余空白。
     *
     * @param text 原始文本
     * @return 每个单词间单空格文本
     */
    // 对标 Python _collapse_internal_whitespace()
    static String collapseInternalWhitespace(String text) {
        return Arrays.stream(text.split("\n", -1))
                .map(line -> line.trim().isEmpty() ? ""
                        : Arrays.stream(line.split("\\s+"))
                                .collect(Collectors.joining(" ")))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 诊断近匹配失败原因。
     *
     * @param oldText    搜索文本
     * @param actualText 实际最佳匹配文本
     * @return 诊断提示列表
     */
    // 对标 Python _diagnose_near_match()
    static List<String> diagnoseNearMatch(String oldText, String actualText) {
        List<String> hints = new ArrayList<>();
        if (oldText.toLowerCase().equals(actualText.toLowerCase())
                && !oldText.equals(actualText)) {
            hints.add("letter case differs");
        }
        if (collapseInternalWhitespace(oldText)
                .equals(collapseInternalWhitespace(actualText))
                && !oldText.equals(actualText)) {
            hints.add("whitespace differs");
        }
        String oldRstripped = oldText.replaceAll("\n+$", "");
        String actualRstripped = actualText.replaceAll("\n+$", "");
        if (oldRstripped.equals(actualRstripped) && !oldText.equals(actualText)) {
            hints.add("trailing newline differs");
        }
        if (normalizeQuotes(oldText).equals(normalizeQuotes(actualText))
                && !oldText.equals(actualText)) {
            hints.add("quote style differs");
        }
        return hints;
    }

    /**
     * 在文件内容中查找与 old_text 最接近的行窗口。
     *
     * @param oldText 搜索文本
     * @param content 文件内容
     * @return 最佳窗口结果
     */
    // 对标 Python _best_window()
    static BestWindow bestWindow(String oldText, String content) {
        String[] linesKeepends = content.split("\n", -1);
        // 重建 keepends 版本
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < linesKeepends.length; i++) {
            lines.add(i < linesKeepends.length - 1
                    ? linesKeepends[i] + "\n" : linesKeepends[i]);
        }
        // 移除末尾空行（由 split 产生）
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }

        String[] oldLinesKeepends = oldText.split("\n", -1);
        List<String> oldLines = new ArrayList<>();
        for (int i = 0; i < oldLinesKeepends.length; i++) {
            oldLines.add(i < oldLinesKeepends.length - 1
                    ? oldLinesKeepends[i] + "\n" : oldLinesKeepends[i]);
        }
        if (!oldLines.isEmpty()
                && oldLines.get(oldLines.size() - 1).isEmpty()
                && oldText.endsWith("\n")) {
            // keep the trailing empty
        } else if (!oldLines.isEmpty()
                && oldLines.get(oldLines.size() - 1).isEmpty()
                && !oldText.endsWith("\n")) {
            oldLines.remove(oldLines.size() - 1);
        }

        int window = Math.max(1, oldLines.size());
        double bestRatio = -1.0;
        int bestStart = 0;
        List<String> bestWindowLines = List.of();

        for (int i = 0; i <= Math.max(0, lines.size() - window); i++) {
            List<String> current = lines.subList(i, Math.min(i + window, lines.size()));
            double r = DiffUtil.ratio(oldLines, current);
            if (r > bestRatio) {
                bestRatio = r;
                bestStart = i;
                bestWindowLines = new ArrayList<>(current);
            }
        }

        String actualText = String.join("", bestWindowLines)
                .replace("\r\n", "\n").replaceAll("\n+$", "");
        String cleanedOld = oldText.replace("\r\n", "\n").replaceAll("\n+$", "");
        List<String> hints = diagnoseNearMatch(cleanedOld, actualText);
        return new BestWindow(bestRatio, bestStart, bestWindowLines, hints);
    }

    /**
     * 生成文件未找到错误消息，含 "Did you mean?" 建议。
     *
     * @param path 用户提供的路径
     * @param fp   解析后的路径
     * @return 错误消息
     */
    // 对标 Python EditFileTool._file_not_found_msg()
    private String fileNotFoundMsg(String path, Path fp) {
        List<String> parts = new ArrayList<>();
        parts.add("Error: File not found: " + path);
        Path parent = fp.getParent();
        if (parent != null && Files.isDirectory(parent)) {
            try (Stream<Path> siblings = Files.list(parent)) {
                List<String> names = siblings
                        .filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .collect(Collectors.toList());
                List<String> close = DiffUtil.getCloseMatches(
                        fp.getFileName().toString(), names, 3, 0.6);
                if (!close.isEmpty()) {
                    List<String> fullPaths = close.stream()
                            .map(n -> parent.resolve(n).toString())
                            .collect(Collectors.toList());
                    parts.add("Did you mean: "
                            + String.join(", ", fullPaths) + "?");
                }
            } catch (IOException ignored) {}
        }
        return String.join("\n", parts);
    }

    /**
     * 生成 old_text 未找到的错误消息，含最佳匹配窗口和 unified diff。
     *
     * @param oldText 搜索文本
     * @param content 文件内容
     * @param path    文件路径
     * @return 诊断错误消息
     */
    // 对标 Python EditFileTool._not_found_msg()
    static String notFoundMsg(String oldText, String content, String path) {
        BestWindow bw = bestWindow(oldText, content);
        if (bw.ratio() > 0.5) {
            String[] oldKeepends = oldText.split("\n", -1);
            List<String> oldLines = new ArrayList<>();
            for (int i = 0; i < oldKeepends.length; i++) {
                oldLines.add(i < oldKeepends.length - 1
                        ? oldKeepends[i] + "\n" : oldKeepends[i]);
            }
            String diff = DiffUtil.unifiedDiff(oldLines, bw.lines(),
                    "old_text (provided)",
                    path + " (actual, line " + (bw.start() + 1) + ")");
            String hintText = "";
            if (!bw.hints().isEmpty()) {
                hintText = "\nPossible cause: "
                        + String.join(", ", bw.hints()) + ".";
            }
            return String.format(
                    "Error: old_text not found in %s.%s\nBest match "
                            + "(%.0f%% similar) at line %d:\n%s",
                    path, hintText,
                    bw.ratio() * 100, bw.start() + 1, diff);
        }
        if (!bw.hints().isEmpty()) {
            return "Error: old_text not found in " + path + ". "
                    + "Possible cause: "
                    + String.join(", ", bw.hints()) + ". "
                    + "Copy the exact text from read_file and try again.";
        }
        return "Error: old_text not found in " + path
                + ". No similar text found. Verify the file content.";
    }

    // ==================== 工具方法 ====================

    /**
     * 剥离每行尾部空白字符。
     *
     * @param text 原始文本
     * @return 处理后文本
     */
    // 对标 Python EditFileTool._strip_trailing_ws()
    static String stripTrailingWs(String text) {
        return Arrays.stream(text.split("\n", -1))
                .map(line -> line.replaceAll("[ \t]+$", ""))
                .collect(Collectors.joining("\n"));
    }

    // ==================== 参数辅助 ====================

    private static String paramStr(Map<String, Object> params, String key) {
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
