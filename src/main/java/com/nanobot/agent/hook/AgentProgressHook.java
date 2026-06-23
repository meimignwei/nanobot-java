package com.nanobot.agent.hook;

import com.nanobot.agent.RuntimeUtils;
import com.nanobot.providers.ToolCallRequest;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Agent 运行生命周期钩子——将 runner 事件转换为用户可见的进度信号。
 *
 * <p>对标 Python {@code nanobot/agent/progress_hook.py AgentProgressHook}。
 */
public class AgentProgressHook extends AgentHookAdapter {

    // 对标 Python progress_hook.py:25-55 __init__
    private final BiFunction<String, Map<String, Object>, CompletableFuture<Void>> onProgress;
    private final Function<String, CompletableFuture<Void>> onStream;
    private final Function<Boolean, CompletableFuture<Void>> onStreamEnd;
    private final String channel;
    private final String chatId;
    private final String messageId;
    private final Map<String, Object> metadata;
    private final String sessionKey;
    private final int toolHintMaxLength;
    private final Consumer<Object[]> setToolContext;
    private final Consumer<Integer> onIteration;

    // 对标 Python: self._stream_buf / self._think_extractor / self._reasoning_open
    private String streamBuf = "";
    private boolean reasoningOpen;

    /**
     * 构造 AgentProgressHook。
     *
     * @param onProgress        进度回调 (content, metadata) -> future
     * @param onStream          流式文本回调 delta -> future
     * @param onStreamEnd       流式结束回调 resuming -> future
     * @param channel           通道名
     * @param chatId            会话 ID
     * @param messageId         消息 ID，可为 null
     * @param metadata          元数据
     * @param sessionKey        会话 key
     * @param toolHintMaxLength 工具提示最大长度
     * @param setToolContext    工具上下文设置回调
     * @param onIteration       迭代回调 iteration -> void
     */
    public AgentProgressHook(
            BiFunction<String, Map<String, Object>, CompletableFuture<Void>> onProgress,
            Function<String, CompletableFuture<Void>> onStream,
            Function<Boolean, CompletableFuture<Void>> onStreamEnd,
            String channel,
            String chatId,
            String messageId,
            Map<String, Object> metadata,
            String sessionKey,
            int toolHintMaxLength,
            Consumer<Object[]> setToolContext,
            Consumer<Integer> onIteration) {
        super(true); // 对标 Python: reraise=True
        this.onProgress = onProgress;
        this.onStream = onStream;
        this.onStreamEnd = onStreamEnd;
        this.channel = channel;
        this.chatId = chatId;
        this.messageId = messageId;
        this.metadata = metadata != null ? metadata : Map.of();
        this.sessionKey = sessionKey;
        this.toolHintMaxLength = toolHintMaxLength;
        this.setToolContext = setToolContext;
        this.onIteration = onIteration;
    }

    // ---- 流式支持 ----

    /** 对标 Python progress_hook.py:56-57 wants_streaming() */
    @Override
    public boolean wantsStreaming() {
        return onStream != null;
    }

    // ---- on_stream ----

    /**
     * LLM 流式文本增量回调——剥离 think 块后提取增量净文本并转发 onStream。
     * 对标 Python progress_hook.py:78-93 on_stream()
     */
    @Override
    public CompletableFuture<Void> onStream(AgentHookContext context, String delta) {
        if (delta == null) return CompletableFuture.completedFuture(null);

        String prevClean = RuntimeUtils.stripThink(streamBuf);
        streamBuf += delta;
        String newClean = RuntimeUtils.stripThink(streamBuf);
        String incremental;
        if (newClean != null && prevClean != null && newClean.length() >= prevClean.length()) {
            incremental = newClean.substring(prevClean.length());
        } else {
            incremental = newClean;
        }

        // 对标 Python: 从 streamBuf 中提取 reasoning 并通过 emit_reasoning 发送
        String reasoning = extractThinkContent(streamBuf);
        if (reasoning != null && !reasoning.isEmpty()) {
            context.setStreamedReasoning(true);
        }

        if (incremental != null && !incremental.isEmpty()) {
            // 对标 Python: 答案文本开始，关闭 reasoning 段
            emitReasoningEnd().join();
            if (onStream != null) {
                return onStream.apply(incremental);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    // ---- on_stream_end ----

    /**
     * 流式文本结束回调——关闭 reasoning 段并通知上层。
     * 对标 Python progress_hook.py:94-98 on_stream_end()
     */
    @Override
    public CompletableFuture<Void> onStreamEnd(AgentHookContext context, boolean resuming) {
        return emitReasoningEnd().thenCompose(v -> {
            streamBuf = "";
            if (onStreamEnd != null) {
                return onStreamEnd.apply(resuming);
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    // ---- before_iteration ----

    /**
     * 每次 LLM 迭代开始前调用。
     * 对标 Python progress_hook.py:101-108 before_iteration()
     */
    @Override
    public CompletableFuture<Void> beforeIteration(AgentHookContext context) {
        if (onIteration != null) {
            onIteration.accept(context.getIteration());
        }
        log.debug("Starting agent loop iteration {} for session {}", context.getIteration(), sessionKey);
        return CompletableFuture.completedFuture(null);
    }

    // ---- before_execute_tools ----

    /**
     * runner 即将执行工具调用前调用——发送进度提示和工具事件。
     * 对标 Python progress_hook.py:110-135 before_execute_tools()
     */
    @Override
    public CompletableFuture<Void> beforeExecuteTools(AgentHookContext context) {
        if (onProgress != null) {
            if (onStream == null && !context.isStreamedContent() && context.getResponse() != null) {
                String thought = stripThink(context.getResponse().content());
                if (thought != null && !thought.isEmpty()) {
                    onProgress.apply(thought, Map.of());
                }
            }
            String toolHint = stripThink(formatToolHints(context.getToolCalls(), toolHintMaxLength));
            List<Map<String, Object>> toolEvents = buildToolEventStartPayloads(context.getToolCalls());
            return invokeOnProgress(toolHint, true, toolEvents);
        }
        // 对标 Python: 记录每个工具调用的日志
        for (ToolCallRequest tc : context.getToolCalls()) {
            String argsStr = toJson(tc.arguments());
            log.info("Tool call: {}({})", tc.name(), argsStr.length() > 200 ? argsStr.substring(0, 200) : argsStr);
        }
        // 对标 Python: 设置工具上下文
        if (setToolContext != null) {
            setToolContext.accept(new Object[]{channel, chatId, messageId, metadata});
        }
        return CompletableFuture.completedFuture(null);
    }

    // ---- emit_reasoning / emit_reasoning_end ----

    /**
     * 发送推理文本块——channel 插件决定是否渲染。
     * 对标 Python progress_hook.py:136-144 emit_reasoning()
     */
    @Override
    public CompletableFuture<Void> emitReasoning(String reasoningContent) {
        if (onProgress != null && reasoningContent != null && !reasoningContent.isEmpty()
                && onProgressAccepts(reasoningContent, "reasoning")) {
            reasoningOpen = true;
            return onProgress.apply(reasoningContent, Map.of("reasoning", true));
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 关闭当前推理流段（如果已打开）。
     * 对标 Python progress_hook.py:146-152 emit_reasoning_end()
     */
    @Override
    public CompletableFuture<Void> emitReasoningEnd() {
        if (reasoningOpen && onProgress != null) {
            reasoningOpen = false;
            return onProgress.apply("", Map.of("reasoning_end", true));
        }
        reasoningOpen = false;
        return CompletableFuture.completedFuture(null);
    }

    // ---- after_iteration ----

    /**
     * 每次 LLM 迭代结束后调用——发送工具完成事件。
     * 对标 Python progress_hook.py:154-175 after_iteration()
     */
    @Override
    public CompletableFuture<Void> afterIteration(AgentHookContext context) {
        if (onProgress != null && context.getToolCalls() != null && !context.getToolCalls().isEmpty()
                && context.getToolEvents() != null) {
            List<Map<String, Object>> finishPayloads = buildToolEventFinishPayloads(context);
            if (!finishPayloads.isEmpty()) {
                return invokeOnProgress("", false, finishPayloads);
            }
        }
        // 对标 Python: 记录 LLM usage
        Map<String, Integer> usage = context.getUsage();
        if (usage != null) {
            log.debug("LLM usage: prompt={} completion={} cached={}",
                    usage.getOrDefault("prompt_tokens", 0),
                    usage.getOrDefault("completion_tokens", 0),
                    usage.getOrDefault("cached_tokens", 0));
        }
        return CompletableFuture.completedFuture(null);
    }

    // ---- finalize_content ----

    /**
     * 过滤 runner 最终文本中的 think 块。
     * 对标 Python progress_hook.py:177-178 finalize_content()
     */
    @Override
    public String finalizeContent(AgentHookContext context, String content) {
        return stripThink(content);
    }

    // ==================== 辅助方法 ====================

    /** 对标 Python progress_hook.py:60-63 _strip_think() */
    private static String stripThink(String text) {
        if (text == null || text.isEmpty()) return null;
        String result = RuntimeUtils.stripThink(text);
        return result != null && !result.isEmpty() ? result : null;
    }

    /** 从文本中提取 <think> 内容，用于流式 reasoning 提取。 */
    private static String extractThinkContent(String text) {
        if (text == null) return null;
        int start = text.indexOf("<think>");
        if (start < 0) return null;
        start += 8;
        int end = text.indexOf("</think>", start);
        if (end < 0) return text.substring(start);
        return text.substring(start, end);
    }

    /** 对标 Python: 检查 on_progress 回调是否接受指定参数名。 */
    private static boolean onProgressAccepts(String content, String paramName) {
        // Java 中通过 BiFunction 无法直接检查参数签名，默认返回 true
        return true;
    }

    // ---- 工具提示格式化（对标 Python tool_hints.py format_tool_hints） ----

    /** 简单工具提示格式化，对标 Python format_tool_hints()。 */
    private static String formatToolHints(List<ToolCallRequest> toolCalls, int maxLength) {
        if (toolCalls == null || toolCalls.isEmpty()) return "";
        List<String> formatted = new ArrayList<>();
        for (ToolCallRequest tc : toolCalls) {
            String hint = formatSingleToolHint(tc, maxLength);
            formatted.add(hint);
        }
        // 去重合并
        List<String> hints = new ArrayList<>();
        for (int i = 0; i < formatted.size(); i++) {
            String h = formatted.get(i);
            if (!hints.isEmpty() && hints.get(hints.size() - 1).equals(h)) {
                // 合并重复（Python 中用 × 乘号）
            } else {
                hints.add(h);
            }
        }
        return String.join(", ", hints);
    }

    private static String formatSingleToolHint(ToolCallRequest tc, int maxLength) {
        String name = tc.name();
        Object args = tc.arguments();
        // 提取第一个字符串参数值
        String firstVal = firstStringArg(args);
        if (firstVal != null) {
            return name + "(\"" + abbreviate(firstVal, maxLength) + "\")";
        }
        return name;
    }

    private static String firstStringArg(Object args) {
        if (args instanceof Map<?, ?> m) {
            for (Object v : m.values()) {
                if (v instanceof String s && !s.isEmpty()) return s;
            }
        }
        return null;
    }

    private static String abbreviate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 1) + "…";
    }

    private static String toJson(Object obj) {
        if (obj instanceof String s) return s;
        if (obj instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var e : ((Map<?, ?>) obj).entrySet()) {
                if (!first) sb.append(", ");
                sb.append(e.getKey()).append("=").append(e.getValue());
                first = false;
            }
            sb.append("}");
            return sb.toString();
        }
        return String.valueOf(obj);
    }

    // ---- 工具事件构建（对标 Python progress_events.py） ----

    /**
     * 构建工具调用开始事件 payload 列表。
     * 对标 Python progress_events.py:57-68 build_tool_event_start_payload()
     */
    private static List<Map<String, Object>> buildToolEventStartPayloads(List<ToolCallRequest> toolCalls) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (ToolCallRequest tc : toolCalls) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("version", 1);
            payload.put("phase", "start");
            payload.put("call_id", tc.id() != null ? tc.id() : "");
            payload.put("name", tc.name() != null ? tc.name() : "");
            payload.put("arguments", toolEventArgs(tc.arguments()));
            payload.put("result", null);
            payload.put("error", null);
            payload.put("files", List.of());
            payload.put("embeds", List.of());
            events.add(payload);
        }
        return events;
    }

    /**
     * 构建工具调用完成事件 payload 列表。
     * 对标 Python progress_events.py:79-106 build_tool_event_finish_payloads()
     */
    private static List<Map<String, Object>> buildToolEventFinishPayloads(AgentHookContext context) {
        List<Map<String, Object>> payloads = new ArrayList<>();
        List<ToolCallRequest> calls = context.getToolCalls();
        List<Object> results = context.getToolResults();
        List<Map<String, String>> events = context.getToolEvents();
        if (calls == null || results == null || events == null) return payloads;
        int count = Math.min(calls.size(), Math.min(results.size(), events.size()));
        for (int idx = 0; idx < count; idx++) {
            ToolCallRequest tc = calls.get(idx);
            Object result = results.get(idx);
            Map<String, String> event = events.get(idx);
            String status = event != null ? event.get("status") : null;
            String phase = "ok".equals(status) ? "end" : "error";
            List<Object> files = List.of();
            List<Object> embeds = List.of();
            if (result instanceof Map<?, ?> rm) {
                Object f = rm.get("files");
                if (f instanceof List<?> fl) files = List.copyOf((List<?>) fl);
                Object em = rm.get("embeds");
                if (em instanceof List<?> el) embeds = List.copyOf((List<?>) el);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("version", 1);
            payload.put("phase", phase);
            payload.put("call_id", tc.id() != null ? tc.id() : "");
            payload.put("name", tc.name() != null ? tc.name() : "");
            payload.put("arguments", toolEventArgs(tc.arguments()));
            payload.put("result", "end".equals(phase) ? result : null);
            payload.put("error", null);
            payload.put("files", files);
            payload.put("embeds", embeds);
            if ("error".equals(phase)) {
                if (result instanceof String s && !s.strip().isEmpty()) {
                    payload.put("error", s.strip());
                } else {
                    payload.put("error", event != null && event.get("detail") != null
                            ? event.get("detail") : "Tool execution failed");
                }
            }
            payloads.add(payload);
        }
        return payloads;
    }

    /**
     * 提取工具调用的参数字典。
     * 对标 Python progress_events.py:52-55 _tool_event_arguments()
     */
    private static Map<String, Object> toolEventArgs(Object arguments) {
        if (arguments instanceof Map<?, ?> m) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (var e : m.entrySet()) {
                if (e.getKey() instanceof String k) result.put(k, e.getValue());
            }
            return result;
        }
        return Map.of();
    }

    /**
     * 调用 on_progress 回调，根据是否支持 tool_events 选择调用方式。
     * 对标 Python progress_events.py:30-40 invoke_on_progress()
     */
    private CompletableFuture<Void> invokeOnProgress(
            String content, boolean toolHint, List<Map<String, Object>> toolEvents) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tool_hint", toolHint);
        if (toolEvents != null && !toolEvents.isEmpty()) {
            meta.put("tool_events", toolEvents);
        }
        return onProgress.apply(content, meta);
    }

    // ==================== logger ====================

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AgentProgressHook.class);
}
