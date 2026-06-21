# 14 — cli + command 包：CLI 入口与命令路由

**对标 Python：** `nanobot/cli/commands.py` (2,043行), `cli/stream.py` (231行), `cli/models.py` (32行), `cli/onboard.py` (1,385行), `command/router.py` (~50行), `command/builtin.py` (719行)

## Python 源码分析

### CLI 整体架构

```
cli/commands.py (2,043行) — 主入口
├── Typer app 定义 + @app.command() 装饰的子命令
├── main() callback — 显示 help 信息
├── onboard() — 初始化配置 + 交互式向导
├── serve() — OpenAI 兼容 API 服务器
├── gateway() — 主 gateway 启动
├── desktop_gateway() — 桌面应用专用 gateway
├── agent() — 交互式 / 单次消息模式 (核心)
├── channels status/login — 频道状态/登录
├── plugins list — 插件列表
├── provider login/logout — OAuth 认证
└── status — 显示 bot 状态
```

### `cli/commands.py` 关键路径

#### main() callback
```
Typer callback: --version / -v → 显示版本并退出
```

#### agent() — 交互模式（核心路径）

```
1. load_config → AgentLoop.from_config()
2. 检查 env restart_notice → display if applicable

交互模式 (无 --message):
  - _init_prompt_session() → prompt_toolkit PromptSession + SafeFileHistory
  - signal handlers (SIGINT, SIGTERM, SIGHUP)
  - async loop:
    - _read_interactive_input_async() → input
    - _is_exit_command() 检查 exit/quit
    - publish InboundMessage to bus (channel="cli", chat_id)
    - wait turn_done Event
    - _print_agent_response() or StreamRenderer

单消息模式 (有 --message):
  - agent_loop.process_direct(message, session_id, on_progress, on_stream, on_stream_end)
  - StreamRenderer 输出 + 最终 _print_agent_response()
```

#### 输出系统

```
StreamRenderer: Rich Live 实现 in-place streaming 更新
  - on_delta(delta): 追加到 buffer, Rich Live 更新
  - on_end(): 停止 Live, 输出最终 Markdown

ThinkingSpinner: Rich status spinner
  - pause() 上下文管理器 (暂停 spinner 打印 trace 行)

_reasoning_buffer: 缓冲 reasoning 内容, 按句子/flush_interval 刷新
  - _print_cli_reasoning(): [dim italic]✻ text[/dim italic]
  - _print_cli_progress_line(): [dim]↳ text[/dim]
```

### `cli/stream.py` — 输出渲染

```
StreamRenderer:
  - render_markdown, show_spinner, bot_name, bot_icon
  - _buf: 累积的 deltas
  - streamed: 是否已输出任何 delta
  - _live: Rich Live (transient=True)
  - _spinner: ThinkingSpinner
  - on_delta(delta): 首次 → 启动 Live; 后续 → update
  - on_end(resuming=False): 停止 Live, 打印最终内容
  - pause_spinner(): 暂停 transient 输出允许 trace 行
  - close(): 干净停止不做最终渲染
  - stop_for_input(): 用户输入前停止 spinner (避免 prompt_toolkit 冲突)

ThinkingSpinner:
  - console.status("[dim]{bot_name} is thinking...[/dim]", spinner="dots")
  - pause() ctx manager: 临时停止/恢复
```

### `cli/models.py` — CLI 数据辅助

```
get_all_models() → list[str]           # 空列表 (stub)
find_model_info(model_name) → dict     # None (stub)
get_model_context_limit(model, provider) → int  # None (stub)
get_model_suggestions(partial, provider, limit) → list[str]  # 空 (stub)
format_token_count(tokens) → str       # 格式化数字
```

### `cli/onboard.py` — 交互式向导

```
run_onboard(initial_config) → OnboardResult:
  - 使用 questionary 库
  - 逐步询问: provider, api_key, model, bot_name, workspace, 默认设置
  - 支持 back 导航
  - 返回 OnboardResult(config, should_save)
```

### `command/router.py` — 命令路由器

```python
class CommandRouter:
    # 三层路由 (按优先级):
    #  1. priority — 精确匹配, dispatch_lock 之前执行 (如 /stop, /restart)
    #  2. exact    — 精确匹配, dispatch_lock 之内执行
    #  3. prefix   — 最长前缀优先匹配 (如 "/model ")

    _priority: dict[str, Handler]      # cmd → handler
    _exact: dict[str, Handler]         # cmd → handler
    _prefix: list[(str, Handler)]      # 按 prefix 长度降序排列

    priority(cmd, handler)             # 注册 priority 命令
    exact(cmd, handler)                # 注册 exact 命令
    prefix(pfx, handler)              # 注册 prefix 命令 (自动按长度排序)

    is_priority(text) → bool           # 是否匹配 priority 层
    is_dispatchable_command(text) → bool  # 是否匹配 exact/prefix 层 (不含 priority)

    dispatch_priority(ctx) → OutboundMessage | None   # 从 run() 无锁调用
    dispatch(ctx) → OutboundMessage | None             # 从 turn loop 有锁调用
```

`CommandContext`:
```python
@dataclass
class CommandContext:
    msg: InboundMessage       # 原始入站消息
    session: Session | None   # 当前 session (可能为 None，如正在运行时)
    key: str                  # session key
    raw: str                  # 原始命令文本 (不含 /)
    args: str = ""            # 前缀命令的剩余参数
    loop: Any = None          # AgentLoop 引用
```

### `command/builtin.py` — 内置命令

```
注册表:
  priority:
    /stop, /restart, /status
  exact:
    /new, /status, /model, /history, /goal, /dream, /dream-log,
    /dream-restore, /skill, /help, /pairing
  prefix:
    /model <args>, /history <n>, /goal <desc>, /dream-log <sha>,
    /dream-restore <sha>, /pairing <args>

命令处理概览:
  /stop       → loop._cancel_active_tasks(key) → "Stopped N task(s)."
  /restart    → set_restart_notice_to_env(), sleep 1s, os.execv()
  /status     → 构建 markdown 状态报告 (model, version, uptime, usage, tasks...)
  /new        → cancel tasks, clear session, archive snapshot → "New session started."
  /model [preset] → 无参: show current; 有参: switch preset
  /history [n] → 显示最后 N 条对话 (默认10, 最大50)
  /goal <desc> → 重写为 agent turn (注入 long_task prompt)
  /dream      → 手动触发 Dream consolidation (异步)
  /dream-log [sha] → 查看 Dream commit diff
  /dream-restore [sha] → 恢复 Dream memory
  /skill      → 列出可用 skills
  /help       → 列出所有 slash command
  /pairing [subcmd] → 管理配对请求 (list/approve/deny/revoke)
```

---

## Java 实现方案

### 1. 整体策略

Python 的 Typer CLI → Java Spring Shell（首选）或 Picocli。

选择 **Spring Shell** 的原因：
- 与 Spring Boot 深度集成，自动 DI
- 内置 interactive shell mode（对标 Python agent 交互模式）
- 内置 command 注册/分派系统（可对标 `CommandRouter`）
- 支持 `--help`、参数验证

备用方案：如果 Spring Shell 的交互模式不够灵活（对标 prompt_toolkit），可以用 Picocli 做主 CLI + 自建 readline 交互循环。

```
Python: typer + prompt_toolkit
Java:   Spring Shell + JLine3 (Spring Shell 默认使用 JLine3)

Python: CommandRouter (三层路由表)
Java:   CommandRouter (独立组件，供 agent loop 使用)
         + Spring Shell @ShellComponent for CLI 版本
```

### 2. `CliCommands.java` — 主 CLI 入口

使用 Spring Shell 实现所有子命令。对标 Python `app.command()` 注册的各个子命令。

```java
package com.nanobot.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jline.reader.LineReader;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;
import org.springframework.shell.standard.commands.Quit;

import com.nanobot.NanobotVersion;
import com.nanobot.agent.AgentLoop;
import com.nanobot.bus.MessageBus;
import com.nanobot.bus.events.InboundMessage;
import com.nanobot.bus.events.OutboundMessage;
import com.nanobot.channels.ChannelManager;
import com.nanobot.config.ConfigLoader;
import com.nanobot.config.NanobotProperties;
import com.nanobot.cron.CronService;
import com.nanobot.session.SessionManager;

/**
 * CLI entry point — 对标 Python cli/commands.py.
 * Spring Shell @ShellComponent provides all subcommands.
 */
@ShellComponent
public class CliCommands implements Quit.Command {

    private static final Logger log = LoggerFactory.getLogger(CliCommands.class);
    private static final Set<String> EXIT_COMMANDS =
        Set.of("exit", "quit", "/exit", "/quit", ":q");

    private final NanobotProperties config;
    private final MessageBus bus;
    private final SessionManager sessionManager;
    private final AgentLoop agentLoop;
    private final ChannelManager channelManager;
    private final CronService cronService;
    private final StreamRenderer streamRenderer;

    public CliCommands(
            NanobotProperties config,
            MessageBus bus,
            SessionManager sessionManager,
            AgentLoop agentLoop,
            ChannelManager channelManager,
            CronService cronService,
            StreamRenderer streamRenderer) {
        this.config = config;
        this.bus = bus;
        this.sessionManager = sessionManager;
        this.agentLoop = agentLoop;
        this.channelManager = channelManager;
        this.cronService = cronService;
        this.streamRenderer = streamRenderer;
    }

    // ---------------------------------------------------------------
    // 对标 Python onboard() — 初始化配置
    // ---------------------------------------------------------------

    @ShellMethod(key = "onboard", value = "Initialize nanobot configuration and workspace.")
    public String onboard(
            @ShellOption(arity = 1, defaultValue = ShellOption.NULL)
            String workspace,
            @ShellOption(arity = 1, defaultValue = ShellOption.NULL)
            String configPath,
            @ShellOption(defaultValue = "false")
            boolean wizard) {

        // 对标 Python onboard():
        // 1. 确定 config path
        // 2. 创建或更新 Config
        // 3. 如果 wizard → 启动交互式 OnboardWizard
        // 4. 创建 workspace 目录
        // 5. sync_workspace_templates
        // 6. 输出下一步指引

        Path resolvedConfigPath = ConfigLoader.getConfigPath(configPath);
        NanobotProperties resolvedConfig;

        if (Files.exists(resolvedConfigPath)) {
            if (wizard) {
                resolvedConfig = ConfigLoader.loadConfig(resolvedConfigPath);
                if (workspace != null) {
                    // Apply workspace override
                    // resolvedConfig.agents().defaults().setWorkspace(workspace);
                }
            } else {
                // Config exists, prompt for overwrite
                return "Config already exists at " + resolvedConfigPath
                    + ". Use --wizard to reconfigure interactively.";
            }
        } else {
            resolvedConfig = new NanobotProperties(/* defaults */);
            if (!wizard) {
                ConfigLoader.saveConfig(resolvedConfig, resolvedConfigPath);
            }
        }

        if (wizard) {
            OnboardResult result = OnboardWizard.run(resolvedConfig);
            if (!result.shouldSave()) {
                return "Configuration discarded. No changes were saved.";
            }
            resolvedConfig = result.config();
            ConfigLoader.saveConfig(resolvedConfig, resolvedConfigPath);
        }

        // Create workspace
        Path wsPath = Path.of(resolvedConfig.agents().defaults().workspace());
        try {
            Files.createDirectories(wsPath);
        } catch (IOException e) {
            return "Error creating workspace: " + e.getMessage();
        }

        // Sync templates
        // WorkspaceTemplateSyncer.sync(wsPath);

        return "nanobot is ready!\n"
            + "  Chat: nanobot agent -m \"Hello!\"\n"
            + "  Gateway: nanobot gateway";
    }

    // ---------------------------------------------------------------
    // 对标 Python serve() — API 服务器
    // ---------------------------------------------------------------

    @ShellMethod(key = "serve", value = "Start the OpenAI-compatible API server.")
    public String serve(
            @ShellOption(arity = 1, defaultValue = ShellOption.NULL)
            Integer port,
            @ShellOption(arity = 1, defaultValue = ShellOption.NULL)
            String host,
            @ShellOption(defaultValue = "120.0")
            Double timeout,
            @ShellOption(defaultValue = "false")
            boolean verbose) {

        int resolvedPort = port != null ? port : config.api().port();
        String resolvedHost = host != null ? host : config.api().host();
        String modelName = config.agents().defaults().model();

        // Start API server (separate process or blocking call)
        // OpenAiApiServer.start(resolvedHost, resolvedPort, timeout, agentLoop, modelName);

        return String.format(
            "Starting OpenAI-compatible API server\n"
            + "  Endpoint: http://%s:%d/v1/chat/completions\n"
            + "  Model: %s\n"
            + "  Session: api:default\n"
            + "  Timeout: %.1fs",
            resolvedHost, resolvedPort, modelName, timeout
        );
    }

    // ---------------------------------------------------------------
    // 对标 Python gateway() — Gateway 启动
    // ---------------------------------------------------------------

    @ShellMethod(key = "gateway", value = "Start the nanobot gateway.")
    public String gateway(
            @ShellOption(arity = 1, defaultValue = ShellOption.NULL)
            Integer port,
            @ShellOption(arity = 1, defaultValue = ShellOption.NULL)
            String workspace,
            @ShellOption(defaultValue = "false")
            boolean verbose) {

        int resolvedPort = port != null ? port : config.gateway().port();
        String modelName = config.agents().defaults().model();

        return String.format(
            "Starting nanobot gateway on port %d...\n"
            + "  Model: %s\n"
            + "  WebUI: http://127.0.0.1:%d",
            resolvedPort, modelName, resolvedPort
        );

        // 实际启动由 Spring Boot Web 服务器处理 (NanobotApplication 已启动 HTTP)
        // 此命令输出状态信息
    }

    // ---------------------------------------------------------------
    // 对标 Python agent() — 交互模式 (核心)
    // ---------------------------------------------------------------

    @ShellMethod(key = "agent", value = "Interact with the agent directly.")
    public void agent(
            @ShellOption(arity = 1, defaultValue = ShellOption.NULL)
            String message,
            @ShellOption(defaultValue = "cli:direct")
            String session,
            @ShellOption(arity = 1, defaultValue = ShellOption.NULL)
            String workspace,
            @ShellOption(defaultValue = "true")
            boolean markdown,
            @ShellOption(defaultValue = "false")
            boolean logs) {

        String sessionId = session;
        if (!sessionId.contains(":")) {
            sessionId = "cli:" + sessionId;
        }

        if (message != null && !message.isEmpty()) {
            // 单次消息模式
            runOnce(message, sessionId, markdown);
        } else {
            // 交互模式
            runInteractive(sessionId, markdown);
        }
    }

    // --- 单次消息模式 (对标 Python agent -m "message") ---

    private void runOnce(String message, String sessionId, boolean markdown) {
        StreamRenderer renderer = new StreamRenderer(
            markdown,
            true,
            config.agents().defaults().botName(),
            config.agents().defaults().botIcon()
        );

        try {
            agentLoop.processDirect(
                message,
                sessionId,
                renderer::onProgress,
                renderer::onDelta,
                renderer::onEnd
            );

            if (!renderer.isStreamed()) {
                renderer.close();
                // Print final response from loop result
            }
        } catch (Exception e) {
            log.error("Agent processing failed", e);
        }
    }

    // --- 交互模式 (对标 Python agent 交互循环) ---

    private void runInteractive(String sessionId, boolean markdown) {
        String[] parts = sessionId.split(":", 2);
        String cliChannel = parts[0];
        String cliChatId = parts.length > 1 ? parts[1] : sessionId;

        String modelName = config.agents().defaults().model();
        System.out.println("nanobot Interactive mode (" + modelName
            + ") -- type 'exit' or Ctrl+C to quit");
        System.out.println();

        // 启动 agent loop (在虚拟线程中)
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        executor.submit(agentLoop::run);

        Terminal terminal = JLineUtils.getTerminal();
        LineReader reader = JLineUtils.createLineReader(terminal);

        try {
            while (true) {
                // Read input
                String userInput = reader.readLine("You: ");
                if (userInput == null) {
                    break; // EOF
                }
                String command = userInput.strip();
                if (command.isEmpty()) {
                    continue;
                }
                if (EXIT_COMMANDS.contains(command.toLowerCase())) {
                    System.out.println("Goodbye!");
                    break;
                }

                // Publish to bus
                StreamRenderer renderer = new StreamRenderer(
                    markdown,
                    true,
                    config.agents().defaults().botName(),
                    config.agents().defaults().botIcon()
                );

                InboundMessage inbound = new InboundMessage(
                    cliChannel,
                    "user",
                    cliChatId,
                    userInput,
                    Map.of("_wants_stream", true)
                );

                bus.publishInbound(inbound);

                // Wait for turn completion (via blocking consume)
                OutboundMessage response = consumeOutboundResponse(bus, renderer, markdown);

                if (response != null && !response.metadata().containsKey("_streamed")) {
                    renderer.close();
                    printAgentResponse(response.content(), markdown, response.metadata());
                }
            }
        } catch (Exception e) {
            log.error("Interactive mode error", e);
        } finally {
            agentLoop.stop();
            executor.shutdown();
        }
    }

    private OutboundMessage consumeOutboundResponse(
            MessageBus bus, StreamRenderer renderer, boolean markdown) {
        // 对标 Python interactive 中的 _consume_outbound()
        // Blocking consume from bus with timeout
        try {
            OutboundMessage msg = bus.consumeOutbound(java.time.Duration.ofSeconds(1));

            if (msg.metadata().containsKey("_stream_delta")) {
                renderer.onDelta(msg.content());
                return null;
            }
            if (msg.metadata().containsKey("_stream_end")) {
                renderer.onEnd(false);
                return null;
            }
            if (msg.metadata().containsKey("_streamed")) {
                return null;
            }

            return msg;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // ---------------------------------------------------------------
    // 对标 Python status()
    // ---------------------------------------------------------------

    @ShellMethod(key = "status", value = "Show nanobot status.")
    public String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("nanobot Status\n\n");

        Path configPath = ConfigLoader.getConfigPath(null);
        sb.append("Config: ").append(configPath)
            .append(Files.exists(configPath) ? " [OK]" : " [MISSING]").append("\n");

        Path wsPath = Path.of(config.agents().defaults().workspace());
        sb.append("Workspace: ").append(wsPath)
            .append(Files.exists(wsPath) ? " [OK]" : " [MISSING]").append("\n");

        String model = config.agents().defaults().model();
        sb.append("Model: ").append(model).append("\n");

        return sb.toString();
    }

    // ---------------------------------------------------------------
    // 辅助方法
    // ---------------------------------------------------------------

    private void printAgentResponse(String response, boolean markdown,
            Map<String, Object> metadata) {
        String botIcon = config.agents().defaults().botIcon();
        String botName = config.agents().defaults().botName();

        System.out.println();
        if (botIcon != null && !botIcon.isEmpty()) {
            System.out.println(botIcon + " " + botName);
        } else {
            System.out.println(botName);
        }

        String content = response != null ? response : "";
        System.out.println(content);
        System.out.println();
    }

    // implement Quit.Command
    @ShellMethod(key = {"quit", "exit"}, value = "Exit the shell.")
    public void quit() {
        System.exit(0);
    }
}
```

### 3. `CommandRouter.java` — 命令路由器

完全对标 Python `command/router.py` 的三层路由表。

```java
package com.nanobot.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.nanobot.bus.events.OutboundMessage;

/**
 * Pure map-based command dispatch with three-tier priority routing.
 * 对标 Python CommandRouter.
 *
 * Three tiers checked in order:
 *   1. priority — exact-match commands handled before the dispatch lock
 *      (e.g., /stop, /restart)
 *   2. exact — exact-match commands handled inside the dispatch lock
 *   3. prefix — longest-prefix-first match (e.g., "/model ")
 */
public class CommandRouter {

    /** Command handler functional interface. */
    @FunctionalInterface
    public interface CommandHandler {
        CompletableFuture<OutboundMessage> handle(CommandContext ctx);
    }

    private final Map<String, CommandHandler> priorityHandlers;
    private final Map<String, CommandHandler> exactHandlers;
    private final List<PrefixEntry> prefixHandlers;

    public CommandRouter() {
        this.priorityHandlers = new HashMap<>();
        this.exactHandlers = new HashMap<>();
        this.prefixHandlers = new ArrayList<>();
    }

    // --- Registration ---

    /**
     * Register a priority handler (checked before dispatch lock).
     * 对标 Python router.priority(cmd, handler).
     */
    public void priority(String cmd, CommandHandler handler) {
        this.priorityHandlers.put(cmd.toLowerCase(), handler);
    }

    /**
     * Register an exact-match handler (checked inside dispatch lock).
     * 对标 Python router.exact(cmd, handler).
     */
    public void exact(String cmd, CommandHandler handler) {
        this.exactHandlers.put(cmd.toLowerCase(), handler);
    }

    /**
     * Register a prefix-match handler. Handlers sorted by prefix length descending.
     * 对标 Python router.prefix(pfx, handler).
     */
    public void prefix(String prefix, CommandHandler handler) {
        this.prefixHandlers.add(new PrefixEntry(prefix.toLowerCase(), handler));
        this.prefixHandlers.sort(
            Comparator.comparingInt((PrefixEntry e) -> e.prefix.length()).reversed()
        );
    }

    // --- Lookup ---

    /**
     * Check if text matches a priority command.
     * 对标 Python is_priority().
     */
    public boolean isPriority(String text) {
        return this.priorityHandlers.containsKey(text.strip().toLowerCase());
    }

    /**
     * Check if text matches any non-priority command tier (exact or prefix).
     * Does NOT check priority tier.
     * 对标 Python is_dispatchable_command().
     */
    public boolean isDispatchableCommand(String text) {
        String cmd = text.strip().toLowerCase();
        if (this.exactHandlers.containsKey(cmd)) {
            return true;
        }
        for (PrefixEntry entry : this.prefixHandlers) {
            if (cmd.startsWith(entry.prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Dispatch a priority command. Called from run() without the lock.
     * 对标 Python dispatch_priority().
     */
    public CompletableFuture<OutboundMessage> dispatchPriority(CommandContext ctx) {
        CommandHandler handler = this.priorityHandlers.get(ctx.raw().toLowerCase());
        if (handler != null) {
            return handler.handle(ctx);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Try exact, then prefix handlers. Returns null if unhandled.
     * 对标 Python dispatch().
     */
    public CompletableFuture<OutboundMessage> dispatch(CommandContext ctx) {
        String cmd = ctx.raw().toLowerCase();

        // Exact match first
        CommandHandler handler = this.exactHandlers.get(cmd);
        if (handler != null) {
            return handler.handle(ctx);
        }

        // Then prefix match (longest first — already sorted)
        for (PrefixEntry entry : this.prefixHandlers) {
            if (cmd.startsWith(entry.prefix)) {
                String args = ctx.raw().substring(entry.prefix.length());
                CommandContext patchedCtx = ctx.withArgs(args);
                return entry.handler.handle(patchedCtx);
            }
        }

        return CompletableFuture.completedFuture(null);
    }

    // --- Internal types ---

    private record PrefixEntry(String prefix, CommandHandler handler) {}
}
```

### 4. `CommandContext.java` — 命令上下文

```java
package com.nanobot.command;

import com.nanobot.agent.AgentLoop;
import com.nanobot.bus.events.InboundMessage;
import com.nanobot.session.Session;

/**
 * Everything a command handler needs to produce a response.
 * 对标 Python CommandContext dataclass.
 */
public record CommandContext(
    InboundMessage msg,
    Session session,    // nullable — null when a task is running
    String key,
    String raw,
    String args,
    AgentLoop loop
) {
    public CommandContext {
        if (args == null) {
            args = "";
        }
    }

    /**
     * Return a copy with overridden args (for prefix match dispatch).
     */
    public CommandContext withArgs(String newArgs) {
        return new CommandContext(msg, session, key, raw, newArgs, loop);
    }
}
```

### 5. `BuiltinCommands.java` — 内置命令

```java
package com.nanobot.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import com.nanobot.NanobotVersion;
import com.nanobot.bus.events.OutboundMessage;
import com.nanobot.utils.StatusContentBuilder;
import com.nanobot.utils.RestartNoticeUtil;

/**
 * Built-in slash command handlers.
 * 对标 Python command/builtin.py.
 */
public final class BuiltinCommands {

    private BuiltinCommands() {}

    // ---------------------------------------------------------------
    // Command specs (for UI palette)
    // ---------------------------------------------------------------

    public static final List<Map<String, String>> BUILTIN_COMMAND_SPECS = List.of(
        Map.of("command", "/new", "title", "New chat",
               "description", "Stop the current task and start a fresh conversation.",
               "icon", "square-pen"),
        Map.of("command", "/stop", "title", "Stop current task",
               "description", "Cancel the active agent turn for this chat.",
               "icon", "square"),
        Map.of("command", "/restart", "title", "Restart nanobot",
               "description", "Restart the bot process in place.",
               "icon", "rotate-cw"),
        Map.of("command", "/status", "title", "Show status",
               "description", "Display runtime, provider, and channel status.",
               "icon", "activity"),
        Map.of("command", "/model", "title", "Switch model preset",
               "description", "Show or switch the active model preset.",
               "icon", "brain", "arg_hint", "[preset]"),
        Map.of("command", "/history", "title", "Show conversation history",
               "description", "Print the last N persisted conversation messages.",
               "icon", "history", "arg_hint", "[n]"),
        Map.of("command", "/goal", "title", "Start long-running goal",
               "description", "Tell the agent to treat the request as a long-running goal.",
               "icon", "activity", "arg_hint", "<goal>"),
        Map.of("command", "/dream", "title", "Run Dream",
               "description", "Manually trigger memory consolidation.",
               "icon", "sparkles"),
        Map.of("command", "/dream-log", "title", "Show Dream log",
               "description", "Show what the last Dream consolidation changed.",
               "icon", "book-open"),
        Map.of("command", "/dream-restore", "title", "Restore memory",
               "description", "Revert memory to a previous Dream snapshot.",
               "icon", "undo-2"),
        Map.of("command", "/skill", "title", "List skills",
               "description", "List all enabled skills available to the agent.",
               "icon", "wrench"),
        Map.of("command", "/help", "title", "Show help",
               "description", "List available slash commands.",
               "icon", "circle-help"),
        Map.of("command", "/pairing", "title", "Manage pairing",
               "description", "List, approve, deny or revoke pairing requests.",
               "icon", "shield", "arg_hint", "[list|approve <code>|deny <code>|revoke <user_id>]")
    );

    /**
     * 对标 Python builtin_command_palette().
     * Return structured command metadata for UI command palettes.
     */
    public static List<Map<String, String>> builtinCommandPalette() {
        List<Map<String, String>> result = new ArrayList<>();
        for (Map<String, String> spec : BUILTIN_COMMAND_SPECS) {
            Map<String, String> entry = new java.util.LinkedHashMap<>(spec);
            entry.remove("arg_hint"); // arg_hint is internal
            result.add(entry);
        }
        return result;
    }

    // ---------------------------------------------------------------
    // Command handlers
    // Each returns CompletableFuture<OutboundMessage>.
    // 对标 Python cmd_*() async functions.
    // ---------------------------------------------------------------

    /**
     * /stop — Cancel all active tasks and subagents.
     * 对标 Python cmd_stop().
     */
    public static CompletableFuture<OutboundMessage> cmdStop(CommandContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            AgentLoop loop = ctx.loop();
            int total = loop.cancelActiveTasks(ctx.key());
            String content = total > 0
                ? "Stopped " + total + " task(s)."
                : "No active task to stop.";
            return new OutboundMessage(
                ctx.msg().channel(),
                ctx.msg().chatId(),
                content,
                ctx.msg().metadata()
            );
        });
    }

    /**
     * /restart — Restart the process in-place.
     * 对标 Python cmd_restart().
     */
    public static CompletableFuture<OutboundMessage> cmdRestart(CommandContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            RestartNoticeUtil.setRestartNoticeToEnv(
                ctx.msg().channel(),
                ctx.msg().chatId(),
                ctx.msg().metadata()
            );

            // Schedule restart after 1 second
            Executors.newSingleThreadScheduledExecutor().schedule(() -> {
                // Process restart via ProcessHandle or external mechanism
                System.exit(0);
            }, 1, java.util.concurrent.TimeUnit.SECONDS);

            return new OutboundMessage(
                ctx.msg().channel(),
                ctx.msg().chatId(),
                "Restarting...",
                ctx.msg().metadata()
            );
        });
    }

    /**
     * /status — Build an outbound status message.
     * 对标 Python cmd_status().
     */
    public static CompletableFuture<OutboundMessage> cmdStatus(CommandContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            AgentLoop loop = ctx.loop();
            Session session = ctx.session();
            if (session == null) {
                session = loop.sessions().getOrCreate(ctx.key());
            }

            String content = StatusContentBuilder.buildStatusContent(
                NanobotVersion.VERSION,
                loop.model(),
                loop.startTime(),
                loop.lastUsage(),
                loop.contextWindowTokens(),
                session.getHistory(0).size(),
                /* searchUsage */ null,
                loop.activeTaskCount(ctx.key())
            );

            Map<String, Object> metadata = new java.util.HashMap<>(ctx.msg().metadata());
            metadata.put("render_as", "text");

            return new OutboundMessage(
                ctx.msg().channel(),
                ctx.msg().chatId(),
                content,
                metadata
            );
        });
    }

    /**
     * /new — Stop active task and start a fresh session.
     * 对标 Python cmd_new().
     */
    public static CompletableFuture<OutboundMessage> cmdNew(CommandContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            AgentLoop loop = ctx.loop();
            loop.cancelActiveTasks(ctx.key());
            Session session = ctx.session();
            if (session == null) {
                session = loop.sessions().getOrCreate(ctx.key());
            }
            session.clear();
            loop.sessions().save(session);
            loop.sessions().invalidate(session.key());

            return new OutboundMessage(
                ctx.msg().channel(),
                ctx.msg().chatId(),
                "New session started.",
                ctx.msg().metadata()
            );
        });
    }

    /**
     * /model [preset] — Show or switch model presets.
     * 对标 Python cmd_model().
     */
    public static CompletableFuture<OutboundMessage> cmdModel(CommandContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            AgentLoop loop = ctx.loop();
            String args = ctx.args().strip();
            Map<String, Object> metadata = new java.util.HashMap<>(ctx.msg().metadata());
            metadata.put("render_as", "text");

            if (args.isEmpty()) {
                // Show current
                List<String> names = loop.modelPresetNames();
                String active = loop.activeModelPresetName();
                StringBuilder sb = new StringBuilder();
                sb.append("## Model\n");
                sb.append("- Current model: `").append(loop.model()).append("`\n");
                sb.append("- Current preset: `").append(active).append("`\n");
                sb.append("- Available presets: ");
                sb.append(String.join(", ", names.stream()
                    .map(n -> "`" + n + "`").toList()));
                return new OutboundMessage(
                    ctx.msg().channel(), ctx.msg().chatId(),
                    sb.toString(), metadata
                );
            }

            // Switch
            try {
                loop.setModelPreset(args);
                StringBuilder sb = new StringBuilder();
                sb.append("Switched model preset to `").append(loop.modelPreset()).append("`.\n");
                sb.append("- Model: `").append(loop.model()).append("`\n");
                sb.append("- Context window: ").append(loop.contextWindowTokens());
                return new OutboundMessage(
                    ctx.msg().channel(), ctx.msg().chatId(),
                    sb.toString(), metadata
                );
            } catch (Exception e) {
                List<String> names = loop.modelPresetNames();
                return new OutboundMessage(
                    ctx.msg().channel(), ctx.msg().chatId(),
                    "Could not switch model preset: " + e.getMessage() + "\n\n"
                        + "Available presets: " + String.join(", ",
                            names.stream().map(n -> "`" + n + "`").toList()),
                    metadata
                );
            }
        });
    }

    /**
     * /history [n] — Show the last N messages.
     * 对标 Python cmd_history().
     */
    public static CompletableFuture<OutboundMessage> cmdHistory(CommandContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            int count = 10; // default
            if (!ctx.args().strip().isEmpty()) {
                try {
                    count = Math.max(1, Math.min(
                        Integer.parseInt(ctx.args().strip()), 50));
                } catch (NumberFormatException e) {
                    return new OutboundMessage(
                        ctx.msg().channel(), ctx.msg().chatId(),
                        "Usage: /history [count] -- e.g., /history 5 (default: 10, max: 50)",
                        ctx.msg().metadata()
                    );
                }
            }

            Session session = ctx.session();
            if (session == null) {
                session = ctx.loop().sessions().getOrCreate(ctx.key());
            }
            List<Map<String, Object>> history = session.getHistory(0);
            List<String> formatted = history.stream()
                .map(BuiltinCommands::formatHistoryMessage)
                .filter(s -> s != null)
                .toList();

            List<String> recent = formatted.subList(
                Math.max(0, formatted.size() - count),
                formatted.size()
            );

            if (recent.isEmpty()) {
                return new OutboundMessage(
                    ctx.msg().channel(), ctx.msg().chatId(),
                    "No conversation history yet.",
                    ctx.msg().metadata()
                );
            }

            Map<String, Object> metadata = new java.util.HashMap<>(ctx.msg().metadata());
            metadata.put("render_as", "text");
            return new OutboundMessage(
                ctx.msg().channel(), ctx.msg().chatId(),
                "Last " + recent.size() + " message(s):\n" + String.join("\n", recent),
                metadata
            );
        });
    }

    /**
     * /goal <description> — Start a long-running goal.
     * 对标 Python cmd_goal().
     */
    public static CompletableFuture<OutboundMessage> cmdGoal(CommandContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            String goal = ctx.args().strip();
            Map<String, Object> metadata = new java.util.HashMap<>(ctx.msg().metadata());
            metadata.put("render_as", "text");

            if (goal.isEmpty()) {
                return new OutboundMessage(
                    ctx.msg().channel(), ctx.msg().chatId(),
                    "Usage: /goal <long-running task description>",
                    metadata
                );
            }

            if (ctx.session() == null) {
                return new OutboundMessage(
                    ctx.msg().channel(), ctx.msg().chatId(),
                    "A task is already running for this chat. "
                        + "Use /stop first, then send /goal again.",
                    metadata
                );
            }

            // Rewrite as a normal agent turn that nudges long_task use
            String goalPrompt = """
                The user declared a sustained objective for this thread.

                Inspect or clarify if needed, then call `long_task` with the refined objective
                (and optional short ui_summary). Work proceeds as normal assistant turns using
                your usual tools. When the objective is fully done and verified, call
                `complete_goal` with a brief recap.

                Goal:
                %s
                """.formatted(goal);

            // Return null — lets the agent loop process this as a normal turn
            ctx.msg().setContent(goalPrompt);
            ctx.msg().metadata().put("original_command", "/goal");
            ctx.msg().metadata().put("original_content", ctx.raw());
            ctx.msg().metadata().put("goal_started_at", System.currentTimeMillis() / 1000.0);

            return null; // return null = let agent loop route this as normal
        });
    }

    /**
     * /help — Return available slash commands.
     * 对标 Python cmd_help() + build_help_text().
     */
    public static CompletableFuture<OutboundMessage> cmdHelp(CommandContext ctx) {
        return CompletableFuture.completedFuture(
            new OutboundMessage(
                ctx.msg().channel(),
                ctx.msg().chatId(),
                buildHelpText(),
                Map.of("render_as", "text")
            )
        );
    }

    public static String buildHelpText() {
        StringBuilder sb = new StringBuilder("nanobot commands:\n");
        for (Map<String, String> spec : BUILTIN_COMMAND_SPECS) {
            String command = spec.get("command");
            if (spec.containsKey("arg_hint")) {
                command = command + " " + spec.get("arg_hint");
            }
            sb.append(command).append(" -- ").append(spec.get("description")).append("\n");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------
    // Registration
    // ---------------------------------------------------------------

    /**
     * Register all built-in commands on the given router.
     * 对标 Python register_builtin_commands().
     */
    public static void registerBuiltinCommands(CommandRouter router) {
        // Priority (before lock)
        router.priority("/stop", BuiltinCommands::cmdStop);
        router.priority("/restart", BuiltinCommands::cmdRestart);
        router.priority("/status", BuiltinCommands::cmdStatus);

        // Exact match (inside lock)
        router.exact("/new", BuiltinCommands::cmdNew);
        router.exact("/status", BuiltinCommands::cmdStatus);
        router.exact("/model", BuiltinCommands::cmdModel);
        router.exact("/history", BuiltinCommands::cmdHistory);
        router.exact("/goal", BuiltinCommands::cmdGoal);
        router.exact("/dream", BuiltinCommands::cmdDream);
        router.exact("/dream-log", BuiltinCommands::cmdDreamLog);
        router.exact("/dream-restore", BuiltinCommands::cmdDreamRestore);
        router.exact("/skill", BuiltinCommands::cmdSkill);
        router.exact("/help", BuiltinCommands::cmdHelp);
        router.exact("/pairing", BuiltinCommands::cmdPairing);

        // Prefix match (longest first)
        router.prefix("/model ", BuiltinCommands::cmdModel);
        router.prefix("/history ", BuiltinCommands::cmdHistory);
        router.prefix("/goal ", BuiltinCommands::cmdGoal);
        router.prefix("/dream-log ", BuiltinCommands::cmdDreamLog);
        router.prefix("/dream-restore ", BuiltinCommands::cmdDreamRestore);
        router.prefix("/pairing ", BuiltinCommands::cmdPairing);
    }

    // --- Remaining command stubs (full implementation follows same pattern) ---

    public static CompletableFuture<OutboundMessage> cmdDream(CommandContext ctx) {
        return CompletableFuture.completedFuture(
            new OutboundMessage(ctx.msg().channel(), ctx.msg().chatId(),
                "Dreaming...", ctx.msg().metadata())
        );
    }

    public static CompletableFuture<OutboundMessage> cmdDreamLog(CommandContext ctx) {
        return CompletableFuture.completedFuture(
            new OutboundMessage(ctx.msg().channel(), ctx.msg().chatId(),
                "Dream log functionality.", Map.of("render_as", "text"))
        );
    }

    public static CompletableFuture<OutboundMessage> cmdDreamRestore(CommandContext ctx) {
        return CompletableFuture.completedFuture(
            new OutboundMessage(ctx.msg().channel(), ctx.msg().chatId(),
                "Dream restore functionality.", Map.of("render_as", "text"))
        );
    }

    public static CompletableFuture<OutboundMessage> cmdSkill(CommandContext ctx) {
        return CompletableFuture.completedFuture(
            new OutboundMessage(ctx.msg().channel(), ctx.msg().chatId(),
                "Skills list.", ctx.msg().metadata())
        );
    }

    public static CompletableFuture<OutboundMessage> cmdPairing(CommandContext ctx) {
        return CompletableFuture.completedFuture(
            new OutboundMessage(ctx.msg().channel(), ctx.msg().chatId(),
                "Pairing management.", ctx.msg().metadata())
        );
    }

    // --- Formatting helpers ---

    private static final int HISTORY_MAX_CONTENT_CHARS = 200;

    private static String formatHistoryMessage(Map<String, Object> msg) {
        String role = (String) msg.get("role");
        if (!"user".equals(role) && !"assistant".equals(role)) {
            return null;
        }
        Object contentObj = msg.get("content");
        String content;
        if (contentObj instanceof List<?> list) {
            StringBuilder parts = new StringBuilder();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m
                        && "text".equals(m.get("type"))
                        && m.get("text") instanceof String text) {
                    parts.append(text).append(" ");
                }
            }
            content = parts.toString();
        } else {
            content = contentObj != null ? contentObj.toString() : "";
        }
        content = content.strip();
        if (content.isEmpty()) {
            return null;
        }
        if (content.length() > HISTORY_MAX_CONTENT_CHARS) {
            content = content.substring(0, HISTORY_MAX_CONTENT_CHARS) + "...";
        }
        String label = "user".equals(role) ? "You" : "Bot";
        return label + ": " + content;
    }
}
```

### 6. `StreamRenderer.java` — 终端输出格式化

完全对标 Python `cli/stream.py`。

```java
package com.nanobot.cli;

import java.io.PrintStream;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Streaming renderer for CLI output.
 * 对标 Python StreamRenderer + ThinkingSpinner.
 *
 * Flow per round:
 *   spinner → first delta → header + updates →
 *   on_end → final render
 */
public class StreamRenderer implements AutoCloseable {

    private final boolean renderMarkdown;
    private final boolean showSpinner;
    private final String botName;
    private final String botIcon;
    private final PrintStream out;

    private StringBuilder buffer;
    private boolean streamed;
    private boolean headerPrinted;
    private boolean liveActive;
    private boolean spinnerActive;
    private final Lock renderLock;

    private static final String ANSI_RESET = "[0m";
    private static final String ANSI_CYAN = "[36m";
    private static final String ANSI_DIM = "[2m";
    private static final String ANSI_BOLD = "[1m";
    private static final String ANSI_ITALIC = "[3m";
    private static final String ANSI_CLEAR_LINE = "\r[2K";

    public StreamRenderer(boolean renderMarkdown, boolean showSpinner,
            String botName, String botIcon) {
        this.renderMarkdown = renderMarkdown;
        this.showSpinner = showSpinner;
        this.botName = botName != null ? botName : "nanobot";
        this.botIcon = botIcon != null ? botIcon : "";
        this.out = System.out;
        this.buffer = new StringBuilder();
        this.streamed = false;
        this.headerPrinted = false;
        this.liveActive = false;
        this.spinnerActive = false;
        this.renderLock = new ReentrantLock();

        if (this.showSpinner) {
            startSpinner();
        }
    }

    // --- Spinner (对标 ThinkingSpinner) ---

    private void startSpinner() {
        this.spinnerActive = true;
        out.print(ANSI_DIM + botName + " is thinking..." + ANSI_RESET);
        out.flush();
    }

    private void stopSpinner() {
        if (spinnerActive) {
            out.print(ANSI_CLEAR_LINE);
            out.flush();
            spinnerActive = false;
        }
    }

    /**
     * Context manager analog: pause spinner for external output.
     * Returns a handle that restores spinner in close().
     */
    public PausedSpinnerContext pauseSpinner() {
        boolean wasActive = spinnerActive;
        stopSpinner();
        return new PausedSpinnerContext(wasActive, this);
    }

    public static class PausedSpinnerContext implements AutoCloseable {
        private final boolean wasActive;
        private final StreamRenderer renderer;

        PausedSpinnerContext(boolean wasActive, StreamRenderer renderer) {
            this.wasActive = wasActive;
            this.renderer = renderer;
        }

        @Override
        public void close() {
            if (wasActive) {
                renderer.startSpinner();
            }
        }
    }

    // --- Properties ---

    public boolean isStreamed() {
        return streamed;
    }

    public boolean isHeaderPrinted() {
        return headerPrinted;
    }

    // --- Rendering ---

    public void ensureHeader() {
        stopSpinner();
        if (headerPrinted) {
            return;
        }
        out.println();
        String header = botIcon.isEmpty()
            ? botName
            : botIcon + " " + botName;
        out.println(ANSI_CYAN + header + ANSI_RESET);
        headerPrinted = true;
    }

    /**
     * 对标 Python on_delta(delta).
     * Receive a streaming delta and update in-place.
     */
    public void onDelta(String delta) {
        renderLock.lock();
        try {
            streamed = true;
            buffer.append(delta);
            if (!buffer.toString().strip().isEmpty()) {
                ensureHeader();
                out.print(delta);
                out.flush();
            }
        } finally {
            renderLock.unlock();
        }
    }

    /**
     * 对标 Python on_end(resuming).
     * Stop live display and print final rendered content.
     */
    public void onEnd(boolean resuming) {
        renderLock.lock();
        try {
            stopSpinner();
            liveActive = false;

            if (!buffer.toString().strip().isEmpty()) {
                out.println(); // final newline
                out.flush();
            }
            if (resuming) {
                buffer.setLength(0);
                if (showSpinner) {
                    startSpinner();
                }
            }
        } finally {
            renderLock.unlock();
        }
    }

    /**
     * 对标 Python stop_for_input().
     * Stop spinner before user input to avoid conflicts.
     */
    public void stopForInput() {
        stopSpinner();
    }

    /**
     * 对标 Python close().
     * Clean stop without rendering a final streamed round.
     */
    @Override
    public void close() {
        stopSpinner();
        liveActive = false;
    }

    // --- Progress output (对标 _print_cli_progress_line / _print_cli_reasoning) ---

    /**
     * Print a CLI progress line, pausing spinner if needed.
     * 对标 Python _print_cli_progress_line().
     */
    public void printProgressLine(String text) {
        try (PausedSpinnerContext ctx = pauseSpinner()) {
            ensureHeader();
            out.println("  " + ANSI_DIM + "-> " + text + ANSI_RESET);
        }
    }

    /**
     * Print reasoning/thinking content in a distinct style.
     * 对标 Python _print_cli_reasoning().
     */
    public void printReasoning(String text) {
        try (PausedSpinnerContext ctx = pauseSpinner()) {
            ensureHeader();
            out.println(ANSI_DIM + ANSI_ITALIC + "* " + text + ANSI_RESET);
        }
    }
}
```

### 7. `ReasoningBuffer.java` — 推理内容缓冲

对标 Python `_ReasoningBuffer`。

```java
package com.nanobot.cli;

/**
 * Accumulates reasoning/thinking text and flushes on sentence boundaries
 * or when a character threshold is reached.
 * 对标 Python _ReasoningBuffer.
 */
public class ReasoningBuffer {

    private static final String[] REASONING_SENTENCE_ENDINGS =
        {".", "!", "?", "。", "！", "？"};
    private static final int REASONING_FLUSH_CHARS = 60;

    private final StringBuilder text;

    public ReasoningBuffer() {
        this.text = new StringBuilder();
    }

    /**
     * Add text and return flushed content if the flush threshold is reached.
     * Returns null if nothing to flush yet.
     */
    public String add(String delta) {
        if (delta == null || delta.isEmpty()) {
            return null;
        }
        text.append(delta);
        if (shouldFlush(delta)) {
            return flush();
        }
        return null;
    }

    /**
     * Flush accumulated text. Returns null if empty.
     */
    public String flush() {
        String result = text.toString().strip();
        text.setLength(0);
        return result.isEmpty() ? null : result;
    }

    /**
     * Clear without returning.
     */
    public void clear() {
        text.setLength(0);
    }

    private boolean shouldFlush(String delta) {
        if (delta.contains("\n")) {
            return true;
        }
        String stripped = delta.stripTrailing();
        for (String ending : REASONING_SENTENCE_ENDINGS) {
            if (stripped.endsWith(ending)) {
                return true;
            }
        }
        return text.length() >= REASONING_FLUSH_CHARS;
    }
}
```

### 8. `OnboardWizard.java` — 交互式向导

对标 Python `cli/onboard.py`。使用 JLine3 的终端能力实现交互式选择。

```java
package com.nanobot.cli;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import com.nanobot.config.NanobotProperties;

/**
 * Interactive onboarding wizard.
 * 对标 Python cli/onboard.py.
 *
 * Walks the user through:
 *   1. Provider selection (anthropic, openai, openrouter, ...)
 *   2. API key entry
 *   3. Model selection
 *   4. Bot name / icon
 *   5. Workspace path
 *   6. Default settings (temperature, max_tokens, etc.)
 */
public final class OnboardWizard {

    private OnboardWizard() {}

    private static final List<ProviderChoice> PROVIDER_CHOICES = List.of(
        new ProviderChoice("anthropic", "Anthropic (Claude)"),
        new ProviderChoice("openai", "OpenAI (GPT)"),
        new ProviderChoice("openrouter", "OpenRouter (multi-provider)"),
        new ProviderChoice("deepseek", "DeepSeek"),
        new ProviderChoice("groq", "Groq"),
        new ProviderChoice("gemini", "Google Gemini"),
        new ProviderChoice("zhipu", "Zhipu (GLM)"),
        new ProviderChoice("ollama", "Ollama (local)"),
        new ProviderChoice("vllm", "vLLM (local)"),
        new ProviderChoice("custom", "Custom (generic OpenAI-compatible)")
    );

    // Back navigation sentinel
    private static final Object BACK = new Object();

    public static OnboardResult run(NanobotProperties initialConfig) {
        Terminal terminal;
        try {
            terminal = TerminalBuilder.builder()
                .system(true)
                .dumb(true) // fallback to dumb terminal if no TTY
                .build();
        } catch (IOException e) {
            throw new RuntimeException("Cannot initialize terminal for onboarding", e);
        }

        LineReader reader = LineReaderBuilder.builder()
            .terminal(terminal)
            .build();

        // Configuration accumulator
        OnboardConfigBuilder builder = new OnboardConfigBuilder(initialConfig);

        try {
            // Step 1: Provider
            String provider = selectProvider(reader, builder);
            if (provider == null) {
                return new OnboardResult(builder.build(), false); // cancelled
            }
            builder.setProvider(provider);

            // Step 2: API Key
            String apiKey = promptApiKey(reader, provider);
            if (apiKey == null) {
                return new OnboardResult(builder.build(), false);
            }
            builder.setApiKey(provider, apiKey);

            // Step 3: Model
            String model = promptModel(reader, provider);
            if (model == null) {
                return new OnboardResult(builder.build(), false);
            }
            builder.setModel(model);

            // Step 4: Bot name
            String botName = promptText(reader,
                "Bot name", builder.getBotName(),
                "The name the agent uses when responding"
            );
            if (botName == null) return new OnboardResult(builder.build(), false);
            builder.setBotName(botName);

            // Step 5: Workspace
            String workspace = promptText(reader,
                "Workspace directory", builder.getWorkspace(),
                "Directory for session files, memory, and tools"
            );
            if (workspace == null) return new OnboardResult(builder.build(), false);
            builder.setWorkspace(workspace);

            // Step 6: Review & confirm
            boolean confirmed = confirm(reader, builder);
            return new OnboardResult(builder.build(), confirmed);

        } catch (Exception e) {
            throw new RuntimeException("Onboarding failed", e);
        }
    }

    // --- Interactive prompts ---

    private static String selectProvider(LineReader reader, OnboardConfigBuilder builder) {
        System.out.println("\nSelect your LLM provider:");
        System.out.println("(Use arrow keys to navigate, Enter to select, Ctrl+C to cancel)\n");

        for (int i = 0; i < PROVIDER_CHOICES.size(); i++) {
            ProviderChoice choice = PROVIDER_CHOICES.get(i);
            System.out.printf("  [%d] %s%n", i + 1, choice.displayName());
        }
        System.out.println();

        String input = reader.readLine("Provider number (1-" + PROVIDER_CHOICES.size()
            + "): ");
        if (input == null || input.isBlank()) {
            return null;
        }
        try {
            int idx = Integer.parseInt(input.strip()) - 1;
            if (idx >= 0 && idx < PROVIDER_CHOICES.size()) {
                return PROVIDER_CHOICES.get(idx).name();
            }
        } catch (NumberFormatException e) {
            System.out.println("Invalid selection.");
            return null;
        }
        return null;
    }

    private static String promptApiKey(LineReader reader, String provider) {
        System.out.println("\nAPI Key for " + provider + ":");
        System.out.println("(Your key is never logged or shared. "
            + "Get one from your provider's dashboard.)\n");

        String apiKey = reader.readLine("API Key: ", '*'); // masked input
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("API key is required.");
            return null;
        }
        return apiKey.strip();
    }

    private static String promptModel(LineReader reader, String provider) {
        String defaultModel = DEFAULT_MODELS.getOrDefault(provider, "");
        String prompt = "Model name";
        if (!defaultModel.isEmpty()) {
            prompt += " (default: " + defaultModel + ")";
        }
        prompt += ": ";

        String input = reader.readLine(prompt);
        if (input == null) {
            return null; // Ctrl+C
        }
        input = input.strip();
        return input.isEmpty() ? defaultModel : input;
    }

    private static String promptText(LineReader reader, String label,
            String defaultValue, String hint) {
        String prompt = label;
        if (defaultValue != null && !defaultValue.isEmpty()) {
            prompt += " [" + defaultValue + "]";
        }
        prompt += ": ";

        String input = reader.readLine(prompt);
        if (input == null) return null;
        input = input.strip();
        return input.isEmpty() ? defaultValue : input;
    }

    private static boolean confirm(LineReader reader, OnboardConfigBuilder builder) {
        System.out.println("\n=== Configuration Summary ===");
        System.out.println("Provider:  " + builder.getProvider());
        System.out.println("Model:     " + builder.getModel());
        System.out.println("Bot Name:  " + builder.getBotName());
        System.out.println("Workspace: " + builder.getWorkspace());
        System.out.println("==============================\n");

        String input = reader.readLine("Save this configuration? [Y/n]: ");
        if (input == null) {
            return false;
        }
        input = input.strip().toLowerCase();
        return input.isEmpty() || input.startsWith("y");
    }

    // --- Data types ---

    private record ProviderChoice(String name, String displayName) {}

    private static final java.util.Map<String, String> DEFAULT_MODELS = Map.of(
        "anthropic", "anthropic/claude-sonnet-4-6",
        "openai", "openai/gpt-4.1",
        "openrouter", "openrouter/anthropic/claude-sonnet-4-6",
        "deepseek", "deepseek/deepseek-chat",
        "groq", "groq/mixtral-8x7b-32768",
        "gemini", "gemini/gemini-2.5-pro",
        "zhipu", "zhipu/glm-4",
        "ollama", "ollama/llama3",
        "vllm", "hosted_vllm/mistral",
        "custom", ""
    );

    /**
     * Onboarding session result — 对标 Python OnboardResult.
     */
    public record OnboardResult(NanobotProperties config, boolean shouldSave) {}
}
```

### 9. `OnboardResult.java` 与 `OnboardConfigBuilder.java` — 配置构建器

```java
package com.nanobot.cli;

import com.nanobot.config.NanobotProperties;

/**
 * Result of an onboarding session.
 * 对标 Python OnboardResult dataclass.
 */
public record OnboardResult(
    NanobotProperties config,
    boolean shouldSave
) {}
```

### 10. `SanitizeSurrogates.java` — 输入清理

对标 Python `_sanitize_surrogates()`。

```java
package com.nanobot.cli;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Reconstruct surrogate pairs into real characters; replace lone surrogates.
 * 对标 Python _sanitize_surrogates().
 *
 * On Windows, console input may produce lone surrogate code points
 * (e.g., \\uD83D\\uDC08 for U+1F408). Round-tripping through UTF-16
 * reconstructs paired surrogates into their actual characters and
 * replaces unpaired ones with U+FFFD.
 */
public final class SanitizeSurrogates {

    private SanitizeSurrogates() {}

    /**
     * 对标 Python: text.encode("utf-16-le", errors="surrogatepass")
     *                 .decode("utf-16-le", errors="replace")
     */
    public static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        byte[] utf16Bytes = text.getBytes(StandardCharsets.UTF_16LE);
        return new String(utf16Bytes, StandardCharsets.UTF_16LE);
    }
}
```

### 11. `JLineUtils.java` — 终端初始化

```java
package com.nanobot.cli;

import java.io.IOException;
import java.nio.file.Path;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import com.nanobot.config.AppPaths;

/**
 * JLine3 terminal and LineReader initialization utilities.
 * 对标 Python prompt_toolkit PromptSession + SafeFileHistory.
 */
public final class JLineUtils {

    private JLineUtils() {}

    private static Terminal terminal;
    private static LineReader reader;

    /**
     * Initialize the global JLine terminal.
     * 对标 Python _init_prompt_session().
     */
    public static Terminal getTerminal() {
        if (terminal == null) {
            synchronized (JLineUtils.class) {
                if (terminal == null) {
                    try {
                        terminal = TerminalBuilder.builder()
                            .system(true)
                            .name("nanobot")
                            .build();
                    } catch (IOException e) {
                        throw new RuntimeException(
                            "Failed to initialize terminal", e);
                    }
                }
            }
        }
        return terminal;
    }

    /**
     * Create a JLine3 LineReader with persistent history.
     * 对标 Python PromptSession + SafeFileHistory.
     */
    public static LineReader createLineReader(Terminal terminal) {
        if (reader == null) {
            synchronized (JLineUtils.class) {
                if (reader == null) {
                    Path historyFile = AppPaths.cliHistoryPath();
                    try {
                        java.nio.file.Files.createDirectories(historyFile.getParent());
                    } catch (IOException e) {
                        // ignore — history will be in-memory only
                    }

                    DefaultHistory history = new DefaultHistory();
                    reader = LineReaderBuilder.builder()
                        .terminal(terminal)
                        .history(history)
                        .variable(LineReader.HISTORY_FILE,
                            historyFile.toAbsolutePath())
                        .build();
                }
            }
        }
        return reader;
    }

    /**
     * Restore terminal to original state.
     * 对标 Python _restore_terminal().
     */
    public static void restoreTerminal() {
        if (terminal != null) {
            try {
                terminal.close();
            } catch (IOException e) {
                // ignore
            }
            terminal = null;
            reader = null;
        }
    }
}
```

### 12. Java 类映射总结表

| Python 文件 | Java 类 | 包路径 |
|---|---|---|
| `cli/commands.py` | `CliCommands` (@ShellComponent) | `com.nanobot.cli` |
| `cli/stream.py` | `StreamRenderer` | `com.nanobot.cli` |
| `cli/stream.py` | `ReasoningBuffer` | `com.nanobot.cli` |
| `cli/stream.py` | `ThinkingSpinner` (merged into StreamRenderer) | `com.nanobot.cli` |
| `cli/models.py` | `CliModels` (utility) | `com.nanobot.cli` |
| `cli/onboard.py` | `OnboardWizard` (utility) | `com.nanobot.cli` |
| `cli/onboard.py` | `OnboardResult` (record) | `com.nanobot.cli` |
| `cli/onboard.py` | `OnboardConfigBuilder` | `com.nanobot.cli` |
| `cli/commands.py` (signal handlers) | `SignalHandlers` (utility) | `com.nanobot.cli` |
| `cli/commands.py` (sanitize) | `SanitizeSurrogates` (utility) | `com.nanobot.cli` |
| `cli/commands.py` (terminal) | `JLineUtils` (utility) | `com.nanobot.cli` |
| `command/router.py` | `CommandRouter` | `com.nanobot.command` |
| `command/router.py` | `CommandContext` (record) | `com.nanobot.command` |
| `command/builtin.py` | `BuiltinCommands` (utility) | `com.nanobot.command` |
| `command/builtin.py` | `StatusContentBuilder` (utility) | `com.nanobot.utils` |

### 13. 关键设计决策

#### CLI 框架选择
选择 **Spring Shell** 作为主 CLI 框架，JLine3 作为底层终端库。Spring Shell 内置于 Spring Boot 生态，提供 `@ShellComponent`、`@ShellMethod`、`@ShellOption` 注解，对标 Python Typer 的声明式命令注册。

#### 交互模式
Spring Shell 默认提供交互式 REPL。对标 Python agent 的交互循环 (`run_interactive()`)，通过 JLine3 的 `LineReader` 实现：
- 多行粘贴（bracketed paste mode）
- 历史导航（上下箭头）
- 信号处理（`UserInterruptException` 替代 Python `signal.SIGINT`）
- 输入前自动停止 spinner（对标 `patch_stdout`）

#### 虚拟线程聊天循环
对标 Python `asyncio.create_task()` 的并发模式：
```java
// Python: bus_task = asyncio.create_task(agent_loop.run())
// Java:   Thread.ofVirtual().start(agentLoop::run);

// Python: outbound_task = asyncio.create_task(_consume_outbound())
// Java:   Thread.ofVirtual().start(this::consumeOutbound);
```

虚拟线程在 `bus.consumeOutbound()` 阻塞时自动让出 OS 线程，等价于 Python `await bus.consume_outbound()`。

#### CommandRouter 独立性
`CommandRouter` 保持在单独的 `com.nanobot.command` 包中，独立于 Spring Shell。两者共存：
- `CommandRouter` 用于 agent loop 内部的 `/` 命令处理（从 Telegram/Discord/CLI 等消息通道调用）
- Spring Shell 命令用于 CLI 工具本身的子命令（`gateway`, `onboard`, `status` 等）

#### 终端输出
不使用 Rich 库（Python 专用）。直接用 ANSI 转义序列:
- `[36m` → 青色 (对标 Rich `[cyan]`)
- `[2m` → 暗色 (对标 Rich `[dim]`)
- `[3m` → 斜体 (对标 Rich `[italic]`)
- `[1m` → 加粗 (对标 Rich `[bold]`)

### 14. 验证标准

```bash
# 1. CLI help
java -jar nanobot-java.jar --help
# 预期: Spring Shell help 输出，列出所有子命令

# 2. Version
java -jar nanobot-java.jar --version
# 预期: nanobot v1.0.0

# 3. Agent 单次消息
java -jar nanobot-java.jar agent -m "Hello, what is 2+2?"
# 预期: 流式输出 agent 响应

# 4. Agent 交互模式
echo "What is 2+2?" | java -jar nanobot-java.jar agent
# 预期: 正常交互会话，按 EOF 退出

# 5. Onboard wizard
TERM=dumb java -jar nanobot-java.jar onboard --wizard
# 预期: text-based 交互式引导

# 6. Gateway start (with webui)
java -jar nanobot-java.jar gateway
# 预期: gateway 启动信息，HTTP 服务器在 18790 端口

# 7. Status
java -jar nanobot-java.jar status
# 预期: 配置/workspace/model 状态

# 8. CommandRouter 单元测试
# JUnit: 注册 /stop, /restart, /model, /help 等命令
# assert isPriority("/stop") == true
# assert isDispatchableCommand("/model gpt-5") == true
# assert dispatchPriority(stopCtx) returns "Stopped N task(s)"
# assert dispatch(modelCtx) with empty args shows current model

# 9. Built-in commands 单元测试
# JUnit: 每个 cmd_* handler 的输入/输出测试
# verify builtinCommandPalette() returns 13 commands
```

### 15. 代码量估算

| Java 文件 | 行数 |
|---|---|
| `CliCommands.java` (@ShellComponent) | ~350 |
| `StreamRenderer.java` | ~180 |
| `ReasoningBuffer.java` | ~50 |
| `CliModels.java` (utility) | ~30 |
| `OnboardWizard.java` (utility) | ~200 |
| `OnboardResult.java` (record) | ~10 |
| `OnboardConfigBuilder.java` | ~80 |
| `JLineUtils.java` (utility) | ~80 |
| `SanitizeSurrogates.java` (utility) | ~25 |
| `SignalHandlers.java` (utility) | ~30 |
| `CommandRouter.java` | ~100 |
| `CommandContext.java` (record) | ~25 |
| `BuiltinCommands.java` (utility) | ~350 |
| `StatusContentBuilder.java` (utility) | ~80 |
| `RestartNoticeUtil.java` (utility) | ~50 |
| **合计** | **~1,640** |
