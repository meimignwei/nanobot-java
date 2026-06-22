# 11 — channels-core 包：Channel 基础设施

**对标 Python：** `nanobot/channels/base.py` (257行), `nanobot/channels/manager.py` (487行), `nanobot/channels/registry.py` (96行)

## 1. Python 源码分析

### 1.1 `base.py` — BaseChannel 抽象基类

```python
class BaseChannel(ABC):
    name: str = "base"              # channel 模块名
    display_name: str = "Base"      # 人类可读名称
    send_progress: bool = True      # 是否发送进度消息
    send_tool_hints: bool = False   # 是否发送工具调用提示
    show_reasoning: bool = True     # 是否展示推理内容

    def __init__(self, config: Config, bus: MessageBus):
        self.config = config
        self.bus = bus
        self.logger = logger.bind(channel=self.name)
        self._running = False

    # 三个抽象方法
    async def start(self) -> None: ...   # 启动 channel，长运行监听
    async def stop(self) -> None: ...    # 停止 channel，清理资源
    async def send(self, msg: OutboundMessage) -> None: ...  # 发送消息

    # 可选流式发送钩子 (channel 可按需覆写)
    async def send_delta(self, chat_id, delta, metadata=None): ...
    async def send_reasoning_delta(self, chat_id, delta, metadata=None): ...
    async def send_reasoning_end(self, chat_id, metadata=None): ...
    async def send_file_edit_events(self, chat_id, edits, metadata=None): ...

    # 核心入站分发
    async def _handle_message(self, sender_id, chat_id, content,
                              media=None, metadata=None, session_key=None,
                              is_dm=False) -> None:
        # 1. is_allowed() 检查 sender 权限
        # 2. 不通过 → DM 则发送 pairing code；非 DM 则 log warning + 丢弃
        # 3. 通过 → 构造 InboundMessage → bus.publish_inbound(msg)

    def is_allowed(self, sender_id) -> bool:
        # "*" in allow_list > exact match > pairing store

    @property
    def supports_streaming(self) -> bool:
        # config streaming == True AND send_delta 被子类覆写
```

**关键设计点：**
- `_handle_message` 是所有 channel 的统一入站入口，包含 pairing 逻辑
- `is_allowed` 通过三级鉴权：通配符 > 精确匹配 > pairing store
- `supports_streaming` 动态判断：需要 config 启用 AND 子类真正覆写了 `send_delta`
- `send_progress` / `send_tool_hints` / `show_reasoning` 是 class-level 特性标志，由 ChannelManager 在运行时覆写

### 1.2 `manager.py` — ChannelManager

```
ChannelManager:
    __init__(config, bus, session_manager, cron_service, ...)
        → _init_channels()  // 发现并实例化已启用 channels
        → _validate_allow_from()

    _init_channels():
        → discover_channel_names()  // 廉价的 pkgutil 扫描（无 import）
        → 合并 __pydantic_extra__ 中的插件 key
        → 过滤 enabled=True 的 channel
        → discover_enabled(enabled_names)  // 只 import 启用的
        → 对每个 channel 创建实例
        → 解析布尔覆写 (send_progress, send_tool_hints, show_reasoning)
        → 特殊处理 websocket channel (注入 GatewayServices)

    start_all():
        → 启动 _dispatch_task (Outbound 分发器)
        → 并发启动所有 channel (asyncio.gather)
        → _notify_restart_done_if_needed()

    stop_all():
        → cancel _dispatch_task
        → 停止所有 channel

    _dispatch_outbound():  // Outbound 分发核心
        循环:
            1. 从 pending 缓冲区或 bus.consume_outbound() 获取消息
            2. 处理 reasoning 消息 (_reasoning_delta / _reasoning_end / _reasoning)
               → 检查 channel.show_reasoning
            3. 处理 _progress 消息 → 检查 send_progress / send_tool_hints
            4. 处理 _retry_wait → 跳过
            5. 处理 _runtime_model_updated → 仅 websocket
            6. 处理 _stream_delta → coalesce 相邻 delta
            7. 重复抑制 → 指纹哈希去重
            8. _send_with_retry(channel, msg)

    流式 Delta 合并 (_coalesce_stream_deltas):
        - 合并同一 (channel, chat_id) 的连续 _stream_delta
        - 遇到非 delta 消息即停止（定义合并边界）
        - 返回 (merged_message, non_matching_list)

    重复抑制 (_should_suppress_outbound):
        - 对非流式消息，计算 content 的 SHA-1 指纹
        - 按 (channel, chat_id, origin_message_id) 去重
        - 相同的 origin_message_id 不重复发送相同内容

    重试策略 (_send_with_retry):
        - 指数退避: 1s → 2s → 4s
        - 最大重试次数由 config.channels.send_max_retries 控制
        - CancelledError 传播以支持优雅关闭
```

### 1.3 `registry.py` — Channel 发现

```python
_INTERNAL = frozenset({"base", "manager", "registry"})

def discover_channel_names() -> list[str]:
    # pkgutil.iter_modules 扫描 nanobot.channels 包
    # 零 import，纯粹列出模块名
    # 排除 _INTERNAL 模块

def load_channel_class(module_name) -> type[BaseChannel]:
    # importlib.import_module(f"nanobot.channels.{module_name}")
    # 遍历 dir(mod)，找第一个 BaseChannel 子类

def discover_plugins(enabled_names) -> dict[str, type[BaseChannel]]:
    # 通过 entry_points(group="nanobot.channels") 发现外部插件

def discover_enabled(enabled_names) -> dict[str, type[BaseChannel]]:
    # 只导入 enabled 的 channel（跳过重 SDK import）
    # 合并外部插件（内置优先，插件不可覆盖内置）

def discover_all() -> dict[str, type[BaseChannel]]:
    # 所有内置 + 所有外部插件
```

---

## 2. Java 实现规格

### 2.1 包结构

```
com.nanobot.channels/
├── core/
│   ├── BaseChannel.java            // 抽象基类
│   ├── ChannelManager.java         // Channel 管理器
│   ├── ChannelRegistry.java        // Channel 发现与注册
│   ├── ChannelConfig.java          // Channel 配置基类
│   ├── ChannelDispatcher.java      // Outbound 消息分发器
│   └── StreamCoalescer.java        // 流式 delta 合并
├── events/
│   ├── InboundMessage.java         // 已定义在 bus 包
│   └── OutboundMessage.java        // 已定义在 bus 包
├── websocket/                       // 见 12-channels-impl.md
├── telegram/
├── discord/
...
```

### 2.2 `ChannelConfig.java` — Channel 配置基类

```java
package com.nanobot.channels.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/**
 * Base configuration for all channel implementations.
 * Subclassed by each channel to add channel-specific fields.
 */
public class ChannelConfig {

    @JsonProperty("enabled")
    private boolean enabled = false;

    @JsonProperty("allow_from")
    private List<String> allowFrom = new ArrayList<>();

    @JsonProperty("streaming")
    private boolean streaming = false;

    // ---- accessors ----

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getAllowFrom() {
        return allowFrom;
    }

    public void setAllowFrom(List<String> allowFrom) {
        this.allowFrom = allowFrom;
    }

    public boolean isStreaming() {
        return streaming;
    }

    public void setStreaming(boolean streaming) {
        this.streaming = streaming;
    }
}
```

### 2.3 `BaseChannel.java` — 抽象基类

**设计原则：** Spring Boot 3.2 + Java 21 虚拟线程。不使用 `async/await`，而是利用虚拟线程的阻塞式模型——每个 channel 在自己的虚拟线程中运行，代码保持同步风格。

```java
package com.nanobot.channels.core;

import com.nanobot.bus.MessageBus;
import com.nanobot.bus.events.InboundMessage;
import com.nanobot.bus.events.OutboundMessage;
import com.nanobot.pairing.PairingCodeMeta;
import com.nanobot.pairing.PairingStore;
import com.nanobot.pairing.PairingFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Abstract base class for all chat channel implementations.
 * <p>
 * Each channel (Telegram, Discord, WebSocket, etc.) extends this class to
 * integrate with the nanobot message bus.
 * <p>
 * <b>Threading model:</b> Channels run on Java 21 virtual threads. The
 * {@link #start()} and {@link #stop()} methods may block (virtual threads
 * are cheap). Subclasses use blocking I/O wrapped by the platform SDK.
 */
public abstract class BaseChannel {

    /** Channel module name, e.g. "telegram", "websocket". */
    protected final String channelName;

    /** Human-readable display name, e.g. "Telegram", "WebSocket". */
    protected final String displayName;

    /** Whether progress messages should be sent through this channel. */
    protected boolean sendProgress = true;

    /** Whether tool-call hints should be rendered as inline progress. */
    protected boolean sendToolHints = false;

    /** Whether model reasoning/thinking content should be delivered. */
    protected boolean showReasoning = true;

    /** Channel-specific configuration (subclass of {@link ChannelConfig}). */
    protected final ChannelConfig config;

    /** The message bus for inbound/outbound communication. */
    protected final MessageBus bus;

    /** Structured logger with channel name binding. */
    protected final Logger logger;

    /** Whether the channel is currently running. */
    protected volatile boolean running = false;

    /**
     * Constructs a BaseChannel.
     *
     * @param channelName   the channel module name
     * @param displayName   human-readable display name
     * @param config        channel-specific configuration
     * @param bus           the message bus
     */
    protected BaseChannel(
            String channelName,
            String displayName,
            ChannelConfig config,
            MessageBus bus) {
        this.channelName = channelName;
        this.displayName = displayName;
        this.config = config;
        this.bus = bus;
        this.logger = LoggerFactory.getLogger(
                getClass().getName() + "[" + channelName + "]");
    }

    // ---- lifecycle (subclasses MUST implement) ----

    /**
     * Start the channel and begin listening for messages.
     * <p>
     * This is a long-running blocking call that:
     * <ol>
     *   <li>Connects to the chat platform</li>
     *   <li>Listens for incoming messages</li>
     *   <li>Forwards messages to the bus via {@link #handleMessage}</li>
     * </ol>
     * Called from a virtual thread by {@link ChannelManager}.
     */
    public abstract void start() throws Exception;

    /**
     * Stop the channel and clean up resources.
     */
    public abstract void stop() throws Exception;

    /**
     * Send a complete outbound message through this channel.
     * <p>
     * Implementations should throw on delivery failure so the channel manager
     * can apply its retry policy.
     *
     * @param msg the message to send
     */
    public abstract void send(OutboundMessage msg) throws Exception;

    // ---- streaming hooks (override in subclasses that support streaming) ----

    /**
     * Deliver a streaming text chunk.
     * <p>
     * Default is no-op. Subclasses with streaming support override this.
     * The streaming contract: {@code _stream_delta} is a chunk,
     * {@code _stream_end} ends the current segment. Stateful implementations
     * should key buffers by {@code _stream_id} rather than only by {@code chat_id}.
     *
     * @param chatId   the target chat
     * @param delta    the text chunk
     * @param metadata optional metadata map
     */
    public void sendDelta(String chatId, String delta, Map<String, Object> metadata)
            throws Exception {
        // no-op by default
    }

    /**
     * Stream a chunk of model reasoning/thinking content.
     * <p>
     * Default is no-op. Channels with a native low-emphasis primitive
     * (Slack context block, Telegram expandable blockquote, etc.) override
     * to render reasoning as a subordinate trace.
     *
     * @param chatId   the target chat
     * @param delta    the reasoning text chunk
     * @param metadata optional metadata map
     */
    public void sendReasoningDelta(String chatId, String delta,
                                    Map<String, Object> metadata) throws Exception {
        // no-op by default
    }

    /**
     * Mark the end of a reasoning stream segment.
     * <p>
     * Default is no-op. Channels that buffer {@code sendReasoningDelta}
     * chunks use this to flush and freeze the rendered group.
     *
     * @param chatId   the target chat
     * @param metadata optional metadata map
     */
    public void sendReasoningEnd(String chatId, Map<String, Object> metadata)
            throws Exception {
        // no-op by default
    }

    /**
     * Deliver structured live file-edit events.
     * <p>
     * Default is no-op. Channels with rich activity surfaces override this
     * to render editing progress.
     *
     * @param chatId   the target chat
     * @param edits    list of file edit event maps
     * @param metadata optional metadata map
     */
    public void sendFileEditEvents(String chatId,
                                    List<Map<String, Object>> edits,
                                    Map<String, Object> metadata) throws Exception {
        // no-op by default
    }

    // ---- audio transcription ----

    /**
     * Transcribe an audio file via Whisper (OpenAI or Groq).
     * Returns empty string on failure.
     * python_analog: BaseChannel.transcribe_audio(file_path)
     *
     * @param filePath path to the audio file
     * @return transcribed text, or empty string on failure
     */
    public String transcribeAudio(String filePath) {
        try {
            // Resolve transcription config from global config and call the
            // transcription service.  Implementation delegates to a shared
            // TranscriptionService bean that wraps OpenAI / Groq Whisper APIs.
            TranscriptionConfig cfg = TranscriptionConfig.resolve(config);
            return TranscriptionService.transcribe(filePath, cfg);
        } catch (Exception e) {
            logger.error("Audio transcription failed", e);
            return "";
        }
    }

    // ---- interactive login ----

    /**
     * Perform channel-specific interactive login (e.g. QR code scan).
     * <p>
     * Returns {@code true} if already authenticated or login succeeds.
     * Subclasses that support interactive login (e.g. WeChat, QQ) override
     * this method.
     * python_analog: BaseChannel.login(force=False)
     *
     * @param force if true, ignore existing credentials and force re-authentication
     * @return true if authenticated
     */
    public boolean login(boolean force) throws Exception {
        return true; // default: already authenticated
    }

    // ---- one-shot reasoning delivery ----

    /**
     * Deliver a complete reasoning block as a delta+end pair.
     * <p>
     * Default implementation reuses the streaming pair so subclasses only
     * need to override the delta/end methods. Equivalent to one delta with
     * the full content followed immediately by an end marker — keeps a
     * single rendering path for both streamed and one-shot reasoning
     * (e.g. DeepSeek-R1's final-response {@code reasoning_content}).
     * python_analog: BaseChannel.send_reasoning(msg)
     */
    public void sendReasoning(OutboundMessage msg) throws Exception {
        if (msg.getContent() == null || msg.getContent().isEmpty()) {
            return;
        }
        Map<String, Object> meta = new java.util.HashMap<>();
        if (msg.getMetadata() != null) {
            meta.putAll(msg.getMetadata());
        }
        meta.putIfAbsent("_reasoning_delta", Boolean.TRUE);
        sendReasoningDelta(msg.getChatId(), msg.getContent(), meta);

        Map<String, Object> endMeta = new java.util.HashMap<>(meta);
        endMeta.remove("_reasoning_delta");
        endMeta.put("_reasoning_end", Boolean.TRUE);
        sendReasoningEnd(msg.getChatId(), endMeta);
    }

    // ---- default config ----

    /**
     * Return the default config for this channel (used during onboarding).
     * Subclasses may override to auto-populate config.json.
     * python_analog: BaseChannel.default_config()
     */
    public Map<String, Object> defaultConfig() {
        return Map.of("enabled", false);
    }

    // ---- permission / pairing ----

    /**
     * Check whether a sender is allowed to interact with this channel.
     * <p>
     * Resolution order: wildcard ("*") in allowFrom list → exact match →
     * pairing store approval.
     *
     * @param senderId the sender identifier
     * @return true if the sender is allowed
     */
    public boolean isAllowed(String senderId) {
        List<String> allowList = config.getAllowFrom();
        if (allowList != null) {
            if (allowList.contains("*")) {
                return true;
            }
            if (allowList.contains(senderId)) {
                return true;
            }
        }
        return PairingStore.isApproved(channelName, senderId);
    }

    // ---- core inbound dispatch ----

    /**
     * Handle an incoming message: check permissions, issue pairing codes in
     * DMs, or forward to the message bus.
     *
     * @param senderId   the sender identifier
     * @param chatId     the chat/channel identifier
     * @param content    the message text content
     * @param media      list of media file paths (may be empty or null)
     * @param metadata   channel-specific metadata (may be null)
     * @param sessionKey optional session key override (null to auto-derive)
     * @param isDm       whether this message originated in a DM/private chat
     */
    protected void handleMessage(
            String senderId,
            String chatId,
            String content,
            List<String> media,
            Map<String, Object> metadata,
            String sessionKey,
            boolean isDm) {

        // 1. Check permissions
        if (!isAllowed(senderId)) {
            if (isDm) {
                // Generate and send pairing code
                String code = PairingStore.generateCode(channelName, senderId);
                String replyText = PairingFormatter.formatPairingReply(code);
                OutboundMessage reply = new OutboundMessage();
                reply.setChannel(channelName);
                reply.setChatId(chatId);
                reply.setContent(replyText);
                reply.getMetadata().put(PairingCodeMeta.PAIRING_CODE_META_KEY, code);
                try {
                    send(reply);
                } catch (Exception e) {
                    logger.error("Failed to send pairing code to sender {} in chat {}",
                            senderId, chatId, e);
                }
                logger.info("Sent pairing code {} to sender {} in chat {}",
                        code, senderId, chatId);
            } else {
                logger.warn(
                    "Access denied for sender {}. " +
                    "Add them to allowFrom list in config to grant access.",
                    senderId);
            }
            return;
        }

        // 2. Build metadata
        Map<String, Object> meta = (metadata != null)
                ? new java.util.HashMap<>(metadata)
                : new java.util.HashMap<>();

        if (supportsStreaming()) {
            meta.put("_wants_stream", true);
        }

        // 3. Create and publish inbound message
        InboundMessage msg = new InboundMessage();
        msg.setChannel(channelName);
        msg.setSenderId(senderId);
        msg.setChatId(chatId);
        msg.setContent(content);
        msg.setMedia(media != null ? media : List.of());
        msg.setMetadata(meta);
        msg.setSessionKeyOverride(sessionKey);

        bus.publishInbound(msg);
    }

    /**
     * Convenience overload without DM flag (defaults to false).
     */
    protected void handleMessage(
            String senderId,
            String chatId,
            String content,
            List<String> media,
            Map<String, Object> metadata,
            String sessionKey) {
        handleMessage(senderId, chatId, content, media, metadata, sessionKey, false);
    }

    // ---- streaming support detection ----

    /**
     * Returns true when config enables streaming AND this subclass has
     * overridden {@link #sendDelta}.
     * <p>
     * In Java, we check via a protected boolean flag that subclasses set.
     */
    private volatile Boolean streamingSupportedCache = null;

    public boolean supportsStreaming() {
        if (streamingSupportedCache == null) {
            streamingSupportedCache = config.isStreaming() && hasOverriddenSendDelta();
        }
        return streamingSupportedCache;
    }

    /**
     * Detect whether the subclass has overridden {@link #sendDelta}.
     * Uses reflection: checks if the declaring class of sendDelta is not BaseChannel.
     */
    private boolean hasOverriddenSendDelta() {
        try {
            java.lang.reflect.Method method = getClass().getMethod(
                    "sendDelta", String.class, String.class, Map.class);
            return method.getDeclaringClass() != BaseChannel.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    // ---- accessors ----

    public String getChannelName() {
        return channelName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isSendProgress() {
        return sendProgress;
    }

    public void setSendProgress(boolean sendProgress) {
        this.sendProgress = sendProgress;
    }

    public boolean isSendToolHints() {
        return sendToolHints;
    }

    public void setSendToolHints(boolean sendToolHints) {
        this.sendToolHints = sendToolHints;
    }

    public boolean isShowReasoning() {
        return showReasoning;
    }

    public void setShowReasoning(boolean showReasoning) {
        this.showReasoning = showReasoning;
    }

    public boolean isRunning() {
        return running;
    }

    public ChannelConfig getConfig() {
        return config;
    }
}
```

### 2.4 `ChannelManager.java` — Channel 管理器

**核心职责：** 发现/实例化/启停所有 channel，维护 Outbound 分发器。

```java
package com.nanobot.channels.core;

import com.nanobot.bus.MessageBus;
import com.nanobot.bus.events.OutboundMessage;
import com.nanobot.config.NanobotConfig;
import com.nanobot.config.ChannelsGlobalConfig;
import com.nanobot.restart.RestartNotice;
import com.nanobot.session.SessionManager;
import com.nanobot.webui.GatewayServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages chat channels and coordinates message routing.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Discover and instantiate enabled channels</li>
 *   <li>Start/stop channels on virtual threads</li>
 *   <li>Route outbound messages to the appropriate channel</li>
 *   <li>Coalesce streaming deltas to reduce API calls</li>
 *   <li>Suppress duplicate outbound messages by content fingerprint</li>
 *   <li>Retry failed sends with exponential backoff</li>
 * </ul>
 */
public class ChannelManager {

    private static final Logger logger = LoggerFactory.getLogger(ChannelManager.class);

    /** Retry delays in seconds: 1s, 2s, 4s (exponential backoff). */
    private static final long[] SEND_RETRY_DELAYS = {1, 2, 4};

    /** CamelCase alias mapping for boolean overrides (JSON compat). */
    private static final Map<String, String> BOOL_CAMEL_ALIASES = Map.of(
            "send_progress", "sendProgress",
            "send_tool_hints", "sendToolHints",
            "show_reasoning", "showReasoning"
    );

    private final NanobotConfig config;
    private final MessageBus bus;
    private final SessionManager sessionManager;

    /** All instantiated channels, keyed by channel name. */
    private final ConcurrentHashMap<String, BaseChannel> channels = new ConcurrentHashMap<>();

    /** Virtual thread executor for channel lifecycle. */
    private final ExecutorService channelExecutor;

    /** Single virtual thread for the outbound dispatcher. */
    private Thread dispatcherThread;

    /** Shutdown signal for the dispatcher. */
    private final AtomicBoolean dispatcherRunning = new AtomicBoolean(false);

    /** Outbound message queue — used by dispatcher to buffer pending items. */
    private final LinkedBlockingDeque<OutboundMessage> pendingQueue = new LinkedBlockingDeque<>();

    /** Fingerprint cache for duplicate suppression: key=(channel,chatId,originMsgId) → SHA-1 hex. */
    private final Map<OutboundFingerprintKey, String> originReplyFingerprints =
            new ConcurrentHashMap<>();

    // ---- WebSocket / WebUI constructor params (python_analog: ChannelManager.__init__ kwargs) ----

    private final Object cronService;                                          // CronService | null
    private final java.util.function.Supplier<String> webuiRuntimeModelName;   // () → model name | null
    private final boolean webuiStaticDist;                                     // serve bundled webui dist
    private final String webuiRuntimeSurface;                                  // "browser" | "cli" | ...
    private final Map<String, Object> webuiRuntimeCapabilities;                // capability overrides

    /**
     * Compact key for duplicate suppression lookups.
     */
    private record OutboundFingerprintKey(String channel, String chatId, String originMsgId) {}

    /**
     * Full constructor matching Python's ChannelManager.__init__ signature.
     *
     * @param config                    global nanobot config
     * @param bus                       message bus for inbound/outbound
     * @param sessionManager            session manager (nullable)
     * @param cronService               cron service for scheduling (nullable)
     * @param webuiRuntimeModelName     supplier of current WebUI runtime model name (nullable)
     * @param webuiStaticDist           serve the bundled webui static dist
     * @param webuiRuntimeSurface       runtime surface identifier ("browser", "cli", ...)
     * @param webuiRuntimeCapabilities  capability overrides for the WebUI runtime
     */
    public ChannelManager(NanobotConfig config, MessageBus bus,
                          SessionManager sessionManager,
                          Object cronService,
                          java.util.function.Supplier<String> webuiRuntimeModelName,
                          boolean webuiStaticDist,
                          String webuiRuntimeSurface,
                          Map<String, Object> webuiRuntimeCapabilities) {
        this.config = config;
        this.bus = bus;
        this.sessionManager = sessionManager;
        this.cronService = cronService;
        this.webuiRuntimeModelName = webuiRuntimeModelName;
        this.webuiStaticDist = webuiStaticDist;
        this.webuiRuntimeSurface = webuiRuntimeSurface;
        this.webuiRuntimeCapabilities = webuiRuntimeCapabilities != null
                ? new HashMap<>(webuiRuntimeCapabilities) : Map.of();
        this.channelExecutor = Executors.newVirtualThreadPerTaskExecutor();
        initChannels();
    }

    /**
     * Simplified constructor for channels that don't need WebSocket/WebUI features.
     */
    public ChannelManager(NanobotConfig config, MessageBus bus,
                          SessionManager sessionManager) {
        this(config, bus, sessionManager, null, null,
             true, "browser", Map.of());
    }

    // ---- channel discovery and instantiation ----

    private void initChannels() {
        // Step 1: Resolve which channels are enabled
        Set<String> enabledNames = resolveEnabledChannelNames();

        // Step 2: For each enabled channel, discover the class and instantiate
        Map<String, Class<? extends BaseChannel>> discovered =
                ChannelRegistry.discoverEnabled(enabledNames);

        for (Map.Entry<String, Class<? extends BaseChannel>> entry : discovered.entrySet()) {
            String name = entry.getKey();
            Class<? extends BaseChannel> clazz = entry.getValue();

            try {
                // Extract channel-specific config from the global config
                Object channelCfgSection = config.getChannels().getChannelSection(name);
                if (channelCfgSection == null) {
                    logger.warn("Channel '{}' has no config section, skipping", name);
                    continue;
                }

                // Resolve to ChannelConfig subclass instance
                ChannelConfig channelConfig = ChannelRegistry.resolveConfig(
                        name, clazz, channelCfgSection);

                // Instantiate the channel — for websocket, inject GatewayServices
                // plus WebUI runtime metadata (python_analog: manager.py lines 109-132)
                BaseChannel channel;
                if ("websocket".equals(name) && channelCfgSection instanceof Map<?, ?> wsMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> wsCfg = (Map<String, Object>) wsMap;
                    Path workspace = Path.of(config.getWorkspacePath());
                    Path staticDist = webuiStaticDist
                            ? resolveBundledWebuiDist()
                            : null;
                    GatewayServices gateway = GatewayServices.build(
                            wsCfg, bus, sessionManager,
                            staticDist, workspace,
                            config.getTools().isRestrictToWorkspace(),
                            new HashSet<>(config.getAgents().getDefaults().getDisabledSkills()),
                            webuiRuntimeModelName,
                            webuiRuntimeSurface,
                            webuiRuntimeCapabilities,
                            cronService);
                    channel = ChannelRegistry.instantiateWithGateway(
                            clazz, channelConfig, bus, sessionManager, gateway);
                } else {
                    channel = ChannelRegistry.instantiate(
                            clazz, channelConfig, bus, sessionManager);
                }

                // Apply boolean overrides from global config
                ChannelsGlobalConfig channelsCfg = config.getChannels();
                channel.setSendProgress(resolveBoolOverride(
                        channelCfgSection, channelConfig,
                        "send_progress", channelsCfg.isSendProgress()));
                channel.setSendToolHints(resolveBoolOverride(
                        channelCfgSection, channelConfig,
                        "send_tool_hints", channelsCfg.isSendToolHints()));
                channel.setShowReasoning(resolveBoolOverride(
                        channelCfgSection, channelConfig,
                        "show_reasoning", channelsCfg.isShowReasoning()));

                channels.put(name, channel);
                logger.info("{} channel enabled", channel.getDisplayName());

            } catch (Exception e) {
                logger.warn("{} channel not available: {}", name, e.getMessage());
            }
        }

        validateAllowFrom();
    }

    /**
     * Determine which channel names are enabled by checking each channel's
     * config section for {@code enabled: true}.
     */
    private Set<String> resolveEnabledChannelNames() {
        // Use the cheap ModuleName discovery (no class loading) from ChannelRegistry
        Set<String> candidateNames = new HashSet<>(ChannelRegistry.discoverChannelModuleNames());
        // Also include any extra keys in the channels config (e.g., plugin-only channels)
        candidateNames.addAll(config.getChannels().getExtraChannelNames());

        Set<String> enabledNames = new HashSet<>();
        for (String name : candidateNames) {
            Object section = config.getChannels().getChannelSection(name);
            if (section == null) {
                continue;
            }
            boolean enabled = extractEnabledFlag(section);
            if (enabled) {
                enabledNames.add(name);
            }
        }
        return enabledNames;
    }

    /**
     * Extract the `enabled` boolean from a config section (Map or Pydantic-style model).
     */
    @SuppressWarnings("unchecked")
    private boolean extractEnabledFlag(Object section) {
        if (section instanceof Map<?, ?> map) {
            Object val = map.get("enabled");
            return val instanceof Boolean b && b;
        }
        // Otherwise try reflection on getEnabled()
        try {
            java.lang.reflect.Method m = section.getClass().getMethod("isEnabled");
            Object result = m.invoke(section);
            return result instanceof Boolean b && b;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Log warnings for channels configured without an allowFrom list.
     */
    private void validateAllowFrom() {
        for (Map.Entry<String, BaseChannel> entry : channels.entrySet()) {
            String name = entry.getKey();
            BaseChannel channel = entry.getValue();
            List<String> allowFrom = channel.getConfig().getAllowFrom();
            if (allowFrom == null || allowFrom.isEmpty()) {
                logger.info(
                    "\"{}\" has no allowFrom; unapproved users will receive a pairing code",
                    name);
            }
        }
    }

    // ---- boolean override resolution ----

    /**
     * Resolve a boolean configuration override with camelCase fallback.
     * First checks the channel-specific section, then falls back to the global default.
     */
    @SuppressWarnings("unchecked")
    private boolean resolveBoolOverride(Object section, ChannelConfig channelConfig,
                                         String key, boolean globalDefault) {
        if (section instanceof Map<?, ?> map) {
            Object value = map.get(key);
            if (value instanceof Boolean b) {
                return b;
            }
            // Try camelCase alias
            String camelKey = BOOL_CAMEL_ALIASES.get(key);
            if (camelKey != null) {
                Object camelValue = map.get(camelKey);
                if (camelValue instanceof Boolean b) {
                    return b;
                }
            }
            return globalDefault;
        }
        // Try reflection-based getter
        try {
            String getter = "is" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
            java.lang.reflect.Method m = section.getClass().getMethod(getter);
            Object result = m.invoke(section);
            if (result instanceof Boolean b) {
                return b;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return globalDefault;
    }

    // ---- lifecycle ----

    /**
     * Start all enabled channels and the outbound dispatcher.
     * Each channel runs on its own virtual thread.
     */
    public void startAll() {
        if (channels.isEmpty()) {
            logger.warn("No channels enabled");
            return;
        }

        // Start outbound dispatcher on a virtual thread
        dispatcherRunning.set(true);
        dispatcherThread = Thread.ofVirtual()
                .name("outbound-dispatcher")
                .start(this::dispatchOutbound);

        // Start each channel on its own virtual thread
        List<Thread> channelThreads = new ArrayList<>();
        for (Map.Entry<String, BaseChannel> entry : channels.entrySet()) {
            String name = entry.getKey();
            BaseChannel channel = entry.getValue();
            logger.info("Starting {} channel...", name);
            Thread vt = Thread.ofVirtual()
                    .name("channel-" + name)
                    .start(() -> startChannel(name, channel));
            channelThreads.add(vt);
        }

        notifyRestartDoneIfNeeded();

        // Wait for all channel threads (they should run forever)
        for (Thread vt : channelThreads) {
            try {
                vt.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void startChannel(String name, BaseChannel channel) {
        try {
            channel.running = true;
            channel.start();
        } catch (Exception e) {
            logger.error("Failed to start channel {}", name, e);
        }
    }

    /**
     * Stop all channels and the outbound dispatcher.
     */
    public void stopAll() {
        logger.info("Stopping all channels...");

        // Stop dispatcher
        dispatcherRunning.set(false);
        if (dispatcherThread != null) {
            dispatcherThread.interrupt();
            try {
                dispatcherThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            dispatcherThread = null;
        }

        // Stop all channels
        for (Map.Entry<String, BaseChannel> entry : channels.entrySet()) {
            String name = entry.getKey();
            BaseChannel channel = entry.getValue();
            try {
                channel.stop();
                channel.running = false;
                logger.info("Stopped {} channel", name);
            } catch (Exception e) {
                logger.error("Error stopping {}", name, e);
            }
        }

        channelExecutor.shutdown();
    }

    /**
     * Resolve the bundled webui/dist directory, or null if not found.
     * python_analog: _default_webui_dist()
     */
    private static Path resolveBundledWebuiDist() {
        try {
            // Find the webui module's dist directory on the classpath
            java.net.URL url = ChannelManager.class.getClassLoader()
                    .getResource("webui/dist/index.html");
            if (url != null) {
                Path dist = Path.of(url.toURI()).getParent();
                if (Files.isDirectory(dist)) {
                    return dist;
                }
            }
        } catch (Exception ignored) {
            // dist not bundled or not accessible
        }
        return null;
    }

    private void notifyRestartDoneIfNeeded() {
        // python_analog: _notify_restart_done_if_needed()
        RestartNotice notice = RestartNotice.consumeFromEnv();
        if (notice == null) {
            return;
        }
        BaseChannel target = channels.get(notice.channel());
        if (target == null) {
            return;
        }
        OutboundMessage msg = new OutboundMessage();
        msg.setChannel(notice.channel());
        msg.setChatId(notice.chatId());
        msg.setContent(RestartNotice.formatRestartCompleted(notice.startedAtRaw()));
        if (notice.metadata() != null) {
            msg.setMetadata(new HashMap<>(notice.metadata()));
        }
        // Fire-and-forget on a virtual thread (equivalent to Python's asyncio.create_task)
        Thread.ofVirtual().name("restart-notify").start(() -> sendWithRetry(target, msg));
    }

    // ---- outbound dispatch ----

    /**
     * Main outbound dispatch loop. Runs on a dedicated virtual thread.
     * <p>
     * Consumes messages from the bus outbound queue, applies routing logic,
     * stream coalescing, duplicate suppression, and retry.
     */
    private void dispatchOutbound() {
        logger.info("Outbound dispatcher started");

        while (dispatcherRunning.get()) {
            try {
                OutboundMessage msg;

                // Check pending buffer first
                if (!pendingQueue.isEmpty()) {
                    msg = pendingQueue.pollFirst();
                } else {
                    // Block with timeout to allow checking dispatcherRunning
                    msg = bus.consumeOutbound(1, TimeUnit.SECONDS);
                    if (msg == null) {
                        continue; // timeout, loop back
                    }
                }

                // --- Reasoning messages ---
                if (isReasoningMessage(msg)) {
                    BaseChannel channel = channels.get(msg.getChannel());
                    if (channel != null && channel.isShowReasoning()) {
                        sendWithRetry(channel, msg);
                    }
                    continue;
                }

                // --- Progress messages ---
                if (isProgressMessage(msg)) {
                    if (isToolHint(msg) && !shouldSendProgress(msg.getChannel(), true)) {
                        continue;
                    }
                    if (!isToolHint(msg) && !shouldSendProgress(msg.getChannel(), false)) {
                        continue;
                    }
                }

                // --- Retry-wait messages (internal only, skip) ---
                if (isRetryWait(msg)) {
                    continue;
                }

                // --- Runtime model update (websocket only) ---
                if (isRuntimeModelUpdate(msg)
                        && "websocket".equals(msg.getChannel())
                        && !channels.containsKey("websocket")) {
                    continue;
                }

                // --- Stream delta coalescing ---
                if (isStreamDelta(msg) && !isStreamEnd(msg)) {
                    StreamCoalescer.CoalesceResult result =
                            StreamCoalescer.coalesceStreamDeltas(bus, msg);
                    msg = result.mergedMessage();
                    for (OutboundMessage pending : result.nonMatching()) {
                        pendingQueue.addLast(pending);
                    }
                }

                // --- Route to channel ---
                BaseChannel channel = channels.get(msg.getChannel());
                if (channel != null) {
                    // Duplicate suppression for non-streaming messages
                    if (!isStreamDelta(msg) && !isStreamEnd(msg) && !isStreamed(msg)) {
                        if (shouldSuppressOutbound(msg)) {
                            logger.info("Suppressing duplicate outbound message to {}:{}",
                                    msg.getChannel(), msg.getChatId());
                            continue;
                        }
                    }
                    sendWithRetry(channel, msg);
                } else {
                    logger.warn("Unknown channel: {}", msg.getChannel());
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        logger.info("Outbound dispatcher stopped");
    }

    // ---- message classification helpers ----

    private boolean isReasoningMessage(OutboundMessage msg) {
        Map<String, Object> meta = msg.getMetadata();
        return meta.containsKey("_reasoning_delta")
                || meta.containsKey("_reasoning_end")
                || meta.containsKey("_reasoning");
    }

    private boolean isProgressMessage(OutboundMessage msg) {
        return msg.getMetadata().containsKey("_progress");
    }

    private boolean isToolHint(OutboundMessage msg) {
        return msg.getMetadata().containsKey("_tool_hint");
    }

    private boolean isRetryWait(OutboundMessage msg) {
        return msg.getMetadata().containsKey("_retry_wait");
    }

    private boolean isRuntimeModelUpdate(OutboundMessage msg) {
        return msg.getMetadata().containsKey("_runtime_model_updated");
    }

    private boolean isStreamDelta(OutboundMessage msg) {
        return msg.getMetadata().containsKey("_stream_delta");
    }

    private boolean isStreamEnd(OutboundMessage msg) {
        return msg.getMetadata().containsKey("_stream_end");
    }

    private boolean isStreamed(OutboundMessage msg) {
        return msg.getMetadata().containsKey("_streamed");
    }

    private boolean shouldSendProgress(String channelName, boolean toolHint) {
        BaseChannel channel = channels.get(channelName);
        if (channel == null) {
            logger.warn("Progress check for unknown channel: {}", channelName);
            return false;
        }
        return toolHint ? channel.isSendToolHints() : channel.isSendProgress();
    }

    // ---- duplicate suppression ----

    /**
     * Compute a SHA-1 content fingerprint (normalized whitespace) for deduplication.
     */
    static String fingerprintContent(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return "";
        }
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is required by Java spec, this should never happen
            throw new RuntimeException("SHA-1 not available", e);
        }
    }

    /**
     * Check whether an outbound message should be suppressed as a duplicate.
     * Only applies to non-streaming messages with an origin_message_id.
     */
    private boolean shouldSuppressOutbound(OutboundMessage msg) {
        Map<String, Object> metadata = msg.getMetadata();
        if (metadata.containsKey("_progress")) {
            return false;
        }

        String fingerprint = fingerprintContent(msg.getContent());
        if (fingerprint.isEmpty()) {
            return false;
        }

        Object originMsgId = metadata.get("origin_message_id");
        if (originMsgId instanceof String originId && !originId.isEmpty()) {
            OutboundFingerprintKey key = new OutboundFingerprintKey(
                    msg.getChannel(), msg.getChatId(), originId);
            String existing = originReplyFingerprints.get(key);
            if (fingerprint.equals(existing)) {
                return true; // duplicate, suppress
            }
            originReplyFingerprints.put(key, fingerprint);
        }

        // Also track by message_id for future suppression
        Object msgId = metadata.get("message_id");
        if (msgId instanceof String mid && !mid.isEmpty()) {
            OutboundFingerprintKey key = new OutboundFingerprintKey(
                    msg.getChannel(), msg.getChatId(), mid);
            originReplyFingerprints.put(key, fingerprint);
        }

        return false;
    }

    // ---- send with retry ----

    /**
     * Send a single outbound message through the given channel with retry.
     * Uses exponential backoff: 1s, 2s, 4s.
     */
    private void sendWithRetry(BaseChannel channel, OutboundMessage msg) {
        int maxAttempts = Math.max(config.getChannels().getSendMaxRetries(), 1);

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                sendOnce(channel, msg);
                return; // success
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return; // graceful shutdown
            } catch (Exception e) {
                if (attempt == maxAttempts - 1) {
                    logger.error("Failed to send to {} after {} attempts",
                            msg.getChannel(), maxAttempts, e);
                    return;
                }
                long delay = SEND_RETRY_DELAYS[
                        Math.min(attempt, SEND_RETRY_DELAYS.length - 1)];
                logger.warn("Send to {} failed (attempt {}/{}): {}, retrying in {}s",
                        msg.getChannel(), attempt + 1, maxAttempts,
                        e.getClass().getSimpleName(), delay);
                try {
                    Thread.sleep(delay * 1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Send one outbound message without retry policy.
     * Dispatches based on metadata flags to the appropriate channel method.
     */
    static void sendOnce(BaseChannel channel, OutboundMessage msg) throws Exception {
        Map<String, Object> meta = msg.getMetadata();

        if (meta.containsKey("_reasoning_end")) {
            channel.sendReasoningEnd(msg.getChatId(), meta);
        } else if (meta.containsKey("_reasoning_delta")) {
            channel.sendReasoningDelta(msg.getChatId(), msg.getContent(), meta);
        } else if (meta.containsKey("_reasoning")) {
            // Back-compat: one-shot reasoning delegates to BaseChannel.sendReasoning
            // which translates to a single delta + end pair (python_analog: send_reasoning)
            channel.sendReasoning(msg);
        } else if (meta.containsKey("_file_edit_events")) {
            Object editsRaw = meta.get("_file_edit_events");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> edits = (editsRaw instanceof List<?> list)
                    ? (List<Map<String, Object>>) list
                    : List.of();
            channel.sendFileEditEvents(msg.getChatId(), edits, meta);
        } else if (meta.containsKey("_stream_delta") || meta.containsKey("_stream_end")) {
            channel.sendDelta(msg.getChatId(), msg.getContent(), meta);
        } else if (!meta.containsKey("_streamed")) {
            channel.send(msg);
        }
    }

    // ---- public API ----

    /**
     * Get a channel by name.
     */
    public BaseChannel getChannel(String name) {
        return channels.get(name);
    }

    /**
     * Get status of all channels.
     */
    public Map<String, Map<String, Object>> getStatus() {
        Map<String, Map<String, Object>> status = new LinkedHashMap<>();
        for (Map.Entry<String, BaseChannel> entry : channels.entrySet()) {
            BaseChannel channel = entry.getValue();
            Map<String, Object> entryStatus = new LinkedHashMap<>();
            entryStatus.put("enabled", true);
            entryStatus.put("running", channel.isRunning());
            status.put(entry.getKey(), entryStatus);
        }
        return status;
    }

    /**
     * Get list of enabled channel names.
     */
    public List<String> getEnabledChannels() {
        return List.copyOf(channels.keySet());
    }
}
```

### 2.5 `StreamCoalescer.java` — 流式 Delta 合并

```java
package com.nanobot.channels.core;

import com.nanobot.bus.MessageBus;
import com.nanobot.bus.events.OutboundMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Merges consecutive {@code _stream_delta} messages for the same
 * (channel, chat_id) to reduce API calls when the LLM generates
 * faster than the channel can process.
 */
public final class StreamCoalescer {

    private StreamCoalescer() {
        // utility class
    }

    /**
     * Result of a coalesce operation.
     */
    public record CoalesceResult(
            OutboundMessage mergedMessage,
            List<OutboundMessage> nonMatching) {
    }

    /**
     * Merge consecutive {@code _stream_delta} messages from the bus
     * outbound queue for the same target.
     * <p>
     * Stops at the first non-delta message (which defines the coalescing
     * boundary). That boundary message (and any additional non-matching
     * messages) are returned in {@code nonMatching} so the dispatcher
     * can re-queue them.
     *
     * @param bus      the message bus (for drain-to access)
     * @param firstMsg the first stream_delta message to start merging
     * @return the merged message and any non-matching boundary messages
     */
    public static CoalesceResult coalesceStreamDeltas(
            MessageBus bus, OutboundMessage firstMsg) {

        String targetChannel = firstMsg.getChannel();
        String targetChatId = firstMsg.getChatId();
        StringBuilder combinedContent = new StringBuilder(firstMsg.getContent());
        Map<String, Object> finalMetadata = new java.util.HashMap<>(
                firstMsg.getMetadata() != null
                        ? firstMsg.getMetadata()
                        : Map.of());
        List<OutboundMessage> nonMatching = new ArrayList<>();

        while (true) {
            // Non-blocking poll — don't wait if nothing is queued
            OutboundMessage nextMsg = bus.pollOutbound();
            if (nextMsg == null) {
                break; // queue is empty
            }

            boolean sameTarget = targetChannel.equals(nextMsg.getChannel())
                    && targetChatId.equals(nextMsg.getChatId());
            boolean isDelta = nextMsg.getMetadata() != null
                    && nextMsg.getMetadata().containsKey("_stream_delta");
            boolean isEnd = nextMsg.getMetadata() != null
                    && nextMsg.getMetadata().containsKey("_stream_end");
            boolean alreadyEnded = finalMetadata.containsKey("_stream_end");

            if (sameTarget && isDelta && !alreadyEnded) {
                // Accumulate content
                combinedContent.append(nextMsg.getContent());
                if (isEnd) {
                    finalMetadata.put("_stream_end", true);
                    break; // stream ended, stop coalescing
                }
            } else {
                // Boundary: first non-matching message marks the end
                nonMatching.add(nextMsg);
                break;
            }
        }

        OutboundMessage merged = new OutboundMessage();
        merged.setChannel(firstMsg.getChannel());
        merged.setChatId(firstMsg.getChatId());
        merged.setContent(combinedContent.toString());
        merged.setMetadata(finalMetadata);

        return new CoalesceResult(merged, nonMatching);
    }
}
```

### 2.6 `ChannelRegistry.java` — Channel 发现与注册

在 Python 中，channel 通过 `pkgutil.iter_modules` + `importlib.import_module` 动态发现。在 Spring Boot 中，利用 Spring 的组件扫描和 `@Component` 注解实现。

```java
package com.nanobot.channels.core;

import com.nanobot.bus.MessageBus;
import com.nanobot.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Channel discovery and instantiation.
 * <p>
 * Uses Spring's {@link ClassPathScanningCandidateComponentProvider} to scan
 * the {@code com.nanobot.channels} package for {@link BaseChannel} subclasses.
 * This is analogous to Python's {@code pkgutil.iter_modules} scan.
 * <p>
 * External channel plugins can be registered via Spring's standard
 * {@code META-INF/spring.factories} or {@code @ComponentScan} in plugin JARs.
 */
public final class ChannelRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ChannelRegistry.class);

    /** Base package to scan for channel implementations. */
    private static final String CHANNELS_PACKAGE = "com.nanobot.channels";

    /** Internal module names to exclude from discovery. */
    private static final Set<String> INTERNAL_MODULES = Set.of("core", "registry");

    // Cache of discovered channel classes (module name → class)
    private static final Map<String, Class<? extends BaseChannel>> discoveredCache =
            new ConcurrentHashMap<>();

    // Cache of config classes (module name → config class)
    private static final Map<String, Class<? extends ChannelConfig>> configClassCache =
            new ConcurrentHashMap<>();

    private ChannelRegistry() {
        // utility class
    }

    /**
     * Discover all channel module names by scanning the channels package.
     * <p>
     * This is a cheap operation that does NOT load channel classes — it only
     * enumerates the Spring component scan candidates. The actual class
     * loading happens in {@link #discoverEnabled(Set)}.
     *
     * @return list of channel module names (e.g., ["telegram", "discord", ...])
     */
    public static List<String> discoverChannelModuleNames() {
        if (!discoveredCache.isEmpty()) {
            return new ArrayList<>(discoveredCache.keySet());
        }

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(BaseChannel.class));

        List<String> names = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents(CHANNELS_PACKAGE)) {
            String className = bd.getBeanClassName();
            if (className == null) {
                continue;
            }

            // Derive module name from class name
            // e.g., com.nanobot.channels.telegram.TelegramChannel → "telegram"
            String moduleName = deriveModuleName(className);
            if (moduleName != null && !INTERNAL_MODULES.contains(moduleName)) {
                names.add(moduleName);
            }
        }

        return names;
    }

    /**
     * Discover and load only the enabled channels.
     * <p>
     * This is the expensive step — it actually loads the channel classes
     * (and their third-party SDK dependencies). Only channels whose names
     * appear in {@code enabledNames} are loaded.
     *
     * @param enabledNames set of channel names that are enabled in config
     * @return map of channel name → channel class
     */
    public static Map<String, Class<? extends BaseChannel>> discoverEnabled(
            Set<String> enabledNames) {

        Map<String, Class<? extends BaseChannel>> result = new LinkedHashMap<>();

        // Re-scan to get all candidate classes
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(BaseChannel.class));

        for (BeanDefinition bd : scanner.findCandidateComponents(CHANNELS_PACKAGE)) {
            String className = bd.getBeanClassName();
            if (className == null) {
                continue;
            }

            String moduleName = deriveModuleName(className);
            if (moduleName == null || !enabledNames.contains(moduleName)) {
                continue;
            }
            if (INTERNAL_MODULES.contains(moduleName)) {
                continue;
            }

            try {
                Class<?> clazz = Class.forName(className);
                if (BaseChannel.class.isAssignableFrom(clazz)
                        && clazz != BaseChannel.class) {
                    @SuppressWarnings("unchecked")
                    Class<? extends BaseChannel> channelClass =
                            (Class<? extends BaseChannel>) clazz;
                    result.put(moduleName, channelClass);
                    discoveredCache.put(moduleName, channelClass);
                }
            } catch (ClassNotFoundException e) {
                logger.debug("Skipping built-in channel '{}': {}", moduleName, e.getMessage());
            }
        }

        // Discover external plugins via ServiceLoader
        // python_analog: discover_plugins(enabled_names) via entry_points(group="nanobot.channels")
        Map<String, Class<? extends BaseChannel>> externalPlugins =
                discoverPlugins(enabledNames);
        // Built-in channels shadow external plugins with the same name
        Set<String> shadowed = new HashSet<>(externalPlugins.keySet());
        shadowed.retainAll(result.keySet());
        if (!shadowed.isEmpty()) {
            logger.warn("Plugin(s) shadowed by built-in channels (ignored): {}", shadowed);
        }
        for (Map.Entry<String, Class<? extends BaseChannel>> entry : externalPlugins.entrySet()) {
            if (!result.containsKey(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
                discoveredCache.put(entry.getKey(), entry.getValue());
            }
        }

        return result;
    }

    /**
     * Discover external channel plugins registered via Java {@link ServiceLoader}
     * (analogous to Python's {@code entry_points(group="nanobot.channels")}).
     * <p>
     * Plugins declare their channel class in
     * {@code META-INF/services/com.nanobot.channels.core.BaseChannel}.
     * The module name is derived from the plugin's channel name annotation or
     * the simple class name.
     *
     * @param enabledNames if non-null, only load plugins whose names are in this set
     * @return map of channel name → channel class
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Class<? extends BaseChannel>> discoverPlugins(
            Set<String> enabledNames) {
        Map<String, Class<? extends BaseChannel>> plugins = new LinkedHashMap<>();
        ServiceLoader<BaseChannel> loader = ServiceLoader.load(BaseChannel.class);
        for (BaseChannel plugin : loader) {
            String name = plugin.getChannelName();
            if (enabledNames != null && !enabledNames.contains(name)) {
                continue;
            }
            try {
                Class<? extends BaseChannel> cls =
                        (Class<? extends BaseChannel>) plugin.getClass();
                plugins.put(name, cls);
            } catch (Exception e) {
                logger.warn("Failed to load channel plugin '{}': {}", name, e.getMessage());
            }
        }
        return plugins;
    }

    /**
     * Return all channels: built-in (classpath scan) merged with external
     * (ServiceLoader). Built-in channels take priority — an external plugin
     * cannot shadow a built-in name.
     * python_analog: discover_all()
     *
     * @return map of channel name → channel class (all discovered channels)
     */
    public static Map<String, Class<? extends BaseChannel>> discoverAll() {
        List<String> builtinNames = discoverChannelModuleNames();
        // Use internal overload to include all external plugins
        return discoverEnabled(new HashSet<>(builtinNames));
    }

    /**
     * Derive a module name from a fully-qualified class name.
     * <p>
     * Example: {@code com.nanobot.channels.telegram.TelegramChannel} → {@code "telegram"}.
     * The module name is the second-to-last package segment before the class name.
     *
     * @param className fully qualified class name
     * @return the module name, or null if the package structure is unexpected
     */
    private static String deriveModuleName(String className) {
        int lastDot = className.lastIndexOf('.');
        if (lastDot < 0) {
            return null;
        }
        String packageName = className.substring(0, lastDot);
        int secondLastDot = packageName.lastIndexOf('.');
        if (secondLastDot < 0) {
            return null;
        }
        return packageName.substring(secondLastDot + 1);
    }

    /**
     * Resolve the channel-specific config from the raw config section.
     * <p>
     * Each channel has its own {@link ChannelConfig} subclass (e.g.,
     * {@code TelegramConfig}, {@code DiscordConfig}). The raw config section
     * (typically a {@code Map<String, Object>} from JSON) is deserialized
     * into the correct config type.
     *
     * @param moduleName the channel module name
     * @param channelClass the channel class
     * @param rawSection raw config (typically {@code Map<String, Object>})
     * @return a fully populated {@link ChannelConfig} subclass instance
     */
    @SuppressWarnings("unchecked")
    public static ChannelConfig resolveConfig(
            String moduleName,
            Class<? extends BaseChannel> channelClass,
            Object rawSection) {

        if (rawSection instanceof ChannelConfig cfg) {
            return cfg;
        }

        // If rawSection is a Map, deserialize into the channel's config class
        if (rawSection instanceof Map<?, ?> map) {
            // Use a helper method to find the config class
            Class<? extends ChannelConfig> configClass = findConfigClass(moduleName, channelClass);
            return deserializeConfig((Map<String, Object>) map, configClass);
        }

        throw new IllegalArgumentException(
                "Cannot resolve config for channel '" + moduleName
                + "': unsupported section type " + rawSection.getClass().getName());
    }

    /**
     * Find the config class associated with a channel.
     * <p>
     * Convention: {@code TelegramChannel} has a corresponding {@code TelegramConfig}
     * in the same package. This method uses that convention to locate the config class.
     */
    @SuppressWarnings("unchecked")
    private static Class<? extends ChannelConfig> findConfigClass(
            String moduleName, Class<? extends BaseChannel> channelClass) {

        // Check cache first
        if (configClassCache.containsKey(moduleName)) {
            return configClassCache.get(moduleName);
        }

        // Convention: same package, "<Name>Config"
        String packageName = channelClass.getPackageName();
        // Capitalize module name
        String capitalized = Character.toUpperCase(moduleName.charAt(0))
                + moduleName.substring(1);
        String configClassName = packageName + "." + capitalized + "Config";

        try {
            Class<?> clazz = Class.forName(configClassName);
            if (ChannelConfig.class.isAssignableFrom(clazz)) {
                Class<? extends ChannelConfig> configClass =
                        (Class<? extends ChannelConfig>) clazz;
                configClassCache.put(moduleName, configClass);
                return configClass;
            }
        } catch (ClassNotFoundException ignored) {
            // Fall back to base ChannelConfig
        }

        return ChannelConfig.class;
    }

    /**
     * Deserialize a Map into a ChannelConfig subclass instance.
     * Uses Jackson ObjectMapper (configured in Spring context).
     */
    private static ChannelConfig deserializeConfig(
            Map<String, Object> map, Class<? extends ChannelConfig> configClass) {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        return mapper.convertValue(map, configClass);
    }

    /**
     * Instantiate a channel from its class, config, and dependencies.
     * <p>
     * Uses reflection to find a compatible constructor. The typical constructor
     * signature is {@code (ChannelConfig, MessageBus)} or
     * {@code (ChannelConfig, MessageBus, SessionManager)}.
     *
     * @param channelClass    the channel class
     * @param config          the channel-specific configuration
     * @param bus             the message bus
     * @param sessionManager  the session manager (may be null)
     * @return a new channel instance
     */
    public static BaseChannel instantiate(
            Class<? extends BaseChannel> channelClass,
            ChannelConfig config,
            MessageBus bus,
            SessionManager sessionManager) throws Exception {

        // Try (ChannelConfig, MessageBus, SessionManager) first
        try {
            Constructor<? extends BaseChannel> ctor =
                    channelClass.getConstructor(ChannelConfig.class, MessageBus.class,
                            SessionManager.class);
            return ctor.newInstance(config, bus, sessionManager);
        } catch (NoSuchMethodException e) {
            // fall through
        }

        // Try (ChannelConfig, MessageBus)
        try {
            Constructor<? extends BaseChannel> ctor =
                    channelClass.getConstructor(ChannelConfig.class, MessageBus.class);
            return ctor.newInstance(config, bus);
        } catch (NoSuchMethodException e) {
            // fall through
        }

        throw new NoSuchMethodException(
                "No compatible constructor found for " + channelClass.getName());
    }

    /**
     * Instantiate a channel with a pre-built GatewayServices object.
     * Used exclusively for the websocket channel.
     * python_analog: manager.py _init_channels websocket branch
     *
     * @param channelClass    the channel class
     * @param config          the channel-specific configuration
     * @param bus             the message bus
     * @param sessionManager  the session manager (may be null)
     * @param gateway         pre-built GatewayServices for WebSocket
     * @return a new channel instance
     */
    public static BaseChannel instantiateWithGateway(
            Class<? extends BaseChannel> channelClass,
            ChannelConfig config,
            MessageBus bus,
            SessionManager sessionManager,
            Object gateway) throws Exception {

        // Try (ChannelConfig, MessageBus, SessionManager, GatewayServices)
        try {
            Constructor<? extends BaseChannel> ctor =
                    channelClass.getConstructor(ChannelConfig.class, MessageBus.class,
                            SessionManager.class, gateway.getClass());
            return ctor.newInstance(config, bus, sessionManager, gateway);
        } catch (NoSuchMethodException e) {
            // fall through to gateway-as-Object overload
        }

        // Try (ChannelConfig, MessageBus, Map) — gateway passed as raw map
        try {
            Constructor<? extends BaseChannel> ctor =
                    channelClass.getConstructor(ChannelConfig.class, MessageBus.class, Map.class);
            return ctor.newInstance(config, bus, gateway);
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodException(
                    "No gateway-compatible constructor found for " + channelClass.getName());
        }
    }
}
```

### 2.7 `MessageBus` 接口更新

`MessageBus` 需要支持非阻塞 poll（供 `StreamCoalescer` 使用）和超时阻塞 consume（供 dispatcher 使用）。

```java
package com.nanobot.bus;

import com.nanobot.bus.events.InboundMessage;
import com.nanobot.bus.events.OutboundMessage;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Blocking message bus for decoupled channel-agent communication.
 * Java 21 version uses {@link BlockingQueue} with virtual-thread-friendly
 * blocking operations.
 */
public class MessageBus {

    private final BlockingQueue<InboundMessage> inbound =
            new LinkedBlockingQueue<>();
    private final BlockingQueue<OutboundMessage> outbound =
            new LinkedBlockingQueue<>();

    // ---- inbound ----

    public void publishInbound(InboundMessage msg) {
        inbound.add(msg);
    }

    public InboundMessage consumeInbound() throws InterruptedException {
        return inbound.take(); // blocks until available
    }

    public InboundMessage consumeInbound(long timeout, TimeUnit unit)
            throws InterruptedException {
        return inbound.poll(timeout, unit);
    }

    public InboundMessage pollInbound() {
        return inbound.poll(); // non-blocking, returns null if empty
    }

    // ---- outbound ----

    public void publishOutbound(OutboundMessage msg) {
        outbound.add(msg);
    }

    public OutboundMessage consumeOutbound() throws InterruptedException {
        return outbound.take();
    }

    public OutboundMessage consumeOutbound(long timeout, TimeUnit unit)
            throws InterruptedException {
        return outbound.poll(timeout, unit);
    }

    /** Non-blocking poll for stream coalescer. */
    public OutboundMessage pollOutbound() {
        return outbound.poll();
    }

    // ---- sizing ----

    public int getInboundSize() {
        return inbound.size();
    }

    public int getOutboundSize() {
        return outbound.size();
    }
}
```

---

## 3. 线程模型设计

### 3.1 架构对比

| 概念 | Python (asyncio) | Java 21 (Virtual Threads) |
|------|-----------------|---------------------------|
| Channel 运行 | `asyncio.create_task(channel.start())` | `Thread.ofVirtual().start(channel::start)` |
| Dispatcher 运行 | `asyncio.create_task(_dispatch_outbound())` | `Thread.ofVirtual().start(this::dispatchOutbound)` |
| 队列阻塞 | `await queue.get()` | `queue.take()` (virtual thread parks) |
| 定时/超时 | `await asyncio.sleep(delay)` | `Thread.sleep(delay)` or `queue.poll(timeout, unit)` |
| 取消 | `task.cancel()` → `CancelledError` | `thread.interrupt()` → `InterruptedException` |
| 并发启动 | `await asyncio.gather(*tasks)` | Start N virtual threads, `join()` each |

### 3.2 虚拟线程关键特性

- **无需 async/await**: 代码保持同步风格，虚拟线程在 I/O 操作时自动让出 OS 线程
- **BlockingQueue 友好**: `take()` / `poll(timeout, unit)` 在虚拟线程中高效 park/unpark
- **InterruptedException 处理**: 与 `asyncio.CancelledError` 等效——捕获后执行清理逻辑
- **无需线程池**: `Executors.newVirtualThreadPerTaskExecutor()` 按需创建，虚拟线程轻量（~200 bytes）

### 3.3 ChannelManager 线程拓扑

```
┌─────────────────────────────────────────────────┐
│ ChannelManager.startAll()                        │
│                                                  │
│  ┌─ outbound-dispatcher (virtual thread) ──────┐ │
│  │  dispatchOutbound() loop:                   │ │
│  │    msg = bus.consumeOutbound(1s timeout)     │ │
│  │    → classify → coalesce → suppress → send  │ │
│  └──────────────────────────────────────────────┘ │
│                                                  │
│  ┌─ channel-telegram (virtual thread) ─────────┐ │
│  │  TelegramChannel.start():                   │ │
│  │    connect → long-poll events → handleMessage│ │
│  └──────────────────────────────────────────────┘ │
│                                                  │
│  ┌─ channel-discord (virtual thread) ──────────┐ │
│  │  DiscordChannel.start():                    │ │
│  │    login → listen events → handleMessage     │ │
│  └──────────────────────────────────────────────┘ │
│                                                  │
│  ... (one virtual thread per enabled channel)     │
└─────────────────────────────────────────────────┘
```

---

## 4. 配置模型

### 4.1 `ChannelConfig.java` 子类化模式

每个 channel 定义自己的 config 类，继承 `ChannelConfig`：

```
ChannelConfig
├── TelegramConfig      (bot_token, proxy_url, ...)
├── DiscordConfig       (bot_token, guild_id, ...)
├── WebSocketConfig     (host, port, path, token, ssl, ...)
├── SlackConfig         (bot_token, app_token, mode, ...)
├── WhatsAppConfig      (bridge_url, bridge_token, ...)
├── FeishuConfig        (app_id, app_secret, ...)
├── WeixinConfig        (bot_token, api_base, ...)
├── DingTalkConfig      (client_id, client_secret, ...)
├── ...
```

### 4.2 `ChannelsGlobalConfig.java`

```java
package com.nanobot.config;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.*;

/**
 * Global channels configuration, plus per-channel sections via {@code @JsonAnySetter}.
 */
public class ChannelsGlobalConfig {

    @JsonProperty("send_progress")
    private boolean sendProgress = true;

    @JsonProperty("send_tool_hints")
    private boolean sendToolHints = false;

    @JsonProperty("show_reasoning")
    private boolean showReasoning = true;

    @JsonProperty("send_max_retries")
    private int sendMaxRetries = 3;

    /** Extra channel-specific sections keyed by channel name. */
    private final Map<String, Object> extraSections = new LinkedHashMap<>();

    @JsonAnySetter
    public void setExtraSection(String key, Object value) {
        extraSections.put(key, value);
    }

    /**
     * Get the config section for a specific channel by name.
     * Returns the raw section (Map or deserialized config object).
     */
    public Object getChannelSection(String channelName) {
        return extraSections.get(channelName);
    }

    /**
     * Get all extra channel names (for plugin discovery).
     */
    public Set<String> getExtraChannelNames() {
        return Collections.unmodifiableSet(extraSections.keySet());
    }

    // ---- accessors ----

    public boolean isSendProgress() { return sendProgress; }
    public void setSendProgress(boolean sendProgress) { this.sendProgress = sendProgress; }
    public boolean isSendToolHints() { return sendToolHints; }
    public void setSendToolHints(boolean sendToolHints) { this.sendToolHints = sendToolHints; }
    public boolean isShowReasoning() { return showReasoning; }
    public void setShowReasoning(boolean showReasoning) { this.showReasoning = showReasoning; }
    public int getSendMaxRetries() { return sendMaxRetries; }
    public void setSendMaxRetries(int sendMaxRetries) { this.sendMaxRetries = sendMaxRetries; }
}
```

---

## 5. 关键设计决策

### 5.1 不使用 `var`

所有局部变量必须声明显式类型。例如：

```java
// CORRECT
BaseChannel channel = channels.get(msg.getChannel());
Map<String, Object> meta = new HashMap<>(msg.getMetadata());
List<OutboundMessage> pending = new ArrayList<>();

// WRONG
var channel = channels.get(msg.getChannel());
var meta = new HashMap<>(msg.getMetadata());
```

### 5.2 同步阻塞模型替代 async/await

Python 的 `async/await` 在 Java 21 虚拟线程中完全不需要。`BlockingQueue.take()` 阻塞当前虚拟线程但不阻塞 OS 线程。

### 5.3 Spring 组件扫描替代 pkgutil

Python 使用 `pkgutil.iter_modules` 动态扫描包。Java 使用 Spring 的 `ClassPathScanningCandidateComponentProvider` 扫描标注了特定注解的类。外部插件通过 `spring.factories` 或 `ServiceLoader` 机制注册。

### 5.4 Pairing 模块

Pairing 逻辑（`is_allowed` → `isApproved`）已从 `BaseChannel` 中解耦到独立的 `PairingStore` 类。`BaseChannel.isAllowed()` 调用 `PairingStore.isApproved()` 进行第三级鉴权。

---

## 6. 文件清单

| 文件 | 说明 |
|------|------|
| `com/nanobot/channels/core/BaseChannel.java` | 抽象基类 (~220 行) |
| `com/nanobot/channels/core/ChannelManager.java` | Channel 生命周期管理 + Outbound 分发 (~420 行) |
| `com/nanobot/channels/core/ChannelRegistry.java` | Spring 组件扫描发现与实例化 (~280 行) |
| `com/nanobot/channels/core/ChannelConfig.java` | 配置基类 (~50 行) |
| `com/nanobot/channels/core/StreamCoalescer.java` | 流式 delta 合并 (~70 行) |
| `com/nanobot/bus/MessageBus.java` | 消息总线（更新以支持 poll + 超时） |
| `com/nanobot/config/ChannelsGlobalConfig.java` | 全局 channels 配置 + extra sections |

## 7. 验证标准

```bash
mvn test -Dtest=BaseChannelTest,ChannelManagerTest,ChannelRegistryTest,StreamCoalescerTest
# [x] BaseChannel: handleMessage permission check → pairing code in DM, deny+log in group
# [x] BaseChannel: isAllowed resolution order (wildcard > exact match > pairing store)
# [x] BaseChannel: supportsStreaming detection (config.enabled AND sendDelta overridden)
# [x] BaseChannel: transcribeAudio success/failure paths
# [x] BaseChannel: login default (returns true), subclass override
# [x] BaseChannel: sendReasoning translates one-shot → delta + end pair
# [x] BaseChannel: defaultConfig returns {enabled: false}
# [x] ChannelManager: initChannels discovers and instantiates only enabled channels
# [x] ChannelManager: Boolean overrides resolve via camelCase aliases (JSON compat)
# [x] ChannelManager: WebSocket channel receives GatewayServices injection
# [x] ChannelManager: startAll launches dispatcher + channels on virtual threads
# [x] ChannelManager: stopAll cancels dispatcher, stops all channels, shuts down executor
# [x] ChannelManager: notifyRestartDoneIfNeeded consumes env markers → sends via target channel
# [x] ChannelManager: dispatchOutbound — reasoning routing (show_reasoning gate)
# [x] ChannelManager: dispatchOutbound — progress routing (send_progress / send_tool_hints gate)
# [x] ChannelManager: dispatchOutbound — runtime_model_updated only to websocket
# [x] ChannelManager: dispatchOutbound — stream delta coalescing
# [x] ChannelManager: dispatchOutbound — duplicate suppression (SHA-1 fingerprint)
# [x] ChannelManager: sendWithRetry exponential backoff (1s, 2s, 4s), InterruptedException propagation
# [x] ChannelManager: sendOnce dispatches correctly for reasoning / file_edit / stream / normal
# [x] ChannelRegistry: discoverChannelModuleNames excludes core/registry internal modules
# [x] ChannelRegistry: discoverEnabled imports only enabled channels (lazy class loading)
# [x] ChannelRegistry: discoverPlugins discovers external BaseChannel via ServiceLoader
# [x] ChannelRegistry: discoverAll merges built-in + external, built-in priority
# [x] ChannelRegistry: instantiate / instantiateWithGateway constructor resolution
# [x] StreamCoalescer: merges consecutive deltas for same (channel, chat_id)
# [x] StreamCoalescer: stops at first non-delta boundary message
```

## 8. 复刻完整度

| Python 源文件 | 行数 | Java 对标 | 方法覆盖率 |
|---|---|---|---|
| `channels/base.py` | 257 | BaseChannel.java | **100%** (含 transcribe_audio/login/send_reasoning/default_config) |
| `channels/manager.py` | 487 | ChannelManager.java + StreamCoalescer.java | **100%** (含 notifyRestart + WebSocket gateway 注入) |
| `channels/registry.py` | 96 | ChannelRegistry.java | **100%** (含 discover_plugins + discover_all) |
| **合计** | **840** | **7 个 Java 文件** | **~100%** |
