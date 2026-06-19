package com.nanobot.agent.context;

import com.nanobot.agent.session.Session;
import com.nanobot.agent.session.SessionManager;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 轻量级会话压缩器：将超出 token 预算的旧消息压缩成摘要写入 history.jsonl。
 * 对应 Python Consolidator 类（agent/memory.py 行 602-1016）。
 *
 * <p>核心流程：估算 prompt token → 与 inputTokenBudget 比较 →
 * 按 user turn 边界切分 → 调用 LLM 压缩 → 更新 session.lastConsolidated。
 * 支持 replay-window 溢出压缩和空闲会话压缩（compactIdleSession）。</p>
 */
public class Consolidator {

    private static final Logger log = LoggerFactory.getLogger(Consolidator.class);

    /** 每轮 consolidate 最大压缩轮次 */
    static final int MAX_CONSOLIDATION_ROUNDS = 5;
    /** token 预算安全余量 */
    static final int SAFETY_BUFFER = 1024;
    /** raw archive 最大字符数 */
    static final int RAW_ARCHIVE_MAX_CHARS = 16_000;
    /** archive 摘要最大字符数 */
    static final int ARCHIVE_SUMMARY_MAX_CHARS = 8_000;

    private final MemoryStore store;
    private ConsolidatorProvider provider;
    private final SessionManager sessions;
    private int contextWindowTokens;
    private final int maxCompletionTokens;
    private final double consolidationRatio;
    private final boolean unifiedSession;
    /** 用于构建 probe 消息的函数引用，对应 Python 的 build_messages 回调 */
    private final BuildMessagesFunction buildMessages;
    /** 工具定义提供者，对应 Python 的 get_tool_definitions 回调 */
    private final java.util.function.Supplier<List<Map<String, Object>>> getToolDefinitions;

    private String model;
    /** 按 session key 分片的压缩锁，避免同一会话并发压缩 */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** 消息构建函数式接口，对应 Python build_messages 回调签名 */
    @FunctionalInterface
    public interface BuildMessagesFunction {
        List<Map<String, Object>> build(
                List<Map<String, Object>> history,
                String currentMessage,
                @Nullable String channel,
                @Nullable String chatId,
                @Nullable String senderId,
                @Nullable String sessionSummary,
                @Nullable Map<String, Object> sessionMetadata,
                @Nullable String sessionKey,
                boolean unifiedSession);
    }

    public Consolidator(
            MemoryStore store,
            ConsolidatorProvider provider,
            String model,
            SessionManager sessions,
            int contextWindowTokens,
            int maxCompletionTokens,
            double consolidationRatio,
            boolean unifiedSession,
            BuildMessagesFunction buildMessages,
            java.util.function.Supplier<List<Map<String, Object>>> getToolDefinitions) {
        this.store = store;
        this.provider = provider;
        this.model = model;
        this.sessions = sessions;
        this.contextWindowTokens = contextWindowTokens;
        this.maxCompletionTokens = maxCompletionTokens;
        this.consolidationRatio = consolidationRatio;
        this.unifiedSession = unifiedSession;
        this.buildMessages = buildMessages;
        this.getToolDefinitions = getToolDefinitions;
    }

    /** 更新 provider 引用（provider 快照切换时调用）。
     *  对应 Python Consolidator.set_provider()。 */
    public void setProvider(ConsolidatorProvider provider, String model, int contextWindowTokens) {
        this.provider = provider;
        this.model = model;
        this.contextWindowTokens = contextWindowTokens;
    }

    /** 获取 session 对应的压缩锁。
     *  对应 Python Consolidator._get_lock()。 */
    public ReentrantLock getLock(String sessionKey) {
        return locks.computeIfAbsent(sessionKey, k -> new ReentrantLock());
    }

    // -- pickConsolidationBoundary --
    // 对应 Python Consolidator._pick_consolidation_boundary()

    /**
     * 选取压缩边界：从未 consolidate 的消息中按 user turn 切分，
     * 累计 token 数达到 tokensToRemove 时返回边界索引。
     * 对应 Python Consolidator._pick_consolidation_boundary()。
     */
    public int[] pickConsolidationBoundary(Session session, int tokensToRemove) {
        int start = session.lastConsolidated();
        var messages = session.messages();
        if (start >= messages.size() || tokensToRemove <= 0) return null;

        int removedTokens = 0;
        int[] lastBoundary = null;
        for (int idx = start; idx < messages.size(); idx++) {
            var message = messages.get(idx);
            if (idx > start && "user".equals(message.get("role"))) {
                lastBoundary = new int[]{idx, removedTokens};
                if (removedTokens >= tokensToRemove) {
                    return lastBoundary;
                }
            }
            removedTokens += Session.estimateMessageTokens(message);
        }
        return lastBoundary;
    }

    // -- fullUnconsolidatedHistory --
    // 对应 Python Consolidator._full_unconsolidated_history()

    /** 获取全部未 consolidate 的历史消息（经 getHistory 清洗）。
     *  对应 Python Consolidator._full_unconsolidated_history()。 */
    static List<Map<String, Object>> fullUnconsolidatedHistory(Session session, boolean includeTimestamps) {
        int unconsolidatedCount = session.messages().size() - session.lastConsolidated();
        if (unconsolidatedCount <= 0) return List.of();
        return session.getHistory(unconsolidatedCount, 0, includeTimestamps);
    }

    // -- estimateSessionPromptTokens --
    // 对应 Python Consolidator.estimate_session_prompt_tokens()

    /**
     * 估算当前会话的 prompt token 数。
     * 返回 [tokens, source] — source 标识估算来源（如 10=provider）。
     * 对应 Python Consolidator.estimate_session_prompt_tokens()。
     */
    public int[] estimateSessionPromptTokens(Session session) {
        var history = fullUnconsolidatedHistory(session, true);
        String channel = null, chatId = null;
        var parts = session.key().split(":", 2);
        if (parts.length == 2) {
            channel = parts[0];
            chatId = parts[1];
        }
        var meta = session.metadata().get("_last_summary");
        String summary = null;
        if (meta instanceof Map<?, ?> m) {
            var text = m.get("text");
            if (text instanceof String s) summary = s;
        } else if (meta instanceof String s) {
            summary = s;
        }
        var probeMessages = buildMessages.build(history, "[token-probe]",
                channel, chatId, null, summary, session.metadata(),
                session.key(), unifiedSession);
        int tokens = provider.estimatePromptTokens(probeMessages, getToolDefinitions.get());
        return new int[]{Math.max(1, tokens), 10}; // [tokens, source]
    }

    // -- inputTokenBudget --
    // 对应 Python Consolidator.input_token_budget()

    /** 计算可用于 prompt 的 token 预算。
     *  对应 Python Consolidator.input_token_budget() property。 */
    public int inputTokenBudget() {
        return contextWindowTokens - maxCompletionTokens - SAFETY_BUFFER;
    }

    // -- truncateToTokenBudget --
    // 对应 Python Consolidator._truncate_to_token_budget()

    /** 按 token 预算截断文本。
     *  对应 Python Consolidator._truncate_to_token_budget()。 */
    public String truncateToTokenBudget(String text) {
        int budget = inputTokenBudget();
        if (budget <= 0) {
            return MemoryStore.truncateText(text, RAW_ARCHIVE_MAX_CHARS);
        }
        // Java 无 tiktoken，使用 ~4 字符/token 估算
        int estimated = text.length() / 4;
        if (estimated <= budget) return text;
        int charLimit = budget * 4;
        return text.substring(0, Math.min(charLimit, text.length())) + "\n... (truncated)";
    }

    // -- archive --
    // 对应 Python Consolidator._archive()

    /**
     * 调用 LLM 将消息列表压缩为摘要。
     * 对应 Python Consolidator._archive()。
     */
    public String archive(List<Map<String, Object>> messages, @Nullable String sessionKey) {
        if (messages == null || messages.isEmpty()) return null;
        try {
            var formatted = formatMessages(messages);
            formatted = truncateToTokenBudget(formatted);
            var response = provider.chat(model, List.of(
                    Map.of("role", "system", "content", ARCHIVE_SYSTEM_PROMPT),
                    Map.of("role", "user", "content", formatted)
            ));
            if ("error".equals(response.finishReason())) {
                throw new RuntimeException("LLM returned error: " + response.content());
            }
            var summary = response.content() != null ? response.content() : "[no summary]";
            store.appendHistory(summary, ARCHIVE_SUMMARY_MAX_CHARS, sessionKey);
            return summary;
        } catch (Exception e) {
            log.warn("Consolidation LLM call failed, raw-dumping to history");
            rawArchive(messages, sessionKey);
            return null;
        }
    }

    // -- rawArchive --
    // 对应 Python Consolidator._raw_archive()

    /** 降级方案：直接将原始消息文本写入 history（LLM 压缩失败时）。
     *  对应 Python Consolidator._raw_archive()。 */
    void rawArchive(List<Map<String, Object>> messages, @Nullable String sessionKey) {
        var formatted = truncateToTokenBudget(formatMessages(messages));
        formatted = MemoryStore.truncateText(
                "[RAW] " + messages.size() + " messages\n" + formatted, RAW_ARCHIVE_MAX_CHARS);
        store.appendHistory(formatted, null, sessionKey);
        log.warn("Memory consolidation degraded: raw-archived {} messages", messages.size());
    }

    // -- maybeConsolidateByTokens --
    // 对应 Python Consolidator.maybe_consolidate_by_tokens()

    /**
     * 根据 token 预算判断是否需要压缩，最多执行 MAX_CONSOLIDATION_ROUNDS 轮。
     * 先处理 replay-window 溢出，再逐步切分压缩直到预算内。
     * 对应 Python Consolidator.maybe_consolidate_by_tokens()。
     */
    public void maybeConsolidateByTokens(Session session, Integer replayMaxMessages) {
        if (contextWindowTokens <= 0) return;

        var lock = getLock(session.key());
        lock.lock();
        try {
            var fresh = sessions.getOrCreate(session.key());
            if (fresh != session) session = fresh;
            if (session.messages().isEmpty()) return;

            int budget = inputTokenBudget();
            int target = (int) (budget * consolidationRatio);

            consolidateReplayOverflow(session, replayMaxMessages);

            int[] estResult;
            try {
                estResult = estimateSessionPromptTokens(session);
            } catch (Exception e) {
                log.error("Token estimation failed for {}", session.key(), e);
                estResult = new int[]{0, 10};
            }
            int estimated = estResult[0];
            if (estimated <= 0) return;
            if (estimated < budget) {
                log.debug("Token consolidation idle {}: {}/{} via {}, msgs={}",
                        session.key(), estimated, contextWindowTokens, estResult[1],
                        session.messages().size() - session.lastConsolidated());
                return;
            }

            String lastSummary = null;
            for (int roundNum = 0; roundNum < MAX_CONSOLIDATION_ROUNDS; roundNum++) {
                if (estimated <= target) break;

                var boundary = pickConsolidationBoundary(session, Math.max(1, estimated - target));
                if (boundary == null) {
                    log.debug("Token consolidation: no safe boundary for {} (round {})",
                            session.key(), roundNum);
                    break;
                }

                int endIdx = boundary[0];
                var chunk = new ArrayList<>(session.messages().subList(
                        session.lastConsolidated(), endIdx));
                if (chunk.isEmpty()) break;

                log.info("Token consolidation round {} for {}: {}/{} via {}, chunk={} msgs",
                        roundNum, session.key(), estimated, contextWindowTokens,
                        estResult[1], chunk.size());

                var summary = archive(chunk, session.key());
                if (summary != null) lastSummary = summary;
                session.setLastConsolidated(endIdx);
                sessions.save(session);
                if (summary == null) break;

                try {
                    estResult = estimateSessionPromptTokens(session);
                } catch (Exception e) {
                    log.error("Token estimation failed for {}", session.key(), e);
                    estResult = new int[]{0, 10};
                }
                estimated = estResult[0];
                if (estimated <= 0) break;
            }

            persistLastSummary(session, lastSummary);
        } finally {
            lock.unlock();
        }
    }

    // -- consolidateReplayOverflow --
    // 对应 Python Consolidator._consolidate_replay_overflow()

    /** 处理 replay-window 溢出：当未 consolidate 消息超过 replay_max 时压缩溢出部分。
     *  对应 Python Consolidator._consolidate_replay_overflow()。 */
    String consolidateReplayOverflow(Session session, @Nullable Integer replayMaxMessages) {
        Integer endIdx = replayOverflowBoundary(session, replayMaxMessages);
        if (endIdx == null) return null;
        var chunk = new ArrayList<>(session.messages().subList(
                session.lastConsolidated(), endIdx));
        if (chunk.isEmpty()) return null;
        log.info("Replay-window consolidation for {}: chunk={} msgs, replay_max={}",
                session.key(), chunk.size(), replayMaxMessages);
        var summary = archive(chunk, session.key());
        session.setLastConsolidated(endIdx);
        sessions.save(session);
        return summary;
    }

    /** 计算 replay 溢出边界：找到保留 replayMaxMessages 条消息后，需要压缩的索引。
     *  对应 Python Consolidator._replay_overflow_boundary()。 */
    Integer replayOverflowBoundary(Session session, @Nullable Integer replayMaxMessages) {
        if (replayMaxMessages == null || replayMaxMessages <= 0) return null;
        var messages = session.messages();
        int start = session.lastConsolidated();
        var tail = new ArrayList<Map<String, Object>>();
        for (int i = start; i < messages.size(); i++) tail.add(messages.get(i));
        if (tail.size() <= replayMaxMessages) return null;

        var sliced = tail.subList(tail.size() - replayMaxMessages, tail.size());
        int trimFromFront = 0;
        for (int i = 0; i < sliced.size(); i++) {
            if ("user".equals(sliced.get(i).get("role"))) {
                if (i > 0 && sliced.get(i - 1).get("_channel_delivery") != null) {
                    trimFromFront = i - 1;
                } else {
                    trimFromFront = i;
                }
                break;
            }
        }
        sliced = sliced.subList(trimFromFront, sliced.size());

        int legalStart = Session.findLegalMessageStart(sliced);
        if (legalStart > 0) {
            sliced = sliced.subList(legalStart, sliced.size());
        }
        if (sliced.isEmpty()) return messages.size();

        // 找到第一条保留消息在原始列表中的索引
        int firstVisibleIdx = start + tail.size() - replayMaxMessages + trimFromFront + legalStart;
        if (firstVisibleIdx <= start) return null;
        return firstVisibleIdx;
    }

    // -- persistLastSummary --
    // 对应 Python Consolidator._persist_last_summary()

    /** 将最后一次压缩的摘要持久化到 session 元数据中。
     *  对应 Python Consolidator._persist_last_summary()。 */
    void persistLastSummary(Session session, @Nullable String summary) {
        if (summary != null && !summary.equals("(nothing)")) {
            var summaryMap = new java.util.LinkedHashMap<String, Object>();
            summaryMap.put("text", summary);
            summaryMap.put("last_active", session.updatedAt().toString());
            session.metadata().put("_last_summary", summaryMap);
            sessions.save(session);
        }
    }

    // -- compactIdleSession --
    // 对应 Python Consolidator.compact_idle_session()

    /**
     * 压缩空闲会话：保留最近 maxSuffix 条合法消息，其余压缩为摘要。
     * 对应 Python Consolidator.compact_idle_session()。
     */
    @SuppressWarnings("unchecked")
    public String compactIdleSession(String sessionKey, int maxSuffix) {
        var lock = getLock(sessionKey);
        lock.lock();
        try {
            sessions.invalidate(sessionKey);
            var session = sessions.getOrCreate(sessionKey);

            int start = session.lastConsolidated();
            var messages = session.messages();
            var tail = new ArrayList<>(messages.subList(start, messages.size()));
            if (tail.isEmpty()) {
                session.setUpdatedAt(Instant.now());
                sessions.save(session);
                return "";
            }

            var probe = new Session(session.key());
            probe.messages().addAll(tail);

            var retainResult = probe.retainRecentLegalSuffix(maxSuffix);
            var kept = probe.messages();
            var dropped = (List<Map<String, Object>>) retainResult.get("dropped");
            int alreadyConsolidated = ((Number) retainResult.get("alreadyConsolidated")).intValue();

            List<Map<String, Object>> archiveMsgs;
            if (dropped.size() > alreadyConsolidated) {
                archiveMsgs = new ArrayList<>(dropped.subList(alreadyConsolidated, dropped.size()));
            } else {
                archiveMsgs = List.of();
            }

            if (archiveMsgs.isEmpty() && kept.isEmpty()) {
                session.setUpdatedAt(Instant.now());
                sessions.save(session);
                return "";
            }

            var lastActive = session.updatedAt();
            String summary = "";
            if (!archiveMsgs.isEmpty()) {
                summary = archive(new ArrayList<>(archiveMsgs), sessionKey);
            }

            if (summary != null && !summary.equals("(nothing)")) {
                var summaryMap = new java.util.LinkedHashMap<String, Object>();
                summaryMap.put("text", summary);
                summaryMap.put("last_active", lastActive.toString());
                session.metadata().put("_last_summary", summaryMap);
            }

            session.messages().clear();
            session.messages().addAll(kept);
            session.setLastConsolidated(0);
            session.setUpdatedAt(Instant.now());
            sessions.save(session);

            if (!archiveMsgs.isEmpty()) {
                log.info("Idle-session compact for {}: archived={}, kept={}, summary={}",
                        sessionKey, archiveMsgs.size(), kept.size(), summary != null);
            }

            return summary;
        } finally {
            lock.unlock();
        }
    }

    // -- formatMessages --
    // 对应 Python Consolidator._format_messages()

    /** 将消息列表格式化为纯文本，供 LLM 压缩使用。
     *  对应 Python Consolidator._format_messages()。 */
    static String formatMessages(List<Map<String, Object>> messages) {
        var sb = new StringBuilder();
        for (var message : messages) {
            var content = message.get("content");
            if (content == null || (content instanceof String s && s.isEmpty())) continue;
            var tools = message.get("tools_used");
            var toolsStr = tools instanceof List<?> tl && !tl.isEmpty()
                    ? " [tools: " + String.join(", ", tl.stream().map(Object::toString).toList()) + "]"
                    : "";
            var ts = message.get("timestamp");
            var tsStr = ts != null ? ts.toString().substring(0, Math.min(16, ts.toString().length())) : "?";
            var role = message.get("role") != null ? message.get("role").toString().toUpperCase() : "?";
            sb.append("[").append(tsStr).append("] ").append(role)
                    .append(toolsStr).append(": ").append(content).append("\n");
        }
        return sb.toString();
    }

    /** 压缩系统提示词，对应 consolidator_archive.md */
    private static final String ARCHIVE_SYSTEM_PROMPT = """
            You are a memory consolidator. Summarize the conversation below into a concise \
            summary that captures the key facts, decisions, and context. The summary will be \
            stored as a long-term memory entry.

            Rules:
            - Preserve important facts, decisions, and user preferences
            - Include action items and their status
            - Be concise but thorough
            - Write in plain paragraph form
            - Omit trivial chitchat and greetings
            - If nothing meaningful, respond with "(nothing)"
            """;
}
