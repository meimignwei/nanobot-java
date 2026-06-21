# 01 — Root 包：项目入口与 SDK 门面

**对标 Python：** `nanobot/__main__.py` (8行), `nanobot/__init__.py` (48行), `nanobot/nanobot.py` (113行)

> **源码即真理。** 以下分析基于 `/Users/mmw/PycharmProjects/nanobot/nanobot/` 目录下的原始 Python 源码。
> > **最高原则：和源码保持一致。** Python 中是 `async def` 的，Java 中保留异步语义（`CompletableFuture`）；Python 中是 `def` 的，Java 中保持同步。

---

## Python 源码分析

### `__main__.py` — 模块入口点（同步）

```python
from nanobot.cli.commands import app

if __name__ == "__main__":
    app()
```

职责单一：导入 Typer CLI 实例 `app` 并直接调用。不启动 Web 服务器、不启动 Gateway——那些由 CLI 子命令（`serve`、`gateway`、`agent`）按需创建。

### `__init__.py` — 包初始化与惰性导出（同步）

```python
__logo__ = "🐈"

def _resolve_version() -> str:
    # 1. 优先 importlib.metadata.version("nanobot-ai")
    # 2. 回退 pyproject.toml (tomllib)
    # 3. 最终兜底 "0.2.1"

__version__ = _resolve_version()

_LAZY_EXPORTS = {"Nanobot": ".nanobot", "RunResult": ".nanobot"}

def __getattr__(name: str):
    # 惰性加载：首次访问时才 import 对应模块
    # 加载后缓存到 globals()

__all__ = ["Nanobot", "RunResult"]
```

关键点：
- `__getattr__` 实现模块级惰性加载，避免循环导入和启动时全量加载
- 版本获取有**三级回退链**：pkg metadata → pyproject.toml → 硬编码兜底
- `__logo__` 是包级字符串常量
- 只导出 `Nanobot` 和 `RunResult` 两个符号

### `nanobot.py` — Nanobot SDK 门面（混合 async/sync）

```python
@dataclass(slots=True)
class RunResult:
    content: str
    tools_used: list[str]
    messages: list[dict[str, Any]]

class Nanobot:
    def __init__(self, loop: AgentLoop) -> None:           # 同步
        self._loop = loop

    @classmethod
    def from_config(cls, config_path=None, *, workspace=None) -> Nanobot:  # 同步
        # 1. load_config(config_path) → Config
        # 2. resolve_config_env_vars() → 原地修改并返回同一实例
        # 3. 可选覆盖 workspace（直接修改 config.agents.defaults.workspace）
        # 4. AgentLoop.from_config(config, image_generation_provider_configs=...)
        # 5. return cls(loop)

    async def run(self, message, *, session_key="sdk:default", hooks=None) -> RunResult:  # 异步
        # 1. 创建 SDKCaptureHook 收集 tools_used + messages
        # 2. 直接操作 self._loop._extra_hooks（私有字段，无 getter/setter）
        # 3. await loop.process_direct(message, session_key=session_key)
        #    - process_direct 内部按 session_key 使用 asyncio.Lock() 串行化
        # 4. 恢复原始 _extra_hooks（无论 process_direct 成功或失败）
        # 5. 返回 RunResult(content, tools_used, messages)

    async def aclose(self) -> None:  # 异步
        await self._loop.close_mcp()
        # close_mcp 内部会 await asyncio.gather(*background_tasks)
        # 以及 await stack.aclose()（每个 MCP 连接的异步清理）

    async def __aenter__(self) -> Nanobot: ...   # 异步
    async def __aexit__(self, *exc) -> None: ...  # 异步
```

关键点：
- `__init__` 和 `from_config()` 是**同步**的
- `run()` / `aclose()` / `__aenter__` / `__aexit__` 是**异步**的
- `run()` 直接读写 `self._loop._extra_hooks`**私有字段**（不是 getter/setter）
- `process_direct()` 内部有**按 session_key 的 asyncio.Lock()** 串行化
- `run()` 的 `try/finally` 保证无论 `process_direct` 成功或失败，hooks 都会被恢复
- `aclose()` 内部涉及多个 `await` 点

### `agent/hook.py` — SDKCaptureHook（异步）

```python
class SDKCaptureHook(AgentHook):
    def __init__(self) -> None:          # 同步
        self.tools_used: list[str] = []
        self.messages: list[dict[str, Any]] = []

    async def after_iteration(self, context: AgentHookContext) -> None:  # 异步
        for call in context.tool_calls:
            self.tools_used.append(call.name)
        self.messages = list(context.messages)

    async def after_run(self, context: AgentRunHookContext) -> None:  # 异步
        self.tools_used = list(context.tools_used)
        self.messages = list(context.messages)
```

- `__init__` 是同步的，`after_iteration` / `after_run` 是**异步**的
- `after_run` 提供的 `tools_used` 是运行器汇总后的权威列表
- `nanobot.py` 的 `run()` 方法在 `process_direct` 返回后读取 `capture.tools_used`

### `agent/loop.py` — AgentLoop 核心（混合 async/sync）

```python
class AgentLoop:
    @classmethod
    def from_config(cls, config, bus=None, **extra) -> AgentLoop:  # 同步
        ...

    async def run(self) -> None:  # 异步（长期运行任务）
        self._running = True
        await self._connect_mcp()
        while self._running:
            msg = await asyncio.wait_for(self.bus.consume_inbound(), timeout=1.0)
            ...

    async def process_direct(self, content, session_key="cli:direct", ...) -> OutboundMessage | None:  # 异步
        await self._connect_mcp()
        lock = self._session_locks.setdefault(session_key, asyncio.Lock())
        async with lock:
            return await self._process_message(msg, ...)

    async def close_mcp(self) -> None:  # 异步
        await asyncio.gather(*self._background_tasks, return_exceptions=True)
        for name, stack in self._mcp_stacks.items():
            await stack.aclose()
```

关键点：
- `from_config()` 是**同步**的 classmethod
- `run()` 是**异步**长期运行任务，由调用方创建为后台任务
- `process_direct()` 是**异步**的，内部有 `asyncio.Lock()` 串行化
- `close_mcp()` 是**异步**的，内部 `await` 多个清理操作

### `bus/queue.py` — MessageBus（异步 I/O）

```python
class MessageBus:
    def __init__(self) -> None: ...           # 同步
    async def publish_inbound(self, msg) -> None: ...   # 异步
    async def consume_inbound(self) -> InboundMessage: ...  # 异步
    async def publish_outbound(self, msg) -> None: ...  # 异步
    async def consume_outbound(self) -> OutboundMessage: ...  # 异步
    def inbound_size(self) -> int: ...       # 同步
    def outbound_size(self) -> int: ...      # 同步
```

构造和查询方法是同步的，消息收发是**异步**的。

---

## Java 实现方案

### 设计原则

**和源码保持一致：**
- Python `async def` → Java `CompletableFuture<T>`
- Python `def` → Java 普通同步方法
- Python `await` → Java `.thenCompose()` / `.thenApply()` / `.join()`
- Python `async with` → Java 自定义 `AsyncCloseable` + `AsyncUtils.withAsync()`
- Python `try/finally` 在 async 中 → Java `CompletableFuture.handle()`

### 1. 入口类 `NanobotApplication.java`（同步）

```java
package com.nanobot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NanobotApplication {
    public static void main(String[] args) {
        SpringApplication.run(NanobotApplication.class, args);
    }
}
```

对标 `__main__.py`。差异说明：
- Python `__main__.py` 是纯 CLI 入口（`app()` 启动 Typer）；Java 使用 Spring Boot 统一入口，由 CLI 命令（Spring Shell）决定启动哪些组件
- `--help`、环境变量绑定由 Spring Boot 自动处理

### 2. 包信息 `package-info.java`（同步）

```java
/**
 * nanobot-java — Java port of the nanobot AI agent framework.
 *
 * <p>Exports:
 * <ul>
 *   <li>{@link com.nanobot.Nanobot} — SDK facade</li>
 *   <li>{@link com.nanobot.RunResult} — run result</li>
 * </ul>
 */
package com.nanobot;
```

对标 `__init__.py` 中的 `__all__` 和模块注释。**不做版本号注入**（见下文 `NanobotVersion`）。

### 3. 版本与常量 `NanobotVersion.java`（同步）

对标 `__init__.py` 中的 `__version__`、`__logo__`、`__getattr__` 惰性加载机制。

```java
package com.nanobot;

import java.io.InputStream;
import java.util.Properties;

/**
 * Package-level version and constants.
 *
 * <p>对标 Python {@code nanobot/__init__.py} 中的 {@code __version__} 和 {@code __logo__}。
 * Python 的 {@code __getattr__} 惰性加载在 Java 中没有等价物——JVM 的类加载本身就是按需的
 * （首次使用类时才初始化），因此不需要额外实现惰性导出。
 */
public final class NanobotVersion {

    public static final String LOGO = "🐈";

    private static final String FALLBACK_VERSION = "0.2.1";

    private NanobotVersion() {}

    /**
     * 三级回退版本获取，对标 Python {@code _resolve_version()}：
     * <ol>
     *   <li>读取 MANIFEST.MF 的 {@code Implementation-Version}（对应 Python pkg metadata）</li>
     *   <li>回退到 classpath 下的 {@code build.properties} 或 pom.properties</li>
     *   <li>最终兜底 {@code "0.2.1"}</li>
     * </ol>
     */
    public static String resolveVersion() {
        String v = readManifestVersion();
        if (v != null && !v.isBlank()) return v;

        v = readBuildPropertiesVersion();
        if (v != null && !v.isBlank()) return v;

        return FALLBACK_VERSION;
    }

    private static String readManifestVersion() {
        Package pkg = Package.getPackage("com.nanobot");
        if (pkg != null) {
            String v = pkg.getImplementationVersion();
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String readBuildPropertiesVersion() {
        try (InputStream is = NanobotVersion.class.getResourceAsStream("/META-INF/build.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                String v = props.getProperty("version");
                if (v != null && !v.isBlank()) return v;
            }
        } catch (Exception ignored) {}
        return null;
    }
}
```

### 4. 异步资源管理 `AsyncCloseable.java` 与 `AsyncUtils.java`

Python 支持 `async with` 语法。Java 中没有等价物，需自定义接口和工具方法。

```java
package com.nanobot;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 异步可关闭资源。对标 Python 的异步上下文管理器协议（{@code __aenter__} / {@code __aexit__}）。
 */
public interface AsyncCloseable {
    CompletableFuture<Void> close();
}
```

```java
package com.nanobot;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 异步工具类。提供 {@code withAsync} 模拟 Python 的 {@code async with} 语法。
 */
public final class AsyncUtils {

    private AsyncUtils() {}

    /**
     * 对标 Python: {@code async with resource as r: result = await action(r)}。
     *
     * <p>无论 {@code action} 成功或失败，都会等待 {@code resource.close()} 完成后
     * 才返回结果或抛出异常。
     */
    public static <T extends AsyncCloseable, R> CompletableFuture<R> withAsync(
            T resource,
            Function<T, CompletableFuture<R>> action) {
        CompletableFuture<R> result = action.apply(resource);
        return result
            .thenCompose(r -> resource.close().thenApply(v -> r))
            .exceptionallyCompose(ex ->
                resource.close().thenCompose(v -> CompletableFuture.failedFuture(ex)));
    }
}
```

### 5. SDK 门面 `Nanobot.java`（混合 async/sync）

```java
package com.nanobot;

import com.nanobot.agent.AgentLoop;
import com.nanobot.agent.hook.AgentHook;
import com.nanobot.agent.hook.SDKCaptureHook;
import com.nanobot.bus.OutboundMessage;
import com.nanobot.config.loader.ConfigLoader;
import com.nanobot.config.schema.Config;
import com.nanobot.providers.image_generation.ImageGenerationProvider;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Programmatic SDK facade — thin wrapper, NOT an orchestrator.
 *
 * <p>对标 Python {@code nanobot/nanobot.py}。
 * 真正的装配（MessageBus、SessionManager、providers、tools 等）
 * 在 {@link AgentLoop#fromConfig} 中完成。
 */
public class Nanobot implements AsyncCloseable {

    private final AgentLoop loop;

    /** 同步。对标 Python: {@code Nanobot.__init__(loop)} */
    public Nanobot(AgentLoop loop) {
        this.loop = loop;
    }

    // ── from_config 工厂方法（同步）──────────────────────────────

    /** 同步。使用默认配置创建实例。对标 {@code Nanobot.from_config()}。 */
    public static Nanobot fromConfig() {
        return fromConfig(null, null);
    }

    /** 同步。指定配置文件创建实例。对标 {@code Nanobot.from_config(config_path)}。 */
    public static Nanobot fromConfig(Path configPath) {
        return fromConfig(configPath, null);
    }

    /**
     * 同步。从配置文件创建实例。
     * 对标 Python: {@code Nanobot.from_config(config_path, workspace=...)}。
     *
     * <p>流程与 Python 源码严格一致：
     * <ol>
     *   <li>{@code ConfigLoader.load(configPath)} — 加载配置（不传则使用默认值）</li>
     *   <li>{@code ConfigLoader.resolveEnvVars(config)} — 原地解析 ${VAR} 环境变量引用</li>
     *   <li>如果 {@code workspace != null}，覆盖 {@code config.agents.defaults.workspace}</li>
     *   <li>{@code AgentLoop.fromConfig(config, ...)} — 真正的组件装配</li>
     * </ol>
     *
     * <p><b>Config 可变性说明：</b>
     * Python 中 Config 是 Pydantic BaseModel，字段可直接修改。
     * Java 中 {@link Config} 必须是可变的 POJO（带 setter 的 {@code @ConfigurationProperties} 类），
     * 或提供 {@code withWorkspace()} 等 copy 方法。不能使用 immutable record。
     */
    public static Nanobot fromConfig(Path configPath, Path workspace) {
        java.nio.file.Path resolvedConfig = null;
        if (configPath != null) {
            String pathStr = configPath.toString();
            // 对标 Python: Path(config_path).expanduser()
            if (pathStr.startsWith("~" + java.io.File.separator) || pathStr.equals("~")) {
                pathStr = System.getProperty("user.home") + pathStr.substring(1);
                configPath = java.nio.file.Path.of(pathStr);
            }
            resolvedConfig = configPath.toAbsolutePath().normalize();
            if (!java.nio.file.Files.exists(resolvedConfig)) {
                throw new IllegalArgumentException(
                    "Config not found: " + resolvedConfig);
            }
        }

        Config config = ConfigLoader.load(resolvedConfig);
        ConfigLoader.resolveEnvVars(config);

        if (workspace != null) {
            String wsStr = workspace.toString();
            if (wsStr.startsWith("~" + java.io.File.separator) || wsStr.equals("~")) {
                wsStr = System.getProperty("user.home") + wsStr.substring(1);
                workspace = java.nio.file.Path.of(wsStr);
            }
            config.getAgents().getDefaults().setWorkspace(
                workspace.toAbsolutePath().normalize().toString());
        }

        // 对标 Python: AgentLoop.from_config(config, image_generation_provider_configs=...)
        // Python 中 image_generation_provider_configs 通过 **extra 关键字参数传入
        AgentLoop loop = AgentLoop.fromConfig(config, Map.of(
            "imageGenerationProviderConfigs",
            ImageGenerationProvider.providerConfigs(config)));
        return new Nanobot(loop);
    }

    // ── run（异步）───────────────────────────────────────────────

    /** 异步。使用默认 session_key 运行。对标 {@code await bot.run(message)}。 */
    public CompletableFuture<RunResult> run(String message) {
        return run(message, "sdk:default", null);
    }

    /** 异步。指定 session_key 运行。对标 {@code await bot.run(message, session_key=...)}。 */
    public CompletableFuture<RunResult> run(String message, String sessionKey) {
        return run(message, sessionKey, null);
    }

    /**
     * 异步。运行 agent 并收集结果。
     * 对标 Python: {@code await bot.run(message, session_key=..., hooks=...)}。
     *
     * <p>Hook 操作与源码严格一致：
     * <ol>
     *   <li>创建 {@link SDKCaptureHook}</li>
     *   <li>读取当前 extra hooks（通过 {@code AgentLoop} 提供的 getter）</li>
     *   <li>设置 extra hooks 为 {@code [capture] + baseHooks}</li>
     *   <li>{@code await loop.process_direct(message, sessionKey)}</li>
     *   <li>恢复原始 extra hooks（无论成功或失败）</li>
     *   <li>返回 {@link RunResult}</li>
     * </ol>
     *
     * <p><b>session 串行化：</b>Python {@code process_direct} 内部按 session_key
     * 使用 {@code asyncio.Lock()} 串行化。Java 中 {@link AgentLoop#processDirect}
     * 必须实现同等的按 session 锁机制（{@code ConcurrentHashMap<String, ReentrantLock>}）。
     */
    public CompletableFuture<RunResult> run(String message, String sessionKey, List<AgentHook> hooks) {
        SDKCaptureHook capture = new SDKCaptureHook();
        List<AgentHook> prev = loop.getExtraHooks();
        List<AgentHook> baseHooks = hooks != null
            ? new ArrayList<>(hooks)
            : new ArrayList<>(prev != null ? prev : List.of());
        List<AgentHook> combined = new ArrayList<>();
        combined.add(capture);
        combined.addAll(baseHooks);
        loop.setExtraHooks(combined);

        // 对标 Python: response = await loop.process_direct(...)
        return loop.processDirect(message, sessionKey)
            .handle((response, ex) -> {
                // 对标 Python: finally 块中恢复 _extra_hooks
                loop.setExtraHooks(prev);
                if (ex != null) {
                    throw new CompletionException(ex);
                }
                // 对标 Python: content = (response.content if response else None) or ""
                String raw = response != null ? response.content() : null;
                String content = (raw == null || raw.isEmpty()) ? "" : raw;
                return new RunResult(content, capture.getToolsUsed(), capture.getMessages());
            });
    }

    // ── 资源释放（异步）───────────────────────────────────────────

    /**
     * 异步。释放 MCP 连接等资源。
     * 对标 Python: {@code await bot.aclose()}。
     *
     * <p>Python 的 {@code close_mcp} 是 async 的，内部会 {@code await asyncio.gather()}
     * 和 {@code await stack.aclose()}。Java 中 {@link AgentLoop#closeMcp} 返回
     * {@code CompletableFuture<Void>}，保留异步语义。
     */
    @Override
    public CompletableFuture<Void> close() {
        return loop.closeMcp();
    }
}
```

### 6. `RunResult` record（同步）

```java
package com.nanobot;

import java.util.List;
import java.util.Map;

/**
 * Result of a single agent run.
 * 对标 Python: {@code @dataclass(slots=True) class RunResult}。
 */
public record RunResult(
    String content,
    List<String> toolsUsed,
    List<Map<String, Object>> messages
) {}
```

### 7. 服务端生命周期 `NanobotServer.java`（混合 async/sync）

> **来源说明**：Python 根包（{@code nanobot/}）中<b>不存在</b>此类。
> 此逻辑提取自 {@code nanobot/cli/commands.py} 的 {@code serve()} 和 {@code gateway()}
> 命令——这些命令创建 {@code AgentLoop} 后启动 channels、cron、API 等子系统。
> Java 中将其提取为独立的 {@code @Component} 以利用 Spring 生命周期管理。

```java
package com.nanobot;

import com.nanobot.agent.AgentLoop;
import com.nanobot.bus.MessageBus;
import com.nanobot.channels.ChannelManager;
import com.nanobot.cron.CronService;
import com.nanobot.api.OpenAiApiServer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class NanobotServer {

    private final MessageBus bus;
    private final AgentLoop agentLoop;
    private final ChannelManager channelManager;
    private final CronService cronService;
    private final OpenAiApiServer apiServer;

    public NanobotServer(MessageBus bus, AgentLoop agentLoop,
                         ChannelManager channelManager, CronService cronService,
                         OpenAiApiServer apiServer) {
        this.bus = bus;
        this.agentLoop = agentLoop;
        this.channelManager = channelManager;
        this.cronService = cronService;
        this.apiServer = apiServer;
    }

    @PostConstruct
    public void start() {
        // 对标 Python: asyncio.create_task(agent_loop.run()) / asyncio.gather(agent.run(), ...)
        // AgentLoop.run() 是长期运行的异步任务，启动后不阻塞等待
        agentLoop.run()
            .exceptionally(ex -> {
                // 避免异常静默丢失
                throw new RuntimeException("AgentLoop crashed", ex);
            });
        channelManager.startAll();
        apiServer.start();
        cronService.start();
    }

    @PreDestroy
    public void stop() {
        channelManager.stopAll();
        apiServer.stop();
        cronService.stop();
    }
}
```

---

## 关键设计决策

### async/sync 边界严格对应源码

| Python | Java | 职责 |
|--------|------|------|
| `__main__.py` → `app()` | `NanobotApplication.main()` | 统一入口（Spring Boot） |
| `__init__.py` | `package-info.java` + `NanobotVersion.java` | 包元数据、版本、常量 |
| `Nanobot.__init__(loop)` | `Nanobot(AgentLoop loop)` | 薄门面构造（**同步**） |
| `Nanobot.from_config()` | `Nanobot.fromConfig()` | SDK 工厂（**同步**） |
| `Nanobot.run()` | `Nanobot.run()` | SDK 调用（**异步**，`CF<RunResult>`） |
| `Nanobot.aclose()` | `Nanobot.close()` | 资源释放（**异步**，`CF<Void>`） |
| `async with bot:` | `AsyncUtils.withAsync(bot, b -> b.run(...))` | 异步上下文管理器 |
| `RunResult` | `RunResult` record | 返回值（**同步**） |
| `AgentLoop.run()` | `AgentLoop.run()` | 长期运行任务（**异步**，`CF<Void>`） |
| `AgentLoop.process_direct()` | `AgentLoop.processDirect()` | 直接处理（**异步**，`CF<OutboundMessage>`） |
| `AgentLoop.close_mcp()` | `AgentLoop.closeMcp()` | MCP 清理（**异步**，`CF<Void>`） |
| `AgentHook` 回调 | `AgentHook` 接口 | 生命周期钩子（**异步**，`CF<Void>`） |
| `SDKCaptureHook` | `SDKCaptureHook` | 结果收集（**异步**） |
| CLI `gateway()` / `serve()` | `NanobotServer` | 服务端生命周期管理 |

### Config 可变性

Python `Config` 是 Pydantic `BaseModel`，字段可在运行时修改（如 `config.agents.defaults.workspace = ...`）。
Java 中必须使用**带 setter 的 POJO**（`@ConfigurationProperties` 类），不能使用 immutable record。

### _extraHooks 访问方式

Python 源码中直接读写 `self._loop._extra_hooks`（私有字段，无 getter/setter）。
Java 中建议：在 `AgentLoop` 中提供 package-private 或 public 的 `getExtraHooks()` / `setExtraHooks()`。

### CompletableFuture 映射规则

| Python | Java |
|--------|------|
| `async def f() -> T` | `CompletableFuture<T> f()` |
| `await expr` | `expr.thenCompose(...)` / `expr.thenApply(...)` |
| `try/finally` 在 async 中 | `CompletableFuture.handle(...)` |
| `async with` | `AsyncUtils.withAsync(resource, action)` |
| `asyncio.Lock()` | `ReentrantLock`（在异步方法中 `lock.lock()` 会阻塞虚拟线程） |

---

## 依赖接口声明（完整实现在其他包中）

以下接口/类在 `Nanobot` 中直接依赖，完整实现和详细设计参见对应包文档：

### `AgentLoop`（08-agent-core.md）

```java
package com.nanobot.agent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class AgentLoop {
    public static AgentLoop fromConfig(Config config, Map<String, Object> extra) { ... }
    public CompletableFuture<Void> run() { ... }
    public CompletableFuture<OutboundMessage> processDirect(String content, String sessionKey) { ... }
    public CompletableFuture<Void> closeMcp() { ... }
    public List<AgentHook> getExtraHooks() { ... }
    public void setExtraHooks(List<AgentHook> hooks) { ... }
}
```

### `AgentHook`（08-agent-core.md）

```java
package com.nanobot.agent.hook;

import java.util.concurrent.CompletableFuture;

public interface AgentHook {
    default CompletableFuture<Void> beforeRun(AgentRunHookContext ctx) {
        return CompletableFuture.completedFuture(null);
    }
    default CompletableFuture<Void> afterRun(AgentRunHookContext ctx) {
        return CompletableFuture.completedFuture(null);
    }
    default CompletableFuture<Void> beforeIteration(AgentHookContext ctx) {
        return CompletableFuture.completedFuture(null);
    }
    default CompletableFuture<Void> afterIteration(AgentHookContext ctx) {
        return CompletableFuture.completedFuture(null);
    }
    default CompletableFuture<Void> onStream(AgentHookContext ctx, String delta) {
        return CompletableFuture.completedFuture(null);
    }
    // ... 其他方法同理
}
```

### `SDKCaptureHook`（08-agent-core.md）

```java
package com.nanobot.agent.hook;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class SDKCaptureHook extends AgentHook {
    private List<String> toolsUsed = new ArrayList<>();
    private List<Map<String, Object>> messages = new ArrayList<>();

    @Override
    public CompletableFuture<Void> afterIteration(AgentHookContext context) {
        for (ToolCallRequest call : context.toolCalls()) {
            toolsUsed.add(call.name());
        }
        messages = new ArrayList<>(context.messages());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> afterRun(AgentRunHookContext context) {
        toolsUsed = new ArrayList<>(context.toolsUsed());
        messages = new ArrayList<>(context.messages());
        return CompletableFuture.completedFuture(null);
    }

    public List<String> getToolsUsed() { return toolsUsed; }
    public List<Map<String, Object>> getMessages() { return messages; }
}
```

---

## 验证标准

```bash
cd nanobot-java
mvn clean compile
# 预期: 编译通过，无错误

# 单元测试: Nanobot.fromConfig 能加载配置并创建实例
mvn test -Dtest=NanobotTest

# 验证版本号三级回退
mvn test -Dtest=NanobotVersionTest

# 验证异步 withAsync 工具
mvn test -Dtest=AsyncUtilsTest
```

## 代码量估算

| 文件 | 行数 |
|------|------|
| NanobotApplication.java | ~12 |
| NanobotVersion.java | ~55 |
| AsyncCloseable.java | ~8 |
| AsyncUtils.java | ~20 |
| Nanobot.java | ~130 |
| RunResult.java | ~8 |
| NanobotServer.java | ~55 |
| package-info.java | ~10 |
| **合计** | **~298** |
