# 01 — Root 包：项目入口与 SDK 门面

**对标 Python：** `nanobot/__main__.py` (8行), `nanobot/nanobot.py` (113行), `nanobot/__init__.py` (48行)

## Python 源码分析

### `__main__.py` — 模块入口点

```python
"""
Entry point for running nanobot as a module: python -m nanobot
"""

from nanobot.cli.commands import app

if __name__ == "__main__":
    app()
```

导入 Typer 实例 `app` 并直接调用。Typer 内部处理命令行解析和 `sys.exit`。没有 PTH 文件检查——那是独立的安全加固逻辑，不在此文件。

### `nanobot.py` — Nanobot SDK 门面

**定位：薄门面，不是编排器。** 真正的组件装配在 `AgentLoop.from_config()` 中。

完整源码结构：

```python
@dataclass(slots=True)
class RunResult:
    content: str
    tools_used: list[str]
    messages: list[dict[str, Any]]

class Nanobot:
    def __init__(self, loop: AgentLoop) -> None:       # 只接受 AgentLoop
        self._loop = loop

    @classmethod
    def from_config(cls, config_path=None, *, workspace=None) -> Nanobot:
        # 1. load_config(config_path) → Config
        # 2. resolve_config_env_vars()
        # 3. 可选覆盖 workspace
        # 4. AgentLoop.from_config(config, image_generation_provider_configs=...)
        # 5. return cls(loop)

    async def run(self, message, *, session_key="sdk:default", hooks=None) -> RunResult:
        # 1. 创建 SDKCaptureHook 收集 tools_used + messages
        # 2. 临时挂载 hooks 到 loop._extra_hooks
        # 3. await loop.process_direct(message, session_key=session_key)
        # 4. 恢复原始 hooks
        # 5. 返回 RunResult(content, tools_used, messages)

    async def aclose(self) -> None:
        await self._loop.close_mcp()

    async def __aenter__(self) -> Nanobot: ...
    async def __aexit__(self, *exc) -> None: ...
```

关键点：
- `__init__` 不做任何组件创建，只存一个 `_loop: AgentLoop` 引用
- `from_config()` 是 SDK 入口，委托 `AgentLoop.from_config()` 完成装配
- 无 `start()` / `stop()` 方法——生命周期由调用方管理
- `run()` 通过 hook 机制注入 `SDKCaptureHook` 收集结果
- `aclose()` 仅释放 MCP 连接

### `__init__.py` — 包初始化与惰性导出

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
- 使用 `__getattr__` 惰性加载，不是直接 import
- 版本获取有三级回退链
- 导出 `Nanobot` 和 `RunResult` 两个符号
- `__logo__` 是独立的包级属性

## Java 实现方案

### 设计原则

`Nanobot` 在 Python 中是薄 SDK 门面，Java 中保持相同定位。真正的装配逻辑在 `AgentLoop` 中（对标 Python `AgentLoop.from_config()`）。

### 1. 入口类 `NanobotApplication.java`

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

对标 `__main__.py`。Spring Boot 自动处理 `--help`、环境变量绑定。

### 2. 包信息 `package-info.java`

```java
/**
 * nanobot-java — Java port of the nanobot AI agent framework.
 *
 * <p>Lazy exports equivalent to nanobot/__init__.py:
 * <ul>
 *   <li>{@link com.nanobot.Nanobot} — SDK facade</li>
 *   <li>{@link com.nanobot.RunResult} — run result</li>
 * </ul>
 */
@Version("0.2.1")
package com.nanobot;

// 版本号通过 Maven pom.xml 的 ${project.version} 注入
// __logo__ = "🐈" 在 application.yml 中配置: nanobot.bot-icon: "🐈"
```

### 3. SDK 门面 `Nanobot.java`

```java
package com.nanobot;

import com.nanobot.agent.AgentLoop;
import com.nanobot.agent.hook.AgentHook;
import com.nanobot.agent.hook.SDKCaptureHook;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Programmatic SDK facade — thin wrapper, NOT an orchestrator.
 *
 * <p>The real assembly (MessageBus, SessionManager, providers, tools, etc.)
 * lives in {@link AgentLoop#fromConfig}.
 */
public class Nanobot implements AutoCloseable {

    private final AgentLoop loop;

    /** Package-private: use {@link #fromConfig} to create instances. */
    Nanobot(AgentLoop loop) {
        this.loop = loop;
    }

    /** Create from a config file. Delegates to AgentLoop.fromConfig(). */
    public static Nanobot fromConfig(Path configPath, Path workspace) {
        // 对标 Python: Nanobot.from_config(config_path, workspace=...)
        // 内部调用 AgentLoop.fromConfig(config, imageGenProviderConfigs)
        // 返回 new Nanobot(loop)
        Config config = ConfigLoader.load(configPath);
        ConfigLoader.resolveEnvVars(config);
        if (workspace != null) {
            config.agents.defaults.workspace = workspace.toAbsolutePath().toString();
        }
        AgentLoop loop = AgentLoop.fromConfig(config,
            ImageGenerationProviderConfigs.from(config));
        return new Nanobot(loop);
    }

    /** Run the agent and collect results. */
    public RunResult run(String message, String sessionKey, List<AgentHook> hooks) {
        SDKCaptureHook capture = new SDKCaptureHook();
        List<AgentHook> prev = loop.getExtraHooks();
        List<AgentHook> baseHooks = hooks != null
            ? new ArrayList<>(hooks) : new ArrayList<>(prev != null ? prev : List.of());
        List<AgentHook> combined = new ArrayList<>();
        combined.add(capture);
        combined.addAll(baseHooks);
        loop.setExtraHooks(combined);
        try {
            OutboundMessage response = loop.processDirect(message, sessionKey);
            String content = response != null ? response.content : "";
            return new RunResult(content, capture.toolsUsed(), capture.messages());
        } finally {
            loop.setExtraHooks(prev);
        }
    }

    @Override
    public void close() {
        loop.closeMcp();
    }
}
```

### 4. `RunResult` record

```java
package com.nanobot;

import java.util.List;
import java.util.Map;

public record RunResult(
    String content,
    List<String> toolsUsed,
    List<Map<String, Object>> messages
) {}
```

对标 Python:
```python
@dataclass(slots=True)
class RunResult:
    content: str
    tools_used: list[str]
    messages: list[dict[str, Any]]
```

### 5. 真正的主装配：`NanobotServer.java`（对标 Python 中不存在于 `nanobot.py` 的启动逻辑）

Python 中，CLI `gateway`/`serve` 命令创建 AgentLoop 并启动 channels/cron/api。这些逻辑不在 `nanobot.py` 中，而在 `cli/commands.py` 的 `gateway()` / `serve()` 函数里。

Java 中拆分为独立的 `NanobotServer`：

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
        Thread.startVirtualThread(agentLoop::run);  // agent loop 在虚拟线程中运行
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

## 关键设计决策

### Nanobot 的定位

`Nanobot` 是**薄 SDK 门面**，不能是 Spring `@Component`。它是给外部调用方（如测试、脚本、SDK 用户）用的编程接口。

### 装配逻辑归属

- `Nanobot.from_config()` → Java `Nanobot.fromConfig()` — SDK 使用的便捷工厂
- `AgentLoop.from_config()` → Java `AgentLoop.fromConfig()` — 真正的组件装配
- CLI `gateway()` / `serve()` → Java `NanobotServer` — 服务端生命周期管理

### Python → Java 映射总结

| Python | Java | 职责 |
|--------|------|------|
| `__main__.py` → `app()` | `NanobotApplication.main()` | 入口点 |
| `__init__.py` | `package-info.java` + Maven pom | 版本、logo、导出 |
| `Nanobot.__init__(loop)` | `Nanobot(AgentLoop loop)` | 薄门面构造 |
| `Nanobot.from_config()` | `Nanobot.fromConfig()` | SDK 工厂 |
| `Nanobot.run()` | `Nanobot.run()` | SDK 调用 |
| `Nanobot.aclose()` | `Nanobot.close()` | 资源释放 |
| `RunResult` | `RunResult` record | 返回值 |
| `SDKCaptureHook` | `SDKCaptureHook` | 结果收集 |

## 验证标准

```bash
cd nanobot-java
mvn clean compile
# 预期: 编译通过，无错误

# 单元测试: Nanobot.fromConfig 能加载配置并创建实例
mvn test -Dtest=NanobotTest
```

## 代码量估算

| 文件 | 行数 |
|------|------|
| NanobotApplication.java | ~12 |
| Nanobot.java | ~60 |
| RunResult.java | ~8 |
| NanobotServer.java | ~50 |
| package-info.java | ~12 |
| **合计** | **~142** |

> 对比原方案 ~290 行（含错误的 AutoConfiguration），修正后减少约一半。
