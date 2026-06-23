package com.nanobot.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 生成 WebUI 会话的简短标题（通过 LLM）。
 *
 * <p>对标 Python {@code nanobot/session/webui_turns.py} 中的
 * {@code maybe_generate_webui_title}、{@code clean_generated_title}、{@code _title_inputs}。
 */
public final class WebuiTitleGenerator {

    private static final Logger log = LoggerFactory.getLogger(WebuiTitleGenerator.class);

    private WebuiTitleGenerator() {}

    /** 标题最大字符数。对标 Python TITLE_MAX_CHARS = 60。 */
    static final int TITLE_MAX_CHARS = 60;

    /** 标题生成最大 token 数。对标 Python TITLE_GENERATION_MAX_TOKENS = 96。 */
    private static final int TITLE_GENERATION_MAX_TOKENS = 96;

    /** 标题生成 reasoning effort。对标 Python TITLE_GENERATION_REASONING_EFFORT = "none"。 */
    private static final String TITLE_GENERATION_REASONING_EFFORT = "none";

    /** 标题前缀匹配模式（语言无关）。对标 Python _LEAD_RE。 */
    private static final Pattern LEAD_RE = Pattern.compile(
            "^\\s*(title|标题)\\s*[:：]\\s*", Pattern.CASE_INSENSITIVE);

    /**
     * 清理 LLM 生成的原始标题文本。
     * 对标 Python {@code clean_generated_title(raw)}。
     *
     * @param raw LLM 原始输出
     * @return 清理后的标题，失败时返回空字符串
     */
    public static String cleanGeneratedTitle(@Nullable String raw) {
        String text = (raw != null ? raw : "").strip();
        if (text.isEmpty()) return "";
        text = LEAD_RE.matcher(text).replaceFirst("");
        text = text.strip().replaceAll("^[\"'`" + "“" + "”" + "‘’]+|[\"'`" + "“" + "”" + "‘’]+$", "");
        text = SessionSanitizer.stripThink(text);
        text = text.replaceAll("\\s+", " ").strip();
        text = text.replaceAll("[。.!！?？,，;；:]$", "");
        if (text.length() > TITLE_MAX_CHARS) {
            text = text.substring(0, TITLE_MAX_CHARS - 1).stripTrailing() + "…";
        }
        return text;
    }

    /**
     * 提取用于标题生成的前几条用户和 assistant 文本。
     * 对标 Python {@code _title_inputs(session)}。
     *
     * @param session 会话
     * @return 含 userText 和 assistantText 的记录
     */
    public static TitleInputs titleInputs(Session session) {
        String u = "", a = "";
        for (Map<String, Object> msg : session.getMessages()) {
            if (Boolean.TRUE.equals(msg.get("_command"))) continue;
            String role = Objects.toString(msg.get("role"), "");
            Object content = msg.get("content");
            if (!(content instanceof String s) || s.strip().isEmpty()) continue;
            String clean = SessionSanitizer.stripThink(s);
            if (clean.isEmpty()) continue;
            if ("user".equals(role) && u.isEmpty()) u = clean;
            else if ("assistant".equals(role) && a.isEmpty()) a = clean;
            if (!u.isEmpty() && !a.isEmpty()) break;
        }
        return new TitleInputs(u, a);
    }

    /** 标题生成所需的输入文本。 */
    public record TitleInputs(String userText, String assistantText) {}

    /**
     * 为 WebUI 会话生成标题（仅 WebUI 会话）。
     * 对标 Python {@code async def maybe_generate_webui_title(...) -> bool}。
     *
     * @param sm         会话管理器
     * @param sessionKey 会话键
     * @param provider   LLM 提供者
     * @param model      模型名称
     * @return 是否成功生成标题
     */
    public static boolean maybeGenerateTitle(
            SessionManager sm, String sessionKey,
            com.nanobot.providers.LLMProvider provider, String model) {
        Session s = sm.getOrCreate(sessionKey);
        if (!Boolean.TRUE.equals(s.getMetadata().get("webui"))) return false;
        if (Boolean.TRUE.equals(s.getMetadata().get("title_user_edited"))) return false;

        Object cur = s.getMetadata().get("title");
        if (cur instanceof String c && !c.strip().isEmpty()) {
            String cleaned = cleanGeneratedTitle(c);
            if (!cleaned.isEmpty()) {
                if (!cleaned.equals(c)) {
                    s.getMetadata().put("title", cleaned);
                    sm.save(s);
                }
                return false;
            }
            s.getMetadata().remove("title");
        }

        TitleInputs inputs = titleInputs(s);
        if (inputs.userText().isEmpty()) return false;

        String prompt = "Generate a concise title for this chat.\nRules:\n"
                + "- Use the same language as the user when practical.\n"
                + "- 3 to 8 words.\n"
                + "- No quotes.\n"
                + "- No punctuation at the end.\n"
                + "- Return only the title.\n\n"
                + "User: " + truncate(inputs.userText(), 1000);
        if (!inputs.assistantText().isEmpty()) {
            prompt += "\nAssistant: " + truncate(inputs.assistantText(), 1000);
        }

        try {
            List<Map<String, Object>> messages = List.of(
                    Map.of("role", "system",
                            "content", "You write short, neutral chat titles. Return only the title text."),
                    Map.of("role", "user", "content", prompt));

            var response = provider.chat(messages, null, model,
                    TITLE_GENERATION_MAX_TOKENS, 0.2,
                    TITLE_GENERATION_REASONING_EFFORT, null).join();
            String generated = response.content();
            String title = cleanGeneratedTitle(generated);
            if (title.isEmpty() || title.toLowerCase(Locale.ROOT).startsWith("error")) {
                log.debug("WebUI title generation returned no usable title for {} (finish_reason={})",
                        sessionKey, response.finishReason());
                return false;
            }
            s.getMetadata().put("title", title);
            sm.save(s);
            return true;
        } catch (Exception e) {
            log.debug("Failed to generate webui session title for {}", sessionKey, e);
            return false;
        }
    }

    /**
     * turn 完成后触发的标题生成包装（仅 websocket 通道）。
     * 对标 Python {@code async def maybe_generate_webui_title_after_turn(...) -> bool}。
     */
    public static boolean maybeGenerateTitleAfterTurn(
            String channel, Map<String, Object> metadata,
            SessionManager sm, String sessionKey,
            com.nanobot.providers.LLMProvider provider, String model) {
        if (!"websocket".equals(channel)
                || !Boolean.TRUE.equals(metadata.get("webui"))) {
            return false;
        }
        return maybeGenerateTitle(sm, sessionKey, provider, model);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max).stripTrailing() + "…";
    }
}
