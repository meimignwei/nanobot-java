package com.nanobot.agent;

import com.nanobot.providers.LLMProvider;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AgentRunner 静态工具方法集——消息构建、token 估算、上下文治理、模板渲染。
 *
 * <p>对标 Python {@code nanobot/agent/runner.py} 中的模块级函数和常量。
 */
public final class RuntimeUtils {

    private RuntimeUtils() {}

    /** 对标 Python EMPTY_FINAL_RESPONSE_MESSAGE = "[No response generated]"。 */
    public static final String EMPTY_FINAL_RESPONSE_MESSAGE = "[No response generated]";

    private static final String BACKFILL_CONTENT = "[Tool result unavailable — call was interrupted or lost]";
    private static final int SNIP_SAFETY_BUFFER = 1024;
    private static final Pattern THINK_PATTERN = Pattern.compile(
            "<think[^>]*>[\\s\\S]*?</think>\\s*", Pattern.CASE_INSENSITIVE);

    // ==================== message builders ====================

    /**
     * 构建 assistant 角色消息。
     * 对标 Python runner.py build_assistant_message()。
     *
     * @param content          文本内容
     * @param toolCalls        工具调用列表
     * @param reasoningContent 推理内容
     * @param thinkingBlocks   thinking 块
     * @return assistant 消息 Map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> buildAssistantMessage(
            String content, Object toolCalls, String reasoningContent,
            List<Map<String, Object>> thinkingBlocks) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");
        msg.put("content", content != null ? content : "");
        if (toolCalls instanceof List<?> l && !l.isEmpty()) {
            msg.put("tool_calls", new ArrayList<>((List<Map<String, Object>>) l));
        }
        if (reasoningContent != null && !reasoningContent.isEmpty()) {
            msg.put("reasoning_content", reasoningContent);
        }
        if (thinkingBlocks != null && !thinkingBlocks.isEmpty()) {
            msg.put("thinking_blocks", new ArrayList<>(thinkingBlocks));
        }
        return msg;
    }

    /** 无 tool_calls/thinking 的便捷构造器。 */
    public static Map<String, Object> buildAssistantMessage(String content, String reasoningContent,
                                                             List<Map<String, Object>> thinkingBlocks) {
        return buildAssistantMessage(content, null, reasoningContent, thinkingBlocks);
    }

    /** 纯文本便捷构造器。 */
    public static Map<String, Object> buildAssistantMessage(String content) {
        return buildAssistantMessage(content, null, null, null);
    }

    /**
     * 构建 length recovery 消息（模型因 length 截断时追加的续写提示）。
     * 对标 Python runner.py build_length_recovery_message()。
     */
    public static Map<String, Object> buildLengthRecoveryMessage() {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("content", "Continue exactly where you left off. Do not repeat anything you already said.");
        return msg;
    }

    /**
     * 构建 finalization retry 消息（空响应重试时追加）。
     * 对标 Python runner.py build_finalization_retry_message()。
     */
    public static Map<String, Object> buildFinalizationRetryMessage() {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("content", "Please provide your final response. What would you like to tell the user?");
        return msg;
    }

    /**
     * 构建预算耗尽 finalization 消息。
     * 对标 Python runner.py build_budget_exhausted_finalization_message()。
     */
    public static Map<String, Object> buildBudgetExhaustedFinalizationMessage() {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("content", "You have reached the maximum number of iterations. "
                + "Please provide your final response to the user summarizing what you have accomplished "
                + "and any remaining work. Do NOT make any more tool calls.");
        return msg;
    }

    /**
     * 构建 goal continuation 消息。
     * 对标 Python runner.py build_goal_continue_message()。
     */
    public static Map<String, Object> buildGoalContinueMessage(String goalContinueMessage) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("content", goalContinueMessage != null ? goalContinueMessage
                : "Continue working toward your goal. What is the next step?");
        return msg;
    }

    // ==================== content helpers ====================

    /**
     * 判断文本是否为空（null / 空白 / 等同于 EMPTY_FINAL_RESPONSE_MESSAGE）。
     * 对标 Python runner.py is_blank_text()。
     */
    public static boolean isBlankText(String text) {
        if (text == null || text.isBlank()) return true;
        String stripped = text.strip();
        return stripped.isEmpty() || EMPTY_FINAL_RESPONSE_MESSAGE.equals(stripped);
    }

    /**
     * Strip think tags from text.
     * 对标 Python strip_think.strip()。
     */
    public static String stripThink(String text) {
        if (text == null || text.isEmpty()) return text;
        return THINK_PATTERN.matcher(text).replaceAll("").strip();
    }

    /**
     * 确保 tool result 非空——空结果替换为占位符。
     * 对标 Python runner.py _ensure_nonempty_tool_result()。
     */
    public static String ensureNonemptyToolResult(String toolName, Object result) {
        if (result == null) return "(empty)";
        if (result instanceof String s) {
            return s.isEmpty() ? "(empty)" : s;
        }
        String str = result.toString();
        return str.isEmpty() ? "(empty)" : str;
    }

    /**
     * 将超长 tool result 持久化到磁盘文件，返回文件引用。
     * 对标 Python runner.py _maybe_persist_tool_result()。
     *
     * @param workspace   工作目录
     * @param sessionKey  会话键
     * @param toolCallId  工具调用 ID
     * @param result      工具结果
     * @param maxChars    最大字符数
     * @return 原始结果或文件引用路径
     */
    public static Object maybePersistToolResult(Path workspace, String sessionKey,
                                                  String toolCallId, Object result,
                                                  int maxChars) {
        if (!(result instanceof String str) || str.length() <= maxChars) {
            return result;
        }
        try {
            Path toolResultsDir = workspace.resolve(".nanobot").resolve("tool_results");
            Files.createDirectories(toolResultsDir);
            String filename = "result_" + toolCallId + ".txt";
            Path file = toolResultsDir.resolve(filename);
            Files.writeString(file, str);
            return "[Tool result saved to .nanobot/tool_results/" + filename
                    + " (" + str.length() + " chars)]";
        } catch (Exception e) {
            return result;
        }
    }

    /** 截断文本到指定字符数。 */
    public static String truncateText(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n... (truncated, " + text.length() + " total chars)";
    }

    // ==================== token estimation ====================

    /**
     * 估算单条消息的 token 数。
     * 对标 Python runner.py estimate_message_tokens()。
     */
    @SuppressWarnings("unchecked")
    public static int estimateMessageTokens(Map<String, Object> msg) {
        StringBuilder payload = new StringBuilder();
        Object content = msg.get("content");
        if (content instanceof String s) {
            payload.append(s);
        } else if (content instanceof List<?> blocks) {
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> bm) {
                    Map<String, Object> b = (Map<String, Object>) bm;
                    if (b.get("text") instanceof String t) {
                        payload.append(t);
                    }
                } else if (block != null) {
                    payload.append(block.toString());
                }
            }
        } else if (content != null) {
            payload.append(content.toString());
        }

        // name / tool_call_id
        if (msg.get("name") instanceof String n) payload.append(n);
        if (msg.get("tool_call_id") instanceof String t) payload.append(t);

        // tool_calls JSON
        if (msg.get("tool_calls") instanceof List<?> tcs) {
            payload.append(tcs.toString());
        }

        // reasoning_content
        if (msg.get("reasoning_content") instanceof String rc) {
            payload.append(rc);
        }

        String text = payload.toString();
        if (text.isEmpty()) return 4; // MIN_TOKENS
        return tokensFromChars(text);
    }

    /**
     * 估算整个 prompt chain 的 token 数。
     * 对标 Python runner.py estimate_prompt_tokens_chain()。
     */
    public static int estimatePromptTokensChain(LLMProvider provider, String model,
                                                  List<Map<String, Object>> messages,
                                                  List<Map<String, Object>> tools) {
        int total = 0;
        for (Map<String, Object> m : messages) {
            total += estimateMessageTokens(m);
        }
        if (tools != null) {
            total += tools.size() * 100; // rough per-tool overhead
        }
        return total;
    }

    /** 无 provider 的便捷重载。 */
    public static int estimatePromptTokensChain(List<Map<String, Object>> messages,
                                                  List<Map<String, Object>> tools) {
        return estimatePromptTokensChain(null, null, messages, tools);
    }

    private static int tokensFromChars(String text) {
        int cjk = 0, ascii = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) cjk++;
            else if (c <= 127) ascii++;
            else cjk++;
        }
        return cjk * 2 + ascii / 4 + 4; // +4 overhead
    }

    // ==================== context governance helpers ====================

    /**
     * 找到消息列表中第一个合法消息的下标——其 tool 结果有匹配的 assistant tool_calls。
     * 遍历消息，跟踪 assistant 声明的 tool_call ID，遇到 tool 消息的 tool_call_id
     * 未在已声明集合中时重置起点并清空声明集合。
     * 对标 Python helpers.py find_legal_message_start()。
     */
    @SuppressWarnings("unchecked")
    public static int findLegalMessageStart(List<Map<String, Object>> messages) {
        Set<String> declared = new HashSet<>();
        int start = 0;
        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            String role = (String) msg.get("role");
            if ("assistant".equals(role)) {
                List<Map<String, Object>> tcs = (List<Map<String, Object>>) msg.get("tool_calls");
                if (tcs != null) {
                    for (Map<String, Object> tc : tcs) {
                        if (tc != null && tc.get("id") != null) {
                            declared.add(String.valueOf(tc.get("id")));
                        }
                    }
                }
            } else if ("tool".equals(role)) {
                Object tid = msg.get("tool_call_id");
                if (tid != null && !declared.contains(String.valueOf(tid))) {
                    start = i + 1;
                    declared.clear();
                }
            }
        }
        return start;
    }

    // ==================== repeated error detection ====================

    /**
     * 检测重复的外部查找调用并返回错误消息。
     * 对标 Python runner.py _repeated_external_lookup_error()。
     */
    public static String repeatedExternalLookupError(String toolName,
                                                       Map<String, Object> arguments,
                                                       Map<String, Integer> countMap) {
        String key = toolName + ":" + (arguments != null ? arguments.toString() : "");
        int count = countMap.getOrDefault(key, 0) + 1;
        countMap.put(key, count);
        if (count >= 3) {
            return "Error: Repeated identical external lookup detected ("
                    + count + " times). Please use a different approach.";
        }
        return null;
    }

    /**
     * 检测重复的工作区违规并返回升级错误消息。
     * 对标 Python runner.py _repeated_workspace_violation_error()。
     */
    public static String repeatedWorkspaceViolationError(String toolName,
                                                           Map<String, Object> arguments,
                                                           Map<String, Integer> countMap) {
        String key = "violation:" + toolName + ":" + (arguments != null ? arguments.toString() : "");
        int count = countMap.getOrDefault(key, 0) + 1;
        countMap.put(key, count);
        if (count >= 3) {
            return "Error: Repeated workspace access violation detected ("
                    + count + " times). The operation is blocked. "
                    + "Please work within the allowed workspace or ask the user "
                    + "to configure workspace access.";
        }
        return null;
    }

    // ==================== template ====================

    /** 模板文件缺失时的降级文本。 */
    private static final String TEMPLATE_MISSING_FALLBACK = "";

    /**
     * 从 classpath 加载 Jinja2 风格模板并渲染变量。
     * 支持：{{ var }}、{% if %}...{% elif %}...{% else %}...{% endif %}、
     * {% include 'path' %}、{% raw %}...{% endraw %}、{% for %}...{% endfor %}。
     * 对标 Python render_template(name, **kwargs)。
     */
    public static String renderTemplate(String templatePath, Map<String, Object> vars) {
        try {
            String template = loadTemplateFile("templates/" + templatePath);
            if (template == null) return TEMPLATE_MISSING_FALLBACK;
            return renderJinja2(template, vars != null ? vars : Map.of());
        } catch (Exception e) {
            return TEMPLATE_MISSING_FALLBACK;
        }
    }

    /** 无变量的便捷重载。 */
    public static String renderTemplate(String templatePath) {
        return renderTemplate(templatePath, Map.of());
    }

    // ---- template file I/O ----

    /** 从 classpath 或文件系统加载模板文本。 */
    private static String loadTemplateFile(String path) {
        // Try classpath first
        try (InputStream is = RuntimeUtils.class.getClassLoader().getResourceAsStream(path)) {
            if (is != null) {
                return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                        .lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception ignored) {}
        // Try filesystem
        try {
            Path p = Path.of(path);
            if (Files.isRegularFile(p)) return Files.readString(p);
        } catch (Exception ignored) {}
        return null;
    }

    // ---- Jinja2 subset engine ----

    /** 条件模式："var == 'val'"、"var != 'val'"、"var"（truthy）、"var or var2 == 'val'"。 */
    private static final Pattern IF_PATTERN = Pattern.compile(
            "\\{%\\s*if\\s+(.+?)\\s*%}");

    private static String renderJinja2(String template, Map<String, Object> vars) {
        // Phase 1: process raw blocks — protect content from further processing
        Map<String, String> rawBlocks = new LinkedHashMap<>();
        template = extractRawBlocks(template, rawBlocks);

        // Phase 2: process includes
        template = processIncludes(template);

        // Phase 3: process if/elif/else/endif blocks
        template = processConditionals(template, vars);

        // Phase 4: process for loops
        template = processForLoops(template, vars);

        // Phase 5: restore raw blocks
        template = restoreRawBlocks(template, rawBlocks);

        // Phase 6: substitute {{ var }}
        template = substituteVars(template, vars);

        return template;
    }

    // ---- raw blocks ----

    private static String extractRawBlocks(String template, Map<String, String> out) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            int start = template.indexOf("{% raw %}", i);
            if (start < 0) {
                result.append(template.substring(i));
                break;
            }
            int end = template.indexOf("{% endraw %}", start + 9);
            if (end < 0) {
                result.append(template.substring(i));
                break;
            }
            String placeholder = "___RAW_" + out.size() + "___";
            out.put(placeholder, template.substring(start + 9, end));
            result.append(template, i, start).append(placeholder);
            i = end + 12;
        }
        return result.toString();
    }

    private static String restoreRawBlocks(String template, Map<String, String> rawBlocks) {
        String result = template;
        for (Map.Entry<String, String> e : rawBlocks.entrySet()) {
            result = result.replace(e.getKey(), e.getValue());
        }
        return result;
    }

    // ---- includes ----

    private static String processIncludes(String template) {
        Matcher m = Pattern.compile("\\{%\\s*include\\s+'([^']+)'\\s*%}").matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String includePath = m.group(1);
            String included = loadTemplateFile("templates/" + includePath);
            if (included == null) included = "";
            m.appendReplacement(sb, Matcher.quoteReplacement(included));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---- conditionals ----

    private static String processConditionals(String template, Map<String, Object> vars) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            int ifStart = indexOfTag(template, "if ", i);
            if (ifStart < 0) { result.append(template.substring(i)); break; }

            result.append(template, i, ifStart);
            int tagEnd = template.indexOf("%}", ifStart + 4);
            if (tagEnd < 0) { result.append(template.substring(ifStart)); break; }
            String condition = template.substring(ifStart + 4, tagEnd).strip();
            // Skip "if " prefix
            if (condition.startsWith("if ")) condition = condition.substring(3).strip();
            i = tagEnd + 2;

            // Find matching endif; collect elif/else blocks
            List<int[]> branches = new ArrayList<>(); // {start, end, kind: 0=if, 1=elif, 2=else}
            branches.add(new int[]{i, -1, 0}); // if body
            int depth = 1;
            int scan = i;
            int branchStart = i;
            int branchKind = 0;
            while (depth > 0 && scan < template.length()) {
                int nextIf = indexOfTag(template, "if ", scan);
                int nextElif = indexOfTag(template, "elif ", scan);
                int nextElse = indexOfTag(template, "else", scan);
                int nextEndif = indexOfTag(template, "endif", scan);

                int nextKind = -1;
                int nextPos = Integer.MAX_VALUE;
                if (nextIf >= 0 && nextIf < nextPos) { nextPos = nextIf; nextKind = 0; }
                if (nextElif >= 0 && nextElif < nextPos) { nextPos = nextElif; nextKind = 1; }
                if (nextElse >= 0 && nextElse < nextPos) { nextPos = nextElse; nextKind = 2; }
                if (nextEndif >= 0 && nextEndif < nextPos) { nextPos = nextEndif; nextKind = 3; }
                if (nextKind < 0) break;

                if (nextKind == 0) depth++;
                else if (nextKind == 3) {
                    depth--;
                    if (depth == 0) {
                        int tagEndPos = template.indexOf("%}", nextPos + 5);
                        branches.get(branches.size() - 1)[1] = nextPos;
                        if (tagEndPos >= 0) nextPos = tagEndPos + 2;
                        else nextPos += 7;
                    }
                } else if (depth == 1) {
                    // elif or else at our level: close current branch, start new one
                    branches.get(branches.size() - 1)[1] = nextPos;
                    int tagEndPos = template.indexOf("%}", nextPos + 2);
                    int bodyStart = tagEndPos >= 0 ? tagEndPos + 2 : nextPos;
                    branches.add(new int[]{bodyStart, -1, nextKind == 1 ? 1 : 2});
                }
                scan = nextPos + 1;
            }
            if (depth > 0) { result.append(template.substring(i)); break; } // unclosed

            // Evaluate and emit
            boolean matched = false;
            for (int[] branch : branches) {
                if (branch[0] < 0 || branch[1] < 0 || branch[0] >= branch[1]) continue;
                if (branch[2] == 2) { // else
                    result.append(template, branch[0], branch[1]);
                    matched = true;
                    break;
                }
                // Extract elif condition
                String branchCond = condition;
                if (branch[2] == 1) {
                    int bs = indexOfTag(template, "elif ", branch[0] - 8);
                    int be = template.indexOf("%}", bs + 6);
                    branchCond = template.substring(bs + 6, be).strip();
                }
                if (evalCondition(branchCond, vars)) {
                    result.append(template, branch[0], branch[1]);
                    matched = true;
                    break;
                }
            }
            if (depth == 0) i = scan;
        }
        return result.toString();
    }

    private static int indexOfTag(String template, String tag, int from) {
        return template.indexOf("{% " + tag, from);
    }

    /** 求值简单条件表达式："var"（truthy）、"var == 'val'"、"var != 'val'"、"a or b"、"a and b"。 */
    private static boolean evalCondition(String cond, Map<String, Object> vars) {
        cond = cond.strip();
        // Handle 'or'
        String[] orParts = splitCondition(cond, " or ");
        for (String p : orParts) {
            if (evalSimpleCondition(p.strip(), vars)) return true;
        }
        return false;
    }

    private static String[] splitCondition(String cond, String sep) {
        List<String> parts = new ArrayList<>();
        int depth = 0, last = 0;
        for (int i = 0; i < cond.length(); i++) {
            char c = cond.charAt(i);
            if (c == '\'' || c == '"') { i = cond.indexOf(c, i + 1); if (i < 0) break; continue; }
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && cond.startsWith(sep, i)) {
                parts.add(cond.substring(last, i));
                last = i + sep.length();
                i += sep.length() - 1;
            }
        }
        parts.add(cond.substring(last));
        return parts.toArray(new String[0]);
    }

    private static boolean evalSimpleCondition(String cond, Map<String, Object> vars) {
        cond = cond.strip();
        // "and" chain — all must be true
        String[] andParts = splitCondition(cond, " and ");
        for (String p : andParts) {
            if (!evalAtomicCondition(p.strip(), vars)) return false;
        }
        return true;
    }

    private static boolean evalAtomicCondition(String cond, Map<String, Object> vars) {
        // var == 'val'
        int eq = cond.indexOf("==");
        if (eq >= 0) {
            String left = cond.substring(0, eq).strip();
            String right = stripQuotes(cond.substring(eq + 2).strip());
            Object val = vars.get(left);
            return right.equals(val instanceof String s ? s : String.valueOf(val));
        }
        // var != 'val'
        int ne = cond.indexOf("!=");
        if (ne >= 0) {
            String left = cond.substring(0, ne).strip();
            String right = stripQuotes(cond.substring(ne + 2).strip());
            Object val = vars.get(left);
            return !right.equals(val instanceof String s ? s : String.valueOf(val));
        }
        // var only (truthy check)
        Object val = vars.get(cond);
        if (val == null) return false;
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return !s.isEmpty();
        return true;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 &&
            ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\"")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    // ---- for loops ----

    @SuppressWarnings("unchecked")
    private static String processForLoops(String template, Map<String, Object> vars) {
        Pattern forPattern = Pattern.compile(
                "\\{%\\s*for\\s+(\\w+)\\s+in\\s+(\\w+)\\s*%}(.*?)\\{%\\s*endfor\\s*%}",
                Pattern.DOTALL);
        Matcher m = forPattern.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String varName = m.group(1);
            String listName = m.group(2);
            String body = m.group(3);
            Object listObj = vars.get(listName);
            StringBuilder replacement = new StringBuilder();
            if (listObj instanceof List<?> list) {
                for (Object item : list) {
                    Map<String, Object> iterVars = new LinkedHashMap<>(vars);
                    iterVars.put(varName, item);
                    replacement.append(renderJinja2(body, iterVars));
                }
            } else if (listObj instanceof Collection<?> c) {
                for (Object item : c) {
                    Map<String, Object> iterVars = new LinkedHashMap<>(vars);
                    iterVars.put(varName, item);
                    replacement.append(renderJinja2(body, iterVars));
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---- variable substitution ----

    private static String substituteVars(String template, Map<String, Object> vars) {
        Matcher m = Pattern.compile("\\{\\{\\s*(\\w+)\\s*}}").matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            Object val = vars.get(key);
            String replacement = val != null ? val.toString() : "";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ==================== deep copy ====================

    /**
     * 深拷贝消息列表（每层 Map 使用 LinkedHashMap）。
     * 对标 Python runner.py deep copy messages。
     */
    public static List<Map<String, Object>> deepCopyMessages(List<Map<String, Object>> original) {
        if (original == null) return new ArrayList<>();
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> m : original) {
            copy.add(new LinkedHashMap<>(m));
        }
        return copy;
    }
}
