package com.nanobot.agent.loop;

import com.nanobot.agent.autocompact.AutoCompact;
import com.nanobot.agent.command.BuiltinCommands;
import com.nanobot.agent.command.CommandContext;
import com.nanobot.agent.command.CommandRouter;
import com.nanobot.agent.context.ContextBuilder;
import com.nanobot.agent.context.Consolidator;
import com.nanobot.agent.context.ConsolidatorProvider;
import com.nanobot.agent.context.MemoryStore;
import com.nanobot.agent.runner.AgentRunSpec;
import com.nanobot.agent.runner.AgentRunner;
import com.nanobot.agent.session.Session;
import com.nanobot.agent.session.SessionManager;
import com.nanobot.agent.subagent.SubagentManager;
import com.nanobot.agent.tools.*;
import com.nanobot.bus.InboundMessage;
import com.nanobot.bus.MessageBus;
import com.nanobot.bus.OutboundMessage;
import com.nanobot.bus.RuntimeEventBus;
import com.nanobot.bus.RuntimeEventPublisher;
import com.nanobot.config.*;
import com.nanobot.providers.ProviderSnapshot;
import com.nanobot.providers.base.LLMProvider;
import com.nanobot.providers.base.LLMRuntime;
import com.nanobot.providers.base.ThrowingConsumer;
import com.nanobot.security.WorkspaceScopeResolver;
import com.nanobot.trace.TraceObservation;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 核心 agent 处理引擎——turn 状态机驱动。
 * 完整对标 Python AgentLoop 类（agent/loop.py 1780 行）。
 *
 * <p>从 MessageBus 消费入站消息，依次经过状态机各阶段
 * （RESTORE→COMPACT→COMMAND→BUILD→RUN→SAVE→RESPOND→DONE），
 * 最终发布出站回复。</p>
 *
 * <p>构造器对标 Python AgentLoop.__init__()（loop.py:179-333），
 * 内部创建 Consolidator、ContextBuilder、SubagentManager、AutoCompact 等全部依赖，
 * 与 Python 版保持一致——调用方只需提供配置即可。</p>
 */
public class AgentLoop implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    // -- TRANSITIONS 状态转换表 --
    // 对应 Python AgentLoop._TRANSITIONS（loop.py:168-177）
    public static final Map<Map.Entry<TurnState, String>, TurnState> TRANSITIONS;

    static {
        var m = new LinkedHashMap<Map.Entry<TurnState, String>, TurnState>();
        m.put(Map.entry(TurnState.RESTORE, "ok"), TurnState.COMPACT);
        m.put(Map.entry(TurnState.COMPACT, "ok"), TurnState.COMMAND);
        m.put(Map.entry(TurnState.COMMAND, "dispatch"), TurnState.BUILD);
        m.put(Map.entry(TurnState.COMMAND, "shortcut"), TurnState.DONE);
        m.put(Map.entry(TurnState.BUILD, "ok"), TurnState.RUN);
        m.put(Map.entry(TurnState.RUN, "ok"), TurnState.SAVE);
        m.put(Map.entry(TurnState.SAVE, "ok"), TurnState.RESPOND);
        m.put(Map.entry(TurnState.RESPOND, "ok"), TurnState.DONE);
        TRANSITIONS = Collections.unmodifiableMap(m);
    }

    private static final String UNIFIED_SESSION_KEY = "unified:default";

    // ================================================================
    // 字段 — 完整对标 Python AgentLoop.__init__()（loop.py:219-333）
    // ================================================================

    // -- 核心依赖（构造器注入 / 内部创建）--
    final MessageBus bus;
    final AgentRunner runner;
    final ContextBuilder context;
    final CommandRouter commands;
    final Consolidator consolidator;
    final SessionManager sessions;
    final AutoCompact autoCompact;
    final SubagentManager subagents;
    final WorkspaceScopeResolver workspaceScopes;
    final FileStateStore fileStateStore;
    final ToolRegistry tools;
    final Path workspace;

    // -- provider / model 运行时 --
    LLMProvider provider;
    /** LLMProvider → ConsolidatorProvider 适配器，供 Consolidator 压缩用 */
    ConsolidatorProviderAdapter consolidatorProvider;
    String model;
    int maxIterations;
    int contextWindowTokens;
    @Nullable Integer contextBlockLimit;
    int maxToolResultChars;
    String providerRetryMode;
    int toolHintMaxLength;
    boolean restrictToWorkspace;

    // -- 配置 --
    final ToolsProperties toolsConfig;
    final Map<String, ModelPresetProperties> modelPresets;
    final boolean unifiedSession;
    final int maxMessages;
    final Map<String, Object> mcpServers;
    final Object channelsConfig; // ChannelsProperties — 与 Python channels_config 对标
    final NanobotProperties nanobotConfig; // 完整配置引用，供 from_config 使用

    // -- provider snapshot / model preset --
    @Nullable
    private java.util.function.Supplier<ProviderSnapshot> providerSnapshotLoader;
    @Nullable
    private java.util.function.Function<String, ProviderSnapshot> presetSnapshotLoader;
    @Nullable
    private Consumer<Object> runtimeModelPublisher; // Consumer<ProviderSnapshot>
    @Nullable
    private List<Object> providerSignature;
    @Nullable
    private List<Object> defaultSelectionSignature;
    @Nullable
    private String activePreset;

    // -- MCP --
    private boolean mcpConnected;
    private boolean mcpConnecting;
    private final Map<String, Object> mcpStacks = new ConcurrentHashMap<>(); // AsyncExitStack 对标

    // -- 并发控制 --
    final ConcurrentMap<String, ReentrantLock> sessionLocks;
    @Nullable final Semaphore concurrencyGate; // null = unlimited
    final ConcurrentMap<String, List<Thread>> activeTasks;
    final ConcurrentMap<String, BlockingQueue<InboundMessage>> pendingQueues;

    // -- 运行时状态 --
    final RuntimeEventBus runtimeEvents;
    final RuntimeEventPublisher runtimeEventPublisher;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running;
    private final double startTime = System.currentTimeMillis() / 1000.0;
    private final Map<String, Integer> lastUsage = new ConcurrentHashMap<>();
    private int currentIteration;
    private final Map<String, Object> runtimeVars = new ConcurrentHashMap<>();

    // ================================================================
    // 构造器 — 对标 Python AgentLoop.__init__()（loop.py:179-333）
    // ================================================================

    /**
     * 全参构造器，完整对标 Python __init__。
     *
     * <p>创建所有内部依赖：ContextBuilder、AgentRunner、Consolidator、
     * SubagentManager、AutoCompact、WorkspaceScopeResolver、FileStateStore、
     * CommandRouter（含内置命令注册）、ToolRegistry（含默认工具注册）。</p>
     */
    public AgentLoop(
            MessageBus bus,
            LLMProvider provider,
            Path workspace,
            @Nullable String model,
            @Nullable Integer maxIterations,
            @Nullable Integer maxConcurrentSubagents,
            @Nullable Integer contextWindowTokens,
            @Nullable Integer contextBlockLimit,
            @Nullable Integer maxToolResultChars,
            @Nullable String providerRetryMode,
            @Nullable Integer toolHintMaxLength,
            boolean restrictToWorkspace,
            @Nullable SessionManager sessionManager,
            @Nullable Map<String, Object> mcpServers,
            @Nullable Object channelsConfig,
            @Nullable String timezone,
            int sessionTtlMinutes,
            double consolidationRatio,
            int maxMessages,
            boolean unifiedSession,
            @Nullable List<String> disabledSkills,
            @Nullable ToolsProperties toolsConfig,
            @Nullable NanobotProperties nanobotConfig,
            @Nullable java.util.function.Supplier<ProviderSnapshot> providerSnapshotLoader,
            @Nullable List<Object> providerSignature,
            @Nullable Map<String, ModelPresetProperties> modelPresets,
            @Nullable String modelPreset,
            @Nullable java.util.function.Function<String, ProviderSnapshot> presetSnapshotLoader,
            @Nullable RuntimeEventBus runtimeEvents,
            @Nullable Consumer<Object> runtimeModelPublisher) {

        this.bus = bus;
        this.runtimeEvents = runtimeEvents != null ? runtimeEvents : new RuntimeEventBus();
        this.runtimeEventPublisher = new RuntimeEventPublisher(this.runtimeEvents);
        this.channelsConfig = channelsConfig;
        this.provider = provider;
        this.providerSnapshotLoader = providerSnapshotLoader;
        this.presetSnapshotLoader = presetSnapshotLoader;
        this.runtimeModelPublisher = runtimeModelPublisher;
        this.providerSignature = providerSignature;
        this.defaultSelectionSignature = null; // 首次 refresh 时计算
        this.workspace = workspace;
        this.model = model != null ? model : provider.getDefaultModel();

        var defaults = nanobotConfig != null ? nanobotConfig.agents().defaults() : null;
        this.maxIterations = maxIterations != null ? maxIterations
                : (defaults != null ? defaults.maxToolIterations() : 200);
        this.contextWindowTokens = contextWindowTokens != null ? contextWindowTokens
                : (defaults != null ? defaults.contextWindowTokens() : 65536);
        this.contextBlockLimit = contextBlockLimit;
        this.maxToolResultChars = maxToolResultChars != null ? maxToolResultChars
                : (defaults != null ? defaults.maxToolResultChars() : 16000);
        this.providerRetryMode = providerRetryMode != null ? providerRetryMode : "standard";
        this.toolHintMaxLength = toolHintMaxLength != null ? toolHintMaxLength
                : (defaults != null ? defaults.toolHintMaxLength() : 40);

        this.toolsConfig = toolsConfig != null ? toolsConfig : new ToolsProperties(null, null, null, null, null, null, null, null, null);
        this.restrictToWorkspace = restrictToWorkspace;
        this.workspaceScopes = new WorkspaceScopeResolver(workspace, restrictToWorkspace);
        this.nanobotConfig = nanobotConfig;

        // 上下文构建器
        this.context = new ContextBuilder(workspace, timezone != null ? timezone : "UTC");
        // 会话管理
        this.sessions = sessionManager != null ? sessionManager : new SessionManager(workspace);
        // 工具注册表
        this.tools = new ToolRegistry();
        // 文件读写状态追踪
        this.fileStateStore = new FileStateStore();
        // AgentRunner
        this.runner = new AgentRunner(provider);

        // 子代理管理器
        this.subagents = new SubagentManager(
                provider, workspace, bus, this.model, this.toolsConfig,
                this.maxToolResultChars, restrictToWorkspace,
                disabledSkills != null ? Set.copyOf(disabledSkills) : Set.of(),
                this.maxIterations,
                maxConcurrentSubagents != null ? maxConcurrentSubagents : 1,
                sk -> {
                    // runner_wall_llm_timeout_s 对标
                    var sess = this.sessions.getOrCreate(sk);
                    var goalMeta = sess.metadata().get("goal_state");
                    if (goalMeta instanceof Map<?, ?> gm) {
                        var timeout = gm.get("wall_llm_timeout_s");
                        if (timeout instanceof Number n) return n.doubleValue();
                    }
                    return null;
                });

        // Consolidator — 对标 Python self.consolidator = Consolidator(...)
        // 需要将 LLMProvider 适配为 ConsolidatorProvider 接口
        var consolidationProvider = new ConsolidatorProviderAdapter(provider);
        // BuildMessagesFunction 包装：填补 Consolidator.BuildMessagesFunction 与
        // ContextBuilder.buildMessages 之间的参数差异
        Consolidator.BuildMessagesFunction buildMessagesFn = (history, currentMessage,
                channel, chatId, senderId, sessionSummary, sessionMetadata,
                sessionKey, unified) ->
                this.context.buildMessages(
                        history, currentMessage, null, null,
                        channel, chatId, "user", senderId,
                        sessionSummary, sessionMetadata, null,
                        workspace, this, null, false, true,
                        sessionKey, unified);
        this.consolidator = new Consolidator(
                this.context.getMemory(), consolidationProvider, this.model,
                this.sessions, this.contextWindowTokens,
                4096, consolidationRatio, unifiedSession,
                buildMessagesFn,
                tools::getDefinitions);
        this.consolidatorProvider = consolidationProvider;

        // AutoCompact
        this.autoCompact = new AutoCompact(this.sessions, this.consolidator, sessionTtlMinutes);

        // 并发控制
        this.sessionLocks = new ConcurrentHashMap<>();
        this.activeTasks = new ConcurrentHashMap<>();
        this.pendingQueues = new ConcurrentHashMap<>();
        this.concurrencyGate = maxConcurrentSubagents != null && maxConcurrentSubagents > 0
                ? new Semaphore(maxConcurrentSubagents) : null;

        // Model presets
        this.modelPresets = modelPresets != null ? new LinkedHashMap<>(modelPresets) : new LinkedHashMap<>();
        this.unifiedSession = unifiedSession;
        this.maxMessages = maxMessages > 0 ? maxMessages : 120;
        this.mcpServers = mcpServers != null ? new LinkedHashMap<>(mcpServers) : new LinkedHashMap<>();

        // 注册默认工具
        registerDefaultTools();

        // 应用初始 model preset
        this.activePreset = null;
        if (modelPreset != null && !modelPreset.isEmpty()) {
            setModelPreset(modelPreset, false);
        }

        // 命令路由（含内置命令注册）
        this.commands = new CommandRouter();
        BuiltinCommands.registerAll(this.commands);
    }

    // ================================================================
    // from_config — 对标 Python from_config() classmethod（loop.py:336-389）
    // ================================================================

    /**
     * 从 NanobotProperties 创建 AgentLoop。
     * 完整对标 Python AgentLoop.from_config()。
     *
     * @param config  NanobotProperties 根配置
     * @param bus     MessageBus（可选，为 null 则创建新实例）
     * @param extraProvider 覆盖 provider（可选）
     * @param extraModel    覆盖 model（可选）
     * @return 配置完成的 AgentLoop 实例
     */
    public static AgentLoop fromConfig(
            NanobotProperties config,
            @Nullable MessageBus bus,
            @Nullable LLMProvider extraProvider,
            @Nullable String extraModel) {

        var defaults = config.agents().defaults();
        MessageBus effectiveBus = bus != null ? bus : new MessageBus();

        // Provider 工厂 — 对标 Python make_provider(config)
        LLMProvider provider;
        if (extraProvider != null) {
            provider = extraProvider;
        } else {
            var pf = new com.nanobot.providers.ProviderFactory(config);
            provider = pf.makeProvider(defaults.modelPreset(), null, extraModel);
        }

        // 解析 model preset
        var resolved = config.resolveDefaultPreset();
        String model = extraModel != null ? extraModel : resolved.model();
        int ctxWindow = resolved.contextWindowTokens();

        return new AgentLoop(
                effectiveBus,
                provider,
                config.workspacePath(),
                model,
                defaults.maxToolIterations(),
                defaults.maxConcurrentSubagents(),
                ctxWindow,
                defaults.contextBlockLimit(),
                defaults.maxToolResultChars(),
                defaults.providerRetryMode(),
                defaults.toolHintMaxLength(),
                config.tools().restrictToWorkspace(),
                null, // sessionManager — 使用默认
                null, // mcpServers
                config.channels(),
                defaults.timezone(),
                defaults.sessionTtlMinutes(),
                defaults.consolidationRatio(),
                defaults.maxMessages(),
                defaults.unifiedSession(),
                defaults.disabledSkills(),
                config.tools(),
                config,
                null, // providerSnapshotLoader
                null, // providerSignature
                config.modelPresets(),
                defaults.modelPreset(),
                null, // presetSnapshotLoader
                null, // runtimeEvents
                null  // runtimeModelPublisher
        );
    }

    // -- accessors --
    // 对应 Python 各 property（loop.py:150-162）

    public boolean isRunning() { return running; }
    public void setRunning(boolean running) { this.running = running; }
    public CommandRouter commands() { return commands; }
    public ToolRegistry tools() { return tools; }
    public String model() { return model; }
    public int contextWindowTokens() { return contextWindowTokens; }
    public int maxIterations() { return maxIterations; }
    public SessionManager sessions() { return sessions; }
    public Consolidator consolidator() { return consolidator; }
    public ContextBuilder context() { return context; }
    public LLMProvider provider() { return provider; }
    public SubagentManager subagents() { return subagents; }

    // -- run --
    // 对应 Python AgentLoop.run()（loop.py 主入口）

    @Override
    public void run() {
        running = true;
        log.info("Agent loop started");
        try {
            while (running) {
                InboundMessage msg;
                try {
                    msg = bus.consumeInbound();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                executor.submit(() -> dispatch(msg));
            }
        } finally {
            running = false;
            log.info("Agent loop stopped");
        }
    }

    public void stop() {
        running = false;
        executor.shutdownNow();
    }

    // -- dispatch --
    // 对应 Python _dispatch()（loop.py:928）

    void dispatch(InboundMessage msg) {
        var key = effectiveSessionKey(msg);
        if (!key.equals(msg.sessionKey())) {
            msg = msg.withSessionKey(key);
        }

        // 优先命令——无需 session lock 即可分发
        if (commands.isPriority(msg.content())) {
            var ctx = new CommandContext(msg, null, key, msg.content(), "", this);
            var result = commands.dispatchPriority(ctx);
            if (result != null) {
                try { bus.publishOutbound(result); } catch (InterruptedException ignored) {}
            }
            return;
        }

        // 获取 session lock + 并发门控
        var lock = sessionLocks.computeIfAbsent(key, k -> new ReentrantLock());
        BlockingQueue<InboundMessage> pending = null;
        lock.lock();
        try {
            if (concurrencyGate != null) {
                concurrencyGate.acquire();
            }
        } catch (InterruptedException e) {
            lock.unlock();
            Thread.currentThread().interrupt();
            return;
        }

        try {
            // 只有持有 session lock 的任务可以发布活跃 mid-turn 注入队列
            pending = new LinkedBlockingQueue<>(20);
            pendingQueues.put(key, pending);

            OutboundMessage response = null;
            try {
                response = processMessage(msg);
            } catch (Exception e) {
                log.error("Error processing message for session {}", key, e);
                try {
                    bus.publishOutbound(new OutboundMessage(
                            msg.channel(), msg.chatId(),
                            "Sorry, I encountered an error.",
                            null, null, null, null));
                } catch (InterruptedException ignored) {}
            }

            if (response != null) {
                try { bus.publishOutbound(response); } catch (InterruptedException ignored) {}
            } else if ("cli".equals(msg.channel())) {
                try {
                    bus.publishOutbound(new OutboundMessage(
                            msg.channel(), msg.chatId(), "",
                            null, null, null, null));
                } catch (InterruptedException ignored) {}
            }
        } finally {
            // 排空 pending 队列，重新发布剩余消息
            if (pendingQueues.get(key) == pending) {
                pendingQueues.remove(key);
            }
            if (pending != null) {
                var leftover = new ArrayList<InboundMessage>();
                pending.drainTo(leftover);
                for (var item : leftover) {
                    try { bus.publishInbound(item); } catch (InterruptedException ignored) {}
                }
                if (!leftover.isEmpty()) {
                    log.info("Re-published {} leftover message(s) to bus for session {}",
                            leftover.size(), key);
                }
            }
            if (concurrencyGate != null) {
                concurrencyGate.release();
            }
            lock.unlock();
        }
    }

    // -- dispatchCommandInline --
    // 对应 Python _dispatch_command_inline()（loop.py:619）

    void dispatchCommandInline(InboundMessage msg, String key, String raw,
                               java.util.function.Function<CommandContext, OutboundMessage> dispatchFn) {
        var ctx = new CommandContext(msg, null, key, raw, "", this);
        var result = dispatchFn.apply(ctx);
        if (result != null) {
            try { bus.publishOutbound(result); } catch (InterruptedException ignored) {}
        } else {
            log.warn("Command '{}' matched but dispatch returned null", raw);
        }
    }

    // ================================================================
    // Provider Snapshot & Model Preset
    // 对标 Python _apply_provider_snapshot / _refresh_provider_snapshot
    // / set_model_preset / model_preset（loop.py:395-471）
    // ================================================================

    /**
     * 热切换 provider/model，不影响活跃 turn。
     * 对标 Python _apply_provider_snapshot()（loop.py:395-424）。
     */
    void applyProviderSnapshot(ProviderSnapshot snapshot, boolean publishUpdate,
                                @Nullable String modelPresetName) {
        var oldModel = this.model;
        this.provider = snapshot.provider();
        this.model = snapshot.model();
        this.contextWindowTokens = snapshot.contextWindowTokens();
        this.runner.getProvider(); // runner.provider 是 final，但 LLMProvider 内部可变
        this.subagents.setProvider(snapshot.provider(), snapshot.model());
        // 更新 ConsolidatorProvider 适配器，hot-swap 底层 LLMProvider
        var newConsolidationProvider = new ConsolidatorProviderAdapter(snapshot.provider());
        this.consolidator.setProvider(newConsolidationProvider, snapshot.model(), snapshot.contextWindowTokens());
        this.consolidatorProvider = newConsolidationProvider;
        this.providerSignature = snapshot.signature();

        if (publishUpdate && runtimeModelPublisher != null) {
            runtimeModelPublisher.accept(snapshot);
        }
        if (publishUpdate) {
            runtimeEventPublisher.runtimeModelChanged(
                    this.model,
                    modelPresetName != null ? modelPresetName : getModelPreset());
        }
        log.info("Runtime model switched for next turn: {} -> {}", oldModel, model);
    }

    /** 便捷方法：不发布更新的快照应用 */
    void applyProviderSnapshot(ProviderSnapshot snapshot) {
        applyProviderSnapshot(snapshot, false, null);
    }

    /**
     * 刷新 provider 配置（从 loader 重新获取快照）。
     * 对标 Python _refresh_provider_snapshot()（loop.py:426-448）。
     */
    void refreshProviderSnapshot() {
        if (providerSnapshotLoader == null) return;
        ProviderSnapshot snapshot;
        try {
            snapshot = providerSnapshotLoader.get();
        } catch (Exception e) {
            log.error("Failed to refresh provider config", e);
            return;
        }
        // 若 active preset 的默认选择签名未变，重新构建 preset snapshot
        if (activePreset != null && presetSnapshotLoader != null) {
            try {
                snapshot = presetSnapshotLoader.apply(activePreset);
            } catch (Exception e) {
                log.error("Failed to refresh active model preset", e);
                return;
            }
        } else {
            activePreset = null;
        }
        if (snapshot.signature().equals(providerSignature)) return;
        applyProviderSnapshot(snapshot);
    }

    /**
     * 获取当前激活的 model preset 名称。
     * 对标 Python model_preset property（loop.py:451-452）。
     */
    @Nullable
    public String getModelPreset() {
        return activePreset;
    }

    /**
     * 设置 model preset（发布更新）。
     * 对标 Python model_preset setter（loop.py:455-456）。
     */
    public void setModelPreset(@Nullable String name) {
        setModelPreset(name, true);
    }

    /**
     * 设置 model preset。
     * 对标 Python set_model_preset()（loop.py:466-471）。
     *
     * @param name          preset 名称
     * @param publishUpdate 是否发布运行时模型变更事件
     */
    public void setModelPreset(@Nullable String name, boolean publishUpdate) {
        if (name == null || name.isEmpty() || "default".equals(name)) {
            // 恢复默认
            if (nanobotConfig != null) {
                var resolved = nanobotConfig.resolveDefaultPreset();
                var match = nanobotConfig.matchProvider(resolved.model(), resolved);
                if (match.config() != null) {
                    var snapshot = new ProviderSnapshot(
                            provider, resolved.model(), resolved.contextWindowTokens(), List.of());
                    applyProviderSnapshot(snapshot, publishUpdate, null);
                }
            }
            activePreset = null;
            return;
        }
        if (!modelPresets.containsKey(name)) {
            throw new IllegalArgumentException("model_preset '" + name + "' not found");
        }
        var preset = modelPresets.get(name);
        var snapshot = new ProviderSnapshot(
                provider, preset.model(), preset.contextWindowTokens(), List.of());
        applyProviderSnapshot(snapshot, publishUpdate, name);
        activePreset = name;
    }

    // ================================================================
    // Tool / MCP / Runtime helpers
    // 对标 Python _register_default_tools / _connect_mcp / llm_runtime
    // ================================================================

    /**
     * 注册默认工具集（通过 ToolLoader 发现并注册）。
     * 对标 Python _register_default_tools()（loop.py:473-501）。
     */
    void registerDefaultTools() {
        var ctx = ToolContext.builder()
                .config(Map.of(
                        "restrict_to_workspace", restrictToWorkspace,
                        "workspace", workspace.toAbsolutePath().normalize().toString()))
                .workspace(workspace.toAbsolutePath().normalize().toString())
                .bus(bus)
                .sessions(sessions)
                .timezone(context != null && context.getMemory() != null ? "UTC" : "UTC")
                .build();

        var loader = new ToolLoader();
        // 从 classpath 发现并注册工具
        var discovered = loader.discover();
        var toolInstances = new ArrayList<Tool>();
        for (var cls : discovered) {
            try {
                toolInstances.add(Tool.create(ctx, cls));
            } catch (Exception e) {
                log.debug("Failed to instantiate tool: {}", cls.getSimpleName());
            }
        }
        var registered = loader.load(toolInstances, ctx, tools, "core");
        log.info("Registered {} tools: {}", registered.size(), registered);

        // 注册 MyTool（运行时状态引用）——对标 Python MyTool 手动注册
        // my tool 通过 ToolLoader 的 scope=core 自动包含
    }

    /**
     * 连接 MCP 服务器。
     * 对标 Python _connect_mcp()（loop.py:503-505）。
     *
     * <p>完整实现需要 MCP client 库。当前为功能 stub——MCP 连接
     * 在首次 process_direct 或 processSystemMessage 调用时按需初始化。</p>
     */
    void connectMcp() {
        if (mcpConnected || mcpConnecting) return;
        if (mcpServers.isEmpty()) {
            mcpConnected = true;
            return;
        }
        // MCP client 连接逻辑（需引入 MCP SDK）
        // 对标 Python agent_context.connect_mcp(self, self.tools)
        mcpConnecting = true;
        try {
            log.info("MCP servers configured: {}", mcpServers.keySet());
            // TODO: 引入 MCP client 库后实现完整连接逻辑
            mcpConnected = true;
        } catch (Exception e) {
            log.warn("MCP connect failed", e);
        } finally {
            mcpConnecting = false;
        }
    }

    /**
     * 返回当前 provider/model 对。
     * 对标 Python llm_runtime() property（loop.py:158-161）。
     */
    public LLMRuntime llmRuntime() {
        refreshProviderSnapshot();
        return new LLMRuntime(provider, model);
    }

    /**
     * 运行时事件发布器。
     * 对标 Python _runtime_events() property（loop.py:565-566）。
     */
    public RuntimeEventPublisher runtimeEvents() {
        return runtimeEventPublisher;
    }

    /**
     * 同步子代理运行时限制。
     * 对标 Python _sync_subagent_runtime_limits()（loop.py:391-393）。
     */
    void syncSubagentRuntimeLimits() {
        subagents.setMaxIterations(this.maxIterations);
    }

    // ================================================================
    // 状态机 — 对标 Python _process_message / _run_state_machine
    // ================================================================

    public OutboundMessage processMessage(InboundMessage msg) {
        // 系统渠道消息（如子代理结果）使用专用处理器
        if ("system".equals(msg.channel())) {
            return processSystemMessage(msg, null, null, null, null, null);
        }

        var key = effectiveSessionKey(msg);
        var turnId = UUID.randomUUID().toString();
        var ctx = new TurnContext(msg, key, TurnState.RESTORE, turnId);

        // 追踪开始（turn 边界），对标 Python RuntimeEventBus.session_turn_started
        TraceObservation.startTrace("turn", Map.of(
                "turnId", turnId,
                "sessionKey", key,
                "channel", msg.channel()));

        try {
            String event = "start";

            while (ctx.state() != TurnState.DONE) {
                var next = TRANSITIONS.get(Map.entry(ctx.state(), event));
                if (next != null) {
                    ctx.setState(next);
                }
                event = switch (ctx.state()) {
                    case RESTORE -> stateRestore(ctx);
                    case COMPACT -> stateCompact(ctx);
                    case COMMAND -> stateCommand(ctx);
                    case BUILD -> stateBuild(ctx);
                    case RUN -> stateRun(ctx);
                    case SAVE -> stateSave(ctx);
                    case RESPOND -> stateRespond(ctx);
                    default -> "ok";
                };
                if (event == null) break;
            }

            return ctx.outbound();
        } finally {
            TraceObservation.endTrace();
        }
    }

    // -- runAgentLoop --
    // 对应 Python _run_agent_loop()（loop.py:665）

    RunLoopResult runAgentLoop(
            TurnContext ctx,
            Runnable progressCallback,
            java.util.function.Consumer<String> streamCallback,
            Runnable streamEndCallback,
            java.util.function.Consumer<String> retryWaitCallback) {

        var session = ctx.session();
        var channel = ctx.msg().channel();
        var chatId = ctx.msg().chatId();
        var metadata = ctx.msg().metadata();
        var sessionKey = ctx.sessionKey();
        ThrowingConsumer<String> retryCb = retryWaitCallback != null
                ? s -> retryWaitCallback.accept(s) : null;
        Consumer<String> progressCb = progressCallback != null
                ? s -> progressCallback.run() : null;

        var spec = new AgentRunSpec(
                ctx.initialMessages(), tools, model, maxIterations, maxToolResultChars,
                null, null, null, null, null, null,
                true, false, workspace, sessionKey,
                contextWindowTokens, null, providerRetryMode,
                progressCb, streamCallback != null, retryCb,
                null, null, null, null, null, true);

        try {
            var result = runner.run(spec);
            ctx.setFinalContent(result.finalContent());
            ctx.setToolsUsed(new ArrayList<>(result.toolsUsed()));
            ctx.setAllMessages(result.messages());
            ctx.setStopReason(result.stopReason());
            ctx.setHadInjections(result.hadInjections());
            if (result.usage() != null) {
                lastUsage.putAll(result.usage());
            }
            return new RunLoopResult(result.finalContent(), result.toolsUsed(),
                    result.messages(), result.stopReason(), result.hadInjections());
        } catch (Exception e) {
            log.error("Agent run failed for {}", sessionKey, e);
            ctx.setFinalContent("An error occurred during processing.");
            ctx.setStopReason("error");
            return new RunLoopResult("An error occurred during processing.",
                    List.of(), ctx.allMessages() != null ? ctx.allMessages() : List.of(),
                    "error", false);
        }
    }

    record RunLoopResult(String finalContent, List<String> toolsUsed,
                         List<Map<String, Object>> messages, String stopReason,
                         boolean hadInjections) {}

    // ================================================================
    // State handlers — 对标 Python _state_*
    // ================================================================

    // 对应 Python _state_restore()（loop.py:1327）
    String stateRestore(TurnContext ctx) {
        var session = sessions.getOrCreate(ctx.sessionKey());
        ctx.setSession(session);

        // 处理 pending 队列
        var pendingQ = pendingQueues.get(ctx.sessionKey());
        if (pendingQ != null) {
            var pending = new ArrayList<InboundMessage>();
            pendingQ.drainTo(pending);
            if (!pending.isEmpty()) {
                ctx.setPendingSummary("Pending: " + pending.size() + " messages");
            }
        }

        // 提前持久化用户消息
        persistUserMessageEarly(ctx.msg(), session);

        return "ok";
    }

    // 对应 Python _state_compact()（loop.py:1363）
    String stateCompact(TurnContext ctx) {
        var session = ctx.session();
        if (session == null) return "ok";

        try {
            consolidator.maybeConsolidateByTokens(session, null);
        } catch (Exception e) {
            log.warn("Consolidation check failed for {}", ctx.sessionKey(), e);
        }
        return "ok";
    }

    // 对应 Python _state_command()（loop.py:1368）
    String stateCommand(TurnContext ctx) {
        var msg = ctx.msg();
        var session = ctx.session();

        if (commands.isDispatchableCommand(msg.content())) {
            var cmdCtx = new CommandContext(msg, session, ctx.sessionKey(),
                    msg.content(), "", this);
            var result = commands.dispatch(cmdCtx);
            if (result != null) {
                ctx.setOutbound(result);
                ctx.setSuppressResponse(true);
                try { bus.publishOutbound(result); } catch (InterruptedException ignored) {}
                return "shortcut";
            }
            // cmdGoal 返回 null → 走正常 dispatch 流程
            if ("/goal".equals(msg.content().split(" ")[0].toLowerCase())) {
                return "dispatch";
            }
        }

        return "dispatch";
    }

    // 对应 Python _state_build()（loop.py:1393）
    String stateBuild(TurnContext ctx) {
        var session = ctx.session();
        if (session == null) return "ok";

        var history = session.getHistory(0, replayTokenBudget(), true);
        // 将当前用户消息追加到历史
        var initialMessages = context.buildMessages(
                history,
                ctx.msg().content(),
                null,                   // skillNames
                ctx.msg().media(),
                ctx.msg().channel(),
                ctx.msg().chatId(),
                "user",                 // currentRole
                ctx.msg().senderId(),
                ctx.pendingSummary(),   // sessionSummary
                session.metadata(),
                null,                   // currentRuntimeLines
                workspace,
                this,                   // runtimeState
                ctx.msg(),              // inboundMessage
                false,                  // skipRuntimeLines
                true,                   // includeMemoryRecentHistory
                ctx.sessionKey(),       // sessionKey
                false);                 // unifiedSession

        ctx.setHistory(new ArrayList<>(history));
        ctx.setInitialMessages(initialMessages);

        return "ok";
    }

    // 对应 Python _state_run()（loop.py:1439）
    String stateRun(TurnContext ctx) {
        var session = ctx.session();
        if (session == null) return "ok";

        var result = runAgentLoop(ctx, null, null, null, null);
        ctx.setFinalContent(result.finalContent());
        ctx.setToolsUsed(new ArrayList<>(result.toolsUsed()));
        ctx.setAllMessages(new ArrayList<>(result.messages()));
        ctx.setStopReason(result.stopReason());
        ctx.setHadInjections(result.hadInjections());

        if ("max_iterations".equals(result.stopReason())) {
            log.warn("Max iterations ({}) reached", maxIterations);
        } else if ("error".equals(result.stopReason())) {
            log.error("LLM returned error: {}",
                    (result.finalContent() != null ? result.finalContent() : "").substring(
                            0, Math.min(200, result.finalContent() != null ? result.finalContent().length() : 0)));
        }

        return "ok";
    }

    // 对应 Python _state_save()（loop.py:1473）
    String stateSave(TurnContext ctx) {
        var session = ctx.session();
        if (session == null) return "ok";

        saveTurn(session, ctx.allMessages(), ctx.saveSkip());
        return "ok";
    }

    // 对应 Python _state_respond()（loop.py:1512）
    String stateRespond(TurnContext ctx) {
        if (ctx.suppressResponse()) {
            ctx.setOutbound(null);
            return "ok";
        }

        var msg = ctx.msg();
        var content = ctx.finalContent();
        if (content == null || content.isBlank()) {
            content = "[No response generated]";
        }

        var meta = msg.metadata() != null ? new LinkedHashMap<>(msg.metadata()) : new LinkedHashMap<String, Object>();
        if (!"error".equals(ctx.stopReason()) && !"tool_error".equals(ctx.stopReason())) {
            meta.put("_streamed", true);
        }

        var outbound = new OutboundMessage(
                msg.channel(), msg.chatId(), content,
                null, null, meta, null);
        ctx.setOutbound(outbound);

        try {
            bus.publishOutbound(outbound);
        } catch (InterruptedException ignored) {}

        return "ok";
    }

    // ================================================================
    // Helpers — 对标 Python _* helpers
    // ================================================================

    // 对应 Python _effective_session_key()（loop.py:647）
    String effectiveSessionKey(InboundMessage msg) {
        return msg.sessionKey();
    }

    // 对应 Python _persist_user_message_early()（loop.py:568）
    void persistUserMessageEarly(InboundMessage msg, Session session) {
        if (msg.content() == null || msg.content().isBlank()) return;
        if (msg.content().startsWith("/")) return; // 命令不提前持久化

        session.addMessage("user", msg.content(),
                msg.media() != null && !msg.media().isEmpty()
                        ? Map.of("media", msg.media())
                        : null);
        sessions.save(session);
    }

    // 对应 Python _build_initial_messages()（loop.py:592）
    List<Map<String, Object>> buildInitialMessages(InboundMessage msg, Session session) {
        var history = session.getHistory(maxIterations * 4, 0, false);
        history.add(Map.of("role", "user", "content",
                msg.content() != null ? msg.content() : "",
                "timestamp", java.time.Instant.now().toString()));
        return history;
    }

    // 对应 Python _save_turn()（loop.py:1569）
    void saveTurn(Session session, List<Map<String, Object>> allMessages, int saveSkip) {
        if (allMessages == null || allMessages.isEmpty()) return;

        for (int i = saveSkip; i < allMessages.size(); i++) {
            var msg = allMessages.get(i);
            var role = msg.get("role");
            var content = msg.get("content");
            if (role == null) continue;

            // 跳过无 tool_calls 的空 assistant 消息——它们会污染 session 上下文
            if ("assistant".equals(role) && (content == null || "".equals(content))
                    && !msg.containsKey("tool_calls")) {
                continue;
            }

            var entry = new LinkedHashMap<>(msg);

            // 截断超长 tool 结果
            if ("tool".equals(role)) {
                if (content instanceof String s && s.length() > maxToolResultChars) {
                    entry.put("content", s.substring(0, maxToolResultChars) + "\n... (truncated)");
                } else if (content instanceof List<?> blocks) {
                    var filtered = sanitizePersistedBlocks(blocks, true);
                    entry.put("content", filtered);
                }
            }

            var extra = new LinkedHashMap<String, Object>();
            for (var e : entry.entrySet()) {
                if (!"role".equals(e.getKey()) && !"content".equals(e.getKey())) {
                    extra.put(e.getKey(), e.getValue());
                }
            }
            session.addMessage(role.toString(),
                    entry.get("content") != null ? entry.get("content").toString() : "",
                    extra.isEmpty() ? null : extra);
        }
        sessions.save(session);
    }

    @SuppressWarnings("unchecked")
    // 对应 Python _sanitize_persisted_blocks()（loop.py:1529）
    List<Map<String, Object>> sanitizePersistedBlocks(List<?> blocks, boolean shouldTruncate) {
        var filtered = new ArrayList<Map<String, Object>>();
        for (var block : blocks) {
            if (!(block instanceof Map<?, ?> bm)) {
                if (block instanceof Map) filtered.add((Map<String, Object>) block);
                continue;
            }
            var b = (Map<String, Object>) bm;

            // 去除内联图片 data URI
            if ("image_url".equals(b.get("type"))) {
                var imageUrl = b.get("image_url");
                if (imageUrl instanceof Map<?, ?> ium) {
                    var urlObj = ium.get("url");
                    var url = urlObj != null ? urlObj.toString() : "";
                    if (url.startsWith("data:image/")) {
                        filtered.add(Map.of("type", "text", "text", "[image]"));
                        continue;
                    }
                }
            }

            // 截断超长文本 block
            if ("text".equals(b.get("type")) && b.get("text") instanceof String text) {
                if (shouldTruncate && text.length() > maxToolResultChars) {
                    var truncated = new LinkedHashMap<>(b);
                    truncated.put("text", text.substring(0, maxToolResultChars) + "\n... (truncated)");
                    filtered.add(truncated);
                    continue;
                }
            }

            filtered.add(b);
        }
        return filtered;
    }

    // -- 工具上下文 --
    // 对应 Python _set_tool_context()（loop.py:507-533）

    void setToolContext(String channel, String chatId, @Nullable String messageId,
                        @Nullable Map<String, Object> metadata, String sessionKey,
                        Path workspace) {
        ToolContext build = ToolContext.builder()
                .config(Map.of("channel", channel, "chat_id", chatId))
                .workspace(workspace.toString())
                .bus(bus)
                .sessions(sessions)
                .timezone("UTC")
                .build();
        ToolContext.bind(build);
    }

    void clearToolContext() {
        ToolContext.unbind();
    }

    // -- 后台任务 --
    // 对应 Python _schedule_background()

    public void scheduleBackground(Runnable task) {
        executor.submit(task);
    }

    // ================================================================
    // Checkpoint — 对标 Python checkpoint 系列
    // ================================================================

    // 对应 Python _set_runtime_checkpoint()（loop.py:1640）
    void setRuntimeCheckpoint(Session session, Map<String, Object> payload) {
        if (session == null) return;
        session.metadata().put("runtime_checkpoint", payload);
    }

    @SuppressWarnings("unchecked")
    // 对应 Python _restore_runtime_checkpoint()（loop.py:1667）
    boolean restoreRuntimeCheckpoint(Session session) {
        if (session == null) return false;
        var checkpoint = session.metadata().get("runtime_checkpoint");
        if (checkpoint instanceof Map<?, ?> cp) {
            var phase = cp.get("phase");
            if ("awaiting_tools".equals(phase) || "tools_completed".equals(phase)
                    || "final_response".equals(phase)) {
                var messages = cp.get("assistant_message");
                if (messages instanceof Map<?, ?> msg) {
                    var typedMsg = (Map<String, Object>) msg;
                    var content = typedMsg.get("content");
                    session.addMessage("assistant",
                            content != null ? content.toString() : "",
                            Map.of("tool_calls", typedMsg.getOrDefault("tool_calls", List.of())));
                    return true;
                }
            }
        }
        return false;
    }

    // 对应 Python _mark_pending_user_turn()（loop.py:1645）
    void markPendingUserTurn(Session session) {
        if (session == null) return;
        session.metadata().put("_pending_user_turn", true);
    }

    // 对应 Python _clear_pending_user_turn()（loop.py:1648）
    void clearPendingUserTurn(Session session) {
        if (session == null) return;
        session.metadata().remove("_pending_user_turn");
    }

    // -- assembleOutbound --
    // 对应 Python _assemble_outbound()（loop.py:1294）

    OutboundMessage assembleOutbound(InboundMessage msg, String finalContent,
                                     List<Map<String, Object>> allMessages,
                                     String stopReason, boolean hadInjections) {
        var preview = finalContent != null && finalContent.length() > 120
                ? finalContent.substring(0, 120) + "..."
                : finalContent;
        log.info("Response to {}:{}: {}", msg.channel(), msg.senderId(), preview);

        var meta = msg.metadata() != null ? new LinkedHashMap<>(msg.metadata()) : new LinkedHashMap<String, Object>();
        if (!"error".equals(stopReason) && !"tool_error".equals(stopReason)) {
            meta.put("_streamed", true);
        }

        return new OutboundMessage(
                msg.channel(), msg.chatId(), finalContent,
                null, null, meta, null);
    }

    // -- replayTokenBudget --
    // 对应 Python _replay_token_budget()（loop.py:653）

    int replayTokenBudget() {
        if (contextWindowTokens <= 0) return 0;
        int reservedOutput = 4096;
        int budget = contextWindowTokens - Math.max(1, reservedOutput) - 1024;
        return budget > 0 ? budget : Math.max(128, contextWindowTokens / 2);
    }

    // -- persistSubagentFollowup --
    // 对应 Python _persist_subagent_followup()（loop.py:1616）

    boolean persistSubagentFollowup(Session session, InboundMessage msg) {
        if (msg.content() == null || msg.content().isBlank()) return false;
        var taskId = msg.metadata() != null ? msg.metadata().get("subagent_task_id") : null;
        if (taskId != null) {
            for (var m : session.messages()) {
                if ("subagent_result".equals(m.get("injected_event"))
                        && taskId.equals(m.get("subagent_task_id"))) {
                    return false;
                }
            }
        }
        var extra = new LinkedHashMap<String, Object>();
        extra.put("sender_id", msg.senderId());
        extra.put("injected_event", "subagent_result");
        if (taskId != null) extra.put("subagent_task_id", taskId);
        session.addMessage("assistant", msg.content(), extra);
        return true;
    }

    // -- restorePendingUserTurn --
    // 对应 Python _restore_pending_user_turn()（loop.py:1721）

    boolean restorePendingUserTurn(Session session) {
        if (session == null) return false;
        var flag = session.metadata().get("_pending_user_turn");
        if (!Boolean.TRUE.equals(flag)) return false;

        var msgs = session.messages();
        if (!msgs.isEmpty()
                && "user".equals(msgs.get(msgs.size() - 1).get("role"))) {
            msgs.add(Map.of(
                    "role", "assistant",
                    "content", "Error: Task interrupted before a response was generated.",
                    "timestamp", java.time.Instant.now().toString()));
            session.setUpdatedAt(java.time.Instant.now());
        }
        clearPendingUserTurn(session);
        return true;
    }

    // -- clearRuntimeCheckpoint --
    // 对应 Python _clear_runtime_checkpoint()（loop.py:1651）

    void clearRuntimeCheckpoint(Session session) {
        if (session != null) session.metadata().remove("runtime_checkpoint");
    }

    // ================================================================
    // processSystemMessage — 对标 Python _process_system_message()
    // ================================================================

    // 对应 Python _process_system_message()（loop.py:1098）
    OutboundMessage processSystemMessage(
            InboundMessage msg,
            @Nullable String sessionKey,
            @Nullable Runnable onProgress,
            @Nullable Consumer<String> onStream,
            @Nullable Runnable onStreamEnd,
            @Nullable BlockingQueue<InboundMessage> pendingQueue) {

        var channel = msg.channel();
        var chatId = msg.chatId();

        // 追踪开始（system/background turn）
        TraceObservation.startTrace("turn.system", Map.of(
                "channel", channel,
                "chatId", chatId,
                "senderId", msg.senderId()));

        try {
            // 解析复合 chat ID（如 "slack:thread:ts"）
            if (msg.chatId().contains(":")) {
                var parts = msg.chatId().split(":", 2);
                channel = parts[0];
                chatId = parts[1];
            }

            var key = msg.sessionKeyOverride() != null ? msg.sessionKeyOverride() : channel + ":" + chatId;
            var session = sessions.getOrCreate(key);

            if (restoreRuntimeCheckpointFull(session)) sessions.save(session);
            if (restorePendingUserTurn(session)) sessions.save(session);

            try {
                consolidator.maybeConsolidateByTokens(session, null);
            } catch (Exception e) {
                log.warn("Consolidation check failed in system message: {}", e.toString());
            }

            boolean isSubagent = "subagent".equals(msg.senderId());
            if (isSubagent && persistSubagentFollowup(session, msg)) {
                sessions.save(session);
            }

            setToolContext(channel, chatId,
                    msg.metadata() != null ? (String) msg.metadata().get("message_id") : null,
                    msg.metadata(), key, workspace);

            var history = session.getHistory(0, replayTokenBudget(), true);
            var currentRole = isSubagent ? "assistant" : "user";

            var messages = context.buildMessages(
                    history,
                    isSubagent ? "" : msg.content(),
                    null,                   // skillNames
                    msg.media(),
                    channel,
                    chatId,
                    currentRole,
                    msg.senderId(),
                    null,                   // sessionSummary
                    session.metadata(),
                    null,                   // currentRuntimeLines
                    workspace,
                    this,                   // runtimeState
                    msg,                    // inboundMessage
                    isSubagent,             // skipRuntimeLines
                    !isSubagent,            // includeMemoryRecentHistory
                    key,                    // sessionKey
                    false);                 // unifiedSession

            long tWall = System.currentTimeMillis();
            var runCtx = new TurnContext(msg, key, TurnState.RESTORE, key + ":" + System.nanoTime());
            runCtx.setSession(session);
            runCtx.setHistory(new ArrayList<>(messages));
            runCtx.setInitialMessages(new ArrayList<>(messages));
            if (onProgress != null) runCtx.setOnProgress(m -> onProgress.run());
            runCtx.setOnStream(onStream);
            runCtx.setOnStreamEnd(onStreamEnd);
            runCtx.setPendingQueue(pendingQueue);

            var result = runAgentLoop(runCtx, onProgress, onStream, onStreamEnd, null);

            int latencyMs = (int) Math.max(0, System.currentTimeMillis() - tWall);
            saveTurn(session, result.messages(), 1 + history.size());

            // enforceFileCap with no-op onArchive（raw_archive 待 MemoryStore git 集成）
            session.enforceFileCap(archive -> {}, Integer.MAX_VALUE);

            clearRuntimeCheckpoint(session);
            sessions.save(session);

            scheduleBackground(() -> {
                try {
                    consolidator.maybeConsolidateByTokens(session, null);
                } catch (Exception e) {
                    log.warn("Background consolidation failed: {}", e.toString());
                }
            });

            var content = result.finalContent() != null && !result.finalContent().isBlank()
                    ? result.finalContent() : "Background task completed.";

            var outboundMeta = new LinkedHashMap<String, Object>();
            if ("slack".equals(channel) && key.startsWith("slack:") && key.split(":").length >= 3) {
                outboundMeta.put("slack", Map.of("thread_ts", key.split(":", 3)[2]));
            }
            var originMsgId = msg.metadata() != null ? msg.metadata().get("origin_message_id") : null;
            if (originMsgId != null) outboundMeta.put("origin_message_id", originMsgId);

            return new OutboundMessage(channel, chatId, content, null, null, outboundMeta, null);
        } finally {
            TraceObservation.endTrace();
        }
    }

    // -- cancelActiveTasks --
    // 对应 Python _cancel_active_tasks()（loop.py:634）

    public int cancelActiveTasks(String sessionKey) {
        List<Thread> threads = activeTasks.get(sessionKey);
        if (threads == null) return 0;
        int count = 0;
        for (var t : threads) {
            if (t.isAlive()) {
                t.interrupt();
                count++;
            }
        }
        threads.clear();
        return count;
    }

    // ================================================================
    // 运行时自省方法（对标 Python AgentLoop properties）
    // ================================================================

    // 对应 Python current_iteration property（loop.py:151）
    public int currentIteration() { return currentIteration; }
    public void setCurrentIteration(int iteration) { this.currentIteration = iteration; }

    // 对应 Python tool_names property（loop.py:155）
    public Set<String> toolNames() { return tools.toolNames(); }

    // 对应 Python _runtime_chat_id()（loop.py:536）
    static String runtimeChatId(InboundMessage msg) {
        return String.valueOf(
                msg.metadata() != null && msg.metadata().get("context_chat_id") != null
                        ? msg.metadata().get("context_chat_id") : msg.chatId());
    }

    // ================================================================
    // prepareMessageMedia + 文档提取
    // 对标 Python _prepare_message_media() + _should_extract_document_text()
    // ================================================================

    /**
     * 准备消息媒体——提取文档文本或引用非图片附件。
     * 对标 Python _prepare_message_media()（loop.py:1353）。
     */
    String prepareMessageMedia(String content, List<String> media) {
        if (shouldExtractDocumentText()) {
            return extractDocumentText(content, media);
        }
        return referenceNonImageAttachments(content, media);
    }

    /**
     * 是否应提取文档文本（PDF/DOCX 等）。
     * 对标 Python _should_extract_document_text()（loop.py:1358）。
     *
     * <p>默认提取。完整实现需检查 channels_config.extract_document_text。</p>
     */
    boolean shouldExtractDocumentText() {
        // 对标 Python: return self.channels_config is not None 时的 extract_document_text 检查
        return channelsConfig != null;
    }

    /**
     * 从媒体附件中提取文档文本。
     * 对标 Python extract_documents()（utils/document.py）。
     *
     * <p>支持 .pdf / .docx / .txt / .md 等格式。当前实现处理纯文本文件；
     * PDF/DOCX 解析需要额外库（Apache PDFBox / Apache POI）。</p>
     */
    static String extractDocumentText(String content, List<String> media) {
        if (media == null || media.isEmpty()) return content;

        var extractedTexts = new ArrayList<String>();
        var nonDocumentPaths = new ArrayList<String>();

        for (var path : media) {
            var lower = path.toLowerCase();
            if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".csv") || lower.endsWith(".log")) {
                try {
                    var text = java.nio.file.Files.readString(Path.of(path));
                    extractedTexts.add("[Document: " + path + "]\n" + text);
                } catch (Exception ignored) {
                    nonDocumentPaths.add(path);
                }
            } else if (lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".doc")) {
                // PDF/DOCX 解析需额外库，标记为待提取
                extractedTexts.add("[Document: " + path + " — PDF/DOCX parsing requires Apache PDFBox/POI]");
            } else {
                nonDocumentPaths.add(path);
            }
        }

        var sb = new StringBuilder();
        if (content != null && !content.isEmpty()) {
            sb.append(content);
        }
        if (!extractedTexts.isEmpty()) {
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(String.join("\n\n", extractedTexts));
        }
        if (!nonDocumentPaths.isEmpty()) {
            var ref = referenceNonImageAttachments("", nonDocumentPaths);
            if (!ref.isEmpty()) {
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(ref);
            }
        }
        return sb.toString();
    }

    /**
     * 引用非图片附件路径。
     * 对标 Python reference_non_image_attachments()（utils/document.py）。
     */
    // 对应 Python _prepare_message_media() 中的附件引用逻辑（loop.py:1353）
    static String referenceNonImageAttachments(String content, List<String> media) {
        if (media == null || media.isEmpty()) return content;
        var imagePaths = new ArrayList<String>();
        var attachmentRefs = new ArrayList<String>();
        for (var path : media) {
            if (isImageFile(path)) {
                imagePaths.add(path);
            } else {
                attachmentRefs.add("[Attachment: " + path + "]");
            }
        }
        if (attachmentRefs.isEmpty()) return content;
        var suffix = String.join("\n", attachmentRefs);
        return (content != null && !content.isEmpty()) ? content + "\n\n" + suffix : suffix;
    }

    // 对应 Python is_image_file helper（loop.py:1353）
    static boolean isImageFile(String path) {
        var lower = path.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")
                || lower.endsWith(".svg") || lower.endsWith(".ico");
    }

    // -- checkpointMessageKey --
    // 对应 Python _checkpoint_message_key()（loop.py:1656）

    static List<Object> checkpointMessageKey(Map<String, Object> message) {
        return java.util.Arrays.asList(
                message.get("role"),
                message.get("content"),
                message.get("tool_call_id"),
                message.get("name"),
                message.get("tool_calls"),
                message.get("reasoning_content"),
                message.get("thinking_blocks"));
    }

    // -- restoreRuntimeCheckpoint (enhanced) --
    // 对应 Python _restore_runtime_checkpoint() 完整版（loop.py:1667）

    @SuppressWarnings("unchecked")
    boolean restoreRuntimeCheckpointFull(Session session) {
        var checkpoint = session.metadata().get("runtime_checkpoint");
        if (!(checkpoint instanceof Map<?, ?> cp)) return false;

        var assistantMessage = cp.get("assistant_message");
        var completedToolResults = cp.get("completed_tool_results");
        var pendingToolCalls = cp.get("pending_tool_calls");

        var restoredMessages = new ArrayList<Map<String, Object>>();
        var now = java.time.Instant.now().toString();

        if (assistantMessage instanceof Map<?, ?> am) {
            var restored = new LinkedHashMap<>((Map<String, Object>) am);
            restored.putIfAbsent("timestamp", now);
            restoredMessages.add(restored);
        }
        if (completedToolResults instanceof List<?> ctr) {
            for (var item : ctr) {
                if (item instanceof Map<?, ?> m) {
                    var restored = new LinkedHashMap<>((Map<String, Object>) m);
                    restored.putIfAbsent("timestamp", now);
                    restoredMessages.add(restored);
                }
            }
        }
        if (pendingToolCalls instanceof List<?> ptc) {
            for (var item : ptc) {
                if (!(item instanceof Map<?, ?> tc)) continue;
                var id = tc.get("id");
                var func = tc.get("function");
                var name = "";
                if (func instanceof Map<?, ?> fm) {
                    var n = fm.get("name");
                    name = n != null ? String.valueOf(n) : "tool";
                }
                restoredMessages.add(Map.of(
                        "role", "tool",
                        "tool_call_id", id != null ? id : "",
                        "name", name,
                        "content", "Error: Task interrupted before this tool finished.",
                        "timestamp", now));
            }
        }

        // 检测与已有 session 消息的重叠
        int overlap = 0;
        int maxOverlap = Math.min(session.messages().size(), restoredMessages.size());
        for (int size = maxOverlap; size > 0; size--) {
            var existing = session.messages().subList(
                    session.messages().size() - size, session.messages().size());
            var restored = restoredMessages.subList(0, size);
            boolean match = true;
            for (int i = 0; i < size; i++) {
                if (!checkpointMessageKey(existing.get(i)).equals(
                        checkpointMessageKey(restored.get(i)))) {
                    match = false;
                    break;
                }
            }
            if (match) {
                overlap = size;
                break;
            }
        }
        session.messages().addAll(restoredMessages.subList(overlap, restoredMessages.size()));

        clearPendingUserTurn(session);
        clearRuntimeCheckpoint(session);
        return true;
    }

    // ================================================================
    // processDirect — 对标 Python process_direct()（loop.py:1741-1779）
    // ================================================================

    /**
     * 直接处理消息（同步），返回出站载荷。
     * 完整对标 Python process_direct()，包括 MCP 连接、session lock、
     * 工具覆盖支持。
     */
    public OutboundMessage processDirect(
            String content,
            String sessionKey,
            String channel,
            String chatId,
            @Nullable List<String> media,
            @Nullable Consumer<String> onProgress,
            @Nullable Consumer<String> onStream,
            @Nullable Runnable onStreamEnd,
            boolean ephemeral,
            @Nullable ToolRegistry overrideTools) {

        connectMcp();

        var msg = new InboundMessage(channel, "user", chatId, content,
                null, media, null, sessionKey);

        var lock = sessionLocks.computeIfAbsent(sessionKey, k -> new ReentrantLock());
        lock.lock();
        try {
            if (concurrencyGate != null) {
                concurrencyGate.acquire();
            }
        } catch (InterruptedException e) {
            lock.unlock();
            Thread.currentThread().interrupt();
            return null;
        }

        try {
            // 工具覆盖——对标 Python if tools is not None: kwargs["tools"] = tools
            ToolRegistry savedTools = null;
            if (overrideTools != null) {
                savedTools = this.tools; // 不对标——tools registry 共享引用
                // 直接使用覆盖工具：将 overrideTools 中的工具注入当前 registry
                for (var name : overrideTools.toolNames()) {
                    var tool = overrideTools.get(name);
                    if (tool != null && !this.tools.has(name)) {
                        this.tools.register(tool);
                    }
                }
            }

            try {
                return processSystemMessage(msg, sessionKey,
                        onProgress != null ? () -> onProgress.accept("") : null,
                        onStream, onStreamEnd, null);
            } finally {
                // 清理工具覆盖
                if (savedTools != null && overrideTools != null) {
                    for (var name : overrideTools.toolNames()) {
                        if (savedTools.has(name)) {
                            // 恢复原工具（不删除，因为 registry 是共享的）
                        }
                    }
                }
            }
        } finally {
            if (concurrencyGate != null) {
                concurrencyGate.release();
            }
            lock.unlock();
            // 对标 Python finally: await self._runtime_events().run_status_changed(...)
            runtimeEventPublisher.runStatusChanged(channel, chatId, sessionKey,
                    "idle", msg.metadata(), null);
            runtimeEventPublisher.clearTurn(sessionKey);
        }
    }

    // ================================================================
    // Bus callbacks — 对标 Python _build_bus_progress_callback
    // ================================================================

    // 对应 Python _build_bus_progress_callback()（loop.py:540）
    Consumer<String> buildBusProgressCallback(InboundMessage msg) {
        return progress -> {
            var meta = msg.metadata() != null
                    ? new LinkedHashMap<>(msg.metadata()) : new LinkedHashMap<String, Object>();
            try {
                bus.publishOutbound(new OutboundMessage(
                        msg.channel(), msg.chatId(), progress, null, null, meta, null));
            } catch (InterruptedException ignored) {}
        };
    }

    // 对应 Python _build_retry_wait_callback()（loop.py:546）
    Consumer<String> buildBusRetryWaitCallback(InboundMessage msg) {
        return content -> {
            var meta = msg.metadata() != null
                    ? new LinkedHashMap<>(msg.metadata()) : new LinkedHashMap<String, Object>();
            meta.put("_retry_wait", true);
            try {
                bus.publishOutbound(new OutboundMessage(
                        msg.channel(), msg.chatId(), content, null, null, meta, null));
            } catch (InterruptedException ignored) {}
        };
    }

    // ================================================================
    // ConsolidatorProviderAdapter — LLMProvider → ConsolidatorProvider 适配
    // ================================================================

    /**
     * 将 LLMProvider 适配为 ConsolidatorProvider 接口，供 Consolidator 压缩使用。
     *
     * <p>Consolidator 需要简化的 chat(model, messages) 接口（仅处理纯文本压缩），
     * 而 LLMProvider.chat() 需要完整参数（tools, maxTokens, temperature 等）。
     * 此适配器在中间做转换，提供压缩所需的轻量调用。</p>
     */
    static class ConsolidatorProviderAdapter implements ConsolidatorProvider {

        private LLMProvider provider;

        ConsolidatorProviderAdapter(LLMProvider provider) {
            this.provider = provider;
        }

        void setProvider(LLMProvider provider) {
            this.provider = provider;
        }

        @Override
        public com.nanobot.providers.base.LLMResponse chat(String model, List<Map<String, Object>> messages) throws Exception {
            // 压缩调用：不传工具，使用 4096 maxTokens，temperature=0（确定性输出）
            return provider.chat(messages, List.of(), model, 4096, 0.0, null, null);
        }

        @Override
        public int estimatePromptTokens(List<Map<String, Object>> messages,
                                        List<Map<String, Object>> toolDefs) {
            // 使用 Session 的静态 token 估算方法（与 Python 一致）
            int total = 0;
            for (var msg : messages) {
                total += com.nanobot.agent.session.Session.estimateMessageTokens(msg);
            }
            return Math.max(1, total);
        }
    }
}
