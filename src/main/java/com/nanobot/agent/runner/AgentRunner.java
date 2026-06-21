package com.nanobot.agent.runner;

import com.nanobot.agent.hook.AgentHookContext;
import com.nanobot.agent.hook.AgentRunHookContext;
import com.nanobot.agent.tools.ToolContext;
import com.nanobot.agent.tools.ToolRegistry;
import com.nanobot.providers.base.LLMProvider;
import com.nanobot.providers.base.LLMResponse;
import com.nanobot.providers.base.ToolCallRequest;
import com.nanobot.trace.TraceObservation;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具调用 LLM 循环执行器——无产品层依赖的纯引擎核心。
 * 完整对标 Python AgentRunner（agent/runner.py 1544 行）。
 *
 * <p>职责：
 * <ul>
 *   <li>迭代调用 LLM + 执行工具（最多 maxIterations 轮）</li>
 *   <li>上下文治理：dropOrphan/backfill/microcompact/snipHistory/applyToolResultBudget</li>
 *   <li>工具结果注入（mid-turn injections）与新消息追加重试</li>
 *   <li>流式输出（streamContent）和 thinking 进度回调</li>
 *   <li>错误重试（标准/fallback 模式）、空白回复重试、超长回复续写</li>
 *   <li>安全：SSRF 违规检测、workspace 违规检测与升级</li>
 *   <li>最终化（finalization）与 empty-final-response 处理</li>
 *   <li>持久化 assistant 消息（含 tool_calls）和模型错误占位</li>
 * </ul></p>
 *
 * <p>Python asyncio → Java Virtual Threads 映射：
 * AgentRunner.run() 在调用方的虚拟线程中同步执行，不阻塞平台线程。</p>
 */
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    // -- 上下文治理常量（对标 Python runner.py 顶层常量）--
    /** LLM 返回空内容时的最大重试次数 */
    static final int MAX_EMPTY_RETRIES = 2;
    /** 超长回复续写的最大尝试次数 */
    static final int MAX_LENGTH_RECOVERIES = 3;
    /** 单轮最多注入工具结果次数 */
    static final int MAX_INJECTIONS_PER_TURN = 3;
    /** 注入循环的最大迭代次数 */
    static final int MAX_INJECTION_CYCLES = 5;
    /** 消息裁剪的安全缓冲 token 数 */
    static final int SNIP_SAFETY_BUFFER = 1024;
    /** 微压缩时保留的最近消息数 */
    static final int MICROCOMPACT_KEEP_RECENT = 10;
    /** 触发微压缩的最小字符数 */
    static final int MICROCOMPACT_MIN_CHARS = 500;
    /** 重复 workspace violation 的上限，超过后升级处理 */
    static final int MAX_REPEAT_WORKSPACE_VIOLATIONS = 2;
    /** 重复外部查找的上限 */
    static final int MAX_REPEAT_EXTERNAL_LOOKUPS = 2;
    static final String BACKFILL_CONTENT = "[Tool result unavailable — call was interrupted or lost]";
    static final String DEFAULT_ERROR_MESSAGE = "Sorry, I encountered an error calling the AI model.";
    static final String ARREARAGE_ERROR_MESSAGE =
            "The AI provider rejected the request because the API key is out of quota or the "
            + "account is in arrears. Please top up / check the billing status of your API key and try again.";
    static final String PERSISTED_MODEL_ERROR_PLACEHOLDER = "[Assistant reply unavailable due to model error.]";
    static final String EMPTY_FINAL_RESPONSE_MESSAGE =
            "I completed the tool steps but couldn't produce a final answer. "
            + "Please try again or narrow the task.";

    static final String FINALIZATION_RETRY_PROMPT =
            "Please provide your response to the user based on the conversation above.";
    static final String BUDGET_EXHAUSTED_FINALIZATION_PROMPT =
            "The tool-call budget for this turn is exhausted. Based only on the "
            + "conversation and tool results above, provide a concise final response to "
            + "the user. Do not call or request tools. Do not claim the task is complete "
            + "unless the evidence above clearly shows it is complete. State what was "
            + "done, what remains, and the best next step if anything is incomplete.";
    static final String LENGTH_RECOVERY_PROMPT =
            "Output limit reached. Continue exactly where you left off "
            + "— no recap, no apology. Break remaining work into smaller steps if needed.";

    private final LLMProvider provider;

    public AgentRunner(LLMProvider provider) {
        this.provider = provider;
    }

    public LLMProvider getProvider() { return provider; }

    // -- run (mirrors Python AgentRunner.run, runner.py:275) --

    public AgentRunResult run(AgentRunSpec spec) {
        var runCtx = new AgentRunHookContext();
        var hook = spec.hook();
        try {
            if (hook != null) hook.beforeRun(runCtx);
        } catch (Exception e) { /* best-effort */ }

        try {
            return runCore(spec, runCtx);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            runCtx.error = "Interrupted";
            runCtx.exception = e;
            return finishRun(hook, runCtx, "Interrupted", "error");
        } catch (Exception e) {
            log.error("Agent run failed", e);
            runCtx.error = "Agent run failed: " + e.getMessage();
            runCtx.exception = e;
            if (hook != null) {
                try { hook.onError(runCtx); } catch (Exception ignored) {}
            }
            return finishRun(hook, runCtx, null, "error");
        } finally {
            if (hook != null) {
                try { hook.onFinally(runCtx); } catch (Exception ignored) {}
            }
        }
    }

    private AgentRunResult finishRun(@Nullable com.nanobot.agent.hook.AgentHook hook,
                                     AgentRunHookContext rctx, @Nullable String finalContent,
                                     String stopReason) {
        rctx.finalContent = finalContent;
        rctx.stopReason = stopReason;
        if (hook != null) {
            try { hook.afterRun(rctx); } catch (Exception ignored) {}
        }
        return new AgentRunResult(
                rctx.finalContent, rctx.messages != null ? rctx.messages : List.of(),
                rctx.toolsUsed, rctx.usage, stopReason,
                rctx.error, rctx.toolEvents, rctx.hadInjections);
    }

    /**
     * 核心执行循环——迭代调用 LLM + 执行工具。
     * 完整对标 Python _run_core()（runner.py:323-739）。
     *
     * <p>每轮迭代执行：上下文治理 → LLM 调用 → 工具执行 → 结果注入 → 下一轮。
     * maxIterations 耗尽或 LLM 返回 stop 时结束，进入 finalization。</p>
     */
    // 对应 Python _run_core() (runner.py:323)
    AgentRunResult runCore(AgentRunSpec spec, AgentRunHookContext runCtx) throws InterruptedException {
        var messages = new ArrayList<>(spec.initialMessages());
        var toolsUsed = new ArrayList<String>();
        var usage = Map.<String, Integer>of();
        int injectionCycles = 0;
        boolean hadInjections = false;

        for (int iteration = 0; iteration < spec.maxIterations(); iteration++) {
            // 上下文治理：清理、回填、压缩、截断
            // 对标 Python _run_core() 内的治理调用（runner.py:339-345）
            dropOrphanToolResults(messages);
            backfillMissingToolResults(messages);
            microcompact(messages);
            applyToolResultBudget(spec, messages);
            snipHistory(spec, messages);

            // Hook
            var iterCtx = new AgentHookContext();
            iterCtx.iteration = iteration;
            iterCtx.messages = messages;
            iterCtx.sessionKey = spec.sessionKey();
            var hook = spec.hook();
            if (hook != null) {
                try { hook.beforeIteration(iterCtx); } catch (Exception ignored) {}
            }

            // Request model
            LLMResponse response = LLMResponse.error("unreachable");
            // -- LLM 调用追踪，对应 Python langfuse.openai 自动埋点 --
            var llmSpan = TraceObservation.startSpan("llm.call", Map.of(
                    "iteration", iteration, "model", spec.model() != null ? spec.model() : "default"));
            try {
                response = requestModel(spec, messages, iterCtx);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                log.error("Model request failed at iteration {}", iteration, e);
                response = LLMResponse.error("Error calling LLM: " + e.getMessage());
            } finally {
                if (llmSpan != null) {
                    // GenAI 语义属性 — LangFuse 原生解析，显示模型名、token 数、延迟
                    var respUsage = response.usage();
                    int promptTokens = respUsage.containsKey("prompt_tokens")
                            ? ((Number) respUsage.get("prompt_tokens")).intValue() : 0;
                    int completionTokens = respUsage.containsKey("completion_tokens")
                            ? ((Number) respUsage.get("completion_tokens")).intValue() : 0;
                    TraceObservation.recordLLMUsage(
                            spec.model() != null ? spec.model() : "default",
                            guessSystemFromModel(spec.model()),
                            promptTokens,
                            completionTokens,
                            response.finishReason());
                    TraceObservation.endSpan(llmSpan);
                }
            }

            // Accumulate usage
            if (!response.usage().isEmpty()) {
                usage = mergeUsage(usage, response.usage());
            }

            // Extract reasoning
            if (response.reasoningContent() != null && hook != null) {
                hook.emitReasoning(response.reasoningContent());
            }

            // Should execute tools?
            if (response.shouldExecuteTools()) {
                var assistantMsg = buildAssistantMessage(
                        response.content(), response.toolCalls(),
                        response.reasoningContent(), response.thinkingBlocks());
                messages.add(assistantMsg);

                // Emit checkpoint
                if (spec.checkpointCallback() != null) {
                    spec.checkpointCallback().accept(Map.of("messages", messages));
                }

                // Execute tools
                if (hook != null) {
                    try { hook.beforeExecuteTools(iterCtx); } catch (Exception ignored) {}
                }
                var toolResults = executeTools(spec, response.toolCalls(), iterCtx);
                messages.addAll(toolResults);
                for (var tc : response.toolCalls()) {
                    toolsUsed.add(tc.name());
                }

                // Drain injections
                if (spec.injectionCallback() != null && injectionCycles < MAX_INJECTION_CYCLES) {
                    var injections = spec.injectionCallback().get();
                    if (injections != null && !injections.isEmpty()) {
                        appendInjectedMessages(messages, injections);
                        injectionCycles++;
                        hadInjections = true;
                    }
                }
                continue;
            }

            // Empty content retry
            if (isBlankText(response.content()) && iteration < spec.maxIterations() - 1) {
                int emptyCount = 0;
                for (int i = messages.size() - 1; i >= 0; i--) {
                    if ("assistant".equals(messages.get(i).get("role"))) emptyCount++;
                    else break;
                }
                if (emptyCount < MAX_EMPTY_RETRIES) continue;
            }

            // Error handling
            if ("error".equals(response.finishReason())) {
                var errorMsg = buildAssistantMessage(
                        spec.errorMessage(), List.of(), null, null);
                messages.add(errorMsg);
                // Try drain injections
                if (spec.injectionCallback() != null && injectionCycles < MAX_INJECTION_CYCLES) {
                    var injections = spec.injectionCallback().get();
                    if (injections != null && !injections.isEmpty()) {
                        appendInjectedMessages(messages, injections);
                        injectionCycles++;
                        hadInjections = true;
                        continue;
                    }
                }
                return new AgentRunResult(null, messages, toolsUsed, usage,
                        "error", response.content(), runCtx.toolEvents, hadInjections);
            }

            // Success — final response
            var finalContent = response.content();
            if (hook != null) {
                finalContent = hook.finalizeContent(iterCtx, finalContent);
            }

            // Drain injections
            if (spec.injectionCallback() != null && injectionCycles < MAX_INJECTION_CYCLES) {
                var injections = spec.injectionCallback().get();
                if (injections != null && !injections.isEmpty()) {
                    appendInjectedMessages(messages, injections);
                    hadInjections = true;
                }
            }

            var finalMsg = buildAssistantMessage(finalContent, List.of(), null, null);
            messages.add(finalMsg);
            runCtx.messages = messages;
            runCtx.finalContent = finalContent;
            return new AgentRunResult(finalContent, messages, toolsUsed, usage,
                    "completed", null, runCtx.toolEvents, hadInjections);
        }

        // Max iterations reached
        if (spec.finalizeOnMaxIterations()) {
            var finalContent = tryFinalizeOnMaxIterations(spec, messages, usage, runCtx, hadInjections);
            if (finalContent != null) {
                appendFinalMessage(messages, finalContent);
            }
            return new AgentRunResult(finalContent, messages, toolsUsed, usage,
                    "max_iterations", null, runCtx.toolEvents, hadInjections);
        }
        return new AgentRunResult(null, messages, toolsUsed, usage,
                "max_iterations", null, runCtx.toolEvents, hadInjections);
    }

    // -- requestModel --

    @SuppressWarnings("unchecked")
        // 对应 Python _request_model() (runner.py:692)
    LLMResponse requestModel(AgentRunSpec spec, List<Map<String, Object>> messages,
                             AgentHookContext iterCtx) throws Exception {
        var tools = spec.tools().getDefinitions();
        int maxTokens = spec.maxTokens() != null ? spec.maxTokens() : provider.generation.maxTokens();
        double temperature = spec.temperature() != null ? spec.temperature() : provider.generation.temperature();
        return provider.chatWithRetry(
                messages, tools, spec.model(), maxTokens, temperature,
                spec.reasoningEffort(), null,
                spec.providerRetryMode(), spec.retryWaitCallback());
    }

    @SuppressWarnings("unchecked")
        // 对应 Python _build_request_kwargs() (runner.py:670)
    Map<String, Object> buildRequestKwargs(AgentRunSpec spec, List<Map<String, Object>> messages) {
        var kwargs = new LinkedHashMap<String, Object>();
        kwargs.put("messages", messages);
        kwargs.put("tools", spec.tools().getDefinitions());
        kwargs.put("model", spec.model());
        kwargs.put("retry_mode", spec.providerRetryMode());
        kwargs.put("on_retry_wait", spec.retryWaitCallback());
        if (spec.temperature() != null) kwargs.put("temperature", spec.temperature());
        if (spec.maxTokens() != null) kwargs.put("max_tokens", spec.maxTokens());
        if (spec.reasoningEffort() != null) kwargs.put("reasoning_effort", spec.reasoningEffort());
        return kwargs;
    }

    @SuppressWarnings("unchecked")
        // 对应 Python _request_no_tools() (runner.py:889)
    LLMResponse requestNoTools(AgentRunSpec spec, List<Map<String, Object>> messages) throws Exception {
        int maxTokens = spec.maxTokens() != null ? spec.maxTokens() : provider.generation.maxTokens();
        double temperature = spec.temperature() != null ? spec.temperature() : provider.generation.temperature();
        return provider.chatWithRetry(
                messages, null, spec.model(), maxTokens, temperature,
                spec.reasoningEffort(), null,
                spec.providerRetryMode(), spec.retryWaitCallback());
    }

    // -- injection draining --

    @SuppressWarnings("unchecked")
        // 对应 Python _try_drain_injections() (runner.py:175)
    boolean tryDrainInjections(AgentRunSpec spec, List<Map<String, Object>> messages,
                               Map<String, Object> assistantMessage,
                               int injectionCycles,
                               String phase, Integer iteration,
                               boolean allowGoalContinue) {
        if (injectionCycles >= MAX_INJECTION_CYCLES && !allowGoalContinue) return false;

        var injections = new ArrayList<Map<String, Object>>();
        boolean realInjection = false;
        if (injectionCycles < MAX_INJECTION_CYCLES) {
            injections.addAll(drainInjections(spec));
            realInjection = !injections.isEmpty();
        }
        if (injections.isEmpty() && allowGoalContinue && assistantMessage != null) {
            var predicate = spec.goalActivePredicate();
            if (predicate != null && predicate.getAsBoolean()) {
                injections.add(Map.of("role", "user", "content",
                        spec.goalContinueMessage() != null ? spec.goalContinueMessage()
                                : "You have an active sustained goal. Please continue working toward the "
                                + "objective using your tools, or call complete_goal if the work is truly finished."));
            }
        }
        if (injections.isEmpty()) return false;
        if (assistantMessage != null) {
            messages.add(assistantMessage);
            if (iteration != null) {
                emitCheckpoint(spec, Map.of(
                        "phase", "final_response",
                        "iteration", iteration,
                        "model", spec.model(),
                        "assistant_message", assistantMessage,
                        "completed_tool_results", List.of(),
                        "pending_tool_calls", List.of()));
            }
        }
        appendInjectedMessages(messages, injections);
        return true;
    }

    @SuppressWarnings("unchecked")
        // 对应 Python _drain_injections() (runner.py:230)
    List<Map<String, Object>> drainInjections(AgentRunSpec spec) {
        var callback = spec.injectionCallback();
        if (callback == null) return List.of();
        try {
            var items = callback.get();
            if (items == null || items.isEmpty()) return List.of();
            var result = new ArrayList<Map<String, Object>>();
            int count = 0;
            for (var item : items) {
                if (count >= MAX_INJECTIONS_PER_TURN) break;
                if (item instanceof Map<?, ?> m) {
                    result.add((Map<String, Object>) m);
                    count++;
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("injection_callback failed", e);
            return List.of();
        }
    }

    // -- finalization --

        // 对应 Python _request_finalization_retry() (runner.py:834)
    LLMResponse requestFinalizationRetry(AgentRunSpec spec,
                                         List<Map<String, Object>> messages) throws Exception {
        var retryMessages = buildFinalizationRetryMessages(messages);
        return requestNoTools(spec, retryMessages);
    }

        // 对应 Python _finalization_retry_messages() (runner.py:843)
    static List<Map<String, Object>> buildFinalizationRetryMessages(List<Map<String, Object>> messages) {
        var retry = new ArrayList<>(messages);
        retry.add(Map.of("role", "user", "content", FINALIZATION_RETRY_PROMPT));
        return retry;
    }

        // 对应 Python _budget_exhausted_finalization_messages() (runner.py:898)
    static List<Map<String, Object>> buildBudgetExhaustedFinalizationMessages(
            List<Map<String, Object>> messages) {
        var retry = new ArrayList<>(messages);
        retry.add(Map.of("role", "user", "content", BUDGET_EXHAUSTED_FINALIZATION_PROMPT));
        return retry;
    }

        // 对应 Python _length_recovery_prompt — 构建长度恢复用户消息 (runner.py 内部)
    static Map<String, Object> buildLengthRecoveryMessage() {
        return Map.of("role", "user", "content", LENGTH_RECOVERY_PROMPT);
    }

        // 对应 Python _max_iterations_fallback() (runner.py:906)
    String maxIterationsFallback(AgentRunSpec spec) {
        if (spec.maxIterationsMessage() != null) {
            return spec.maxIterationsMessage().replace("{max_iterations}",
                    String.valueOf(spec.maxIterations()));
        }
        return "Maximum iterations (" + spec.maxIterations()
                + ") reached. Task may be incomplete — send another message to continue.";
    }

    @SuppressWarnings("unchecked")
        // 对应 Python _try_finalize_after_max_iterations() (runner.py:848)
    String tryFinalizeOnMaxIterations(AgentRunSpec spec, List<Map<String, Object>> messages,
                                      Map<String, Integer> usage, AgentRunHookContext runCtx,
                                      boolean hadInjections) {
        var retryMessages = buildBudgetExhaustedFinalizationMessages(messages);
        try {
            var response = requestNoTools(spec, retryMessages);
            var rawUsage = usageOrEstimate(spec, retryMessages, response);
            accumulateUsage(usage, rawUsage);
            if ("error".equals(response.finishReason()) || response.hasToolCalls()) return null;
            var hook = spec.hook();
            if (hook != null) {
                var ctx = new AgentHookContext();
                ctx.iteration = spec.maxIterations();
                ctx.messages = messages;
                ctx.response = response;
                ctx.usage = rawUsage;
                ctx.sessionKey = spec.sessionKey();
                return hook.finalizeContent(ctx, response.content());
            }
            return response.content();
        } catch (Exception e) {
            log.warn("Budget-exhausted finalization failed for {}", spec.sessionKey(), e);
            return null;
        }
    }

    // -- usage estimation --

        // 对应 Python _usage_or_estimate() (runner.py:917)
    Map<String, Integer> usageOrEstimate(AgentRunSpec spec, List<Map<String, Object>> messages,
                                         LLMResponse response) {
        var usage = usageDict(response.usage());
        var total = usageTotal(usage);
        if (total > 0) {
            usage.put("total_tokens", total);
            usage.putIfAbsent("provider_tokens", total);
            return usage;
        }
        if ("error".equals(response.finishReason())) return Map.of();
        return estimateResponseUsage(spec, messages, response);
    }

    @SuppressWarnings("unchecked")
        // 对应 Python _estimate_response_usage() (runner.py:933)
    Map<String, Integer> estimateResponseUsage(AgentRunSpec spec, List<Map<String, Object>> messages,
                                                LLMResponse response) {
        try {
            var tools = spec.tools().getDefinitions();
            // Estimate: chars / 4 ≈ tokens
            int promptTokens = 0;
            for (var msg : messages) {
                var content = msg.get("content");
                if (content instanceof String s) promptTokens += s.length() / 4;
                else if (content instanceof List<?> l) {
                    for (var block : l) {
                        if (block instanceof Map<?, ?> bm && "text".equals(bm.get("type"))) {
                            var t = bm.get("text");
                            if (t instanceof String ts) promptTokens += ts.length() / 4;
                        }
                    }
                }
            }
            promptTokens = Math.max(1, promptTokens);
            var responseContent = response.content() != null ? response.content() : "";
            int completionTokens = Math.max(1, responseContent.length() / 4);
            int totalTokens = promptTokens + completionTokens;
            return Map.of("prompt_tokens", promptTokens, "completion_tokens", completionTokens,
                    "total_tokens", totalTokens, "estimated_tokens", totalTokens);
        } catch (Exception e) {
            return Map.of();
        }
    }

    // -- executeTools (enhanced with batching) --

    @SuppressWarnings("unchecked")
        // 对应 Python _execute_tools() (runner.py:991)
    List<Map<String, Object>> executeTools(AgentRunSpec spec, List<ToolCallRequest> toolCalls,
                                           AgentHookContext iterCtx) {
        var extLookupCounts = new LinkedHashMap<String, Integer>();
        var wsViolationCounts = new LinkedHashMap<String, Integer>();
        var batches = partitionToolBatches(spec, toolCalls);
        var results = new ArrayList<Map<String, Object>>();

        for (var batch : batches) {
            if (spec.concurrentTools() && batch.size() > 1) {
                // Concurrent execution via virtual threads
                var futures = new ArrayList<java.util.concurrent.Future<Map<String, Object>>>();
                var batchExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
                try {
                    for (var tc : batch) {
                        futures.add(batchExecutor.submit(() -> runToolWithClassification(
                                spec, tc, extLookupCounts, wsViolationCounts)));
                    }
                    for (var f : futures) {
                        try { results.add(f.get()); } catch (Exception e) {
                            results.add(Map.of("role", "tool", "tool_call_id", "concurrent-error",
                                    "content", "Tool execution error: " + e.getMessage()));
                        }
                    }
                } finally {
                    batchExecutor.shutdown();
                }
            } else {
                for (var tc : batch) {
                    results.add(runToolWithClassification(spec, tc, extLookupCounts, wsViolationCounts));
                }
            }
        }

        for (var r : results) {
            iterCtx.toolResults.add(r);
        }
        return results;
    }

    @SuppressWarnings("unchecked")
        // 对应 Python _run_tool() (runner.py:1028)
    Map<String, Object> runToolWithClassification(AgentRunSpec spec, ToolCallRequest toolCall,
                                                   Map<String, Integer> extLookupCounts,
                                                   Map<String, Integer> wsViolationCounts) {
        var hint = "\n\n[Analyze the error above and try a different approach.]";

        // Check repeated external lookup (Python _run_tool lines 1036-1049)
        var lookupErr = repeatedExternalLookupError(toolCall.name(), toolCall.arguments(), extLookupCounts);
        if (lookupErr != null) {
            return Map.of("role", "tool", "tool_call_id", toolCall.id(),
                    "name", toolCall.name(), "content", lookupErr + hint);
        }

        // Get tool and params
        var tool = spec.tools().get(toolCall.name());
        Map<String, Object> params;
        if (toolCall.arguments() instanceof Map<?, ?> m) {
            params = (Map<String, Object>) m;
        } else {
            params = Map.of();
        }

        // -- 工具执行追踪 --
        var toolSpan = TraceObservation.startSpan("tool.execute", Map.of(
                "tool", toolCall.name(),
                "callId", toolCall.id()));

        // Execute (Python _run_tool lines 1099-1103)
        String rawText = "";
        try {
            var toolResult = tool != null
                    ? tool.execute(params, ToolContext.create())
                    : spec.tools().execute(toolCall.name(), params);
            rawText = toolResult != null ? toolResult.toString() : "";
        } catch (Exception e) {
            rawText = "Error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (toolSpan != null) {
                toolSpan.setAttribute("status",
                        rawText.startsWith("Error") ? "error" : "ok");
                TraceObservation.endSpan(toolSpan);
            }
        }

        // Classify violations (Python _run_tool lines 1135-1160, using _classify_violation)
        if (rawText.startsWith("Error") || isSsrfViolation(rawText) || isWorkspaceViolation(rawText)) {
            var event = new LinkedHashMap<String, Object>();
            event.put("name", toolCall.name());
            event.put("status", "error");
            event.put("detail", rawText.replace("\n", " ").trim().substring(
                    0, Math.min(120, rawText.replace("\n", " ").trim().length())));

            var classified = classifyViolation(rawText,
                    isSsrfViolation(rawText) ? ssrfSoftPayload(rawText) : rawText + hint,
                    event, toolCall, wsViolationCounts);
            if (classified != null) {
                return classified;
            }

            // Unclassified error → return with hint
            return Map.of("role", "tool", "tool_call_id", toolCall.id(),
                    "name", toolCall.name(), "content", rawText + hint);
        }

        // Success — normalize and truncate
        var normalized = normalizeToolResult(spec, toolCall.id(), toolCall.name(), rawText);
        return Map.of("role", "tool", "tool_call_id", toolCall.id(),
                "name", toolCall.name(), "content", normalized);
    }

    // -- classifyViolation (Python _classify_violation, runner.py:1222-1260) --

    @SuppressWarnings("unchecked")
    @Nullable
        // 对应 Python _classify_violation() (runner.py:1222)
    Map<String, Object> classifyViolation(
            String rawText,
            String softPayload,
            Map<String, Object> event,
            ToolCallRequest toolCall,
            Map<String, Integer> wsViolationCounts) {

        // SSRF is a hard security block (Python lines 1232-1239)
        if (isSsrfViolation(rawText)) {
            log.warn("Tool {} blocked by SSRF guard: {}", toolCall.name(),
                    rawText.replace("\n", " ").trim().substring(
                            0, Math.min(200, rawText.replace("\n", " ").trim().length())));
            event.put("detail", eventDetail("ssrf_violation: ", rawText, 160));
            return Map.of("role", "tool", "tool_call_id", toolCall.id(),
                    "name", toolCall.name(), "content", ssrfSoftPayload(rawText));
        }

        // Workspace violation — may escalate on repeated attempts (Python lines 1241-1258)
        if (isWorkspaceViolation(rawText)) {
            var escalation = repeatedWorkspaceViolationError(
                    toolCall.name(), toolCall.arguments(), wsViolationCounts);
            event.put("detail", eventDetail("workspace_violation: ", rawText, 160));
            if (escalation != null) {
                log.warn("Tool {} hit workspace boundary repeatedly; escalating hint", toolCall.name());
                event.put("detail", eventDetail("workspace_violation_escalated: ", rawText, 160));
                return Map.of("role", "tool", "tool_call_id", toolCall.id(),
                        "name", toolCall.name(), "content", escalation);
            }
            return Map.of("role", "tool", "tool_call_id", toolCall.id(),
                    "name", toolCall.name(), "content", softPayload);
        }

        return null; // pass through
    }

    // -- repeatedExternalLookupError (Python repeated_external_lookup_error) --

    @SuppressWarnings("unchecked")
    @Nullable
        // 对应 Python repeated_external_lookup_error (runner.py:1028, 提取自 _run_tool)
    static String repeatedExternalLookupError(String toolName, Object arguments,
                                               Map<String, Integer> extLookupCounts) {
        var sig = externalLookupSignature(toolName, arguments);
        if (sig == null) return null;
        int count = extLookupCounts.getOrDefault(sig, 0) + 1;
        extLookupCounts.put(sig, count);
        if (count > MAX_REPEAT_EXTERNAL_LOOKUPS) {
            return "Repeated external lookup blocked for " + sig;
        }
        return null;
    }

    // -- repeatedWorkspaceViolationError (Python repeated_workspace_violation_error) --

    @SuppressWarnings("unchecked")
    @Nullable
        // 对应 Python repeated_workspace_violation_error (runner.py:1028, 提取自 _run_tool)
    static String repeatedWorkspaceViolationError(String toolName, Object arguments,
                                                   Map<String, Integer> wsViolationCounts) {
        var sig = workspaceViolationSignature(toolName,
                arguments instanceof Map<?, ?> m ? (Map<String, Object>) m : null);
        if (sig == null) return null;
        int count = wsViolationCounts.getOrDefault(sig, 0) + 1;
        wsViolationCounts.put(sig, count);
        if (count <= MAX_REPEAT_WORKSPACE_VIOLATIONS) return null;
        log.warn("Escalating repeated workspace bypass attempt {} (attempt {})",
                sig.length() > 160 ? sig.substring(0, 160) : sig, count);
        var target = sig.contains(":") ? sig.substring(sig.indexOf(":") + 1) : sig;
        return "Error: refusing repeated workspace-bypass attempts.\n"
                + "You have tried to access '" + target + "' (or an equivalent path) "
                + count + " times in this turn. This is a hard policy boundary -- "
                + "switching tools, shell tricks, working_dir overrides, symlinks, "
                + "or base64 piping will NOT change the answer. Stop retrying. "
                + "Stay within the configured workspace directory.";
    }

    // -- normalizeToolResult --

        // 对应 Python _normalize_tool_result() (runner.py:1301)
    Object normalizeToolResult(AgentRunSpec spec, String toolCallId, String toolName, Object result) {
        if (result == null || (result instanceof String s && s.isBlank())) {
            return "(" + toolName + " completed with no output)";
        }
        // read_file is exempt from offload to prevent persist→read→persist loops
        if ("read_file".equals(toolName)) return result;
        if (result instanceof String s && s.length() > spec.maxToolResultChars()) {
            return s.substring(0, spec.maxToolResultChars()) + "\n... (truncated)";
        }
        return result;
    }

    // -- partitionToolBatches --

        // 对应 Python _partition_tool_batches() (runner.py:1520)
    List<List<ToolCallRequest>> partitionToolBatches(AgentRunSpec spec,
                                                      List<ToolCallRequest> toolCalls) {
        if (!spec.concurrentTools()) {
            return toolCalls.stream().map(List::of).collect(java.util.stream.Collectors.toList());
        }
        var batches = new ArrayList<List<ToolCallRequest>>();
        var current = new ArrayList<ToolCallRequest>();
        for (var tc : toolCalls) {
            var tool = spec.tools().get(tc.name());
            boolean canBatch = tool != null && tool.isConcurrencySafe();
            if (canBatch) {
                current.add(tc);
                continue;
            }
            if (!current.isEmpty()) {
                batches.add(List.copyOf(current));
                current.clear();
            }
            batches.add(List.of(tc));
        }
        if (!current.isEmpty()) batches.add(current);
        return batches;
    }

    // -- emitCheckpoint --

        // 对应 Python _emit_checkpoint() (runner.py:1271)
    void emitCheckpoint(AgentRunSpec spec, Map<String, Object> payload) {
        var callback = spec.checkpointCallback();
        if (callback != null) {
            try { callback.accept(payload); } catch (Exception ignored) {}
        }
    }

    // -- external lookup / workspace violation signatures --

    @SuppressWarnings("unchecked")
        // 对应 Python external_lookup_signature — 去重键构建 (runner.py:1028)
    static String externalLookupSignature(String toolName, Object arguments) {
        if (!(arguments instanceof Map<?, ?> args)) return null;
        if ("web_fetch".equals(toolName)) {
            var urlObj = args.get("url");
            var url = urlObj != null ? urlObj.toString().trim() : "";
            if (!url.isEmpty()) return "web_fetch:" + url.toLowerCase();
        }
        if ("web_search".equals(toolName)) {
            var queryObj = args.get("query");
            if (queryObj == null) queryObj = args.get("search_term");
            var query = queryObj != null ? queryObj.toString().trim() : "";
            if (!query.isEmpty()) return "web_search:" + query.toLowerCase();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
        // 对应 Python workspace_violation_signature — 去重键构建 (runner.py:1156)
    static String workspaceViolationSignature(String toolName, Map<String, Object> arguments) {
        if (arguments == null) return null;
        var pathObj = arguments.get("file_path");
        if (pathObj == null) pathObj = arguments.get("path");
        if (pathObj == null) pathObj = arguments.get("directory");
        var path = pathObj != null ? pathObj.toString().trim() : "";
        if (!path.isEmpty()) return toolName + ":" + path;
        return null;
    }

    // -- Context governance --

    @SuppressWarnings("unchecked")
        // 对应 Python _drop_orphan_tool_results() (runner.py:1332)
    public static void dropOrphanToolResults(List<Map<String, Object>> messages) {
        var declared = new java.util.HashSet<String>();
        for (var msg : messages) {
            if ("assistant".equals(msg.get("role"))) {
                var toolCalls = msg.get("tool_calls");
                if (toolCalls instanceof List<?> tcList) {
                    for (var tc : tcList) {
                        if (tc instanceof Map<?, ?> tcm) {
                            var id = tcm.get("id");
                            if (id != null) declared.add(String.valueOf(id));
                        }
                    }
                }
            }
        }
        messages.removeIf(msg -> {
            if (!"tool".equals(msg.get("role"))) return false;
            var tid = msg.get("tool_call_id");
            return tid == null || !declared.contains(String.valueOf(tid));
        });
    }

    @SuppressWarnings("unchecked")
        // 对应 Python _backfill_missing_tool_results() (runner.py:1358)
    public static void backfillMissingToolResults(List<Map<String, Object>> messages) {
        record Declared(int assistantIdx, String callId, String name) {}
        var declared = new ArrayList<Declared>();
        var fulfilled = new java.util.HashSet<String>();

        for (int idx = 0; idx < messages.size(); idx++) {
            var msg = messages.get(idx);
            var role = msg.get("role");
            if ("assistant".equals(role)) {
                var tcs = msg.get("tool_calls");
                if (tcs instanceof List<?> tcList) {
                    for (var tc : tcList) {
                        if (tc instanceof Map<?, ?> tcm) {
                            var id = tcm.get("id");
                            if (id != null) {
                                var func = tcm.get("function");
                                var name = "";
                                if (func instanceof Map<?, ?> fm) {
                                    var n = fm.get("name");
                                    name = n != null ? String.valueOf(n) : "";
                                }
                                declared.add(new Declared(idx, String.valueOf(id), name));
                            }
                        }
                    }
                }
            } else if ("tool".equals(role)) {
                var tid = msg.get("tool_call_id");
                if (tid != null) fulfilled.add(String.valueOf(tid));
            }
        }

        // Insert synthetic results in reverse order (earliest first) after
        // their assistant message, skipping past any existing tool results.
        int offset = 0;
        for (var d : declared) {
            if (fulfilled.contains(d.callId)) continue;
            int insertAt = d.assistantIdx + 1 + offset;
            while (insertAt < messages.size()
                    && "tool".equals(messages.get(insertAt).get("role"))) {
                insertAt++;
            }
            var synthetic = new LinkedHashMap<String, Object>();
            synthetic.put("role", "tool");
            synthetic.put("tool_call_id", d.callId);
            synthetic.put("name", d.name);
            synthetic.put("content", BACKFILL_CONTENT);
            messages.add(insertAt, synthetic);
            offset++;
        }
    }

    static final java.util.Set<String> COMPACTABLE_TOOLS = java.util.Set.of(
            "read_file", "exec", "grep", "find_files",
            "web_search", "web_fetch", "list_dir", "list_exec_sessions");

        // 对应 Python _microcompact() (runner.py:1399)
    public static void microcompact(List<Map<String, Object>> messages) {
        // Collect indices of compactable tool results
        var compactableIndices = new ArrayList<Integer>();
        for (int i = 0; i < messages.size(); i++) {
            var msg = messages.get(i);
            if ("tool".equals(msg.get("role"))
                    && COMPACTABLE_TOOLS.contains(msg.get("name"))) {
                compactableIndices.add(i);
            }
        }
        if (compactableIndices.size() <= MICROCOMPACT_KEEP_RECENT) return;

        // Keep the most recent, compact the stale ones
        int staleCount = compactableIndices.size() - MICROCOMPACT_KEEP_RECENT;
        for (int i = 0; i < staleCount; i++) {
            int idx = compactableIndices.get(i);
            var msg = messages.get(idx);
            var content = msg.get("content");
            if (content instanceof String s && s.length() >= MICROCOMPACT_MIN_CHARS) {
                var name = String.valueOf(msg.getOrDefault("name", "tool"));
                msg.put("content", "[" + name + " result omitted from context]");
            }
        }
    }

    @SuppressWarnings("unchecked")
        // 对应 Python _apply_tool_result_budget() (runner.py:1424)
    public void applyToolResultBudget(AgentRunSpec spec, List<Map<String, Object>> messages) {
        for (int idx = 0; idx < messages.size(); idx++) {
            var msg = messages.get(idx);
            if (!"tool".equals(msg.get("role"))) continue;
            var toolCallId = String.valueOf(msg.getOrDefault("tool_call_id", "tool_" + idx));
            var toolName = String.valueOf(msg.getOrDefault("name", "tool"));
            var content = msg.get("content");
            // normalizeToolResult applies maxToolResultChars and offload logic
            var normalized = normalizeToolResult(spec, toolCallId, toolName, content);
            if (!normalized.equals(content)) {
                msg.put("content", normalized);
            }
        }
    }

    @SuppressWarnings("unchecked")
        // 对应 Python _snip_history() (runner.py:1445)
    public void snipHistory(AgentRunSpec spec, List<Map<String, Object>> messages) {
        if (messages.isEmpty() || spec.contextWindowTokens() == null || spec.contextWindowTokens() <= 0) return;

        int maxOutput = spec.maxTokens() != null ? spec.maxTokens() : 4096;
        int budget = spec.contextBlockLimit() != null ? spec.contextBlockLimit()
                : spec.contextWindowTokens() - maxOutput - SNIP_SAFETY_BUFFER;
        if (budget <= 0) return;

        int estimate = estimatePromptTokens(messages, spec.tools().getDefinitions());
        if (estimate <= budget) return;

        // Separate system messages (always preserved)
        var systemMessages = new ArrayList<Map<String, Object>>();
        var nonSystem = new ArrayList<Map<String, Object>>();
        for (var msg : messages) {
            if ("system".equals(msg.get("role"))) {
                systemMessages.add(msg);
            } else {
                nonSystem.add(msg);
            }
        }
        if (nonSystem.isEmpty()) return;

        int systemTokens = 0;
        for (var sm : systemMessages) {
            systemTokens += com.nanobot.agent.session.Session.estimateMessageTokens(sm);
        }
        int remainingBudget = Math.max(0, budget - systemTokens);

        // Collect from tail within budget
        var kept = new ArrayList<Map<String, Object>>();
        int keptTokens = 0;
        for (int i = nonSystem.size() - 1; i >= 0; i--) {
            var msg = nonSystem.get(i);
            int msgTokens = com.nanobot.agent.session.Session.estimateMessageTokens(msg);
            if (!kept.isEmpty() && keptTokens + msgTokens > remainingBudget) break;
            kept.add(msg);
            keptTokens += msgTokens;
        }
        java.util.Collections.reverse(kept);

        // Align to user turn
        if (!kept.isEmpty()) {
            boolean foundUser = false;
            for (int i = 0; i < kept.size(); i++) {
                if ("user".equals(kept.get(i).get("role"))) {
                    kept = new ArrayList<>(kept.subList(i, kept.size()));
                    foundUser = true;
                    break;
                }
            }
            if (!foundUser) {
                // Recover nearest user from outside window
                for (int i = nonSystem.size() - 1; i >= 0; i--) {
                    if ("user".equals(nonSystem.get(i).get("role"))) {
                        kept = new ArrayList<>(nonSystem.subList(i, nonSystem.size()));
                        break;
                    }
                }
            }
            // Find legal message start (tool results with matching calls)
            int start = findLegalMessageStart(kept);
            if (start > 0) kept = new ArrayList<>(kept.subList(start, kept.size()));
        }
        if (kept.isEmpty()) {
            kept = new ArrayList<>(nonSystem.subList(
                    Math.max(0, nonSystem.size() - 4), nonSystem.size()));
            int start = findLegalMessageStart(kept);
            if (start > 0) kept = new ArrayList<>(kept.subList(start, kept.size()));
        }

        messages.clear();
        messages.addAll(systemMessages);
        messages.addAll(kept);
    }

    @SuppressWarnings("unchecked")
        // 对应 Python _find_legal_message_start — 消息列表校验 (runner.py:1504+)
    static int findLegalMessageStart(List<Map<String, Object>> messages) {
        var declared = new java.util.HashSet<String>();
        int start = 0;
        for (int i = 0; i < messages.size(); i++) {
            var msg = messages.get(i);
            var role = msg.get("role");
            if ("assistant".equals(role)) {
                var tcs = msg.get("tool_calls");
                if (tcs instanceof List<?> tcList) {
                    for (var tc : tcList) {
                        if (tc instanceof Map<?, ?> tcm) {
                            var id = tcm.get("id");
                            if (id != null) declared.add(String.valueOf(id));
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

        // 对应 Python token 估算逻辑 _snip_history() (runner.py:1480+)
    int estimatePromptTokens(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        int total = 0;
        for (var msg : messages) {
            total += com.nanobot.agent.session.Session.estimateMessageTokens(msg);
        }
        return total;
    }

    // -- helpers --

    @SuppressWarnings("unchecked")
        // 对应 Python build_assistant_message — 构造 OpenAI assistant 消息 (runner.py 内部)
    public static Map<String, Object> buildAssistantMessage(
            @Nullable String content, List<ToolCallRequest> toolCalls,
            @Nullable String reasoningContent,
            @Nullable List<Map<String, Object>> thinkingBlocks) {
        var msg = new LinkedHashMap<String, Object>();
        msg.put("role", "assistant");
        msg.put("content", content != null ? content : "");
        if (toolCalls != null && !toolCalls.isEmpty()) {
            var tcList = new ArrayList<Map<String, Object>>();
            for (var tc : toolCalls) {
                tcList.add(tc.toOpenAiToolCall());
            }
            msg.put("tool_calls", tcList);
        }
        if (reasoningContent != null && !reasoningContent.isEmpty()) {
            msg.put("reasoning_content", reasoningContent);
        }
        if (thinkingBlocks != null && !thinkingBlocks.isEmpty()) {
            msg.put("thinking_blocks", thinkingBlocks);
        }
        return msg;
    }

    @SuppressWarnings("unchecked")
        // 对应 Python _append_injected_messages() (runner.py:154)
    public static void appendInjectedMessages(List<Map<String, Object>> messages,
                                              List<Map<String, Object>> injections) {
        for (var injection : injections) {
            if (!messages.isEmpty()
                    && "user".equals(injection.get("role"))
                    && "user".equals(messages.get(messages.size() - 1).get("role"))) {
                var merged = new LinkedHashMap<>(messages.get(messages.size() - 1));
                merged.put("content", mergeMessageContent(
                        merged.get("content"), injection.get("content")));
                messages.set(messages.size() - 1, merged);
                continue;
            }
            messages.add(injection);
        }
    }

        // 对应 Python _merge_message_content() (runner.py:137)
    static Object mergeMessageContent(Object left, Object right) {
        if (left instanceof String ls && right instanceof String rs) {
            return ls.isEmpty() ? rs : ls + "\n\n" + rs;
        }
        var leftBlocks = toBlocks(left);
        var rightBlocks = toBlocks(right);
        var result = new ArrayList<>(leftBlocks);
        result.addAll(rightBlocks);
        return result;
    }

        // 对应 Python _to_blocks() 内部函数 (runner.py:141)
    private static List<Map<String, Object>> toBlocks(Object value) {
        if (value instanceof List<?> l) {
            var blocks = new ArrayList<Map<String, Object>>();
            for (var item : l) {
                if (item instanceof Map<?, ?> m) {
                    blocks.add((Map<String, Object>) m);
                } else {
                    blocks.add(Map.of("type", "text", "text", String.valueOf(item)));
                }
            }
            return blocks;
        }
        if (value == null) return List.of();
        return List.of(Map.of("type", "text", "text", String.valueOf(value)));
    }

    static boolean isBlankText(@Nullable String text) {
        return text == null || text.isBlank();
    }

        // 对应 Python _merge_usage() (runner.py:985)
    static Map<String, Integer> mergeUsage(Map<String, Integer> base, Map<String, Integer> delta) {
        if (delta.isEmpty()) return base;
        var merged = new LinkedHashMap<>(base);
        for (var entry : delta.entrySet()) {
            merged.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
        return merged;
    }

        // 对应 Python _accumulate_usage() (runner.py:980)
    public static void accumulateUsage(Map<String, Integer> target, Map<String, Integer> addition) {
        for (var entry : addition.entrySet()) {
            target.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
    }

        // 对应 Python _usage_total() (runner.py:974)
    public static int usageTotal(Map<String, Integer> usage) {
        if (usage.containsKey("total_tokens")) {
            return usage.get("total_tokens");
        }
        return usage.getOrDefault("prompt_tokens", 0)
                + usage.getOrDefault("completion_tokens", 0);
    }

        // 对应 Python _usage_dict() (runner.py:962)
    public static Map<String, Integer> usageDict(@Nullable Map<String, ?> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        var result = new LinkedHashMap<String, Integer>();
        for (var entry : raw.entrySet()) {
            try {
                var val = entry.getValue();
                if (val instanceof Number n) {
                    result.put(entry.getKey(), n.intValue());
                } else if (val != null) {
                    result.put(entry.getKey(), Integer.parseInt(val.toString()));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    // -- security checks --

    private static final List<String> SSRF_MARKERS = List.of(
            "internal/private url detected",
            "private/internal address",
            "private address"
    );

    private static final String SSRF_BOUNDARY_NOTE =
            "This is a non-bypassable security boundary. Stop trying to access "
            + "private/internal URLs. Do not retry with curl, wget, encoded IPs, "
            + "alternate DNS, redirects, proxies, or another tool. Ask the user for "
            + "local files, logs, screenshots, or an explicit safe public URL instead. "
            + "If the user explicitly trusts this private URL, ask them to whitelist "
            + "the exact IP/CIDR via tools.ssrfWhitelist.";

    private static final List<String> WORKSPACE_VIOLATION_MARKERS = List.of(
            "outside the configured workspace",
            "outside allowed directory",
            "working_dir is outside",
            "working_dir could not be resolved",
            "path outside working dir",
            "path traversal detected"
    );

    /** 从模型名推断 AI 系统（用于 GenAI trace 属性）。
     *  对应 Python langfuse.openai wrapper 自动提取 provider 的逻辑。 */
    private static String guessSystemFromModel(@Nullable String model) {
        if (model == null) return "unknown";
        var lowered = model.toLowerCase();
        if (lowered.contains("claude")) return "anthropic";
        if (lowered.contains("gpt") || lowered.contains("o1") || lowered.contains("o3") || lowered.contains("o4")) return "openai";
        if (lowered.contains("gemini")) return "google_vertex_ai";
        if (lowered.contains("deepseek")) return "deepseek";
        if (lowered.contains("qwen")) return "alibaba_cloud";
        if (lowered.contains("llama") || lowered.contains("mistral") || lowered.contains("mixtral")) return "meta";
        if (lowered.contains("bedrock")) return "aws_bedrock";
        return "unknown";
    }

        // 对应 Python _is_ssrf_violation() (runner.py:1206)
    public static boolean isSsrfViolation(@Nullable String text) {
        if (text == null || text.isEmpty()) return false;
        var lowered = text.toLowerCase();
        return SSRF_MARKERS.stream().anyMatch(lowered::contains);
    }

        // 对应 Python _is_workspace_violation() (runner.py:1213)
    public static boolean isWorkspaceViolation(@Nullable String text) {
        if (text == null || text.isEmpty()) return false;
        var lowered = text.toLowerCase();
        if (isSsrfViolation(lowered)) return true;
        return WORKSPACE_VIOLATION_MARKERS.stream().anyMatch(lowered::contains);
    }

        // 对应 Python _ssrf_soft_payload() (runner.py:1263)
    public static String ssrfSoftPayload(String rawText) {
        var text = rawText.strip();
        if (text.isEmpty()) text = "Error: request blocked by SSRF guard";
        return text + "\n\n" + SSRF_BOUNDARY_NOTE;
    }

        // 对应 Python _event_detail() (runner.py:1268)
    public static String eventDetail(String prefix, String text, int limit) {
        return (prefix + text.replace("\n", " ").strip()).substring(
                0, Math.min(prefix.length() + text.replace("\n", " ").strip().length(), limit));
    }

    // -- message helpers --

        // 对应 Python _append_final_message() (runner.py:1281)
    public static void appendFinalMessage(List<Map<String, Object>> messages,
                                          @Nullable String content) {
        if (content == null || content.isEmpty()) return;
        if (!messages.isEmpty()
                && "assistant".equals(messages.get(messages.size() - 1).get("role"))
                && !messages.get(messages.size() - 1).containsKey("tool_calls")) {
            if (content.equals(messages.get(messages.size() - 1).get("content"))) return;
            messages.set(messages.size() - 1, buildAssistantMessage(content, null, null, null));
            return;
        }
        messages.add(buildAssistantMessage(content, null, null, null));
    }

        // 对应 Python _append_model_error_placeholder() (runner.py:1296)
    public static void appendModelErrorPlaceholder(List<Map<String, Object>> messages) {
        if (!messages.isEmpty()
                && "assistant".equals(messages.get(messages.size() - 1).get("role"))
                && !messages.get(messages.size() - 1).containsKey("tool_calls")) {
            return;
        }
        messages.add(buildAssistantMessage(
                "[Assistant reply unavailable due to model error.]", null, null, null));
    }

    /** Exposed for tests; same as package-private mergeMessageContent. */
    public static Object mergeMessageContentPublic(Object left, Object right) {
        return mergeMessageContent(left, right);
    }
}
