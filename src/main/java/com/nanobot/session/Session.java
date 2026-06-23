package com.nanobot.session;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 会话数据对象，包含消息历史、元数据和去重偏移。
 *
 * <p>对标 Python {@code nanobot/session/manager.py Session}（第 100-388 行）。
 * 注意：<b>不能使用 Java record</b>——messages 列表和 metadata Map 是可变对象。
 *
 * <p>线程安全：messages 使用 CopyOnWriteArrayList，metadata 使用 synchronizedMap。
 */
public class Session {

    /** 文件存储最大消息数，对标 Python FILE_MAX_MESSAGES。 */
    static final int FILE_MAX_MESSAGES = 2000;

    /** 会话列表预览最大字符数，对标 Python _SESSION_PREVIEW_MAX_CHARS。 */
    static final int SESSION_PREVIEW_MAX_CHARS = 120;

    /** fork 时清除的易失性 metadata key 集，对标 Python _FORK_VOLATILE_METADATA_KEYS。 */
    static final Set<String> FORK_VOLATILE_METADATA_KEYS = Set.of(
            "goal_state", "pending_user_turn", "runtime_checkpoint",
            "thread_goal", "title", "title_user_edited"
    );

    // 对标 Python _MESSAGE_TIME_PREFIX_RE
    private static final Pattern MESSAGE_TIME_PREFIX_RE =
            Pattern.compile("^\\[Message Time: [^\\]]+\\]\n?");
    // 对标 Python _LOCAL_IMAGE_BREADCRUMB_RE
    private static final Pattern LOCAL_IMAGE_BREADCRUMB_RE =
            Pattern.compile("^\\[image: (?:/|~)[^\\]]+\\]\\s*$");
    // 对标 Python _TOOL_CALL_ECHO_RE
    private static final Pattern TOOL_CALL_ECHO_RE =
            Pattern.compile("^\\s*(?:generate_image|message)\\([^)]*\\)\\s*$");

    private final String key;
    private final List<Map<String, Object>> messages;
    private volatile Instant createdAt;
    private volatile Instant updatedAt;
    private final Map<String, Object> metadata;
    private volatile int lastConsolidated;

    /** 构造新会话。对标 Python Session.__init__() */
    public Session(String key) {
        this.key = key;
        this.messages = new CopyOnWriteArrayList<>();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.metadata = Collections.synchronizedMap(new LinkedHashMap<>());
        this.lastConsolidated = 0;
    }

    /** 完整构造器（从磁盘恢复）。对标 Python Session 数据类构造 */
    public Session(String key, List<Map<String, Object>> messages,
                   Instant createdAt, Instant updatedAt,
                   Map<String, Object> metadata, int lastConsolidated) {
        this.key = key;
        this.messages = new CopyOnWriteArrayList<>(messages);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.metadata = Collections.synchronizedMap(
                new LinkedHashMap<>(metadata));
        // 对标 Python __post_init__
        int size = this.messages.size();
        this.lastConsolidated = (lastConsolidated >= 0
                && lastConsolidated <= size) ? lastConsolidated : 0;
    }

    // ==================== getters / setters ====================

    public String getKey() { return key; }

    public List<Map<String, Object>> getMessages() { return messages; }

    public Instant getCreatedAt() { return createdAt; }

    public void setCreatedAt(Instant t) { this.createdAt = t; }

    public Instant getUpdatedAt() { return updatedAt; }

    public void setUpdatedAt(Instant t) { this.updatedAt = t; }

    public Map<String, Object> getMetadata() { return metadata; }

    public int getLastConsolidated() { return lastConsolidated; }
    public void setLastConsolidated(int value) {
        int size = messages.size();
        this.lastConsolidated = (value >= 0 && value <= size) ? value : 0;
    }

    // ==================== 核心方法 ====================

    /**
     * 追加消息到会话，自动添加 timestamp 并更新 updatedAt。
     *
     * @param role    消息角色（user/assistant/system/tool）
     * @param content 消息内容
     * @param kwargs  额外 key-value 参数
     */
    // 对标 Python Session.add_message()
    public void addMessage(String role, Object content,
                           Map<String, Object> kwargs) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", role);
        msg.put("content", content);
        msg.put("timestamp", Instant.now().toString());
        if (kwargs != null) msg.putAll(kwargs);
        messages.add(msg);
        updatedAt = Instant.now();
    }

    /** 无额外参数的 addMessage。 */
    public void addMessage(String role, Object content) {
        addMessage(role, content, null);
    }

    /**
     * 获取未整合消息历史供 LLM 输入。
     * 先按 maxMessages 截断尾部，再按 maxTokens 裁剪。
     *
     * @param maxMessages       最大消息数（0 表示默认 120）
     * @param maxTokens         最大 token 数（0 表示不限制）
     * @param includeTimestamps 是否注入 [Message Time:] 前缀
     * @return 清理后的消息列表
     */
    // 对标 Python Session.get_history()
    public List<Map<String, Object>> getHistory(int maxMessages,
                                                 int maxTokens,
                                                 boolean includeTimestamps) {
        int lc = lastConsolidated;
        List<Map<String, Object>> unconsolidated =
                messages.subList(lc, messages.size());
        int max = maxMessages > 0 ? maxMessages : 120;
        List<Map<String, Object>> sliced = new ArrayList<>(
                unconsolidated.subList(
                        Math.max(0, unconsolidated.size() - max),
                        unconsolidated.size()));

        // 对齐到 user turn 起始
        int userStart = findFirstUserIndex(sliced);
        if (userStart >= 0) {
            if (userStart > 0 && sliced.get(userStart - 1)
                    .get("_channel_delivery") != null) {
                userStart--;
            }
            sliced = sliced.subList(userStart, sliced.size());
        }

        int legalStart = findLegalMessageStart(sliced);
        if (legalStart > 0) sliced = sliced.subList(legalStart, sliced.size());

        // 清理每条消息
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> message : sliced) {
            if (message.get("_command") != null) continue;

            Object rawContent = message.get("content");
            String content = rawContent instanceof String s ? s
                    : rawContent != null ? rawContent.toString() : "";
            String role = (String) message.get("role");

            if ("assistant".equals(role) && !content.isEmpty()) {
                content = sanitizeAssistantReplayText(content);
            }

            // 合成 media breadcrumbs
            content = appendMediaBreadcrumbs(message, role, content);
            // 合成 CLI app breadcrumbs
            content = appendCliAppBreadcrumbs(message, role, content);
            // 合成 MCP preset breadcrumbs
            content = appendMcpPresetBreadcrumbs(message, role, content);

            if (includeTimestamps && "user".equals(role)) {
                Object ts = message.get("timestamp");
                if (ts != null) {
                    content = "[Message Time: " + ts + "]\n" + content;
                }
            }

            // 跳过无内容的 assistant 消息
            if ("assistant".equals(role) && content.strip().isEmpty()) {
                if (!message.containsKey("tool_calls")
                        && !message.containsKey("reasoning_content")
                        && !message.containsKey("thinking_blocks")) {
                    continue;
                }
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", message.get("role"));
            entry.put("content", content);
            for (String k : List.of("tool_calls", "tool_call_id", "name",
                    "reasoning_content", "thinking_blocks")) {
                Object v = message.get(k);
                if (v != null) entry.put(k, v);
            }
            out.add(entry);
        }

        // Token 预算裁剪
        if (maxTokens > 0 && !out.isEmpty()) {
            out = trimByTokens(out, maxTokens, messages, lc);
        }
        return out;
    }

    /** 便捷方法。 */
    public List<Map<String, Object>> getHistory() {
        return getHistory(120, 0, false);
    }

    /**
     * 清空所有消息，重置元数据。
     */
    // 对标 Python Session.clear()
    public void clear() {
        messages.clear();
        lastConsolidated = 0;
        updatedAt = Instant.now();
        metadata.remove("_last_summary");
    }

    /**
     * 保留尾部合法后缀，硬上限裁剪旧消息。
     *
     * @param maxMessages 硬上限
     * @return (dropped, alreadyConsolidatedCount)
     */
    // 对标 Python Session.retain_recent_legal_suffix()
    public RetainResult retainRecentLegalSuffix(int maxMessages) {
        if (maxMessages <= 0) {
            int lc = lastConsolidated;
            List<Map<String, Object>> dropped = new ArrayList<>(messages);
            clear();
            return new RetainResult(dropped, Math.min(lc, dropped.size()));
        }
        if (messages.size() <= maxMessages) {
            return new RetainResult(List.of(), 0);
        }

        List<Map<String, Object>> original = new ArrayList<>(messages);
        int beforeLc = lastConsolidated;

        List<Map<String, Object>> retained = new ArrayList<>(
                messages.subList(messages.size() - maxMessages, messages.size()));

        int firstUser = findFirstUserIndex(retained);
        if (firstUser >= 0) {
            retained = retained.subList(firstUser, retained.size());
        } else {
            // 锚定到最近的 user 消息
            for (int i = messages.size() - 1; i >= 0; i--) {
                if ("user".equals(messages.get(i).get("role"))) {
                    int end = Math.min(i + maxMessages, messages.size());
                    retained = new ArrayList<>(messages.subList(i, end));
                    break;
                }
            }
        }

        int ls = findLegalMessageStart(retained);
        if (ls > 0) retained = retained.subList(ls, retained.size());

        if (retained.size() > maxMessages) {
            retained = retained.subList(
                    retained.size() - maxMessages, retained.size());
            int ls2 = findLegalMessageStart(retained);
            if (ls2 > 0) retained = retained.subList(ls2, retained.size());
        }

        Set<Integer> retainedIds = new HashSet<>();
        for (Map<String, Object> m : retained) {
            retainedIds.add(System.identityHashCode(m));
        }
        List<Map<String, Object>> dropped = new ArrayList<>();
        for (Map<String, Object> m : original) {
            if (!retainedIds.contains(System.identityHashCode(m))) {
                dropped.add(m);
            }
        }

        int alreadyConsolidated = 0;
        for (int i = 0; i < original.size(); i++) {
            if (i < beforeLc && !retainedIds.contains(
                    System.identityHashCode(original.get(i)))) {
                alreadyConsolidated++;
            }
        }

        int newLc = 0;
        for (int i = 0; i < original.size(); i++) {
            if (i < beforeLc && retainedIds.contains(
                    System.identityHashCode(original.get(i)))) {
                newLc++;
            }
        }

        messages.clear();
        messages.addAll(retained);
        lastConsolidated = newLc;
        updatedAt = Instant.now();
        return new RetainResult(dropped, alreadyConsolidated);
    }

    /**
     * 归档并裁剪旧消息以限制文件大小。
     *
     * @param onArchive 归档回调
     * @param limit     消息数上限
     */
    // 对标 Python Session.enforce_file_cap()
    public void enforceFileCap(Consumer<List<Map<String, Object>>> onArchive,
                               int limit) {
        if (limit <= 0 || messages.size() <= limit) return;
        RetainResult rr = retainRecentLegalSuffix(limit);
        if (rr.dropped().isEmpty()) return;
        if (rr.alreadyConsolidated() < rr.dropped().size() && onArchive != null) {
            onArchive.accept(rr.dropped().subList(
                    rr.alreadyConsolidated(), rr.dropped().size()));
        }
    }

    /** retainRecentLegalSuffix 的返回类型。 */
    public record RetainResult(List<Map<String, Object>> dropped,
                               int alreadyConsolidated) {}

    // ==================== 静态工具方法 ====================

    /**
     * 移除 assistant 消息中的内部重放痕迹。
     *
     * @param content 原始内容
     * @return 清理后的内容
     */
    // 对标 Python _sanitize_assistant_replay_text()
    static String sanitizeAssistantReplayText(String content) {
        content = MESSAGE_TIME_PREFIX_RE.matcher(content).replaceFirst("");
        StringBuilder sb = new StringBuilder();
        for (String line : content.split("\n")) {
            if (!LOCAL_IMAGE_BREADCRUMB_RE.matcher(line).matches()
                    && !TOOL_CALL_ECHO_RE.matcher(line).matches()) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(line);
            }
        }
        return sb.toString().strip();
    }

    /**
     * 文本预览截断（max 120 chars），用于会话列表。
     *
     * @param content 原始内容
     * @return 截断预览
     */
    // 对标 Python _text_preview()
    static String textPreview(Object content) {
        String text;
        if (content instanceof String s) {
            text = s;
        } else if (content instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object block : parts) {
                if (block instanceof Map<?, ?> m
                        && "text".equals(m.get("type"))
                        && m.get("text") instanceof String t) {
                    sb.append(t);
                }
            }
            text = sb.toString();
        } else {
            return "";
        }
        text = sanitizeAssistantReplayText(text);
        text = text.replaceAll("\\s+", " ").strip();
        if (text.length() > SESSION_PREVIEW_MAX_CHARS) {
            text = text.substring(0, SESSION_PREVIEW_MAX_CHARS - 1)
                    .stripTrailing() + "…";
        }
        return text;
    }

    /** 找到消息列表中第一个 user 的索引。 */
    static int findFirstUserIndex(List<Map<String, Object>> msgs) {
        for (int i = 0; i < msgs.size(); i++) {
            if ("user".equals(msgs.get(i).get("role"))) return i;
        }
        return -1;
    }

    /**
     * 找到第一条合法消息位置（跳过孤立 tool results）。
     *
     * @param msgs 消息列表
     * @return 合法起始索引
     */
    // 对标 Python find_legal_message_start()
    @SuppressWarnings("unchecked")
    static int findLegalMessageStart(List<Map<String, Object>> msgs) {
        Set<String> declared = new HashSet<>();
        int start = 0;
        for (int i = 0; i < msgs.size(); i++) {
            Map<String, Object> msg = msgs.get(i);
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

    /**
     * 按 token 预算从尾部裁剪消息列表。
     *
     * @param out       消息列表
     * @param maxTokens token 上限
     * @param fullList  完整消息列表（用于恢复被完全裁剪的 user turns）
     * @param lc        lastConsolidated 偏移
     * @return 裁剪后列表
     */
    // 对标 Python get_history() token 裁剪
    static List<Map<String, Object>> trimByTokens(
            List<Map<String, Object>> out, int maxTokens,
            List<Map<String, Object>> fullList, int lc) {
        List<Map<String, Object>> kept = new ArrayList<>();
        int used = 0;
        for (int i = out.size() - 1; i >= 0; i--) {
            int tokens = estimateMessageTokens(out.get(i));
            if (!kept.isEmpty() && used + tokens > maxTokens) break;
            kept.add(out.get(i));
            used += tokens;
        }
        Collections.reverse(kept);

        int firstUser = findFirstUserIndex(kept);
        if (firstUser >= 0) {
            kept = kept.subList(firstUser, kept.size());
        } else {
            // 恢复最近 user turn
            List<Map<String, Object>> all =
                    fullList.subList(lc, fullList.size());
            for (int i = all.size() - 1; i >= 0; i--) {
                if ("user".equals(all.get(i).get("role"))) {
                    kept = new ArrayList<>(all.subList(i, all.size()));
                    break;
                }
            }
        }

        int ls = findLegalMessageStart(kept);
        if (ls > 0) kept = kept.subList(ls, kept.size());
        return kept;
    }

    /**
     * 估算单条消息 token 数——提取消息文本 payload 后使用 CJK 感知启发式：
     * CJK 字符 ×2 + ASCII 字符 /4 + 4 开销。
     * 对标 Python estimate_message_tokens()。
     */
    @SuppressWarnings("unchecked")
    static int estimateMessageTokens(Map<String, Object> msg) {
        StringBuilder payload = new StringBuilder();
        Object content = msg.get("content");
        if (content instanceof String s) {
            payload.append(s);
        } else if (content instanceof List<?> blocks) {
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> bm) {
                    Map<String, Object> b = (Map<String, Object>) bm;
                    if (b.get("text") instanceof String t) payload.append(t);
                    else payload.append(block.toString());
                } else if (block != null) {
                    payload.append(block.toString());
                }
            }
        } else if (content != null) {
            payload.append(content.toString());
        }
        if (msg.get("name") instanceof String n) payload.append(n);
        if (msg.get("tool_call_id") instanceof String t) payload.append(t);
        if (msg.get("tool_calls") instanceof List<?> tcs) payload.append(tcs.toString());
        if (msg.get("reasoning_content") instanceof String rc) payload.append(rc);

        String text = payload.toString();
        if (text.isEmpty()) return 4;
        int cjk = 0, ascii = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) cjk++;
            else if (c <= 127) ascii++;
            else cjk++;
        }
        return cjk * 2 + ascii / 4 + 4;
    }

    // ==================== breadcrumb 辅助方法 ====================

    /** 合成 media [image: path] breadcrumbs。 */
    private String appendMediaBreadcrumbs(Map<String, Object> msg,
                                           String role, String content) {
        if (!"user".equals(role)) return content;
        Object media = msg.get("media");
        if (!(media instanceof List<?> ml) || ml.isEmpty()) return content;
        StringBuilder sb = new StringBuilder();
        for (Object p : ml) {
            if (p instanceof String sp && !sp.isEmpty()) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append("[image: ").append(sp).append(']');
            }
        }
        String crumbs = sb.toString();
        if (crumbs.isEmpty()) return content;
        return content.isEmpty() ? crumbs : content + "\n" + crumbs;
    }

    /** 合成 CLI app attachment breadcrumbs。 */
    private String appendCliAppBreadcrumbs(Map<String, Object> msg,
                                            String role, String content) {
        if (!"user".equals(role)) return content;
        Object cliApps = msg.get("cli_apps");
        if (!(cliApps instanceof List<?> cl) || cl.isEmpty()) return content;
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Object item : cl) {
            if (count++ >= 8) break;
            if (item instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) m;
                String name = String.valueOf(
                        map.getOrDefault("name", "")).strip().toLowerCase();
                if (name.isEmpty()) continue;
                String entry = String.valueOf(
                        map.getOrDefault("entry_point", "unknown")).strip();
                if (entry.isEmpty()) entry = "unknown";
                if (!sb.isEmpty()) sb.append('\n');
                sb.append("[CLI App Attachment: @").append(name)
                        .append("; tool=run_cli_app; entry_point=")
                        .append(entry)
                        .append("; skill=skills/cli-app-")
                        .append(name).append("/SKILL.md]");
            }
        }
        String text = sb.toString();
        if (text.isEmpty()) return content;
        return content.isEmpty() ? text : content + "\n" + text;
    }

    /** 合成 MCP preset attachment breadcrumbs。 */
    private String appendMcpPresetBreadcrumbs(Map<String, Object> msg,
                                               String role, String content) {
        if (!"user".equals(role)) return content;
        Object mcpPresets = msg.get("mcp_presets");
        if (!(mcpPresets instanceof List<?> ml) || ml.isEmpty()) return content;
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Object item : ml) {
            if (count++ >= 8) break;
            if (item instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) m;
                String name = String.valueOf(
                        map.getOrDefault("name", "")).strip().toLowerCase();
                if (name.isEmpty()) continue;
                String transport = String.valueOf(
                        map.getOrDefault("transport", "mcp")).strip();
                if (transport.isEmpty()) transport = "mcp";
                if (!sb.isEmpty()) sb.append('\n');
                sb.append("[MCP Preset Attachment: @").append(name)
                        .append("; tool_prefix=mcp_").append(name)
                        .append("_; transport=").append(transport).append(']');
            }
        }
        String text = sb.toString();
        if (text.isEmpty()) return content;
        return content.isEmpty() ? text : content + "\n" + text;
    }
}
