# 03 — bus 包：消息总线与运行时事件系统

**对标 Python：** `nanobot/bus/queue.py` (44行), `nanobot/bus/events.py` (53行), `nanobot/bus/progress.py` (70行), `nanobot/bus/runtime_events.py` (~252行)

## 1. Python 源码分析

### 1.1 `queue.py` — MessageBus 核心

```python
class MessageBus:
    def __init__(self):
        self.inbound: asyncio.Queue[InboundMessage] = asyncio.Queue()
        self.outbound: asyncio.Queue[OutboundMessage] = asyncio.Queue()

    async def publish_inbound(self, msg: InboundMessage):
        await self.inbound.put(msg)

    async def consume_inbound(self) -> InboundMessage:
        return await self.inbound.get()

    async def publish_outbound(self, msg: OutboundMessage):
        await self.outbound.put(msg)

    async def consume_outbound(self) -> OutboundMessage:
        return await self.outbound.get()

    @property
    def inbound_size(self) -> int:
        return self.inbound.qsize()

    @property
    def outbound_size(self) -> int:
        return self.outbound.qsize()
```

极其简单——两个无界 `asyncio.Queue`，四个方法。Channels 生产者调用 `publish_inbound`，AgentLoop 消费者调用 `consume_inbound`；AgentLoop 调用 `publish_outbound`，各 Channel 消费者调用 `consume_outbound`。

### 1.2 `events.py` — 消息事件类型

```python
@dataclass
class InboundMessage:
    channel: str
    sender_id: str
    chat_id: str
    content: str
    timestamp: datetime = field(default_factory=datetime.now)
    media: list[str] = field(default_factory=list)
    metadata: dict[str, Any] = field(default_factory=dict)
    session_key_override: str | None = None

    @property
    def session_key(self) -> str:
        return self.session_key_override or f"{self.channel}:{self.chat_id}"

@dataclass
class OutboundMessage:
    channel: str
    chat_id: str
    content: str
    reply_to: str | None = None
    media: list[str] = field(default_factory=list)
    metadata: dict[str, Any] = field(default_factory=dict)
    buttons: list[list[str]] = field(default_factory=list)
```

常量定义:
- `OUTBOUND_META_AGENT_UI = "_agent_ui"` — 结构化 UI payload 键
- `INBOUND_META_RUNTIME_CONTROL = "_runtime_control"` — 进程内控制消息键
- `RUNTIME_CONTROL_ACK = "_ack"` / `RUNTIME_CONTROL_MCP_RELOAD = "mcp_reload"` — 控制子类型

### 1.3 `progress.py` — 进度回调

```python
def build_bus_progress_callback(
    bus: MessageBus,
    msg: InboundMessage,
) -> Callable[..., Awaitable[None]]:
    async def _bus_progress(
        content: str,
        *,
        tool_hint: bool = False,
        tool_events: list[dict[str, Any]] | None = None,
        file_edit_events: list[dict[str, Any]] | None = None,
        reasoning: bool = False,
        reasoning_end: bool = False,
    ) -> None:
        meta = dict(msg.metadata or {})
        meta["_progress"] = True
        meta["_tool_hint"] = tool_hint
        if reasoning:
            meta["_reasoning_delta"] = True
        if reasoning_end:
            meta["_reasoning_end"] = True
        if tool_events:
            meta["_tool_events"] = tool_events
        if file_edit_events:
            meta["_file_edit_events"] = file_edit_events
        await bus.publish_outbound(
            OutboundMessage(
                channel=msg.channel,
                chat_id=msg.chat_id,
                content=content,
                metadata=meta,
            )
        )
    return _bus_progress
```

核心模式: 返回一个闭包，该闭包持有 `bus` 和 `msg` 引用，每次调用时将进度内容包装为 `OutboundMessage`（携带 `_progress: True` flag）并发布。

### 1.4 `runtime_events.py` — 进程内运行时事件系统

这是与 `MessageBus` 完全独立的进程内 pub/sub 系统，专为 WebUI 等内部消费者设计。事件类型有:

```
RuntimeEventContext (路由上下文, frozen)
  - channel: str
  - chat_id: str
  - session_key: str
  - metadata: dict[str, Any]

SessionTurnStarted        — turn 开始加载 session 准备构建上下文
TurnRunStatusChanged      — turn 运行状态变化 (e.g. "running", "done")
TurnCompleted             — turn 完成，含 latency_ms 和 runtime 引用
GoalStateChanged          — session 的 sustained-goal 状态变化
RuntimeModelChanged       — 活动运行时模型/preset 变化
```

`RuntimeEventBus` 是轻量级手工 pub/sub:
```python
class RuntimeEventBus:
    def __init__(self):
        self._handlers: list[tuple[type | None, callable]] = []

    def subscribe(self, handler, event_type=None) -> callable:  # 返回 unsubscribe
    async def publish(self, event):  # 按注册顺序调用，await 异步 handler
    def publish_nowait(self, event):  # 通过 asyncio.create_task 发布
```

`RuntimeEventPublisher` 是 turn-scoped 便捷发布器，管理 `_turn_latency_ms` 和 `_turn_runtime` 的 per-session 状态。

## 2. Java 实现方案

### 2.1 包结构

```
com.nanobot.bus/
├── MessageBus.java              # 消息总线核心
├── InboundMessage.java          # 入站消息 record
├── OutboundMessage.java         # 出站消息 record
├── MessageBusConstants.java     # 常量定义
├── BusProgressCallback.java     # 进度回调 (函数式接口 + 工厂)
├── RuntimeEventBus.java         # 进程内事件总线
├── RuntimeEventPublisher.java   # 便捷发布器
├── RuntimeEventContext.java     # 路由上下文 record
├── SessionTurnStarted.java      # 事件: session turn 开始
├── TurnRunStatusChanged.java    # 事件: run 状态变化
├── TurnCompleted.java           # 事件: turn 完成
├── GoalStateChanged.java        # 事件: goal 状态变化
├── RuntimeModelChanged.java     # 事件: 模型变化
└── RuntimeEvent.java            # 事件标记接口 (sealed interface)
```

### 2.2 `MessageBus.java` — 消息总线

```java
package com.nanobot.bus;

import org.springframework.stereotype.Component;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Async message bus that decouples chat channels from the agent core.
 *
 * Channels push messages to the inbound queue, and the agent processes
 * them and pushes responses to the outbound queue.
 *
 * python_analog: nanobot.bus.queue.MessageBus
 */
@Component
public class MessageBus {

    private final BlockingQueue<InboundMessage> inbound;
    private final BlockingQueue<OutboundMessage> outbound;

    /**
     * Creates a new MessageBus with unbounded queues.
     *
     * python_analog: MessageBus.__init__()
     *   self.inbound = asyncio.Queue()
     *   self.outbound = asyncio.Queue()
     */
    public MessageBus() {
        this.inbound = new LinkedBlockingQueue<>();
        this.outbound = new LinkedBlockingQueue<>();
    }

    /**
     * Creates a MessageBus with capacity-bounded queues.
     * When capacity is non-positive, queues are unbounded.
     *
     * Note: Python asyncio.Queue() is always unbounded. This constructor
     *       is an extension for backpressure control in Java.
     */
    public MessageBus(int inboundCapacity, int outboundCapacity) {
        this.inbound = (inboundCapacity > 0)
            ? new LinkedBlockingQueue<>(inboundCapacity)
            : new LinkedBlockingQueue<>();
        this.outbound = (outboundCapacity > 0)
            ? new LinkedBlockingQueue<>(outboundCapacity)
            : new LinkedBlockingQueue<>();
    }

    /**
     * Publish a message from a channel to the agent.
     *
     * python_analog: async def publish_inbound(self, msg) -> None
     *   await self.inbound.put(msg)
     *
     * In virtual threads, put() blocks the virtual thread carrier only — the
     * underlying platform thread is released.  With an unbounded queue, put()
     * returns immediately; with a bounded queue it blocks when full.
     */
    public void publishInbound(InboundMessage msg) throws InterruptedException {
        inbound.put(msg);
    }

    /**
     * Consume the next inbound message (blocks until available).
     *
     * python_analog: async def consume_inbound(self) -> InboundMessage
     *   return await self.inbound.get()
     *
     * Called by AgentLoop in a dedicated virtual thread.  Blocks that
     * virtual thread (not the platform thread) until a message arrives.
     */
    public InboundMessage consumeInbound() throws InterruptedException {
        return inbound.take();
    }

    /**
     * Publish a response from the agent to channels.
     *
     * python_analog: async def publish_outbound(self, msg) -> None
     *   await self.outbound.put(msg)
     */
    public void publishOutbound(OutboundMessage msg) throws InterruptedException {
        outbound.put(msg);
    }

    /**
     * Consume the next outbound message (blocks until available).
     *
     * python_analog: async def consume_outbound(self) -> OutboundMessage
     *   return await self.outbound.get()
     *
     * Called by each channel's send-loop virtual thread.
     */
    public OutboundMessage consumeOutbound() throws InterruptedException {
        return outbound.take();
    }

    /**
     * Number of pending inbound messages.
     *
     * python_analog: @property inbound_size -> inbound.qsize()
     */
    public int inboundSize() {
        return inbound.size();
    }

    /**
     * Number of pending outbound messages.
     *
     * python_analog: @property outbound_size -> outbound.qsize()
     */
    public int outboundSize() {
        return outbound.size();
    }
}
```

**设计要点：**

| Python | Java | 语义 |
|--------|------|------|
| `asyncio.Queue()` | `new LinkedBlockingQueue<>()` | 无界阻塞队列 |
| `asyncio.Queue(maxsize=N)` | `new LinkedBlockingQueue<>(N)` | 有界阻塞队列 |
| `await q.put(x)` | `q.put(x)` | 阻塞式入队（虚拟线程挂起） |
| `await q.get()` | `q.take()` | 阻塞式出队（虚拟线程挂起） |
| `q.qsize()` | `q.size()` | 近似值 |

### 2.3 事件 Records

#### `InboundMessage.java`

```java
package com.nanobot.bus;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Message received from a chat channel.
 *
 * python_analog: nanobot.bus.events.InboundMessage
 */
public record InboundMessage(
    String channel,           // "telegram", "discord", "slack", "websocket", etc.
    String senderId,          // User identifier
    String chatId,            // Chat/channel identifier
    String content,           // Message text
    Instant timestamp,        // When the message was received
    List<String> media,       // Media URLs or local paths
    Map<String, Object> metadata,  // Channel-specific data
    @Nullable String sessionKeyOverride  // Optional thread-scoped session override
) {

    /**
     * Compact constructor: provide defaults for optional fields.
     * Uses mutable collections to match Python dataclass default_factory=list/dict.
     */
    public InboundMessage {
        if (timestamp == null) timestamp = Instant.now();
        if (media == null) media = new ArrayList<>();
        if (metadata == null) metadata = new HashMap<>();
    }

    /**
     * Convenience constructor with minimal required fields.
     * Uses mutable collections to match Python dataclass default_factory=list/dict.
     */
    public InboundMessage(String channel, String senderId, String chatId, String content) {
        this(channel, senderId, chatId, content,
             Instant.now(), new ArrayList<>(), new HashMap<>(), null);
    }

    /**
     * Unique key for session identification.
     *
     * python_analog: @property session_key
     *   return self.session_key_override or f"{self.channel}:{self.chat_id}"
     */
    public String sessionKey() {
        if (sessionKeyOverride != null && !sessionKeyOverride.isEmpty()) {
            return sessionKeyOverride;
        }
        return channel + ":" + chatId;
    }
}
```

**注意:** Python 中 `session_key_override: str | None = None` 默认值为 `None`。在 Java record 中，`@Nullable String sessionKeyOverride` 天然支持 `null`。我们需要通过 compact constructor 设定 `Instant.now()` 等默认值。

#### `OutboundMessage.java`

```java
package com.nanobot.bus;

import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Message to send to a chat channel.
 *
 * {@code metadata} can carry routing ({@code message_id}, ...),
 * trace flags ({@code _progress}), and optional
 * {@code _agent_ui} blobs for rich clients; non-WebUI channels
 * may ignore unknown keys.
 *
 * python_analog: nanobot.bus.events.OutboundMessage
 */
public record OutboundMessage(
    String channel,
    String chatId,
    String content,
    @Nullable String replyTo,
    List<String> media,
    Map<String, Object> metadata,
    List<List<String>> buttons
) {

    /**
     * Compact constructor: provide defaults for optional fields.
     * Uses mutable collections to match Python dataclass default_factory=list/dict.
     */
    public OutboundMessage {
        if (media == null) media = new ArrayList<>();
        if (metadata == null) metadata = new HashMap<>();
        if (buttons == null) buttons = new ArrayList<>();
    }

    /**
     * Convenience constructor: content-only message with no optional fields.
     * Uses mutable collections to match Python dataclass default_factory=list/dict.
     */
    public OutboundMessage(String channel, String chatId, String content) {
        this(channel, chatId, content, null, new ArrayList<>(), new HashMap<>(), new ArrayList<>());
    }
}
```

#### `MessageBusConstants.java`

```java
package com.nanobot.bus;

/**
 * Constants shared across the bus package.
 *
 * python_analog: nanobot.bus.events module-level constants
 */
public final class MessageBusConstants {

    private MessageBusConstants() {
        // utility class — prevent instantiation
    }

    /** Optional metadata key for structured, channel-agnostic UI payloads. */
    public static final String OUTBOUND_META_AGENT_UI = "_agent_ui";

    /** Internal-only inbound metadata for in-process runtime control. */
    public static final String INBOUND_META_RUNTIME_CONTROL = "_runtime_control";

    /** Runtime control sub-types. */
    public static final String RUNTIME_CONTROL_ACK = "_ack";
    public static final String RUNTIME_CONTROL_MCP_RELOAD = "mcp_reload";
}
```

### 2.4 进度回调

在 Python 中，`build_bus_progress_callback` 返回一个闭包。在 Java 中，我们使用函数式接口 + 工厂方法。

```java
package com.nanobot.bus;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Functional interface for bus progress callbacks.
 *
 * Each call publishes an OutboundMessage with {@code _progress: true}
 * in metadata so channels can route progress updates separately from
 * final responses.
 *
 * python_analog: nanobot.bus.progress.build_bus_progress_callback()
 */
@FunctionalInterface
public interface BusProgressCallback {

    /**
     * Publish a progress update.
     *
     * @param content      the progress text
     * @param toolHint     true when this is a tool-execution hint
     * @param toolEvents   optional tool-call event payloads
     * @param fileEditEvents optional file-edit event payloads
     * @param reasoning    true when this is a reasoning/preview delta
     * @param reasoningEnd true when the reasoning block has completed
     */
    void onProgress(
        String content,
        boolean toolHint,
        @Nullable List<Map<String, Object>> toolEvents,
        @Nullable List<Map<String, Object>> fileEditEvents,
        boolean reasoning,
        boolean reasoningEnd
    ) throws InterruptedException;

    // --- factory method ---

    /**
     * Build a callback that publishes progress as OutboundMessages
     * on the given bus, scoped to the given inbound message.
     *
     * python_analog: build_bus_progress_callback(bus, msg)
     * Note: Python has two nested functions (_publish_progress and _bus_progress);
     *       _bus_progress currently just delegates to _publish_progress.
     *       Java folds this into a single lambda for simplicity.
     */
    static BusProgressCallback create(MessageBus bus, InboundMessage msg) {
        return (
            String content,
            boolean toolHint,
            List<Map<String, Object>> toolEvents,
            List<Map<String, Object>> fileEditEvents,
            boolean reasoning,
            boolean reasoningEnd
        ) -> {
            Map<String, Object> meta = new HashMap<>();
            // Copy original metadata so we don't mutate msg.metadata
            Map<String, Object> originalMeta = msg.metadata();
            if (originalMeta != null && !originalMeta.isEmpty()) {
                meta.putAll(originalMeta);
            }
            meta.put("_progress", Boolean.TRUE);
            meta.put("_tool_hint", toolHint);
            if (reasoning) {
                meta.put("_reasoning_delta", Boolean.TRUE);
            }
            if (reasoningEnd) {
                meta.put("_reasoning_end", Boolean.TRUE);
            }
            if (toolEvents != null && !toolEvents.isEmpty()) {
                meta.put("_tool_events", toolEvents);
            }
            if (fileEditEvents != null && !fileEditEvents.isEmpty()) {
                meta.put("_file_edit_events", fileEditEvents);
            }

            OutboundMessage outMsg = new OutboundMessage(
                msg.channel(),
                msg.chatId(),
                content,
                null,
                List.of(),
                Collections.unmodifiableMap(meta),
                List.of()
            );
            bus.publishOutbound(outMsg);
        };
    }
}
```

### 2.5 运行时事件系统

#### 2.5.1 密封接口 `RuntimeEvent.java`

用 Java 17+ `sealed interface` 对标 Python 的 `Union` 类型:

```java
package com.nanobot.bus;

/**
 * Tagging interface for all runtime events.
 *
 * python_analog: nanobot.bus.runtime_events.RuntimeEvent (Union type)
 */
public sealed interface RuntimeEvent
    permits SessionTurnStarted,
            TurnRunStatusChanged,
            TurnCompleted,
            GoalStateChanged,
            RuntimeModelChanged {
    // marker interface
}
```

#### 2.5.2 `RuntimeEventContext.java`

```java
package com.nanobot.bus;

import java.util.Collections;
import java.util.Map;

/**
 * Routing context common to turn-scoped runtime events.
 *
 * python_analog: nanobot.bus.runtime_events.RuntimeEventContext (frozen dataclass)
 */
public record RuntimeEventContext(
    String channel,
    String chatId,
    String sessionKey,
    Map<String, Object> metadata
) {
    public RuntimeEventContext {
        if (metadata == null) metadata = Collections.emptyMap();
    }
}
```

#### 2.5.3 五个事件 Records

```java
// SessionTurnStarted.java
package com.nanobot.bus;

/**
 * A user/system turn has loaded its session and is about to build context.
 *
 * python_analog: nanobot.bus.runtime_events.SessionTurnStarted
 */
public record SessionTurnStarted(
    RuntimeEventContext context
) implements RuntimeEvent {}


// TurnRunStatusChanged.java
package com.nanobot.bus;

import jakarta.annotation.Nullable;

/**
 * Visible run status changed for a turn.
 *
 * python_analog: nanobot.bus.runtime_events.TurnRunStatusChanged
 */
public record TurnRunStatusChanged(
    RuntimeEventContext context,
    String status,         // e.g. "running", "done", "error"
    @Nullable Double startedAt  // epoch seconds or null
) implements RuntimeEvent {}


// TurnCompleted.java
package com.nanobot.bus;

import jakarta.annotation.Nullable;

/**
 * A turn has delivered its final user-visible response.
 *
 * python_analog: nanobot.bus.runtime_events.TurnCompleted
 */
public record TurnCompleted(
    RuntimeEventContext context,
    @Nullable Integer latencyMs,
    @Nullable Object runtime  // LLMRuntime reference for title generation
) implements RuntimeEvent {}


// GoalStateChanged.java
package com.nanobot.bus;

import java.util.Collections;
import java.util.Map;

/**
 * A session's sustained-goal state changed.
 *
 * python_analog: nanobot.bus.runtime_events.GoalStateChanged
 */
public record GoalStateChanged(
    RuntimeEventContext context,
    Map<String, Object> sessionMetadata
) implements RuntimeEvent {
    public GoalStateChanged {
        if (sessionMetadata == null) sessionMetadata = Collections.emptyMap();
    }
}


// RuntimeModelChanged.java
package com.nanobot.bus;

import jakarta.annotation.Nullable;

/**
 * The active runtime model/preset changed.
 *
 * python_analog: nanobot.bus.runtime_events.RuntimeModelChanged
 */
public record RuntimeModelChanged(
    String model,
    @Nullable String modelPreset
) implements RuntimeEvent {}
```

#### 2.5.4 `RuntimeEventBus.java` — 进程内 pub/sub

```java
package com.nanobot.bus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Small in-process pub/sub bus for runtime state.
 *
 * Subscribers run in registration order. {@code publish} runs synchronous
 * handlers inline and submits async handlers via virtual threads.
 * {@code publishNowait} delegates to a virtual thread executor so the
 * caller does not need to block.
 *
 * python_analog: nanobot.bus.runtime_events.RuntimeEventBus
 */
/**
 * Synchronous runtime event handler.
 *
 * python_analog: RuntimeEventHandler where return type is None
 */
@FunctionalInterface
public interface RuntimeEventHandler {
    void handle(RuntimeEvent event) throws Exception;
}

/**
 * Asynchronous runtime event handler.
 *
 * python_analog: RuntimeEventHandler where return type is Awaitable[None]
 */
@FunctionalInterface
public interface AsyncRuntimeEventHandler {
    CompletableFuture<Void> handleAsync(RuntimeEvent event);
}

@Component
public class RuntimeEventBus {

    private static final Logger log = LoggerFactory.getLogger(RuntimeEventBus.class);

    /**
     * Handler entries: (event type filter, handler).
     * {@code null} type means "subscribe to all events".
     * Handler may be {@link RuntimeEventHandler} (sync) or
     * {@link AsyncRuntimeEventHandler} (async).
     *
     * python_analog: self._handlers: list[tuple[RuntimeEventType | None, RuntimeEventHandler]]
     */
    private final CopyOnWriteArrayList<HandlerEntry> handlers;

    private final ExecutorService asyncExecutor;

    public RuntimeEventBus() {
        this.handlers = new CopyOnWriteArrayList<>();
        this.asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    // --- subscribe (sync) ---

    /**
     * Subscribe a synchronous handler to events of a specific type.
     *
     * @param handler   the callback; runs synchronously in publish()
     * @param eventType the event class to filter on; null means "all events"
     * @return a Runnable that, when called, unsubscribes the handler
     *
     * python_analog: def subscribe(self, handler, event_type=None) -> Callable[[], None]
     */
    public Runnable subscribe(RuntimeEventHandler handler, Class<? extends RuntimeEvent> eventType) {
        HandlerEntry entry = new HandlerEntry(eventType, handler);
        handlers.add(entry);
        return () -> handlers.remove(entry);
    }

    public Runnable subscribe(RuntimeEventHandler handler) {
        return subscribe(handler, null);
    }

    // --- subscribe (async) ---

    /**
     * Subscribe an asynchronous handler to events of a specific type.
     * The returned CompletableFuture will be awaited by publish().
     */
    public Runnable subscribe(AsyncRuntimeEventHandler handler, Class<? extends RuntimeEvent> eventType) {
        HandlerEntry entry = new HandlerEntry(eventType, handler);
        handlers.add(entry);
        return () -> handlers.remove(entry);
    }

    public Runnable subscribe(AsyncRuntimeEventHandler handler) {
        return subscribe(handler, null);
    }

    // --- publish ---

    /**
     * Publish an event to all matching subscribers, in registration order.
     * Sync handlers are called inline; async handlers are awaited via
     * {@code CompletableFuture.join()} so the caller blocks until completion.
     *
     * python_analog: async def publish(self, event) -> None
     *   for event_type, handler in self._handlers:
     *       if event_type is not None and not isinstance(event, event_type): continue
     *       result = handler(event)
     *       if inspect.isawaitable(result): await result
     */
    public void publish(RuntimeEvent event) {
        for (HandlerEntry entry : handlers) {
            if (entry.typeFilter() == null
                || entry.typeFilter().isInstance(event)) {
                try {
                    Object h = entry.handler();
                    if (h instanceof AsyncRuntimeEventHandler async) {
                        async.handleAsync(event).join();  // await async handler
                    } else {
                        ((RuntimeEventHandler) h).handle(event);
                    }
                } catch (Exception ex) {
                    log.error("runtime event handler failed for {}", event.getClass().getSimpleName(), ex);
                }
            }
        }
    }

    /**
     * Publish an event asynchronously on a virtual thread, returning immediately.
     *
     * python_analog: def publish_nowait(self, event) -> None
     *   loop = asyncio.get_running_loop()
     *   loop.create_task(self.publish(event))
     */
    public void publishNowait(RuntimeEvent event) {
        asyncExecutor.submit(() -> publish(event));
    }

    // --- internal ---

    /**
     * A single subscriber entry holding an optional type filter
     * and the handler (sync or async).
     *
     * python_analog: _HandlerEntry = tuple[RuntimeEventType | None, RuntimeEventHandler]
     */
    private record HandlerEntry(
        Class<? extends RuntimeEvent> typeFilter,  // null = all events
        Object handler  // RuntimeEventHandler | AsyncRuntimeEventHandler
    ) {}
}
```

#### 2.5.5 `RuntimeEventPublisher.java` — 便捷发布器

```java
package com.nanobot.bus;

import jakarta.annotation.Nullable;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Convenience publisher for turn-scoped runtime events.
 *
 * Agent code should decide when state transitions happen; this helper
 * owns the mechanics of building event contexts and carrying per-turn
 * metadata.
 *
 * python_analog: nanobot.bus.runtime_events.RuntimeEventPublisher
 */
@Component
public class RuntimeEventPublisher {

    private final RuntimeEventBus bus;

    /** Per-session turn latency in milliseconds. */
    private final ConcurrentHashMap<String, Integer> turnLatencyMs;

    /** Per-session runtime reference (e.g. LLMRuntime for title generation). */
    private final ConcurrentHashMap<String, Object> turnRuntime;

    public RuntimeEventPublisher(RuntimeEventBus bus) {
        this.bus = bus;
        this.turnLatencyMs = new ConcurrentHashMap<>();
        this.turnRuntime = new ConcurrentHashMap<>();
    }

    /** Convenience constructor with a default bus. */
    public RuntimeEventPublisher() {
        this(new RuntimeEventBus());
    }

    // --- context builder ---

    /**
     * Build a RuntimeEventContext from the given fields.
     *
     * python_analog: @staticmethod _context(channel, chat_id, session_key, metadata)
     */
    private static RuntimeEventContext context(
        String channel,
        String chatId,
        String sessionKey,
        @Nullable Map<String, Object> metadata
    ) {
        Map<String, Object> metaCopy = (metadata != null)
            ? Collections.unmodifiableMap(metadata)
            : Collections.emptyMap();
        return new RuntimeEventContext(channel, chatId, sessionKey, metaCopy);
    }

    // --- turn state management ---

    /**
     * Record the LLM runtime for a session (used by title generation).
     *
     * python_analog: def record_turn_runtime(self, session_key, runtime)
     */
    public void recordTurnRuntime(String sessionKey, Object runtime) {
        turnRuntime.put(sessionKey, runtime);
    }

    /**
     * Record the turn latency for a session.
     *
     * python_analog: def record_turn_latency(self, session_key, latency_ms)
     */
    public void recordTurnLatency(String sessionKey, @Nullable Integer latencyMs) {
        if (latencyMs != null) {
            turnLatencyMs.put(sessionKey, latencyMs);
        }
    }

    /**
     * Clear per-turn state for a session.
     *
     * python_analog: def clear_turn(self, session_key)
     */
    public void clearTurn(String sessionKey) {
        turnLatencyMs.remove(sessionKey);
        turnRuntime.remove(sessionKey);
    }

    // --- event publishing ---

    /**
     * Publish a SessionTurnStarted event.
     *
     * python_analog: async def session_turn_started(self, msg, session_key)
     * Note: Python method is async because bus.publish() is async.
     *       In Java, publish() blocks until all handlers complete
     *       (including async handlers via CompletableFuture.join()),
     *       so this method is synchronous.
     */
    public void sessionTurnStarted(InboundMessage msg, String sessionKey) {
        RuntimeEventContext ctx = context(
            msg.channel(), msg.chatId(), sessionKey, msg.metadata()
        );
        bus.publish(new SessionTurnStarted(ctx));
    }

    /**
     * Publish a TurnRunStatusChanged event.
     *
     * python_analog: async def run_status_changed(self, msg, session_key, status, *, started_at)
     * Note: See sessionTurnStarted() for sync/async rationale.
     */
    public void runStatusChanged(
        InboundMessage msg,
        String sessionKey,
        String status,
        @Nullable Double startedAt
    ) {
        RuntimeEventContext ctx = context(
            msg.channel(), msg.chatId(), sessionKey, msg.metadata()
        );
        bus.publish(new TurnRunStatusChanged(ctx, status, startedAt));
    }

    /**
     * Publish a TurnCompleted event, consuming the stored latency and runtime.
     *
     * python_analog: async def turn_completed(self, *, channel, chat_id, session_key, metadata)
     * Note: See sessionTurnStarted() for sync/async rationale.
     */
    public void turnCompleted(
        String channel,
        String chatId,
        String sessionKey,
        @Nullable Map<String, Object> metadata
    ) {
        RuntimeEventContext ctx = context(channel, chatId, sessionKey, metadata);
        Integer latency = turnLatencyMs.remove(sessionKey);
        Object runtime = turnRuntime.remove(sessionKey);
        bus.publish(new TurnCompleted(ctx, latency, runtime));
    }

    /**
     * Publish a RuntimeModelChanged event asynchronously.
     *
     * python_analog: def runtime_model_changed(self, model, model_preset)
     */
    public void runtimeModelChanged(String model, @Nullable String modelPreset) {
        bus.publishNowait(new RuntimeModelChanged(model, modelPreset));
    }
}
```

### 2.6 `EnsureRuntimeEventPublisher` 工具方法

对标 Python 的 `ensure_runtime_event_publisher(owner)`:

```java
package com.nanobot.bus;

/**
 * Utility to lazily ensure a RuntimeEventPublisher is attached to an owner object.
 *
 * python_analog: nanobot.bus.runtime_events.ensure_runtime_event_publisher(owner)
 */
public final class RuntimeEventPublishers {

    private RuntimeEventPublishers() {
        // utility class
    }

    /**
     * Return an owner's runtime publisher, creating missing state lazily.
     *
     * The owner object must have settable fields {@code runtimeEventPublisher}
     * and {@code runtimeEvents}. In practice, the AgentLoop class exposes these
     * as instance fields and this helper mutates them.
     *
     * @param owner the object that may hold a RuntimeEventPublisher
     * @return the existing or newly-created publisher
     */
    public static RuntimeEventPublisher ensure(RuntimeEventOwner owner) {
        Object pubObj = owner.getRuntimeEventPublisher();
        if (pubObj instanceof RuntimeEventPublisher publisher) {
            return publisher;
        }

        Object busObj = owner.getRuntimeEvents();
        RuntimeEventBus bus;
        if (busObj instanceof RuntimeEventBus existing) {
            bus = existing;
        } else {
            bus = new RuntimeEventBus();
            owner.setRuntimeEvents(bus);
        }

        RuntimeEventPublisher publisher = new RuntimeEventPublisher(bus);
        owner.setRuntimeEventPublisher(publisher);
        return publisher;
    }

    /**
     * Interface that owner classes must implement to support lazy publisher creation.
     */
    public interface RuntimeEventOwner {
        @Nullable RuntimeEventPublisher getRuntimeEventPublisher();
        void setRuntimeEventPublisher(RuntimeEventPublisher publisher);

        @Nullable RuntimeEventBus getRuntimeEvents();
        void setRuntimeEvents(RuntimeEventBus bus);
    }
}
```

## 3. 线程安全分析

### 3.1 MessageBus

| 组件 | 数据结构 | 线程安全策略 |
|------|----------|-------------|
| `inbound` queue | `LinkedBlockingQueue` | 内置线程安全，所有操作原子 |
| `outbound` queue | `LinkedBlockingQueue` | 内置线程安全，所有操作原子 |
| `inboundSize()` / `outboundSize()` | `LinkedBlockingQueue.size()` | 近似值（文档注明即可），内部靠 `AtomicInteger` 维护 |

`LinkedBlockingQueue` 由 Doug Lea 实现，使用 `ReentrantLock` + `Condition` 双锁设计（put 锁和 take 锁分离），put/take 在绝大多数情况下不竞争。在虚拟线程场景下，`take()` 阻塞时 JVM 自动挂起虚拟线程并释放底层平台线程。

**不需要额外同步。** `MessageBus` 是无状态路由器。

### 3.2 事件 Records

Java `record` 的所有字段是 `final`，构造后不可变。`List<String>` 和 `Map<String, Object>` 字段应使用不可变集合（如 `List.of()` / `Map.of()` 或 `Collections.unmodifiable*`）以确保深度不可变。这不是 `record` 的强制要求，但推荐遵循。

### 3.3 RuntimeEventBus

| 数据结构 | 线程安全策略 |
|----------|-------------|
| `handlers` | `CopyOnWriteArrayList` — 读（publish 遍历）无锁，写（subscribe/unsubscribe）COW |
| `asyncExecutor` | `Executors.newVirtualThreadPerTaskExecutor()` — 每个 `publishNowait` 提交一个任务 |

`CopyOnWriteArrayList` 的优势：
- `publish()` 是高频操作（每次状态变化触发），只做遍历读取 — 无锁
- `subscribe()` / `unsubscribe()` 是低频操作（启动时注册） — COW 写开销可接受
- 订阅/取消订阅在 publish 遍历期间是安全的 — snapshot 语义

### 3.4 RuntimeEventPublisher

| 数据结构 | 线程安全策略 |
|----------|-------------|
| `turnLatencyMs` | `ConcurrentHashMap` — 所有操作线程安全 |
| `turnRuntime` | `ConcurrentHashMap` — 所有操作线程安全 |

每个 session key 在一个时间点上只有一个 turn 在运行，所以 per-key 竞争不会发生。`ConcurrentHashMap` 提供跨 key 的安全并发。

## 4. 中断与取消

Python `asyncio.Queue.get()` 等待时可通过 `asyncio.CancelledError` 取消。Java `LinkedBlockingQueue.take()` 在虚拟线程中可通过 `Thread.interrupt()` 中断，抛出 `InterruptedException`。

消费端标准模式:
```java
try {
    while (!Thread.currentThread().isInterrupted()) {
        InboundMessage msg = bus.consumeInbound();
        processTurn(msg);
    }
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    // clean shutdown — drain remaining before exit
} finally {
    // close resources
}
```

## 5. Spring 集成

`MessageBus`、`RuntimeEventBus`、`RuntimeEventPublisher` 都标记 `@Component`，由 Spring 容器管理单例。

在 `NanobotAutoConfiguration` 中无需显式声明（它们通过 `@ComponentScan` 自动发现），但可以显式声明以控制顺序:

```java
@Bean
@ConditionalOnMissingBean
public MessageBus messageBus() {
    return new MessageBus();
}

@Bean
@ConditionalOnMissingBean
public RuntimeEventBus runtimeEventBus() {
    return new RuntimeEventBus();
}

@Bean
@ConditionalOnMissingBean
public RuntimeEventPublisher runtimeEventPublisher(RuntimeEventBus bus) {
    return new RuntimeEventPublisher(bus);
}
```

## 6. 测试用例

```java
package com.nanobot.bus;

import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class MessageBusTest {

    private MessageBus bus;

    @BeforeEach
    void setUp() {
        bus = new MessageBus();
    }

    @Test
    void publishAndConsumeInbound() throws InterruptedException {
        InboundMessage msg = new InboundMessage("test", "u1", "c1", "hello");
        bus.publishInbound(msg);
        InboundMessage result = bus.consumeInbound();
        assertThat(result).isEqualTo(msg);
    }

    @Test
    void publishAndConsumeOutbound() throws InterruptedException {
        OutboundMessage msg = new OutboundMessage("test", "c1", "reply");
        bus.publishOutbound(msg);
        OutboundMessage result = bus.consumeOutbound();
        assertThat(result).isEqualTo(msg);
    }

    @Test
    void inboundSizeReflectsPending() throws InterruptedException {
        bus.publishInbound(msg("a"));
        bus.publishInbound(msg("b"));
        assertThat(bus.inboundSize()).isEqualTo(2);
        bus.consumeInbound();
        assertThat(bus.inboundSize()).isEqualTo(1);
    }

    @Test
    void consumeBlocksUntilMessage() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch consumed = new CountDownLatch(1);

        Thread vt = Thread.ofVirtual().start(() -> {
            started.countDown();
            try {
                bus.consumeInbound();
                consumed.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        // consume should be blocking
        assertThat(consumed.await(200, TimeUnit.MILLISECONDS)).isFalse();
        bus.publishInbound(msg("x"));
        assertThat(consumed.await(2, TimeUnit.SECONDS)).isTrue();
        vt.join(Duration.ofSeconds(1));
    }

    @Test
    void sessionKeyDefault() {
        InboundMessage m = new InboundMessage("tg", "u1", "c1", "hi");
        assertThat(m.sessionKey()).isEqualTo("tg:c1");
    }

    @Test
    void sessionKeyOverride() {
        InboundMessage m = new InboundMessage("tg", "u1", "c1", "hi",
            Instant.now(), List.of(), Map.of(), "override:key");
        assertThat(m.sessionKey()).isEqualTo("override:key");
    }

    @Test
    void outboundMessageDefaults() {
        OutboundMessage m = new OutboundMessage("ch", "c1", "text");
        assertThat(m.replyTo()).isNull();
        assertThat(m.media()).isEmpty();
        assertThat(m.metadata()).isEmpty();
        assertThat(m.buttons()).isEmpty();
    }

    @Test
    void boundedQueueBlocksOnFull() throws Exception {
        MessageBus bounded = new MessageBus(1, 100);
        bounded.publishInbound(msg("first"));
        // Queue is full — put in another virtual thread should block
        CompletableFuture<Void> blocked = CompletableFuture.runAsync(() -> {
            try {
                bounded.publishInbound(msg("second"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, Thread.ofVirtual().factory());
        // Give it time to block
        Thread.sleep(100);
        assertThat(blocked).isNotDone();
        // Drain one → unblocks
        bounded.consumeInbound();
        blocked.get(2, TimeUnit.SECONDS);
        assertThat(bounded.inboundSize()).isEqualTo(1);
    }

    private static InboundMessage msg(String content) {
        return new InboundMessage("test", "u1", "c1", content);
    }
}

class RuntimeEventBusTest {

    private RuntimeEventBus bus;

    @BeforeEach
    void setUp() {
        bus = new RuntimeEventBus();
    }

    @Test
    void subscribeAndPublish() {
        List<RuntimeEvent> received = new CopyOnWriteArrayList<>();
        bus.subscribe((RuntimeEventHandler) received::add, SessionTurnStarted.class);

        RuntimeEventContext ctx = new RuntimeEventContext("ws", "c1", "ws:c1", Map.of());
        bus.publish(new SessionTurnStarted(ctx));

        assertThat(received).hasSize(1);
        assertThat(received.get(0)).isInstanceOf(SessionTurnStarted.class);
    }

    @Test
    void typeFilterOnlyMatchesCorrectType() {
        List<RuntimeEvent> all = new CopyOnWriteArrayList<>();
        List<RuntimeEvent> turnOnly = new CopyOnWriteArrayList<>();
        bus.subscribe((RuntimeEventHandler) all::add);
        bus.subscribe((RuntimeEventHandler) turnOnly::add, TurnCompleted.class);

        RuntimeEventContext ctx = new RuntimeEventContext("ws", "c1", "ws:c1", Map.of());
        bus.publish(new TurnCompleted(ctx, 100, null));
        bus.publish(new RuntimeModelChanged("claude", null));

        assertThat(all).hasSize(2);
        assertThat(turnOnly).hasSize(1);
        assertThat(turnOnly.get(0)).isInstanceOf(TurnCompleted.class);
    }

    @Test
    void unsubscribeRemovesHandler() {
        List<RuntimeEvent> received = new CopyOnWriteArrayList<>();
        Runnable unsub = bus.subscribe((RuntimeEventHandler) received::add);
        unsub.run();

        RuntimeEventContext ctx = new RuntimeEventContext("ws", "c1", "ws:c1", Map.of());
        bus.publish(new SessionTurnStarted(ctx));
        assertThat(received).isEmpty();
    }

    @Test
    void handlerExceptionDoesNotCrashBus() {
        bus.subscribe((RuntimeEventHandler) (e -> { throw new RuntimeException("boom"); }));
        List<RuntimeEvent> received = new CopyOnWriteArrayList<>();
        bus.subscribe((RuntimeEventHandler) received::add);

        RuntimeEventContext ctx = new RuntimeEventContext("ws", "c1", "ws:c1", Map.of());
        bus.publish(new TurnCompleted(ctx, 100, null));

        // Second handler should still run
        assertThat(received).hasSize(1);
    }

    @Test
    void publishNowaitRunsAsynchronously() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        bus.subscribe((RuntimeEventHandler) (e -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            done.countDown();
        }));

        RuntimeEventContext ctx = new RuntimeEventContext("ws", "c1", "ws:c1", Map.of());
        long start = System.nanoTime();
        bus.publishNowait(new TurnCompleted(ctx, 100, null));
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // publishNowait should return almost immediately
        assertThat(elapsed).isLessThan(200);
        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
    }
}

class BusProgressCallbackTest {

    @Test
    void callbackPublishesOutboundMessage() throws InterruptedException {
        MessageBus bus = new MessageBus();
        InboundMessage msg = new InboundMessage("tg", "u1", "c1", "hello");
        BusProgressCallback cb = BusProgressCallback.create(bus, msg);

        cb.onProgress("thinking...", false, null, null, true, false);

        OutboundMessage out = bus.consumeOutbound();
        assertThat(out.channel()).isEqualTo("tg");
        assertThat(out.chatId()).isEqualTo("c1");
        assertThat(out.content()).isEqualTo("thinking...");
        assertThat(out.metadata()).containsEntry("_progress", Boolean.TRUE);
        assertThat(out.metadata()).containsEntry("_reasoning_delta", Boolean.TRUE);
    }

    @Test
    void callbackIncludesToolEvents() throws InterruptedException {
        MessageBus bus = new MessageBus();
        InboundMessage msg = new InboundMessage("tg", "u1", "c1", "hello");
        BusProgressCallback cb = BusProgressCallback.create(bus, msg);

        List<Map<String, Object>> toolEvents = List.of(
            Map.of("name", "read_file", "status", "running")
        );

        cb.onProgress("reading file...", true, toolEvents, null, false, false);

        OutboundMessage out = bus.consumeOutbound();
        assertThat(out.metadata()).containsEntry("_tool_hint", Boolean.TRUE);
        assertThat(out.metadata()).containsKey("_tool_events");
    }
}
```

## 7. 验证标准

```bash
cd nanobot-java
mvn test -Dtest=MessageBusTest,RuntimeEventBusTest,BusProgressCallbackTest

# 所有测试通过:
# [x] 入队 → 出队顺序正确
# [x] inboundSize()/outboundSize() 正确
# [x] 虚拟线程阻塞/唤醒语义
# [x] sessionKey() 默认值 + override
# [x] OutboundMessage 所有可选字段默认值
# [x] RuntimeEventBus 异步 handler 正确等待完成
# [x] RuntimeEventBus 类型过滤正确
# [x] RuntimeEventBus unsubscribe 正确
# [x] RuntimeEventBus handler 异常隔离
# [x] RuntimeEventBus publishNowait 异步语义
# [x] BusProgressCallback 正确构建 OutboundMessage metadata
```

## 8. 代码量估算

| 文件 | 行数 |
|------|------|
| MessageBus.java | ~80 |
| InboundMessage.java | ~50 |
| OutboundMessage.java | ~40 |
| MessageBusConstants.java | ~20 |
| BusProgressCallback.java | ~65 |
| RuntimeEvent.java (sealed interface) | ~10 |
| RuntimeEventContext.java | ~20 |
| SessionTurnStarted.java | ~12 |
| TurnRunStatusChanged.java | ~15 |
| TurnCompleted.java | ~15 |
| GoalStateChanged.java | ~15 |
| RuntimeModelChanged.java | ~12 |
| RuntimeEventBus.java (含 RuntimeEventHandler + AsyncRuntimeEventHandler) | ~130 |
| RuntimeEventPublisher.java | ~105 |
| RuntimeEventPublishers.java | ~45 |
| MessageBusTest.java | ~120 |
| RuntimeEventBusTest.java | ~100 |
| BusProgressCallbackTest.java | ~60 |
| **合计** | **~940** |
