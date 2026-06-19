# P1 — MessageBus

## 复刻目标

对标 `nanobot/bus/queue.py`（45 行） + `nanobot/bus/events.py`（54 行）。消息总线极其简单——两个队列，四个方法。Java 复刻难度最低。

## Python 源码对照

**源文件**:
- `nanobot/bus/queue.py` — MessageBus 类
- `nanobot/bus/events.py` — InboundMessage / OutboundMessage dataclass

**MessageBus 核心代码**:

```python
class MessageBus:
    def __init__(self):
        self.inbound: asyncio.Queue[InboundMessage] = asyncio.Queue()
        self.outbound: asyncio.Queue[OutboundMessage] = asyncio.Queue()

    async def publish_inbound(self, msg): ...    # await q.put(msg)
    async def consume_inbound(self) -> ...: ...   # return await q.get()
    async def publish_outbound(self, msg): ...    # await q.put(msg)
    async def consume_outbound(self) -> ...: ... # return await q.get()
```

**事件类型**:

```python
@dataclass
class InboundMessage:
    channel: str         # "telegram", "discord", "websocket"
    sender_id: str       # 用户 ID
    chat_id: str         # 会话 ID
    content: str         # 消息文本
    timestamp: datetime
    media: list[str]     # 附件 URL
    metadata: dict[str, Any]
    session_key_override: str | None  # 线程级会话覆盖
    # @property session_key → f"{channel}:{chat_id}" 或 override

@dataclass
class OutboundMessage:
    channel: str
    chat_id: str
    content: str
    reply_to: str | None
    media: list[str]
    metadata: dict[str, Any]
    buttons: list[list[str]]
```

## Java 实现方案

### 1. MessageBus

`asyncio.Queue` → `LinkedBlockingQueue`。在虚拟线程下 `take()`/`put()` 阻塞时 JVM 自动挂起/恢复虚拟线程。

```java
// MessageBus.java
@Component
public class MessageBus {
    private final BlockingQueue<InboundMessage> inbound = new LinkedBlockingQueue<>();
    private final BlockingQueue<OutboundMessage> outbound = new LinkedBlockingQueue<>();

    public void publishInbound(InboundMessage msg) {
        inbound.put(msg);  // 阻塞直到入队成功（队列无界时立即返回）
    }

    public InboundMessage consumeInbound() throws InterruptedException {
        return inbound.take();  // 阻塞直到有消息
    }

    public void publishOutbound(OutboundMessage msg) {
        outbound.put(msg);
    }

    public OutboundMessage consumeOutbound() throws InterruptedException {
        return outbound.take();
    }

    public int inboundSize() { return inbound.size(); }
    public int outboundSize() { return outbound.size(); }
}
```

**Python asyncio.Queue vs Java LinkedBlockingQueue**:

| Python | Java | 说明 |
|--------|------|------|
| `await q.put(x)` | `q.put(x)` | 同步阻塞，虚拟线程自动挂起 |
| `await q.get()` | `q.take()` | 同上 |
| `q.qsize()` | `q.size()` | 近似值 |
| `asyncio.Queue()` 无界 | `LinkedBlockingQueue()` 无界 | 默认 Integer.MAX_VALUE |

### 2. 事件类型

Python `@dataclass` → Java `record`。`metadata: dict[str, Any]` → `Map<String, Object>`。

```java
// InboundMessage.java
public record InboundMessage(
    String channel,
    String senderId,
    String chatId,
    String content,
    @Default Instant timestamp,
    @Default List<String> media,
    @Default Map<String, Object> metadata,
    @Nullable String sessionKeyOverride
) {
    // 对标 InboundMessage.session_key property
    public String sessionKey() {
        return sessionKeyOverride != null
            ? sessionKeyOverride
            : channel + ":" + chatId;
    }

    public InboundMessage {
        if (timestamp == null) timestamp = Instant.now();
        if (media == null) media = List.of();
        if (metadata == null) metadata = Map.of();
    }
}

// OutboundMessage.java
public record OutboundMessage(
    String channel,
    String chatId,
    String content,
    @Nullable String replyTo,
    @Default List<String> media,
    @Default Map<String, Object> metadata,
    @Default List<List<String>> buttons
) {
    // compact constructor for defaults
}
```

### 3. 使用方式

Channel 端（生产者）:
```java
@Component
public class WebSocketChannel extends BaseChannel {
    void onUserMessage(WsSession session, String text) {
        var msg = new InboundMessage(
            "websocket", session.userId(), session.chatId(),
            text, Instant.now(), List.of(), Map.of(), null
        );
        bus.publishInbound(msg);
    }
}
```

Agent 端（消费者）:
```java
// AgentLoop.run() 中
var msg = bus.consumeInbound();  // 阻塞等待
processTurn(msg);
```

## 关键差异处理

### 取消/中断

Python: `asyncio.CancelledError` 在 `await q.get()` 时抛出。
Java: `Thread.interrupt()` 在 `q.take()` 时抛出 `InterruptedException`。

处理方式一致——catch、清理、退出：

```java
try {
    var msg = bus.consumeInbound();
    processTurn(msg);
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    cleanup();
    return;
}
```

### 背压

Python asyncio.Queue 无界。Java LinkedBlockingQueue 默认 `Integer.MAX_VALUE` 容量。如果需要背压控制，可设 `new LinkedBlockingQueue<>(1000)` 使 `put()` 在满时阻塞。

## 测试对齐

```java
class MessageBusTest {
    private MessageBus bus;

    @BeforeEach
    void setUp() { bus = new MessageBus(); }

    @Test
    void publishAndConsumeInbound() throws InterruptedException {
        var msg = new InboundMessage("test", "u1", "c1", "hello", null, null, null, null);
        bus.publishInbound(msg);
        assertEquals(msg, bus.consumeInbound());
    }

    @Test
    void inboundSizeReflectsPending() throws InterruptedException {
        bus.publishInbound(msg("a"));
        bus.publishInbound(msg("b"));
        assertEquals(2, bus.inboundSize());
        bus.consumeInbound();
        assertEquals(1, bus.inboundSize());
    }

    @Test
    void consumeBlocksUntilMessage() {
        // 用虚拟线程验证阻塞语义
        var latch = new CountDownLatch(1);
        Thread.startVirtualThread(() -> {
            try { bus.consumeInbound(); latch.countDown(); }
            catch (InterruptedException e) { /* expected when interrupted */ }
        });
        // consume 应该阻塞
        assertFalse(latch.await(200, TimeUnit.MILLISECONDS));
        bus.publishInbound(msg("x"));
        assertTrue(latch.await(2, TimeUnit.SECONDS));
    }

    @Test
    void sessionKeyDefaultAndOverride() {
        var m1 = new InboundMessage("tg", "u1", "c1", "hi", null, null, null, null);
        assertEquals("tg:c1", m1.sessionKey());
        var m2 = new InboundMessage("tg", "u1", "c1", "hi", null, null, null, "override:key");
        assertEquals("override:key", m2.sessionKey());
    }

    private static InboundMessage msg(String content) {
        return new InboundMessage("test", "u1", "c1", content, null, null, null, null);
    }
}
```

## 验证标准

单测覆盖：
- [x] 入队 → 出队顺序正确
- [x] `inboundSize()/outboundSize()` 正确
- [x] 虚拟线程阻塞/唤醒语义
- [x] `sessionKey()` 默认值 + override
- [x] OutboundMessage 所有可选字段默认值

## 代码量估算

- MessageBus: ~50 行
- InboundMessage + OutboundMessage records: ~60 行
- 测试: ~60 行
- **合计: ~170 行**
