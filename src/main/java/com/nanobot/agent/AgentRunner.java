package com.nanobot.agent;

import com.nanobot.agent.hook.AgentHook;
import com.nanobot.agent.hook.AgentHookContext;
import com.nanobot.agent.hook.AgentRunHookContext;
import com.nanobot.agent.tools.Tool;
import com.nanobot.agent.tools.ToolRegistry;
import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.LLMResponse;
import com.nanobot.providers.ToolCallRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Agent 核心迭代执行器——驱动 LLM 对话循环、工具执行、上下文治理和注入排空。
 *
 * <p>对标 Python {@code nanobot/agent/runner.py} AgentRunner 类（约 1543 行）。
 */
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    // ==================== 常量 ====================
    // 对标 Python runner.py 模块级常量

    private static final String DEFAULT_ERROR_MESSAGE = "Sorry, I encountered an error calling the AI model.";
    private static final String ARREARAGE_ERROR_MESSAGE =
            "The AI provider rejected the request because the API key is out of quota or the "
                    + "account is in arrears. Please top up / check the billing status of your API key and try again.";
    private static final String PERSISTED_MODEL_ERROR_PLACEHOLDER = "[Assistant reply unavailable due to model error.]";
    private static final int MAX_EMPTY_RETRIES = 2;
    private static final int MAX_LENGTH_RECOVERIES = 3;
    private static final int MAX_INJECTIONS_PER_TURN = 3;
    private static final int MAX_INJECTION_CYCLES = 5;
    private static final int SNIP_SAFETY_BUFFER = 1024;
    private static final int MICROCOMPACT_KEEP_RECENT = 10;
    private static final int MICROCOMPACT_MIN_CHARS = 500;
    /** 可被 microcompact 的工具结果（对标 Python COMPACTABLE_TOOLS）。 */
    private static final Set<String> COMPACTABLE_TOOLS = Set.of(
            "read_file", "exec", "grep", "find_files",
            "web_search", "web_fetch", "list_dir", "list_exec_sessions");
    /** 豁免 offload 的工具（对标 Python TOOL_RESULT_OFFLOAD_EXEMPT_TOOLS）。 */
    private static final Set<String> TOOL_RESULT_OFFLOAD_EXEMPT_TOOLS = Set.of("read_file");
    private static final String BACKFILL_CONTENT = "[Tool result unavailable — call was interrupted or lost]";

    // SSRF / workspace violation markers（对标 Python runner.py）
    private static final List<String> SSRF_MARKERS = List.of(
            "internal/private url detected", "private/internal address", "private address");
    private static final String SSRF_BOUNDARY_NOTE =
            "This is a non-bypassable security boundary. Stop trying to access "
                    + "private/internal URLs. Do not retry with curl, wget, encoded IPs, "
                    + "alternate DNS, redirects, proxies, or another tool. Ask the user for "
                    + "local files, logs, screenshots, or an explicit safe public URL instead. "
                    + "If the user explicitly trusts this private URL, ask them to whitelist "
                    + "the exact IP/CIDR via tools.ssrfWhitelist.";
    private static final List<String> WORKSPACE_VIOLATION_MARKERS = List.of(
            "outside the configured workspace", "outside allowed directory",
            "working_dir is outside", "working_dir could not be resolved",
            "path outside working dir", "path traversal detected");

    private final LLMProvider provider;

    public AgentRunner(LLMProvider provider) {
        this.provider = provider;
    }

    // ==================== 内部状态类 ====================

    /** 核心循环状态，对标 Python _RunCoreState。 */
    private static class RunCoreState {
        String finalContent;
        final List<String> toolsUsed = new ArrayList<>();
        final Map<String, Integer> usage = new HashMap<>(Map.of("prompt_tokens", 0, "completion_tokens", 0));
        String error;
        String stopReason = "completed";
        final List<Map<String, String>> toolEvents = new ArrayList<>();
        final Map<String, Integer> externalLookupCounts = new HashMap<>();
        final Map<String, Integer> workspaceViolationCounts = new HashMap<>();
        int emptyContentRetries;
        int lengthRecoveryCount;
        boolean hadInjections;
        int injectionCycles;
    }

    /** 工具执行结果，对标 Python _ToolOutcome。 */
    private record ToolOutcome(List<Object> results, List<Map<String, String>> events, Throwable fatalError) {}

    /** 注入排空结果，对标 Python _DrainResult。 */
    private record DrainResult(boolean shouldContinue, int cycles) {}

    // ==================== run 入口 ====================

    /**
     * 执行 agent 核心循环，返回最终结果。
     * 对标 Python {@code async def run(spec)}。
     *
     * @param spec 运行参数规格
     * @return 执行结果的 CompletableFuture
     */
    public CompletableFuture<AgentRunResult> run(AgentRunSpec spec) {
        AgentHook hook = spec.hook() != null ? spec.hook() : new com.nanobot.agent.hook.AgentHookAdapter(false) {};
        List<Map<String, Object>> messages = new ArrayList<>(RuntimeUtils.deepCopyMessages(spec.initialMessages()));
        AgentRunHookContext runCtx = new AgentRunHookContext();
        runCtx.setMessages(RuntimeUtils.deepCopyMessages(messages));

        CompletableFuture<AgentRunResult> core = hook.beforeRun(runCtx)
                .thenCompose(v -> runCore(spec, hook, messages));

        return core.handle((result, ex) -> {
            if (ex != null) {
                runCtx.setMessages(RuntimeUtils.deepCopyMessages(messages));
                Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
                if (cause instanceof CancellationException) {
                    runCtx.setStopReason("cancelled");
                    runCtx.setError(null);
                } else {
                    runCtx.setStopReason("error");
                    runCtx.setError("Error: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                }
                runCtx.setException(cause);
                try { hook.onError(runCtx).join(); } catch (Exception ignored) {}
                return CompletableFuture.<AgentRunResult>failedFuture(cause);
            }
            runCtx.setMessages(RuntimeUtils.deepCopyMessages(result.messages()));
            runCtx.setFinalContent(result.finalContent());
            runCtx.setToolsUsed(new ArrayList<>(result.toolsUsed()));
            runCtx.setUsage(new HashMap<>(result.usage()));
            runCtx.setStopReason(result.stopReason());
            runCtx.setError(result.error());
            runCtx.setToolEvents(result.toolEvents() != null ? new ArrayList<>(result.toolEvents()) : new ArrayList<>());
            runCtx.setHadInjections(result.hadInjections());
            runCtx.setException(null);
            CompletableFuture<Void> errHook = (runCtx.getError() != null)
                    ? hook.onError(runCtx) : CompletableFuture.completedFuture(null);
            return errHook.thenCompose(v -> hook.afterRun(runCtx).thenApply(v2 -> result));
        }).thenCompose(cf -> cf)
                .whenComplete((result, ex) -> {
                    runCtx.setMessages(RuntimeUtils.deepCopyMessages(messages));
                    try { hook.onFinally(runCtx).join(); } catch (Exception e) { /* log */ }
                });
    }

    // ==================== 核心循环 ====================

    /** 对标 Python _run_core()。 */
    private CompletableFuture<AgentRunResult> runCore(AgentRunSpec spec, AgentHook hook,
                                                       List<Map<String, Object>> messages) {
        RunCoreState s = new RunCoreState();
        return runCoreIter(spec, hook, messages, s, 0);
    }

    /** 对标 Python _run_core_iter()——单次迭代。 */
    private CompletableFuture<AgentRunResult> runCoreIter(
            AgentRunSpec spec, AgentHook hook,
            List<Map<String, Object>> messages, RunCoreState s, int iteration) {

        if (iteration >= spec.maxIterations()) {
            return handleMaxIterations(spec, hook, messages, s);
        }

        // 1. Context governance
        final List<Map<String, Object>> messagesForModel = computeMessagesForModel(spec, messages);

        AgentHookContext ctx = new AgentHookContext();
        ctx.setIteration(iteration);
        ctx.setMessages(messages);
        ctx.setSessionKey(spec.sessionKey());

        return hook.beforeIteration(ctx)
                .thenCompose(v -> requestModel(spec, messagesForModel, hook, ctx))
                .thenCompose(response -> {
                    ctx.setResponse(response);
                    ctx.setToolCalls(new ArrayList<>(response.toolCalls()));

                    String reasoningText = response.reasoningContent();
                    if (reasoningText != null && !reasoningText.isEmpty() && !ctx.isStreamedReasoning()) {
                        return hook.emitReasoning(reasoningText)
                                .thenCompose(v -> hook.emitReasoningEnd())
                                .thenApply(v -> {
                                    ctx.setStreamedReasoning(true);
                                    return response;
                                });
                    }
                    return CompletableFuture.completedFuture(response);
                })
                .thenCompose(response -> {
                    Map<String, Integer> rawUsage = usageOrEstimate(spec, messagesForModel, response);
                    ctx.setUsage(new HashMap<>(rawUsage));
                    accumulateUsage(s.usage, rawUsage);

                    if (response.shouldExecuteTools()) {
                        return handleToolBranch(spec, hook, messages, messagesForModel, s, ctx, iteration, response);
                    }
                    return handleFinalBranch(spec, hook, messages, messagesForModel, s, ctx, iteration, response);
                });
    }

    // ==================== tool branch ====================

    /** 对标 Python _handle_tool_branch()。 */
    @SuppressWarnings("unchecked")
    private CompletableFuture<AgentRunResult> handleToolBranch(
            AgentRunSpec spec, AgentHook hook,
            List<Map<String, Object>> messages, List<Map<String, Object>> messagesForModel,
            RunCoreState s, AgentHookContext ctx, int iteration, LLMResponse response) {

        return endStreamIfWanted(hook, ctx, true)
                .thenCompose(v -> {
                    Map<String, Object> assistantMessage = RuntimeUtils.buildAssistantMessage(
                            response.content() != null ? response.content() : "",
                            response.toolCalls(), response.reasoningContent(), response.thinkingBlocks());
                    messages.add(assistantMessage);
                    Map<String, Object> checkpointPayload = new LinkedHashMap<>();
                    checkpointPayload.put("phase", "awaiting_tools");
                    checkpointPayload.put("iteration", iteration);
                    checkpointPayload.put("model", spec.model());
                    checkpointPayload.put("assistant_message", assistantMessage);
                    checkpointPayload.put("completed_tool_results", List.of());
                    checkpointPayload.put("pending_tool_calls", response.toolCalls());
                    return emitCheckpoint(spec, checkpointPayload)
                            .thenCompose(v2 -> hook.beforeExecuteTools(ctx))
                            .thenCompose(v2 -> executeTools(spec, response.toolCalls(),
                                    s.externalLookupCounts, s.workspaceViolationCounts));
                })
                .thenCompose(toolOutcome -> {
                    List<Object> results = toolOutcome.results;
                    List<Map<String, String>> newEvents = toolOutcome.events;
                    Throwable fatalError = toolOutcome.fatalError;

                    s.toolEvents.addAll(newEvents);
                    for (int i = 0; i < response.toolCalls().size(); i++) {
                        if (i < newEvents.size() && "ok".equals(newEvents.get(i).get("status"))) {
                            s.toolsUsed.add(response.toolCalls().get(i).name());
                        }
                    }
                    ctx.setToolResults(new ArrayList<>(results));
                    ctx.setToolEvents(new ArrayList<>(newEvents));

                    List<Map<String, Object>> completedToolResults = new ArrayList<>();
                    for (int i = 0; i < response.toolCalls().size(); i++) {
                        ToolCallRequest tc = response.toolCalls().get(i);
                        Map<String, Object> toolMessage = new LinkedHashMap<>();
                        toolMessage.put("role", "tool");
                        toolMessage.put("tool_call_id", tc.id());
                        toolMessage.put("name", tc.name());
                        toolMessage.put("content", normalizeToolResult(spec, tc.id(), tc.name(),
                                i < results.size() ? results.get(i) : null));
                        messages.add(toolMessage);
                        completedToolResults.add(toolMessage);
                    }

                    if (fatalError != null) {
                        s.error = "Error: " + fatalError.getClass().getSimpleName() + ": " + fatalError.getMessage();
                        s.finalContent = s.error;
                        s.stopReason = "tool_error";
                        appendFinalMessage(messages, s.finalContent);
                        ctx.setFinalContent(s.finalContent);
                        ctx.setError(s.error);
                        ctx.setStopReason(s.stopReason);
                        return hook.afterIteration(ctx)
                                .thenCompose(v -> tryDrainInjections(spec, messages, null,
                                        s.injectionCycles, "after tool error", iteration, false))
                                .thenCompose(drain -> {
                                    if (drain.shouldContinue) {
                                        s.hadInjections = true;
                                        s.injectionCycles = drain.cycles;
                                        return runCoreIter(spec, hook, messages, s, iteration + 1);
                                    }
                                    return CompletableFuture.completedFuture(buildResult(messages, s));
                                });
                    }

                    Map<String, Object> cpPayload = new LinkedHashMap<>();
                    cpPayload.put("phase", "tools_completed");
                    cpPayload.put("iteration", iteration);
                    cpPayload.put("model", spec.model());
                    cpPayload.put("assistant_message", messages.get(messages.size() - 1 - completedToolResults.size()));
                    cpPayload.put("completed_tool_results", completedToolResults);
                    cpPayload.put("pending_tool_calls", List.of());
                    return emitCheckpoint(spec, cpPayload).thenCompose(v -> {
                        s.emptyContentRetries = 0;
                        s.lengthRecoveryCount = 0;
                        return tryDrainInjections(spec, messages, null, s.injectionCycles,
                                "after tool execution", iteration, false);
                    }).thenCompose(drain -> {
                        if (drain.shouldContinue) {
                            s.hadInjections = true;
                            s.injectionCycles = drain.cycles;
                        }
                        return hook.afterIteration(ctx)
                                .thenCompose(v -> runCoreIter(spec, hook, messages, s, iteration + 1));
                    });
                });
    }

    // ==================== final branch ====================

    /** 对标 Python _handle_final_branch()。 */
    @SuppressWarnings("unchecked")
    private CompletableFuture<AgentRunResult> handleFinalBranch(
            AgentRunSpec spec, AgentHook hook,
            List<Map<String, Object>> messages, List<Map<String, Object>> messagesForModel,
            RunCoreState s, AgentHookContext ctx, int iteration, LLMResponse response) {

        String clean = hook.finalizeContent(ctx, response.content());

        if (!"error".equals(response.finishReason()) && RuntimeUtils.isBlankText(clean)) {
            s.emptyContentRetries++;
            if (s.emptyContentRetries < MAX_EMPTY_RETRIES) {
                return endStreamIfWanted(hook, ctx, false)
                        .thenCompose(v -> hook.afterIteration(ctx))
                        .thenCompose(v -> runCoreIter(spec, hook, messages, s, iteration + 1));
            }
            return endStreamIfWanted(hook, ctx, false)
                    .thenCompose(v -> requestFinalizationRetry(spec, messagesForModel))
                    .thenCompose(retryResponse -> {
                        List<Map<String, Object>> retryMessages = finalizationRetryMessages(messagesForModel);
                        Map<String, Integer> retryUsage = usageOrEstimate(spec, retryMessages, retryResponse);
                        accumulateUsage(s.usage, retryUsage);
                        ctx.setResponse(retryResponse);
                        ctx.setUsage(mergeUsage(ctx.getUsage(), retryUsage));
                        ctx.setToolCalls(new ArrayList<>(retryResponse.toolCalls()));
                        String retryClean = hook.finalizeContent(ctx, retryResponse.content());
                        return finishFinalBranch(spec, hook, messages, s, ctx, iteration, retryResponse, retryClean);
                    });
        }
        return finishFinalBranch(spec, hook, messages, s, ctx, iteration, response, clean);
    }

    /** 对标 Python _finish_final_branch()。 */
    @SuppressWarnings("unchecked")
    private CompletableFuture<AgentRunResult> finishFinalBranch(
            AgentRunSpec spec, AgentHook hook,
            List<Map<String, Object>> messages, RunCoreState s,
            AgentHookContext ctx, int iteration, LLMResponse response, String clean) {

        if ("length".equals(response.finishReason()) && !RuntimeUtils.isBlankText(clean)) {
            s.lengthRecoveryCount++;
            if (s.lengthRecoveryCount <= MAX_LENGTH_RECOVERIES) {
                return endStreamIfWanted(hook, ctx, true)
                        .thenCompose(v -> {
                            messages.add(RuntimeUtils.buildAssistantMessage(clean,
                                    response.reasoningContent(), response.thinkingBlocks()));
                            messages.add(RuntimeUtils.buildLengthRecoveryMessage());
                            return hook.afterIteration(ctx);
                        })
                        .thenCompose(v -> runCoreIter(spec, hook, messages, s, iteration + 1));
            }
        }

        final Map<String, Object> assistantMessage =
                (!"error".equals(response.finishReason()) && !RuntimeUtils.isBlankText(clean))
                ? RuntimeUtils.buildAssistantMessage(clean,
                        response.reasoningContent(), response.thinkingBlocks())
                : null;

        return tryDrainInjections(spec, messages, assistantMessage, s.injectionCycles,
                "after final response", iteration, true)
                .thenCompose(drainResult -> {
                    boolean shouldContinue = drainResult.shouldContinue;
                    s.injectionCycles = drainResult.cycles;
                    if (shouldContinue) s.hadInjections = true;

                    return endStreamIfWanted(hook, ctx, shouldContinue)
                            .thenCompose(v -> {
                                if (shouldContinue) {
                                    return hook.afterIteration(ctx)
                                            .thenCompose(v2 -> runCoreIter(spec, hook, messages, s, iteration + 1));
                                }

                                if ("error".equals(response.finishReason())) {
                                    if (LLMProvider.isArrearageResponse(response)) {
                                        s.finalContent = ARREARAGE_ERROR_MESSAGE;
                                    } else {
                                        s.finalContent = clean != null ? clean
                                                : (spec.errorMessage() != null ? spec.errorMessage() : DEFAULT_ERROR_MESSAGE);
                                    }
                                    s.stopReason = "error";
                                    s.error = s.finalContent;
                                    appendModelErrorPlaceholder(messages);
                                    ctx.setFinalContent(s.finalContent);
                                    ctx.setError(s.error);
                                    ctx.setStopReason(s.stopReason);
                                    return hook.afterIteration(ctx)
                                            .thenCompose(v2 -> tryDrainInjections(spec, messages, null,
                                                    s.injectionCycles, "after LLM error", iteration, false))
                                            .thenCompose(drain2 -> {
                                                if (drain2.shouldContinue) {
                                                    s.hadInjections = true;
                                                    s.injectionCycles = drain2.cycles;
                                                    return runCoreIter(spec, hook, messages, s, iteration + 1);
                                                }
                                                return CompletableFuture.completedFuture(buildResult(messages, s));
                                            });
                                }

                                if (RuntimeUtils.isBlankText(clean)) {
                                    s.finalContent = RuntimeUtils.EMPTY_FINAL_RESPONSE_MESSAGE;
                                    s.stopReason = "empty_final_response";
                                    s.error = s.finalContent;
                                    appendFinalMessage(messages, s.finalContent);
                                    ctx.setFinalContent(s.finalContent);
                                    ctx.setError(s.error);
                                    ctx.setStopReason(s.stopReason);
                                    return hook.afterIteration(ctx)
                                            .thenCompose(v2 -> tryDrainInjections(spec, messages, null,
                                                    s.injectionCycles, "after empty response", iteration, false))
                                            .thenCompose(drain2 -> {
                                                if (drain2.shouldContinue) {
                                                    s.hadInjections = true;
                                                    s.injectionCycles = drain2.cycles;
                                                    return runCoreIter(spec, hook, messages, s, iteration + 1);
                                                }
                                                return CompletableFuture.completedFuture(buildResult(messages, s));
                                            });
                                }

                                Map<String, Object> finalAssistant = assistantMessage != null ? assistantMessage
                                        : RuntimeUtils.buildAssistantMessage(clean,
                                        response.reasoningContent(), response.thinkingBlocks());
                                messages.add(finalAssistant);
                                Map<String, Object> cp = new LinkedHashMap<>();
                                cp.put("phase", "final_response");
                                cp.put("iteration", iteration);
                                cp.put("model", spec.model());
                                cp.put("assistant_message", finalAssistant);
                                cp.put("completed_tool_results", List.of());
                                cp.put("pending_tool_calls", List.of());
                                return emitCheckpoint(spec, cp).thenCompose(v2 -> {
                                    s.finalContent = clean;
                                    ctx.setFinalContent(s.finalContent);
                                    ctx.setStopReason(s.stopReason);
                                    return hook.afterIteration(ctx)
                                            .thenApply(v3 -> buildResult(messages, s));
                                });
                            });
                });
    }

    // ==================== max iterations ====================

    /** 对标 Python _handle_max_iterations()。 */
    private CompletableFuture<AgentRunResult> handleMaxIterations(
            AgentRunSpec spec, AgentHook hook,
            List<Map<String, Object>> messages, RunCoreState s) {
        s.stopReason = "max_iterations";
        return tryDrainInjections(spec, messages, null, s.injectionCycles,
                "after max_iterations", null, false)
                .thenCompose(drainResult -> {
                    if (drainResult.shouldContinue) {
                        s.hadInjections = true;
                        s.injectionCycles = drainResult.cycles;
                    }
                    s.finalContent = null;
                    if (spec.finalizeOnMaxIterations()) {
                        return tryFinalizeAfterMaxIterations(spec, hook, messages, s.usage)
                                .thenApply(finalizeContent -> {
                                    if (finalizeContent == null) {
                                        finalizeContent = maxIterationsFallback(spec);
                                    }
                                    s.finalContent = finalizeContent;
                                    appendFinalMessage(messages, s.finalContent);
                                    return buildResult(messages, s);
                                });
                    }
                    s.finalContent = maxIterationsFallback(spec);
                    appendFinalMessage(messages, s.finalContent);
                    return CompletableFuture.completedFuture(buildResult(messages, s));
                });
    }

    private CompletableFuture<String> tryFinalizeAfterMaxIterations(
            AgentRunSpec spec, AgentHook hook,
            List<Map<String, Object>> messages, Map<String, Integer> usage) {
        List<Map<String, Object>> retryMessages = budgetExhaustedFinalizationMessages(messages);
        return requestNoTools(spec, retryMessages)
                .thenApply(response -> {
                    Map<String, Integer> rawUsage = usageOrEstimate(spec, retryMessages, response);
                    accumulateUsage(usage, rawUsage);
                    if ("error".equals(response.finishReason()) || response.hasToolCalls()) {
                        return null;
                    }
                    AgentHookContext ctx = new AgentHookContext();
                    ctx.setIteration(spec.maxIterations());
                    ctx.setMessages(messages);
                    ctx.setResponse(response);
                    ctx.setUsage(new HashMap<>(rawUsage));
                    ctx.setSessionKey(spec.sessionKey());
                    String clean = hook.finalizeContent(ctx, response.content());
                    return RuntimeUtils.isBlankText(clean) ? null : clean;
                })
                .exceptionally(ex -> null);
    }

    // ==================== context governance ====================

    /** 安全获取 governContext 结果，异常时回退。 */
    private List<Map<String, Object>> computeMessagesForModel(
            AgentRunSpec spec, List<Map<String, Object>> messages) {
        try {
            return governContext(spec, messages);
        } catch (Exception e) {
            try {
                return backfillMissingToolResults(dropOrphanToolResults(messages));
            } catch (Exception e2) {
                return messages;
            }
        }
    }

    /** 对标 Python _govern_context()——多层上下文治理管道。 */
    private List<Map<String, Object>> governContext(AgentRunSpec spec, List<Map<String, Object>> messages) {
        List<Map<String, Object>> m = dropOrphanToolResults(messages);
        m = backfillMissingToolResults(m);
        m = microcompact(m);
        m = applyToolResultBudget(spec, m);
        m = snipHistory(spec, m);
        m = dropOrphanToolResults(m);
        m = backfillMissingToolResults(m);
        return m;
    }

    /** 对标 Python _drop_orphan_tool_results()——删除无对应 assistant tool_calls 的 tool 消息。 */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> dropOrphanToolResults(List<Map<String, Object>> messages) {
        Set<String> declared = new HashSet<>();
        List<Map<String, Object>> updated = null;
        for (int idx = 0; idx < messages.size(); idx++) {
            Map<String, Object> msg = messages.get(idx);
            String role = (String) msg.get("role");
            if ("assistant".equals(role)) {
                List<Map<String, Object>> tcs = (List<Map<String, Object>>) msg.get("tool_calls");
                if (tcs != null) {
                    for (Map<String, Object> tc : tcs) {
                        if (tc != null && tc.get("id") != null) {
                            declared.add(tc.get("id").toString());
                        }
                    }
                }
            }
            if ("tool".equals(role)) {
                String tid = (String) msg.get("tool_call_id");
                if (tid != null && !declared.contains(tid)) {
                    if (updated == null) {
                        updated = new ArrayList<>();
                        for (int j = 0; j < idx; j++) updated.add(new LinkedHashMap<>(messages.get(j)));
                    }
                    continue;
                }
            }
            if (updated != null) updated.add(new LinkedHashMap<>(msg));
        }
        return updated != null ? updated : messages;
    }

    /** 对标 Python _backfill_missing_tool_results()——回填缺失的 tool result。 */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> backfillMissingToolResults(List<Map<String, Object>> messages) {
        List<Object[]> declared = new ArrayList<>();
        Set<String> fulfilled = new HashSet<>();
        for (int idx = 0; idx < messages.size(); idx++) {
            Map<String, Object> msg = messages.get(idx);
            String role = (String) msg.get("role");
            if ("assistant".equals(role)) {
                List<Map<String, Object>> tcs = (List<Map<String, Object>>) msg.get("tool_calls");
                if (tcs != null) {
                    for (Map<String, Object> tc : tcs) {
                        if (tc != null && tc.get("id") != null) {
                            String name = "";
                            Map<String, Object> func = (Map<String, Object>) tc.get("function");
                            if (func != null) name = (String) func.get("name");
                            declared.add(new Object[]{idx, tc.get("id").toString(), name});
                        }
                    }
                }
            } else if ("tool".equals(role)) {
                String tid = (String) msg.get("tool_call_id");
                if (tid != null) fulfilled.add(tid);
            }
        }
        List<Object[]> missing = new ArrayList<>();
        for (Object[] d : declared) {
            if (!fulfilled.contains((String) d[1])) missing.add(d);
        }
        if (missing.isEmpty()) return messages;

        List<Map<String, Object>> updated = new ArrayList<>();
        for (Map<String, Object> m : messages) updated.add(new LinkedHashMap<>(m));
        int offset = 0;
        for (Object[] m : missing) {
            int assistantIdx = (Integer) m[0];
            String callId = (String) m[1];
            String name = (String) m[2];
            int insertAt = assistantIdx + 1 + offset;
            while (insertAt < updated.size() && "tool".equals(updated.get(insertAt).get("role"))) {
                insertAt++;
            }
            Map<String, Object> fill = new LinkedHashMap<>();
            fill.put("role", "tool");
            fill.put("tool_call_id", callId);
            fill.put("name", name);
            fill.put("content", BACKFILL_CONTENT);
            updated.add(insertAt, fill);
            offset++;
        }
        return updated;
    }

    /** 对标 Python _microcompact()——压缩旧的可压缩 tool 结果。 */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> microcompact(List<Map<String, Object>> messages) {
        List<Integer> compactableIndices = new ArrayList<>();
        for (int idx = 0; idx < messages.size(); idx++) {
            Map<String, Object> msg = messages.get(idx);
            if ("tool".equals(msg.get("role")) && COMPACTABLE_TOOLS.contains(msg.get("name"))) {
                compactableIndices.add(idx);
            }
        }
        if (compactableIndices.size() <= MICROCOMPACT_KEEP_RECENT) return messages;
        List<Integer> stale = compactableIndices.subList(0, compactableIndices.size() - MICROCOMPACT_KEEP_RECENT);
        List<Map<String, Object>> updated = null;
        for (int idx : stale) {
            Map<String, Object> msg = messages.get(idx);
            Object content = msg.get("content");
            if (!(content instanceof String str) || str.length() < MICROCOMPACT_MIN_CHARS) continue;
            String name = (String) msg.getOrDefault("name", "tool");
            String summary = "[" + name + " result omitted from context]";
            if (updated == null) {
                updated = new ArrayList<>();
                for (Map<String, Object> m : messages) updated.add(new LinkedHashMap<>(m));
            }
            updated.get(idx).put("content", summary);
        }
        return updated != null ? updated : messages;
    }

    /** 对标 Python _apply_tool_result_budget()——裁剪超长 tool result。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> applyToolResultBudget(AgentRunSpec spec, List<Map<String, Object>> messages) {
        List<Map<String, Object>> updated = messages;
        for (int idx = 0; idx < messages.size(); idx++) {
            Map<String, Object> msg = messages.get(idx);
            if (!"tool".equals(msg.get("role"))) continue;
            String tci = msg.get("tool_call_id") != null ? msg.get("tool_call_id").toString() : "tool_" + idx;
            String name = msg.get("name") != null ? msg.get("name").toString() : "tool";
            Object normalized = normalizeToolResult(spec, tci, name, msg.get("content"));
            if (!Objects.equals(normalized, msg.get("content"))) {
                if (updated == messages) {
                    updated = new ArrayList<>();
                    for (Map<String, Object> m : messages) updated.add(new LinkedHashMap<>(m));
                }
                updated.get(idx).put("content", normalized);
            }
        }
        return updated;
    }

    /** 对标 Python _snip_history()——token 预算超出时从历史头部裁剪。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> snipHistory(AgentRunSpec spec, List<Map<String, Object>> messages) {
        if (messages.isEmpty() || spec.contextWindowTokens() == null) return messages;
        int providerMaxTokens = 4096;
        try { providerMaxTokens = provider.getGeneration().maxTokens(); } catch (Exception ignored) {}
        int maxOutput = spec.maxTokens() != null ? spec.maxTokens() : providerMaxTokens;
        int budget = spec.contextBlockLimit() != null ? spec.contextBlockLimit()
                : spec.contextWindowTokens() - maxOutput - SNIP_SAFETY_BUFFER;
        if (budget <= 0) return messages;

        int estimate = RuntimeUtils.estimatePromptTokensChain(provider, spec.model(), messages, spec.tools().getDefinitions());
        if (estimate <= budget) return messages;

        List<Map<String, Object>> systemMessages = new ArrayList<>();
        List<Map<String, Object>> nonSystem = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            if ("system".equals(msg.get("role"))) systemMessages.add(new LinkedHashMap<>(msg));
            else nonSystem.add(new LinkedHashMap<>(msg));
        }
        if (nonSystem.isEmpty()) return messages;

        int systemTokens = systemMessages.stream().mapToInt(RuntimeUtils::estimateMessageTokens).sum();
        int fixedTokens = RuntimeUtils.estimatePromptTokensChain(provider, spec.model(), systemMessages, spec.tools().getDefinitions());
        int remainingBudget = Math.max(0, budget - Math.max(systemTokens, fixedTokens));

        List<Map<String, Object>> kept = new ArrayList<>();
        int keptTokens = 0;
        for (int i = nonSystem.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = nonSystem.get(i);
            int msgTokens = RuntimeUtils.estimateMessageTokens(msg);
            if (!kept.isEmpty() && keptTokens + msgTokens > remainingBudget) break;
            kept.add(0, msg);
            keptTokens += msgTokens;
        }

        if (!kept.isEmpty()) {
            boolean foundUser = false;
            for (int i = 0; i < kept.size(); i++) {
                if ("user".equals(kept.get(i).get("role"))) {
                    kept = kept.subList(i, kept.size());
                    foundUser = true;
                    break;
                }
            }
            if (!foundUser) {
                for (int i = nonSystem.size() - 1; i >= 0; i--) {
                    if ("user".equals(nonSystem.get(i).get("role"))) {
                        kept = nonSystem.subList(i, nonSystem.size());
                        break;
                    }
                }
            }
            int start = RuntimeUtils.findLegalMessageStart(kept);
            if (start > 0) kept = kept.subList(start, kept.size());
        }
        if (kept.isEmpty()) {
            kept = nonSystem.subList(Math.max(0, nonSystem.size() - 4), nonSystem.size());
            int start = RuntimeUtils.findLegalMessageStart(kept);
            if (start > 0) kept = kept.subList(start, kept.size());
        }
        List<Map<String, Object>> result = new ArrayList<>(systemMessages);
        result.addAll(kept);
        return result;
    }

    // ==================== LLM request ====================

    /** 对标 Python _request_model()——选择流式/进度/普通路径发送 LLM 请求。 */
    private CompletableFuture<LLMResponse> requestModel(
            AgentRunSpec spec, List<Map<String, Object>> messages,
            AgentHook hook, AgentHookContext ctx) {
        double timeoutS = spec.llmTimeoutS() != null ? spec.llmTimeoutS() : 300.0;
        String envTimeout = System.getenv("NANOBOT_LLM_TIMEOUT_S");
        if (envTimeout != null) {
            try { timeoutS = Double.parseDouble(envTimeout.trim()); } catch (NumberFormatException ignored) {}
        }
        if (timeoutS <= 0) timeoutS = Double.NaN;

        boolean wantsStreaming = hook.wantsStreaming();
        boolean wantsProgressStreaming = !wantsStreaming && spec.streamProgressDeltas()
                && spec.progressCallback() != null && provider.supportsProgressDeltas();

        CompletableFuture<LLMResponse> coro;
        if (wantsStreaming) {
            coro = provider.chatStreamWithRetry(
                    messages, spec.tools().getDefinitions(), spec.model(),
                    spec.maxTokens(), spec.temperature(), spec.reasoningEffort(), null,
                    delta -> { ctx.setStreamedContent(true); return hook.onStream(ctx, delta); },
                    delta -> {
                        if (delta != null && !delta.isEmpty()) {
                            ctx.setStreamedReasoning(true);
                            return hook.emitReasoning(delta);
                        }
                        return CompletableFuture.completedFuture(null);
                    },
                    null,
                    () -> hook.onStreamEnd(ctx, true),
                    spec.providerRetryMode(),
                    spec.retryWaitCallback() != null
                            ? spec.retryWaitCallback()::apply
                            : null);
        } else if (wantsProgressStreaming) {
            String[] streamBuf = {""};
            coro = provider.chatStreamWithRetry(
                    messages, spec.tools().getDefinitions(), spec.model(),
                    spec.maxTokens(), spec.temperature(), spec.reasoningEffort(), null,
                    delta -> {
                        if (delta == null || delta.isEmpty()) return CompletableFuture.completedFuture(null);
                        String prevClean = RuntimeUtils.stripThink(streamBuf[0]);
                        streamBuf[0] += delta;
                        String newClean = RuntimeUtils.stripThink(streamBuf[0]);
                        String incremental = newClean.substring(prevClean.length());
                        if (!incremental.isEmpty()) {
                            ctx.setStreamedContent(true);
                            if (spec.progressCallback() != null) {
                                return spec.progressCallback().apply(incremental, Map.of());
                            }
                        }
                        return CompletableFuture.completedFuture(null);
                    },
                    null, null, null,
                    spec.providerRetryMode(),
                    spec.retryWaitCallback() != null
                            ? spec.retryWaitCallback()::apply
                            : null);
        } else {
            coro = provider.chatWithRetry(
                    messages, spec.tools().getDefinitions(), spec.model(),
                    spec.maxTokens(), spec.temperature(), spec.reasoningEffort(), null,
                    spec.providerRetryMode(),
                    spec.retryWaitCallback() != null
                            ? spec.retryWaitCallback()::apply
                            : null);
        }

        Double outerTimeout = (wantsStreaming || wantsProgressStreaming) ? null : timeoutS;
        if (outerTimeout != null && !Double.isNaN(outerTimeout)) {
            return coro.orTimeout((long) (outerTimeout * 1000), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> {
                        if (ex instanceof java.util.concurrent.TimeoutException) {
                            return new LLMResponse(
                                    "Error calling LLM: timed out after " + outerTimeout + "s", "error");
                        }
                        throw new RuntimeException(ex);
                    });
        }
        return coro;
    }

    private CompletableFuture<LLMResponse> requestNoTools(AgentRunSpec spec,
                                                            List<Map<String, Object>> messages) {
        return provider.chatWithRetry(
                messages, null, spec.model(),
                spec.maxTokens(), spec.temperature(), spec.reasoningEffort(), null,
                spec.providerRetryMode(), null);
    }

    private CompletableFuture<LLMResponse> requestFinalizationRetry(AgentRunSpec spec,
                                                                      List<Map<String, Object>> messages) {
        return requestNoTools(spec, finalizationRetryMessages(messages));
    }

    private static List<Map<String, Object>> finalizationRetryMessages(List<Map<String, Object>> messages) {
        List<Map<String, Object>> retry = new ArrayList<>(messages);
        retry.add(RuntimeUtils.buildFinalizationRetryMessage());
        return retry;
    }

    private static List<Map<String, Object>> budgetExhaustedFinalizationMessages(List<Map<String, Object>> messages) {
        List<Map<String, Object>> retry = new ArrayList<>(messages);
        retry.add(RuntimeUtils.buildBudgetExhaustedFinalizationMessage());
        return retry;
    }

    private static String maxIterationsFallback(AgentRunSpec spec) {
        if (spec.maxIterationsMessage() != null) {
            return spec.maxIterationsMessage().replace("{max_iterations}", String.valueOf(spec.maxIterations()));
        }
        return RuntimeUtils.renderTemplate("agent/max_iterations_message.md",
                Map.of("max_iterations", spec.maxIterations()));
    }

    // ==================== tool execution ====================

    /** 对标 Python _execute_tools()——分批并行/串行执行工具调用。 */
    private CompletableFuture<ToolOutcome> executeTools(
            AgentRunSpec spec, List<ToolCallRequest> toolCalls,
            Map<String, Integer> externalLookupCounts,
            Map<String, Integer> workspaceViolationCounts) {
        List<List<ToolCallRequest>> batches = partitionToolBatches(spec, toolCalls);
        List<CompletableFuture<List<ToolOutcome>>> batchFutures = new ArrayList<>();

        for (List<ToolCallRequest> batch : batches) {
            if (spec.concurrentTools() && batch.size() > 1) {
                List<CompletableFuture<ToolOutcome>> futures = new ArrayList<>();
                for (ToolCallRequest tc : batch) {
                    futures.add(runTool(spec, tc, externalLookupCounts, workspaceViolationCounts));
                }
                batchFutures.add(CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .thenApply(v -> futures.stream().map(CompletableFuture::join).toList()));
            } else {
                CompletableFuture<List<ToolOutcome>> chain = CompletableFuture.completedFuture(new ArrayList<>());
                for (ToolCallRequest tc : batch) {
                    chain = chain.thenCompose(list ->
                            runTool(spec, tc, externalLookupCounts, workspaceViolationCounts)
                                    .thenApply(o -> { list.add(o); return list; }));
                }
                batchFutures.add(chain);
            }
        }

        return CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<Object> results = new ArrayList<>();
                    List<Map<String, String>> events = new ArrayList<>();
                    Throwable fatal = null;
                    for (CompletableFuture<List<ToolOutcome>> bf : batchFutures) {
                        for (ToolOutcome o : bf.join()) {
                            results.addAll(o.results());
                            events.addAll(o.events());
                            if (o.fatalError() != null && fatal == null) fatal = o.fatalError();
                        }
                    }
                    return new ToolOutcome(results, events, fatal);
                });
    }

    /** 对标 Python _partition_tool_batches()——按并发安全性将工具调用分组。 */
    private List<List<ToolCallRequest>> partitionToolBatches(AgentRunSpec spec, List<ToolCallRequest> toolCalls) {
        if (!spec.concurrentTools()) {
            List<List<ToolCallRequest>> result = new ArrayList<>();
            for (ToolCallRequest tc : toolCalls) result.add(List.of(tc));
            return result;
        }
        List<List<ToolCallRequest>> batches = new ArrayList<>();
        List<ToolCallRequest> current = new ArrayList<>();
        for (ToolCallRequest tc : toolCalls) {
            Tool tool = spec.tools().get(tc.name());
            boolean canBatch = tool != null && tool.isConcurrencySafe();
            if (canBatch) {
                current.add(tc);
            } else {
                if (!current.isEmpty()) {
                    batches.add(new ArrayList<>(current));
                    current.clear();
                }
                batches.add(List.of(tc));
            }
        }
        if (!current.isEmpty()) batches.add(current);
        return batches;
    }

    /** 对标 Python _run_tool()——执行单个工具调用，含错误分类和外查找/工作区违规检测。 */
    @SuppressWarnings("unchecked")
    private CompletableFuture<ToolOutcome> runTool(
            AgentRunSpec spec, ToolCallRequest toolCall,
            Map<String, Integer> externalLookupCounts,
            Map<String, Integer> workspaceViolationCounts) {
        String hint = "\n\n[Analyze the error above and try a different approach.]";

        String lookupError = RuntimeUtils.repeatedExternalLookupError(
                toolCall.name(), (Map<String, Object>) toolCall.arguments(), externalLookupCounts);
        if (lookupError != null) {
            Map<String, String> event = new HashMap<>(Map.of(
                    "name", toolCall.name(), "status", "error", "detail", "repeated external lookup blocked"));
            if (spec.failOnToolError()) {
                return CompletableFuture.completedFuture(new ToolOutcome(
                        List.of(lookupError + hint), List.of(event), new RuntimeException(lookupError)));
            }
            return CompletableFuture.completedFuture(new ToolOutcome(
                    List.of(lookupError + hint), List.of(event), null));
        }

        Map<String, Object> params = toolCall.arguments() instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();

        return spec.tools().execute(toolCall.name(), params)
                .handle((result, ex) -> {
                    if (ex != null) {
                        if (ex instanceof CancellationException) throw (CancellationException) ex;
                        String errMsg = ex.getMessage() != null ? ex.getMessage() : "";
                        Map<String, String> event = new HashMap<>(Map.of(
                                "name", toolCall.name(), "status", "error", "detail",
                                errMsg.substring(0, Math.min(120, errMsg.length()))));
                        String payload = "Error: " + ex.getClass().getSimpleName() + ": " + errMsg;
                        ToolOutcome handled = classifyViolation(errMsg, payload, event, toolCall, workspaceViolationCounts);
                        if (handled != null) return handled;
                        if (spec.failOnToolError()) {
                            return new ToolOutcome(List.of(payload), List.of(event), ex);
                        }
                        return new ToolOutcome(List.of(payload), List.of(event), null);
                    }

                    if (result instanceof String str && str.startsWith("Error")) {
                        Map<String, String> event = new HashMap<>(Map.of(
                                "name", toolCall.name(), "status", "error", "detail",
                                str.replace("\n", " ").trim().substring(0, Math.min(120, str.length()))));
                        ToolOutcome handled = classifyViolation(str, str + hint, event, toolCall, workspaceViolationCounts);
                        if (handled != null) return handled;
                        if (spec.failOnToolError()) {
                            return new ToolOutcome(List.of(str + hint), List.of(event), new RuntimeException(str));
                        }
                        return new ToolOutcome(List.of(str + hint), List.of(event), null);
                    }

                    String detail = result == null ? "(empty)" : result.toString().replace("\n", " ").trim();
                    if (detail.isEmpty()) detail = "(empty)";
                    if (detail.length() > 120) detail = detail.substring(0, 120) + "...";
                    return new ToolOutcome(List.of(result), List.of(new HashMap<>(Map.of(
                            "name", toolCall.name(), "status", "ok", "detail", detail))), null);
                });
    }

    // ==================== injection draining ====================

    /** 对标 Python _try_drain_injections()——尝试从 pending 队列注入消息。 */
    @SuppressWarnings("unchecked")
    private CompletableFuture<DrainResult> tryDrainInjections(
            AgentRunSpec spec, List<Map<String, Object>> messages,
            Map<String, Object> assistantMessage, int injectionCycles,
            String phase, Integer iteration, boolean allowGoalContinue) {
        if (injectionCycles >= MAX_INJECTION_CYCLES) {
            return CompletableFuture.completedFuture(new DrainResult(false, injectionCycles));
        }
        return drainInjections(spec).thenCompose(initialInjections -> {
            List<Map<String, Object>> injections = new ArrayList<>(initialInjections);
            boolean realInjection = !injections.isEmpty();
            boolean goalContinued = false;
            if (!realInjection && allowGoalContinue && assistantMessage != null) {
                if (spec.goalActivePredicate() != null) {
                    Boolean active = spec.goalActivePredicate().test(null);
                    if (Boolean.TRUE.equals(active)) {
                        injections.add(RuntimeUtils.buildGoalContinueMessage(spec.goalContinueMessage()));
                        goalContinued = true;
                    }
                }
            }
            if (injections.isEmpty()) {
                return CompletableFuture.completedFuture(new DrainResult(false, injectionCycles));
            }
            int newCycles = injectionCycles + (realInjection ? 1 : 0);
            if (assistantMessage != null) {
                messages.add(assistantMessage);
                if (iteration != null) {
                    Map<String, Object> cp = new LinkedHashMap<>(Map.of(
                            "phase", "final_response", "iteration", iteration, "model", spec.model(),
                            "assistant_message", assistantMessage,
                            "completed_tool_results", List.of(), "pending_tool_calls", List.of()));
                    return emitCheckpoint(spec, cp).thenApply(v -> {
                        appendInjectedMessages(messages, injections);
                        return new DrainResult(true, newCycles);
                    });
                }
            }
            appendInjectedMessages(messages, injections);
            return CompletableFuture.completedFuture(new DrainResult(true, newCycles));
        });
    }

    /** 对标 Python _drain_injections()——从 injection callback 获取待注入消息。 */
    @SuppressWarnings("unchecked")
    private CompletableFuture<List<Map<String, Object>>> drainInjections(AgentRunSpec spec) {
        if (spec.injectionCallback() == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return spec.injectionCallback().apply(MAX_INJECTIONS_PER_TURN)
                .thenApply(items -> {
                    if (items == null || items.isEmpty()) return List.<Map<String, Object>>of();
                    List<Map<String, Object>> injected = new ArrayList<>();
                    for (Map<String, Object> item : items) {
                        if ("user".equals(item.get("role")) && item.containsKey("content")) {
                            injected.add(item);
                        } else {
                            String text = item.get("content") != null
                                    ? item.get("content").toString() : item.toString();
                            if (!text.trim().isEmpty()) {
                                injected.add(new HashMap<>(Map.of("role", "user", "content", text)));
                            }
                        }
                    }
                    if (injected.size() > MAX_INJECTIONS_PER_TURN) {
                        return injected.subList(0, MAX_INJECTIONS_PER_TURN);
                    }
                    return injected;
                })
                .exceptionally(ex -> List.<Map<String, Object>>of());
    }

    // ==================== violation classification ====================

    /** 对标 Python _classify_violation()——分类工具错误为 SSRF/工作区违规。 */
    private ToolOutcome classifyViolation(String rawText, String softPayload,
                                            Map<String, String> event, ToolCallRequest toolCall,
                                            Map<String, Integer> workspaceViolationCounts) {
        if (isSsrfViolation(rawText)) {
            event.put("detail", eventDetail("ssrf_violation: ", rawText));
            return new ToolOutcome(List.of(ssrfSoftPayload(rawText)), List.of(event), null);
        }
        if (isWorkspaceViolation(rawText)) {
            String escalation = RuntimeUtils.repeatedWorkspaceViolationError(
                    toolCall.name(), (Map<String, Object>) toolCall.arguments(), workspaceViolationCounts);
            if (escalation != null) {
                event.put("detail", eventDetail("workspace_violation_escalated: ", rawText));
                return new ToolOutcome(List.of(escalation), List.of(event), null);
            }
            event.put("detail", eventDetail("workspace_violation: ", rawText));
            return new ToolOutcome(List.of(softPayload), List.of(event), null);
        }
        return null;
    }

    private static boolean isSsrfViolation(String text) {
        if (text == null || text.isEmpty()) return false;
        String lowered = text.toLowerCase();
        return SSRF_MARKERS.stream().anyMatch(lowered::contains);
    }

    private static boolean isWorkspaceViolation(String text) {
        if (text == null || text.isEmpty()) return false;
        String lowered = text.toLowerCase();
        if (isSsrfViolation(lowered)) return true;
        return WORKSPACE_VIOLATION_MARKERS.stream().anyMatch(lowered::contains);
    }

    private static String ssrfSoftPayload(String rawText) {
        String text = rawText != null ? rawText.strip() : "Error: request blocked by SSRF guard";
        return text + "\n\n" + SSRF_BOUNDARY_NOTE;
    }

    private static String eventDetail(String prefix, String text) {
        String cleaned = text.replace("\n", " ").strip();
        String full = prefix + cleaned;
        return full.length() > 160 ? full.substring(0, 160) : full;
    }

    // ==================== helpers ====================

    private CompletableFuture<Void> endStreamIfWanted(AgentHook hook, AgentHookContext ctx, boolean resuming) {
        if (hook.wantsStreaming()) {
            return hook.onStreamEnd(ctx, resuming);
        }
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> emitCheckpoint(AgentRunSpec spec, Map<String, Object> payload) {
        if (spec.checkpointCallback() != null) {
            return spec.checkpointCallback().apply(payload);
        }
        return CompletableFuture.completedFuture(null);
    }

    private AgentRunResult buildResult(List<Map<String, Object>> messages, RunCoreState s) {
        return new AgentRunResult(s.finalContent, messages, s.toolsUsed, s.usage,
                s.stopReason, s.error, s.toolEvents, s.hadInjections);
    }

    private Object normalizeToolResult(AgentRunSpec spec, String toolCallId,
                                         String toolName, Object result) {
        result = RuntimeUtils.ensureNonemptyToolResult(toolName, result);
        if (TOOL_RESULT_OFFLOAD_EXEMPT_TOOLS.contains(toolName)) {
            return result;
        }
        try {
            result = RuntimeUtils.maybePersistToolResult(
                    spec.workspace(), spec.sessionKey(), toolCallId, result, spec.maxToolResultChars());
        } catch (Exception e) { /* fall through */ }
        if (result instanceof String str && str.length() > spec.maxToolResultChars()) {
            return RuntimeUtils.truncateText(str, spec.maxToolResultChars());
        }
        return result;
    }

    private static void appendFinalMessage(List<Map<String, Object>> messages, String content) {
        if (content == null || content.isEmpty()) return;
        if (!messages.isEmpty() && "assistant".equals(messages.get(messages.size() - 1).get("role"))
                && messages.get(messages.size() - 1).get("tool_calls") == null) {
            if (content.equals(messages.get(messages.size() - 1).get("content"))) return;
            Map<String, Object> merged = new LinkedHashMap<>(messages.get(messages.size() - 1));
            merged.put("content", content);
            messages.set(messages.size() - 1, merged);
            return;
        }
        messages.add(RuntimeUtils.buildAssistantMessage(content));
    }

    private static void appendModelErrorPlaceholder(List<Map<String, Object>> messages) {
        if (!messages.isEmpty() && "assistant".equals(messages.get(messages.size() - 1).get("role"))
                && messages.get(messages.size() - 1).get("tool_calls") == null) {
            return;
        }
        messages.add(RuntimeUtils.buildAssistantMessage(PERSISTED_MODEL_ERROR_PLACEHOLDER));
    }

    @SuppressWarnings("unchecked")
    private static void appendInjectedMessages(List<Map<String, Object>> messages,
                                                List<Map<String, Object>> injections) {
        for (Map<String, Object> injection : injections) {
            if (!messages.isEmpty() && "user".equals(injection.get("role"))
                    && "user".equals(messages.get(messages.size() - 1).get("role"))) {
                Map<String, Object> merged = new LinkedHashMap<>(messages.get(messages.size() - 1));
                merged.put("content", mergeMessageContent(merged.get("content"), injection.get("content")));
                messages.set(messages.size() - 1, merged);
                continue;
            }
            messages.add(injection);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object mergeMessageContent(Object left, Object right) {
        if (left instanceof String ls && right instanceof String rs) {
            return ls.isEmpty() ? rs : ls + "\n\n" + rs;
        }
        List<Map<String, Object>> result = new ArrayList<>(toBlocks(left));
        result.addAll(toBlocks(right));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toBlocks(Object value) {
        if (value instanceof List) return new ArrayList<>((List<Map<String, Object>>) value);
        if (value == null) return new ArrayList<>();
        return new ArrayList<>(List.of(Map.of("type", "text", "text", String.valueOf(value))));
    }

    // ==================== usage helpers ====================

    private Map<String, Integer> usageOrEstimate(AgentRunSpec spec,
                                                   List<Map<String, Object>> messages, LLMResponse response) {
        Map<String, Integer> usage = usageDict(response.usage());
        int total = usageTotal(usage);
        if (total > 0) {
            usage.put("total_tokens", total);
            usage.putIfAbsent("provider_tokens", total);
            return usage;
        }
        if ("error".equals(response.finishReason())) return new HashMap<>();
        return estimateResponseUsage(spec, messages, response);
    }

    private Map<String, Integer> estimateResponseUsage(AgentRunSpec spec,
                                                         List<Map<String, Object>> messages, LLMResponse response) {
        List<Map<String, Object>> tools;
        try { tools = spec.tools().getDefinitions(); } catch (Exception e) { tools = null; }
        int promptTokens = RuntimeUtils.estimatePromptTokensChain(provider, spec.model(), messages, tools);
        Map<String, Object> assistantMessage = RuntimeUtils.buildAssistantMessage(
                response.content() != null ? response.content() : "",
                response.toolCalls(), response.reasoningContent(), response.thinkingBlocks());
        int completionTokens = RuntimeUtils.estimateMessageTokens(assistantMessage);
        int total = Math.max(0, promptTokens) + Math.max(0, completionTokens);
        if (total <= 0) return new HashMap<>();
        return new HashMap<>(Map.of(
                "prompt_tokens", Math.max(0, promptTokens),
                "completion_tokens", Math.max(0, completionTokens),
                "total_tokens", total, "estimated_tokens", total));
    }

    private static Map<String, Integer> usageDict(Map<String, ?> usage) {
        if (usage == null) return new HashMap<>();
        Map<String, Integer> result = new HashMap<>();
        for (Map.Entry<String, ?> e : usage.entrySet()) {
            try { result.put(e.getKey(), Integer.valueOf(String.valueOf(e.getValue()))); }
            catch (NumberFormatException ignored) {}
        }
        return result;
    }

    private static int usageTotal(Map<String, Integer> usage) {
        int total = usage.getOrDefault("total_tokens", 0);
        if (total == 0) {
            total = usage.getOrDefault("prompt_tokens", 0) + usage.getOrDefault("completion_tokens", 0);
        }
        return Math.max(0, total);
    }

    private static void accumulateUsage(Map<String, Integer> target, Map<String, Integer> addition) {
        for (Map.Entry<String, Integer> e : addition.entrySet()) {
            target.put(e.getKey(), target.getOrDefault(e.getKey(), 0) + e.getValue());
        }
    }

    private static Map<String, Integer> mergeUsage(Map<String, Integer> left, Map<String, Integer> right) {
        Map<String, Integer> merged = new HashMap<>(left);
        for (Map.Entry<String, Integer> e : right.entrySet()) {
            merged.put(e.getKey(), merged.getOrDefault(e.getKey(), 0) + e.getValue());
        }
        return merged;
    }
}
