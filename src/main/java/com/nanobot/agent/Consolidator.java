package com.nanobot.agent;

import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.LLMResponse;
import com.nanobot.session.Session;
import com.nanobot.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 轻量级 token-budget 触发式历史压缩——当 session 消息量或 token 估算超过
 * 安全预算时，循环归档旧消息到 history.jsonl。
 *
 * <p>对标 Python {@code nanobot/agent/memory.py:602-1015 class Consolidator}。
 */
public class Consolidator {

    private static final Logger log = LoggerFactory.getLogger(Consolidator.class);

    /** 对标 Python _MAX_CONSOLIDATION_ROUNDS = 5。 */
    private static final int MAX_CONSOLIDATION_ROUNDS = 5;

    /** 对标 Python 安全缓冲 1024 token。 */
    private static final int SAFETY_BUFFER = 1024;

    /** 降级归档字符上限。 */
    private static final int RAW_ARCHIVE_MAX_CHARS = 16_000;
    private static final int ARCHIVE_SUMMARY_MAX_CHARS = 8_000;

    /**
     * 用于 token 估算的 probe messages 构建函数。
     * 对标 Python build_messages 的完整参数签名。
     */
    @FunctionalInterface
    public interface BuildMessagesFunction {
        List<Map<String, Object>> build(
                List<Map<String, Object>> history,
                String currentMessage,
                @Nullable String channel,
                @Nullable String chatId,
                @Nullable String senderId,
                @Nullable String sessionSummary,
                Map<String, Object> sessionMetadata,
                String sessionKey,
                boolean unifiedSession);
    }

    private final MemoryStore store;
    private LLMProvider provider;
    private String model;
    private final SessionManager sessions;
    private int contextWindowTokens;
    private int maxCompletionTokens;
    private final double consolidationRatio;
    private final boolean unifiedSession;
    private final BuildMessagesFunction buildMessagesFn;
    private final Supplier<List<Map<String, Object>>> getToolDefinitionsFn;

    /** 对标 Python WeakValueDictionary[str, asyncio.Lock]。
     *  Java 无 WeakValueDictionary 等价物，故在 getLock 中概率性清理空闲锁。 */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private static final double LOCK_CLEANUP_PROB = 0.05;

    public Consolidator(MemoryStore store, LLMProvider provider, String model,
                        SessionManager sessions, int contextWindowTokens,
                        BuildMessagesFunction buildMessagesFn,
                        Supplier<List<Map<String, Object>>> getToolDefinitionsFn,
                        int maxCompletionTokens, double consolidationRatio,
                        boolean unifiedSession) {
        this.store = store;
        this.provider = provider;
        this.model = model;
        this.sessions = sessions;
        this.contextWindowTokens = contextWindowTokens;
        this.maxCompletionTokens = maxCompletionTokens;
        this.consolidationRatio = consolidationRatio;
        this.unifiedSession = unifiedSession;
        this.buildMessagesFn = buildMessagesFn;
        this.getToolDefinitionsFn = getToolDefinitionsFn;
    }

    /** 对标 Python set_provider(provider, model, context_window_tokens)。 */
    public void setProvider(LLMProvider provider, String model, int contextWindowTokens) {
        this.provider = provider;
        this.model = model;
        this.contextWindowTokens = contextWindowTokens;
        try {
            this.maxCompletionTokens = provider.getGeneration().maxTokens();
        } catch (Exception ignored) {}
    }

    /** 对标 Python get_lock(session_key)——概率性清理空闲锁以防内存泄漏。 */
    public ReentrantLock getLock(String sessionKey) {
        if (Math.random() < LOCK_CLEANUP_PROB) {
            cleanIdleLocks();
        }
        return locks.computeIfAbsent(sessionKey, k -> new ReentrantLock());
    }

    /** 对标 Python WeakValueDictionary 自动驱逐——清理未被持有且无排队线程的锁。 */
    private void cleanIdleLocks() {
        locks.entrySet().removeIf(entry -> {
            ReentrantLock lock = entry.getValue();
            return !lock.isLocked() && !lock.hasQueuedThreads();
        });
    }

    // ==================== consolidation boundary ====================

    /**
     * 在 session 中选取安全的整合边界（对齐 user turn）。
     * 对标 Python pick_consolidation_boundary(session, tokens_to_remove)。
     *
     * @return (endIndex, removedTokens) 或 null
     */
    @Nullable
    public Map.Entry<Integer, Integer> pickConsolidationBoundary(
            Session session, int tokensToRemove) {
        int start = session.getLastConsolidated();
        List<Map<String, Object>> messages = session.getMessages();
        if (start >= messages.size() || tokensToRemove <= 0) return null;
        int removedTokens = 0;
        Map.Entry<Integer, Integer> lastBoundary = null;
        for (int idx = start; idx < messages.size(); idx++) {
            Map<String, Object> msg = messages.get(idx);
            if (idx > start && "user".equals(msg.get("role"))) {
                lastBoundary = Map.entry(idx, removedTokens);
                if (removedTokens >= tokensToRemove) return lastBoundary;
            }
            removedTokens += estimateMessageTokens(msg);
        }
        return lastBoundary;
    }

    // ==================== history helpers ====================

    /** 对标 Python full_unconsolidated_history(session, include_timestamps) 静态方法。 */
    public static List<Map<String, Object>> fullUnconsolidatedHistory(
            Session session, boolean includeTimestamps) {
        int unconsolidated = session.getMessages().size() - session.getLastConsolidated();
        if (unconsolidated <= 0) return List.of();
        return session.getHistory(unconsolidated, 0, includeTimestamps);
    }

    // ==================== replay overflow ====================

    /**
     * 计算 replay 溢出边界。
     * 对标 Python replay_overflow_boundary(session, replay_max_messages)。
     */
    @Nullable
    public Integer replayOverflowBoundary(Session session,
                                          @Nullable Integer replayMaxMessages) {
        if (replayMaxMessages == null || replayMaxMessages <= 0) return null;
        List<Map<String, Object>> tail = new ArrayList<>();
        int base = session.getLastConsolidated();
        for (int i = base; i < session.getMessages().size(); i++) {
            tail.add(session.getMessages().get(i));
        }
        if (tail.size() <= replayMaxMessages) return null;
        List<Map<String, Object>> sliced = new ArrayList<>(
                tail.subList(tail.size() - replayMaxMessages, tail.size()));
        // Align to user turn
        for (int i = 0; i < sliced.size(); i++) {
            if ("user".equals(sliced.get(i).get("role"))) {
                int s = i;
                if (i > 0 && Boolean.TRUE.equals(sliced.get(i - 1).get("_channel_delivery"))) s = i - 1;
                sliced = new ArrayList<>(sliced.subList(s, sliced.size()));
                break;
            }
        }
        int legalStart = findLegalMessageStart(sliced);
        if (legalStart > 0) sliced = new ArrayList<>(sliced.subList(legalStart, sliced.size()));
        if (sliced.isEmpty()) return session.getMessages().size();
        int firstVisibleIdx = base + tail.indexOf(sliced.get(0));
        if (firstVisibleIdx <= base) return null;
        return firstVisibleIdx;
    }

    /**
     * 触发 replay overflow 整合（若需要）。
     * 对标 Python _consolidate_replay_overflow(session, replay_max_messages)。
     */
    public CompletableFuture<String> consolidateReplayOverflow(
            Session session, @Nullable Integer replayMaxMessages) {
        Integer endIdx = replayOverflowBoundary(session, replayMaxMessages);
        if (endIdx == null) return CompletableFuture.completedFuture(null);
        List<Map<String, Object>> chunk = session.getMessages()
                .subList(session.getLastConsolidated(), endIdx);
        if (chunk.isEmpty()) return CompletableFuture.completedFuture(null);
        log.info("Replay-window consolidation for {}: chunk={} msgs, replay_max={}",
                session.getKey(), chunk.size(), replayMaxMessages);
        return archive(chunk, session.getKey())
                .thenApply(summary -> {
                    session.setLastConsolidated(endIdx);
                    sessions.save(session);
                    return summary;
                });
    }

    // ==================== persist summary ====================

    private void persistLastSummary(Session session, @Nullable String summary) {
        if (summary != null && !"(nothing)".equals(summary)) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("text", summary);
            meta.put("last_active", session.getUpdatedAt().toString());
            session.getMetadata().put("_last_summary", meta);
            sessions.save(session);
        }
    }

    // ==================== token estimation ====================

    /**
     * 估算 session 的 prompt token 数。
     * 对标 Python estimate_session_prompt_tokens(session)。
     *
     * @return (estimatedTokenCount, sourceIndicator)
     */
    public Map.Entry<Integer, String> estimateSessionPromptTokens(Session session) {
        List<Map<String, Object>> history = fullUnconsolidatedHistory(session, true);
        String[] parts = session.getKey().split(":", 2);
        String channel = parts.length > 1 ? parts[0] : null;
        String chatId = parts.length > 1 ? parts[1] : null;
        Object meta = session.getMetadata().get("_last_summary");
        String summary = null;
        if (meta instanceof Map<?, ?> map) {
            summary = (String) map.get("text");
        } else if (meta instanceof String s) {
            summary = s;
        }
        List<Map<String, Object>> probeMessages = buildMessagesFn.build(
                history, "[token-probe]", channel, chatId, null,
                summary, session.getMetadata(), session.getKey(), unifiedSession);
        int estimated = estimatePromptTokensChain(probeMessages, getToolDefinitionsFn.get());
        String source = "char_estimate"; // simplified — no JTokkit
        return Map.entry(estimated, source);
    }

    /** 对标 Python _input_token_budget property。 */
    private int inputTokenBudget() {
        return contextWindowTokens - maxCompletionTokens - SAFETY_BUFFER;
    }

    /**
     * token-based 文本截断（无 tokenizer 时用 chars/4 近似）。
     * 对标 Python _truncate_to_token_budget(text)。
     */
    private String truncateToTokenBudget(String text) {
        int budget = inputTokenBudget();
        if (budget <= 0) return truncateText(text, RAW_ARCHIVE_MAX_CHARS);
        int charBudget = budget * 4;
        if (text.length() <= charBudget) return text;
        return text.substring(0, charBudget) + "\n... (truncated)";
    }

    // ==================== archive ====================

    /**
     * 调用 LLM 总结消息块并写入 history.jsonl。
     * 若 LLM 失败则降级为 raw_archive。
     * 对标 Python archive(messages, *, session_key=None) async。
     */
    public CompletableFuture<String> archive(List<Map<String, Object>> messages,
                                              @Nullable String sessionKey) {
        if (messages.isEmpty()) return CompletableFuture.completedFuture(null);
        String formatted = truncateToTokenBudget(MemoryStore.formatMessages(messages));
        // 对标 Python render_template("agent/consolidator_archive.md")
        String archiveSystemPrompt = com.nanobot.agent.RuntimeUtils.renderTemplate(
                "agent/consolidator_archive.md");
        List<Map<String, Object>> llmMessages = List.of(
                Map.of("role", "system", "content", archiveSystemPrompt),
                Map.of("role", "user", "content", formatted));
        return provider.chat(llmMessages, null, model, 512, 0.3, null, null)
                .thenApply(response -> {
                    if ("error".equals(response.finishReason())) {
                        throw new RuntimeException("LLM returned error: " + response.content());
                    }
                    String summary = response.content() != null
                            ? response.content() : "[no summary]";
                    store.appendHistory(summary, ARCHIVE_SUMMARY_MAX_CHARS, sessionKey);
                    return summary;
                })
                .exceptionally(ex -> {
                    log.warn("Consolidation LLM call failed, raw-dumping to history");
                    store.rawArchive(messages, null, sessionKey);
                    return null;
                });
    }

    // ==================== maybe_consolidate ====================

    /**
     * 若 token 预算超标则触发整合循环。
     * 对标 Python maybe_consolidate_by_tokens(session, *, replay_max_messages=None) async。
     */
    public CompletableFuture<Void> maybeConsolidateByTokens(
            Session session, @Nullable Integer replayMaxMessages) {
        if (contextWindowTokens <= 0) return CompletableFuture.completedFuture(null);
        ReentrantLock lock = getLock(session.getKey());
        lock.lock();
        try {
            Session fresh = sessions.getOrCreate(session.getKey());
            final Session s = (fresh != session) ? fresh : session;
            if (s.getMessages().isEmpty()) return CompletableFuture.completedFuture(null);

            return consolidateReplayOverflow(s, replayMaxMessages)
                    .thenCompose(lastSummary -> {
                        int budget = inputTokenBudget();
                        int target = (int) (budget * consolidationRatio);
                        try {
                            Map.Entry<Integer, String> est = estimateSessionPromptTokens(s);
                            int estimated = est.getKey();
                            if (estimated <= 0 || estimated < budget) {
                                persistLastSummary(s, lastSummary);
                                return CompletableFuture.completedFuture(null);
                            }
                            return runConsolidationRounds(s, lastSummary,
                                    estimated, target, 0);
                        } catch (Exception e) {
                            log.error("Token estimation failed for {}", s.getKey(), e);
                            persistLastSummary(s, lastSummary);
                            return CompletableFuture.completedFuture(null);
                        }
                    });
        } finally {
            lock.unlock();
        }
    }

    private CompletableFuture<Void> runConsolidationRounds(
            Session session, String lastSummary, int estimated, int target,
            int roundNum) {
        if (estimated <= target || roundNum >= MAX_CONSOLIDATION_ROUNDS) {
            persistLastSummary(session, lastSummary);
            return CompletableFuture.completedFuture(null);
        }
        Map.Entry<Integer, Integer> boundary = pickConsolidationBoundary(
                session, Math.max(1, estimated - target));
        if (boundary == null) {
            persistLastSummary(session, lastSummary);
            return CompletableFuture.completedFuture(null);
        }
        int endIdx = boundary.getKey();
        List<Map<String, Object>> chunk = session.getMessages()
                .subList(session.getLastConsolidated(), endIdx);
        if (chunk.isEmpty()) {
            persistLastSummary(session, lastSummary);
            return CompletableFuture.completedFuture(null);
        }
        log.info("Token consolidation round {} for {}: {}/{} tokens, chunk={} msgs",
                roundNum, session.getKey(), estimated, contextWindowTokens, chunk.size());
        return archive(chunk, session.getKey())
                .thenCompose(summary -> {
                    String newLastSummary = summary != null ? summary : lastSummary;
                    session.setLastConsolidated(endIdx);
                    sessions.save(session);
                    if (summary == null) {
                        persistLastSummary(session, newLastSummary);
                        return CompletableFuture.completedFuture(null);
                    }
                    try {
                        Map.Entry<Integer, String> est = estimateSessionPromptTokens(session);
                        int newEstimated = est.getKey();
                        if (newEstimated <= 0) {
                            persistLastSummary(session, newLastSummary);
                            return CompletableFuture.completedFuture(null);
                        }
                        return runConsolidationRounds(session, newLastSummary,
                                newEstimated, target, roundNum + 1);
                    } catch (Exception e) {
                        log.error("Token estimation failed for {}", session.getKey(), e);
                        persistLastSummary(session, newLastSummary);
                        return CompletableFuture.completedFuture(null);
                    }
                });
    }

    // ==================== compact_idle_session ====================

    /**
     * 硬截断空闲会话——保留尾部保留后缀，其余归档到 history。
     * 对标 Python compact_idle_session(session_key, max_suffix=8) async。
     */
    public CompletableFuture<String> compactIdleSession(String sessionKey, int maxSuffix) {
        ReentrantLock lock = getLock(sessionKey);
        lock.lock();
        try {
            sessions.invalidate(sessionKey);
            Session session = sessions.getOrCreate(sessionKey);
            List<Map<String, Object>> tail = new ArrayList<>(
                    session.getMessages().subList(
                            session.getLastConsolidated(), session.getMessages().size()));
            if (tail.isEmpty()) {
                session.setUpdatedAt(Instant.now());
                sessions.save(session);
                return CompletableFuture.completedFuture("");
            }
            Session probe = new Session(sessionKey, tail,
                    session.getCreatedAt(), session.getUpdatedAt(),
                    new LinkedHashMap<>(), 0);
            Session.RetainResult retainResult = probe.retainRecentLegalSuffix(maxSuffix);
            List<Map<String, Object>> kept = probe.getMessages();
            List<Map<String, Object>> dropped = retainResult.dropped();
            int alreadyConsolidated = retainResult.alreadyConsolidated();
            List<Map<String, Object>> archiveMsgs = new ArrayList<>(
                    dropped.subList(alreadyConsolidated, dropped.size()));

            if (archiveMsgs.isEmpty() && kept.isEmpty()) {
                session.setUpdatedAt(Instant.now());
                sessions.save(session);
                return CompletableFuture.completedFuture("");
            }
            Instant lastActive = session.getUpdatedAt();
            if (archiveMsgs.isEmpty()) {
                session.getMessages().clear();
                session.getMessages().addAll(kept);
                session.setLastConsolidated(0);
                session.setUpdatedAt(Instant.now());
                sessions.save(session);
                return CompletableFuture.completedFuture("");
            }
            return archive(archiveMsgs, sessionKey)
                    .thenApply(summary -> {
                        if (summary != null && !"(nothing)".equals(summary)) {
                            session.getMetadata().put("_last_summary", Map.of(
                                    "text", summary,
                                    "last_active", lastActive.toString()));
                        }
                        session.getMessages().clear();
                        session.getMessages().addAll(kept);
                        session.setLastConsolidated(0);
                        session.setUpdatedAt(Instant.now());
                        sessions.save(session);
                        log.info("Idle-session compact for {}: archived={}, kept={}, summary={}",
                                sessionKey, archiveMsgs.size(),
                                kept.size(), summary != null);
                        return summary;
                    });
        } finally {
            lock.unlock();
        }
    }

    // ==================== helpers ====================

    /** 对标 Python estimate_message_tokens(message)。 */
    private static int estimateMessageTokens(Map<String, Object> msg) {
        Object content = msg.get("content");
        String text = content instanceof String s ? s
                : content != null ? content.toString() : "";
        if (text.isEmpty()) return 4;
        int cjk = 0, ascii = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) cjk++;
            else if (c <= 127) ascii++;
            else cjk++;
        }
        return cjk * 2 + ascii / 4 + 10;
    }

    private static int estimatePromptTokensChain(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools) {
        int total = 0;
        for (Map<String, Object> m : messages) total += estimateMessageTokens(m);
        if (tools != null) total += tools.size() * 100;
        return total;
    }

    @SuppressWarnings("unchecked")
    private static int findLegalMessageStart(List<Map<String, Object>> messages) {
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

    private static String truncateText(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n... (truncated)";
    }

}
