package com.nanobot.agent;

import com.nanobot.agent.hook.AgentHook;
import com.nanobot.agent.tools.ToolContext;
import com.nanobot.agent.tools.ToolLoader;
import com.nanobot.agent.tools.ToolRegistry;
import com.nanobot.agent.tools.FileStates;
import com.nanobot.bus.InboundMessage;
import com.nanobot.bus.MessageBus;
import com.nanobot.config.ToolsConfig;
import com.nanobot.providers.LLMProvider;
import com.nanobot.security.WorkspaceScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 后台子 agent 执行管理——spawn、cancel、状态追踪。
 *
 * <p>对标 Python {@code nanobot/agent/subagent.py:74-394 class SubagentManager}。
 */
public class SubagentManager {

    private static final Logger log = LoggerFactory.getLogger(SubagentManager.class);

    /** 子 agent 工具执行的 scope 标识。 */
    private static final String SUBAGENT_SCOPE = "subagent";

    private LLMProvider provider;
    private final Path workspace;
    private final MessageBus bus;
    private final int maxToolResultChars;
    private String model;
    private final ToolsConfig toolsConfig;
    private final boolean restrictToWorkspace;
    private final Set<String> disabledSkills;
    private final int maxIterations;
    private final int maxConcurrentSubagents;
    private final Function<String, Float> llmWallTimeoutForSession;

    /** 运行中的子 agent 任务（taskId → CompletableFuture）。 */
    private final ConcurrentMap<String, CompletableFuture<Void>> runningTasks = new ConcurrentHashMap<>();

    /** 运行中的子 agent 状态（taskId → SubagentStatus）。 */
    private final ConcurrentMap<String, SubagentStatus> taskStatuses = new ConcurrentHashMap<>();

    /** session 到其子 agent 任务的映射（sessionKey → taskId 集合）。 */
    private final ConcurrentMap<String, Set<String>> sessionTasks = new ConcurrentHashMap<>();

    /**
     * 创建 SubagentManager。
     * 对标 Python SubagentManager.__init__(...)。
     */
    public SubagentManager(LLMProvider provider, Path workspace, MessageBus bus,
                           int maxToolResultChars, @Nullable String model,
                           @Nullable ToolsConfig toolsConfig, boolean restrictToWorkspace,
                           @Nullable List<String> disabledSkills,
                           @Nullable Integer maxIterations,
                           @Nullable Integer maxConcurrentSubagents,
                           @Nullable Function<String, Float> llmWallTimeoutForSession) {
        this.provider = provider;
        this.workspace = workspace;
        this.bus = bus;
        this.model = model != null ? model : provider.getDefaultModel();
        this.toolsConfig = toolsConfig != null ? toolsConfig : new ToolsConfig();
        this.maxToolResultChars = maxToolResultChars;
        this.restrictToWorkspace = restrictToWorkspace;
        this.disabledSkills = disabledSkills != null
                ? ConcurrentHashMap.newKeySet() : ConcurrentHashMap.newKeySet();
        if (disabledSkills != null) this.disabledSkills.addAll(disabledSkills);
        this.maxIterations = maxIterations != null ? maxIterations : 200;
        this.maxConcurrentSubagents = maxConcurrentSubagents != null
                ? maxConcurrentSubagents : 3;
        this.llmWallTimeoutForSession = llmWallTimeoutForSession;
    }

    // ==================== 工具构建 ====================

    /**
     * 构建子 agent 专用的工具注册表。
     * 对标 Python _subagent_tools_config() + _build_tools(workspace, tools_config)。
     */
    private ToolRegistry buildTools(@Nullable Path scope, @Nullable ToolsConfig cfg) {
        Path root = scope != null ? scope : workspace;
        ToolRegistry registry = new ToolRegistry();
        ToolsConfig c = cfg != null ? cfg : toolsConfig;
        ToolContext ctx = ToolContext.builder()
                .config(c)
                .workspace(root.toAbsolutePath().toString())
                .fileStateStore(new FileStates())
                .workspaceSandbox(workspaceSandboxStatus(c.isRestrictToWorkspace(), root))
                .build();
        new ToolLoader().load(ctx, registry, SUBAGENT_SCOPE);
        return registry;
    }

    private static Map<String, Object> workspaceSandboxStatus(boolean restricted, Path ws) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("restrict_to_workspace", restricted);
        status.put("workspace", ws.toString());
        return status;
    }

    // ==================== provider ====================

    /** 对标 Python set_provider(provider, model)。 */
    public void setProvider(LLMProvider provider, String model) {
        this.provider = provider;
        this.model = model;
    }

    // ==================== spawn ====================

    /**
     * 派生子 agent 任务。
     * 对标 Python spawn(task, label=None, ...) async → str。
     *
     * @return 用户可见的确认消息
     */
    public CompletableFuture<String> spawn(
            String task, @Nullable String label,
            String originChannel, String originChatId,
            @Nullable String sessionKey, @Nullable String originMessageId,
            @Nullable Float temperature, @Nullable WorkspaceScope workspaceScope) {

        String taskId = UUID.randomUUID().toString().substring(0, 8);
        String displayLabel = label != null ? label
                : (task.length() > 30 ? task.substring(0, 30) + "..." : task);
        Map<String, String> origin = new LinkedHashMap<>();
        origin.put("channel", originChannel);
        origin.put("chat_id", originChatId);
        if (sessionKey != null) origin.put("session_key", sessionKey);

        SubagentStatus status = new SubagentStatus(
                taskId, displayLabel, task, System.nanoTime(),
                "initializing", 0, List.of(), new LinkedHashMap<>(), null, null);
        taskStatuses.put(taskId, status);

        CompletableFuture<Void> bgTask = runSubagent(
                taskId, task, displayLabel, origin, status,
                originMessageId, temperature, workspaceScope);
        runningTasks.put(taskId, bgTask);
        if (sessionKey != null) {
            sessionTasks.computeIfAbsent(sessionKey,
                    k -> ConcurrentHashMap.newKeySet()).add(taskId);
        }

        // Cleanup callback — 对标 Python _cleanup done_callback
        bgTask.whenComplete((v, ex) -> {
            runningTasks.remove(taskId);
            taskStatuses.remove(taskId);
            if (sessionKey != null) {
                Set<String> ids = sessionTasks.get(sessionKey);
                if (ids != null) {
                    ids.remove(taskId);
                    if (ids.isEmpty()) sessionTasks.remove(sessionKey);
                }
            }
            if (ex != null && !(ex instanceof CancellationException)) {
                log.error("Subagent [{}] terminated with exception", taskId, ex);
            }
        });

        // 防御性：5 分钟后强制清理孤儿状态
        CompletableFuture.delayedExecutor(5, TimeUnit.MINUTES).execute(() -> {
            if (taskStatuses.containsKey(taskId) && bgTask.isDone()) {
                taskStatuses.remove(taskId);
                runningTasks.remove(taskId);
            }
        });

        log.info("Spawned subagent [{}]: {}", taskId, displayLabel);
        return CompletableFuture.completedFuture(
                "Subagent [" + displayLabel + "] started (id: " + taskId
                        + "). I'll notify you when it completes.");
    }

    // ==================== run ====================

    /**
     * 在后台线程中执行子 agent 任务。
     * 对标 Python _run_subagent(task_id, task, ...) async。
     */
    private CompletableFuture<Void> runSubagent(
            String taskId, String task, String label,
            Map<String, String> origin, SubagentStatus status,
            @Nullable String originMessageId, @Nullable Float temperature,
            @Nullable WorkspaceScope workspaceScope) {

        return CompletableFuture.runAsync(() -> {
            log.info("Subagent [{}] starting task: {}", taskId, label);

            // Checkpoint callback
            Function<Map<String, Object>, CompletableFuture<Void>> onCheckpoint = payload -> {
                status.setPhase((String) payload.getOrDefault("phase", status.getPhase()));
                status.setIteration((Integer) payload.getOrDefault("iteration", status.getIteration()));
                return CompletableFuture.completedFuture(null);
            };

            Object workspaceToken = null;
            try {
                Path root = workspaceScope != null
                        ? workspaceScope.getProjectPath() : workspace;
                ToolsConfig cfg = null;
                if (workspaceScope != null) {
                    cfg = ToolsConfig.builder()
                            .exec(toolsConfig.isExec())
                            .web(toolsConfig.isWeb())
                            .restrictToWorkspace(workspaceScope.isRestrictToWorkspace())
                            .build();
                }
                ToolRegistry tools = buildTools(root, cfg);

                // 对标 Python render_template("agent/subagent_system.md")
                String timeCtx = "Current time: " + java.time.Instant.now().toString();
                int toolCount = tools.getDefinitions() != null
                        ? tools.getDefinitions().size() : 0;
                String systemPrompt = RuntimeUtils.renderTemplate(
                        "agent/subagent_system.md",
                        Map.of("time_ctx", timeCtx, "workspace",
                                root.toAbsolutePath().toString(),
                                "skills_summary", toolCount > 0
                                        ? toolCount + " tools available" : ""));

                List<Map<String, Object>> messages = new ArrayList<>();
                messages.add(Map.of("role", "system", "content", systemPrompt));
                messages.add(Map.of("role", "user", "content", task));

                String sessKey = origin.get("session_key");
                Float llmTimeout = llmWallTimeoutForSession != null
                        ? llmWallTimeoutForSession.apply(sessKey) : null;

                // Bind workspace scope
                if (workspaceScope != null) {
                    workspaceToken = bindWorkspaceScope(workspaceScope);
                }

                // Build spec and run
                AgentRunSpec spec = AgentRunSpec.builder()
                        .initialMessages(messages)
                        .tools(tools)
                        .model(model)
                        .temperature(temperature != null ? temperature.doubleValue() : null)
                        .maxIterations(maxIterations)
                        .maxToolResultChars(maxToolResultChars)
                        .hook(new SubagentHook(taskId, status))
                        .maxIterationsMessage("Task completed but no final response was generated.")
                        .finalizeOnMaxIterations(false)
                        .failOnToolError(true)
                        .checkpointCallback(onCheckpoint)
                        .sessionKey(sessKey)
                        .workspace(root)
                        .llmTimeoutS(llmTimeout != null ? llmTimeout.doubleValue() : null)
                        .build();

                AgentRunner runner = new AgentRunner(provider);
                AgentRunResult result = runner.run(spec).join();
                status.setPhase("done");
                status.setStopReason(result.stopReason());

                String outcome;
                if ("tool_error".equals(result.stopReason())) {
                    List<Map<String, Object>> events = new ArrayList<>();
                    for (Map<String, String> e : result.toolEvents()) {
                        events.add(new LinkedHashMap<>(e));
                    }
                    status.setToolEvents(events);
                    announceResult(taskId, label, task,
                            formatPartialProgress(result), origin, "error",
                            originMessageId).join();
                } else if ("error".equals(result.stopReason())) {
                    announceResult(taskId, label, task,
                            result.error() != null ? result.error()
                                    : "Error: subagent execution failed.",
                            origin, "error", originMessageId).join();
                } else {
                    String finalResult = result.finalContent() != null
                            ? result.finalContent()
                            : "Task completed but no final response was generated.";
                    log.info("Subagent [{}] completed successfully", taskId);
                    announceResult(taskId, label, task, finalResult, origin, "ok",
                            originMessageId).join();
                }
            } catch (Exception e) {
                status.setPhase("error");
                status.setError(e.getMessage());
                log.error("Subagent [{}] failed", taskId, e);
                try {
                    announceResult(taskId, label, task, "Error: " + e.getMessage(),
                            origin, "error", originMessageId).join();
                } catch (Exception ignored) {}
            } finally {
                if (workspaceToken != null) resetWorkspaceScope(workspaceToken);
            }
        });
    }

    // ==================== announce ====================

    /**
     * 通过 bus 注入子 agent 结果到主会话。
     * 对标 Python _announce_result(...) async。
     */
    private CompletableFuture<Void> announceResult(
            String taskId, String label, String task, String result,
            Map<String, String> origin, String status,
            @Nullable String originMessageId) {
        String statusText = "ok".equals(status) ? "completed successfully" : "failed";
        // 对标 Python render_template("agent/subagent_announce.md")
        String announceContent = RuntimeUtils.renderTemplate(
                "agent/subagent_announce.md",
                Map.of("label", label, "status_text", statusText,
                        "task", task, "result", result));
        String override = origin.get("session_key") != null
                ? origin.get("session_key")
                : origin.get("channel") + ":" + origin.get("chat_id");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("injected_event", "subagent_result");
        metadata.put("subagent_task_id", taskId);
        if (originMessageId != null) metadata.put("origin_message_id", originMessageId);

        InboundMessage msg = new InboundMessage(
                "system", "subagent",
                origin.get("channel") + ":" + origin.get("chat_id"),
                announceContent, java.time.Instant.now(), List.of(), metadata, override);
        return CompletableFuture.runAsync(() -> {
            try {
                bus.publishInbound(msg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).thenRun(() -> log.debug("Subagent [{}] announced result to {}:{}",
                taskId, origin.get("channel"), origin.get("chat_id")));
    }

    // ==================== partial progress ====================

    /**
     * 格式化部分完成进度文本。
     * 对标 Python _format_partial_progress(result) 静态方法。
     */
    @SuppressWarnings("unchecked")
    private static String formatPartialProgress(AgentRunResult result) {
        List<Map<String, String>> toolEvents = result.toolEvents();
        List<Map<String, String>> completed = toolEvents.stream()
                .filter(e -> "ok".equals(e.get("status")))
                .toList();
        Map<String, String> failure = null;
        List<Map<String, String>> rev = new ArrayList<>(toolEvents);
        Collections.reverse(rev);
        for (Map<String, String> e : rev) {
            if ("error".equals(e.get("status"))) { failure = e; break; }
        }
        List<String> lines = new ArrayList<>();
        if (!completed.isEmpty()) {
            lines.add("Completed steps:");
            List<Map<String, String>> lastCompleted = completed.size() > 3
                    ? completed.subList(completed.size() - 3, completed.size())
                    : completed;
            for (Map<String, String> event : lastCompleted) {
                lines.add("- " + event.get("name") + ": " + event.get("detail"));
            }
        }
        if (failure != null) {
            if (!lines.isEmpty()) lines.add("");
            lines.add("Failure:");
            lines.add("- " + failure.get("name") + ": " + failure.get("detail"));
        }
        if (result.error() != null && failure == null) {
            if (!lines.isEmpty()) lines.add("");
            lines.add("Failure:");
            lines.add("- " + result.error());
        }
        String joined = String.join("\n", lines);
        return joined.isEmpty()
                ? (result.error() != null ? result.error()
                : "Error: subagent execution failed.")
                : joined;
    }

    // ==================== cancel ====================

    /**
     * 取消指定 session 的所有子 agent 任务。
     * 对标 Python cancel_by_session(session_key) async → int。
     */
    public CompletableFuture<Integer> cancelBySession(String sessionKey) {
        Set<String> tids = sessionTasks.get(sessionKey);
        if (tids == null || tids.isEmpty()) return CompletableFuture.completedFuture(0);
        List<CompletableFuture<Void>> toCancel = new ArrayList<>();
        for (String tid : tids) {
            CompletableFuture<Void> task = runningTasks.get(tid);
            if (task != null && !task.isDone()) {
                task.cancel(true);
                toCancel.add(task);
            }
        }
        return CompletableFuture.allOf(
                        toCancel.toArray(new CompletableFuture[0]))
                .handle((v, ex) -> toCancel.size());
    }

    // ==================== counts ====================

    /** 对标 Python get_running_count() → int。 */
    public int getRunningCount() {
        return (int) runningTasks.values().stream()
                .filter(f -> !f.isDone()).count();
    }

    /** 对标 Python get_running_count_by_session(session_key) → int。 */
    public int getRunningCountBySession(String sessionKey) {
        Set<String> tids = sessionTasks.get(sessionKey);
        if (tids == null) return 0;
        int count = 0;
        for (String tid : tids) {
            CompletableFuture<Void> task = runningTasks.get(tid);
            if (task != null && !task.isDone()) count++;
        }
        return count;
    }

    // ==================== workspace scope ====================

    /** 对标 Python security.workspace_access.bind_workspace_scope。 */
    private static Object bindWorkspaceScope(WorkspaceScope scope) {
        return scope;
    }

    /** 对标 Python security.workspace_access.reset_workspace_scope。 */
    private static void resetWorkspaceScope(Object token) {}
}
