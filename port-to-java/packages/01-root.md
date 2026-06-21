# 01 — Root 包：项目入口与主应用编排

**对标 Python：** `nanobot/__main__.py` (8行), `nanobot/nanobot.py` (~120行), `nanobot/__init__.py` (48行)

## Python 源码分析

### `__main__.py` — 入口点启动

```python
from nanobot.cli.commands import main
import sys
if __name__ == "__main__":
    sys.exit(main())
```

直接委托到 `cli.commands.main()`。前面有 security 模块的 PTH 文件检查。

### `nanobot.py` — Nanobot 应用编排器

核心类 `Nanobot` 负责将整个应用组装起来。关键方法：

```
class Nanobot:
    __init__(self, config: Config)
        - 创建 MessageBus
        - 创建 SessionManager
        - 创建 MemoryStore / Consolidator
        - 创建 ToolRegistry → ToolLoader → AgentLoop
        - 创建 ChannelManager
        - 创建 CronService
        - 创建 API Server
        - 用于 WebUI 的 GatewayServices

    from_config(config_path=None, preset=None)  ← 静态工厂
        → load_config() → Nanobot(config)

    async start()
        - 启动 ChannelManager (所有 channel)
        - 启动 API Server
        - 启动 CronService

    async stop()
```

### `__init__.py` — 版本与重导出

```python
__version__ = "..."  # 从 pyproject.toml 读取
from nanobot.nanobot import Nanobot  # 重导出
```

## Java 实现方案

### 1. 入口类 `NanobotApplication.java`

```java
package com.nanobot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(NanobotProperties.class)
public class NanobotApplication {

    public static void main(String[] args) {
        SpringApplication.run(NanobotApplication.class, args);
    }
}
```

Spring Boot 自动处理：
- `--help` 参数
- `--spring.profiles.active=prod` 切换配置
- 环境变量绑定（`NANOBOT_*` → `nanobot.*` 属性路径）

### 2. 应用编排器 `Nanobot.java`

```java
package com.nanobot;

import com.nanobot.agent.*;
import com.nanobot.agent.tools.*;
import com.nanobot.bus.*;
import com.nanobot.channels.*;
import com.nanobot.config.*;
import com.nanobot.cron.*;
import com.nanobot.providers.*;
import com.nanobot.session.*;
import com.nanobot.utils.*;
import com.nanobot.webui.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@RequiredArgsConstructor
public class Nanobot implements AutoCloseable {

    private final NanobotProperties properties;
    private final MessageBus bus;
    private final SessionManager sessionManager;
    private final MemoryStore memoryStore;
    private final Consolidator consolidator;
    private final ToolRegistry toolRegistry;
    private final ToolLoader toolLoader;
    private final AgentLoop agentLoop;
    private final ChannelManager channelManager;
    private final CronService cronService;
    private final OpenAiApiServer apiServer;
    private final GatewayServices gatewayServices;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @PostConstruct
    public void start() {
        // 1. 加载 tools
        ToolContext toolCtx = buildToolContext();
        toolLoader.load(toolCtx, toolRegistry, "core");

        // 2. 启动 agent loop
        executor.submit(agentLoop::run);

        // 3. 启动所有 channels
        channelManager.startAll();

        // 4. 启动 API server
        apiServer.start();

        // 5. 启动 cron service
        cronService.start();
    }

    @PreDestroy
    @Override
    public void close() {
        channelManager.stopAll();
        apiServer.stop();
        cronService.stop();
        executor.shutdown();
    }

    private ToolContext buildToolContext() {
        ToolContext ctx = new ToolContext(
            properties,
            bus,
            sessionManager,
            // ... 其他依赖
        );
        return ctx;
    }

    // 对标 Python Nanobot.from_config()
    public static Nanobot fromConfig(String configPath, String preset) {
        // 由 Spring 容器管理，此方法提供程序化创建
        // 实际使用 SpringApplication.run() 即可
        throw new UnsupportedOperationException("Use Spring Boot autoconfiguration");
    }
}
```

### 3. Spring 配置类 `NanobotAutoConfiguration.java`

将 Nanobot 的各部分装配为 Spring Bean：

```java
package com.nanobot;

import com.nanobot.agent.*;
import com.nanobot.agent.tools.*;
import com.nanobot.bus.*;
import com.nanobot.channels.*;
import com.nanobot.config.*;
import com.nanobot.cron.*;
import com.nanobot.providers.*;
import com.nanobot.session.*;
import com.nanobot.webui.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NanobotAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AppPaths appPaths(NanobotProperties properties) {
        return new AppPaths(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public MessageBus messageBus() {
        return new MessageBus();
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionManager sessionManager(AppPaths appPaths) {
        return new SessionManager(appPaths);
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryStore memoryStore(SessionManager sessionManager, AppPaths appPaths) {
        return new MemoryStore(sessionManager, appPaths);
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry() {
        return new ToolRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolLoader toolLoader() {
        return new ToolLoader();
    }

    @Bean
    @ConditionalOnMissingBean
    public LLMProvider llmProvider(NanobotProperties properties) {
        ProviderFactory factory = new ProviderFactory(properties);
        return factory.makeProvider(null, null);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentLoop agentLoop(
            MessageBus bus,
            LLMProvider provider,
            ToolRegistry toolRegistry,
            SessionManager sessionManager,
            MemoryStore memoryStore,
            NanobotProperties properties) {
        return new AgentLoop(bus, provider, toolRegistry,
                             sessionManager, memoryStore, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelManager channelManager(
            NanobotProperties properties,
            MessageBus bus,
            SessionManager sessionManager,
            CronService cronService) {
        return new ChannelManager(properties, bus, sessionManager, cronService);
    }

    @Bean
    @ConditionalOnMissingBean
    public OpenAiApiServer openAiApiServer(NanobotProperties properties) {
        return new OpenAiApiServer(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public CronService cronService(NanobotProperties properties, MessageBus bus) {
        return new CronService(properties, bus);
    }

    @Bean
    @ConditionalOnMissingBean
    public GatewayServices gatewayServices(
            NanobotProperties properties,
            SessionManager sessionManager,
            CronService cronService) {
        return buildGatewayServices(properties, sessionManager, cronService);
    }

    @Bean
    @ConditionalOnMissingBean
    public Nanobot nanobot(/* 所有依赖注入 */) {
        return new Nanobot(/* ... */);
    }
}
```

### 4. `application.yml` 基础配置

```yaml
spring:
  application:
    name: nanobot-java
  threads:
    virtual:
      enabled: true

server:
  port: 0  # 由 NanobotProperties.api.port 覆盖

logging:
  level:
    com.nanobot: INFO
    com.nanobot.agent: DEBUG

nanobot:
  agents:
    defaults:
      workspace: ~/.nanobot-java/workspace
      model: anthropic/claude-sonnet-4-6
      provider: auto
      max-tokens: 8192
      context-window-tokens: 65536
      temperature: 0.1
      max-tool-iterations: 200
      provider-retry-mode: standard
      bot-name: nanobot-java
      bot-icon: "🐈"
      timezone: UTC
      session-ttl-minutes: 0
      max-messages: 120
      consolidation-ratio: 0.5
  channels:
    send-progress: true
    send-tool-hints: false
    show-reasoning: true
    extract-document-text: true
    send-max-retries: 3
  api:
    host: 127.0.0.1
    port: 8900
    timeout: 120.0
  gateway:
    host: 127.0.0.1
    port: 18790
```

## 关键设计决策

### 异步 → 虚拟线程

Python 的 `async def start()` 在 Java 中变为普通的同步方法，由虚拟线程执行。`executor.submit(agentLoop::run)` 启动的虚拟线程会自动在阻塞 I/O 时让出 CPU。

### 静态工厂 vs Spring Bean

Python 的 `Nanobot.from_config()` 静态工厂在 Java 中由 Spring 的 `@Configuration` + `@Bean` 工厂方法替代，同样支持依赖注入和延迟初始化。

### 生命周期管理

Python 的 `async start()` / `async stop()` → Java `@PostConstruct` / `@PreDestroy`（Spring 管理）+ `AutoCloseable`（程序化使用）。

## 验证标准

```bash
cd nanobot-java
mvn clean package -DskipTests
java -jar target/nanobot-java-1.0.0.jar --help
# 预期输出: Spring Boot 启动信息 + nanobot 配置摘要

NANOBOT_AGENTS_DEFAULTS_MODEL=openai/gpt-5 java -jar target/nanobot-java-1.0.0.jar
# 预期: 日志中显示 model = openai/gpt-5
```

## 代码量估算

| 文件 | 行数 |
|------|------|
| NanobotApplication.java | ~15 |
| Nanobot.java | ~100 |
| NanobotAutoConfiguration.java | ~120 |
| package-info.java (__init__.py 对标) | ~5 |
| application.yml | ~50 |
| **合计** | **~290** |
