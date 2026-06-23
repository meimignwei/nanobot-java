package com.nanobot.agent;

import com.nanobot.agent.hook.AgentHook;
import com.nanobot.agent.hook.AgentProgressHook;
import com.nanobot.agent.hook.CompositeHook;
import com.nanobot.agent.tools.FileStateStore;
import com.nanobot.agent.tools.FileStates;
import com.nanobot.agent.tools.RequestContext;
import com.nanobot.agent.tools.Tool;
import com.nanobot.agent.tools.ToolRegistry;
import com.nanobot.bus.*;
import com.nanobot.command.CommandContext;
import com.nanobot.command.CommandRouter;
import com.nanobot.config.AgentDefaultsProperties;
import com.nanobot.config.ChannelsProperties;
import com.nanobot.config.Config;
import com.nanobot.providers.LLMProvider;
import com.nanobot.utils.DocumentUtil;
import com.nanobot.session.*;
import com.nanobot.session.SessionConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Agent 主循环——驱动消息消费、LLM 对话、工具执行的完整生命周期。
 *
 * <p>对标 Python {@code nanobot/agent/loop.py} AgentLoop 类（约 1779 行）。
 */
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    /** 统一会话键，对标 Python UNIFIED_SESSION_KEY = "unified:default"。 */
    private static final String UNIFIED_SESSION_KEY = "unified:default";

    /** 对标 Python EMPTY_FINAL_RESPONSE_MESSAGE。 */
    private static final String EMPTY_FINAL_RESPONSE_MESSAGE = "[No response generated]";

    /** 对标 Python SUSTAINED_GOAL_CONTINUE_PROMPT。 */
    private static final String SUSTAINED_GOAL_CONTINUE_PROMPT =
            "Continue working toward your goal. What is the next step?";

    // ==================== 依赖 ====================

    private final MessageBus bus;
    private final LLMProvider provider;
    private final ToolRegistry toolRegistry;
    private final SessionManager sessionManager;
    private final MemoryStore memoryStore;
    private final Consolidator consolidator;
    private final CommandRouter commandRouter;
    private final SkillsLoader skillsLoader;
    private final ContextBuilder contextBuilder;
    private final AgentRunner runner;
    private final AutoCompact autoCompact;
    private String model;
    private final int maxIterations;
    private final int maxToolResultChars;
    private final String providerRetryMode;
    private final int maxMessages;
    private final boolean unifiedSession;
    private final int contextWindowTokens;
    /** 对标 Python self._runtime_events() → RuntimeEventPublisher。 */
    private final RuntimeEventPublisher runtimeEvents;
    private final WebuiTurnCoordinator webuiTurnCoordinator;
    private final TurnContinuation turnContinuation;
    private final SubagentManager subagentManager;
    private final Path workspace;

    /** 对标 Python self.model_presets: dict[str, ModelPresetConfig]。 */
    private final Map<String, com.nanobot.config.ModelPresetProperties> modelPresets;
    /** 对标 Python self._active_preset: str | None——当前活跃的模型预设名称。 */
    private String modelPreset;
    /** 对标 Python self._provider_signature——缓存的 provider 快照签名。 */
    private Object providerSignature;
    /** 对标 Python self._default_selection_signature——默认选择的签名。 */
    private Object defaultSelectionSignature;

    /** 对标 Python self._mcp_servers / self._mcp_stacks。 */
    private final Map<String, Object> mcpServers;
    private final Map<String, Object> mcpStacks = new ConcurrentHashMap<>();

    /** 对标 Python self._provider_snapshot_loader——外部 provider 快照加载器。 */
    private final java.util.function.Supplier<Object> providerSnapshotLoader;
    /** 对标 Python self._preset_snapshot_loader——preset 快照加载器。 */
    private final java.util.function.Function<String, Object> presetSnapshotLoader;
    /** 对标 Python self.tools_config。 */
    private final Object toolsConfig;

    /** 额外的生命周期 hook 列表。 */
    private List<AgentHook> extraHooks = new ArrayList<>();

    /** 对标 Python self._background_tasks: list[asyncio.Task]——被追踪的后台任务（shutdown 时排空）。 */
    private final List<CompletableFuture<Void>> backgroundTasks = new CopyOnWriteArrayList<>();

    /** 对标 Python self._file_state_store——每个逻辑 session 一个 FileStateStore，工具解析活跃状态。 */
    private final FileStateStore fileStateStore = new FileStateStore();

    /** 对标 Python self.context_block_limit。 */
    private final Integer contextBlockLimit;

    /** 对标 Python self.tool_hint_max_length。 */
    private final int toolHintMaxLength;

    /** 对标 Python self.channels_config。 */
    private final ChannelsProperties channelsConfig;

    /** 对标 Python self.restrict_to_workspace。 */
    private final boolean restrictToWorkspace;

    /** 对标 Python self._start_time。 */
    private final long startTime = System.currentTimeMillis();

    /** 对标 Python self._last_usage: dict[str, int]。 */
    private volatile Map<String, Integer> lastUsage = Map.of();

    /** 对标 Python self._runtime_vars: dict[str, Any]。 */
    private final Map<String, Object> runtimeVars = new ConcurrentHashMap<>();

    /** 对标 Python self._current_iteration: int。 */
    private volatile int currentIteration;

    // ==================== 并发控制 ====================

    /** 对标 Python self._session_locks: WeakValueDictionary[str, asyncio.Lock]。 */
    private final Map<String, Lock> sessionLocks = new ConcurrentHashMap<>();

    /** 并发门控信号量。 */
    private final Semaphore concurrencyGate;

    /** 运行中的 session → pending 消息队列映射。 */
    private final Map<String, BlockingQueue<InboundMessage>> pendingQueues = new ConcurrentHashMap<>();

    /** 运行中的 session → 活跃任务列表。 */
    private final Map<String, List<CompletableFuture<Void>>> activeTasks = new ConcurrentHashMap<>();

    private volatile boolean running = false;

    // ==================== 构造 ====================

    public AgentLoop(MessageBus bus, LLMProvider provider, Path workspace, String model,
                     Integer maxIterations, Integer contextWindowTokens, Integer maxToolResultChars,
                     String providerRetryMode, Integer maxMessages, boolean unifiedSession,
                     SessionManager sessionManager, Consolidator consolidator,
                     CommandRouter commandRouter, SkillsLoader skillsLoader,
                     ContextBuilder contextBuilder, MemoryStore memoryStore,
                     AutoCompact autoCompact,
                     RuntimeEventBus eventsBus, WebuiTurnCoordinator webuiTurnCoordinator,
                     TurnContinuation turnContinuation, ToolRegistry toolRegistry,
                     SubagentManager subagentManager,
                     @Nullable Map<String, com.nanobot.config.ModelPresetProperties> modelPresets,
                     @Nullable String modelPreset,
                     @Nullable Integer contextBlockLimit,
                     int toolHintMaxLength,
                     @Nullable ChannelsProperties channelsConfig,
                     boolean restrictToWorkspace,
                     int sessionTtlMinutes,
                     double consolidationRatio,
                     int maxConcurrentSubagents,
                     @Nullable java.util.function.Supplier<Object> providerSnapshotLoader,
                     @Nullable java.util.function.Function<String, Object> presetSnapshotLoader,
                     @Nullable Object toolsConfig,
                     @Nullable Map<String, Object> mcpServers) {
        this.bus = bus;
        this.provider = provider;
        this.model = model != null ? model : provider.getDefaultModel();
        this.maxIterations = maxIterations != null ? maxIterations : 200;
        this.contextWindowTokens = contextWindowTokens != null ? contextWindowTokens : 65536;
        this.maxToolResultChars = maxToolResultChars != null ? maxToolResultChars : 100_000;
        this.providerRetryMode = providerRetryMode != null ? providerRetryMode : "standard";
        this.maxMessages = maxMessages != null && maxMessages > 0 ? maxMessages : 120;
        this.unifiedSession = unifiedSession;
        this.sessionManager = sessionManager;
        this.consolidator = consolidator;
        this.commandRouter = commandRouter;
        this.skillsLoader = skillsLoader;
        this.contextBuilder = contextBuilder;
        this.autoCompact = autoCompact;
        this.memoryStore = memoryStore;
        this.runtimeEvents = new RuntimeEventPublisher(eventsBus);
        this.webuiTurnCoordinator = webuiTurnCoordinator;
        this.turnContinuation = turnContinuation;
        this.toolRegistry = toolRegistry;
        this.subagentManager = subagentManager;
        this.workspace = workspace;
        this.modelPresets = modelPresets != null
                ? Collections.unmodifiableMap(modelPresets) : Map.of();
        this.modelPreset = modelPreset;
        this.runner = new AgentRunner(provider);
        int maxConc = maxConcurrentSubagents > 0 ? maxConcurrentSubagents : Integer.parseInt(
                System.getenv().getOrDefault("NANOBOT_MAX_CONCURRENT_REQUESTS", "3"));
        this.concurrencyGate = maxConc > 0 ? new Semaphore(maxConc) : null;
        this.contextBlockLimit = contextBlockLimit;
        this.toolHintMaxLength = toolHintMaxLength > 0 ? toolHintMaxLength : 40;
        this.channelsConfig = channelsConfig;
        this.restrictToWorkspace = restrictToWorkspace;
        this.providerSnapshotLoader = providerSnapshotLoader;
        this.presetSnapshotLoader = presetSnapshotLoader;
        this.toolsConfig = toolsConfig;
        this.mcpServers = new ConcurrentHashMap<>(mcpServers != null ? mcpServers : Map.of());
        // 对标 Python __init__: 注册默认工具集
        registerDefaultTools();
    }

    // ==================== fromConfig 工厂 ====================

    /**
     * 从 Config 装配 AgentLoop 及其所有依赖。
     * 对标 Python {@code from_config(config, **extra)} classmethod。
     *
     * @param config 运行时配置
     * @param extra  额外的关键字参数
     * @return 装配完成的 AgentLoop 实例
     */
    @SuppressWarnings("unchecked")
    public static AgentLoop fromConfig(Config config, Map<String, Object> extra) {
        // 从 extra map 中提取运行时组件（由 boot 层注入）
        Path workspace = config.workspacePath();
        MessageBus bus = (MessageBus) extra.get("bus");
        LLMProvider provider = (LLMProvider) extra.get("provider");
        String model = (String) extra.getOrDefault("model", provider.getDefaultModel());
        SessionManager sessionManager = (SessionManager) extra.get("sessionManager");
        CommandRouter commandRouter = (CommandRouter) extra.get("commandRouter");
        RuntimeEventBus eventsBus = (RuntimeEventBus) extra.get("runtimeEvents");
        ToolRegistry toolRegistry = (ToolRegistry) extra.get("toolRegistry");

        if (bus == null || provider == null || sessionManager == null) {
            throw new IllegalArgumentException("extra map must contain bus, provider, sessionManager");
        }

        AgentDefaultsProperties defaults = config.getAgents().getDefaults();
        int contextWindowTokens = defaults.getContextWindowTokens();
        int maxToolResultChars = defaults.getMaxToolResultChars();
        int maxIterations = defaults.getMaxToolIterations();
        int maxMessages = defaults.getMaxMessages();
        boolean unifiedSession = defaults.isUnifiedSession();
        String providerRetryMode = defaults.getProviderRetryMode();
        int toolHintMaxLength = defaults.getToolHintMaxLength();
        Integer contextBlockLimit = defaults.getContextBlockLimit();
        int sessionTtlMinutes = defaults.getSessionTtlMinutes();
        double consolidationRatio = defaults.getConsolidationRatio();
        int maxConcurrentSubagents = defaults.getMaxConcurrentSubagents();

        // 对标 Python from_config: 使用 resolved preset 的 context_window_tokens
        Integer resolvedCtx = (Integer) extra.get("context_window_tokens");
        if (resolvedCtx != null) contextWindowTokens = resolvedCtx;

        ChannelsProperties channelsConfig = config.getChannels();
        boolean restrictToWorkspace = config.getTools() != null && config.getTools().restrictToWorkspace();

        // 对标 Python from_config: 提取 provider_snapshot_loader / preset_snapshot_loader
        java.util.function.Supplier<Object> providerSnapshotLoader =
                (java.util.function.Supplier<Object>) extra.get("provider_snapshot_loader");
        java.util.function.Function<String, Object> presetSnapshotLoader =
                (java.util.function.Function<String, Object>) extra.get("preset_snapshot_loader");
        Object toolsConfig = extra.get("tools_config");
        @SuppressWarnings("unchecked")
        Map<String, Object> mcpServers = (Map<String, Object>) extra.get("mcp_servers");

        MemoryStore memoryStore = new MemoryStore(workspace);
        Consolidator consolidator = new Consolidator(memoryStore, provider, model,
                sessionManager, contextWindowTokens, null, null, 4096, consolidationRatio, unifiedSession);
        List<String> disabledSkills = defaults.getDisabledSkills();
        Set<String> disabledSkillsSet = disabledSkills != null
                ? new HashSet<>(disabledSkills) : Set.of();
        String timezone = defaults.getTimezone();
        SkillsLoader skillsLoader = new SkillsLoader(workspace, null, disabledSkillsSet);
        ContextBuilder contextBuilder = new ContextBuilder(workspace, timezone, disabledSkills);
        AutoCompact autoCompact = new AutoCompact(sessionManager, consolidator, sessionTtlMinutes);
        WebuiTurnCoordinator webuiTurnCoordinator = new WebuiTurnCoordinator(
                bus, sessionManager, task -> CompletableFuture.runAsync(task));
        TurnContinuation turnContinuation = TurnContinuation.getInstance();
        SubagentManager subagentManager = new SubagentManager(provider, workspace, bus,
                maxToolResultChars, model, null, false, null, maxIterations, null, null);

        // 对标 Python from_config: 提取 model presets（含 "default"）
        Map<String, com.nanobot.config.ModelPresetProperties> presets =
                new LinkedHashMap<>(config.getModelPresets());
        presets.put("default", config.resolveDefaultPreset());
        String activePreset = defaults.getModelPreset();

        return new AgentLoop(bus, provider, workspace, model,
                maxIterations, contextWindowTokens, maxToolResultChars,
                providerRetryMode, maxMessages, unifiedSession,
                sessionManager, consolidator, commandRouter, skillsLoader,
                contextBuilder, memoryStore, autoCompact, eventsBus,
                webuiTurnCoordinator, turnContinuation, toolRegistry, subagentManager,
                presets, activePreset,
                contextBlockLimit, toolHintMaxLength,
                channelsConfig, restrictToWorkspace,
                sessionTtlMinutes, consolidationRatio, maxConcurrentSubagents,
                providerSnapshotLoader, presetSnapshotLoader, toolsConfig, mcpServers);
    }

    // ==================== run 主循环 ====================

    /**
     * 启动长期运行的 agent 主循环。
     * 对标 Python {@code async def run()}。
     */
    /**
     * 启动长期运行的 agent 主循环——先连接 MCP 服务器，再消费消息。
     * 对标 Python {@code async def run()}。
     */
    public CompletableFuture<Void> run() {
        running = true;
        // 对标 Python run(): 先连接 MCP 服务器再进入循环
        return connectMcp().thenCompose(v -> {
            log.info("Agent loop started");
            return runLoop();
        });
    }

    /**
     * 核心消息消费循环——消费入站消息 → 处理 → 检查过期会话 → 继续。
     * 对标 Python run() 内的 while True 循环。
     */
    private CompletableFuture<Void> runLoop() {
        if (!running) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return bus.consumeInbound();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            }
        })
                .orTimeout(1, TimeUnit.SECONDS)
                .thenCompose(this::processSingleMessage)
                .exceptionally(ex -> {
                    Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        autoCompact.checkExpired(
                                cf -> CompletableFuture.runAsync(() -> {}),
                                pendingQueues.keySet());
                        return null;
                    }
                    if (cause instanceof CancellationException) {
                        if (!running) throw (CancellationException) cause;
                        return null;
                    }
                    log.warn("Error consuming inbound message: {}", cause.getMessage());
                    return null;
                })
                .thenCompose(v -> runLoop());
    }

    /**
     * 处理单条入站消息——运行时控制检查 → 命令分发 → pending 队列路由 → 调度执行。
     * 对标 Python {@code _process_single_message()}。
     */
    /**
     * 处理单条入站消息——运行时控制检查 → 命令分发 → pending 队列路由 → 调度执行。
     * 对标 Python {@code _process_single_message()}。
     */
    private CompletableFuture<Void> processSingleMessage(InboundMessage msg) {
        String raw = msg.content() != null ? msg.content().strip() : "";
        String effectiveKey = effectiveSessionKey(msg);

        // 对标 Python run(): 处理运行时控制指令（如 my.set 变量变更）
        if (handleRuntimeControl(msg)) {
            return CompletableFuture.completedFuture(null);
        }

        // 运行时控制 (如 /stop)
        return CompletableFuture.supplyAsync(() -> false)
                .thenApply(handled -> {
                    if (commandRouter != null && commandRouter.isPriority(raw)) {
                        dispatchCommandInline(msg, effectiveKey, raw,
                                commandRouter::dispatchPriority);
                        return true;
                    }
                    return false;
                })
                .thenCompose(handled -> {
                    if (handled) return CompletableFuture.completedFuture(null);

                    // pending queue 路由
                    if (pendingQueues.containsKey(effectiveKey)) {
                        if (commandRouter != null && commandRouter.isDispatchableCommand(raw)) {
                            return dispatchCommandInline(msg, effectiveKey, raw,
                                    commandRouter::dispatch)
                                    .thenApply(v -> null);
                        }
                        InboundMessage pendingMsg = effectiveKey.equals(msg.sessionKey())
                                ? msg : msg.withSessionKeyOverride(effectiveKey);
                        BlockingQueue<InboundMessage> queue = pendingQueues.get(effectiveKey);
                        if (queue != null && queue.offer(pendingMsg)) {
                            log.info("Routed follow-up message to pending queue for session {}",
                                    effectiveKey);
                            return CompletableFuture.completedFuture(null);
                        }
                        log.warn("Pending queue full for session {}, falling back", effectiveKey);
                    }

                    // 创建调度任务
                    CompletableFuture<Void> task = dispatch(msg);
                    activeTasks.computeIfAbsent(effectiveKey,
                            k -> new CopyOnWriteArrayList<>()).add(task);
                    task.whenComplete((v, ex) -> {
                        List<CompletableFuture<Void>> tasks = activeTasks.get(effectiveKey);
                        if (tasks != null) tasks.remove(task);
                    });
                    return task;
                });
    }

    /** 停止主循环。对标 Python close_mcp + stop。 */
    public void stop() {
        running = false;
        closeMcp();
        log.info("Agent loop stopping");
    }

    /** 对标 Python set_provider(provider, model, *, model_preset=None)。 */
    public void setProvider(LLMProvider provider, String model) {
        runnerSetProvider(provider, model);
        subagentManager.setProvider(provider, model);
        consolidator.setProvider(provider, model, contextWindowTokens);
    }

    private void runnerSetProvider(LLMProvider p, String m) {
        // Provider is already injected; model change propagates via spec
    }

    /**
     * 设置模型预设，应用 snapshot 并发布 runtime_model_changed 事件。
     * 对标 Python set_model_preset(name, publish_update=True)。
     */
    public void setModelPreset(@Nullable String name) {
        if (name == null || name.isBlank()) {
            this.modelPreset = null;
            return;
        }
        name = name.trim();
        if (!modelPresets.containsKey(name)) {
            log.warn("Model preset '{}' not found. Available: {}", name, modelPresets.keySet());
            return;
        }
        String oldModel = this.model;
        this.modelPreset = name;
        com.nanobot.config.ModelPresetProperties preset = modelPresets.get(name);
        // 对标 Python: 应用 preset 配置到 provider
        if (preset.model() != null) this.model = preset.model();
        if (preset.contextWindowTokens() > 0) {
            // contextWindowTokens 通过 consolidator 更新
            consolidator.setProvider(provider, this.model,
                    preset.contextWindowTokens() > 0
                            ? preset.contextWindowTokens() : this.contextWindowTokens);
        }
        // 对标 Python: 发布 runtime_model_changed 事件
        runtimeEvents.runtimeModelChanged(this.model, this.modelPreset);
        log.info("Runtime model switched for next turn: {} -> {} (preset={})",
                oldModel, this.model, name);
    }

    /**
     * 从外部配置源重新加载 provider 快照并对比签名，有变化时应用新 snapshot。
     * 对标 Python {@code _refresh_provider_snapshot()}。
     */
    // 对标 Python loop.py:426-448 _refresh_provider_snapshot()
    private void refreshProviderSnapshot() {
        if (providerSnapshotLoader == null) return;
        Object snapshot;
        try {
            snapshot = providerSnapshotLoader.get();
        } catch (Exception e) {
            log.warn("Failed to refresh provider config", e);
            return;
        }
        if (snapshot == null) return;
        // 对标 Python: 提取 snapshot 签名
        Object newSignature;
        try {
            java.lang.reflect.Method sigMethod = snapshot.getClass().getMethod("getSignature");
            newSignature = sigMethod.invoke(snapshot);
        } catch (Exception e) {
            log.debug("Cannot extract signature from provider snapshot: {}", e.getMessage());
            return;
        }
        // 对标 Python: default_selection_signature 检查——判断默认选择是否变化
        Object defaultSig = extractDefaultSelectionSignature(snapshot);
        // 对标 Python: 活跃 preset + 默认签名未变 → 从 preset 重建快照
        if (modelPreset != null
                && (defaultSelectionSignature == null || Objects.equals(defaultSelectionSignature, defaultSig))) {
            defaultSelectionSignature = defaultSig;
            try {
                snapshot = buildModelPresetSnapshot(modelPreset);
                if (snapshot == null) return;
            } catch (Exception e) {
                log.warn("Failed to refresh active model preset", e);
                return;
            }
        } else {
            // 对标 Python: 默认选择已变化，清除活跃 preset
            modelPreset = null;
            defaultSelectionSignature = defaultSig;
        }
        if (Objects.equals(newSignature, this.providerSignature)) return;
        this.providerSignature = newSignature;
        this.defaultSelectionSignature = defaultSig;
        // 对标 Python: 应用 provider snapshot
        applyProviderSnapshot(snapshot);
    }

    /**
     * 提取 snapshot 的默认选择签名。
     * 对标 Python preset_helpers.default_selection_signature()。
     */
    private Object extractDefaultSelectionSignature(Object snapshot) {
        try {
            java.lang.reflect.Method m = snapshot.getClass().getMethod("getDefaultSelectionSignature");
            return m.invoke(snapshot);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从活跃 preset 重建 provider 快照。
     * 对标 Python loop.py:458-464 _build_model_preset_snapshot()。
     */
    private Object buildModelPresetSnapshot(String name) {
        if (presetSnapshotLoader == null) return null;
        try {
            return presetSnapshotLoader.apply(name);
        } catch (Exception e) {
            log.warn("Failed to build model preset snapshot for '{}'", name, e);
            return null;
        }
    }

    /**
     * 应用 provider snapshot 到 runner / consolidator / model 字段。
     * 对标 Python loop.py _apply_provider_snapshot()。
     */
    private void applyProviderSnapshot(Object snapshot) {
        try {
            java.lang.reflect.Method getProvider = snapshot.getClass().getMethod("getProvider");
            java.lang.reflect.Method getModel = snapshot.getClass().getMethod("getModel");
            java.lang.reflect.Method getCtx = snapshot.getClass().getMethod("getContextWindowTokens");
            LLMProvider newProvider = (LLMProvider) getProvider.invoke(snapshot);
            String newModel = (String) getModel.invoke(snapshot);
            Integer newCtx = (Integer) getCtx.invoke(snapshot);
            if (newProvider != null) {
                runnerSetProvider(newProvider, newModel);
                this.model = newModel != null ? newModel : this.model;
                consolidator.setProvider(newProvider, this.model,
                        newCtx != null && newCtx > 0 ? newCtx : this.contextWindowTokens);
                runtimeEvents.runtimeModelChanged(this.model, this.modelPreset);
                log.info("Runtime model switched via snapshot refresh: {}", this.model);
            }
        } catch (Exception e) {
            log.debug("Cannot apply provider snapshot: {}", e.getMessage());
        }
    }

    /** 对标 Python model_preset property getter。 */
    public String modelPreset() { return modelPreset; }

    /**
     * 返回当前循环拥有的 LLMRuntime（provider + model 对）。
     * 对标 Python llm_runtime() property。
     */
    // 对标 Python loop.py:158-161 llm_runtime()
    public LLMRuntime llmRuntime() {
        refreshProviderSnapshot();
        return new LLMRuntime(provider, model);
    }

    /**
     * 返回消息的运行时显示 chat_id（优先使用 context_chat_id）。
     * 对标 Python _runtime_chat_id(msg) 静态方法。
     */
    // 对标 Python loop.py:535-538 _runtime_chat_id()
    public static String runtimeChatId(InboundMessage msg) {
        Map<String, Object> meta = msg.metadata();
        if (meta != null && meta.get("context_chat_id") != null) {
            return String.valueOf(meta.get("context_chat_id"));
        }
        return msg.chatId();
    }

    /**
     * 判断是否应提取文档文本（由 channels config 控制）。
     * 对标 Python _should_extract_document_text()。
     */
    // 对标 Python loop.py:1358-1361 _should_extract_document_text()
    private boolean shouldExtractDocumentText() {
        if (channelsConfig == null) return true;
        return channelsConfig.isExtractDocumentText();
    }

    /**
     * 返回当前迭代计数。
     * 对标 Python current_iteration property。
     */
    // 对标 Python loop.py:150-152 current_iteration property
    public int currentIteration() { return currentIteration; }

    /** 对标 Python tool_names property。 */
    public List<String> toolNames() { return toolRegistry.getToolNames(); }

    /**
     * 通过 ToolLoader 注册默认工具集，对标 Python _register_default_tools()。
     * 若 toolRegistry 已有工具（由外部注入），则仅补注 MyTool。
     */
    // 对标 Python loop.py:473-501 _register_default_tools()
    private void registerDefaultTools() {
        com.nanobot.agent.tools.ToolContext ctx = new com.nanobot.agent.tools.ToolContext(
                null, // toolsConfig — Java 中工具配置由外部注入
                workspace.toString(),
                bus,
                subagentManager,
                null, // cronService
                sessionManager,
                fileStateStore,
                null, // providerSnapshotLoader
                null, // imageGenerationProviderConfigs
                "UTC", // timezone
                null, // workspaceSandbox
                runtimeEvents
        );
        com.nanobot.agent.tools.ToolLoader loader = new com.nanobot.agent.tools.ToolLoader();
        List<String> registered = loader.load(ctx, toolRegistry, "core");

        // 对标 Python: MyTool 需要 runtime_state 引用——手动注册
        // 若 toolRegistry 中尚无 "my" 工具，注册一个默认实例
        if (!toolRegistry.has("my")) {
            toolRegistry.register(new com.nanobot.agent.tools.impl.MyTool());
            registered.add("my");
        }

        log.info("Registered {} tools: {}", registered.size(), registered);
    }

    /** 对标 Python cancel_by_session(key)。 */
    public int cancelBySession(String sessionKey) {
        int count = 0;
        List<CompletableFuture<Void>> tasks = activeTasks.remove(sessionKey);
        if (tasks != null) {
            for (CompletableFuture<Void> task : tasks) {
                if (!task.isDone()) { task.cancel(true); count++; }
            }
        }
        try {
            count += subagentManager.cancelBySession(sessionKey).join();
        } catch (Exception ignored) {}
        pendingQueues.remove(sessionKey);
        runtimeEvents.clearTurn(sessionKey);
        return count;
    }

    /** 对标 Python _connect_mcp()——存根，MCP 服务器连接需外部库。 */
    public CompletableFuture<Void> connectMcp() {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 处理运行时控制指令（如 runtime variable changes）。
     * 对标 Python {@code agent_context.handle_runtime_control(state, msg, tools)}。
     *
     * @param msg 入站消息
     * @return 如果消息被运行时控制指令消费返回 true
     */
    // 对标 Python context.py:47-48 handle_runtime_control() + mcp_tools.handle_runtime_control()
    private boolean handleRuntimeControl(InboundMessage msg) {
        Map<String, Object> meta = msg.metadata();
        if (meta == null) return false;
        Object ctrl = meta.get("_runtime_control");
        if (ctrl == null) return false;
        // 对标 Python: my.set 运行时变量变更
        if ("my.set".equals(ctrl)) {
            Object key = meta.get("_key");
            Object value = meta.get("_value");
            if (key instanceof String k) {
                runtimeVars.put(k, value);
                log.debug("Runtime variable set: {} = {}", k, value);
                return true;
            }
        }
        // 对标 Python mcp_tools.py: MCP hot reload 请求
        if ("mcp_reload".equals(ctrl)) {
            log.info("MCP reload requested; reconnecting MCP servers");
            connectMcp().join();
            return true;
        }
        return false;
    }

    /**
     * 排空 pending background archives，然后关闭 MCP 连接。
     * 对标 Python close_mcp()——先排空后台任务再清理连接。
     */
    public CompletableFuture<Void> closeMcp() {
        // 对标 Python: 先排空后台任务（归档完成后再关闭连接）
        CompletableFuture<Void> drainFuture;
        if (!backgroundTasks.isEmpty()) {
            List<CompletableFuture<Void>> snapshot = new ArrayList<>(backgroundTasks);
            backgroundTasks.clear();
            drainFuture = CompletableFuture.allOf(
                    snapshot.toArray(new CompletableFuture[0]))
                    .exceptionally(ex -> null);
        } else {
            drainFuture = CompletableFuture.completedFuture(null);
        }
        return drainFuture.thenRun(() -> {
            mcpStacks.clear();
            mcpServers.clear();
        });
    }

    /**
     * 调度一个 CompletableFuture 作为被追踪的后台任务（shutdown 时排空）。
     * 对标 Python _schedule_background(coro)。
     */
    private void scheduleBackground(CompletableFuture<Void> task) {
        backgroundTasks.add(task);
        task.whenComplete((v, ex) -> backgroundTasks.remove(task));
        task.exceptionally(ex -> {
            log.warn("Background task failed", ex);
            return null;
        });
    }

    // ==================== dispatch ====================

    /**
     * 按 session 串行、跨 session 并发的消息调度。
     * 对标 Python {@code async def _dispatch()}。
     */
    private CompletableFuture<Void> dispatch(InboundMessage msg) {
        String sessionKey = effectiveSessionKey(msg);
        InboundMessage effectiveMsg = sessionKey.equals(msg.sessionKey())
                ? msg : msg.withSessionKeyOverride(sessionKey);

        Lock lock = sessionLocks.computeIfAbsent(sessionKey, k -> new ReentrantLock());
        BlockingQueue<InboundMessage> pending = new LinkedBlockingQueue<>(20);
        pendingQueues.put(sessionKey, pending);

        return CompletableFuture.runAsync(() -> {
            if (concurrencyGate != null) {
                try { concurrencyGate.acquire(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            }
            lock.lock();
        }).thenCompose(v -> {
            // 流式回调
            boolean wantsStream = Boolean.TRUE.equals(
                    msg.metadata() != null ? msg.metadata().get("_wants_stream") : null);
            String streamBaseId = sessionKey + ":" + System.nanoTime();

            Function<String, CompletableFuture<Void>> onStream = null;
            Function<Boolean, CompletableFuture<Void>> onStreamEnd = null;

            if (wantsStream) {
                // 对标 Python _dispatch: 用 stream_segment 跟踪流式分段
                AtomicInteger streamSegmentCounter = new AtomicInteger(0);

                onStream = delta -> CompletableFuture.runAsync(() -> {
                    try {
                        Map<String, Object> streamMeta = new LinkedHashMap<>(
                                effectiveMsg.metadata() != null
                                        ? effectiveMsg.metadata() : Map.of());
                        streamMeta.put("_stream_delta", true);
                        streamMeta.put("_stream_id",
                                streamBaseId + ":" + streamSegmentCounter.get());
                        bus.publishOutbound(new OutboundMessage(
                                effectiveMsg.channel(), effectiveMsg.chatId(), delta,
                                null, null, streamMeta, List.of()));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                // 对标 Python _dispatch: on_stream_end 跟踪 resuming 参数 + 递增 stream_segment
                onStreamEnd = resuming -> CompletableFuture.runAsync(() -> {
                    try {
                        Map<String, Object> endMeta = new LinkedHashMap<>(
                                effectiveMsg.metadata() != null
                                        ? effectiveMsg.metadata() : Map.of());
                        endMeta.put("_stream_end", true);
                        endMeta.put("_resuming", resuming);
                        endMeta.put("_stream_id",
                                streamBaseId + ":" + streamSegmentCounter.get());
                        bus.publishOutbound(new OutboundMessage(
                                effectiveMsg.channel(), effectiveMsg.chatId(), "",
                                null, null, endMeta, List.of()));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    streamSegmentCounter.incrementAndGet();
                });
            }

            return processMessage(effectiveMsg, onStream, onStreamEnd, pending)
                    .thenCompose(response -> {
                        CompletableFuture<Void> publishFuture = CompletableFuture.completedFuture(null);
                        if (response != null) {
                            publishFuture = CompletableFuture.runAsync(() -> {
                                try {
                                    bus.publishOutbound(response);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            });
                        } else if ("cli".equals(effectiveMsg.channel())) {
                            // 对标 Python _dispatch: CLI channel 空响应时发送空出站消息
                            // 对标 Python loop.py:986-990
                            publishFuture = CompletableFuture.runAsync(() -> {
                                try {
                                    bus.publishOutbound(new OutboundMessage(
                                            effectiveMsg.channel(), effectiveMsg.chatId(),
                                            "", null, null,
                                            effectiveMsg.metadata() != null
                                                    ? effectiveMsg.metadata() : Map.of(),
                                            List.of()));
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            });
                        }
                        return publishFuture.thenRun(() -> {
                            // 对标 Python _dispatch: turn_completed 事件（仅非延续时发布）
                            if (!TurnContinuation.isInternalContinuationPending(
                                    effectiveMsg.metadata())) {
                                runtimeEvents.turnCompleted(
                                        effectiveMsg.channel(), effectiveMsg.chatId(),
                                        sessionKey, effectiveMsg.metadata());
                            }
                        });
                    })
                    .exceptionally(ex -> {
                        Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
                        if (cause instanceof CancellationException) {
                            log.info("Task cancelled for session {}", sessionKey);
                            try {
                                Session session = sessionManager.getOrCreate(sessionKey);
                                // 对标 Python _dispatch: 取消时恢复 checkpoint
                                restoreRuntimeCheckpoint(session);
                                sessionManager.save(session);
                            } catch (Exception e) { /* ignore */ }
                            // 对标 Python _dispatch: 取消时发布 turn_completed + idle 事件（仅非延续时）
                            if (!TurnContinuation.isInternalContinuationPending(
                                    effectiveMsg.metadata())) {
                                runtimeEvents.turnCompleted(
                                        effectiveMsg.channel(), effectiveMsg.chatId(),
                                        sessionKey, effectiveMsg.metadata());
                            }
                            runtimeEvents.runStatusChanged(
                                    effectiveMsg, sessionKey, "idle", null);
                            runtimeEvents.clearTurn(sessionKey);
                            throw new CompletionException(cause);
                        }
                        log.error("Error processing message for session {}", sessionKey, cause);
                        // 对标 Python _dispatch: 发布错误出站消息
                        try {
                            bus.publishOutbound(new OutboundMessage(
                                    effectiveMsg.channel(), effectiveMsg.chatId(),
                                    "Sorry, I encountered an error.",
                                    null, null, Map.of(), List.of()));
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        // 对标 Python _dispatch: 错误时发布 turn_completed + idle 事件（仅非延续时）
                        if (!TurnContinuation.isInternalContinuationPending(
                                effectiveMsg.metadata())) {
                            runtimeEvents.turnCompleted(
                                    effectiveMsg.channel(), effectiveMsg.chatId(),
                                    sessionKey, effectiveMsg.metadata());
                        }
                        runtimeEvents.runStatusChanged(
                                effectiveMsg, sessionKey, "idle", null);
                        runtimeEvents.clearTurn(sessionKey);
                        return null;
                    })
                    .thenCompose(v2 -> {
                        // 排空 pending 队列
                        BlockingQueue<InboundMessage> queue = pendingQueues.remove(sessionKey);
                        if (queue != null && !queue.isEmpty()) {
                            List<InboundMessage> leftover = new ArrayList<>();
                            queue.drainTo(leftover);
                            CompletableFuture<Void> drainFuture = CompletableFuture.completedFuture(null);
                            for (InboundMessage item : leftover) {
                                InboundMessage captured = item;
                                drainFuture = drainFuture.thenCompose(v3 -> CompletableFuture.runAsync(() -> {
                                    try {
                                        bus.publishInbound(captured);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                }));
                            }
                            return drainFuture;
                        }
                        return CompletableFuture.completedFuture(null);
                    });
        }).whenComplete((v, ex) -> {
            lock.unlock();
            if (concurrencyGate != null) concurrencyGate.release();
            // 对标 Python _dispatch finally: run_status_changed("idle") + clear_turn
            // 仅在异常路径未触发时补发（成功路径已在 thenCompose 中处理）
            if (ex == null && v == null) {
                // This is the outer finally for non-pending-queue case — already handled above
            }
        });
    }

    // ==================== processMessage 状态机 ====================

    /**
     * 处理单条消息——创建 TurnContext 并驱动状态机。
     * 对标 Python {@code _process_message()}。
     *
     * @param inboundMsg   入站消息
     * @param onStream     流式回调
     * @param onStreamEnd  流式结束回调
     * @param pendingQueue pending 消息队列
     * @return 出站响应（或 null）
     */
    public CompletableFuture<OutboundMessage> processMessage(
            InboundMessage inboundMsg,
            @Nullable Function<String, CompletableFuture<Void>> onStream,
            @Nullable Function<Boolean, CompletableFuture<Void>> onStreamEnd,
            @Nullable BlockingQueue<InboundMessage> pendingQueue) {

        // 对标 Python _process_message: 刷新 provider 快照
        refreshProviderSnapshot();

        // 对标 Python _process_message: system channel 消息走专用处理
        if ("system".equals(inboundMsg.channel())) {
            return processSystemMessage(inboundMsg, null, onStream, onStreamEnd, pendingQueue);
        }

        String sessionKey = resolveSessionKey(inboundMsg);

        // 对标 Python _process_system_message: 持久化子 agent follow-up 到 session
        boolean isSubagent = "subagent".equals(inboundMsg.senderId());
        if (isSubagent) {
            Session session = sessionManager.getOrCreate(sessionKey);
            if (persistSubagentFollowup(session, inboundMsg)) {
                log.debug("Subagent result persisted for session {}", sessionKey);
                sessionManager.save(session);
            }
        }

        TurnContext turn = new TurnContext(
                inboundMsg, sessionKey, TurnState.RESTORE,
                sessionKey + ":" + System.nanoTime(),
                System.currentTimeMillis());
        // 对标 Python _process_message: visible_run_started_at 从延续元数据中提取
        Double runStartedAt = TurnContinuation.internalContinuationRunStartedAt(
                inboundMsg.metadata());
        if (runStartedAt != null) {
            turn.setVisibleRunStartedAt((long) (runStartedAt * 1000.0));
        }
        turn.setOnStream(onStream);
        turn.setOnStreamEnd(onStreamEnd);
        if (pendingQueue != null) turn.setPendingQueue(pendingQueue);

        return processTurn(turn);
    }

    /**
     * 状态机驱动——根据当前状态调用对应 handler，收集事件，转换到下一状态。
     * 对标 Python {@code _process_turn()}。
     */
    private CompletableFuture<OutboundMessage> processTurn(TurnContext turn) {
        if (turn.getState() == TurnState.DONE) {
            return CompletableFuture.completedFuture(turn.getOutbound());
        }

        TurnState state = turn.getState();
        CompletableFuture<String> eventFuture;
        long t0 = System.nanoTime();

        switch (state) {
            case RESTORE:  eventFuture = stateRestore(turn);  break;
            case COMPACT:  eventFuture = stateCompact(turn);  break;
            case COMMAND:  eventFuture = stateCommand(turn);  break;
            case BUILD:    eventFuture = stateBuild(turn);    break;
            case RUN:      eventFuture = stateRun(turn);      break;
            case SAVE:     eventFuture = stateSave(turn);     break;
            case RESPOND:  eventFuture = stateRespond(turn);  break;
            default:
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Missing state handler for " + state));
        }

        return eventFuture.thenCompose(event -> {
            long durationMs = (System.nanoTime() - t0) / 1_000_000;
            turn.getTrace().add(new StateTraceEntry(state, t0, durationMs, event));
            log.debug("[turn {}] State {} took {}ms -> event {}",
                    turn.getTurnId(), state, durationMs, event);

            TurnState nextState = TurnStateTransitions.next(state, event);
            turn.setState(nextState);
            return processTurn(turn);
        });
    }

    // ==================== 状态处理器 ====================

    /**
     * RESTORE 状态——加载 session，恢复 checkpoint 和 pending user turn。
     * 对标 Python {@code _state_restore()}。
     */
    private CompletableFuture<String> stateRestore(TurnContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            // 对标 Python _state_restore: 处理 media attachments
            InboundMessage msg = ctx.getMsg();
            if (msg.media() != null && !msg.media().isEmpty()) {
                ctx.setMsg(prepareMessageMedia(msg));
            }
            // 对标 Python _state_restore: 日志预览
            Object c = ctx.getMsg().content();
            String contentStr = c instanceof String s ? s : (c != null ? c.toString() : "");
            String preview = contentStr.length() > 80
                    ? contentStr.substring(0, 80) + "..." : contentStr;
            log.info("Processing message from {}:{}: {}",
                    ctx.getMsg().channel(), ctx.getMsg().senderId(), preview);
            if (ctx.getSession() == null) {
                ctx.setSession(sessionManager.getOrCreate(ctx.getSessionKey()));
            }
            return ctx.getSession();
        }).thenApply(session -> {
            // 对标 Python _state_restore: 发布 session_turn_started 事件
            runtimeEvents.sessionTurnStarted(ctx.getMsg(), ctx.getSessionKey());
            // 对标 Python: workspace_scopes.persist_message_scope
            persistMessageScope(session, ctx.getMsg());
            // 对标 Python: 恢复 checkpoint / pending user turn
            if (restoreRuntimeCheckpoint(session)) {
                sessionManager.save(session);
            }
            if (restorePendingUserTurn(session)) {
                sessionManager.save(session);
            }
            return "ok";
        });
    }

    /**
     * COMPACT 状态——触发 consolidation 和 auto-compact 准备。
     * 对标 Python {@code _state_compact()}。
     */
    private CompletableFuture<String> stateCompact(TurnContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            Map.Entry<Session, String> prepared = autoCompact.prepareSession(
                    ctx.getSession(), ctx.getSessionKey());
            ctx.setSession(prepared.getKey());
            ctx.setPendingSummary(prepared.getValue());
            return "ok";
        });
    }

    /**
     * COMMAND 状态——检查命令前缀并分发，匹配时 shortcut 跳过 BUILD/RUN。
     * 对标 Python {@code _state_command()}。
     */
    private CompletableFuture<String> stateCommand(TurnContext ctx) {
        if (commandRouter == null) return CompletableFuture.completedFuture("dispatch");
        String raw = ctx.getMsg().content() != null ? ctx.getMsg().content().strip() : "";
        CommandContext cmdCtx = new CommandContext(
                ctx.getMsg(), ctx.getSession(), ctx.getSessionKey(), raw, this);
        return commandRouter.dispatch(cmdCtx)
                .thenApply(result -> {
                    if (result != null) {
                        ctx.setOutbound(result);
                        // shortcut 命令需提前持久化用户消息
                        if (!"/new".equalsIgnoreCase(raw)) {
                            // 对标 Python: shortcut 命令持久化带 _command 标记
                            ctx.setUserPersistedEarly(
                                    persistUserMessageEarly(ctx.getMsg(), ctx.getSession(),
                                            Map.of("_command", true)));
                            ctx.getSession().addMessage("assistant", result.content());
                            sessionManager.save(ctx.getSession());
                            TurnContinuation.clearInternalContinuationState(
                                    ctx.getSession().getMetadata());
                        }
                        return "shortcut";
                    }
                    return "dispatch";
                });
    }

    /**
     * BUILD 状态——设置工具上下文、获取历史、构建初始 messages。
     * 对标 Python {@code _state_build()}。
     */
    private CompletableFuture<String> stateBuild(TurnContext ctx) {
        // 对标 Python _state_build: 先触发 consolidation（非 ephemeral）
        return consolidator.maybeConsolidateByTokens(ctx.getSession(), maxMessages)
                .thenCompose(v -> CompletableFuture.supplyAsync(() -> {
                    // 对标 Python _state_build: 重置 MessageTool per-turn 追踪
                    Tool messageTool = toolRegistry.get("message");
                    if (messageTool instanceof com.nanobot.agent.tools.impl.MessageTool mt) {
                        mt.startTurn();
                    }
                    // 对标 Python _state_build: 设置工具上下文（RequestContext）
                    setToolContext(ctx.getMsg().channel(), ctx.getMsg().chatId(),
                            ctx.getMsg().metadata() != null
                                    ? (String) ctx.getMsg().metadata().get("message_id") : null,
                            ctx.getMsg().metadata(), ctx.getSessionKey());
                    ctx.setHistory(ctx.getSession().getHistory(
                            maxMessages, replayTokenBudget(), true));
                    // 对标 Python _state_build: 记录 turn runtime
                    runtimeEvents.recordTurnRuntime(ctx.getSessionKey(), llmRuntime());
                    // 对标 Python: 用 image_generation_prompt 装饰当前消息（WebUI 图片生成模式）
                    InboundMessage decoratedMsg = decorateWithImageGen(ctx.getMsg());
                    List<Map<String, Object>> messages = contextBuilder.buildMessages(
                            ctx.getHistory(), decoratedMsg, ctx.getSession(),
                            ctx.getPendingSummary(), !ctx.isEphemeral());
                    ctx.setInitialMessages(Collections.unmodifiableList(messages));

                    ctx.setUserPersistedEarly(
                            persistUserMessageEarly(ctx.getMsg(), ctx.getSession()));
                    return "ok";
                }));
    }

    /**
     * RUN 状态——调用 AgentRunner 执行 LLM 对话循环。
     * 对标 Python {@code _state_run()}。
     */
    private CompletableFuture<String> stateRun(TurnContext ctx) {
        if (ctx.getVisibleRunStartedAt() == null) {
            ctx.setVisibleRunStartedAt(System.currentTimeMillis());
        }
        // 对标 Python _state_run: 发布 run_status_changed("running") 事件
        runtimeEvents.runStatusChanged(ctx.getMsg(), ctx.getSessionKey(), "running",
                ctx.getVisibleRunStartedAt() != null
                        ? ctx.getVisibleRunStartedAt() / 1000.0 : null);
        return runAgentLoop(ctx)
                .thenApply(result -> {
                    // 对标 Python _run_agent_loop: 跟踪用量和迭代计数
                    if (result.usage() != null) {
                        this.lastUsage = result.usage();
                    }
                    ctx.setFinalContent(result.finalContent());
                    ctx.setAllMessages(result.messages());
                    ctx.setToolsUsed(result.toolsUsed());
                    ctx.setStopReason(result.stopReason());
                    ctx.setHadInjections(result.hadInjections());
                    // 对标 Python _state_run: 尝试注入内部延续
                    TurnContinuation.maybeContinueTurn(ctx);
                    return "ok";
                });
    }

    /**
     * 构建 AgentRunSpec 并调用 runner.run()。
     * 对标 Python {@code _run_agent_loop()}。
     */
    private CompletableFuture<AgentRunResult> runAgentLoop(TurnContext ctx) {
        Function<String, CompletableFuture<Void>> onStream = ctx.getOnStream();
        Function<Boolean, CompletableFuture<Void>> onStreamEnd = ctx.getOnStreamEnd();
        Function<String, CompletableFuture<Void>> onRetryWait = ctx.getOnRetryWait();

        if (ctx.getOnProgress() == null) {
            ctx.setOnProgress(buildBusProgressCallback(ctx.getMsg()));
        }
        if (onRetryWait == null) {
            onRetryWait = buildRetryWaitCallback(ctx.getMsg());
            ctx.setOnRetryWait(onRetryWait);
        }

        Map<String, Object> sessionMeta = ctx.getSession() != null
                ? ctx.getSession().getMetadata() : null;

        // checkpoint callback
        // 对标 Python _checkpoint closure
        Function<Map<String, Object>, CompletableFuture<Void>> checkpointCallback = payload -> {
            if (ctx.getSession() != null) {
                ctx.getSession().getMetadata().put("runtime_checkpoint", payload);
                sessionManager.save(ctx.getSession());
            }
            return CompletableFuture.completedFuture(null);
        };

        // 对标 Python _drain_pending: 注入回调——从 pending queue 取消息并格式化为 user message
        // 包括阻塞等待子 agent 结果的能力（最多 300s）
        // 对标 Python loop.py:716-765 _drain_pending()
        Function<Integer, CompletableFuture<List<Map<String, Object>>>> injectionCallback = limit -> {
            java.util.concurrent.BlockingQueue<InboundMessage> queue =
                    (java.util.concurrent.BlockingQueue<InboundMessage>) ctx.getPendingQueue();
            List<Map<String, Object>> injected = new ArrayList<>();
            if (queue == null) return CompletableFuture.completedFuture(injected);
            int count = 0;
            // 对标 Python: 非阻塞排空已有消息
            while (count < limit) {
                InboundMessage m = queue.poll();
                if (m == null) break;
                injected.add(toUserMessage(m));
                count++;
            }
            // 对标 Python loop.py:744-763: 若无消息但子 agent 仍在运行，阻塞等待至少一个结果
            if (injected.isEmpty()
                    && ctx.getSession() != null
                    && subagentManager.getRunningCountBySession(
                            ctx.getSession().getKey()) > 0) {
                try {
                    InboundMessage m = queue.poll(300, java.util.concurrent.TimeUnit.SECONDS);
                    if (m != null) {
                        injected.add(toUserMessage(m));
                        // 排空剩余可立即取到的消息
                        while (injected.size() < limit) {
                            InboundMessage extra = queue.poll();
                            if (extra == null) break;
                            injected.add(toUserMessage(extra));
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Timeout waiting for sub-agent completion in session {}",
                            ctx.getSession().getKey());
                }
            }
            return CompletableFuture.completedFuture(injected);
        };

        AgentHook hook = buildHooks(ctx);

        // 对标 Python _run_agent_loop: 动态构建 goal continue prompt
        // 对标 Python loop.py:785-791
        List<String> goalLines = GoalState.goalStateRuntimeLines(sessionMeta);
        String goalContinue;
        if (!goalLines.isEmpty()) {
            goalContinue = "You have an active sustained goal:\n\n"
                    + String.join("\n", goalLines)
                    + "\n\nPlease continue working toward the objective using your tools, "
                    + "or call complete_goal if the work is truly finished.";
        } else {
            goalContinue = SUSTAINED_GOAL_CONTINUE_PROMPT;
        }

        AgentRunSpec spec = AgentRunSpec.builder()
                .initialMessages(ctx.getInitialMessages())
                .tools(ctx.getTools() != null ? ctx.getTools() : toolRegistry)
                .model(model)
                .maxIterations(maxIterations)
                .maxToolResultChars(maxToolResultChars)
                .temperature(null)
                .maxTokens(null)
                .providerRetryMode(providerRetryMode)
                .hook(hook)
                .errorMessage("Sorry, I encountered an error calling the AI model.")
                .concurrentTools(true)
                .workspace(ctx.getSession() != null
                        ? Path.of(ctx.getSession().getKey().split(":")[0]) : Path.of("."))
                .sessionKey(ctx.getSessionKey())
                .contextWindowTokens(contextWindowTokens)
                .contextBlockLimit(contextBlockLimit)
                .progressCallback(ctx.getOnProgress())
                .streamProgressDeltas(onStream != null)
                .retryWaitCallback(onRetryWait)
                .checkpointCallback(checkpointCallback)
                .injectionCallback(injectionCallback)
                .llmTimeoutS(runnerWallLlmTimeoutS(ctx.getSessionKey(),
                        sessionMeta != null ? sessionMeta : Map.of(),
                        ctx.getMsg().metadata()))
                .goalActivePredicate(v -> GoalState.sustainedGoalActive(sessionMeta))
                .goalContinueMessage(goalContinue)
                .finalizeOnMaxIterations(TurnContinuation.shouldFinalizeOnMaxIterations(
                        ctx.getPendingQueue() != null && ctx.getSession() != null,
                        sessionMeta,
                        ctx.getMsg().metadata()))
                .build();

        // 对标 Python _run_agent_loop: 绑定 RequestContext + FileStates + WorkspaceScope
        String activeSessionKey = ctx.getSession() != null
                ? ctx.getSession().getKey() : ctx.getSessionKey();
        RequestContext reqCtx = new RequestContext(
                ctx.getMsg().channel(), ctx.getMsg().chatId(),
                ctx.getMsg().metadata() != null
                        ? (String) ctx.getMsg().metadata().get("message_id") : null,
                activeSessionKey,
                ctx.getMsg().metadata() != null
                        ? Collections.unmodifiableMap(ctx.getMsg().metadata()) : Map.of());
        // 对标 Python: bind_file_states / bind_request_context / bind_workspace_scope
        RequestContext previousReq = RequestContext.bind(reqCtx);
        FileStates fileStates = fileStateStore.forSession(activeSessionKey);

        // 对标 Python _run_agent_loop: 同步子 agent 运行时限制
        syncSubagentRuntimeLimits();

        return runner.run(spec)
                .thenApply(result -> {
                    // 对标 Python _run_agent_loop: 跟踪用量
                    if (result.usage() != null) {
                        this.lastUsage = result.usage();
                    }
                    // 对标 Python loop.py:834-846: 预算耗尽时流式输出最终内容
                    if ("max_iterations".equals(result.stopReason())) {
                        log.warn("Max iterations ({}) reached", maxIterations);
                        boolean shouldStream = TurnContinuation.shouldStreamBudgetResponse(
                                result.stopReason(),
                                ctx.getPendingQueue() != null && ctx.getSession() != null,
                                sessionMeta,
                                ctx.getMsg().metadata());
                        if (onStream != null && onStreamEnd != null && shouldStream) {
                            try {
                                onStream.apply(result.finalContent() != null
                                        ? result.finalContent() : "").join();
                                onStreamEnd.apply(false).join();
                            } catch (Exception e) {
                                log.warn("Budget stream output failed", e);
                            }
                        }
                    } else if ("error".equals(result.stopReason())) {
                        log.error("LLM returned error: {}",
                                result.finalContent() != null
                                        ? result.finalContent().substring(0,
                                                Math.min(200, result.finalContent().length()))
                                        : "");
                    }
                    return result;
                })
                .whenComplete((result, ex) -> {
                    // 对标 Python _run_agent_loop finally: 解绑/恢复上下文
                    if (previousReq != null) {
                        RequestContext.bind(previousReq);
                    } else {
                        RequestContext.unbind();
                    }
                });
    }

    /**
     * 将 pending 队列中的消息格式化为 LLM user message。
     * 对标 Python _drain_pending 中的 {@code _to_user_message} 闭包。
     *
     * @param m pending 入站消息
     * @return {"role": "user", "content": ...} 格式的 Map
     */
    // 对标 Python loop.py:728-735 _to_user_message()
    private Map<String, Object> toUserMessage(InboundMessage m) {
        // 对标 Python: 通过 _prepare_message_media 处理 content + media
        InboundMessage prepared = prepareMessageMedia(m);
        String content = prepared.content() instanceof String s ? s : "";
        List<String> media = prepared.media() != null && !prepared.media().isEmpty()
                ? prepared.media() : null;
        // 对标 Python: 通过 _build_user_content 构建用户消息（含 base64 图片编码）
        Object userContent = contextBuilder.buildUserContent(content, media);
        return Map.of("role", "user", "content",
                userContent != null ? userContent : "");
    }

    /**
     * 同步子 agent 运行时限制。
     * 对标 Python {@code _sync_subagent_runtime_limits()}。
     */
    private void syncSubagentRuntimeLimits() {
        // 对标 Python: 保持 subagent maxIterations 与当前循环一致
        // Java 中 SubagentManager 在构造时已设定 maxIterations，
        // 若 maxIterations 可变，此处需更新；目前该值构造后不变。
    }

    /**
     * SAVE 状态——持久化 turn 结果和延迟信息。
     * 对标 Python {@code _state_save()}。
     */
    private CompletableFuture<String> stateSave(TurnContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            // 对标 Python _state_save: 准备保存边界（清理持续目标状态）
            TurnContinuation.prepareSaveBoundary(ctx);

            if ((ctx.getFinalContent() == null || ctx.getFinalContent().isBlank())
                    && !ctx.isSuppressResponse()) {
                ctx.setFinalContent(EMPTY_FINAL_RESPONSE_MESSAGE);
            }
            // 对标 Python _state_save: 内部延续 turn 使用 visible_run_started_at
            long latencyStart = TurnContinuation.isInternalContinuationInbound(
                    ctx.getMsg().metadata())
                    && ctx.getVisibleRunStartedAt() != null
                    ? ctx.getVisibleRunStartedAt()
                    : ctx.getTurnWallStartedAt();
            long latencyMs = Math.max(0, System.currentTimeMillis() - latencyStart);
            ctx.setTurnLatencyMs((int) latencyMs);
            saveTurn(ctx.getSession(), ctx.getAllMessages(), ctx.getSaveSkip(), latencyMs);
            // 对标 Python _state_save: 记录 turn 延迟
            runtimeEvents.recordTurnLatency(ctx.getSessionKey(), (int) latencyMs);
            // 对标 Python _state_save: enforce_file_cap + maybe_consolidate（仅非 ephemeral）
            if (!ctx.isEphemeral()) {
                ctx.getSession().enforceFileCap(
                        msgs -> memoryStore.rawArchive(msgs, null, ctx.getSessionKey()),
                        SessionConstants.FILE_MAX_MESSAGES);
                scheduleBackground(consolidator.maybeConsolidateByTokens(
                        ctx.getSession(), maxMessages));
            }
            // 对标 Python _state_save: 清理 pending user turn / runtime checkpoint
            TurnContinuation.clearInternalContinuationState(
                    ctx.getSession().getMetadata());
            sessionManager.save(ctx.getSession());
            return "ok";
        });
    }

    /**
     * RESPOND 状态——组装出站响应。
     * 对标 Python {@code _state_respond()}。
     */
    /**
     * RESPOND 状态——组装出站响应。
     * 对标 Python {@code _state_respond()}。
     */
    private CompletableFuture<String> stateRespond(TurnContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            if (ctx.isSuppressResponse()) {
                ctx.setOutbound(null);
                return "ok";
            }
            OutboundMessage outbound = assembleOutbound(ctx.getMsg(),
                    ctx.getFinalContent(), ctx.getAllMessages(),
                    ctx.getStopReason(), ctx.isHadInjections(),
                    ctx.getOnStream(), ctx.getTurnLatencyMs());
            // 对标 Python: ephemeral 时附加 _stop_reason 到 metadata
            if (ctx.isEphemeral() && outbound != null) {
                outbound.metadata().put("_stop_reason", ctx.getStopReason());
            }
            ctx.setOutbound(outbound);
            return "ok";
        });
    }

    // ==================== helpers ====================

    /** 计算有效的 session key。 */
    private String effectiveSessionKey(InboundMessage msg) {
        if (unifiedSession && msg.sessionKeyOverride() == null) {
            return UNIFIED_SESSION_KEY;
        }
        return msg.sessionKey();
    }

    /** 解析 session key。 */
    private String resolveSessionKey(InboundMessage msg) {
        return msg.sessionKey();
    }

    /** 构建 bus progress 回调。 */
    private BiFunction<String, Map<String, Object>, CompletableFuture<Void>> buildBusProgressCallback(
            InboundMessage msg) {
        return (content, meta) -> CompletableFuture.runAsync(() -> {
            try {
                bus.publishOutbound(new OutboundMessage(
                        msg.channel(), msg.chatId(), content, null, null,
                        meta != null ? meta : Map.of(), List.of()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** 构建 retry wait 回调。 */
    private Function<String, CompletableFuture<Void>> buildRetryWaitCallback(InboundMessage msg) {
        return content -> CompletableFuture.runAsync(() -> {
            try {
                bus.publishOutbound(new OutboundMessage(
                        msg.channel(), msg.chatId(), content, null, null,
                        Map.of("_retry_wait", true), List.of()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * 构建 hook 链——创建 AgentProgressHook 作为主 hook，与非 ephemeral extraHooks 组合。
     * 对标 Python loop.py:694-709 run_agent_loop 中 hook 组装。
     */
    private AgentHook buildHooks(TurnContext ctx) {
        InboundMessage msg = ctx.getOriginalMsg();
        // 对标 Python: AgentProgressHook(on_progress, on_stream, on_stream_end, channel, chat_id, ...)
        AgentProgressHook progressHook = new AgentProgressHook(
                ctx.getOnProgress(),
                ctx.getOnStream(),
                ctx.getOnStreamEnd(),
                msg.channel(),
                msg.chatId(),
                msg.metadata() != null ? (String) msg.metadata().get("message_id") : null,
                msg.metadata(),
                ctx.getSessionKey(),
                toolHintMaxLength,
                null,   // set_tool_context — Java 中通过 RequestContext 管理，暂不注入
                iteration -> { this.currentIteration = iteration; }
        );
        // 对标 Python: 非 ephemeral 且 extra_hooks 非空时组合为 CompositeHook
        if (!ctx.isEphemeral() && !extraHooks.isEmpty()) {
            List<AgentHook> hooks = new ArrayList<>();
            hooks.add(progressHook);
            hooks.addAll(extraHooks);
            return new CompositeHook(hooks);
        }
        return progressHook;
    }

    /** 计算 replay token budget。 */
    private int replayTokenBudget() {
        if (contextWindowTokens <= 0) return 0;
        int maxOutput = 4096;
        try { maxOutput = provider.getGeneration().maxTokens(); } catch (Exception ignored) {}
        int budget = contextWindowTokens - Math.max(1, maxOutput) - 1024;
        return budget > 0 ? budget : Math.max(128, contextWindowTokens / 2);
    }

    /**
     * 提前持久化用户消息（turn 开始前），对标 Python _persist_user_message_early()。
     *
     * @param msg     入站消息
     * @param session 当前会话
     * @param kwargs  额外标记（如 _command）
     * @return 是否成功持久化
     */
    // 对标 Python loop.py:568-590 _persist_user_message_early()
    @SuppressWarnings("unchecked")
    private boolean persistUserMessageEarly(InboundMessage msg, Session session,
                                            Map<String, Object>... kwargs) {
        if (session == null) return false;
        if (!TurnContinuation.shouldPersistUserMessage(msg.metadata())) return false;

        // 对标 Python: 收集 media paths
        List<String> media = msg.media();
        List<String> mediaPaths = new ArrayList<>();
        if (media != null) {
            for (String p : media) {
                if (p != null && !p.isEmpty()) mediaPaths.add(p);
            }
        }
        String text = msg.content() instanceof String s ? s : "";
        boolean hasText = text != null && !text.strip().isEmpty();
        if (!hasText && mediaPaths.isEmpty()) return false;

        // 对标 Python: 构建 extra dict（media + session_extra + kwargs）
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("message_id", msg.metadata() != null
                ? msg.metadata().getOrDefault("message_id", "") : "");
        if (!mediaPaths.isEmpty()) {
            extra.put("media", List.copyOf(mediaPaths));
        }
        // 对标 Python: extra = (...) | agent_context.session_extra(msg.metadata)
        extra.putAll(sessionExtra(msg.metadata()));
        // 对标 Python: extra.update(kwargs)
        for (Map<String, Object> kw : kwargs) {
            if (kw != null) extra.putAll(kw);
        }
        session.addMessage("user", text, extra);
        // 对标 Python: 标记 pending user turn，crash recovery 可关闭该 turn
        markPendingUserTurn(session);
        sessionManager.save(session);
        return true;
    }

    /**
     * 返回持久化会话时附加的 capabilities 元数据。
     * 对标 Python {@code agent_context.session_extra(metadata)}。
     *
     * @param metadata 消息元数据
     * @return 附加的会话持久化字段
     */
    // 对标 Python context.py:25-27 session_extra()
    private static Map<String, Object> sessionExtra(@Nullable Map<String, Object> metadata) {
        // 对标 Python: cli_app_utils.session_extra | mcp_tools.session_extra
        // Java 存根：当前无 CLI app / MCP tools 的 session_extra 实现
        return Map.of();
    }

    /**
     * 保存 turn 结果到 session，含截断、清理、空 assistant 过滤。
     * 对标 Python _save_turn() + _sanitize_persisted_blocks()。
     */
    @SuppressWarnings("unchecked")
    private void saveTurn(Session session, List<Map<String, Object>> messages,
                          int saveSkip, long latencyMs) {
        if (session == null || messages == null) return;
        int skip = Math.min(saveSkip, messages.size());
        Integer lastAssistantIdx = null;
        for (int i = skip; i < messages.size(); i++) {
            Map<String, Object> msg = new LinkedHashMap<>(messages.get(i));
            String role = (String) msg.get("role");
            Object content = msg.get("content");
            // 对标 Python: 跳过无 content 且无 tool_calls 的 assistant 消息
            if ("assistant".equals(role)
                    && (content == null || "".equals(content))
                    && !msg.containsKey("tool_calls")) {
                continue;
            }
            if ("tool".equals(role)) {
                if (content instanceof String s && s.length() > maxToolResultChars) {
                    msg.put("content", truncateText(s, maxToolResultChars));
                } else if (content instanceof List<?> blocks) {
                    List<Map<String, Object>> sanitized = sanitizePersistedBlocks(
                            (List<Map<String, Object>>) blocks, true, false);
                    if (sanitized.isEmpty()) continue;
                    msg.put("content", sanitized);
                }
            } else if ("user".equals(role)) {
                if (content instanceof String s) {
                    String cleaned = stripRuntimeContext(s);
                    if (cleaned == null || cleaned.isBlank()) continue;
                    msg.put("content", cleaned);
                } else if (content instanceof List<?> blocks) {
                    List<Map<String, Object>> sanitized = sanitizePersistedBlocks(
                            (List<Map<String, Object>>) blocks, false, true);
                    if (sanitized.isEmpty()) continue;
                    msg.put("content", sanitized);
                }
            }
            msg.putIfAbsent("timestamp", Instant.now().toString());
            session.getMessages().add(msg);
            if ("assistant".equals(role)) {
                lastAssistantIdx = session.getMessages().size() - 1;
            }
        }
        // 对标 Python: 将 latency_ms 注入最后一个 assistant 消息
        if (latencyMs > 0 && lastAssistantIdx != null) {
            session.getMessages().get(lastAssistantIdx).put("latency_ms", (int) latencyMs);
        }
        session.setUpdatedAt(Instant.now());
    }

    /**
     * 清理 volatile multimodal payloads，对标 Python _sanitize_persisted_blocks()。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sanitizePersistedBlocks(
            List<Map<String, Object>> blocks, boolean shouldTruncateText, boolean dropRuntime) {
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> block : blocks) {
            if (!(block instanceof Map)) {
                filtered.add(block);
                continue;
            }
            // 对标 Python: 丢弃 runtime context 块
            if (dropRuntime && "text".equals(block.get("type"))
                    && block.get("text") instanceof String t
                    && t.startsWith("[Runtime Context")) {
                continue;
            }
            // 对标 Python: 将 data:image/ URL 替换为 placeholder
            if ("image_url".equals(block.get("type"))) {
                Object imgUrl = block.get("image_url");
                if (imgUrl instanceof Map<?, ?> img && img.get("url") instanceof String url
                        && url.startsWith("data:image/")) {
                    Map<String, Object> meta = (Map<String, Object>) block.get("_meta");
                    String path = meta != null ? (String) meta.get("path") : null;
                    // 对标 Python: image_placeholder_text(path)
                    filtered.add(Map.of("type", "text", "text",
                            imagePlaceholderText(path, "[image]")));
                    continue;
                }
            }
            // 对标 Python: 截断过长的文本块
            if (shouldTruncateText && "text".equals(block.get("type"))
                    && block.get("text") instanceof String t
                    && t.length() > maxToolResultChars) {
                Map<String, Object> copy = new LinkedHashMap<>(block);
                copy.put("text", truncateText(t, maxToolResultChars));
                filtered.add(copy);
                continue;
            }
            filtered.add(block);
        }
        return filtered;
    }

    /**
     * 去除 content 中 runtime context 块。
     * 对标 Python _save_turn 中 user content 处理。
     */
    private String stripRuntimeContext(String content) {
        if (content == null) return null;
        int pos = content.indexOf("[Runtime Context");
        if (pos < 0) return content;
        return content.substring(0, pos).stripTrailing();
    }

    /**
     * 构建图片占位符文本，对标 Python image_placeholder_text()。
     *
     * @param path  图片路径，为 null 时使用默认占位符
     * @param empty path 为 null 时的默认占位符
     * @return 格式化后的占位符文本
     */
    // 对标 Python helpers.py:228-230 image_placeholder_text()
    static String imagePlaceholderText(@Nullable String path, String empty) {
        return path != null ? "[image: " + path + "]" : empty;
    }

    /**
     * 装饰用户 prompt——当 WebUI 图片生成模式启用时追加指令。
     * 对标 Python image_generation_intent.py:10-27 image_generation_prompt()。
     *
     * @param content  原始消息文本
     * @param metadata 消息元数据
     * @return 装饰后的消息文本
     */
    // 对标 Python image_generation_intent.py image_generation_prompt()
    private static String imageGenerationPrompt(String content, @Nullable Map<String, Object> metadata) {
        if (metadata == null) return content;
        Object raw = metadata.get("image_generation");
        if (!(raw instanceof Map<?, ?> ig) || !Boolean.TRUE.equals(ig.get("enabled"))) {
            return content;
        }
        Object ar = ig.get("aspect_ratio");
        String instruction;
        if (ar instanceof String s && !s.strip().isEmpty()) {
            instruction = "The user selected WebUI image generation mode. Use the generate_image tool. "
                    + "When calling generate_image, pass aspect_ratio=" + s + ".";
        } else {
            instruction = "The user selected WebUI image generation mode. Use the generate_image tool. "
                    + "Choose the most suitable aspect_ratio yourself from the prompt and intended use.";
        }
        return content + "\n\n[WebUI image generation instruction: " + instruction + "]";
    }

    /**
     * 用 image_generation_prompt 装饰 InboundMessage 的 content。
     * 对标 Python loop.py:604 image_generation_prompt(msg.content, msg.metadata)。
     */
    private InboundMessage decorateWithImageGen(InboundMessage msg) {
        String original = msg.content() instanceof String s ? s : "";
        String decorated = imageGenerationPrompt(original, msg.metadata());
        if (decorated.equals(original)) return msg;
        return new InboundMessage(msg.channel(), msg.senderId(), msg.chatId(),
                decorated, msg.timestamp(), msg.media(), msg.metadata(),
                msg.sessionKeyOverride());
    }

    /** 截断文本，对标 Python truncate_text()。 */
    private static String truncateText(String text, int maxChars) {
        if (maxChars <= 0 || text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n... (truncated)";
    }

    /**
     * 组装出站响应（对标 Python _assemble_outbound）。
     *
     * @param onStream 流式回调，非 null 时标记 _streamed 元数据
     * @param turnLatencyMs turn 延迟，非 null 时注入 latency_ms 元数据
     */
    // 对标 Python loop.py:1294-1325 _assemble_outbound()
    private OutboundMessage assembleOutbound(InboundMessage msg, String content,
                                              List<Map<String, Object>> messages,
                                              String stopReason, boolean hadInjections,
                                              @Nullable Function<String, CompletableFuture<Void>> onStream,
                                              @Nullable Integer turnLatencyMs) {
        // 对标 Python: MessageTool 抑制——若本 turn 已通过 message 工具发送消息，
        // 且没有注入或 stop_reason 为 empty_final_response，则抑制出站响应。
        Tool messageTool = toolRegistry.get("message");
        if (messageTool instanceof com.nanobot.agent.tools.impl.MessageTool mt
                && mt.isSentInTurn()) {
            if (!hadInjections || "empty_final_response".equals(stopReason)) {
                return null;
            }
        }

        String preview = content != null && content.length() > 120
                ? content.substring(0, 120) + "..." : content;
        log.info("Response to {}:{}: {}", msg.channel(), msg.senderId(), preview);

        Map<String, Object> meta = new LinkedHashMap<>();
        if (msg.metadata() != null) meta.putAll(msg.metadata());
        // 对标 Python: 流式标记
        if (onStream != null && !"error".equals(stopReason) && !"tool_error".equals(stopReason)) {
            meta.put("_streamed", true);
        }
        if (turnLatencyMs != null) {
            meta.put("latency_ms", turnLatencyMs);
        }
        return new OutboundMessage(msg.channel(), msg.chatId(), content,
                null, null, meta, List.of());
    }

    /** 获取 LLM wall timeout（秒）。 */
    private double runnerWallLlmTimeoutS(String sessionKey,
                                           Map<String, Object> sessionMetadata,
                                           Map<String, Object> messageMetadata) {
        if (GoalState.sustainedGoalActive(sessionMetadata)) return 0.0;
        return 300.0;
    }

    /** 对标 Python _restore_runtime_checkpoint(session): 将未完成的 turn 物化到 session 历史。 */
    private boolean restoreRuntimeCheckpoint(Session session) {
        Object raw = session.getMetadata().get("runtime_checkpoint");
        if (!(raw instanceof Map<?, ?> checkpoint)) return false;
        @SuppressWarnings("unchecked")
        Map<String, Object> cp = (Map<String, Object>) checkpoint;
        List<Map<String, Object>> restored = new ArrayList<>();
        Object assistantMsg = cp.get("assistant_message");
        if (assistantMsg instanceof Map<?, ?> am) {
            Map<String, Object> m = new LinkedHashMap<>();
            @SuppressWarnings("unchecked")
            Map<String, Object> src = (Map<String, Object>) am;
            m.putAll(src);
            m.putIfAbsent("timestamp", Instant.now().toString());
            restored.add(m);
        }
        Object completed = cp.get("completed_tool_results");
        if (completed instanceof List<?> cl) {
            for (Object item : cl) {
                if (item instanceof Map<?, ?> tm) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> src = (Map<String, Object>) tm;
                    m.putAll(src);
                    m.putIfAbsent("timestamp", Instant.now().toString());
                    restored.add(m);
                }
            }
        }
        Object pending = cp.get("pending_tool_calls");
        if (pending instanceof List<?> pl) {
            for (Object item : pl) {
                if (item instanceof Map<?, ?> tc) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> tcm = (Map<String, Object>) tc;
                    Object toolId = tcm.get("id");
                    Object func = tcm.get("function");
                    String name = "tool";
                    if (func instanceof Map<?, ?> fm) {
                        name = Objects.toString(((Map<?, ?>) fm).get("name"), "tool");
                    }
                    restored.add(Map.of(
                            "role", "tool", "tool_call_id", Objects.toString(toolId, ""),
                            "name", name, "content",
                            "Error: Task interrupted before this tool finished.",
                            "timestamp", Instant.now().toString()));
                }
            }
        }
        // 对标 Python: 重叠检测——去重 session 末尾与 restored 开头的重复消息
        int overlap = 0;
        int maxOverlap = Math.min(session.getMessages().size(), restored.size());
        for (int size = maxOverlap; size > 0; size--) {
            List<Map<String, Object>> sessionTail = session.getMessages()
                    .subList(session.getMessages().size() - size, session.getMessages().size());
            List<Map<String, Object>> restoredHead = restored.subList(0, size);
            boolean match = true;
            for (int j = 0; j < size; j++) {
                if (!checkpointMessageKey(sessionTail.get(j))
                        .equals(checkpointMessageKey(restoredHead.get(j)))) {
                    match = false;
                    break;
                }
            }
            if (match) { overlap = size; break; }
        }
        if (overlap > 0 && overlap <= session.getMessages().size()) {
            // 以 restored 版本覆盖重叠尾部
            int start = session.getMessages().size() - overlap;
            while (session.getMessages().size() > start) {
                session.getMessages().remove(session.getMessages().size() - 1);
            }
            session.getMessages().addAll(restored.subList(0, overlap));
            restored = new ArrayList<>(restored.subList(overlap, restored.size()));
        }
        session.getMessages().addAll(restored);
        session.getMetadata().remove("runtime_checkpoint");
        // 对标 Python: 同时清理 pending_user_turn
        session.getMetadata().remove("pending_user_turn");
        return !restored.isEmpty() || overlap > 0
                || (raw instanceof Map<?, ?>);
    }

    /**
     * 校验消息 key，用于 checkpoint 去重。
     * 对标 Python _checkpoint_message_key() 静态方法。
     */
    private static List<Object> checkpointMessageKey(Map<String, Object> message) {
        return List.of(
                message.get("role"),
                message.get("content"),
                message.get("tool_call_id"),
                message.get("name"),
                message.get("tool_calls"),
                message.get("reasoning_content"),
                message.get("thinking_blocks"));
    }

    /**
     * 持久化子 agent 结果到 session，供后续 turn 复现。
     * 对标 Python _persist_subagent_followup(session, msg)。
     */
    private boolean persistSubagentFollowup(Session session, InboundMessage msg) {
        if (msg.content() == null || msg.content().isBlank()) return false;
        Object taskId = msg.metadata() != null
                ? msg.metadata().get("subagent_task_id") : null;
        if (taskId != null) {
            for (Map<String, Object> m : session.getMessages()) {
                if ("subagent_result".equals(m.get("injected_event"))
                        && taskId.equals(m.get("subagent_task_id"))) {
                    return false;
                }
            }
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("injected_event", "subagent_result");
        meta.put("sender_id", msg.senderId());
        if (taskId != null) meta.put("subagent_task_id", taskId);
        session.addMessage("assistant", msg.content(), meta);
        return true;
    }

    /**
     * 为所有 ContextAware 工具设置请求上下文（channel/chat_id/message_id/session_key）。
     * 对标 Python _set_tool_context()。
     */
    private void setToolContext(String channel, String chatId,
                                 @Nullable String messageId,
                                 @Nullable Map<String, Object> metadata,
                                 @Nullable String sessionKey) {
        String effectiveKey = sessionKey != null ? sessionKey
                : unifiedSession ? AgentLoop.UNIFIED_SESSION_KEY
                : channel + ":" + chatId;
        RequestContext reqCtx = new RequestContext(
                channel, chatId, messageId, effectiveKey,
                metadata != null ? Collections.unmodifiableMap(metadata) : Map.of());
        RequestContext.bind(reqCtx);
        // 同时通知所有 ContextAware 工具
        for (String name : toolRegistry.getToolNames()) {
            Tool tool = toolRegistry.get(name);
            if (tool instanceof com.nanobot.agent.tools.ContextAware ca) {
                ca.setContext(reqCtx);
            }
        }
    }

    /**
     * 处理 system channel 消息（如 subagent announce）。
     * 对标 Python _process_system_message()。
     */
    private CompletableFuture<OutboundMessage> processSystemMessage(
            InboundMessage msg,
            @Nullable String sessionKey,
            @Nullable Function<String, CompletableFuture<Void>> onStream,
            @Nullable Function<Boolean, CompletableFuture<Void>> onStreamEnd,
            @Nullable BlockingQueue<InboundMessage> pendingQueue) {
        return CompletableFuture.supplyAsync(() -> {
            String channel, chatId;
            String cid = msg.chatId();
            if (cid != null && cid.contains(":")) {
                int idx = cid.indexOf(":");
                channel = cid.substring(0, idx);
                chatId = cid.substring(idx + 1);
            } else {
                channel = "cli";
                chatId = cid != null ? cid : "system";
            }
            String key = msg.sessionKeyOverride() != null
                    ? msg.sessionKeyOverride() : channel + ":" + chatId;
            log.info("Processing system message from {}", msg.senderId());
            Session session = sessionManager.getOrCreate(key);
            if (restoreRuntimeCheckpoint(session)) sessionManager.save(session);
            if (restorePendingUserTurn(session)) sessionManager.save(session);

            Map.Entry<Session, String> prepared = autoCompact.prepareSession(session, key);
            session = prepared.getKey();
            String pending = prepared.getValue();

            // 触发 consolidation
            consolidator.maybeConsolidateByTokens(session, maxMessages).join();

            boolean isSubagent = "subagent".equals(msg.senderId());
            if (isSubagent && persistSubagentFollowup(session, msg)) {
                log.debug("Subagent result persisted for session {}", key);
                sessionManager.save(session);
            }
            setToolContext(channel, chatId,
                    msg.metadata() != null ? (String) msg.metadata().get("message_id") : null,
                    msg.metadata(), key);

            List<Map<String, Object>> history = session.getHistory(
                    maxMessages, replayTokenBudget(), true);

            // 构建 messages：对标 Python _process_system_message 的 build_messages 调用
            InboundMessage effectiveMsg = isSubagent
                    ? new InboundMessage(channel, msg.senderId(), chatId, "",
                            msg.timestamp(), msg.media(), msg.metadata(),
                            msg.sessionKeyOverride())
                    : msg;
            List<Map<String, Object>> messages = contextBuilder.buildMessages(
                    history, effectiveMsg, session, pending, true);

            // 运行 agent loop——对标 Python _process_system_message
            AgentRunSpec spec = AgentRunSpec.builder()
                    .initialMessages(messages)
                    .tools(toolRegistry)
                    .model(model)
                    .maxIterations(maxIterations)
                    .maxToolResultChars(maxToolResultChars)
                    .providerRetryMode(providerRetryMode)
                    .workspace(workspace)
                    .sessionKey(key)
                    .contextWindowTokens(contextWindowTokens)
                    .contextBlockLimit(contextBlockLimit)
                    .build();
            AgentRunner runner = new AgentRunner(provider);
            AgentRunResult result = runner.run(spec).join();

            long latencyMs = Math.max(0, System.currentTimeMillis()
                    - session.getUpdatedAt().toEpochMilli());
            // 用 system turn 的 latency 近似
            saveTurn(session, result.messages(), 1 + history.size(),
                    Math.max(0, (int) latencyMs));
            runtimeEvents.recordTurnLatency(key, (int) latencyMs);
            session.enforceFileCap(
                    msgs -> memoryStore.rawArchive(msgs, null, key),
                    SessionConstants.FILE_MAX_MESSAGES);
            clearRuntimeCheckpoint(session);
            sessionManager.save(session);
            scheduleBackground(consolidator.maybeConsolidateByTokens(
                    session, maxMessages));

            String content = result.finalContent() != null
                    ? result.finalContent() : "Background task completed.";
            Map<String, Object> outboundMeta = new LinkedHashMap<>();
            if (msg.metadata() != null) {
                Object originId = msg.metadata().get("origin_message_id");
                if (originId != null) outboundMeta.put("origin_message_id", originId);
            }
            return new OutboundMessage(channel, chatId, content,
                    null, null, outboundMeta, List.of());
        });
    }

    /**
     * 处理 media attachments：提取文档文本或引用非图片附件。
     * 对标 Python {@code _prepare_message_media()}。
     *
     * @param msg 原始入站消息
     * @return 经过文档提取/附件引用处理后的入站消息
     */
    // 对标 Python loop.py:1353-1356 _prepare_message_media()
    private InboundMessage prepareMessageMedia(InboundMessage msg) {
        List<String> media = msg.media();
        if (media == null || media.isEmpty()) return msg;
        String content = msg.content() instanceof String s ? s : "";
        DocumentUtil.ContentAndImages result;
        if (shouldExtractDocumentText()) {
            // 对标 Python: 提取文档文本内容
            result = DocumentUtil.extractDocuments(content, media);
        } else {
            // 对标 Python: 仅引用非图片附件路径
            result = DocumentUtil.referenceNonImageAttachments(content, media);
        }
        return new InboundMessage(msg.channel(), msg.senderId(), msg.chatId(),
                result.content(), msg.timestamp(),
                result.images(), msg.metadata(), msg.sessionKeyOverride());
    }

    /**
     * 持久化 message workspace scope 到 session metadata。
     * 对标 Python workspace_scopes.persist_message_scope()——存根实现。
     */
    private void persistMessageScope(Session session, InboundMessage msg) {
        // 存根：Python 将 workspace_scope 存储到 session metadata 供后续 turn 使用
        if (msg.metadata() != null && msg.metadata().containsKey("workspace_scope")) {
            session.getMetadata().put("_workspace_scope", msg.metadata().get("workspace_scope"));
        }
    }

    /**
     * 恢复因崩溃中断的 pending user turn——补充错误 assistant 消息。
     * 对标 Python _restore_pending_user_turn()。
     */
    private boolean restorePendingUserTurn(Session session) {
        if (session.getMetadata().get("pending_user_turn") == null) return false;
        List<Map<String, Object>> msgs = session.getMessages();
        if (!msgs.isEmpty() && "user".equals(msgs.get(msgs.size() - 1).get("role"))) {
            msgs.add(Map.of(
                    "role", "assistant",
                    "content", "Error: Task interrupted before a response was generated.",
                    "timestamp", Instant.now().toString()));
            session.setUpdatedAt(Instant.now());
        }
        session.getMetadata().remove("pending_user_turn");
        return true;
    }

    /** 标记 pending user turn。对标 Python _mark_pending_user_turn()。 */
    private void markPendingUserTurn(Session session) {
        session.getMetadata().put("pending_user_turn", true);
    }

    /** 清理 pending user turn。对标 Python _clear_pending_user_turn()。 */
    private void clearPendingUserTurn(Session session) {
        session.getMetadata().remove("pending_user_turn");
    }

    /** 清理 runtime checkpoint。对标 Python _clear_runtime_checkpoint()。 */
    private void clearRuntimeCheckpoint(Session session) {
        session.getMetadata().remove("runtime_checkpoint");
    }

    /** 内联命令分发。 */
    private CompletableFuture<Void> dispatchCommandInline(
            InboundMessage msg, String key, String raw,
            Function<CommandContext, CompletableFuture<OutboundMessage>> dispatchFn) {
        CommandContext ctx = new CommandContext(msg, null, key, raw, this);
        return dispatchFn.apply(ctx)
                .thenCompose(result -> {
                    if (result != null) {
                        return CompletableFuture.runAsync(() -> {
                            try {
                                bus.publishOutbound(result);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
                    }
                    return CompletableFuture.completedFuture(null);
                });
    }

    // ==================== getters/setters ====================

    public List<AgentHook> getExtraHooks() { return extraHooks; }
    public void setExtraHooks(List<AgentHook> hooks) { this.extraHooks = hooks; }

    /**
     * 直接处理消息（不经过 bus），与 dispatch 共享 session 锁保证串行。
     * 对标 Python process_direct()。
     */
    public CompletableFuture<OutboundMessage> processDirect(String content, String sessionKey) {
        InboundMessage msg = new InboundMessage("direct", "api", sessionKey, content);
        msg = msg.withSessionKeyOverride(sessionKey);
        // 对标 Python process_direct: 共享 dispatch 锁（直接调用也串行化）
        Lock lock = sessionLocks.computeIfAbsent(sessionKey, k -> new ReentrantLock());
        lock.lock();
        try {
            return processMessage(msg, null, null, null)
                    .whenComplete((r, ex) -> lock.unlock());
        } catch (Exception e) {
            lock.unlock();
            throw e;
        }
    }

    public SessionManager getSessionManager() { return sessionManager; }
    public MessageBus getBus() { return bus; }
    public LLMProvider getProvider() { return provider; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }
    public String getModel() { return model; }
    public int getMaxIterations() { return maxIterations; }
    public int getMaxToolResultChars() { return maxToolResultChars; }
    public int getContextWindowTokens() { return contextWindowTokens; }
}
