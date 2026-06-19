package com.nanobot.agent.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 会话，存储消息历史和元数据。对应 Python Session 类。
 *
 * <p>职责：消息增删、历史回放（含 token 预算裁剪）、消息固化/截断、
 * assistant 回复清洗（去除时间戳前缀、图片占位符、tool_call 回声）、
 * 文件数量上限裁剪。</p>
 */
public class Session {

    /** 清洗 assistant 回放文本中的 [Message Time: ...] 前缀 */
    private static final Pattern MESSAGE_TIME_PREFIX = Pattern.compile("^\\[Message Time: [^\\]]+\\]\n?");
    /** 清洗 assistant 回放文本中的本地图片占位符，如 [image: /path/to/img] */
    private static final Pattern LOCAL_IMAGE_BREADCRUMB = Pattern.compile("^\\[image: (?:/|~)[^\\]]+\\]\\s*$");
    /** 清洗 assistant 回放文本中的 tool_call 回声，如 generate_image(...) */
    private static final Pattern TOOL_CALL_ECHO = Pattern.compile("^\\s*(?:generate_image|message)\\([^)]*\\)\\s*$");

    private final String key;
    private final List<Map<String, Object>> messages;
    private final Map<String, Object> metadata;
    private final Instant createdAt;
    private Instant updatedAt;
    /** 已 consolidate 的消息数量（前面的消息已经被压缩成摘要） */
    private int lastConsolidated;

    public Session(String key) {
        this.key = key;
        this.messages = new ArrayList<>();
        this.metadata = new LinkedHashMap<>();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.lastConsolidated = 0;
    }

    // -- 访问器 --

    public String key() { return key; }
    public List<Map<String, Object>> messages() { return messages; }
    public Map<String, Object> metadata() { return metadata; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public int lastConsolidated() { return lastConsolidated; }

    /** 设置已 consolidate 数量，自动钳位到 [0, messages.size()] */
    public void setLastConsolidated(int value) {
        if (value < 0 || value > messages.size()) {
            this.lastConsolidated = 0;
        } else {
            this.lastConsolidated = value;
        }
    }

    // -- 添加消息 --

    /** 添加一条消息（无额外字段） */
    public void addMessage(String role, String content) {
        addMessage(role, content, null);
    }

    /** 添加一条消息，附带额外字段（如 tool_calls、media 等） */
    public void addMessage(String role, String content, Map<String, Object> extra) {
        var msg = new LinkedHashMap<String, Object>();
        msg.put("role", role);
        msg.put("content", content);
        msg.put("timestamp", Instant.now().toString());
        if (extra != null) {
            msg.putAll(extra);
        }
        messages.add(msg);
        updatedAt = Instant.now();
    }

    // -- 获取历史 --

    /**
     * 获取会话历史，供 LLM 上下文使用。
     *
     * <p>处理流程：取最近 N 条未 consolidate 的消息 → 对齐 user turn →
     * 丢弃开头孤儿 tool 结果 → 清洗 assistant 文本 → 合成图片/CLI/MCP 面
     * 包屑 → 按 token 预算从尾部裁剪。</p>
     *
     * @param maxMessages 最大消息数（0 表示默认 120）
     * @param maxTokens   最大 token 数（0 表示不限制）
     * @param includeTimestamps 是否在 user 消息前插入时间戳
     * @return 清洗后的消息列表，时间从旧到新
     */
    public List<Map<String, Object>> getHistory(int maxMessages, int maxTokens, boolean includeTimestamps) {
        int effectiveLimit = maxMessages > 0 ? maxMessages : 120;
        var unconsolidated = messages.subList(lastConsolidated, messages.size());
        int totalUnconsolidated = unconsolidated.size();
        int sliceStart = Math.max(0, totalUnconsolidated - effectiveLimit);
        var sliced = new ArrayList<>(unconsolidated.subList(sliceStart, totalUnconsolidated));

        // 对齐到第一个 user 消息（允许前面有一个 _channel_delivery）
        for (int i = 0; i < sliced.size(); i++) {
            if ("user".equals(sliced.get(i).get("role"))) {
                if (i > 0 && sliced.get(i - 1).get("_channel_delivery") != null) {
                    sliced = new ArrayList<>(sliced.subList(i - 1, sliced.size()));
                } else {
                    sliced = new ArrayList<>(sliced.subList(i, sliced.size()));
                }
                break;
            }
        }

        // 丢弃开头的孤儿 tool 结果（没有对应 assistant tool_calls 声明）
        int start = findLegalMessageStart(sliced);
        if (start > 0) {
            sliced = new ArrayList<>(sliced.subList(start, sliced.size()));
        }

        // 构建输出：跳过命令消息、清洗 assistant 文本、合成附件面包装屑
        var out = new ArrayList<Map<String, Object>>();
        for (var msg : sliced) {
            if (msg.get("_command") != null) {
                continue;
            }
            var content = msg.get("content");
            var role = msg.get("role");

            if ("assistant".equals(role) && content instanceof String s) {
                content = sanitizeAssistantReplayText(s);
            }

            // 合成图片面包装屑
            var media = msg.get("media");
            if ("user".equals(role) && media instanceof List<?> ml && !ml.isEmpty() && content instanceof String cs) {
                var crumbs = new StringBuilder();
                for (var p : ml) {
                    if (p instanceof String ps && !ps.isEmpty()) {
                        if (!crumbs.isEmpty()) crumbs.append("\n");
                        crumbs.append(imagePlaceholderText(ps));
                    }
                }
                if (!crumbs.isEmpty()) {
                    content = cs.isEmpty() ? crumbs.toString() : cs + "\n" + crumbs;
                }
            }

            // 合成 CLI app 面包装屑
            var cliApps = msg.get("cli_apps");
            if ("user".equals(role) && cliApps instanceof List<?> cl && !cl.isEmpty() && content instanceof String cs) {
                var lines = new ArrayList<String>();
                int count = 0;
                for (var item : cl) {
                    if (count >= 8) break;
                    if (item instanceof Map<?, ?> im) {
                        var name = strVal(im.get("name")).trim().toLowerCase();
                        if (name.isEmpty()) continue;
                        var entryPoint = strVal(im.get("entry_point"), "unknown").trim();
                        if (entryPoint.isEmpty()) entryPoint = "unknown";
                        lines.add("[CLI App Attachment: @" + name + "; tool=run_cli_app; entry_point="
                                + entryPoint + "; skill=skills/cli-app-" + name + "/SKILL.md]");
                    }
                }
                if (!lines.isEmpty()) {
                    content = cs.isEmpty() ? String.join("\n", lines) : cs + "\n" + String.join("\n", lines);
                }
            }

            // 合成 MCP preset 面包装屑
            var mcpPresets = msg.get("mcp_presets");
            if ("user".equals(role) && mcpPresets instanceof List<?> mpl && !mpl.isEmpty() && content instanceof String cs) {
                var lines = new ArrayList<String>();
                int count = 0;
                for (var item : mpl) {
                    if (count >= 8) break;
                    if (item instanceof Map<?, ?> im) {
                        var name = strVal(im.get("name")).trim().toLowerCase();
                        if (name.isEmpty()) continue;
                        var transport = strVal(im.get("transport"), "mcp").trim();
                        if (transport.isEmpty()) transport = "mcp";
                        lines.add("[MCP Preset Attachment: @" + name + "; tool_prefix=mcp_" + name + "_; transport="
                                + transport + "]");
                    }
                }
                if (!lines.isEmpty()) {
                    content = cs.isEmpty() ? String.join("\n", lines) : cs + "\n" + String.join("\n", lines);
                }
            }

            // 在 user 消息前插入时间戳
            if (includeTimestamps) {
                content = annotateMessageTime(msg, content);
            }

            // 跳过无 tool_calls / reasoning / thinking_blocks 的空 assistant 消息
            if ("assistant".equals(role) && content instanceof String cs && cs.trim().isEmpty()) {
                if (msg.get("tool_calls") == null && msg.get("reasoning_content") == null
                        && msg.get("thinking_blocks") == null) {
                    continue;
                }
            }

            var entry = new LinkedHashMap<String, Object>();
            entry.put("role", msg.get("role"));
            entry.put("content", content);
            for (var key : List.of("tool_calls", "tool_call_id", "name", "reasoning_content", "thinking_blocks")) {
                if (msg.containsKey(key)) {
                    entry.put(key, msg.get(key));
                }
            }
            out.add(entry);
        }

        // 按 token 预算从尾部裁剪
        if (maxTokens > 0 && !out.isEmpty()) {
            var kept = new ArrayList<Map<String, Object>>();
            int used = 0;
            for (int i = out.size() - 1; i >= 0; i--) {
                int tokens = estimateMessageTokens(out.get(i));
                if (!kept.isEmpty() && used + tokens > maxTokens) {
                    break;
                }
                kept.addFirst(out.get(i));
                used += tokens;
            }

            // 对齐到第一个 user 消息
            int firstUser = -1;
            for (int i = 0; i < kept.size(); i++) {
                if ("user".equals(kept.get(i).get("role"))) {
                    firstUser = i;
                    break;
                }
            }
            if (firstUser >= 0) {
                kept = new ArrayList<>(kept.subList(firstUser, kept.size()));
            } else {
                // 从原始输出中恢复最近的 user 消息
                int recoveredUser = -1;
                for (int i = out.size() - 1; i >= 0; i--) {
                    if ("user".equals(out.get(i).get("role"))) {
                        recoveredUser = i;
                        break;
                    }
                }
                if (recoveredUser >= 0) {
                    kept = new ArrayList<>(out.subList(recoveredUser, out.size()));
                }
            }

            // 确保不以孤儿 tool 结果开头
            int legalStart = findLegalMessageStart(kept);
            if (legalStart > 0) {
                kept = new ArrayList<>(kept.subList(legalStart, kept.size()));
            }
            out = kept;
        }

        return out;
    }

    // -- 清空会话 --

    /** 清空所有消息和元数据，重置 consolidate 计数 */
    public void clear() {
        messages.clear();
        lastConsolidated = 0;
        updatedAt = Instant.now();
        metadata.remove("_last_summary");
    }

    // -- 保留最近合法后缀 --

    /**
     * 保留最近的合法消息后缀，返回被丢弃的消息。
     *
     * <p>与 getHistory 对齐：从 user turn 开始、丢弃孤儿 tool 结果、
     * 硬上限裁剪。</p>
     *
     * @param maxMessages 保留的最大消息数（0 表示清空全部）
     * @return Map 包含 "dropped"（丢弃的消息）和 "alreadyConsolidated"（已压缩的丢弃数）
     */
    public Map<String, Object> retainRecentLegalSuffix(int maxMessages) {
        if (maxMessages <= 0) {
            var dropped = List.<Map<String, Object>>copyOf(messages);
            int lc = lastConsolidated;
            clear();
            return Map.of("dropped", dropped, "alreadyConsolidated", Math.min(lc, dropped.size()));
        }
        if (messages.size() <= maxMessages) {
            return Map.of("dropped", List.of(), "alreadyConsolidated", 0);
        }

        var original = List.<Map<String, Object>>copyOf(messages);
        int beforeLc = lastConsolidated;

        var retained = new ArrayList<>(messages.subList(messages.size() - maxMessages, messages.size()));

        // 对齐到第一个 user turn
        int firstUser = -1;
        for (int i = 0; i < retained.size(); i++) {
            if ("user".equals(retained.get(i).get("role"))) {
                firstUser = i;
                break;
            }
        }
        if (firstUser >= 0) {
            retained = new ArrayList<>(retained.subList(firstUser, retained.size()));
        } else {
            // 锚定到完整会话中最近的 user
            int latestUser = -1;
            for (int i = messages.size() - 1; i >= 0; i--) {
                if ("user".equals(messages.get(i).get("role"))) {
                    latestUser = i;
                    break;
                }
            }
            if (latestUser >= 0) {
                int end = Math.min(latestUser + maxMessages, messages.size());
                retained = new ArrayList<>(messages.subList(latestUser, end));
            }
        }

        // 丢弃开头孤儿 tool 结果
        int legalStart = findLegalMessageStart(retained);
        if (legalStart > 0) {
            retained = new ArrayList<>(retained.subList(legalStart, retained.size()));
        }

        // 硬上限
        if (retained.size() > maxMessages) {
            retained = new ArrayList<>(retained.subList(retained.size() - maxMessages, retained.size()));
            int ls = findLegalMessageStart(retained);
            if (ls > 0) {
                retained = new ArrayList<>(retained.subList(ls, retained.size()));
            }
        }

        // 用 identity hash 计算被丢弃的消息
        var retainedIds = new java.util.HashSet<Integer>();
        for (var m : retained) {
            retainedIds.add(System.identityHashCode(m));
        }
        var dropped = new ArrayList<Map<String, Object>>();
        for (var m : original) {
            if (!retainedIds.contains(System.identityHashCode(m))) {
                dropped.add(m);
            }
        }

        int alreadyConsolidated = 0;
        for (int i = 0; i < original.size(); i++) {
            if (i < beforeLc && !retainedIds.contains(System.identityHashCode(original.get(i)))) {
                alreadyConsolidated++;
            }
        }

        int newLc = 0;
        for (int i = 0; i < original.size(); i++) {
            if (i < beforeLc && retainedIds.contains(System.identityHashCode(original.get(i)))) {
                newLc++;
            }
        }

        messages.clear();
        messages.addAll(retained);
        lastConsolidated = newLc;
        updatedAt = Instant.now();
        return Map.of("dropped", dropped, "alreadyConsolidated", alreadyConsolidated);
    }

    // -- 文件数量上限裁剪 --

    /**
     * 消息数量超过上限时执行裁剪，将超出的已 consolidate 部分移交 onArchive 回调归档。
     *
     * @param onArchive 归档回调（如写入 raw_archive）
     * @param limit     消息数量上限
     */
    public void enforceFileCap(Consumer<List<Map<String, Object>>> onArchive, int limit) {
        if (limit <= 0 || messages.size() <= limit) {
            return;
        }
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) retainRecentLegalSuffix(limit);
        @SuppressWarnings("unchecked")
        var dropped = (List<Map<String, Object>>) result.get("dropped");
        int alreadyConsolidated = (int) result.get("alreadyConsolidated");
        if (dropped.isEmpty()) {
            return;
        }
        var archiveChunk = new ArrayList<>(dropped.subList(alreadyConsolidated, dropped.size()));
        if (!archiveChunk.isEmpty() && onArchive != null) {
            onArchive.accept(archiveChunk);
        }
    }

    // -- 截断消息 --

    /** 从头截断消息到指定数量 */
    public void truncateMessages(int maxMessages) {
        while (messages.size() > maxMessages) {
            messages.remove(0);
        }
        if (lastConsolidated > messages.size()) {
            lastConsolidated = messages.size();
        }
    }

    // -- 静态工具方法 --

    /**
     * 找到消息列表中第一个"合法"消息的索引。
     *
     * <p>跳过开头的孤儿 tool 结果（没有对应 assistant tool_calls 声明的 tool 消息）。
     * 只检查从索引 0 开始的连续 tool 孤儿。</p>
     */
    public static int findLegalMessageStart(List<Map<String, Object>> messages) {
        var declared = new java.util.HashSet<String>();
        int start = 0;
        for (int i = 0; i < messages.size(); i++) {
            var msg = messages.get(i);
            var role = msg.get("role");
            if ("assistant".equals(role)) {
                var toolCalls = msg.get("tool_calls");
                if (toolCalls instanceof List<?> tcList) {
                    for (var tc : tcList) {
                        if (tc instanceof Map<?, ?> tcm) {
                            var id = tcm.get("id");
                            if (id != null) {
                                declared.add(String.valueOf(id));
                            }
                        }
                    }
                }
            } else if ("tool".equals(role)) {
                var tid = msg.get("tool_call_id");
                if (tid != null && !declared.contains(String.valueOf(tid))) {
                    start = i + 1;
                    declared.clear();
                }
            }
        }
        return start;
    }

    /**
     * 估算单条消息的 token 数量。
     *
     * <p>简单估算：拼接消息中所有文本内容（content、name、tool_call_id、
     * tool_calls JSON），按 ~4 字符/token 计算。下限为 1。</p>
     */
    public static int estimateMessageTokens(Map<String, Object> message) {
        var parts = new ArrayList<String>();
        var content = message.get("content");
        if (content instanceof String s) {
            parts.add(s);
        } else if (content instanceof List<?> cl) {
            for (var part : cl) {
                if (part instanceof Map<?, ?> pm && "text".equals(pm.get("type"))) {
                    var text = pm.get("text");
                    if (text instanceof String ts && !ts.isEmpty()) {
                        parts.add(ts);
                    }
                } else {
                    parts.add(String.valueOf(part));
                }
            }
        } else if (content != null) {
            parts.add(String.valueOf(content));
        }
        for (var key : List.of("name", "tool_call_id")) {
            var value = message.get(key);
            if (value instanceof String sv && !sv.isEmpty()) {
                parts.add(sv);
            }
        }
        var toolCalls = message.get("tool_calls");
        if (toolCalls instanceof List<?> tcl && !tcl.isEmpty()) {
            parts.add(tcl.toString());
        }
        int total = String.join("", parts).length();
        return Math.max(1, total / 4);
    }

    /**
     * 清洗 assistant 回放文本：去除时间戳前缀、本地图片占位符、tool_call 回声。
     * 对应 Python sanitize_assistant_replay_text。
     */
    static String sanitizeAssistantReplayText(String content) {
        content = MESSAGE_TIME_PREFIX.matcher(content).replaceFirst("");
        var lines = content.split("\n");
        var kept = new ArrayList<String>();
        for (var line : lines) {
            if (!LOCAL_IMAGE_BREADCRUMB.matcher(line).matches()
                    && !TOOL_CALL_ECHO.matcher(line).matches()) {
                kept.add(line);
            }
        }
        return String.join("\n", kept).trim();
    }

    /** 在 user 消息内容前插入 [Message Time: ...] 时间戳标注 */
    static Object annotateMessageTime(Map<String, Object> msg, Object content) {
        var timestamp = msg.get("timestamp");
        if (timestamp == null || !(content instanceof String s)) {
            return content;
        }
        if (!"user".equals(msg.get("role"))) {
            return content;
        }
        return "[Message Time: " + timestamp + "]\n" + s;
    }

    /** 生成图片占位符文本，如 [image: /path/to/img.png] */
    static String imagePlaceholderText(String path) {
        return path != null && !path.isEmpty() ? "[image: " + path + "]" : "[image]";
    }

    private static String strVal(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String strVal(Object value, String defaultVal) {
        return value == null ? defaultVal : String.valueOf(value);
    }
}
