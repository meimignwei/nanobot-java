package com.nanobot.agent;

import com.nanobot.agent.hook.AgentHook;
import com.nanobot.agent.tools.ToolRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * AgentRunner 运行参数规格，包含模型、工具、回调、上下文治理等全部配置。
 *
 * <p>对标 Python {@code nanobot/agent/runner.py} AgentRunSpec 数据类。
 */
public class AgentRunSpec {

    private final List<Map<String, Object>> initialMessages;
    private final ToolRegistry tools;
    private final String model;
    private final int maxIterations;
    private final int maxToolResultChars;
    private final Double temperature;
    private final Integer maxTokens;
    private final String reasoningEffort;
    private final AgentHook hook;
    private final String errorMessage;
    private final String maxIterationsMessage;
    private final boolean concurrentTools;
    private final boolean failOnToolError;
    private final Path workspace;
    private final String sessionKey;
    private final Integer contextWindowTokens;
    private final Integer contextBlockLimit;
    private final String providerRetryMode;
    private final BiFunction<String, Map<String, Object>, CompletableFuture<Void>> progressCallback;
    private final boolean streamProgressDeltas;
    private final Function<String, CompletableFuture<Void>> retryWaitCallback;
    private final Function<Map<String, Object>, CompletableFuture<Void>> checkpointCallback;
    private final Function<Integer, CompletableFuture<List<Map<String, Object>>>> injectionCallback;
    private final Double llmTimeoutS;
    private final Predicate<Void> goalActivePredicate;
    private final String goalContinueMessage;
    private final boolean finalizeOnMaxIterations;

    private AgentRunSpec(Builder b) {
        this.initialMessages = b.initialMessages;
        this.tools = b.tools;
        this.model = b.model;
        this.maxIterations = b.maxIterations;
        this.maxToolResultChars = b.maxToolResultChars;
        this.temperature = b.temperature;
        this.maxTokens = b.maxTokens;
        this.reasoningEffort = b.reasoningEffort;
        this.hook = b.hook;
        this.errorMessage = b.errorMessage;
        this.maxIterationsMessage = b.maxIterationsMessage;
        this.concurrentTools = b.concurrentTools;
        this.failOnToolError = b.failOnToolError;
        this.workspace = b.workspace;
        this.sessionKey = b.sessionKey;
        this.contextWindowTokens = b.contextWindowTokens;
        this.contextBlockLimit = b.contextBlockLimit;
        this.providerRetryMode = b.providerRetryMode;
        this.progressCallback = b.progressCallback;
        this.streamProgressDeltas = b.streamProgressDeltas;
        this.retryWaitCallback = b.retryWaitCallback;
        this.checkpointCallback = b.checkpointCallback;
        this.injectionCallback = b.injectionCallback;
        this.llmTimeoutS = b.llmTimeoutS;
        this.goalActivePredicate = b.goalActivePredicate;
        this.goalContinueMessage = b.goalContinueMessage;
        this.finalizeOnMaxIterations = b.finalizeOnMaxIterations;
    }

    public static Builder builder() { return new Builder(); }

    // getters
    public List<Map<String, Object>> initialMessages() { return initialMessages; }
    public ToolRegistry tools() { return tools; }
    public String model() { return model; }
    public int maxIterations() { return maxIterations; }
    public int maxToolResultChars() { return maxToolResultChars; }
    public Double temperature() { return temperature; }
    public Integer maxTokens() { return maxTokens; }
    public String reasoningEffort() { return reasoningEffort; }
    public AgentHook hook() { return hook; }
    public String errorMessage() { return errorMessage; }
    public String maxIterationsMessage() { return maxIterationsMessage; }
    public boolean concurrentTools() { return concurrentTools; }
    public boolean failOnToolError() { return failOnToolError; }
    public Path workspace() { return workspace; }
    public String sessionKey() { return sessionKey; }
    public Integer contextWindowTokens() { return contextWindowTokens; }
    public Integer contextBlockLimit() { return contextBlockLimit; }
    public String providerRetryMode() { return providerRetryMode; }
    public BiFunction<String, Map<String, Object>, CompletableFuture<Void>> progressCallback() { return progressCallback; }
    public boolean streamProgressDeltas() { return streamProgressDeltas; }
    public Function<String, CompletableFuture<Void>> retryWaitCallback() { return retryWaitCallback; }
    public Function<Map<String, Object>, CompletableFuture<Void>> checkpointCallback() { return checkpointCallback; }
    public Function<Integer, CompletableFuture<List<Map<String, Object>>>> injectionCallback() { return injectionCallback; }
    public Double llmTimeoutS() { return llmTimeoutS; }
    public Predicate<Void> goalActivePredicate() { return goalActivePredicate; }
    public String goalContinueMessage() { return goalContinueMessage; }
    public boolean finalizeOnMaxIterations() { return finalizeOnMaxIterations; }

    public static class Builder {
        private List<Map<String, Object>> initialMessages;
        private ToolRegistry tools;
        private String model;
        private int maxIterations = 200;
        private int maxToolResultChars = 100_000;
        private Double temperature;
        private Integer maxTokens;
        private String reasoningEffort;
        private AgentHook hook;
        private String errorMessage = "Sorry, I encountered an error calling the AI model.";
        private String maxIterationsMessage;
        private boolean concurrentTools;
        private boolean failOnToolError;
        private Path workspace;
        private String sessionKey;
        private Integer contextWindowTokens;
        private Integer contextBlockLimit;
        private String providerRetryMode = "standard";
        private BiFunction<String, Map<String, Object>, CompletableFuture<Void>> progressCallback;
        private boolean streamProgressDeltas = true;
        private Function<String, CompletableFuture<Void>> retryWaitCallback;
        private Function<Map<String, Object>, CompletableFuture<Void>> checkpointCallback;
        private Function<Integer, CompletableFuture<List<Map<String, Object>>>> injectionCallback;
        private Double llmTimeoutS;
        private Predicate<Void> goalActivePredicate;
        private String goalContinueMessage;
        private boolean finalizeOnMaxIterations = true;

        public Builder initialMessages(List<Map<String, Object>> v) { this.initialMessages = v; return this; }
        public Builder tools(ToolRegistry v) { this.tools = v; return this; }
        public Builder model(String v) { this.model = v; return this; }
        public Builder maxIterations(int v) { this.maxIterations = v; return this; }
        public Builder maxToolResultChars(int v) { this.maxToolResultChars = v; return this; }
        public Builder temperature(Double v) { this.temperature = v; return this; }
        public Builder maxTokens(Integer v) { this.maxTokens = v; return this; }
        public Builder reasoningEffort(String v) { this.reasoningEffort = v; return this; }
        public Builder hook(AgentHook v) { this.hook = v; return this; }
        public Builder errorMessage(String v) { this.errorMessage = v; return this; }
        public Builder maxIterationsMessage(String v) { this.maxIterationsMessage = v; return this; }
        public Builder concurrentTools(boolean v) { this.concurrentTools = v; return this; }
        public Builder failOnToolError(boolean v) { this.failOnToolError = v; return this; }
        public Builder workspace(Path v) { this.workspace = v; return this; }
        public Builder sessionKey(String v) { this.sessionKey = v; return this; }
        public Builder contextWindowTokens(Integer v) { this.contextWindowTokens = v; return this; }
        public Builder contextBlockLimit(Integer v) { this.contextBlockLimit = v; return this; }
        public Builder providerRetryMode(String v) { this.providerRetryMode = v; return this; }
        public Builder progressCallback(BiFunction<String, Map<String, Object>, CompletableFuture<Void>> v) { this.progressCallback = v; return this; }
        public Builder streamProgressDeltas(boolean v) { this.streamProgressDeltas = v; return this; }
        public Builder retryWaitCallback(Function<String, CompletableFuture<Void>> v) { this.retryWaitCallback = v; return this; }
        public Builder checkpointCallback(Function<Map<String, Object>, CompletableFuture<Void>> v) { this.checkpointCallback = v; return this; }
        public Builder injectionCallback(Function<Integer, CompletableFuture<List<Map<String, Object>>>> v) { this.injectionCallback = v; return this; }
        public Builder llmTimeoutS(Double v) { this.llmTimeoutS = v; return this; }
        public Builder goalActivePredicate(Predicate<Void> v) { this.goalActivePredicate = v; return this; }
        public Builder goalContinueMessage(String v) { this.goalContinueMessage = v; return this; }
        public Builder finalizeOnMaxIterations(boolean v) { this.finalizeOnMaxIterations = v; return this; }

        public AgentRunSpec build() {
            return new AgentRunSpec(this);
        }
    }
}
