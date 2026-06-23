package com.nanobot.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 按 token 预算从消息列表尾部裁剪。
 *
 * <p>使用 tiktoken cl100k_base 编码（通过 JTokkit）时提供精确估算，
 * 不可用时回退到 chars/4 的近似算法。
 *
 * <p>对标 Python {@code nanobot/session/manager.py} 中
 * {@code get_history()} 的 max_tokens 裁剪路径和
 * {@code nanobot/utils/helpers.py} 的 {@code estimate_message_tokens()}。
 */
final class SessionTokenBudget {

    private static final Logger log = LoggerFactory.getLogger(SessionTokenBudget.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 最小 token 开销。对标 Python 的 MIN_TOKENS = 4。 */
    private static final int MIN_TOKENS = 4;

    private SessionTokenBudget() {}

    /**
     * 按 maxTokens 从尾部裁剪消息列表。
     * 对标 Python get_history() 的 token 预算裁剪路径。
     *
     * @param out       清理后的消息列表
     * @param maxTokens token 上限
     * @return 裁剪后的列表
     */
    static List<Map<String, Object>> trim(List<Map<String, Object>> out, int maxTokens) {
        // 从尾部倒序收集
        List<Map<String, Object>> kept = new ArrayList<>();
        int used = 0;
        for (int i = out.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = out.get(i);
            int tokens = estimateTokens(msg);
            if (!kept.isEmpty() && used + tokens > maxTokens) break;
            kept.add(msg);
            used += tokens;
        }
        Collections.reverse(kept);

        // 确保以 user turn 开头
        int firstUser = SessionHelpers.findFirstRole(kept, "user");
        if (firstUser >= 0) {
            kept = new ArrayList<>(kept.subList(firstUser, kept.size()));
        } else {
            // 回溯查找最近 user turn
            int recovered = -1;
            for (int i = out.size() - 1; i >= 0; i--) {
                if ("user".equals(out.get(i).get("role"))) { recovered = i; break; }
            }
            if (recovered >= 0) kept = new ArrayList<>(out.subList(recovered, out.size()));
        }

        int ls = SessionHelpers.findLegalMessageStart(kept);
        if (ls > 0) kept = new ArrayList<>(kept.subList(ls, kept.size()));
        return kept;
    }

    /**
     * 估算单条持久化消息贡献的 prompt token 数。
     * 对标 Python {@code estimate_message_tokens(message)}。
     *
     * <p>payload 构成（与 Python 完全一致）：
     * <ol>
     *   <li>content 字段（String 或 content-block List 或 JSON）</li>
     *   <li>name、tool_call_id 字段</li>
     *   <li>完整 tool_calls JSON</li>
     *   <li>reasoning_content 字段</li>
     * </ol>
     * 空 payload 返回 MIN_TOKENS（4）。
     *
     * @param msg 消息 Map
     * @return 估算 token 数
     */
    static int estimateTokens(Map<String, Object> msg) {
        // 构建与 Python estimate_message_tokens 完全一致的 payload 部件
        List<String> parts = new ArrayList<>();

        // content 字段
        Object content = msg.get("content");
        if (content instanceof String s) {
            parts.add(s);
        } else if (content instanceof List<?> list) {
            for (Object part : list) {
                if (part instanceof Map<?, ?> m && "text".equals(m.get("type"))) {
                    Object text = m.get("text");
                    if (text instanceof String s && !s.isEmpty()) parts.add(s);
                } else {
                    try { parts.add(MAPPER.writeValueAsString(part)); }
                    catch (JsonProcessingException ignored) {}
                }
            }
        } else if (content != null) {
            try { parts.add(MAPPER.writeValueAsString(content)); }
            catch (JsonProcessingException ignored) {}
        }

        // name 和 tool_call_id 字段
        for (String key : List.of("name", "tool_call_id")) {
            Object v = msg.get(key);
            if (v instanceof String s && !s.isEmpty()) parts.add(s);
        }

        // 完整 tool_calls JSON
        Object tc = msg.get("tool_calls");
        if (tc != null) {
            try { parts.add(MAPPER.writeValueAsString(tc)); }
            catch (JsonProcessingException ignored) {}
        }

        // reasoning_content 字段
        Object rc = msg.get("reasoning_content");
        if (rc instanceof String s && !s.isEmpty()) parts.add(s);

        String payload = String.join("\n", parts);
        if (payload.isEmpty()) return MIN_TOKENS;

        // 使用 chars/4 近似算法（对标 Python jtokkit 回退路径）
        return Math.max(MIN_TOKENS, payload.length() / 4 + MIN_TOKENS);
    }
}
