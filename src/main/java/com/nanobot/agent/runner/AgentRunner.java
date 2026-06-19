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
    static final int MAX_INJECTION_CYCLES = 5;
    static final int SNIP_SAFETY_BUFFER = 1024;
    static final int MICROCOMPACT_KEEP_RECENT = 10;
    static final String BACKFILL_CONTENT = "[Tool result unavailable — call was interrupted or lost]";

    private final LLMProvider provider;

    public AgentRunner(LLMProvider provider) {
        this.provider = provider;
    }

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
            return tryFinalizeOnMaxIterations(spec, messages, toolsUsed, usage, runCtx, hadInjections);
        }
        return new AgentRunResult(null, messages, toolsUsed, usage,
                "max_iterations", null, runCtx.toolEvents, hadInjections);
    }

    // -- requestModel --

    LLMResponse requestModel(AgentRunSpec spec, List<Map<String, Object>> messages,
                             AgentHookContext iterCtx) throws Exception {
        var model = spec.model() != null ? spec.model() : provider.getDefaultModel();
        int maxTokens = spec.maxTokens() != null ? spec.maxTokens() : provider.generation.maxTokens();
        double temperature = spec.temperature() != null ? spec.temperature() : provider.generation.temperature();
        var tools = spec.tools().getDefinitions();

        return provider.chatWithRetry(
                messages, tools, model, maxTokens, temperature,
                spec.reasoningEffort(), null,
                spec.providerRetryMode(), spec.retryWaitCallback());
    }

    // -- executeTools --

    List<Map<String, Object>> executeTools(AgentRunSpec spec, List<ToolCallRequest> toolCalls,
                                           AgentHookContext iterCtx) {
        var results = new ArrayList<Map<String, Object>>();
        for (var tc : toolCalls) {
            var result = runTool(spec, tc);
            results.add(result);
            iterCtx.toolCalls.add(tc);
            iterCtx.toolResults.add(result);
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> runTool(AgentRunSpec spec, ToolCallRequest toolCall) {
        var result = new LinkedHashMap<String, Object>();
        result.put("role", "tool");
        result.put("tool_call_id", toolCall.id());
        result.put("name", toolCall.name());

        var tool = spec.tools().get(toolCall.name());
        if (tool == null) {
            result.put("content", "Error: tool '" + toolCall.name() + "' not found");
            return result;
        }

        try {
            Map<String, Object> args;
            if (toolCall.arguments() instanceof Map<?, ?> m) {
                args = (Map<String, Object>) m;
            } else {
                args = Map.of();
            }
            var toolResult = tool.execute(args, ToolContext.create());
            String output = toolResult != null ? toolResult.toString() : "";
            if (output.length() > spec.maxToolResultChars()) {
                output = output.substring(0, spec.maxToolResultChars()) + "\n... (truncated)";
            }
            result.put("content", output);
        } catch (Exception e) {
            result.put("content", "Tool error: " + e.getMessage());
        }
        return result;
    }

    // -- tryFinalizeOnMaxIterations --

    AgentRunResult tryFinalizeOnMaxIterations(AgentRunSpec spec, List<Map<String, Object>> messages,
                                             List<String> toolsUsed, Map<String, Integer> usage,
                                             AgentRunHookContext runCtx, boolean hadInjections) {
        try {
            var prompt = "You have reached the maximum number of iterations. "
                    + "Please provide a final response summarizing what you've done so far. "
                    + "Do NOT call any tools.";
            messages.add(Map.of("role", "user", "content", prompt));
            var model = spec.model() != null ? spec.model() : provider.getDefaultModel();
            var finalResponse = provider.chat(
                    messages, null, model,
                    spec.maxTokens() != null ? spec.maxTokens() : provider.generation.maxTokens(),
                    spec.temperature() != null ? spec.temperature() : provider.generation.temperature(),
                    spec.reasoningEffort(), "none");
            var content = finalResponse.content();
            messages.add(buildAssistantMessage(content, List.of(), null, null));
            return new AgentRunResult(content, messages, toolsUsed, usage,
                    "max_iterations", null, runCtx.toolEvents, hadInjections);
        } catch (Exception e) {
            log.warn("Finalization failed: {}", e.getMessage());
            return new AgentRunResult(null, messages, toolsUsed, usage,
                    "max_iterations", null, runCtx.toolEvents, hadInjections);
        }
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
}
