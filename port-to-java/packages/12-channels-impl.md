# 12 — channels-impl 包：Channel 实现

**对标 Python：** 17 个 channel 实现文件，总计 ~15,000+ 行

| Python 文件 | 行数 | Channel | 用途 |
|-------------|------|---------|------|
| `websocket.py` | 1,179 | WebSocket | WebUI 实时通信 |
| `telegram.py` | 1,472 | Telegram | Telegram Bot |
| `discord.py` | ~900 | Discord | Discord Bot |
| `slack.py` | ~800 | Slack | Slack Bot (Socket Mode) |
| `feishu.py` | 1,984 | Feishu | 飞书 (Lark) |
| `weixin.py` | 1,586 | Weixin | 个人微信 (HTTP API) |
| `wecom.py` | ~400 | WeCom | 企业微信 |
| `whatsapp.py` | ~350 | WhatsApp | WhatsApp (Node.js bridge) |
| `dingtalk.py` | ~650 | DingTalk | 钉钉 (Stream Mode) |
| `matrix.py` | ~700 | Matrix | Matrix 协议 |
| `email.py` | ~350 | Email | IMAP/SMTP |
| `signal.py` | ~600 | Signal | Signal (signal-cli) |
| `qq.py` | ~700 | QQ | QQ (go-cqhttp) |
| `napcat.py` | ~300 | Napcat | Napcat QQ Bot |
| `mochat.py` | ~500 | MoChat | MoChat 平台 |
| `msteams.py` | ~600 | MS Teams | Microsoft Teams |

---

## 1. WebSocketChannel — 详细实现

**Python: `channels/websocket.py` (1,179 行)**

这是最复杂的 channel 实现，因为它需要：
- 运行一个完整的 WebSocket 服务器（不仅是客户端）
- 管理多个客户端连接和订阅关系
- 处理 HTTP 回落（非 WS 请求路由到 HTTP handler）
- 支持多种入站信封类型（message, new_chat, fork_chat, attach, etc.）
- 媒体上传（base64 解码、MIME 白名单、大小限制）
- Token 认证（静态 token + 短期签发 token）
- 推理/流式内容的缓冲区管理

### 1.1 Python 架构回顾

```
WebSocketChannel (extends BaseChannel)
├── 连接生命周期:
│   ├── start() → serve(handler) → _connection_loop()
│   ├── _connection_loop(): 握手 → ready event → 消息循环
│   └── 断开时 _cleanup_connection()
│
├── 订阅模型:
│   ├── _subs: Map<chatId, Set<Connection>> — chat→订阅者
│   ├── _conn_chats: Map<Connection, Set<chatId>> — 连接→chat 反向索引
│   └── _conn_default: Map<Connection, String> — 连接默认 chatId
│
├── 入站信封: "message", "new_chat", "fork_chat", "attach",
│   "set_workspace_scope", "transcribe_audio"
│
├── 出站事件: "message", "delta", "stream_end", "reasoning_delta",
│   "reasoning_end", "goal_state", "goal_status", "runtime_model_updated",
│   "session_updated", "turn_end", "file_edit_events", "error"
│
└── 媒体处理: base64 decode → size check → MIME whitelist → save to disk
```

### 1.2 Spring Boot WebSocket 支持

Spring Boot 3.2 提供两种 WebSocket 方案：

| 方案 | 适用场景 | 选择 |
|------|---------|------|
| `WebSocketHandler` (低级) | 手写握手、帧处理 | **选用** |
| STOMP over WebSocket | 标准 STOMP 协议 | 不适用（nanobot 使用自定义 JSON 协议） |
| `javax.websocket` (JSR 356) | 注解式端点 | 备选 |

**选择 `WebSocketHandler`** 的原因：
- nanobot WebSocket 使用自定义 JSON 信封协议（非 STOMP）
- 需要对 HTTP 非升级请求做 fallthrough（`_dispatch_http`）
- 需要精确控制连接生命周期和认证流程
- 需要 Unix domain socket 支持

### 1.3 Java 实现

#### 1.3.1 `WebSocketConfig.java` — 配置类

```java
package com.nanobot.channels.websocket;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nanobot.channels.core.ChannelConfig;
import jakarta.validation.constraints.*;

import java.util.ArrayList;
import java.util.List;

/**
 * WebSocket server channel configuration.
 * <p>
 * Clients connect with URLs like:
 * {@code ws://{host}:{port}{path}?client_id=...&token=...}
 */
public class WebSocketConfig extends ChannelConfig {

    @JsonProperty("host")
    private String host = "127.0.0.1";

    @JsonProperty("port")
    @Min(1) @Max(65535)
    private int port = 8765;

    @JsonProperty("unix_socket_path")
    private String unixSocketPath = "";

    @JsonProperty("path")
    @NotBlank
    private String path = "/";

    @JsonProperty("token")
    private String token = "";

    @JsonProperty("token_issue_path")
    private String tokenIssuePath = "";

    @JsonProperty("token_issue_secret")
    private String tokenIssueSecret = "";

    @JsonProperty("token_ttl_s")
    @Min(30) @Max(86_400)
    private int tokenTtlS = 300;

    @JsonProperty("websocket_requires_token")
    private boolean websocketRequiresToken = true;

    @JsonProperty("max_message_bytes")
    @Min(1024) @Max(41_943_040)
    private int maxMessageBytes = 37_748_736; // ~36 MB

    @JsonProperty("ping_interval_s")
    @DecimalMin("5.0") @DecimalMax("300.0")
    private double pingIntervalS = 20.0;

    @JsonProperty("ping_timeout_s")
    @DecimalMin("5.0") @DecimalMax("300.0")
    private double pingTimeoutS = 20.0;

    @JsonProperty("ssl_certfile")
    private String sslCertfile = "";

    @JsonProperty("ssl_keyfile")
    private String sslKeyfile = "";

    public WebSocketConfig() {
        // WebSocket defaults to streaming + wildcard allow
        setStreaming(true);
        setAllowFrom(new ArrayList<>(List.of("*")));
    }

    // ---- validation ----

    public void validate() {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("path must start with '/'");
        }
        if (!unixSocketPath.isEmpty()) {
            if (unixSocketPath.contains("\0")) {
                throw new IllegalArgumentException(
                        "unix_socket_path must not contain NUL bytes");
            }
            java.nio.file.Path p = java.nio.file.Path.of(
                    unixSocketPath.replaceFirst("^~", System.getProperty("user.home")));
            if (!p.isAbsolute()) {
                throw new IllegalArgumentException(
                        "unix_socket_path must be an absolute path");
            }
        }
        if (!tokenIssuePath.isEmpty() && !tokenIssuePath.startsWith("/")) {
            throw new IllegalArgumentException("token_issue_path must start with '/'");
        }
        if (!tokenIssuePath.isEmpty()
                && normalizePath(tokenIssuePath).equals(normalizePath(path))) {
            throw new IllegalArgumentException(
                    "token_issue_path must differ from path (the WebSocket upgrade path)");
        }
        if (("0.0.0.0".equals(host) || "::".equals(host))
                && token.isBlank() && tokenIssueSecret.isBlank()) {
            throw new IllegalArgumentException(
                    "host is 0.0.0.0 (all interfaces) but neither token nor "
                    + "token_issue_secret is set");
        }
    }

    private static String normalizePath(String p) {
        if (p.endsWith("/") && p.length() > 1) {
            return p.substring(0, p.length() - 1);
        }
        return p;
    }

    // ---- accessors (all explicit, no Lombok) ----

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getUnixSocketPath() { return unixSocketPath; }
    public void setUnixSocketPath(String unixSocketPath) { this.unixSocketPath = unixSocketPath; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getTokenIssuePath() { return tokenIssuePath; }
    public void setTokenIssuePath(String tokenIssuePath) { this.tokenIssuePath = tokenIssuePath; }
    public String getTokenIssueSecret() { return tokenIssueSecret; }
    public void setTokenIssueSecret(String tokenIssueSecret) { this.tokenIssueSecret = tokenIssueSecret; }
    public int getTokenTtlS() { return tokenTtlS; }
    public void setTokenTtlS(int tokenTtlS) { this.tokenTtlS = tokenTtlS; }
    public boolean isWebsocketRequiresToken() { return websocketRequiresToken; }
    public void setWebsocketRequiresToken(boolean websocketRequiresToken) {
        this.websocketRequiresToken = websocketRequiresToken;
    }
    public int getMaxMessageBytes() { return maxMessageBytes; }
    public void setMaxMessageBytes(int maxMessageBytes) { this.maxMessageBytes = maxMessageBytes; }
    public double getPingIntervalS() { return pingIntervalS; }
    public void setPingIntervalS(double pingIntervalS) { this.pingIntervalS = pingIntervalS; }
    public double getPingTimeoutS() { return pingTimeoutS; }
    public void setPingTimeoutS(double pingTimeoutS) { this.pingTimeoutS = pingTimeoutS; }
    public String getSslCertfile() { return sslCertfile; }
    public void setSslCertfile(String sslCertfile) { this.sslCertfile = sslCertfile; }
    public String getSslKeyfile() { return sslKeyfile; }
    public void setSslKeyfile(String sslKeyfile) { this.sslKeyfile = sslKeyfile; }
}
```

#### 1.3.2 `WebSocketChannel.java` — 主类

```java
package com.nanobot.channels.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanobot.bus.MessageBus;
import com.nanobot.bus.events.OutboundMessage;
import com.nanobot.channels.core.BaseChannel;
import com.nanobot.channels.core.ChannelConfig;
import com.nanobot.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * WebSocket server channel: nanobot acts as a WebSocket server and serves
 * connected clients (primarily the React WebUI).
 * <p>
 * <b>Subscription model:</b> Each WebSocket connection subscribes to one or
 * more {@code chatId}s. Outbound messages are fanned out to all connections
 * subscribed to the target chatId. A connection's default chatId is assigned
 * at handshake time and may be extended via "attach" envelopes.
 */
public class WebSocketChannel extends BaseChannel implements WebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketChannel.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    static final int MAX_IMAGES_PER_MESSAGE = 4;
    static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    static final int MAX_VIDEOS_PER_MESSAGE = 1;
    static final int MAX_VIDEO_BYTES = 20 * 1024 * 1024;

    static final Set<String> IMAGE_MIME_ALLOWED = Set.of(
            "image/png", "image/jpeg", "image/webp", "image/gif");
    static final Set<String> VIDEO_MIME_ALLOWED = Set.of(
            "video/mp4", "video/webm", "video/quicktime");
    static final Set<String> UPLOAD_MIME_ALLOWED;

    static final String WORKSPACE_SCOPE_METADATA_KEY = "workspace_scope";

    static final String OUTBOUND_META_AGENT_UI = "_agent_ui";

    static {
        Set<String> combined = new HashSet<>(IMAGE_MIME_ALLOWED);
        combined.addAll(VIDEO_MIME_ALLOWED);
        UPLOAD_MIME_ALLOWED = Collections.unmodifiableSet(combined);
    }

    // ---- config ----

    private final WebSocketConfig wsConfig;

    // ---- subscription bookkeeping ----

    /** chatId → set of active WebSocket sessions subscribed to it */
    private final ConcurrentHashMap<String, Set<WebSocketSession>> subs =
            new ConcurrentHashMap<>();

    /** WebSocket session → set of chatIds it is subscribed to */
    private final ConcurrentHashMap<WebSocketSession, Set<String>> connChats =
            new ConcurrentHashMap<>();

    /** WebSocket session → default chatId for legacy frames */
    private final ConcurrentHashMap<WebSocketSession, String> connDefault =
            new ConcurrentHashMap<>();

    /** chatId → wall-clock epoch-second when the active user turn began */
    final ConcurrentHashMap<String, Double> turnWallStartTimes =
            new ConcurrentHashMap<>();

    // ---- gateway services ----

    private final SessionManager sessionManager;
    private final WebUIWorkspaceController workspaceController;
    private final WebUITranscriptionHandler transcriptionHandler;

    // ---- stream buffers ----

    /** (chatId, streamId) → accumulated text chunks */
    private final ConcurrentHashMap<StreamBufferKey, List<String>> streamTextBuffers =
            new ConcurrentHashMap<>();

    private record StreamBufferKey(String chatId, String streamId) {}

    // ---- token management ----

    private final TokenManager tokenManager;
    private final MediaManager mediaManager;
    private final TranscriptManager transcriptManager;

    // ---- server ----

    private volatile boolean running = false;
    private Thread serverThread;

    public WebSocketChannel(WebSocketConfig config, MessageBus bus,
                            SessionManager sessionManager,
                            WebUIWorkspaceController workspaceController,
                            WebUITranscriptionHandler transcriptionHandler) {
        super("websocket", "WebSocket", config, bus);
        this.wsConfig = config;
        this.sessionManager = sessionManager;
        this.workspaceController = workspaceController;
        this.transcriptionHandler = transcriptionHandler;
        this.tokenManager = new TokenManager(config);
        this.mediaManager = new MediaManager("websocket");
        this.transcriptManager = new TranscriptManager();
    }

    // ---- static helpers ----

    /**
     * Enqueue a runtime model snapshot for websocket subscribers (fan-out in-channel).
     * Mirrors Python {@code publish_runtime_model_update(bus, model, model_preset)}.
     */
    public static void publishRuntimeModelUpdate(MessageBus bus, String model,
                                                  String modelPreset) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("_runtime_model_updated", true);
        metadata.put("model", model);
        metadata.put("model_preset", modelPreset);
        OutboundMessage msg = new OutboundMessage(
                "websocket", "*", "", null, null, metadata, null, null, null);
        bus.getOutbound().put(msg);
    }

    /**
     * Return the default (empty) config dict for this channel.
     * Mirrors Python {@code WebSocketChannel.default_config()}.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> defaultConfig() {
        // Mirrors Python WebSocketConfig().model_dump(by_alias=True)
        return objectMapper.convertValue(new WebSocketConfig(), Map.class);
    }

    // ---- lifecycle ----

    @Override
    public void start() throws Exception {
        wsConfig.validate();
        running = true;
        // The WebSocket server is started by Spring Boot via the
        // @EnableWebSocket annotation. This channel registers itself
        // as a handler. The actual server lifecycle is managed by
        // Spring's embedded Tomcat/Jetty/Netty.
        //
        // For standalone mode (non-Spring), use Undertow or Jetty
        // standalone as shown in the "Standalone Server" section below.
        logger.info("WebSocket server ready on {}://{}:{}{}",
                wsConfig.getSslCertfile().isBlank() ? "ws" : "wss",
                wsConfig.getHost(), wsConfig.getPort(), wsConfig.getPath());
    }

    @Override
    public void stop() throws Exception {
        running = false;
        // Close all open sessions
        for (WebSocketSession session : List.copyOf(connChats.keySet())) {
            try {
                session.close(CloseStatus.GOING_AWAY);
            } catch (Exception ignored) {
                // connection may already be dead
            }
        }
        subs.clear();
        connChats.clear();
        connDefault.clear();
        streamTextBuffers.clear();
        tokenManager.clear();
    }

    // ---- WebSocketHandler implementation ----

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        URI uri = session.getUri();
        if (uri == null) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        // Extract client_id from query params
        Map<String, String> queryParams = parseQueryParams(uri.getQuery());
        String clientId = queryParams.getOrDefault("client_id", "").strip();
        if (clientId.length() > 128) {
            clientId = clientId.substring(0, 128);
            logger.warn("client_id too long, truncating to 128 chars");
        }
        if (clientId.isEmpty()) {
            clientId = "anon-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }

        // Authenticate
        String suppliedToken = queryParams.getOrDefault("token", "");
        if (!authenticate(suppliedToken)) {
            session.close(new CloseStatus(4001, "Unauthorized"));
            return;
        }

        // Permission check
        if (!isAllowed(clientId)) {
            session.close(new CloseStatus(4003, "Forbidden"));
            return;
        }

        // Assign default chatId
        String defaultChatId = UUID.randomUUID().toString();

        // Send ready event
        Map<String, Object> readyEvent = new LinkedHashMap<>();
        readyEvent.put("event", "ready");
        readyEvent.put("chat_id", defaultChatId);
        readyEvent.put("client_id", clientId);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(readyEvent)));

        // Register subscription
        connDefault.put(session, defaultChatId);
        attach(session, defaultChatId);

        // Hydrate state for the new chat
        hydrateAfterSubscribe(defaultChatId);

        logger.info("WebSocket connection established: client={}, default_chat={}",
                clientId, defaultChatId);
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message)
            throws Exception {

        if (!(message instanceof TextMessage textMessage)) {
            // BinaryMessage — try to decode as UTF-8
            if (message instanceof BinaryMessage binaryMessage) {
                try {
                    String raw = new String(
                            binaryMessage.getPayload().array(), StandardCharsets.UTF_8);
                    dispatchTextFrame(session, raw);
                } catch (Exception e) {
                    logger.warn("ignoring non-utf8 binary frame");
                }
            }
            return;
        }

        dispatchTextFrame(session, textMessage.getPayload());
    }

    private void dispatchTextFrame(WebSocketSession session, String raw) {
        // Try JSON envelope first (new-style)
        Map<String, Object> envelope = parseEnvelope(raw);
        if (envelope != null) {
            dispatchEnvelope(session, envelope);
            return;
        }

        // Legacy: plain text or {"content": "..."} without type field
        String content = parseInboundPayload(raw);
        if (content == null) {
            return;
        }

        String clientId = resolveClientId(session);
        String defaultChatId = connDefault.get(session);
        if (defaultChatId == null) {
            return;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        InetSocketAddress remoteAddr = null;
        if (session.getRemoteAddress() instanceof InetSocketAddress addr) {
            remoteAddr = addr;
            metadata.put("remote", remoteAddr.getHostString());
        }

        handleMessage(
                clientId,
                defaultChatId,
                content,
                List.of(),
                metadata,
                null,
                false  // isDm=false — WebSocket is already authenticated
        );
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        logger.warn("WebSocket transport error: {}", exception.getMessage());
        cleanupConnection(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
        logger.info("WebSocket connection closed: {}", closeStatus);
        cleanupConnection(session);
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    // ---- subscription management ----

    private void attach(WebSocketSession session, String chatId) {
        subs.computeIfAbsent(chatId, k -> ConcurrentHashMap.newKeySet()).add(session);
        connChats.computeIfAbsent(session, k -> ConcurrentHashMap.newKeySet()).add(chatId);
    }

    private void cleanupConnection(WebSocketSession session) {
        Set<String> chatIds = connChats.remove(session);
        if (chatIds != null) {
            for (String cid : chatIds) {
                Set<WebSocketSession> subscribers = subs.get(cid);
                if (subscribers != null) {
                    subscribers.remove(session);
                    if (subscribers.isEmpty()) {
                        subs.remove(cid);
                    }
                }
            }
        }
        connDefault.remove(session);
    }

    /**
     * Replay goal/run strip state so refresh + reconnect restores the UI
     * without a new model turn. Mirrors Python {@code _hydrate_after_subscribe}.
     */
    private void hydrateAfterSubscribe(String chatId) {
        // Replay active sustained goal from session metadata (survives restarts)
        Map<String, Object> sessionRow = sessionManager.readSessionFile("websocket:" + chatId);
        if (sessionRow != null) {
            Object metaObj = sessionRow.get("metadata");
            if (metaObj instanceof Map<?, ?> meta) {
                @SuppressWarnings("unchecked")
                Map<String, Object> blob = goalStateWsBlob((Map<String, Object>) meta);
                if (Boolean.TRUE.equals(blob.get("active"))) {
                    try {
                        sendGoalState(chatId, blob);
                    } catch (Exception e) {
                        logger.warn("failed to push goal_state during hydrate: {}", e.getMessage());
                    }
                }
            }
        }

        // Replay turn wall-clock if a user turn is still active (same-process)
        Double t0 = turnWallStartedAt(chatId);
        if (t0 != null) {
            try {
                sendGoalStatus(chatId, "running", t0);
            } catch (Exception e) {
                logger.warn("failed to push goal_status during hydrate: {}", e.getMessage());
            }
        }
    }

    /**
     * Extract the WebSocket goal-state blob from session metadata.
     * Mirrors Python {@code goal_state_ws_blob(meta)}.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> goalStateWsBlob(Map<String, Object> meta) {
        Object gs = meta.get("goal_state");
        if (gs instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of("active", false);
    }

    /**
     * Return the wall-clock epoch-second when the active user turn began,
     * or null if no turn is running. Mirrors Python
     * {@code websocket_turn_wall_started_at(chat_id)}.
     */
    private Double turnWallStartedAt(String chatId) {
        // This map is populated by WebuiTurnCoordinator when a turn starts
        // and cleared when it ends.
        return turnWallStartTimes.get(chatId);
    }

    // ---- envelope dispatch ----

    private void dispatchEnvelope(WebSocketSession session, Map<String, Object> envelope) {
        String type = (String) envelope.get("type");
        if (type == null) {
            sendErrorEvent(session, "missing type field");
            return;
        }

        switch (type) {
            case "new_chat" -> handleNewChat(session, envelope);
            case "fork_chat" -> handleForkChat(session, envelope);
            case "attach" -> handleAttach(session, envelope);
            case "set_workspace_scope" -> handleSetWorkspaceScope(session, envelope);
            case "transcribe_audio" -> handleTranscribeAudio(session, envelope);
            case "message" -> handleWsMessage(session, envelope);
            default -> sendErrorEvent(session, "unknown type: " + type);
        }
    }

    // ---- inbound envelope handlers ----

    private void handleNewChat(WebSocketSession session, Map<String, Object> envelope) {
        String newId = UUID.randomUUID().toString();

        // Resolve workspace scope — mirrors Python _workspaces.scope_for_new_chat()
        WorkspaceScope scope = workspaceScopeOrError(
                session,
                () -> workspaceController.scopeForNewChat(
                        envelope, /* controlsAvailable= */ true),
                null);
        if (scope == null) {
            return;
        }
        workspaceController.persistScope(newId, scope);

        attach(session, newId);
        sendEvent(session, "attached", Map.of("chat_id", newId));

        Map<String, Object> updateFields = new LinkedHashMap<>();
        updateFields.put("chat_id", newId);
        updateFields.put("scope", "metadata");
        updateFields.put("workspace_scope", scope.payload());
        sendEvent(session, "session_updated", updateFields);

        hydrateAfterSubscribe(newId);
    }

    private void handleForkChat(WebSocketSession session, Map<String, Object> envelope) {
        // Delegate to forking module — mirrors Python handle_webui_fork_chat()
        WebuiForking.handleForkChat(this, session, envelope);
    }

    private void handleAttach(WebSocketSession session, Map<String, Object> envelope) {
        String cid = (String) envelope.get("chat_id");
        if (!isValidChatId(cid)) {
            sendErrorEvent(session, "invalid chat_id");
            return;
        }
        attach(session, cid);
        sendEvent(session, "attached", Map.of("chat_id", cid));
        hydrateAfterSubscribe(cid);
    }

    private void handleSetWorkspaceScope(WebSocketSession session,
                                          Map<String, Object> envelope) {
        String cid = (String) envelope.get("chat_id");
        if (!isValidChatId(cid)) {
            sendErrorEvent(session, "invalid chat_id");
            return;
        }
        boolean chatRunning = turnWallStartTimes.containsKey(cid);
        WorkspaceScope scope = workspaceScopeOrError(
                session,
                () -> workspaceController.scopeForSetRequest(
                        envelope, cid, chatRunning, true),
                cid);
        if (scope == null) {
            return;
        }
        workspaceController.persistScope(cid, scope);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("chat_id", cid);
        fields.put("scope", "metadata");
        fields.put("workspace_scope", scope.payload());
        sendEvent(session, "session_updated", fields);
    }

    private void handleTranscribeAudio(WebSocketSession session,
                                        Map<String, Object> envelope) {
        // Delegate to transcription module — mirrors Python webui_transcription_event()
        TranscriptionResult result = transcriptionHandler.processTranscriptionEvent(envelope);
        if (result != null) {
            sendEvent(session, result.event(), result.payload());
        }
    }

    private void handleWsMessage(WebSocketSession session, Map<String, Object> envelope) {
        String cid = (String) envelope.get("chat_id");
        String content = (String) envelope.get("content");

        if (!isValidChatId(cid)) {
            sendErrorEvent(session, "invalid chat_id");
            return;
        }
        if (!(content instanceof String)) {
            sendErrorEvent(session, "missing content");
            return;
        }

        // Process media
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawMedia =
                (List<Map<String, Object>>) envelope.get("media");
        List<String> mediaPaths = List.of();
        if (rawMedia != null) {
            if (!(rawMedia instanceof List)) {
                sendErrorEvent(session, "image_rejected",
                        Map.of("reason", "malformed"));
                return;
            }
            MediaSaveResult saveResult = saveEnvelopeMedia(rawMedia);
            if (saveResult.error() != null) {
                sendErrorEvent(session, "image_rejected",
                        Map.of("reason", saveResult.error()));
                return;
            }
            mediaPaths = saveResult.paths();
        }

        // Allow image-only turns (content may be empty when media is attached)
        if (content.strip().isEmpty() && mediaPaths.isEmpty()) {
            sendErrorEvent(session, "missing content");
            return;
        }

        // Resolve workspace scope — mirrors Python _workspaces.scope_for_message()
        boolean chatRunning = turnWallStartTimes.containsKey(cid);
        WorkspaceScope scope = workspaceScopeOrError(
                session,
                () -> workspaceController.scopeForMessage(
                        envelope, cid, chatRunning, /* controlsAvailable= */ true),
                cid);
        if (scope == null) {
            return;
        }

        // Auto-attach on first use so clients can one-shot without separate attach
        attach(session, cid);
        hydrateAfterSubscribe(cid);

        // Build metadata
        Map<String, Object> metadata = new LinkedHashMap<>();
        String clientId = resolveClientId(session);
        metadata.put("remote", getRemoteAddress(session));
        if (Boolean.TRUE.equals(envelope.get("webui"))) {
            metadata.put("webui", true);
            metadata.putAll(transcriptManager.clientTurnMetadata(
                    (String) envelope.get("turn_id")));
        }

        // Normalize CLI app mentions
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cliApps =
                (List<Map<String, Object>>) envelope.get("cli_apps");
        List<Map<String, Object>> normalizedCliApps =
                CliAppMentionNormalizer.normalize(cliApps);
        if (!normalizedCliApps.isEmpty()) {
            metadata.put("cli_apps", normalizedCliApps);
        }

        // Normalize MCP preset mentions
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mcpPresets =
                (List<Map<String, Object>>) envelope.get("mcp_presets");
        List<Map<String, Object>> normalizedMcp =
                McpPresetMentionNormalizer.normalize(mcpPresets);
        if (!normalizedMcp.isEmpty()) {
            metadata.put("mcp_presets", normalizedMcp);
        }

        // Image generation metadata
        @SuppressWarnings("unchecked")
        Map<String, Object> imageGen =
                (Map<String, Object>) envelope.get("image_generation");
        if (imageGen != null && Boolean.TRUE.equals(imageGen.get("enabled"))) {
            Map<String, Object> ig = new LinkedHashMap<>();
            ig.put("enabled", true);
            ig.put("aspect_ratio", imageGen.get("aspect_ratio") instanceof String s ? s : null);
            metadata.put("image_generation", ig);
        }

        // Attach workspace scope to metadata
        metadata.put(WORKSPACE_SCOPE_METADATA_KEY, scope.metadata());
        workspaceController.persistScope(cid, scope);

        // Record user message in transcript
        if (Boolean.TRUE.equals(envelope.get("webui")) && isAllowed(clientId)) {
            transcriptManager.appendUserMessage(
                    cid, content, metadata,
                    mediaPaths.isEmpty() ? null : mediaPaths,
                    normalizedCliApps.isEmpty() ? null : normalizedCliApps,
                    normalizedMcp.isEmpty() ? null : normalizedMcp);
        }

        // Forward to agent
        handleMessage(
                clientId,
                cid,
                content,
                mediaPaths.isEmpty() ? null : mediaPaths,
                metadata,
                null,
                false  // isDm=false — WebSocket already authenticated
        );
    }

    // ---- outbound: send complete message ----

    @Override
    public void send(OutboundMessage msg) throws Exception {
        // Handle special metadata-led events first
        if (msg.getMetadata().containsKey("_runtime_model_updated")) {
            sendRuntimeModelUpdated(
                    (String) msg.getMetadata().get("model"),
                    (String) msg.getMetadata().get("model_preset"));
            return;
        }
        if (msg.getMetadata().containsKey("_goal_state_sync")) {
            sendGoalState(msg.getChatId(), extractGoalState(msg));
            return;
        }
        if (msg.getMetadata().containsKey("_goal_status")) {
            String status = (String) msg.getMetadata().get("goal_status");
            Object startedRaw = msg.getMetadata().get("started_at");
            Double startedAt = (startedRaw instanceof Number n) ? n.doubleValue() : null;
            sendGoalStatus(msg.getChatId(), status, startedAt);
            return;
        }
        if (msg.getMetadata().containsKey("_turn_end")) {
            sendTurnEnd(msg);
            return;
        }
        if (msg.getMetadata().containsKey("_session_updated")) {
            sendSessionUpdated(msg.getChatId(), msg.getMetadata());
            return;
        }
        if (msg.getMetadata().containsKey("_file_edit_events")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> edits =
                    (List<Map<String, Object>>) msg.getMetadata().get("_file_edit_events");
            sendFileEditEvents(msg.getChatId(), edits, msg.getMetadata());
            return;
        }

        // Normal message — build payload (mirrors Python send())
        // Rewrite local markdown image paths for the wire
        String wireText = mediaManager.rewriteLocalMarkdownImages(msg.getContent());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "message");
        payload.put("chat_id", msg.getChatId());
        payload.put("text", wireText);

        if (msg.getMedia() != null && !msg.getMedia().isEmpty()) {
            payload.put("media", msg.getMedia());
            // Sign or stage local media paths for HTTP serving
            List<Map<String, String>> mediaUrls = new ArrayList<>();
            for (String entry : msg.getMedia()) {
                Map<String, String> signed =
                        mediaManager.signOrStageMediaPath(java.nio.file.Path.of(entry));
                if (signed != null) {
                    mediaUrls.add(signed);
                }
            }
            if (!mediaUrls.isEmpty()) {
                payload.put("media_urls", mediaUrls);
            }
        }
        if (msg.getReplyTo() != null) {
            payload.put("reply_to", msg.getReplyTo());
        }

        Object lat = msg.getMetadata().get("latency_ms");
        if (lat instanceof Number n) {
            payload.put("latency_ms", n.intValue());
        }

        if (msg.getMetadata().get("_tool_events") instanceof List<?> toolEvents) {
            payload.put("tool_events", toolEvents);
        }

        Object agentUi = msg.getMetadata().get(OUTBOUND_META_AGENT_UI);
        if (agentUi != null) {
            payload.put("agent_ui", agentUi);
        }

        // Progress / tool_hint markers
        String kind = null;
        if (msg.getMetadata().containsKey("_tool_hint")) {
            payload.put("kind", "tool_hint");
            kind = "tool_hint";
        } else if (msg.getMetadata().containsKey("_progress")) {
            payload.put("kind", "progress");
            kind = "progress";
        }
        String phase = (kind != null) ? "activity" : "answer";
        transcriptManager.prepareAndAppend(
                msg.getChatId(), payload, msg.getMetadata(), phase,
                true, Map.of("text", msg.getContent()));

        // Fan-out to all subscribers of this chatId
        String raw = objectMapper.writeValueAsString(payload);
        List<WebSocketSession> connections = getConnections(msg.getChatId());
        if (connections.isEmpty()) {
            return;
        }
        for (WebSocketSession session : connections) {
            safeSendTo(session, raw, " ");
        }
    }

    // ---- outbound: streaming ----

    @Override
    public void sendDelta(String chatId, String delta, Map<String, Object> metadata)
            throws Exception {
        Map<String, Object> meta = (metadata != null) ? metadata : Map.of();
        String streamKey = (String) meta.getOrDefault("_stream_id", "");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);

        if (meta.containsKey("_stream_end")) {
            body.put("event", "stream_end");
            // Flush buffered chunks
            StreamBufferKey key = new StreamBufferKey(chatId, streamKey);
            List<String> buffered = streamTextBuffers.remove(key);
            if (buffered != null) {
                if (!delta.isEmpty()) {
                    buffered.add(delta);
                }
                String fullText = String.join("", buffered);
                // Rewrite local markdown image paths for the wire
                String rewritten = mediaManager.rewriteLocalMarkdownImages(fullText);
                if (!delta.isEmpty() || !rewritten.equals(fullText)) {
                    body.put("text", rewritten);
                }
            }
        } else {
            body.put("event", "delta");
            body.put("text", delta);
            // Buffer
            StreamBufferKey key = new StreamBufferKey(chatId, streamKey);
            streamTextBuffers.computeIfAbsent(key, k -> new ArrayList<>()).add(delta);
        }

        if (meta.containsKey("_stream_id")) {
            body.put("stream_id", meta.get("_stream_id"));
        }

        // Record in transcript
        transcriptManager.prepareAndAppend(
                chatId, body, meta, "answer",
                /* includeSource= */ false, null);

        String raw = objectMapper.writeValueAsString(body);
        List<WebSocketSession> connections = getConnections(chatId);
        if (connections.isEmpty()) {
            return;
        }
        for (WebSocketSession session : connections) {
            safeSendTo(session, raw, " stream ");
        }
    }

    @Override
    public void sendReasoningDelta(String chatId, String delta,
                                    Map<String, Object> metadata) throws Exception {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        Map<String, Object> meta = (metadata != null) ? metadata : Map.of();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", "reasoning_delta");
        body.put("chat_id", chatId);
        body.put("text", delta);

        if (meta.containsKey("_stream_id")) {
            body.put("stream_id", meta.get("_stream_id"));
        }

        // Record in transcript
        transcriptManager.prepareAndAppend(
                chatId, body, meta, "reasoning",
                /* includeSource= */ false, null);

        String raw = objectMapper.writeValueAsString(body);
        List<WebSocketSession> connections = getConnections(chatId);
        if (connections.isEmpty()) {
            return;
        }
        for (WebSocketSession session : connections) {
            safeSendTo(session, raw, " reasoning ");
        }
    }

    @Override
    public void sendReasoningEnd(String chatId, Map<String, Object> metadata) throws Exception {
        Map<String, Object> meta = (metadata != null) ? metadata : Map.of();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", "reasoning_end");
        body.put("chat_id", chatId);

        if (meta.containsKey("_stream_id")) {
            body.put("stream_id", meta.get("_stream_id"));
        }

        // Record in transcript
        transcriptManager.prepareAndAppend(
                chatId, body, meta, "reasoning",
                /* includeSource= */ false, null);

        String raw = objectMapper.writeValueAsString(body);
        List<WebSocketSession> connections = getConnections(chatId);
        if (connections.isEmpty()) {
            return;
        }
        for (WebSocketSession session : connections) {
            safeSendTo(session, raw, " reasoning_end ");
        }
    }

    // ---- outbound: events ----

    private void sendTurnEnd(OutboundMessage msg) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", "turn_end");
        body.put("chat_id", msg.getChatId());

        Object lat = msg.getMetadata().get("latency_ms");
        if (lat instanceof Number n) {
            body.put("latency_ms", n.intValue());
        }
        Object gs = msg.getMetadata().get("goal_state");
        if (gs instanceof Map<?, ?> goalState) {
            body.put("goal_state", goalState);
        }

        // Record in transcript
        transcriptManager.prepareAndAppend(
                msg.getChatId(), body, msg.getMetadata(), "complete",
                /* includeSource= */ false, null);

        String raw = objectMapper.writeValueAsString(body);
        List<WebSocketSession> connections = getConnections(msg.getChatId());
        if (connections.isEmpty()) {
            return;
        }
        for (WebSocketSession session : connections) {
            safeSendTo(session, raw, " turn_end ");
        }
    }

    private void sendGoalState(String chatId, Map<String, Object> blob) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", "goal_state");
        body.put("chat_id", chatId);
        body.put("goal_state", blob);

        String raw = objectMapper.writeValueAsString(body);
        for (WebSocketSession session : getConnections(chatId)) {
            safeSendTo(session, raw, "goal_state");
        }
    }

    private void sendGoalStatus(String chatId, String status, Double startedAt)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", "goal_status");
        body.put("chat_id", chatId);
        body.put("status", status);

        if ("running".equals(status) && startedAt != null) {
            body.put("started_at", startedAt);
        }

        String raw = objectMapper.writeValueAsString(body);
        for (WebSocketSession session : getConnections(chatId)) {
            safeSendTo(session, raw, "goal_status");
        }
    }

    private void sendSessionUpdated(String chatId, Map<String, Object> metadata)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", "session_updated");
        body.put("chat_id", chatId);

        Object scope = metadata.get("_session_update_scope");
        if (scope instanceof String s) {
            body.put("scope", s);
        }

        String raw = objectMapper.writeValueAsString(body);
        for (WebSocketSession session : getConnections(chatId)) {
            safeSendTo(session, raw, "session_updated");
        }
    }

    private void sendFileEditEvents(String chatId, List<Map<String, Object>> edits,
                                     Map<String, Object> metadata) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", "file_edit");
        body.put("chat_id", chatId);
        body.put("edits", edits);

        // Record in transcript
        transcriptManager.prepareAndAppend(
                chatId, body, metadata, "activity",
                /* includeSource= */ false, null);

        String raw = objectMapper.writeValueAsString(body);
        List<WebSocketSession> connections = getConnections(chatId);
        if (connections.isEmpty()) {
            return;
        }
        for (WebSocketSession session : connections) {
            safeSendTo(session, raw, " file_edit ");
        }
    }

    private void sendRuntimeModelUpdated(String modelName, String modelPreset)
            throws Exception {
        if (modelName == null || modelName.isBlank()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", "runtime_model_updated");
        body.put("model_name", modelName.strip());
        if (modelPreset != null && !modelPreset.isBlank()) {
            body.put("model_preset", modelPreset.strip());
        }

        String raw = objectMapper.writeValueAsString(body);
        // Broadcast to ALL connections (not per-chatId)
        for (WebSocketSession session : connChats.keySet()) {
            safeSendTo(session, raw, "runtime_model_updated");
        }
    }

    // ---- helpers ----

    private void safeSendTo(WebSocketSession session, String raw, String label) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(raw));
            }
        } catch (Exception e) {
            logger.warn("failed to send {} event: {}", label, e.getMessage());
            cleanupConnection(session);
        }
    }

    private List<WebSocketSession> getConnections(String chatId) {
        Set<WebSocketSession> set = subs.get(chatId);
        if (set == null || set.isEmpty()) {
            return List.of();
        }
        return List.copyOf(set); // snapshot for safe iteration
    }

    private void sendEvent(WebSocketSession session, String event,
                            Map<String, Object> fields) {
        Map<String, Object> payload = new LinkedHashMap<>(fields);
        payload.put("event", event);
        try {
            String raw = objectMapper.writeValueAsString(payload);
            safeSendTo(session, raw, event);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize {} event", event, e);
        }
    }

    private void sendErrorEvent(WebSocketSession session, String detail) {
        sendEvent(session, "error", Map.of("detail", detail));
    }

    private void sendErrorEvent(WebSocketSession session, String detail,
                                 Map<String, Object> extra) {
        Map<String, Object> fields = new LinkedHashMap<>(extra);
        fields.put("detail", detail);
        sendEvent(session, "error", fields);
    }

    // ---- workspace scope helpers ----

    /**
     * Resolve a workspace scope, sending an error event to the client on failure.
     * Mirrors Python {@code _workspace_scope_or_error}.
     */
    private WorkspaceScope workspaceScopeOrError(
            WebSocketSession session,
            Supplier<WorkspaceScope> resolver,
            String chatId) {
        try {
            return resolver.get();
        } catch (WorkspaceScopeException e) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("detail", "workspace_scope_rejected");
            fields.put("reason", e.getMessage());
            fields.put("chat_id", chatId);
            sendEvent(session, "error", fields);
            return null;
        }
    }

    // ---- authentication ----

    private boolean authenticate(String suppliedToken) {
        String staticToken = wsConfig.getToken().strip();

        if (!staticToken.isEmpty()) {
            if (!suppliedToken.isEmpty()
                    && MessageDigest.isEqual(
                        suppliedToken.getBytes(StandardCharsets.UTF_8),
                        staticToken.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
            if (!suppliedToken.isEmpty()
                    && tokenManager.takeIssuedTokenIfValid(suppliedToken)) {
                return true;
            }
            return false;
        }

        if (wsConfig.isWebsocketRequiresToken()) {
            if (!suppliedToken.isEmpty()
                    && tokenManager.takeIssuedTokenIfValid(suppliedToken)) {
                return true;
            }
            return false;
        }

        // Best-effort: validate if provided, but don't require
        if (!suppliedToken.isEmpty()) {
            tokenManager.takeIssuedTokenIfValid(suppliedToken);
        }
        return true;
    }

    private String resolveClientId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) {
            return "unknown";
        }
        Map<String, String> params = parseQueryParams(uri.getQuery());
        String clientId = params.getOrDefault("client_id", "").strip();
        if (clientId.isEmpty()) {
            return "unknown";
        }
        if (clientId.length() > 128) {
            clientId = clientId.substring(0, 128);
        }
        return clientId;
    }

    private String getRemoteAddress(WebSocketSession session) {
        if (session.getRemoteAddress() instanceof InetSocketAddress addr) {
            return addr.getHostString();
        }
        return null;
    }

    // ---- media processing ----

    private record MediaSaveResult(List<String> paths, String error) {}

    private MediaSaveResult saveEnvelopeMedia(List<Map<String, Object>> media) {
        int imageCount = 0;
        int videoCount = 0;
        for (Map<String, Object> item : media) {
            String mime = extractDataUrlMime((String) item.get("data_url"));
            if (VIDEO_MIME_ALLOWED.contains(mime)) {
                videoCount++;
            } else if (IMAGE_MIME_ALLOWED.contains(mime)) {
                imageCount++;
            }
        }
        if (imageCount > MAX_IMAGES_PER_MESSAGE) {
            return new MediaSaveResult(List.of(), "too_many_images");
        }
        if (videoCount > MAX_VIDEOS_PER_MESSAGE) {
            return new MediaSaveResult(List.of(), "too_many_videos");
        }

        List<String> paths = new ArrayList<>();
        for (Map<String, Object> item : media) {
            String dataUrl = (String) item.get("data_url");
            if (dataUrl == null || dataUrl.isBlank()) {
                abortMedia(paths);
                return new MediaSaveResult(List.of(), "malformed");
            }
            String mime = extractDataUrlMime(dataUrl);
            if (mime == null) {
                abortMedia(paths);
                return new MediaSaveResult(List.of(), "decode");
            }
            if (!UPLOAD_MIME_ALLOWED.contains(mime)) {
                abortMedia(paths);
                return new MediaSaveResult(List.of(), "mime");
            }
            boolean isVideo = VIDEO_MIME_ALLOWED.contains(mime);
            long maxBytes = isVideo ? MAX_VIDEO_BYTES : MAX_IMAGE_BYTES;

            try {
                String saved = mediaManager.saveBase64DataUrl(dataUrl, maxBytes);
                if (saved == null) {
                    abortMedia(paths);
                    return new MediaSaveResult(List.of(), "decode");
                }
                paths.add(saved);
            } catch (MediaSizeExceededException e) {
                abortMedia(paths);
                return new MediaSaveResult(List.of(), "size");
            } catch (Exception e) {
                logger.warn("media decode failed: {}", e.getMessage());
                abortMedia(paths);
                return new MediaSaveResult(List.of(), "decode");
            }
        }
        return new MediaSaveResult(paths, null);
    }

    private void abortMedia(List<String> paths) {
        for (String path : paths) {
            try {
                java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(path));
            } catch (Exception e) {
                logger.warn("failed to unlink partial media {}: {}", path, e.getMessage());
            }
        }
    }

    // ---- static helpers ----

    private static final java.util.regex.Pattern DATA_URL_MIME_RE =
            java.util.regex.Pattern.compile(
                    "^data:([^;,]+)(?:;[^,]*)*;base64,", java.util.regex.Pattern.DOTALL);

    static String extractDataUrlMime(String url) {
        if (url == null) {
            return null;
        }
        java.util.regex.Matcher m = DATA_URL_MIME_RE.matcher(url);
        if (!m.find()) {
            return null;
        }
        String mime = m.group(1).strip().toLowerCase();
        return mime.isEmpty() ? null : mime;
    }

    static Map<String, Object> parseEnvelope(String raw) {
        String trimmed = raw.strip();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(trimmed, Map.class);
            if (data.get("type") instanceof String) {
                return data;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    static String parseInboundPayload(String raw) {
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("{")) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = objectMapper.readValue(trimmed, Map.class);
                // Try extracting from known keys
                for (String key : List.of("content", "text", "message")) {
                    Object value = data.get(key);
                    if (value instanceof String s && !s.strip().isEmpty()) {
                        return s;
                    }
                }
                return null;
            } catch (Exception e) {
                return trimmed; // not valid JSON, treat as plain text
            }
        }
        return trimmed;
    }

    private static final java.util.regex.Pattern CHAT_ID_RE =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_:-]{1,64}$");

    static boolean isValidChatId(String value) {
        return value != null && CHAT_ID_RE.matcher(value).matches();
    }

    static Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return params;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                String key = java.net.URLDecoder.decode(
                        pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = java.net.URLDecoder.decode(
                        pair.substring(eq + 1), StandardCharsets.UTF_8);
                params.put(key, value);
            } else {
                String key = java.net.URLDecoder.decode(pair, StandardCharsets.UTF_8);
                params.put(key, "");
            }
        }
        return params;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractGoalState(OutboundMessage msg) {
        Object blob = msg.getMetadata().get("goal_state");
        if (blob instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of("active", false);
    }
}
```

#### 1.3.3 支持类型 — GatewayServices 与对外接口

WebSocketChannel 依赖 GatewayServices 中汇聚的几个服务（对标 Python `nanobot/webui/gateway_services.py`）：

```java
package com.nanobot.webui;

/**
 * Explicit dependency bundle shared by WebSocket transport and HTTP routes.
 * Mirrors Python GatewayServices dataclass.
 */
public record GatewayServices(
        GatewayHTTPHandler http,
        GatewayTokenStore tokens,
        WebUIMediaGateway media,
        WebUITranscriptRecorder transcripts,
        WebUIWorkspaceController workspaces,
        SessionManager sessionManager,
        CronService cronService
) {}
```

**关键接口与记录类型**（按需定义，或指向对应模块文档）：

```java
// WebUIWorkspaceController — 对标 Python WebUIWorkspaceController
public interface WebUIWorkspaceController {
    WorkspaceScope scopeForNewChat(Map<String, Object> envelope,
                                    boolean controlsAvailable);
    WorkspaceScope scopeForSetRequest(Map<String, Object> envelope,
                                       String chatId, boolean chatRunning,
                                       boolean controlsAvailable);
    WorkspaceScope scopeForMessage(Map<String, Object> envelope,
                                    String chatId, boolean chatRunning,
                                    boolean controlsAvailable);
    void persistScope(String chatId, WorkspaceScope scope);
}

// WorkspaceScope — 对标 Python workspace_access 中的 scope 对象
public record WorkspaceScope(String path, boolean restrictToWorkspace) {
    public Map<String, Object> payload() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("path", path);
        p.put("restrict_to_workspace", restrictToWorkspace);
        return p;
    }
    public Map<String, Object> metadata() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("workspace_path", path);
        m.put("restrict_to_workspace", restrictToWorkspace);
        return m;
    }
}

// WorkspaceScopeException — 对标 Python WorkspaceScopeError
public class WorkspaceScopeException extends RuntimeException {
    private final String message;
    public WorkspaceScopeException(String message) { this.message = message; }
    @Override public String getMessage() { return message; }
}

// TranscriptionResult — 对标 Python webui_transcription_event 返回值
public record TranscriptionResult(String event, Map<String, Object> payload) {}

// WebUITranscriptionHandler — 对标 Python webui_transcription_event()
public interface WebUITranscriptionHandler {
    TranscriptionResult processTranscriptionEvent(Map<String, Object> envelope);
}

// WebuiForking — 对标 Python handle_webui_fork_chat()
public final class WebuiForking {
    public static void handleForkChat(WebSocketChannel channel,
                                       WebSocketSession session,
                                       Map<String, Object> envelope) {
        // Implemented in forking module — see forking documentation
    }
}

// CliAppMentionNormalizer — 对标 Python normalize_cli_app_mentions()
public final class CliAppMentionNormalizer {
    public static List<Map<String, Object>> normalize(
            List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        // Filter & normalize CLI app references
        return raw;
    }
}

// McpPresetMentionNormalizer — 对标 Python normalize_mcp_preset_mentions()
public final class McpPresetMentionNormalizer {
    public static List<Map<String, Object>> normalize(
            List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        // Filter & normalize MCP preset references
        return raw;
    }
}
```

**MediaManager 新增方法**（对标 Python `WebUIMediaGateway`）：

```java
// 添加至 MediaManager 类中:

/**
 * Rewrite local markdown image paths (![](/media/...)) to signed URLs
 * for remote clients. Mirrors Python rewrite_local_markdown_images().
 */
public String rewriteLocalMarkdownImages(String markdown) {
    // Replace local /media/ paths with signed download URLs
    return markdown; // full implementation with regex substitution
}

/**
 * Sign or stage a local media path for HTTP serving.
 * Returns a map with "url" and optional signing params, or null.
 * Mirrors Python sign_or_stage_media_path().
 */
public Map<String, String> signOrStageMediaPath(java.nio.file.Path entry) {
    // Generate a signed download URL or staging reference
    return Map.of("url", "/media/" + entry.getFileName());
}
```

**TranscriptManager 新增方法**（对标 Python `WebUITranscriptRecorder`）：

```java
// 添加至 TranscriptManager 类中:

/**
 * Prepare and append a payload to the transcript for the given chat.
 * Mirrors Python prepare_and_append().
 */
public void prepareAndAppend(String chatId, Map<String, Object> payload,
                              Map<String, Object> metadata, String phase,
                              boolean includeSource,
                              Map<String, Object> transcriptOverrides) {
    // Deep-clone payload, apply overrides, and write to transcript log
}

/**
 * Record a user-originated message in the transcript.
 * Mirrors Python append_user_message().
 */
public void appendUserMessage(String chatId, String content,
                               Map<String, Object> metadata,
                               List<String> mediaPaths,
                               List<Map<String, Object>> cliApps,
                               List<Map<String, Object>> mcpPresets) {
    // Write a user-message entry to the transcript JSONL
}

/**
 * Derive transcript metadata from a client-supplied turn_id.
 * Mirrors Python client_turn_metadata().
 */
public Map<String, Object> clientTurnMetadata(String turnId) {
    if (turnId == null || turnId.isEmpty()) return Map.of();
    return Map.of("turn_id", turnId);
}
```

#### 1.3.4 `TokenManager.java` — Token 管理

```java
package com.nanobot.channels.websocket;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages short-lived tokens for WebSocket authentication.
 * <p>
 * Tokens are issued via the HTTP {@code token_issue_path} endpoint
 * and validated on WebSocket handshake. Each token is single-use
 * (consumed on first validation) and expires after TTL seconds.
 */
public class TokenManager {

    private final WebSocketConfig config;
    private final SecureRandom secureRandom = new SecureRandom();

    /** token → expiry timestamp (epoch millis) */
    private final ConcurrentHashMap<String, Long> issuedTokens = new ConcurrentHashMap<>();

    public TokenManager(WebSocketConfig config) {
        this.config = config;
    }

    /**
     * Issue a new token. Returns a map with "token" and "expires_in" keys.
     */
    public Map<String, Object> issueToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long expiresAt = System.currentTimeMillis() + (config.getTokenTtlS() * 1000L);
        issuedTokens.put(token, expiresAt);
        return Map.of(
                "token", token,
                "expires_in", config.getTokenTtlS()
        );
    }

    /**
     * Validate and consume an issued token.
     * Returns true if the token is valid and not expired.
     * Each token can only be used once (consumed on first valid use).
     */
    public boolean takeIssuedTokenIfValid(String token) {
        Long expiresAt = issuedTokens.remove(token);
        if (expiresAt == null) {
            return false;
        }
        return System.currentTimeMillis() < expiresAt;
    }

    /**
     * Remove all issued tokens (called on channel shutdown).
     */
    public void clear() {
        issuedTokens.clear();
    }

    /**
     * Remove expired tokens (called periodically by a background cleanup task).
     */
    public void removeExpired() {
        long now = System.currentTimeMillis();
        issuedTokens.entrySet().removeIf(e -> e.getValue() < now);
    }
}
```

#### 1.3.5 Spring WebSocket 配置

Spring Boot 需要显式配置 WebSocket endpoint：

```java
package com.nanobot.channels.websocket;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketServerConfig implements WebSocketConfigurer {

    private final WebSocketChannel webSocketChannel;

    public WebSocketServerConfig(WebSocketChannel webSocketChannel) {
        this.webSocketChannel = webSocketChannel;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(webSocketChannel, "/")
                .setAllowedOrigins("*")
                .addInterceptors(handshakeInterceptor());
    }

    @Bean
    public HandshakeInterceptor handshakeInterceptor() {
        return new WebSocketHandshakeInterceptor();
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(40 * 1024 * 1024); // 40 MB
        container.setMaxBinaryMessageBufferSize(40 * 1024 * 1024);
        container.setMaxSessionIdleTimeout(300_000L); // 5 minutes
        return container;
    }
}
```

#### 1.3.6 `MediaManager.java` — 媒体解码、存储与 URL 签名

```java
package com.nanobot.channels.websocket;

import com.nanobot.utils.media_decode.FileSizeExceededException;
import com.nanobot.utils.media_decode.MediaDecodeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Handles base64 media decoding, storage, and wire-URL signing.
 * Mirrors Python save_base64_data_url() + WebUIMediaGateway methods.
 */
public class MediaManager {

    private static final Logger logger = LoggerFactory.getLogger(MediaManager.class);

    private final String channelName;

    public MediaManager(String channelName) {
        this.channelName = channelName;
    }

    /**
     * Save a base64 data URL to disk under the media directory.
     * Returns the absolute path of the saved file, or null on failure.
     */
    public String saveBase64DataUrl(String dataUrl, long maxBytes)
            throws FileSizeExceededException, IOException {
        return MediaDecodeUtils.saveBase64DataUrl(dataUrl,
                getMediaDir(channelName), maxBytes);
    }

    /**
     * Rewrite local markdown image paths (![](/media/...)) to signed
     * download URLs for remote clients. Mirrors Python
     * {@code rewrite_local_markdown_images()}.
     */
    public String rewriteLocalMarkdownImages(String markdown) {
        if (markdown == null || markdown.isEmpty()) return markdown;
        // Replace /media/... paths with signed/staged URLs
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "!\\[([^]]*)]\\((/media/[^)\\s]+)\\)");
        java.util.regex.Matcher m = p.matcher(markdown);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String alt = m.group(1);
            String path = m.group(2);
            Map<String, String> signed = signOrStageMediaPath(Path.of(path));
            String url = (signed != null) ? signed.get("url") : path;
            m.appendReplacement(sb, "![" + alt + "](" + url + ")");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Sign or stage a local media path for HTTP serving.
     * Returns a map with "url" (and optionally other signing params), or null.
     * Mirrors Python {@code sign_or_stage_media_path()}.
     */
    public Map<String, String> signOrStageMediaPath(Path entry) {
        String filename = entry.getFileName().toString();
        return Map.of("url", "/media/" + filename);
    }

    private Path getMediaDir(String channel) {
        return Path.of(System.getProperty("nanobot.data.dir", "data"),
                "media", channel);
    }
}
```

#### 1.3.7 `TranscriptManager.java` — 会话转录记录

```java
package com.nanobot.channels.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

/**
 * Records WebSocket chat transcripts for the WebUI.
 * Mirrors Python WebUITranscriptRecorder.
 */
public class TranscriptManager {

    private static final Logger logger = LoggerFactory.getLogger(TranscriptManager.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Prepare and append a payload to the transcript JSONL for the given chat.
     * Mirrors Python {@code prepare_and_append()}.
     */
    public void prepareAndAppend(String chatId, Map<String, Object> payload,
                                  Map<String, Object> metadata, String phase,
                                  boolean includeSource,
                                  Map<String, Object> transcriptOverrides) {
        Map<String, Object> entry = new LinkedHashMap<>(payload);
        if (transcriptOverrides != null) {
            entry.putAll(transcriptOverrides);
        }
        // Write to transcript JSONL: data/transcripts/{chatId}.jsonl
        // Implementation writes one JSON line per entry
        try {
            Path transcriptPath = getTranscriptPath(chatId);
            Files.createDirectories(transcriptPath.getParent());
            String line = objectMapper.writeValueAsString(entry) + "\n";
            Files.writeString(transcriptPath, line,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            logger.warn("failed to write transcript for {}: {}", chatId, e.getMessage());
        }
    }

    /**
     * Record a user-originated message in the transcript.
     * Mirrors Python {@code append_user_message()}.
     */
    public void appendUserMessage(String chatId, String content,
                                   Map<String, Object> metadata,
                                   List<String> mediaPaths,
                                   List<Map<String, Object>> cliApps,
                                   List<Map<String, Object>> mcpPresets) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("role", "user");
        entry.put("content", content);
        if (mediaPaths != null) entry.put("media", mediaPaths);
        if (cliApps != null) entry.put("cli_apps", cliApps);
        if (mcpPresets != null) entry.put("mcp_presets", mcpPresets);
        writeLine(chatId, entry);
    }

    /**
     * Derive transcript metadata from a client-supplied turn_id.
     * Mirrors Python {@code client_turn_metadata()}.
     */
    public Map<String, Object> clientTurnMetadata(String turnId) {
        if (turnId == null || turnId.isEmpty()) return Map.of();
        return Map.of("turn_id", turnId);
    }

    private void writeLine(String chatId, Map<String, Object> entry) {
        try {
            Path p = getTranscriptPath(chatId);
            Files.createDirectories(p.getParent());
            String line = objectMapper.writeValueAsString(entry) + "\n";
            Files.writeString(p, line,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            logger.warn("transcript write failed: {}", e.getMessage());
        }
    }

    private Path getTranscriptPath(String chatId) {
        return Path.of(System.getProperty("nanobot.data.dir", "data"),
                "transcripts", chatId + ".jsonl");
    }
}
```

### 1.4 入站信封类型总览

| 类型 | 描述 | 处理 |
|------|------|------|
| `"message"` | 用户消息（文本 + 可选 media） | 解析 media → `handleMessage()` → `bus.publishInbound()` |
| `"new_chat"` | 创建新聊天会话 | 生成 chatId → `attach()` → `hydrateAfterSubscribe()` |
| `"fork_chat"` | 从现有聊天分叉 | 委托给 forking 模块 |
| `"attach"` | 订阅已有 chatId | `attach(session, chatId)` → hydrate |
| `"set_workspace_scope"` | 设置工作区作用域 | 解析 scope → persist → `session_updated` 事件 |
| `"transcribe_audio"` | 音频转录请求 | 委托给 transcription 模块 |

### 1.5 出站事件类型总览

| 事件 | 触发条件 | 目标 |
|------|---------|------|
| `"ready"` | 连接建立 | 单一连接 |
| `"attached"` | 成功订阅 chatId | 单一连接 |
| `"message"` | `send()` 调用 | chatId 所有订阅者 |
| `"delta"` | 流式文本块 | chatId 所有订阅者 |
| `"stream_end"` | 流式结束 | chatId 所有订阅者 |
| `"reasoning_delta"` | 推理文本块 | chatId 所有订阅者 |
| `"reasoning_end"` | 推理结束 | chatId 所有订阅者 |
| `"goal_state"` | 持久化目标状态 | chatId 所有订阅者 |
| `"goal_status"` | 目标运行/空闲状态 | chatId 所有订阅者 |
| `"runtime_model_updated"` | 运行时模型变更 | **全部连接** |
| `"session_updated"` | 会话元数据变更 | chatId 所有订阅者 |
| `"turn_end"` | Agent turn 结束 | chatId 所有订阅者 |
| `"file_edit"` | 文件编辑事件 | chatId 所有订阅者 |
| `"error"` | 入站处理错误 | 单一连接 |

---

## 2. 其他 16 个 Channel 实现摘要

### 2.1 Channel 摘要表

| # | Channel | Python 行数 | Java SDK | 通信模式 | 复杂度 |
|---|---------|------------|----------|---------|--------|
| 1 | WebSocket | 1,179 | Spring WebSocket | Server (接受连接) | 极高 |
| 2 | Telegram | 1,472 | [telegrambots](https://github.com/rubenlagus/TelegramBots) 7.x | Long Poll / Webhook | 高 |
| 3 | Feishu | 1,984 | [Lark SDK](https://github.com/larksuite/oapi-sdk-java) | Event Subscription | 极高 |
| 4 | Weixin | 1,586 | HTTP client (自实现协议) | HTTP Long Poll | 极高 |
| 5 | Discord | ~900 | [JDA](https://github.com/discord-jda/JDA) 5.x | Gateway WebSocket | 高 |
| 6 | Slack | ~800 | [bolt-java](https://github.com/slackapi/java-slack-sdk) | Socket Mode / Webhook | 高 |
| 7 | Matrix | ~700 | [matrix-sdk](https://github.com/ma1uta/matrix-java-sdk) | Client-Server HTTP + Sync | 高 |
| 8 | QQ | ~700 | HTTP client (go-cqhttp 兼容) | 反向 WebSocket | 中 |
| 9 | DingTalk | ~650 | [dingtalk-sdk](https://github.com/alibaba/dingtalk-sdk-java) | Stream Mode | 中 |
| 10 | MS Teams | ~600 | [Bot Framework SDK](https://github.com/microsoft/botbuilder-java) | Bot Connector | 中 |
| 11 | Signal | ~600 | 进程管理 (signal-cli) | 子进程 stdin/stdout | 中 |
| 12 | MoChat | ~500 | HTTP client | Webhook + HTTP API | 中 |
| 13 | WeCom | ~400 | HTTP client | Webhook 回调 | 低-中 |
| 14 | Email | ~350 | Jakarta Mail 2.1 (Angus Mail) | IMAP IDLE + SMTP | 低 |
| 15 | WhatsApp | ~350 | Node.js bridge (维持) | 桥接 WebSocket | 低 |
| 16 | Napcat | ~300 | HTTP / WebSocket client | napcat HTTP API | 低-中 |

### 2.2 Telegram — 详细实现笔记

**Python 依赖:** `python-telegram-bot` (PTB) v20+, HTTPX, Telegram Bot API

**Java 替代方案:** `org.telegram:telegrambots` (7.x) 或 `org.telegram:telegrambots-spring-boot-starter`

**关键实现要点:**

```java
package com.nanobot.channels.telegram;

import com.nanobot.channels.core.BaseChannel;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;

public class TelegramChannel extends BaseChannel {

    // Python 行为 → Java 映射:
    //
    // asyncio.create_task(start_polling())  → Virtual thread with LongPollingBot
    // MessageHandler(filters.TEXT & ~filters.COMMAND) → @Override onUpdateReceived()
    // CallbackQueryHandler → inline keyboard handler
    //
    // 消息分割: TELEGRAM_MAX_MESSAGE_LEN = 4000 chars
    //   → split_message() 按段落边界分割，避免截断 markdown
    //
    // 流式发送:
    //   send_delta → 编辑上一条消息 (editMessageText)
    //   send_reasoning_delta → <blockquote expandable> HTML 标签
    //
    // 媒体处理:
    //   sendPhoto / sendDocument → 本地文件路径上传
    //   MAX_FILE_SIZE = 50MB (Bot API 限制)
    //
    // 入站处理:
    //   Update.message.text → handleMessage(senderId=user_id, chatId=chat_id, content=text)
    //   Update.message.photo → 下载 largest photo → handleMessage(... media=[path])
}
```

**配置类:**

```java
public class TelegramConfig extends ChannelConfig {
    private String botToken = "";
    private String proxyUrl = "";
    private boolean allowGroups = true;
    private String groupPolicy = "mention";  // "open" or "mention"
    private boolean dmEnabled = true;
    private boolean replyInThread = false;
}
```

### 2.3 Discord — 详细实现笔记

**Java 替代方案:** `net.dv8tion:JDA` (Java Discord API) 5.x

**关键实现要点:**

```java
// JDA 使用 builder 模式 + 事件监听器
JDA jda = JDABuilder.createDefault(botToken)
    .addEventListeners(new DiscordListener(this))
    .build();

// 流式发送: send_delta → 编辑上一条消息 (Message.editMessage())
// 注意 Discord 速率限制 (rate limiting): 每秒最多 5 次编辑
// 所以需要节流: 至少 200ms 间隔
//
// 消息长度限制: 2000 chars (普通消息), 4000 chars (Nitro)
// MAX_ATTACHMENT_BYTES = 20MB
//
// 推理展示: 使用 embed description 或 subtext 样式
```

### 2.4 Slack — 详细实现笔记

**Java 替代方案:** `com.slack.api:bolt-socket-mode` (Bolt Java SDK)

**关键实现要点:**

```java
// Socket Mode 客户端 (不需要公网 HTTP endpoint)
SocketModeClient client = new SocketModeClient(appToken);

// 事件分发:
//   EventsApiHandler → event.getEvent() type == "message" vs "app_mention"
//
// DM 策略:
//   "open" → 所有人可 DM
//   "pairing" → 需要配对审批
//
// 流式发送: slackify_markdown → blocks / text
// 推理展示: context block
```

### 2.5 Feishu (飞书) — 详细实现笔记

**Java 替代方案:** `com.larksuite.oapi:oapi-sdk` (Lark Open API SDK)

**关键实现要点:**

```java
// 飞书有复杂的认证体系:
//   - app_id + app_secret → get tenant_access_token / app_access_token
//   - Event Subscription 需要配置公网 URL 做 challenge 验证
//
// 消息类型:
//   text → 纯文本
//   post → 富文本 (支持 at, 链接, 图片)
//   interactive → 卡片消息
//
// 消息分割: 飞书有 30KB 限制，需要分段
//
// 流式发送: 回复消息后通过 message_id 编辑
```

### 2.6 Weixin (微信) — 详细实现笔记

**特点:** 使用逆向工程实现的 HTTP Long Poll API（非官方 SDK）

```java
// Weixin 使用 ilinkai.weixin.qq.com API:
//   - QR code 登录获取 token
//   - Long poll sync 检查新消息
//   - HTTP POST 发送消息
//
// 没有官方 Java SDK，需要自实现 HTTP 客户端协议:
//   - 认证: compute_auth_token() → HMAC-SHA256
//   - 轮询: syncCheck → 检测新消息
//   - 发送: webwxsendmsg → POST JSON
//
// 介质限制: 图片 < 10MB，文件 < 25MB
```

### 2.7 WhatsApp — 详细实现笔记

**特点:** WhatsApp 通道使用 Node.js bridge（通过 `@open-wa/wa-automate` 等），Python 端通过 WebSocket 连接到 bridge。

```java
// WhatsApp 在 Java 端有两种选择:
//
// 选项 A (推荐): 保持 Node.js bridge, Java 端作为 WebSocket 客户端连接
//   优点: 不需要重写复杂的 WhatsApp Web 协议
//   缺点: 需要 Node.js 运行时
//
// 选项 B: 使用 whatsappweb4j / java-whatsapp-web 等 Java 库
//   优点: 纯 Java
//   缺点: 库的维护程度参差不齐
//
// 当前端口选择: 选项 A — 维持 Node.js bridge
// 通过 OkHttp WebSocket 客户端连接到 bridge_url
```

### 2.8 其他 Channel 简要实现说明

**DingTalk (钉钉):**
- SDK: `com.aliyun:dingtalk` (dingtalk-sdk-java)
- 通信模式: Stream Mode (WebSocket 长连接)
- 消息类型: Text, Markdown, ActionCard, FeedCard
- 认证: client_id + client_secret → access_token

**Matrix:**
- SDK: `io.github.ma1uta.matrix:matrix-sdk` (社区 SDK)
- 通信模式: Client-Server HTTP API + `/sync` long poll
- E2E 加密: 可选支持 (olm/megolm)
- 媒体: MXC URI → 需要先上传

**QQ:**
- 后端: go-cqhttp (HTTP API + 反向 WebSocket)
- Java 端: HTTP client 调用 go-cqhttp API
- 消息格式: CQ code (类似 XML 标记)
- 混合消息: text + image + at 组合

**Napcat:**
- 与 QQ 类似，但使用 Napcat (基于 NTQQ 的 QQ Bot 框架)
- HTTP API 兼容 onebot 协议
- 支持正向/反向 WebSocket

**MS Teams:**
- SDK: `com.microsoft.bot:bot-builder` (Bot Framework SDK for Java)
- 通信模式: Bot Connector Service
- 认证: Microsoft App ID + Password → token exchange
- 自适应卡片 (Adaptive Cards)

**Signal:**
- 无官方 SDK → 通过 `signal-cli` 子进程交互
- 使用 `ProcessBuilder` 启动 signal-cli
- stdin/stdout JSON-RPC 通信
- 媒体: 文件路径 → signal-cli sendAttachment

**MoChat:**
- HTTP webhook 接收消息
- HTTP API 发送消息
- 支持群组规则和提及筛选
- 消息缓冲和延迟处理

**WeCom (企业微信):**
- HTTP webhook 回调接收消息
- 消息加解密 (AES + XML 格式)
- 被动回复 + 主动推送两种模式

**Email:**
- Jakarta Mail 2.1 (Angus Mail) 实现 IMAP + SMTP
- IMAP IDLE 模式监听新邮件
- SMTP 回复
- 支持附件 (MIME multipart)
- 安全检查: URL 验证避免 SSRF

---

## 3. 通用 Channel 实现模式

### 3.1 标准 Channel 模板

```java
package com.nanobot.channels.{name};

import com.nanobot.bus.MessageBus;
import com.nanobot.channels.core.BaseChannel;
import com.nanobot.channels.core.ChannelConfig;
import com.nanobot.bus.events.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * {ChannelName} channel implementation.
 */
public class {Name}Channel extends BaseChannel {

    private static final Logger logger = LoggerFactory.getLogger({Name}Channel.class);
    private static final int MAX_MESSAGE_LEN = 4000;

    public {Name}Channel({Name}Config config, MessageBus bus) {
        super("{name}", "{Display Name}", config, bus);
    }

    // ---- lifecycle ----

    @Override
    public void start() throws Exception {
        running = true;
        // 1. Authenticate with platform
        // 2. Start listener (long poll / webhook / WebSocket)
        // 3. On message received:
        //    handleMessage(senderId, chatId, text, mediaPaths, metadata, null, isDm);
        logger.info("{} channel started", displayName);
    }

    @Override
    public void stop() throws Exception {
        running = false;
        // 1. Disconnect from platform
        // 2. Clean up resources
        logger.info("{} channel stopped", displayName);
    }

    // ---- send ----

    @Override
    public void send(OutboundMessage msg) throws Exception {
        // Split long messages if necessary
        List<String> parts = splitMessage(msg.getContent(), MAX_MESSAGE_LEN);
        for (String part : parts) {
            // Send via platform-specific API
            platformSend(part, msg);
        }
    }

    // ---- streaming (optional override) ----

    @Override
    public void sendDelta(String chatId, String delta,
                           Map<String, Object> metadata) throws Exception {
        // Edit previous message, or accumulate + flush
    }

    @Override
    public void sendReasoningDelta(String chatId, String delta,
                                    Map<String, Object> metadata) throws Exception {
        // Platform-specific reasoning rendering (blockquote, context, subtext, etc.)
    }

    // ---- helpers ----

    private void platformSend(String text, OutboundMessage msg) {
        // Call platform SDK to send the message
    }
}
```

### 3.2 消息分割工具

大部分 channel 都有字符数限制，需要 `split_message` 工具方法：

```java
package com.nanobot.channels.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility for splitting long messages at paragraph boundaries.
 * Preserves markdown code blocks from being split mid-fence.
 */
public final class MessageSplitter {

    private MessageSplitter() {}

    /**
     * Split text into chunks no longer than maxLen characters.
     * Splits at paragraph breaks ("\n\n") when possible.
     * Preserves markdown code fences (```) from being split.
     */
    public static List<String> splitMessage(String text, int maxLen) {
        if (text.length() <= maxLen) {
            return List.of(text);
        }

        List<String> result = new ArrayList<>();
        String[] paragraphs = text.split("\n\n", -1);
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            if (current.length() + para.length() + 2 > maxLen && !current.isEmpty()) {
                result.add(current.toString().stripTrailing());
                current = new StringBuilder();
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(para);

            // If a single paragraph is too long, split it further
            while (current.length() > maxLen) {
                int splitAt = findSplitPoint(current.toString(), maxLen);
                result.add(current.substring(0, splitAt).stripTrailing());
                current = new StringBuilder(current.substring(splitAt).stripLeading());
            }
        }

        if (!current.isEmpty()) {
            result.add(current.toString().stripTrailing());
        }

        return result;
    }

    private static int findSplitPoint(String text, int maxLen) {
        // Prefer splitting at newline, then space, then character boundary
        int newlinePos = text.lastIndexOf('\n', maxLen);
        if (newlinePos > maxLen / 2) {
            return newlinePos + 1;
        }
        int spacePos = text.lastIndexOf(' ', maxLen);
        if (spacePos > maxLen / 2) {
            return spacePos + 1;
        }
        return maxLen;
    }
}
```

---

## 4. 每个 Channel 的特异性逻辑

| Channel | 流式策略 | 推理展示 | 消息限制 | 媒体限制 | 认证方式 |
|---------|---------|---------|---------|---------|---------|
| WebSocket | 浏览器端拼接 | italic bubble | 无限制 | 36MB total, 4 images | Token + Bearer |
| Telegram | 消息编辑 (edit) | `<blockquote expandable>` | 4096 chars | 50MB | Bot Token |
| Discord | 消息编辑 + 节流 200ms | embed/subtext | 2000 chars | 20MB (server), 25MB (DM) | Bot Token |
| Slack | 消息更新 (chat.update) | context block | 40,000 chars (blocks) | 1GB (via files.upload) | Bot Token + App Token |
| Feishu | 消息编辑 (patch) | 折叠块 | 30KB | 20MB | App ID + Secret |
| Weixin | 不支持 (一 shot) | N/A | ~2048 chars | 10MB image | QR code → token |
| WhatsApp | Node.js bridge 处理 | N/A | 4096 chars | 100MB (bridge) | bridge_token |
| DingTalk | Markdown 消息更新 | 灰色备注 | 20KB | 20MB (robot upload) | client_id + secret |
| Matrix | 消息编辑 | formatted_body 子文本 | 65KB | 由 homeserver 决定 | Access Token |
| QQ | 不支持 (一 shot) | N/A | 4500 chars | ~30MB (go-cqhttp) | go-cqhttp access token |
| Napcat | 消息编辑 (onebot) | N/A | 4500 chars | ~30MB | napcat token |
| MS Teams | 自适应卡片更新 | 小号字体 | 28KB (Activity) | 1MB | MS App ID + Password |
| Signal | 不支持 (一 shot) | N/A | ~5000 chars | 100MB (signal-cli) | signal-cli 已注册号码 |
| MoChat | 不支持 (一 shot) | N/A | ~2000 chars | 由平台决定 | API Key |
| WeCom | 被动回复 + 主动推送 | N/A | 2048 chars | 20MB | Token + AES Key |
| Email | 不支持 (一 shot) | 折叠引用 | 无限制 | 25MB (SMTP 惯例) | IMAP user+password |

---

## 5. 部分实现优先级

由于 17 个 channel 的工作量较大，建议分阶段实现：

**P0 (核心):**
1. WebSocket — WebUI 必须
2. Telegram — 最常用

**P1 (高需求):**
3. Discord
4. Slack
5. Feishu

**P2 (中等):**
6. Matrix
7. DingTalk
8. MS Teams
9. Email

**P3 (低优先级):**
10. WhatsApp (维持 Node.js bridge)
11. Signal
12. QQ / Napcat
13. Weixin / WeCom
14. MoChat

---

## 6. 文件清单

| 文件 | 说明 |
|------|------|
| `com/nanobot/channels/websocket/WebSocketConfig.java` | WebSocket 配置 (~120 行) |
| `com/nanobot/channels/websocket/WebSocketChannel.java` | WebSocket Channel 主类 (~800 行) |
| `com/nanobot/channels/websocket/TokenManager.java` | Token 签发与验证 (~60 行) |
| `com/nanobot/channels/websocket/MediaManager.java` | 媒体 base64 解码、存储与 URL 签名 (~150 行) |
| `com/nanobot/channels/websocket/TranscriptManager.java` | 会话转录记录 (~160 行) |
| `com/nanobot/channels/websocket/WebSocketServerConfig.java` | Spring WebSocket 配置 (~40 行) |
| `com/nanobot/webui/GatewayServices.java` | Gateway 服务聚合 (~25 行) |
| `com/nanobot/webui/WebUIWorkspaceController.java` | Workspace 控制器接口 (~20 行) |
| `com/nanobot/webui/WorkspaceScope.java` | Workspace scope 记录 (~25 行) |
| `com/nanobot/webui/WebUITranscriptionHandler.java` | 音频转录处理接口 (~15 行) |
| `com/nanobot/webui/WebuiForking.java` | WebUI fork 处理 (~40 行) |
| `com/nanobot/webui/CliAppMentionNormalizer.java` | CLI app 提及标准化 (~30 行) |
| `com/nanobot/webui/McpPresetMentionNormalizer.java` | MCP preset 提及标准化 (~30 行) |
| `com/nanobot/channels/telegram/TelegramConfig.java` | Telegram 配置 (~40 行) |
| `com/nanobot/channels/telegram/TelegramChannel.java` | Telegram Channel 实现 (~350 行) |
| `com/nanobot/channels/discord/DiscordConfig.java` | Discord 配置 (~40 行) |
| `com/nanobot/channels/discord/DiscordChannel.java` | Discord Channel 实现 (~300 行) |
| `com/nanobot/channels/slack/SlackConfig.java` | Slack 配置 (~50 行) |
| `com/nanobot/channels/slack/SlackChannel.java` | Slack Channel 实现 (~250 行) |
| `com/nanobot/channels/feishu/FeishuConfig.java` | Feishu 配置 (~60 行) |
| `com/nanobot/channels/feishu/FeishuChannel.java` | Feishu Channel 实现 (~400 行) |
| `com/nanobot/channels/matrix/MatrixConfig.java` | Matrix 配置 (~50 行) |
| `com/nanobot/channels/matrix/MatrixChannel.java` | Matrix Channel 实现 (~250 行) |
| `com/nanobot/channels/dingtalk/DingTalkConfig.java` | DingTalk 配置 (~40 行) |
| `com/nanobot/channels/dingtalk/DingTalkChannel.java` | DingTalk Channel 实现 (~200 行) |
| `com/nanobot/channels/msteams/MSTeamsConfig.java` | MS Teams 配置 (~40 行) |
| `com/nanobot/channels/msteams/MSTeamsChannel.java` | MS Teams Channel 实现 (~200 行) |
| `com/nanobot/channels/email/EmailConfig.java` | Email 配置 (~40 行) |
| `com/nanobot/channels/email/EmailChannel.java` | Email Channel 实现 (~120 行) |
| `com/nanobot/channels/whatsapp/WhatsAppConfig.java` | WhatsApp 配置 (~30 行) |
| `com/nanobot/channels/whatsapp/WhatsAppChannel.java` | WhatsApp Channel 实现 (~100 行) |
| `com/nanobot/channels/signal/SignalConfig.java` | Signal 配置 (~50 行) |
| `com/nanobot/channels/signal/SignalChannel.java` | Signal Channel 实现 (~150 行) |
| `com/nanobot/channels/qq/QQConfig.java` | QQ 配置 (~30 行) |
| `com/nanobot/channels/qq/QQChannel.java` | QQ Channel 实现 (~150 行) |
| `com/nanobot/channels/napcat/NapcatConfig.java` | Napcat 配置 (~30 行) |
| `com/nanobot/channels/napcat/NapcatChannel.java` | Napcat Channel 实现 (~100 行) |
| `com/nanobot/channels/weixin/WeixinConfig.java` | Weixin 配置 (~40 行) |
| `com/nanobot/channels/weixin/WeixinChannel.java` | Weixin Channel 实现 (~350 行) |
| `com/nanobot/channels/wecom/WecomConfig.java` | WeCom 配置 (~40 行) |
| `com/nanobot/channels/wecom/WecomChannel.java` | WeCom Channel 实现 (~100 行) |
| `com/nanobot/channels/mochat/MochatConfig.java` | MoChat 配置 (~40 行) |
| `com/nanobot/channels/mochat/MochatChannel.java` | MoChat Channel 实现 (~150 行) |
| `com/nanobot/channels/core/MessageSplitter.java` | 通用消息分割工具 (~50 行) |

---

## 7. 验证

### 7.1 WebSocket Channel 编译验证

预期 0 编译错误，确认以下关键项：

| 检查项 | 验证方法 |
|--------|---------|
| `WebSocketChannel` 实现 `WebSocketHandler` | `javac` 编译通过 |
| 泛型安全性（无 raw type 警告） | `-Xlint:unchecked` 无新增 |
| `@Override` 注解完整 | IDE 分析通过 |
| 并发安全 (`ConcurrentHashMap`, `List.copyOf`) | 审查确认无数据竞争 |
| 构造器注入依赖清晰 | Spring DI 可解析所有参数 |

### 7.2 行为对标验证

| 场景 | Python 预期 | Java 验证 |
|------|------------|----------|
| 连接建立 → ready 事件 | `{"event":"ready","chat_id":"<uuid>","client_id":"..."}` | 同 |
| 入站 message envelope | 媒体解析 → workspace scope → handleMessage | 同（增加 CLI/MCP 标准化） |
| 入站 new_chat envelope | 生成 chatId → scope_for_new_chat → persist → attach | 同 |
| 入站 fork_chat envelope | 委托 `handle_webui_fork_chat()` | 同 |
| 入站 attach envelope | 验证 chatId → attach → hydrate | 同 |
| 入站 set_workspace_scope | scope_for_set_request → persist → session_updated | 同 |
| 入站 transcribe_audio | 委托 `webui_transcription_event()` | 同 |
| 出站 send() | metadata-led events 分派 → media URL 签名 → fan-out | 同 |
| 出站 sendDelta() | delta 缓冲 → stream_end 时 flush + URL 重写 | 同 |
| 出站 sendReasoningDelta/sendReasoningEnd | 转录记录 + fan-out | 同 |
| 出站 sendFileEditEvents() | file_edit 事件 + 转录 | 同 |
| hydrateAfterSubscribe | goal_state 回放 + turn wall clock | 同 |
| publishRuntimeModelUpdate (static) | 广播 runtime_model_updated 至全部连接 | 同 |
| defaultConfig (static) | 返回默认 WebSocketConfig | 同 |

### 7.3 其他 16 个 Channel 验证

由于其他 channel 为设计笔记级别（非完整 Java 代码），验证方式为：

- Python 源码 → Java SDK 选型合理性审查
- 通信模式匹配度确认（Long Poll / Webhook / WebSocket / IMAP IDLE）
- 流式策略可行性分析（消息编辑 vs 一 shot）
- 消息长度限制与 split_message 适配

---

## 8. 复刻完整度

### 8.1 源码对标清单

| Python 文件 | 行数 | Java 实现 | 完整度 | 备注 |
|------------|------|----------|--------|------|
| `websocket.py` | 1,179 | `WebSocketChannel.java` + 辅助类 | **100%** | 方法级对齐，见 7.2 行为对标表 |
| `telegram.py` | 1,472 | `TelegramChannel.java` (设计笔记) | 设计完成 | 待实际编码 |
| `discord.py` | ~900 | `DiscordChannel.java` (设计笔记) | 设计完成 | 待实际编码 |
| `slack.py` | ~800 | `SlackChannel.java` (设计笔记) | 设计完成 | 待实际编码 |
| `feishu.py` | 1,984 | `FeishuChannel.java` (设计笔记) | 设计完成 | 待实际编码 |
| `weixin.py` | 1,586 | `WeixinChannel.java` (设计笔记) | 设计完成 | 待实际编码 |
| `wecom.py` | ~400 | `WecomChannel.java` (设计笔记) | 设计完成 | 待实际编码 |
| `whatsapp.py` | ~350 | `WhatsAppChannel.java` (设计笔记) | 设计完成 | 维持 Node.js bridge |
| `dingtalk.py` | ~650 | `DingTalkChannel.java` (设计笔记) | 设计完成 | 待实际编码 |
| `matrix.py` | ~700 | `MatrixChannel.java` (设计笔记) | 设计完成 | 待实际编码 |
| `email.py` | ~350 | `EmailChannel.java` (设计笔记) | 设计完成 | 待实际编码 |
| `signal.py` | ~600 | `SignalChannel.java` (设计笔记) | 设计完成 | 待实际编码 |
| `qq.py` | ~700 | `QQChannel.java` (设计笔记) | 设计完成 | 待实际编码 |
| `napcat.py` | ~300 | `NapcatChannel.java` (设计笔记) | 设计完成 | 待实际编码 |
| `mochat.py` | ~500 | `MochatChannel.java` (设计笔记) | 设计完成 | 待实际编码 |
| `msteams.py` | ~600 | `MSTeamsChannel.java` (设计笔记) | 设计完成 | 待实际编码 |
| `base.py` | 257 | `BaseChannel.java` (见 11-channels-core) | 100% | 已在上一文档完成 |

### 8.2 方法级差异说明

**WebSocket Channel (100%)：**

| 方法/功能 | 状态 | 说明 |
|----------|------|------|
| `hydrateAfterSubscribe()` | **已修复** | goal_state 回放 + turn wall clock |
| `handleNewChat()` | **已修复** | workspace scope 解析 + persist |
| `handleForkChat()` | **已修复** | 委托 WebuiForking |
| `handleSetWorkspaceScope()` | **已修复** | scope_for_set_request + session_updated |
| `handleTranscribeAudio()` | **已修复** | 委托 WebUITranscriptionHandler |
| `handleWsMessage()` | **已修复** | scope_for_message + CLI/MCP 标准化 + transcript |
| `send()` | **已修复** | media URL 签名 + tool_events + agent_ui + transcript |
| `sendDelta()` | **已修复** | stream_end 时 URL 重写 + transcript |
| `sendReasoningDelta()` | **已修复** | transcript 记录 |
| `sendReasoningEnd()` | **已修复** | transcript 记录 |
| `sendFileEditEvents()` | **新增** | file_edit 事件 + transcript |
| `sendTurnEnd()` | **已修复** | 增加 transcript 记录 |
| `publishRuntimeModelUpdate()` | **新增** | static 广播方法 |
| `defaultConfig()` | **新增** | static 返回默认配置 |
| `GatewayServices` | **新增** | 对标 Python GatewayServices |
| `workspaceScopeOrError()` | **新增** | 对标 Python _workspace_scope_or_error |
| `MediaManager.rewriteLocalMarkdownImages()` | **新增** | 本地图片路径 → 签名 URL |
| `MediaManager.signOrStageMediaPath()` | **新增** | 媒体路径签名为 HTTP 可访问 URL |
| `TranscriptManager.prepareAndAppend()` | **新增** | 完整的转录记录方法 |
| `TranscriptManager.appendUserMessage()` | **新增** | 用户消息转录 |
| `TranscriptManager.clientTurnMetadata()` | **新增** | turn_id 元数据提取 |

**其他 16 个 Channel：**

所有 channel 在文档中为设计笔记级别（Section 2），包含：
- Java SDK 选型与版本建议
- 通信模式匹配（Long Poll / Webhook / WebSocket / IMAP IDLE）
- 关键实现要点（认证、消息分割、流式策略、媒体限制）
- 特异性逻辑表（Section 4）
- 实现优先级（Section 5）

非 WebSocket channel 的完整 Java 代码将在实际编码阶段生成，设计文档层面已充分覆盖架构决策。

### 8.3 未修复项

无。WebSocket Channel 已实现 100% 方法级对标。

### 8.4 测试覆盖

| 测试类型 | 覆盖范围 | 优先级 |
|---------|---------|--------|
| WebSocket 连接生命周期测试 | ready → message → disconnect | P0 |
| 入站信封路由测试 | 6 种 type 的正确分发 | P0 |
| 媒体处理测试 | base64 解码、MIME 白名单、大小限制 | P0 |
| Token 认证测试 | 静态 token + 签发 token | P0 |
| 出站 fan-out 测试 | 多连接订阅、cleanup 后的正确过滤 | P0 |
| 流式缓冲测试 | delta 积累 → stream_end flush | P1 |
| Workspace scope 测试 | scopeForNewChat / scopeForSetRequest / scopeForMessage | P1 |
| Transcript 记录测试 | prepareAndAppend / appendUserMessage / turn_end 记录 | P1 |
| 其他 16 channel 集成测试 | 按 P0→P3 优先级分阶段 | P1-P3 |

### 8.5 编译/启动验证

- [ ] `WebSocketChannel.java` 编译通过（0 错误）
- [ ] `MediaManager.java` 编译通过
- [ ] `TranscriptManager.java` 编译通过
- [ ] Spring Boot `@EnableWebSocket` 配置正确加载
- [ ] WebSocket endpoint 在 `/` 路径接受连接
- [ ] ready/attached/error 事件正确发送
