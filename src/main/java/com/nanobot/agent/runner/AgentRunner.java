package com.nanobot.agent.runner;

import com.nanobot.agent.hook.AgentHookContext;
import com.nanobot.agent.hook.AgentRunHookContext;
import com.nanobot.agent.tools.ToolContext;
import com.nanobot.agent.tools.ToolRegistry;
import com.nanobot.providers.base.LLMProvider;
import com.nanobot.providers.base.LLMResponse;
import com.nanobot.providers.base.ToolCallRequest;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Run a tool-capable LLM loop without product-layer concerns.
 * Mirrors Python AgentRunner class (agent/runner.py 1544 lines).
 */
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    static final int MAX_EMPTY_RETRIES = 2;
    static final int MAX_LENGTH_RECOVERIES = 3;
    static final int MAX_INJECTIONS_PER_TURN = 3;
    static final int MAX_INJECTION_CYCLES = 5;
    static final int SNIP_SAFETY_BUFFER = 1024;
    static final int MICROCOMPACT_KEEP_RECENT = 10;
    static final int MICROCOMPACT_MIN_CHARS = 500;
    static final int MAX_REPEAT_WORKSPACE_VIOLATIONS = 2;
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

    // -- run --

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

    // -- runCore --

    AgentRunResult runCore(AgentRunSpec spec, AgentRunHookContext runCtx) throws InterruptedException {
        var messages = new ArrayList<>(spec.initialMessages());
        var toolsUsed = new ArrayList<String>();
        var usage = Map.<String, Integer>of();
        int injectionCycles = 0;
        boolean hadInjections = false;

        for (int iteration = 0; iteration < spec.maxIterations(); iteration++) {
            // Context governance
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
            LLMResponse response;
            try {
                response = requestModel(spec, messages, iterCtx);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                log.error("Model request failed at iteration {}", iteration, e);
                response = LLMResponse.error("Error calling LLM: " + e.getMessage());
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

    LLMResponse requestFinalizationRetry(AgentRunSpec spec,
                                         List<Map<String, Object>> messages) throws Exception {
        var retryMessages = buildFinalizationRetryMessages(messages);
        return requestNoTools(spec, retryMessages);
    }

    static List<Map<String, Object>> buildFinalizationRetryMessages(List<Map<String, Object>> messages) {
        var retry = new ArrayList<>(messages);
        retry.add(Map.of("role", "user", "content", FINALIZATION_RETRY_PROMPT));
        return retry;
    }

    static List<Map<String, Object>> buildBudgetExhaustedFinalizationMessages(
            List<Map<String, Object>> messages) {
        var retry = new ArrayList<>(messages);
        retry.add(Map.of("role", "user", "content", BUDGET_EXHAUSTED_FINALIZATION_PROMPT));
        return retry;
    }

    static Map<String, Object> buildLengthRecoveryMessage() {
        return Map.of("role", "user", "content", LENGTH_RECOVERY_PROMPT);
    }

    String maxIterationsFallback(AgentRunSpec spec) {
        if (spec.maxIterationsMessage() != null) {
            return spec.maxIterationsMessage().replace("{max_iterations}",
                    String.valueOf(spec.maxIterations()));
        }
        return "Maximum iterations (" + spec.maxIterations()
                + ") reached. Task may be incomplete — send another message to continue.";
    }

    @SuppressWarnings("unchecked")
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
    Map<String, Object> runToolWithClassification(AgentRunSpec spec, ToolCallRequest toolCall,
                                                   Map<String, Integer> extLookupCounts,
                                                   Map<String, Integer> wsViolationCounts) {
        var hint = "\n\n[Analyze the error above and try a different approach.]";

        // Check repeated external lookup
        var lookupSig = externalLookupSignature(toolCall.name(), toolCall.arguments());
        if (lookupSig != null) {
            int count = extLookupCounts.getOrDefault(lookupSig, 0) + 1;
            extLookupCounts.put(lookupSig, count);
            if (count > MAX_REPEAT_EXTERNAL_LOOKUPS) {
                return Map.of("role", "tool", "tool_call_id", toolCall.id(),
                        "name", toolCall.name(),
                        "content", "Repeated external lookup blocked for " + lookupSig + hint);
            }
        }

        // Get tool and params
        var tool = spec.tools().get(toolCall.name());
        Map<String, Object> params;
        if (toolCall.arguments() instanceof Map<?, ?> m) {
            params = (Map<String, Object>) m;
        } else {
            params = Map.of();
        }

        // Execute
        String rawText;
        try {
            var toolResult = tool != null
                    ? tool.execute(params, ToolContext.create())
                    : spec.tools().execute(toolCall.name(), params);
            rawText = toolResult != null ? toolResult.toString() : "";
        } catch (Exception e) {
            rawText = "Error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        // Check for violations in output
        if (rawText.startsWith("Error") || isSsrfViolation(rawText) || isWorkspaceViolation(rawText)) {
            var event = Map.of("name", toolCall.name(), "status", "error",
                    "detail", rawText.replace("\n", " ").trim().substring(
                            0, Math.min(120, rawText.replace("\n", " ").trim().length())));

            // Check SSRF
            if (isSsrfViolation(rawText)) {
                log.warn("Tool {} blocked by SSRF guard: {}", toolCall.name(),
                        rawText.replace("\n", " ").trim().substring(0, Math.min(200, rawText.replace("\n", " ").trim().length())));
                return Map.of("role", "tool", "tool_call_id", toolCall.id(),
                        "name", toolCall.name(), "content", ssrfSoftPayload(rawText));
            }

            // Check workspace violation
            if (isWorkspaceViolation(rawText)) {
                var wsSig = workspaceViolationSignature(toolCall.name(), params);
                if (wsSig != null) {
                    int wsCount = wsViolationCounts.getOrDefault(wsSig, 0) + 1;
                    wsViolationCounts.put(wsSig, wsCount);
                    if (wsCount > MAX_REPEAT_WORKSPACE_VIOLATIONS) {
                        log.warn("Tool {} hit workspace boundary repeatedly; escalating hint", toolCall.name());
                        return Map.of("role", "tool", "tool_call_id", toolCall.id(),
                                "name", toolCall.name(),
                                "content", "Repeated workspace boundary violation for "
                                        + toolCall.name() + ". Stop retrying with different paths. "
                                        + "Stay within the configured workspace directory." + hint);
                    }
                }
                return Map.of("role", "tool", "tool_call_id", toolCall.id(),
                        "name", toolCall.name(), "content", rawText + hint);
            }

            return Map.of("role", "tool", "tool_call_id", toolCall.id(),
                    "name", toolCall.name(), "content", rawText + hint);
        }

        // Success — normalize and truncate
        var normalized = normalizeToolResult(spec, toolCall.id(), toolCall.name(), rawText);
        return Map.of("role", "tool", "tool_call_id", toolCall.id(),
                "name", toolCall.name(), "content", normalized);
    }

    // -- normalizeToolResult --

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

    void emitCheckpoint(AgentRunSpec spec, Map<String, Object> payload) {
        var callback = spec.checkpointCallback();
        if (callback != null) {
            try { callback.accept(payload); } catch (Exception ignored) {}
        }
    }

    // -- external lookup / workspace violation signatures --

    @SuppressWarnings("unchecked")
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
    public static void backfillMissingToolResults(List<Map<String, Object>> messages) {
        var declared = new LinkedHashMap<String, Integer>();
        for (var msg : messages) {
            if ("assistant".equals(msg.get("role"))) {
                var toolCalls = msg.get("tool_calls");
                if (toolCalls instanceof List<?> tcList) {
                    for (var tc : tcList) {
                        if (tc instanceof Map<?, ?> tcm) {
                            var id = tcm.get("id");
                            if (id != null) declared.put(String.valueOf(id), 0);
                        }
                    }
                }
            }
        }
        var hasResult = new java.util.HashSet<String>();
        for (var msg : messages) {
            if ("tool".equals(msg.get("role"))) {
                var tid = msg.get("tool_call_id");
                if (tid != null) hasResult.add(String.valueOf(tid));
            }
        }
        for (var id : declared.keySet()) {
            if (!hasResult.contains(id)) {
                var backfill = new LinkedHashMap<String, Object>();
                backfill.put("role", "tool");
                backfill.put("tool_call_id", id);
                backfill.put("content", BACKFILL_CONTENT);
                messages.add(backfill);
            }
        }
    }

    public static void microcompact(List<Map<String, Object>> messages) {
        int compactTarget = messages.size() - MICROCOMPACT_KEEP_RECENT;
        if (compactTarget <= 0) return;

        int idx = 0;
        var iter = messages.listIterator();
        while (iter.hasNext() && idx < compactTarget) {
            var msg = iter.next();
            if ("tool".equals(msg.get("role"))) {
                iter.remove();
            }
            idx++;
        }
        int legalStart = com.nanobot.agent.session.Session.findLegalMessageStart(messages);
        if (legalStart > 0) {
            messages.subList(0, legalStart).clear();
        }
    }

    @SuppressWarnings("unchecked")
    public static void applyToolResultBudget(AgentRunSpec spec, List<Map<String, Object>> messages) {
        int limit = spec.maxToolResultChars();
        if (limit <= 0) return;
        for (var msg : messages) {
            if (!"tool".equals(msg.get("role"))) continue;
            var content = msg.get("content");
            if (content instanceof String s && s.length() > limit) {
                msg.put("content", s.substring(0, limit) + "\n... (truncated)");
            }
        }
    }

    public static void snipHistory(AgentRunSpec spec, List<Map<String, Object>> messages) {
        if (spec.contextWindowTokens() == null || spec.contextWindowTokens() <= 0) return;
        int budget = spec.contextWindowTokens() - SNIP_SAFETY_BUFFER;
        if (budget <= 0) return;
        int total = 0;
        for (var msg : messages) {
            total += com.nanobot.agent.session.Session.estimateMessageTokens(msg);
        }
        if (total <= budget) return;

        while (messages.size() > 2 && total > budget) {
            var removed = messages.remove(0);
            total -= com.nanobot.agent.session.Session.estimateMessageTokens(removed);
        }
    }

    // -- helpers --

    @SuppressWarnings("unchecked")
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

    static Map<String, Integer> mergeUsage(Map<String, Integer> base, Map<String, Integer> delta) {
        if (delta.isEmpty()) return base;
        var merged = new LinkedHashMap<>(base);
        for (var entry : delta.entrySet()) {
            merged.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
        return merged;
    }

    public static void accumulateUsage(Map<String, Integer> target, Map<String, Integer> addition) {
        for (var entry : addition.entrySet()) {
            target.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
    }

    public static int usageTotal(Map<String, Integer> usage) {
        if (usage.containsKey("total_tokens")) {
            return usage.get("total_tokens");
        }
        return usage.getOrDefault("prompt_tokens", 0)
                + usage.getOrDefault("completion_tokens", 0);
    }

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

    public static boolean isSsrfViolation(@Nullable String text) {
        if (text == null || text.isEmpty()) return false;
        var lowered = text.toLowerCase();
        return SSRF_MARKERS.stream().anyMatch(lowered::contains);
    }

    public static boolean isWorkspaceViolation(@Nullable String text) {
        if (text == null || text.isEmpty()) return false;
        var lowered = text.toLowerCase();
        if (isSsrfViolation(lowered)) return true;
        return WORKSPACE_VIOLATION_MARKERS.stream().anyMatch(lowered::contains);
    }

    public static String ssrfSoftPayload(String rawText) {
        var text = rawText.strip();
        if (text.isEmpty()) text = "Error: request blocked by SSRF guard";
        return text + "\n\n" + SSRF_BOUNDARY_NOTE;
    }

    public static String eventDetail(String prefix, String text, int limit) {
        return (prefix + text.replace("\n", " ").strip()).substring(
                0, Math.min(prefix.length() + text.replace("\n", " ").strip().length(), limit));
    }

    // -- message helpers --

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
