package com.nanobot.session;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 消息内容清洁与面包屑合成（包私有）。
 *
 * <p>对标 Python {@code nanobot/session/manager.py} 中的
 * {@code _sanitize_assistant_replay_text}、{@code _text_preview}、
 * {@code _message_preview_text}、{@code _metadata_title}、{@code strip_think} 等辅助函数。
 */
final class SessionSanitizer {

    private SessionSanitizer() {}

    /** 对标 Python _MESSAGE_TIME_PREFIX_RE。 */
    private static final Pattern MSG_TIME_RE = Pattern.compile("^\\[Message Time: [^\\]]+\\]\n?");

    /** 对标 Python _LOCAL_IMAGE_BREADCRUMB_RE（本地路径模式）。 */
    private static final Pattern IMG_BREAD_RE = Pattern.compile("^\\[image: (?:/|~)[^\\]]+\\]\\s*$");

    /** 对标 Python _TOOL_CALL_ECHO_RE。 */
    private static final Pattern TOOL_ECHO_RE = Pattern.compile("^\\s*(?:generate_image|message)\\([^)]*\\)\\s*$");

    /** 预览最大字符数。对标 Python _SESSION_PREVIEW_MAX_CHARS。 */
    static final int PREVIEW_MAX_CHARS = 120;

    /**
     * 移除 assistant 消息中的内部重放痕迹。
     * 对标 Python {@code _sanitize_assistant_replay_text(content)}。
     */
    static String sanitizeAssistantReplayText(String content) {
        String c = MSG_TIME_RE.matcher(content).replaceFirst("");
        StringBuilder sb = new StringBuilder();
        for (String line : c.split("\n")) {
            if (!IMG_BREAD_RE.matcher(line).matches()
                    && !TOOL_ECHO_RE.matcher(line).matches()) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(line);
            }
        }
        return sb.toString().strip();
    }

    /**
     * 合成媒体 [image: path] 面包屑（仅对 user 消息）。
     * 对标 Python add_media_breadcrumbs 逻辑。
     */
    static String addMediaBreadcrumbs(Map<String, Object> msg, String content) {
        if (!"user".equals(msg.get("role"))) return content;
        Object m = msg.get("media");
        if (!(m instanceof List<?> ml) || ml.isEmpty()) return content;
        StringBuilder c = new StringBuilder();
        for (Object item : ml) {
            if (item instanceof String s && !s.isEmpty()) {
                if (!c.isEmpty()) c.append('\n');
                c.append("[image: ").append(s).append("]");
            }
        }
        String t = c.toString();
        return t.isEmpty() ? content : (content.isEmpty() ? t : content + "\n" + t);
    }

    /**
     * 合成 CLI app attachment 面包屑（仅对 user 消息）。
     * 对标 Python add_cli_apps_breadcrumbs 逻辑。
     */
    static String addCliAppsBreadcrumbs(Map<String, Object> msg, String content) {
        if (!"user".equals(msg.get("role"))) return content;
        Object cli = msg.get("cli_apps");
        if (!(cli instanceof List<?> cl)) return content;
        StringBuilder c = new StringBuilder();
        int n = 0;
        for (Object item : cl) {
            if (n >= 8 || !(item instanceof Map<?, ?> app)) { n++; continue; }
            n++;
            String name = Objects.toString(app.get("name"), "").strip().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) continue;
            String ep = Objects.toString(app.get("entry_point"), "unknown").strip();
            if (ep.isEmpty()) ep = "unknown";
            if (!c.isEmpty()) c.append('\n');
            c.append(String.format(
                    "[CLI App Attachment: @%s; tool=run_cli_app; entry_point=%s; skill=skills/cli-app-%s/SKILL.md]",
                    name, ep, name));
        }
        String t = c.toString();
        return t.isEmpty() ? content : (content.isEmpty() ? t : content + "\n" + t);
    }

    /**
     * 合成 MCP preset attachment 面包屑（仅对 user 消息）。
     * 对标 Python add_mcp_presets_breadcrumbs 逻辑。
     */
    static String addMcpPresetsBreadcrumbs(Map<String, Object> msg, String content) {
        if (!"user".equals(msg.get("role"))) return content;
        Object mcp = msg.get("mcp_presets");
        if (!(mcp instanceof List<?> ml)) return content;
        StringBuilder c = new StringBuilder();
        int n = 0;
        for (Object item : ml) {
            if (n >= 8 || !(item instanceof Map<?, ?> p)) { n++; continue; }
            n++;
            String name = Objects.toString(p.get("name"), "").strip().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) continue;
            String transport = Objects.toString(p.get("transport"), "mcp").strip();
            if (transport.isEmpty()) transport = "mcp";
            if (!c.isEmpty()) c.append('\n');
            c.append(String.format(
                    "[MCP Preset Attachment: @%s; tool_prefix=mcp_%s_; transport=%s]",
                    name, name, transport));
        }
        String t = c.toString();
        return t.isEmpty() ? content : (content.isEmpty() ? t : content + "\n" + t);
    }

    /**
     * 为 user 消息注入 [Message Time: ts] 前缀。
     * 对标 Python annotate_message_time 逻辑。
     */
    static String annotateMessageTime(Map<String, Object> msg, String content) {
        Object ts = msg.get("timestamp");
        if (ts == null || !"user".equals(msg.get("role"))) return content;
        return "[Message Time: " + ts + "]\n" + content;
    }

    /**
     * 文本预览截断（max 120 chars），用于会话列表。
     * 对标 Python {@code _text_preview(content)}。
     */
    static String textPreview(Object content) {
        String text;
        if (content instanceof String s) {
            text = s;
        } else if (content instanceof List<?> blocks) {
            List<String> parts = new ArrayList<>();
            for (Object b : blocks) {
                if (b instanceof Map<?, ?> m && "text".equals(m.get("type"))) {
                    Object v = m.get("text");
                    if (v instanceof String s) parts.add(s);
                }
            }
            text = String.join(" ", parts);
        } else {
            return "";
        }
        text = sanitizeAssistantReplayText(text).replaceAll("\\s+", " ").strip();
        if (text.length() > PREVIEW_MAX_CHARS) {
            text = text.substring(0, PREVIEW_MAX_CHARS - 1).stripTrailing() + "…";
        }
        return text;
    }

    /**
     * 消息预览文本（含 subagent_result 截断）。
     * 对标 Python {@code _message_preview_text(msg)}。
     */
    static String messagePreviewText(Map<String, Object> msg) {
        Object content = msg.get("content");
        if ("subagent_result".equals(msg.get("injected_event")) && content instanceof String s) {
            content = truncate(s, 300);
        }
        return textPreview(content);
    }

    /**
     * 从 metadata 获取清理后的标题。
     * 对标 Python {@code _metadata_title(metadata)}。
     */
    static String metadataTitle(Map<String, Object> metadata) {
        Object t = metadata.get("title");
        if (!(t instanceof String s) || s.isEmpty()) return "";
        if (Boolean.TRUE.equals(metadata.get("title_user_edited"))) return s;
        return stripThink(s);
    }

    /**
     * 移除 think 标记和 channel 占位符。
     * 对标 Python {@code strip_think(text)}。
     */
    static String stripThink(String text) {
        if (text == null) return "";
        // 1. Well-formed blocks
        text = text.replaceAll("(?s)<think>.*?</think>", "");
        text = text.replaceAll("(?s)^\\s*<think>.*$", "");
        text = text.replaceAll("(?s)<thought>.*?</thought>", "");
        text = text.replaceAll("(?s)^\\s*<thought>.*$", "");
        // 2. Malformed opening tags missing >
        text = text.replaceAll("<think(?![A-Za-z0-9_\\-:>/])", "");
        text = text.replaceAll("<thought(?![A-Za-z0-9_\\-:>/])", "");
        // 3. Edge-only orphan closing tags
        text = text.replaceAll("^\\s*</think>\\s*", "");
        text = text.replaceAll("\\s*</think>\\s*$", "");
        text = text.replaceAll("^\\s*</thought>\\s*", "");
        text = text.replaceAll("\\s*</thought>\\s*$", "");
        // 4. Channel markers
        text = text.replaceAll("^\\s*<\\|?channel\\|?>\\s*", "");
        // 5. Trailing partial control tags
        String partial = "</?(?:t|th|thi|thin|think|tho|thou|thoug|though|thought)>?"
                + "|<\\|?(?:c|ch|cha|chan|chann|channe|channel)(?:\\|?>?)?";
        text = text.replaceAll("(?:" + partial + ")$", "");
        text = text.replaceAll("^\\s*<\\|?$", "");
        return text.strip();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max).stripTrailing() + "…";
    }
}
