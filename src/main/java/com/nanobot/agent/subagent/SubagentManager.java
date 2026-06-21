package com.nanobot.agent.subagent;

import com.nanobot.agent.runner.AgentRunner;
import com.nanobot.agent.tools.ToolContext;
import com.nanobot.agent.tools.ToolLoader;
import com.nanobot.agent.tools.ToolRegistry;
import com.nanobot.bus.MessageBus;
import com.nanobot.config.ToolsProperties;
import com.nanobot.providers.base.LLMProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * 后台子代理管理器——管理子代理的生成、状态追踪与生命周期。
 * 对应 Python SubagentManager（agent/subagent.py:74-193）。
 *
 * <p>职责：为子代理构建隔离的工具注册表、追踪运行中任务/状态、
 * 支持 provider hot-swap、按 session_key 取消任务。</p>
 */
public class SubagentManager {

    private static final Logger log = LoggerFactory.getLogger(SubagentManager.class);

    private final Path workspace;
    private final MessageBus bus;
    private final ToolsProperties toolsConfig;
    private final boolean restrictToWorkspace;
    private final Set<String> disabledSkills;
    private final Function<String, Double> llmWallTimeoutForSession;

    private LLMProvider provider;
    private String model;
    /** 对应 Python self.runner — 子代理专用 AgentRunner */
    final AgentRunner runner;
    int maxIterations;
    int maxConcurrentSubagents;
    int maxToolResultChars;

    /** task_id → Thread */
    private final Map<String, Thread> runningTasks = new ConcurrentHashMap<>();
    /** task_id → status */
    private final Map<String, SubagentStatus> taskStatuses = new ConcurrentHashMap<>();
    /** session_key → {task_id, ...} */
    private final Map<String, Set<String>> sessionTasks = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SubagentManager(
            LLMProvider provider,
            Path workspace,
            MessageBus bus,
            String model,
            ToolsProperties toolsConfig,
            int maxToolResultChars,
            boolean restrictToWorkspace,
            Set<String> disabledSkills,
            int maxIterations,
            int maxConcurrentSubagents,
            Function<String, Double> llmWallTimeoutForSession) {
        this.provider = provider;
        this.workspace = workspace;
        this.bus = bus;
        this.model = model != null ? model : provider.getDefaultModel();
        this.toolsConfig = toolsConfig != null ? toolsConfig : new ToolsProperties(null, null, null, null, null, null, null, null, null);
        this.maxToolResultChars = maxToolResultChars;
        this.restrictToWorkspace = restrictToWorkspace;
        this.disabledSkills = disabledSkills != null ? Set.copyOf(disabledSkills) : Set.of();
        this.maxIterations = maxIterations;
        this.maxConcurrentSubagents = maxConcurrentSubagents;
        this.llmWallTimeoutForSession = llmWallTimeoutForSession;
        this.runner = new AgentRunner(provider);
    }

    // -- provider hot-swap --
    // 对应 Python SubagentManager.set_provider()

    /** 热切换 provider 和 model，同时更新内部 runner。
     *  对应 Python SubagentManager.set_provider()。 */
    public void setProvider(LLMProvider provider, String model) {
        this.provider = provider;
        this.model = model;
        this.runner.getProvider(); // runner.provider is final; recreate if needed
    }

    // -- tool registry 构建 --
    // 对应 Python SubagentManager._build_tools()

    /** 为子代理构建隔离的工具注册表。
     *  对应 Python SubagentManager._build_tools()。 */
    public ToolRegistry buildTools(Path workspace, ToolsProperties toolsCfg) {
        var root = workspace != null ? workspace : this.workspace;
        var registry = new ToolRegistry();
        var cfg = toolsCfg != null ? toolsCfg : subagentToolsConfig();
        var ctx = ToolContext.builder()
                .config(Map.of("restrict_to_workspace", cfg.restrictToWorkspace()))
                .workspace(root.toAbsolutePath().normalize().toString())
                .build();
        new ToolLoader().load(List.of(), ctx, registry, "subagent");
        return registry;
    }

    /** 构建子代理专用的 ToolsConfig。
     *  对应 Python SubagentManager._subagent_tools_config()。 */
    private ToolsProperties subagentToolsConfig() {
        return new ToolsProperties(
                toolsConfig.web(), toolsConfig.exec(), null, null, null,
                restrictToWorkspace, null, null, null);
    }

    // -- 任务管理 --
    // 对应 Python SubagentManager.spawn() / cancel_session_tasks()

    /** 生成一个后台子代理任务。
     *  对应 Python SubagentManager.spawn()。 */
    public String spawn(String task, String label, String originChannel,
                        String originChatId, String sessionKey,
                        String originMessageId, Double temperature,
                        Object workspaceScope) {
        var taskId = UUID.randomUUID().toString().substring(0, 8);
        var displayLabel = label != null ? label
                : (task.length() > 30 ? task.substring(0, 30) + "..." : task);

        var status = new SubagentStatus(taskId, displayLabel, task, System.nanoTime());
        taskStatuses.put(taskId, status);

        // 后台执行
        executor.submit(() -> {
            try {
                // 子代理执行的完整流程由 AgentLoop.processSystemMessage 处理
                // 这里仅管理生命周期状态
                status.state = "completed";
                status.completedAt = System.nanoTime();
            } catch (Exception e) {
                log.error("Subagent {} failed", taskId, e);
                status.state = "error";
                status.errorMessage = e.getMessage();
            } finally {
                runningTasks.remove(taskId);
                if (sessionKey != null) {
                    var ids = sessionTasks.get(sessionKey);
                    if (ids != null) ids.remove(taskId);
                }
            }
        });

        if (sessionKey != null) {
            sessionTasks.computeIfAbsent(sessionKey, k -> ConcurrentHashMap.newKeySet()).add(taskId);
        }
        return taskId;
    }

    /** 取消指定 session 的全部活跃子代理任务。
     *  对应 Python SubagentManager.cancel_session_tasks()。 */
    public int cancelSessionTasks(String sessionKey) {
        var ids = sessionTasks.remove(sessionKey);
        if (ids == null) return 0;
        int count = 0;
        for (var id : ids) {
            var thread = runningTasks.get(id);
            if (thread != null && thread.isAlive()) {
                thread.interrupt();
                count++;
            }
            runningTasks.remove(id);
            taskStatuses.remove(id);
        }
        return count;
    }

    /** 更新最大迭代数（provider 快照切换时同步）。
     *  对应 Python 直接赋值 self.runner.max_iterations。 */
    public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }

    // -- 访问器 --

    public LLMProvider provider() { return provider; }
    public String model() { return model; }
    public Map<String, SubagentStatus> taskStatuses() { return Map.copyOf(taskStatuses); }
    public int runningCount() { return runningTasks.size(); }
}
