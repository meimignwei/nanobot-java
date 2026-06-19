package com.nanobot.trace;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * 轻量级 OpenTelemetry 追踪工具，替代自建 TraceSpan/TraceContext/TraceCollector。
 *
 * <p>对应 Python 版 nanobot 的 LangFuse 集成策略（openai_compat_provider.py:419）：
 * Python 用 {@code langfuse.openai.AsyncOpenAI} 的 drop-in wrapper 自动追踪 LLM 调用；
 * Java 版通过 OTel API + OTLP exporter 实现等价效果，且覆盖所有 LLM provider 而非仅 OpenAI。</p>
 *
 * <h3>使用方式（与旧 TraceContext API 兼容）</h3>
 * <pre>{@code
 * TraceObservation.startTrace("turn", Map.of("sessionKey", key));
 * var span = TraceObservation.startSpan("llm.call", Map.of("model", "gpt-4"));
 * TraceObservation.endSpan(span);
 * var span2 = TraceObservation.startSpan("tool.execute", Map.of("tool", "read_file"));
 * TraceObservation.endSpan(span2);
 * TraceObservation.endTrace();
 * }</pre>
 *
 * <p>Span 属性命名遵循
 * <a href="https://opentelemetry.io/docs/specs/semconv/gen-ai/">OTel GenAI 语义规范</a>，
 * LangFuse 原生解析这些属性并展示在 UI 火焰图中。</p>
 */
@Component
public final class TraceObservation {

    private static final Logger log = LoggerFactory.getLogger(TraceObservation.class);

    /** GenAI 语义属性常量（对应 OTel GenAI 规范 v1.27+） */
    public static final String GEN_AI_OPERATION = "gen_ai.operation.name";
    public static final String GEN_AI_SYSTEM = "gen_ai.system";
    public static final String GEN_AI_REQUEST_MODEL = "gen_ai.request.model";
    public static final String GEN_AI_RESPONSE_MODEL = "gen_ai.response.model";
    public static final String GEN_AI_USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens";
    public static final String GEN_AI_USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";
    public static final String GEN_AI_REQUEST_TEMPERATURE = "gen_ai.request.temperature";
    public static final String GEN_AI_REQUEST_MAX_TOKENS = "gen_ai.request.max_tokens";
    public static final String GEN_AI_RESPONSE_FINISH_REASON = "gen_ai.response.finish_reasons";

    /** 每个线程独立的 span 栈，确保并发安全 */
    private static final ThreadLocal<Deque<SpanContext>> STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    /** Tracer 实例 */
    private static volatile Tracer tracer;

    private TraceObservation() {}

    /** Spring 注入时自动设置 tracer */
    @Autowired
    public void setTracer(Tracer t) {
        tracer = t;
    }

    /** 获取当前 tracer，未注入时回退到全局默认 */
    private static Tracer tracer() {
        Tracer t = tracer;
        if (t != null) return t;
        return GlobalOpenTelemetry.getTracer("nanobot", "0.1.0");
    }

    /**
     * 开始一个根 trace（对应 AgentLoop 的一轮 turn）。
     *
     * @param name       trace 名称（如 "turn"、"turn.system"）
     * @param attributes trace 级别属性（如 sessionKey、channel 等）
     */
    public static void startTrace(String name, Map<String, Object> attributes) {
        var span = tracer().spanBuilder(name)
                .setSpanKind(SpanKind.SERVER)
                .startSpan();
        attributes.forEach((k, v) -> {
            if (v instanceof String s) span.setAttribute(k, s);
            else if (v instanceof Number n) span.setAttribute(k, n.doubleValue());
            else if (v instanceof Boolean b) span.setAttribute(k, b);
            else if (v != null) span.setAttribute(k, v.toString());
        });
        Scope scope = span.makeCurrent();
        Deque<SpanContext> stack = STACK.get();
        stack.push(new SpanContext(span, scope));
    }

    /**
     * 在当前 trace 下创建一个子 span。
     *
     * @param name       span 名称（如 "llm.call"、"tool.execute"、"consolidate"）
     * @param attributes span 级别属性
     * @return Span 对象，用于后续 endSpan 调用；若不在 trace 中则返回 null
     */
    @Nullable
    public static Span startSpan(String name, Map<String, Object> attributes) {
        Deque<SpanContext> stack = STACK.get();
        if (stack.isEmpty()) {
            log.debug("startSpan('{}') 调用时不在 trace 上下文中，忽略", name);
            return null;
        }
        var span = tracer().spanBuilder(name)
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        attributes.forEach((k, v) -> {
            if (v instanceof String s) span.setAttribute(k, s);
            else if (v instanceof Number n) span.setAttribute(k, n.doubleValue());
            else if (v instanceof Boolean b) span.setAttribute(k, b);
            else if (v != null) span.setAttribute(k, v.toString());
        });
        Scope scope = span.makeCurrent(); // 子 span 压入当前上下文
        stack.push(new SpanContext(span, scope));
        return span;
    }

    /**
     * 结束一个 span 并返回上层上下文。
     *
     * @param span 要结束的 span（可 null，自动忽略）
     */
    public static void endSpan(@Nullable Span span) {
        if (span == null) return;
        Deque<SpanContext> stack = STACK.get();
        if (stack.isEmpty()) {
            span.end();
            return;
        }
        // 定位到匹配的 span 并结束它及其子级
        SpanContext top = stack.peek();
        if (top != null && top.span == span) {
            stack.pop();
            if (top.scope != null) top.scope.close();
            span.end();
            return;
        }
        // 可能中间有未结束的子 span，自动关闭它们
        while (!stack.isEmpty()) {
            SpanContext sc = stack.pop();
            if (sc.scope != null) sc.scope.close();
            sc.span.end();
            if (sc.span == span) return;
        }
    }

    /**
     * 结束整个 trace 并清理当前线程的 span 栈。
     */
    public static void endTrace() {
        Deque<SpanContext> stack = STACK.get();
        while (!stack.isEmpty()) {
            SpanContext sc = stack.pop();
            if (sc.scope != null) sc.scope.close();
            sc.span.setStatus(StatusCode.OK);
            sc.span.end();
        }
    }

    /**
     * 结束整个 trace（错误场景）。
     *
     * @param errorMessage 错误描述
     */
    public static void endTraceWithError(String errorMessage) {
        Deque<SpanContext> stack = STACK.get();
        while (!stack.isEmpty()) {
            SpanContext sc = stack.pop();
            if (sc.scope != null) sc.scope.close();
            sc.span.setStatus(StatusCode.ERROR, errorMessage);
            sc.span.setAttribute("error.message", errorMessage);
            sc.span.end();
        }
    }

    /**
     * 获取当前活跃的 trace root span（用于外部添加属性）。
     */
    @Nullable
    public static Span currentTrace() {
        Deque<SpanContext> stack = STACK.get();
        if (stack.isEmpty()) return null;
        return stack.peekLast().span;
    }

    /**
     * 录入 LLM 调用结果到当前 span 的 GenAI 语义属性。
     * 对应 Python langfuse.openai wrapper 自动解析出的 metadata。
     *
     * @param model            模型名
     * @param system           AI 系统（openai/anthropic/bedrock 等）
     * @param promptTokens     输入 token 数
     * @param completionTokens 输出 token 数
     * @param finishReason     结束原因
     */
    public static void recordLLMUsage(
            String model,
            String system,
            int promptTokens,
            int completionTokens,
            @Nullable String finishReason) {
        Span span = Span.current();
        if (span == null || !span.isRecording()) return;
        span.setAttribute(GEN_AI_OPERATION, "chat");
        span.setAttribute(GEN_AI_SYSTEM, system);
        span.setAttribute(GEN_AI_REQUEST_MODEL, model);
        span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, promptTokens);
        span.setAttribute(GEN_AI_USAGE_OUTPUT_TOKENS, completionTokens);
        if (finishReason != null && !finishReason.isBlank()) {
            span.setAttribute(GEN_AI_RESPONSE_FINISH_REASON, finishReason);
        }
    }

    // ---- 内部结构 ----

    /** span 及其 scope 的包装 */
    private record SpanContext(Span span, @Nullable Scope scope) {}
}
