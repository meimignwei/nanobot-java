# P6 — Channel + WebUI

## 复刻目标

对标 `nanobot/channels/base.py` + `nanobot/channels/websocket.py`（~1,178 行）+ `nanobot/channels/manager.py` + WebUI 前端（TypeScript，零改动）。

这是小闭环的最后一环——用户在浏览器里输入文字，经由 WebSocket → MessageBus → AgentLoop → 回复渲染回浏览器。

## Python 源码对照

### BaseChannel

```python
class BaseChannel(ABC):
    name: str = "base"
    display_name: str = "Base"
    send_progress: bool = True

    def __init__(self, config, bus: MessageBus):
        self.config = config
        self.bus = bus
        self._running = False

    @abstractmethod async def start(self) -> None: ...    # 监听消息
    @abstractmethod async def stop(self) -> None: ...     # 清理
    @abstractmethod async def send(self, msg: OutboundMessage) -> None: ...  # 发送回复
```

### ChannelManager

```python
class ChannelManager:
    def __init__(config, bus):
        self._channels: dict[str, BaseChannel] = {}
    async def start_channel(name): ...
    async def stop_channel(name): ...
    async def start_all(): ...
    async def stop_all(): ...
```

### WebSocket Channel (关键)

```python
class WebSocketChannel(BaseChannel):
    # 使用 websockets.asyncio.server
    async def start(self):
        async with websockets.server.serve(self._handler, host, port):
            await asyncio.Future()  # run forever

    async def _handler(self, ws):
        """Per-connection handler"""
        async for raw in ws:
            event = json.loads(raw)
            msg = InboundMessage(
                channel="websocket",
                sender_id=event["sender_id"],
                chat_id=event["chat_id"],
                content=event["content"],
                ...
            )
            await self.bus.publish_inbound(msg)

    async def send(self, msg: OutboundMessage):
        """发送给特定 WS 连接"""
        # 按 chat_id 找到 ws → json.dumps → ws.send()
```

### WebUI 协议

前端 `nanobot-client.ts` 定义的协议：

```typescript
// Inbound (前端发给后端)
type InboundEvent = {
    type: "user_message" | "ping" | ...
    payload: { chat_id, content, ... }
}

// Outbound (后端发给前端)
type Outbound = {
    type: "delta" | "assistant_message" | "tool_call" | "file_edit"
        | "reasoning" | "error" | ...
    payload: { ... }
}
```

## Java 实现方案

### 1. BaseChannel

```java
// BaseChannel.java
public abstract class BaseChannel {
    protected final String name;
    protected final String displayName;
    protected final MessageBus bus;
    protected final NanobotProperties config;
    protected volatile boolean running = false;

    protected BaseChannel(String name, String displayName,
                           MessageBus bus, NanobotProperties config) {
        this.name = name;
        this.displayName = displayName;
        this.bus = bus;
        this.config = config;
    }

    /** 对标 Python start(): 开始监听 */
    public abstract void start() throws Exception;

    /** 对标 Python stop(): 优雅停止 */
    public abstract void stop() throws Exception;

    /** 对标 Python send(): 发送回复消息 */
    public abstract void send(OutboundMessage msg) throws Exception;

    public boolean isRunning() { return running; }
    public String name() { return name; }
}
```

### 2. ChannelManager

```java
// ChannelManager.java
@Component
public class ChannelManager {
    private final Map<String, BaseChannel> channels = new ConcurrentHashMap<>();
    private final MessageBus bus;
    private final NanobotProperties config;
    private final ExecutorService executor;

    public ChannelManager(MessageBus bus, NanobotProperties config) {
        this.bus = bus;
        this.config = config;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /** 对标 Python start_all(): 启动所有已配置的渠道 */
    public void startAll() {
        // 核心: WebSocketChannel
        startChannel("websocket", new WebSocketChannel(bus, config));
        // 调试: ConsoleChannel
        if (config.isDebug()) {
            startChannel("console", new ConsoleChannel(bus, config));
        }
    }

    public void startChannel(String name, BaseChannel channel) {
        channels.put(name, channel);
        executor.submit(() -> {
            try {
                logger.info("Starting channel: {}", name);
                channel.start();
            } catch (Exception e) {
                logger.error("Channel {} failed", name, e);
            }
        });
    }

    public void stopAll() {
        channels.values().forEach(ch -> {
            try { ch.stop(); } catch (Exception e) { /* log */ }
        });
    }

    /** 发送 outbound 消息到正确的 channel */
    public void dispatchOutbound(OutboundMessage msg) throws Exception {
        var channel = channels.get(msg.channel());
        if (channel != null) {
            channel.send(msg);
        }
    }

    /** 按名称获取 channel，用于流式 delta 等直接操作 */
    public Optional<BaseChannel> getChannel(String name) {
        return Optional.ofNullable(channels.get(name));
    }
}
```

### 3. WebSocketChannel — 核心实现

对标 Python `websockets.asyncio.server`，Java 用 Spring Boot 内置 WebSocket 支持：

```java
// WebSocketChannel.java
@Component
public class WebSocketChannel extends BaseChannel {

    private final ConcurrentMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public WebSocketChannel(MessageBus bus, NanobotProperties config) {
        super("websocket", "WebUI", bus, config);
    }

    /** 对标 Python start(): 注册为 Spring WebSocket handler */
    @Override
    public void start() {
        running = true;
        // WebSocket 连接通过 Spring 的 @Configuration 注册
        // 实际处理在下面的 WebSocketHandler 中
    }

    @Override
    public void stop() {
        running = false;
        // 关闭所有连接
        sessions.values().forEach(s -> {
            try { s.close(); } catch (IOException e) { /* ignore */ }
        });
        sessions.clear();
    }

    /** 对标 Python send(): 按 chat_id 找到 WS session 并发送 */
    @Override
    public void send(OutboundMessage msg) throws Exception {
        // 按 chat_id 索引（在 onMessage 中建立映射）
        var ws = sessions.get(msg.chatId());
        if (ws == null || !ws.isOpen()) return;

        // 构建 WebUI 协议 outbound 事件
        var event = Map.of(
            "type", "assistant_message",
            "payload", Map.of(
                "content", msg.content(),
                "chat_id", msg.chatId()
            )
        );
        // 对标 Python ws.send(json.dumps(event))
        ws.sendMessage(new TextMessage(mapper.writeValueAsString(event)));
    }

    /** WebSocket 连接建立 — 对标 Python _handler __aenter__ */
    void onConnect(WebSocketSession session) {
        // 连接建立时暂不索引（chat_id 在第一条 user_message 中确定）
    }

    /** WebSocket 连接断开 — 对标 Python _handler __aexit__ */
    void onDisconnect(WebSocketSession session) {
        sessions.values().removeIf(s -> s.getId().equals(session.getId()));
    }

    /** 处理收到的 WebSocket 消息 — 对标 Python async for raw in ws */
    void onMessage(WebSocketSession session, String text) {
        @SuppressWarnings("unchecked")
        var event = (Map<String, Object>) mapper.readValue(text, Map.class);
        String type = (String) event.get("type");

        if ("user_message".equals(type)) {
            @SuppressWarnings("unchecked")
            var payload = (Map<String, Object>) event.get("payload");
            String chatId = (String) payload.get("chat_id");
            // 按 chat_id 索引 session，用于 send() 精准路由
            sessions.put(chatId, session);
            var msg = new InboundMessage(
                "websocket",
                chatId,                        // sender_id
                chatId,                        // chat_id
                (String) payload.get("content"),
                Instant.now(),
                List.of(),
                Map.of("message_id", session.getId()),
                null
            );
            try {
                bus.publishInbound(msg);  // 发布到消息总线 → AgentLoop 处理
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // "ping" → 忽略，通过 WebSocket ping/pong 自动处理
    }

    /** 流式 delta 发送（对标 Python send_delta） */
    public void sendDelta(String chatId, String delta) {
        var ws = sessions.get(chatId);
        if (ws == null || !ws.isOpen()) return;
        try {
            var event = Map.of(
                "type", "delta",
                "payload", Map.of("content", delta, "chat_id", chatId)
            );
            ws.sendMessage(new TextMessage(mapper.writeValueAsString(event)));
        } catch (IOException e) {
            // 连接已断开，忽略
        }
    }
}
```

### 4. WebSocket 配置（Spring Boot）

```java
// WebSocketConfig.java
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final WebSocketChannel channel;

    public WebSocketConfig(WebSocketChannel channel) {
        this.channel = channel;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new WebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) {
                // 对标 Python _handler 的 __aenter__
                channel.onConnect(session);
            }

            @Override
            public void handleMessage(WebSocketSession session,
                                       WebSocketMessage<?> message) {
                if (message instanceof TextMessage text) {
                    channel.onMessage(session, text.getPayload());
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session,
                                               CloseStatus status) {
                channel.onDisconnect(session);
            }
        }, "/ws")
        .setAllowedOrigins("*");  // 开发阶段，生产应限制
    }
}
```

### 5. ConsoleChannel（调试用）

对标 Python 无——这是个便利功能，在没有 WebUI 时用命令行交互：

```java
// ConsoleChannel.java
@Component
public class ConsoleChannel extends BaseChannel {
    public ConsoleChannel(MessageBus bus, NanobotProperties config) {
        super("console", "Console", bus, config);
    }

    @Override
    public void start() {
        running = true;
        var scanner = new Scanner(System.in);
        var chatId = "console:default";
        int msgCount = 0;

        while (running && scanner.hasNextLine() && !Thread.interrupted()) {
            var line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;
            if (line.equals("/quit")) break;

            var msg = new InboundMessage(
                "console", "user", chatId, line,
                Instant.now(), List.of(), Map.of(), null
            );
            try {
                bus.publishInbound(msg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Override
    public void send(OutboundMessage msg) throws Exception {
        System.out.println("\n[Agent] " + msg.content());
    }

    @Override
    public void stop() { running = false; }
}
```

### 6. 流式输出整合

AgentLoop 在执行 AgentRunner 时需要把 `on_content_delta` 回调连接到 WebSocketChannel：

```java
// 在 AgentLoop.doRun() 中
var channel = channelManager.getChannel(ctx.msg.channel());
var wsChannel = (WebSocketChannel) channel;  // 或其他支持流式的 channel

var response = provider.chatStreamWithRetry(
    messages, tools, model, maxTokens, temperature,
    reasoningEffort, toolChoice,
    // on_content_delta: 对标 Python 的回调链
    delta -> {
        try {
            wsChannel.sendDelta(ctx.msg.chatId(), delta);
        } catch (Exception e) { /* log */ }
    },
    null, null, null, retryMode, null
);
```

### 7. WebUI 静态文件服务

对标 Python 版：gateway serve 预编译的 React SPA（`nanobot/web/dist/`）：

```java
// WebUiConfig.java
@Configuration
public class WebUiConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 服务前端静态资源
        registry.addResourceHandler("/webui/**")
            .addResourceLocations("classpath:/static/webui/");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // SPA fallback: 所有非 API/WS 路由 → index.html
        registry.addViewController("/webui/{path:^(?!api|ws).*}")
            .setViewName("forward:/webui/index.html");
    }
}
```

前端的 Vite dev server 配置保持不变（`vite.config.ts`）——proxy 到 Java 后端端口即可。

## 测试对齐

```java
// WebSocketChannelTest.java
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class WebSocketChannelIntegrationTest {

    @Test void connectAndSendMessage() throws Exception {
        var wsClient = new StandardWebSocketClient();
        var session = wsClient.execute(
            new TextWebSocketHandler() {
                @Override
                protected void handleTextMessage(WebSocketSession s,
                    TextMessage msg) {
                    // 验证收到 assistant_message
                }
            },
            "ws://localhost:" + port + "/ws"
        ).get(5, TimeUnit.SECONDS);

        session.sendMessage(new TextMessage("""
            {"type":"user_message","payload":{"chat_id":"test",
            "content":"Hello"}}
        """));
        // 等待回复...
    }

    @Test void sendDeltaStreamsContent() { ... }
    @Test void disconnectCleansUp() { ... }
}

// ChannelManagerTest.java
class ChannelManagerTest {
    @Test void startAllCreatesChannels() { ... }
    @Test void dispatchOutboundRoutesCorrectly() { ... }
}
```

## 验证标准

### 小闭环端到端验证

```bash
# 1. 构建前端（复用 Python 项目的 webui/）
cd webui
bun run build               # → dist/

# 2. 复制到 Java 项目的 static
cp -r dist/ ../nanobot-java/src/main/resources/static/webui/

# 3. 启动 Java 后端
cd ../nanobot-java
NANOBOT_PROVIDERS_ANTHROPIC_API_KEY=sk-ant-xxx mvn spring-boot:run

# 4. 浏览器打开 http://localhost:18790/webui/
# 5. 输入 "Hello, what tools do you have?"
# 6. 预期: Agent 回复文本 + 工具列表
```

```bash
# Console 模式
mvn spring-boot:run
# > Hello
# [Agent] Hi! How can I help you today?
# > Run 'ls'
# [Agent] I can see: ...
# > /quit
```

## 代码量估算

- BaseChannel: ~30 行
- ChannelManager: ~60 行
- WebSocketChannel: ~200 行
- WebSocketConfig: ~40 行
- ConsoleChannel: ~60 行
- WebUiConfig (静态文件): ~30 行
- 流式输出整合: ~40 行
- 测试: ~200 行
- **合计: ~660 行 + 前端 0 行（复用现有）**
