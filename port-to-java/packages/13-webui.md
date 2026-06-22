# 13 — webui 包：WebUI 层（HTTP API + WebSocket 支撑后端）

**对标 Python：** `nanobot/webui/` 目录 (24 个文件, ~8,500 行)

## Python 源码分析

### 整体架构

WebUI 分为两个核心层：
1. **Gateway 服务层** — `gateway_services.py`、`gateway_tokens.py`、`media_gateway.py` 等，提供 DI 容器的显式依赖和共享服务。
2. **HTTP 路由层** — `ws_http.py` 是核心路由分发器，处理 `/webui/bootstrap`、session CRUD、settings、media、skills、workspaces、sidebar-state、静态 SPA 等所有非 WebSocket 的 HTTP 路由。

Python 侧 Gateway 使用 `websockets` 库的 HTTP 协议层（非标准 HTTP 服务器），所有路由通过自定义 `dispatch()` 方法分派。

### 关键文件清单

| Python 文件 | 行数 | 职责 |
|---|---|---|
| `gateway_services.py` | 82 | `GatewayServices` 冻结 dataclass DI 容器 + `build_gateway_services()` 工厂 |
| `gateway_tokens.py` | 83 | `GatewayTokenStore` — HMAC token 签发/验证/吊销管理 |
| `ws_http.py` | 603 | `GatewayHTTPHandler` — HTTP 路由分发（bootstrap/sessions/settings/media/misc/static） |
| `http_utils.py` | 152 | 共享 HTTP 工具函数（headers、query、Bearer 解析、path 规范化） |
| `websocket_logging.py` | 46 | WebSocket 握手噪音过滤日志 |
| `media_api.py` | ~400 | 媒体签名 URL 生成与上传 API |
| `media_gateway.py` | 93 | `WebUIMediaGateway` — 媒体 URL 签名/改写/augmentation 服务 |
| `settings_api.py` | ~800 | WebUI settings CRUD payload 构建（agent/model/provider/image-gen/network/token-usage） |
| `settings_routes.py` | ~400 | `WebUISettingsRouter` — HTTP 路由适配器，将 settings 请求映射到 settings_api |
| `transcript.py` | 1,864 | Conversation transcript 录制与 WebUI 线程响应构建 |
| `session_list_index.py` | 220 | 缓存的 session 列表索引（WebUI sidebar 优化） |
| `session_automations.py` | ~50 | Session 自动化 payload 构建（cron 集成） |
| `thread_disk.py` | ~80 | WebUI thread 磁盘持久化（保存/删除） |
| `forking.py` | ~100 | Chat fork（session 分叉）逻辑 |
| `file_preview.py` | ~120 | 文件预览 payload（图片/文本/二进制） |
| `sidebar_state.py` | ~60 | 读/写 WebUI sidebar 折叠状态持久化 |
| `skills_api.py` | 62 | 技能列表（安全脱敏：不泄露本地路径） |
| `workspaces.py` | 284 | `WebUIWorkspaceController` — project workspace 作用域 & 访问控制 |
| `mcp_presets_api.py` | ~150 | MCP 预设的 settings CRUD API |
| `mcp_presets_runtime.py` | ~100 | MCP 预设运行时管理 |
| `cli_apps_api.py` | ~100 | CLI apps settings/action API |
| `token_usage.py` | ~250 | Token usage 统计 payload 构建 |
| `transcription_ws.py` | ~80 | Transcription WebSocket 端点 |
| `version_check.py` | ~60 | 版本检查 API |

### GatewayServices 依赖注入模型

```python
@dataclass(frozen=True)
class GatewayServices:
    http: GatewayHTTPHandler
    tokens: GatewayTokenStore
    media: WebUIMediaGateway
    transcripts: WebUITranscriptRecorder
    workspaces: WebUIWorkspaceController
    session_manager: Any | None
    cron_service: Any | None
```

工厂函数 `build_gateway_services()` 构造所有依赖的显式图谱：
1. 创建 `GatewayTokenStore`（无依赖）
2. 创建 `WebUIMediaGateway(workspace_path, logger)` — 持有 32 字节 HMAC secret
3. 创建 `WebUITranscriptRecorder(log)` — 转录录制
4. 创建 `WebUIWorkspaceController(session_manager, default_workspace, ...)`
5. 创建 `GatewayHTTPHandler(config, session_manager, static_dist_path, bus, tokens, media, workspaces, ...)`
6. 在 `GatewayHTTPHandler.__init__` 中内联构造 `WebUISettingsRouter`

### GatewayHTTPHandler 路由表

```
POST  <token_issue_path>     → _handle_token_issue()     # 签发 WebSocket 连接 token
GET   /webui/bootstrap       → _handle_bootstrap()        # token + ws_url + model_name + runtime_surface + capabilities
*     /api/settings/*        → settings_routes.dispatch() # 所有 settings CRUD
GET   /api/sessions          → _handle_sessions_list()    # WebUI sidebar session 列表
GET   /api/sessions/{key}/messages    → _handle_session_messages()
GET   /api/sessions/{key}/webui-thread → _handle_webui_thread_get()
GET   /api/sessions/{key}/file-preview → _handle_file_preview()
GET   /api/sessions/{key}/automations  → _handle_session_automations()
POST  /api/sessions/{key}/delete       → _handle_session_delete()
GET   /api/media/{sig}/{payload}       → _handle_media_fetch()   # 签名媒体服务
GET   /api/commands          → _handle_commands()        # 内置命令面板
GET   /api/workspaces        → _handle_workspaces()      # project 作用域
GET   /api/webui/skills      → _handle_webui_skills()
GET   /api/webui/skills/{name} → _handle_webui_skill_detail()
GET   /api/webui/sidebar-state → _handle_webui_sidebar_state()
POST  /api/webui/sidebar-state/update → _handle_webui_sidebar_state_update()
GET   /*                     → _serve_static()           # SPA 静态资源 + index.html 回退
```

### GatewayTokenStore 状态管理

```python
@dataclass
class GatewayTokenStore:
    max_tokens: int = 10_000
    issued_tokens: dict[str, float] = {}    # token_value → expiry_timestamp
    api_tokens: dict[str, float] = {}       # 额外的 API token 集合

    check_api_token(request) → bool        # 验证 Bearer token 或 query param token
    can_issue(include_api_token=False) → bool
    issue_token(ttl_s, api_token=False) → str   # 格式: "nbwt_..." + secrets.token_urlsafe(32)
    take_issued_token_if_valid(token) → bool    # 一次性消费（WebSocket 连接用）
    clear() → None                              # 清空所有 issued token
```

token 格式: `nbwt_` + 43 字符 URL-safe base64。

### Media 签名服务

```python
class WebUIMediaGateway:
    - 持有 32 字节 HMAC secret (secrets.token_bytes(32))
    - serve_signed_media(sig, payload, request) → Response   # 签名验证 + 文件服务
    - sign_media_path(abs_path) → signed_url                  # 签名本地路径
    - sign_or_stage_media_path(path) → dict                   # 签名或暂存
    - rewrite_local_markdown_images(text, workspace_path) → str  # 重写本地 MD 图片链接
    - augment_media_urls(payload) → None                      # 批量附加签名 URL
    - augment_transcript_media(paths) → list[dict]            # 转录附件签名
```

签名格式: `/api/media/{hmac_sig}/{base64_encoded_path}`

### 静态 SPA 服务

Python 侧的处理逻辑：
1. 如果请求路径以 `/api/` 开头 → 永远返回 404，不回退到 SPA
2. 如果 `static_dist_path` 非空 → 尝试解析相对路径文件
3. 路径安全检查：`".." in rel.split("/")` 和 `candidate.relative_to(static_dist_path)` 防止目录穿越
4. 如果文件不存在 → 回退到 `index.html` (SPA 客户端路由)
5. `index.html` 设置 `Cache-Control: no-cache`；其他静态资源设置 `public, max-age=31536000, immutable`

---

## Java 实现方案

### 1. 整体策略

Python 的 `GatewayServices` dataclass DI 容器 → Java Spring `@Component` bean，由 Spring DI 自动装配。
Python 的 `GatewayHTTPHandler` 自定义 dispatch → Java Spring `@RestController` + `@RequestMapping`。
Python 的 `websockets.http11` 请求/响应 → Java Spring `HttpServletRequest` / `ResponseEntity`。

前端 `webui/` 目录完整复制到 `src/main/resources/static/`，零改动部署。

```
Python: GatewayServices (frozen dataclass) + build_gateway_services()
Java:   GatewayServices (record) + @Configuration @Bean factory methods

Python: GatewayHTTPHandler (自定义 dispatch)
Java:   GatewayHttpController (@RestController) + SettingsRoutesController + MediaController 等

Python: GatewayTokenStore (dataclass with dicts)
Java:   GatewayTokenStore (@Component, ConcurrentHashMap, ConcurrentLinkedDeque)
```

### 2. `GatewayServices.java` — DI 容器

```java
package com.nanobot.webui;

import java.nio.file.Path;
import java.util.Set;

import com.nanobot.cron.CronService;
import com.nanobot.session.SessionManager;

/**
 * Explicit dependencies shared by WebSocket transport and HTTP routes.
 * Frozen immutable record —对标 Python @dataclass(frozen=True) GatewayServices.
 */
public record GatewayServices(
    GatewayHttpController http,
    GatewayTokenStore tokens,
    WebUIMediaGateway media,
    WebUITranscriptRecorder transcripts,
    WebUIWorkspaceController workspaces,
    SessionManager sessionManager,
    CronService cronService
) {
}
```

工厂配置类：

```java
package com.nanobot.webui;

import java.nio.file.Path;
import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.nanobot.config.NanobotProperties;
import com.nanobot.cron.CronService;
import com.nanobot.session.SessionManager;
import com.nanobot.bus.MessageBus;

@Configuration
public class GatewayServicesConfiguration {

    @Bean
    public GatewayTokenStore gatewayTokenStore() {
        return new GatewayTokenStore(10_000);
    }

    @Bean
    public WebUIMediaGateway webUIMediaGateway(NanobotProperties properties) {
        return new WebUIMediaGateway(
            properties.agents().defaults().workspacePath(),
            properties
        );
    }

    @Bean
    public WebUITranscriptRecorder webUITranscriptRecorder() {
        return new WebUITranscriptRecorder();
    }

    @Bean
    public WebUIWorkspaceController webUIWorkspaceController(
            SessionManager sessionManager,
            NanobotProperties properties) {
        return new WebUIWorkspaceController(
            sessionManager,
            properties.agents().defaults().workspacePath(),
            properties.tools().restrictToWorkspace()
        );
    }

    @Bean
    public GatewayServices gatewayServices(
            MessageBus bus,
            SessionManager sessionManager,
            CronService cronService,
            NanobotProperties properties,
            GatewayTokenStore tokens,
            WebUIMediaGateway media,
            WebUIWorkspaceController workspaces) {

        WebUITranscriptRecorder transcripts = webUITranscriptRecorder();

        return new GatewayServices(
            null, // http 由 GatewayHttpController @Bean 单独注册
            tokens,
            media,
            transcripts,
            workspaces,
            sessionManager,
            cronService
        );
    }
}
```

### 3. `GatewayHttpController.java` — HTTP 路由分发

将所有 HTTP 路由转为 Spring `@RestController`。使用显式类型（无 `var`）。

```java
package com.nanobot.webui;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nanobot.bus.MessageBus;
import com.nanobot.command.builtin.BuiltinCommands;
import com.nanobot.config.NanobotProperties;
import com.nanobot.cron.CronService;
import com.nanobot.session.SessionManager;

@RestController
public class GatewayHttpController {

    private static final Pattern SESSION_KEY_PATTERN =
        Pattern.compile("^/api/sessions/([^/]+)/(.+)$");
    private static final Pattern MEDIA_PATH_PATTERN =
        Pattern.compile("^/api/media/([A-Za-z0-9_-]+)/([A-Za-z0-9_-]+)$");
    private static final Pattern SKILL_DETAIL_PATTERN =
        Pattern.compile("^/api/webui/skills/([^/]+)$");

    private static final Set<String> API_EXIT_COMMANDS = Set.of("exit", "quit", "/exit", "/quit", ":q");

    private final NanobotProperties config;
    private final SessionManager sessionManager;
    private final MessageBus bus;
    private final GatewayTokenStore tokens;
    private final WebUIMediaGateway media;
    private final WebUIWorkspaceController workspaces;
    private final Path skillsWorkspacePath;
    private final Set<String> disabledSkills;
    private final CronService cronService;
    private final String runtimeSurface;
    private final Map<String, Object> runtimeCapabilities;
    private final Path staticDistPath;

    public GatewayHttpController(
            NanobotProperties config,
            SessionManager sessionManager,
            MessageBus bus,
            GatewayTokenStore tokens,
            WebUIMediaGateway media,
            WebUIWorkspaceController workspaces,
            Path skillsWorkspacePath,
            Set<String> disabledSkills,
            CronService cronService,
            String runtimeSurface,
            Map<String, Object> runtimeCapabilities,
            Path staticDistPath) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.bus = bus;
        this.tokens = tokens;
        this.media = media;
        this.workspaces = workspaces;
        this.skillsWorkspacePath = skillsWorkspacePath;
        this.disabledSkills = disabledSkills;
        this.cronService = cronService;
        this.runtimeSurface = runtimeSurface;
        this.runtimeCapabilities = runtimeCapabilities;
        this.staticDistPath = staticDistPath;
    }

    // ---------------------------------------------------------------
    // Token Issue (对标 Python token_issue_path)
    // ---------------------------------------------------------------

    @PostMapping("${nanobot.gateway.token-issue-path:/webui/issue-token}")
    public ResponseEntity<Map<String, Object>> handleTokenIssue(
            HttpServletRequest request) {

        String secret = config.gateway().tokenIssueSecret().strip().isEmpty()
            ? config.gateway().token().strip()
            : config.gateway().tokenIssueSecret().strip();

        if (!secret.isEmpty()) {
            if (!issueRouteSecretMatches(request, secret)) {
                return ResponseEntity.status(401).build();
            }
        }

        if (!tokens.canIssue(false)) {
            return ResponseEntity.status(429)
                .body(Map.of("error", "too many outstanding tokens"));
        }

        String tokenValue = tokens.issueToken(config.gateway().tokenTtlSeconds(), false);
        int ttl = config.gateway().tokenTtlSeconds();
        return ResponseEntity.ok(Map.of("token", tokenValue, "expires_in", ttl));
    }

    // ---------------------------------------------------------------
    // Bootstrap
    // ---------------------------------------------------------------

    @GetMapping("/webui/bootstrap")
    public ResponseEntity<Map<String, Object>> handleBootstrap(
            HttpServletRequest request) {

        String secret = config.gateway().tokenIssueSecret().strip().isEmpty()
            ? config.gateway().token().strip()
            : config.gateway().tokenIssueSecret().strip();

        if (!secret.isEmpty()) {
            if (!issueRouteSecretMatches(request, secret)) {
                return ResponseEntity.status(401).build();
            }
        } else if (!HttpUtils.isLocalhost(request)) {
            return ResponseEntity.status(403)
                .body(Map.of("error", "bootstrap is localhost-only"));
        }

        if (!tokens.canIssue(true)) {
            return ResponseEntity.status(429)
                .body(Map.of("error", "too many outstanding tokens"));
        }

        String token = tokens.issueToken(config.gateway().tokenTtlSeconds(), true);
        int expiresIn = config.gateway().tokenTtlSeconds();
        String wsUrl = bootstrapWsUrl(request);
        String wsPath = HttpUtils.normalizeConfigPath(config.gateway().path());
        String modelName = resolveBootstrapModelName();
        Map<String, Object> capabilities = runtimeCapabilities != null
            ? runtimeCapabilities
            : Map.of();

        Map<String, Object> body = Map.of(
            "token", token,
            "ws_path", wsPath,
            "ws_url", wsUrl,
            "expires_in", expiresIn,
            "model_name", modelName,
            "runtime_surface", runtimeSurface,
            "runtime_capabilities", capabilities
        );

        return ResponseEntity.ok(body);
    }

    private String bootstrapWsUrl(HttpServletRequest request) {
        String host = HttpUtils.safeHostHeader(
            HttpUtils.caseInsensitiveHeader(request, "Host"));
        if (host.isEmpty()) {
            host = HttpUtils.hostForUrl(
                config.gateway().host(), config.gateway().port());
        }
        String proto = HttpUtils.caseInsensitiveHeader(request, "X-Forwarded-Proto");
        if (!proto.isEmpty()) {
            String[] parts = proto.split(",", 2);
            proto = parts[0].strip().toLowerCase();
        }
        boolean secure = proto.equals("https") || proto.equals("wss")
            || !config.gateway().sslCertfile().strip().isEmpty();
        String scheme = secure ? "wss" : "ws";
        String path = HttpUtils.normalizeConfigPath(config.gateway().path());
        return scheme + "://" + host + path;
    }

    // ---------------------------------------------------------------
    // Session Routes
    // ---------------------------------------------------------------

    @GetMapping("/api/sessions")
    public ResponseEntity<Map<String, Object>> handleSessionList(
            HttpServletRequest request) {
        if (!tokens.checkApiToken(request)) {
            return ResponseEntity.status(401).build();
        }
        if (sessionManager == null) {
            return ResponseEntity.status(503)
                .body(Map.of("error", "session manager unavailable"));
        }
        List<Map<String, Object>> sessions = SessionListIndex
            .listWebuiSessions(sessionManager, workspaces);
        return ResponseEntity.ok(Map.of("sessions", sessions));
    }

    @GetMapping("/api/sessions/{key}/messages")
    public ResponseEntity<?> handleSessionMessages(
            @PathVariable String key,
            HttpServletRequest request) {
        if (!tokens.checkApiToken(request)) {
            return ResponseEntity.status(401).build();
        }
        if (sessionManager == null) {
            return ResponseEntity.status(503)
                .body(Map.of("error", "session manager unavailable"));
        }
        String decodedKey = TokenUtils.decodeApiKey(key);
        if (decodedKey == null) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "invalid session key"));
        }
        if (!TokenUtils.isWebsocketChannelSessionKey(decodedKey)) {
            return ResponseEntity.status(404)
                .body(Map.of("error", "session not found"));
        }
        Map<String, Object> data = sessionManager.readSessionFile(decodedKey);
        if (data == null) {
            return ResponseEntity.status(404)
                .body(Map.of("error", "session not found"));
        }
        media.augmentMediaUrls(data);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/api/sessions/{key}/webui-thread")
    public ResponseEntity<?> handleWebuiThread(
            @PathVariable String key,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "direction", required = false) String direction,
            @RequestParam(name = "before", required = false) String before,
            HttpServletRequest request) {
        // 对标 Python _handle_webui_thread_get()
        if (!tokens.checkApiToken(request)) {
            return ResponseEntity.status(401).build();
        }
        String decodedKey = TokenUtils.decodeApiKey(key);
        if (decodedKey == null) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "invalid session key"));
        }
        if (!TokenUtils.isWebsocketChannelSessionKey(decodedKey)) {
            return ResponseEntity.status(404)
                .body(Map.of("error", "session not found"));
        }
        // ... 委托给 WebUITranscriptRecorder.buildWebuiThreadResponse()
        Map<String, Object> data = WebUITranscriptRecorder.buildWebuiThreadResponse(
            decodedKey, media, workspaces,
            sessionManager, limit, direction, before
        );
        if (data == null) {
            return ResponseEntity.status(404)
                .body(Map.of("error", "webui thread not found"));
        }
        return ResponseEntity.ok(data);
    }

    @GetMapping("/api/sessions/{key}/file-preview")
    public ResponseEntity<?> handleFilePreview(
            @PathVariable String key,
            @RequestParam("path") String path,
            HttpServletRequest request) {
        // 对标 Python _handle_file_preview()
        if (!tokens.checkApiToken(request)) {
            return ResponseEntity.status(401).build();
        }
        String decodedKey = TokenUtils.decodeApiKey(key);
        if (decodedKey == null || !TokenUtils.isWebsocketChannelSessionKey(decodedKey)) {
            return ResponseEntity.status(404).build();
        }
        Map<String, Object> payload = FilePreview.filePreviewPayload(
            path, workspaces.scopeForSessionKey(decodedKey));
        return ResponseEntity.ok(payload);
    }

    @GetMapping("/api/sessions/{key}/automations")
    public ResponseEntity<?> handleSessionAutomations(
            @PathVariable String key,
            HttpServletRequest request) {
        if (!tokens.checkApiToken(request)) {
            return ResponseEntity.status(401).build();
        }
        String decodedKey = TokenUtils.decodeApiKey(key);
        if (decodedKey == null || !TokenUtils.isWebsocketChannelSessionKey(decodedKey)) {
            return ResponseEntity.status(404).build();
        }
        Map<String, Object> payload = SessionAutomations
            .sessionAutomationsPayload(cronService, decodedKey);
        return ResponseEntity.ok(payload);
    }

    @PostMapping("/api/sessions/{key}/delete")
    public ResponseEntity<?> handleSessionDelete(
            @PathVariable String key,
            HttpServletRequest request) {
        if (!tokens.checkApiToken(request)) {
            return ResponseEntity.status(401).build();
        }
        if (sessionManager == null) {
            return ResponseEntity.status(503).build();
        }
        String decodedKey = TokenUtils.decodeApiKey(key);
        if (decodedKey == null || !TokenUtils.isWebsocketChannelSessionKey(decodedKey)) {
            return ResponseEntity.status(404).build();
        }
        boolean deleted = sessionManager.deleteSession(decodedKey);
        ThreadDisk.deleteWebuiThread(decodedKey);
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    // ---------------------------------------------------------------
    // Media Routes
    // ---------------------------------------------------------------

    @GetMapping("/api/media/{sig}/{payload}")
    public ResponseEntity<?> handleMediaFetch(
            @PathVariable String sig,
            @PathVariable String payload,
            HttpServletRequest request) {
        return media.serveSignedMedia(sig, payload, request);
    }

    // ---------------------------------------------------------------
    // Misc Routes
    // ---------------------------------------------------------------

    @GetMapping("/api/commands")
    public ResponseEntity<Map<String, Object>> handleCommands(
            HttpServletRequest request) {
        if (!tokens.checkApiToken(request)) {
            return ResponseEntity.status(401).build();
        }
        List<Map<String, String>> commands = BuiltinCommands.builtinCommandPalette();
        return ResponseEntity.ok(Map.of("commands", commands));
    }

    @GetMapping("/api/workspaces")
    public ResponseEntity<Map<String, Object>> handleWorkspaces(
            HttpServletRequest request) {
        if (!tokens.checkApiToken(request)) {
            return ResponseEntity.status(401).build();
        }
        boolean controlsAvailable = "native".equals(runtimeSurface)
            || HttpUtils.isLocalhost(request);
        return ResponseEntity.ok(
            workspaces.payload(controlsAvailable));
    }

    @GetMapping("/api/webui/skills")
    public ResponseEntity<Map<String, Object>> handleWebuiSkills(
            HttpServletRequest request) {
        if (!tokens.checkApiToken(request)) {
            return ResponseEntity.status(401).build();
        }
        Map<String, Object> payload = SkillsApi.webuiSkillsPayload(
            skillsWorkspacePath, disabledSkills);
        return ResponseEntity.ok(payload);
    }

    @GetMapping("/api/webui/skills/{name}")
    public ResponseEntity<?> handleWebuiSkillDetail(
            @PathVariable String name,
            HttpServletRequest request) {
        if (!tokens.checkApiToken(request)) {
            return ResponseEntity.status(401).build();
        }
        String decoded = java.net.URLDecoder.decode(name,
            java.nio.charset.StandardCharsets.UTF_8);
        if (decoded.isEmpty() || decoded.contains("/") || decoded.contains("\\")) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "invalid skill name"));
        }
        Map<String, Object> payload = SkillsApi.webuiSkillDetailPayload(
            skillsWorkspacePath, decoded, disabledSkills);
        if (payload == null) {
            return ResponseEntity.status(404)
                .body(Map.of("error", "skill not found"));
        }
        return ResponseEntity.ok(payload);
    }

    @GetMapping("/api/webui/sidebar-state")
    public ResponseEntity<?> handleWebuiSidebarState(
            HttpServletRequest request) {
        if (!tokens.checkApiToken(request)) {
            return ResponseEntity.status(401).build();
        }
        Map<String, Object> state = SidebarState.readWebuiSidebarState();
        return ResponseEntity.ok(state);
    }

    @PostMapping("/api/webui/sidebar-state/update")
    public ResponseEntity<?> handleWebuiSidebarStateUpdate(
            @RequestParam("state") String rawState,
            HttpServletRequest request) {
        if (!tokens.checkApiToken(request)) {
            return ResponseEntity.status(401).build();
        }
        Map<?, ?> decoded;
        try {
            decoded = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(rawState, Map.class);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "state must be JSON"));
        }
        if (!(decoded instanceof Map)) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "state must be an object"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> stateMap = (Map<String, Object>) decoded;
        try {
            Map<String, Object> result = SidebarState.writeWebuiSidebarState(stateMap);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500)
                .body(Map.of("error", "failed to write sidebar state"));
        }
    }

    // ---------------------------------------------------------------
    // 辅助方法
    // ---------------------------------------------------------------

    private String resolveBootstrapModelName() {
        // 对标 Python _resolve_bootstrap_model_name()
        String model = config.agents().defaults().model();
        return model != null ? model.strip() : "";
    }

    private boolean issueRouteSecretMatches(HttpServletRequest request, String secret) {
        return HttpUtils.issueRouteSecretMatches(request, secret);
    }
}
```

### 4. `GatewayTokenStore.java` — HMAC Token 管理

```java
package com.nanobot.webui;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Own short-lived WebSocket and WebUI API tokens for one gateway process.
 * 对标 Python GatewayTokenStore — single-process, no Redis needed.
 */
public class GatewayTokenStore {

    private static final String TOKEN_PREFIX = "nbwt_";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final int maxTokens;
    private final ConcurrentMap<String, Long> issuedTokens;  // token → expiry epoch millis
    private final ConcurrentMap<String, Long> apiTokens;     // token → expiry epoch millis

    public GatewayTokenStore(int maxTokens) {
        this.maxTokens = maxTokens;
        this.issuedTokens = new ConcurrentHashMap<>();
        this.apiTokens = new ConcurrentHashMap<>();
    }

    /**
     * 对标 Python check_api_token().
     * Validates Bearer token from Authorization header or ?token= query param.
     */
    public boolean checkApiToken(HttpServletRequest request) {
        purgeExpiredApiTokens();
        String token = HttpUtils.bearerToken(request);
        if (token == null || token.isEmpty()) {
            token = request.getParameter("token");
        }
        if (token == null || token.isEmpty()) {
            return false;
        }
        Long expiry = apiTokens.get(token);
        if (expiry == null || System.currentTimeMillis() > expiry) {
            apiTokens.remove(token);
            return false;
        }
        return true;
    }

    /**
     * 对标 Python can_issue().
     */
    public boolean canIssue(boolean includeApiToken) {
        purgeExpiredIssuedTokens();
        purgeExpiredApiTokens();
        if (issuedTokens.size() >= maxTokens) {
            return false;
        }
        if (includeApiToken && apiTokens.size() >= maxTokens) {
            return false;
        }
        return true;
    }

    /**
     * 对标 Python issue_token().
     * Format: "nbwt_" + 43 chars URL-safe base64 (32 random bytes).
     */
    public String issueToken(int ttlSeconds, boolean apiToken) {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        String tokenValue = TOKEN_PREFIX
            + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        long expiry = System.currentTimeMillis() + (ttlSeconds * 1000L);
        issuedTokens.put(tokenValue, expiry);
        if (apiToken) {
            apiTokens.put(tokenValue, expiry);
        }
        return tokenValue;
    }

    /**
     * 对标 Python take_issued_token_if_valid().
     * One-shot consumption for WebSocket connection establishment.
     */
    public boolean takeIssuedTokenIfValid(String tokenValue) {
        if (tokenValue == null || tokenValue.isEmpty()) {
            return false;
        }
        purgeExpiredIssuedTokens();
        Long expiry = issuedTokens.remove(tokenValue);
        if (expiry == null) {
            return false;
        }
        if (System.currentTimeMillis() > expiry) {
            return false;
        }
        return true;
    }

    /**
     * 对标 Python clear().
     */
    public void clear() {
        issuedTokens.clear();
        apiTokens.clear();
    }

    // --- Internal purge methods ---

    private void purgeExpiredApiTokens() {
        long now = System.currentTimeMillis();
        Iterator<ConcurrentMap.Entry<String, Long>> iter = apiTokens.entrySet().iterator();
        while (iter.hasNext()) {
            ConcurrentMap.Entry<String, Long> entry = iter.next();
            if (now > entry.getValue()) {
                iter.remove();
            }
        }
    }

    private void purgeExpiredIssuedTokens() {
        long now = System.currentTimeMillis();
        Iterator<ConcurrentMap.Entry<String, Long>> iter = issuedTokens.entrySet().iterator();
        while (iter.hasNext()) {
            ConcurrentMap.Entry<String, Long> entry = iter.next();
            if (now > entry.getValue()) {
                iter.remove();
            }
        }
    }
}
```

### 5. `WebSocketLogging.java` — WebSocket 会话日志

```java
package com.nanobot.webui;

import java.util.logging.Filter;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Logging helpers for the WebUI WebSocket server surface.
 * 对标 Python websocket_logging.py.
 */
public final class WebSocketLogging {

    private WebSocketLogging() {}

    public static final String OPENING_HANDSHAKE_FAILED_MESSAGE = "opening handshake failed";

    /**
     * 对标 Python WebSocketHandshakeNoiseFilter.
     * Suppresses restart-time handshake failures where the browser already disconnected.
     */
    public static class WebSocketHandshakeNoiseFilter implements Filter {
        @Override
        public boolean isLoggable(LogRecord record) {
            if (!OPENING_HANDSHAKE_FAILED_MESSAGE.equals(record.getMessage())) {
                return true;
            }
            Throwable thrown = record.getThrown();
            return !exceptionChainHasDisconnect(thrown);
        }

        private boolean exceptionChainHasDisconnect(Throwable exc) {
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            while (exc != null) {
                int ident = System.identityHashCode(exc);
                if (seen.contains(ident)) {
                    return false;
                }
                seen.add(ident);
                if (isDisconnectException(exc)) {
                    return true;
                }
                exc = exc.getCause();
            }
            return false;
        }

        private boolean isDisconnectException(Throwable exc) {
            String className = exc.getClass().getName();
            return className.contains("BrokenPipe")
                || className.contains("ConnectionAborted")
                || className.contains("ConnectionReset")
                || className.contains("ConnectionClosed");
        }
    }

    /**
     * 对标 Python websockets_server_logger().
     */
    public static Logger configureWebsocketsServerLogger() {
        Logger wsLogger = Logger.getLogger("websockets.server");
        java.util.List<Filter> filters = java.util.Arrays.asList(
            wsLogger.getFilter() != null ? new Filter[]{wsLogger.getFilter()} : new Filter[0]
        );
        boolean hasFilter = false;
        for (Filter f : filters) {
            if (f instanceof WebSocketHandshakeNoiseFilter) {
                hasFilter = true;
                break;
            }
        }
        if (!hasFilter) {
            wsLogger.setFilter(new WebSocketHandshakeNoiseFilter());
        }
        return wsLogger;
    }
}
```

### 6. `HttpUtils.java` — HTTP 工具函数

```java
package com.nanobot.webui;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Shared HTTP helpers for the embedded WebUI gateway.
 * 对标 Python http_utils.py.
 */
public final class HttpUtils {

    private HttpUtils() {}

    private static final Pattern SAFE_IPV6_PATTERN =
        Pattern.compile("\\[[0-9A-Fa-f:.]+\\](?::\\d{1,5})?");
    private static final Pattern SAFE_HOSTNAME_PATTERN =
        Pattern.compile("[A-Za-z0-9.-]+(?::\\d{1,5})?");

    // --- Path utilities ---

    public static String stripTrailingSlash(String path) {
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path.isEmpty() ? "/" : path;
    }

    public static String normalizeConfigPath(String path) {
        return stripTrailingSlash(path);
    }

    // --- Header utilities ---

    /**
     * 对标 Python case_insensitive_header().
     */
    public static String caseInsensitiveHeader(HttpServletRequest request, String key) {
        String value = request.getHeader(key);
        if (value == null) {
            value = request.getHeader(key.toLowerCase());
        }
        return value != null ? value.strip() : "";
    }

    /**
     * 对标 Python safe_host_header().
     */
    public static String safeHostHeader(String value) {
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            return "";
        }
        if (SAFE_IPV6_PATTERN.matcher(stripped).matches()) {
            return stripped;
        }
        if (SAFE_HOSTNAME_PATTERN.matcher(stripped).matches()) {
            return stripped;
        }
        return "";
    }

    /**
     * 对标 Python host_for_url().
     */
    public static String hostForUrl(String host, int port) {
        String h = host.strip();
        if ("0.0.0.0".equals(h) || "::".equals(h)) {
            h = "127.0.0.1";
        }
        if (h.contains(":") && !h.startsWith("[")) {
            h = "[" + h + "]";
        }
        return h + ":" + port;
    }

    // --- Auth utilities ---

    /**
     * 对标 Python bearer_token().
     */
    public static String bearerToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null) {
            auth = request.getHeader("authorization");
        }
        if (auth != null) {
            String lower = auth.toLowerCase();
            if (lower.startsWith("bearer ")) {
                String token = auth.substring(7).strip();
                return token.isEmpty() ? null : token;
            }
        }
        return null;
    }

    /**
     * 对标 Python issue_route_secret_matches().
     * COMPAT: 前端的 Authorization header 或 X-Nanobot-Auth header 携带 issue secret
     */
    public static boolean issueRouteSecretMatches(HttpServletRequest request, String secret) {
        if (secret == null || secret.isEmpty()) {
            return true;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null) {
            authorization = request.getHeader("authorization");
        }
        if (authorization != null) {
            String lower = authorization.toLowerCase();
            if (lower.startsWith("bearer ")) {
                String supplied = authorization.substring(7).strip();
                return hmacCompareDigest(supplied, secret);
            }
        }
        String nanobotAuth = request.getHeader("X-Nanobot-Auth");
        if (nanobotAuth == null) {
            nanobotAuth = request.getHeader("x-nanobot-auth");
        }
        if (nanobotAuth == null) {
            return false;
        }
        return hmacCompareDigest(nanobotAuth.strip(), secret);
    }

    // --- Localhost check ---

    /**
     * 对标 Python is_localhost().
     */
    public static boolean isLocalhost(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr == null) {
            return false;
        }
        // Handle IPv4-mapped IPv6
        if (remoteAddr.startsWith("::ffff:")) {
            remoteAddr = remoteAddr.substring(7);
        }
        return "127.0.0.1".equals(remoteAddr)
            || "::1".equals(remoteAddr)
            || "localhost".equals(remoteAddr);
    }

    // --- Crypto ---

    /**
     * Constant-time HMAC comparison. 对标 Python hmac.compare_digest().
     */
    public static boolean hmacCompareDigest(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    // --- Query parsing ---

    /**
     * Parse query string from request URL into a Map<String, List<String>>.
     * 对标 Python parse_query().
     */
    public static Map<String, List<String>> parseQuery(HttpServletRequest request) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        String queryString = request.getQueryString();
        if (queryString == null || queryString.isEmpty()) {
            return result;
        }
        String[] pairs = queryString.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            String key, value;
            if (idx >= 0) {
                key = java.net.URLDecoder.decode(pair.substring(0, idx),
                    StandardCharsets.UTF_8);
                value = java.net.URLDecoder.decode(pair.substring(idx + 1),
                    StandardCharsets.UTF_8);
            } else {
                key = java.net.URLDecoder.decode(pair, StandardCharsets.UTF_8);
                value = "";
            }
            result.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(value);
        }
        return result;
    }

    /**
     * 对标 Python query_first().
     */
    public static String queryFirst(Map<String, List<String>> query, String key) {
        List<String> values = query.get(key);
        return (values != null && !values.isEmpty()) ? values.get(0) : null;
    }
}
```

### 7. 静态 SPA 服务策略

Spring Boot 自动处理 `classpath:/static/` 下的静态资源。需要额外配置 SPA fallback：

```java
package com.nanobot.webui;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * SPA fallback: 任何非 /api/ 的 404 请求回退到 index.html.
 * 对标 Python ws_http.py _serve_static() 的 SPA fallback 逻辑。
 */
@Configuration
public class SpaFallbackConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Primary static resources
        registry.addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
            .resourceChain(true)
            .addResolver(new PathResourceResolver() {
                @Override
                protected Resource getResource(String resourcePath,
                        Resource location) throws IOException {
                    Resource requestedResource = location.createRelative(resourcePath);

                    // Never serve SPA for /api/ routes (COMPAT: Python 行为)
                    if (resourcePath.startsWith("api/")) {
                        return null;
                    }

                    // 安全检查: 防御目录穿越 (对标 Python ".." check)
                    if (resourcePath.contains("..")) {
                        return null;
                    }

                    // File exists → serve it
                    if (requestedResource.exists() && requestedResource.isReadable()) {
                        return requestedResource;
                    }

                    // SPA fallback → index.html with no-cache
                    Resource fallback = location.createRelative("index.html");
                    if (fallback.exists() && fallback.isReadable()) {
                        return fallback;
                    }

                    return null;
                }
            });
    }
}
```

Cache-Control 策略（对标 Python）:
- `index.html` → `no-cache`（SPA shell 不缓存）
- 其他静态资源（JS/CSS/assets）→ `public, max-age=31536000, immutable`（hash 文件名，可永久缓存）

通过 `application.yml` 配置：

```yaml
spring:
  web:
    resources:
      cache:
        cachecontrol:
          max-age: 31536000
          cache-public: true
          cache-immutable: true
      chain:
        cache: true
        strategy:
          content:
            enabled: true
            paths: "/**"
```

### 8. `TokenUtils.java` — Token 辅助

```java
package com.nanobot.webui;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Token utility methods shared by HTTP routes and WebSocket channel.
 */
public final class TokenUtils {

    private TokenUtils() {}

    private static final Pattern API_KEY_RE = Pattern.compile("^[A-Za-z0-9_:.-]{1,128}$");

    /**
     * 对标 Python _decode_api_key().
     */
    public static String decodeApiKey(String rawKey) {
        String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
        if (!API_KEY_RE.matcher(key).matches()) {
            return null;
        }
        return key;
    }

    /**
     * 对标 Python _is_websocket_channel_session_key().
     */
    public static boolean isWebsocketChannelSessionKey(String key) {
        return key.startsWith("websocket:");
    }
}
```

### 9. `SidebarState.java` — Sidebar 持久化

```java
package com.nanobot.webui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.nanobot.config.AppPaths;

/**
 * Persisted WebUI sidebar workspace state — UI-only metadata scoped to the
 * active nanobot instance data directory.
 * 对标 Python sidebar_state.py.
 */
public final class SidebarState {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int WEBUI_SIDEBAR_STATE_SCHEMA_VERSION = 1;
    private static final int MAX_STATE_FILE_BYTES = 256 * 1024;
    private static final int MAX_LIST_ITEMS = 2_000;
    private static final int MAX_MAP_ITEMS = 2_000;
    private static final int MAX_KEY_LEN = 512;
    private static final int MAX_TITLE_LEN = 160;
    private static final int MAX_TAG_LEN = 40;
    private static final Set<String> ALLOWED_DENSITIES = Set.of("comfortable", "compact");
    private static final Set<String> ALLOWED_SORTS =
        Set.of("updated_desc", "created_desc", "title_asc");

    private SidebarState() {}

    // 对标 Python webui_sidebar_state_path()
    private static Path sidebarStatePath() {
        return AppPaths.webuiDir().resolve("sidebar-state.json");
    }

    // 对标 Python default_webui_sidebar_state()
    static Map<String, Object> defaultState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("schema_version", WEBUI_SIDEBAR_STATE_SCHEMA_VERSION);
        state.put("pinned_keys", List.of());
        state.put("archived_keys", List.of());
        state.put("title_overrides", Map.of());
        state.put("project_name_overrides", Map.of());
        state.put("tags_by_key", Map.of());
        state.put("collapsed_groups", Map.of());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("density", "comfortable");
        view.put("show_previews", false);
        view.put("show_timestamps", false);
        view.put("show_archived", false);
        view.put("sort", "updated_desc");
        state.put("view", view);
        state.put("updated_at", null);
        return state;
    }

    // 对标 Python normalize_webui_sidebar_state()
    @SuppressWarnings("unchecked")
    static Map<String, Object> normalize(Map<String, Object> raw) {
        Map<String, Object> state = defaultState();
        if (raw == null) return state;
        state.put("pinned_keys",
            cleanStringList(raw.get("pinned_keys"), MAX_KEY_LEN));
        state.put("archived_keys",
            cleanStringList(raw.get("archived_keys"), MAX_KEY_LEN));
        state.put("title_overrides",
            cleanTitleOverrides(raw.get("title_overrides")));
        state.put("project_name_overrides",
            cleanTitleOverrides(raw.get("project_name_overrides")));
        state.put("tags_by_key",
            cleanTagsByKey(raw.get("tags_by_key")));
        state.put("collapsed_groups",
            cleanBoolMap(raw.get("collapsed_groups")));
        state.put("view", cleanView(raw.get("view")));
        Object updatedAt = raw.get("updated_at");
        state.put("updated_at", updatedAt instanceof String s ? s : null);
        return state;
    }

    // 对标 Python _clean_string()
    static String cleanString(Object value, int maxLen) {
        if (!(value instanceof String s)) return null;
        String cleaned = s.strip();
        return cleaned.isEmpty() ? null : cleaned.substring(0, Math.min(cleaned.length(), maxLen));
    }

    // 对标 Python _clean_string_list()
    @SuppressWarnings("unchecked")
    static List<String> cleanStringList(Object value, int maxLen) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int limit = Math.min(list.size(), MAX_LIST_ITEMS);
        for (int i = 0; i < limit; i++) {
            String cleaned = cleanString(list.get(i), maxLen);
            if (cleaned == null || seen.contains(cleaned)) continue;
            seen.add(cleaned);
            out.add(cleaned);
        }
        return Collections.unmodifiableList(out);
    }

    // 对标 Python _clean_bool_map()
    @SuppressWarnings("unchecked")
    static Map<String, Boolean> cleanBoolMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Boolean> out = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (count++ >= MAX_MAP_ITEMS) break;
            String key = cleanString(e.getKey(), MAX_KEY_LEN);
            if (key == null) continue;
            out.put(key, Boolean.TRUE.equals(e.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }

    // 对标 Python _clean_title_overrides()
    @SuppressWarnings("unchecked")
    static Map<String, String> cleanTitleOverrides(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (count++ >= MAX_MAP_ITEMS) break;
            String key = cleanString(e.getKey(), MAX_KEY_LEN);
            String title = cleanString(e.getValue(), MAX_TITLE_LEN);
            if (key == null || title == null) continue;
            out.put(key, title);
        }
        return Collections.unmodifiableMap(out);
    }

    // 对标 Python _clean_tags_by_key()
    @SuppressWarnings("unchecked")
    static Map<String, List<String>> cleanTagsByKey(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, List<String>> out = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (count++ >= MAX_MAP_ITEMS) break;
            String key = cleanString(e.getKey(), MAX_KEY_LEN);
            if (key == null) continue;
            List<String> tags = cleanStringList(e.getValue(), MAX_TAG_LEN);
            if (tags.size() > 12) tags = tags.subList(0, 12);
            if (!tags.isEmpty()) out.put(key, tags);
        }
        return Collections.unmodifiableMap(out);
    }

    // 对标 Python _clean_view()
    @SuppressWarnings("unchecked")
    static Map<String, Object> cleanView(Object value) {
        Map<String, Object> defaultView =
            (Map<String, Object>) defaultState().get("view");
        if (!(value instanceof Map<?, ?> raw)) return defaultView;
        Map<String, Object> out = new LinkedHashMap<>();
        Object density = raw.get("density");
        out.put("density",
            ALLOWED_DENSITIES.contains(density) ? density : defaultView.get("density"));
        out.put("show_previews",
            raw.get("show_previews") instanceof Boolean b ? b : defaultView.get("show_previews"));
        out.put("show_timestamps",
            raw.get("show_timestamps") instanceof Boolean b ? b : defaultView.get("show_timestamps"));
        out.put("show_archived",
            raw.get("show_archived") instanceof Boolean b ? b : defaultView.get("show_archived"));
        Object sort = raw.get("sort");
        out.put("sort",
            ALLOWED_SORTS.contains(sort) ? sort : defaultView.get("sort"));
        return out;
    }

    // 对标 Python read_webui_sidebar_state()
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readWebuiSidebarState() {
        Path path = sidebarStatePath();
        if (!Files.isRegularFile(path)) {
            return defaultState();
        }
        try {
            if (Files.size(path) > MAX_STATE_FILE_BYTES) {
                return defaultState();
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            Map<String, Object> raw = MAPPER.readValue(content,
                new TypeReference<LinkedHashMap<String, Object>>() {});
            return normalize(raw);
        } catch (IOException e) {
            return defaultState();
        }
    }

    // 对标 Python write_webui_sidebar_state()
    @SuppressWarnings("unchecked")
    public static Map<String, Object> writeWebuiSidebarState(Map<String, Object> raw) {
        Map<String, Object> state = normalize(raw);
        state.put("updated_at",
            DateTimeFormatter.ISO_INSTANT.format(Instant.now().atZone(ZoneOffset.UTC)));

        Path path = sidebarStatePath();
        Path tmp = path.resolveSibling("sidebar-state.json.tmp");
        try {
            String encoded = MAPPER.writeValueAsString(state);
            if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_STATE_FILE_BYTES) {
                throw new IllegalArgumentException("sidebar state is too large");
            }
            Files.createDirectories(path.getParent());
            Files.writeString(tmp, encoded, StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("failed to write sidebar state", e);
        }
        return state;
    }
}
```

### 10. `FilePreview.java` — 文件预览 API

```java
package com.nanobot.webui;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.nanobot.security.WorkspaceScope;
import com.nanobot.security.WorkspacePolicy;

/**
 * Workspace-scoped source preview payloads for the WebUI.
 * 对标 Python file_preview.py.
 */
public final class FilePreview {

    private FilePreview() {}

    // 对标 Python MAX_FILE_PREVIEW_BYTES
    private static final long MAX_FILE_PREVIEW_BYTES = 384 * 1024; // 384 KB

    // 对标 Python file_preview_payload()
    public static Map<String, Object> filePreviewPayload(
            String rawPath, WorkspaceScope scope) {
        return filePreviewPayload(rawPath, scope, MAX_FILE_PREVIEW_BYTES);
    }

    static Map<String, Object> filePreviewPayload(
            String rawPath, WorkspaceScope scope, long maxBytes) {

        // 对标 Python _clean_preview_path()
        String path = cleanPreviewPath(rawPath);
        if (path.isEmpty()) {
            throw new WebUIFilePreviewError(400, "missing path");
        }
        if (path.length() > 4096) {
            throw new WebUIFilePreviewError(400, "path is too long");
        }

        // 对标 Python resolve_allowed_path()
        Path resolved;
        try {
            resolved = WorkspacePolicy.resolveAllowedPath(
                path, scope.projectPath(), scope.projectPath(), true);
        } catch (IOException e) {
            throw new WebUIFilePreviewError(404, "file not found");
        } catch (SecurityException e) {
            throw new WebUIFilePreviewError(403,
                "file is outside the current workspace");
        }

        if (!Files.isRegularFile(resolved)) {
            throw new WebUIFilePreviewError(404, "file not found");
        }

        byte[] raw;
        try {
            raw = Files.readAllBytes(resolved);
        } catch (IOException e) {
            throw new WebUIFilePreviewError(500, "failed to read file");
        }

        // 对标 Python: check for null bytes in first 4096 bytes for binary detection
        int checkLen = Math.min(4096, raw.length);
        for (int i = 0; i < checkLen; i++) {
            if (raw[i] == 0) {
                throw new WebUIFilePreviewError(415,
                    "binary files cannot be previewed");
            }
        }

        boolean truncated = raw.length > maxBytes;
        byte[] previewBytes = truncated ? java.util.Arrays.copyOf(raw, (int) maxBytes) : raw;

        String content;
        try {
            content = new String(previewBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            content = new String(previewBytes, StandardCharsets.UTF_8);
        }

        String displayPath = displayPath(resolved, scope.projectPath());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", resolved.toString());
        result.put("display_path", displayPath);
        result.put("project_path", scope.projectPath().toString());
        result.put("language", languageForPath(resolved));
        result.put("content", content);
        result.put("size", raw.length);
        result.put("truncated", truncated);
        return result;
    }

    // 对标 Python _clean_preview_path()
    static String cleanPreviewPath(String rawPath) {
        if (rawPath == null) return "";
        String value = rawPath.strip();
        if (value.isEmpty()) return "";
        if (value.startsWith("file://")) {
            java.net.URI uri = java.net.URI.create(value);
            value = uri.getPath();
            if (value != null) {
                value = URLDecoder.decode(value, StandardCharsets.UTF_8);
            } else {
                value = "";
            }
            // Strip leading slash on Windows-style paths like /C:/...
            if (Pattern.matches("^/[A-Za-z]:[\\\\/].*", value)) {
                value = value.substring(1);
            }
        } else {
            value = URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
        // Strip query and fragment
        int hashIdx = value.indexOf('#');
        if (hashIdx >= 0) value = value.substring(0, hashIdx);
        int qIdx = value.indexOf('?');
        if (qIdx >= 0) value = value.substring(0, qIdx);
        value = value.strip();
        // Strip go-to-line suffix like :42:15
        if (!Pattern.matches("^[A-Za-z]:[\\\\/].*", value)) {
            value = value.replaceFirst(":\\d+(?::\\d+)?$", "");
        }
        return value;
    }

    // 对标 Python _display_path()
    static String displayPath(Path path, Path root) {
        try {
            return root.relativize(path).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return path.toString().replace('\\', '/');
        }
    }

    // 对标 Python _language_for_path()
    static String languageForPath(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        String ext = name.contains(".")
            ? name.substring(name.lastIndexOf('.') + 1) : "";
        if ("dockerfile".equals(name)) return "dockerfile";
        return java.util.Map.of(
            "cjs", "javascript", "css", "css", "cts", "typescript",
            "html", "html", "js", "javascript", "json", "json",
            "jsonl", "json", "jsx", "jsx", "md", "markdown",
            "mdx", "markdown", "mjs", "javascript", "mts", "typescript",
            "py", "python", "pyi", "python", "scss", "scss",
            "sh", "bash", "toml", "toml", "ts", "typescript",
            "tsx", "tsx", "yaml", "yaml", "yml", "yaml"
        ).getOrDefault(ext, !ext.isEmpty() ? ext : "text");
    }

    public static class WebUIFilePreviewError extends RuntimeException {
        private final int status;
        public WebUIFilePreviewError(int status, String message) {
            super(message);
            this.status = status;
        }
        public int getStatus() { return status; }
    }
}
```

### 11. `ChatForking.java` — Chat Fork 编排

```java
package com.nanobot.webui;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.nanobot.session.SessionManager;

/**
 * WebUI chat fork orchestration.
 * 对标 Python forking.py — validate, clone session/transcript, attach, hydrate.
 */
public final class ChatForking {

    private ChatForking() {}

    private static final Pattern CHAT_ID_RE = Pattern.compile("^[A-Za-z0-9_:-]{1,64}$");

    // 对标 Python handle_webui_fork_chat()
    public static void handleForkChat(
            com.nanobot.channels.websocket.WebSocketChannel channel,
            org.springframework.web.socket.WebSocketSession connection,
            Map<String, Object> envelope) {

        Object sourceChatId = envelope.get("source_chat_id");
        Object rawIndex = envelope.get("before_user_index");

        if (!(sourceChatId instanceof String s) || !CHAT_ID_RE.matcher(s).matches()) {
            channel.sendErrorEvent(connection, "invalid source_chat_id");
            return;
        }
        if (rawIndex instanceof Boolean || !(rawIndex instanceof Integer)
                || ((Integer) rawIndex) < 0) {
            channel.sendErrorEvent(connection, "invalid before_user_index");
            return;
        }
        int beforeUserIndex = (Integer) rawIndex;

        SessionManager sessionManager = channel.getSessionManager();
        if (sessionManager == null) {
            channel.sendErrorEvent(connection, "session_manager_unavailable");
            return;
        }

        String newId = UUID.randomUUID().toString();
        String sourceKey = "websocket:" + s;
        String targetKey = "websocket:" + newId;

        try {
            // 对标 Python create_webui_chat_fork()
            com.nanobot.session.Session forked = sessionManager.forkSessionBeforeUserIndex(
                sourceKey, targetKey, beforeUserIndex);
            if (forked == null) {
                channel.sendErrorEvent(connection, "invalid fork source or index");
                return;
            }

            // Fork transcript
            boolean transcriptOk = WebUITranscriptRecorder
                .forkTranscriptBeforeUserIndex(sourceKey, targetKey, beforeUserIndex);
            if (!transcriptOk) {
                WebUITranscriptRecorder.writeSessionMessagesAsTranscript(
                    targetKey, forked.messages());
            }
            WebUITranscriptRecorder.appendForkMarker(targetKey);

            // Optional fork title
            Object title = envelope.get("title");
            if (title instanceof String ts && !ts.isBlank()) {
                String cleaned = cleanGeneratedTitle(ts);
                if (!cleaned.isEmpty()) {
                    forked.metadata().put("webui_title", cleaned);
                    sessionManager.save(forked, true);
                }
            }
        } catch (Exception e) {
            channel.logWarn("fork_chat failed: {}", e.getMessage());
            channel.sendErrorEvent(connection, "fork_chat_failed");
            return;
        }

        // Attach new chat and hydrate
        channel.attach(connection, newId);
        channel.sendEvent(connection, "attached", Map.of("chat_id", newId));
        channel.hydrateAfterSubscribe(newId);
    }

    // 对标 Python clean_generated_title()
    static String cleanGeneratedTitle(String raw) {
        String cleaned = raw.strip();
        if (cleaned.isEmpty()) return "";
        if (cleaned.length() > 200) cleaned = cleaned.substring(0, 200);
        return cleaned;
    }
}
```

### 12. `TranscriptionWS.java` — 音频转录 WebSocket 端点

```java
package com.nanobot.webui;

import java.util.Map;

import com.nanobot.audio.TranscriptionService;
import com.nanobot.audio.TranscriptionIngressError;

/**
 * WebUI transcription envelope handling.
 * 对标 Python transcription_ws.py — webui_transcription_event().
 */
public final class TranscriptionWS {

    private TranscriptionWS() {}

    private static final int MAX_REQUEST_ID_LENGTH = 80;

    /**
     * 对标 Python webui_transcription_event().
     * Returns (eventName, payload) for the WebSocket response.
     */
    public static TranscriptionResult processTranscriptionEvent(
            Map<String, Object> envelope,
            TranscriptionService transcriptionService) {

        Object requestId = envelope.get("request_id");
        boolean validRequestId = requestId instanceof String s
            && !s.isEmpty() && s.length() <= MAX_REQUEST_ID_LENGTH;

        if (!validRequestId) {
            return new TranscriptionResult("transcription_error",
                errorPayload("invalid_request", null));
        }
        String rid = (String) requestId;

        try {
            String text = transcriptionService.transcribeAudioDataUrl(
                (String) envelope.get("data_url"),
                envelope.get("duration_ms") instanceof Number n
                    ? n.longValue() : null);
            return new TranscriptionResult("transcription_result",
                Map.of("request_id", rid, "text", text));
        } catch (TranscriptionIngressError e) {
            return new TranscriptionResult("transcription_error",
                errorPayload(e.getDetail(), rid, e.getExtra()));
        }
    }

    private static Map<String, Object> errorPayload(String detail, Object requestId) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("detail", detail);
        if (requestId != null) payload.put("request_id", requestId);
        return payload;
    }

    private static Map<String, Object> errorPayload(
            String detail, String requestId, Map<String, Object> extra) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(extra);
        payload.put("detail", detail);
        payload.put("request_id", requestId);
        return payload;
    }

    public record TranscriptionResult(String event, Map<String, Object> payload) {}
}
```

### 13. `ThreadDisk.java` — WebUI Thread 磁盘持久化

```java
package com.nanobot.webui;

import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nanobot.session.SessionManager;

/**
 * Legacy WebUI JSON snapshot path helpers.
 * 对标 Python thread_disk.py.
 */
public final class ThreadDisk {

    private static final Logger logger = LoggerFactory.getLogger(ThreadDisk.class);

    private ThreadDisk() {}

    // 对标 Python webui_thread_file_path()
    static Path webuiThreadFilePath(String sessionKey) {
        String stem = SessionManager.safeKey(sessionKey);
        return com.nanobot.config.AppPaths.webuiDir().resolve(stem + ".json");
    }

    // 对标 Python delete_webui_thread()
    public static boolean deleteWebuiThread(String sessionKey) {
        boolean removed = false;
        Path path = webuiThreadFilePath(sessionKey);
        if (Files.isRegularFile(path)) {
            try {
                Files.delete(path);
                removed = true;
            } catch (Exception e) {
                logger.warn("Failed to delete webui thread file {}: {}", path, e.getMessage());
            }
        }
        if (WebUITranscriptRecorder.deleteWebuiTranscript(sessionKey)) {
            removed = true;
        }
        return removed;
    }
}
```

### 14. `SessionAutomations.java` — Session 自动化

```java
package com.nanobot.webui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.nanobot.cron.CronService;
import com.nanobot.cron.CronJob;

/**
 * Session-scoped automation payloads for the WebUI sidebar.
 * 对标 Python session_automations.py.
 */
public final class SessionAutomations {

    private SessionAutomations() {}

    // 对标 Python session_automations_payload()
    public static Map<String, Object> sessionAutomationsPayload(
            CronService cronService, String sessionKey) {
        List<Map<String, Object>> matched = new ArrayList<>();
        if (cronService != null) {
            for (CronJob job : cronService.listJobs(true)) {
                if (jobMatchesSession(job, sessionKey)) {
                    matched.add(serializeJob(job));
                }
            }
        }
        return Map.of("jobs", matched);
    }

    // 对标 Python _job_matches_session()
    private static boolean jobMatchesSession(CronJob job, String sessionKey) {
        if (!"agent_turn".equals(job.payload().kind())) return false;
        if (job.payload().sessionKey() != null) {
            return job.payload().sessionKey().equals(sessionKey);
        }
        if (job.payload().channel() != null && job.payload().to() != null) {
            return (job.payload().channel() + ":" + job.payload().to()).equals(sessionKey);
        }
        return false;
    }

    // 对标 Python _serialize_job()
    private static Map<String, Object> serializeJob(CronJob job) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", job.id());
        result.put("name", job.name());
        result.put("enabled", job.enabled());
        result.put("schedule", Map.of(
            "kind", job.schedule().kind(),
            "at_ms", job.schedule().atMs(),
            "every_ms", job.schedule().everyMs(),
            "expr", job.schedule().expr(),
            "tz", job.schedule().tz()
        ));
        result.put("payload", Map.of("message", job.payload().message()));
        result.put("state", Map.of(
            "next_run_at_ms", job.state().nextRunAtMs(),
            "last_status", job.state().lastStatus()
        ));
        return result;
    }
}
```

### 15. `SkillsApi.java` — 技能列表（安全脱敏）

```java
package com.nanobot.webui;

import java.nio.file.Path;
import java.util.*;

import com.nanobot.agent.SkillsLoader;

/**
 * Lightweight skill summaries for the WebUI — no local filesystem path leakage.
 * 对标 Python skills_api.py.
 */
public final class SkillsApi {

    private SkillsApi() {}

    // 对标 Python webui_skills_payload()
    public static Map<String, Object> webuiSkillsPayload(
            Path workspacePath, Set<String> disabledSkills) {
        SkillsLoader loader = new SkillsLoader(workspacePath, disabledSkills);
        List<Map<String, Object>> entries = loader.listSkills(false);
        entries.sort(Comparator
            .comparing((Map<String, Object> e) ->
                !"workspace".equals(e.get("source")))
            .thenComparing(e -> (String) e.get("name")));
        List<Map<String, Object>> skills = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            skills.add(skillPayload(loader, entry));
        }
        return Map.of("skills", skills);
    }

    // 对标 Python webui_skill_detail_payload()
    public static Map<String, Object> webuiSkillDetailPayload(
            Path workspacePath, String name, Set<String> disabledSkills) {
        SkillsLoader loader = new SkillsLoader(workspacePath, disabledSkills);
        List<Map<String, Object>> entries = loader.listSkills(false);
        Map<String, Object> entry = null;
        for (Map<String, Object> e : entries) {
            if (name.equals(e.get("name"))) { entry = e; break; }
        }
        if (entry == null) return null;
        Map<String, Object> result = new LinkedHashMap<>(skillPayload(loader, entry));
        result.put("requirements", loader.getSkillRequirements(name));
        result.put("raw_markdown", loader.loadSkill(name) != null
            ? loader.loadSkill(name) : "");
        return result;
    }

    // 对标 Python _skill_payload()
    @SuppressWarnings("unchecked")
    private static Map<String, Object> skillPayload(
            SkillsLoader loader, Map<String, Object> entry) {
        String name = (String) entry.get("name");
        Map<String, Object> metadata = loader.getSkillMetadata(name);
        SkillsLoader.Availability availability = loader.getSkillAvailability(name);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("description", description(metadata, name));
        result.put("source", entry.getOrDefault("source", "unknown"));
        result.put("available", availability.available());
        result.put("unavailable_reason", availability.unavailableReason());
        return result;
    }

    // 对标 Python _description()
    private static String description(Map<String, Object> metadata, String fallback) {
        if (metadata == null) return fallback;
        Object desc = metadata.get("description");
        if (desc instanceof String s && !s.strip().isEmpty()) return s.strip();
        return fallback;
    }
}
```

### 16. `CliAppsApi.java` — CLI Apps API

```java
package com.nanobot.webui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * CLI Apps helpers for the WebUI.
 * 对标 Python cli_apps_api.py.
 */
public final class CliAppsApi {

    private CliAppsApi() {}

    private static final Pattern CLI_APP_NAME_RE =
        Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$", Pattern.CASE_INSENSITIVE);

    // 对标 Python normalize_cli_app_mentions()
    @SuppressWarnings("unchecked")
    public static List<Map<String, String>> normalizeCliAppMentions(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, String>> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int limit = Math.min(list.size(), 8);
        for (int i = 0; i < limit; i++) {
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> m)) continue;
            String name = clipWsString(((Map<String, Object>) m).get("name"), 64);
            if (name == null || !CLI_APP_NAME_RE.matcher(name).matches()) continue;
            String key = name.toLowerCase();
            if (seen.contains(key)) continue;
            seen.add(key);
            Map<String, String> row = new java.util.LinkedHashMap<>();
            row.put("name", key);
            for (String field : List.of("display_name", "category",
                    "entry_point", "logo_url", "brand_color")) {
                String value = clipWsString(((Map<String, Object>) m).get(field),
                    "logo_url".equals(field) ? 512 : 160);
                if (value != null) row.put(field, value);
            }
            out.add(row);
        }
        return out;
    }

    static String clipWsString(Object value, int limit) {
        if (!(value instanceof String s)) return null;
        String text = s.strip();
        if (text.isEmpty()) return null;
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
```

### 17. `VersionCheckApi.java` — 版本检查

```java
package com.nanobot.webui;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.nanobot.Version;

/**
 * On-demand version checker for nanobot releases.
 * 对标 Python version_check.py.
 */
public final class VersionCheckApi {

    private VersionCheckApi() {}

    private static final String PYPI_URL = "https://pypi.org/pypi/nanobot-ai/json";
    private static final long CACHE_TTL_MS = 300_000; // 5 minutes
    private static final ObjectMapper mapper = new ObjectMapper();

    private static volatile long cachedAtMs = 0;
    private static volatile String cachedVersion = null;

    // 对标 Python check_for_update()
    @SuppressWarnings("unchecked")
    public static Map<String, Object> checkForUpdate() {
        long now = System.currentTimeMillis();
        String latest;
        if (now - cachedAtMs < CACHE_TTL_MS && cachedVersion != null) {
            latest = cachedVersion;
        } else {
            try {
                HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
                HttpRequest req = HttpRequest.newBuilder(URI.create(PYPI_URL))
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
                HttpResponse<String> resp = client.send(req,
                    HttpResponse.BodyHandlers.ofString());
                Map<String, Object> data = mapper.readValue(resp.body(), Map.class);
                Map<String, Object> info = (Map<String, Object>) data.get("info");
                latest = info != null ? (String) info.get("version") : null;
            } catch (Exception e) {
                return null;
            }
            cachedAtMs = now;
            cachedVersion = latest;
        }

        String current = Version.CURRENT;
        if (latest == null || latest.equals(current)) return null;
        return Map.of(
            "currentVersion", current,
            "latestVersion", latest,
            "pypiUrl", "https://pypi.org/project/nanobot-ai/"
        );
    }
}
```

### 18. API 兼容性保证

前端 `webui/` 源码（React + TypeScript + Vite）完整复制到 `src/main/resources/static/`，零改动。后端必须保持以下契约：

| API 路径 | 请求方法 | 请求体/参数 | 响应格式 | 兼容要点 |
|---|---|---|---|---|
| `POST /webui/issue-token` | POST | `Authorization: Bearer <issue_secret>` | `{"token": "nbwt_...", "expires_in": N}` | token 格式必须为 `nbwt_` + URL-safe base64 |
| `GET /webui/bootstrap` | GET | `Authorization: Bearer <issue_secret>` | `{"token": "...", "ws_path": "/", "ws_url": "ws://...", "expires_in": N, "model_name": "...", "runtime_surface": "...", "runtime_capabilities": {...}}` | 完整的 7 个字段都不能少 |
| `GET /api/sessions` | GET | `Authorization: Bearer <api_token>` | `{"sessions": [...]}` | session key 格式 `websocket:...` |
| `GET /api/sessions/{key}/messages` | GET | `Authorization: Bearer <api_token>` | 完整的 session JSON（含 messages 数组） | media URL 需要 augment |
| `GET /api/media/{sig}/{payload}` | GET | (签名验证) | 原始文件 bytes + Content-Type | HMAC 签名必须可验证 |
| `GET /api/commands` | GET | `Authorization: Bearer <api_token>` | `{"commands": [{"command": "/new", "title": "New chat", ...}]}` | 命令面板结构 |
| `GET /api/webui/skills` | GET | `Authorization: Bearer <api_token>` | `{"skills": [...]}` | 不能泄露本地路径 |
| `GET /*` (非 /api/) | GET | - | 静态文件 / index.html fallback | SPA 路由回退 |

### 19. Java 类映射总结表

| Python 文件 | Java 类 | 包路径 |
|---|---|---|
| `gateway_services.py` | `GatewayServices` (record) | `com.nanobot.webui` |
| `gateway_services.py` | `GatewayServicesConfiguration` (@Configuration) | `com.nanobot.webui` |
| `ws_http.py` | `GatewayHttpController` (@RestController) | `com.nanobot.webui` |
| `gateway_tokens.py` | `GatewayTokenStore` (@Component) | `com.nanobot.webui` |
| `http_utils.py` | `HttpUtils` (utility class) | `com.nanobot.webui` |
| `websocket_logging.py` | `WebSocketLogging` + `WebSocketHandshakeNoiseFilter` | `com.nanobot.webui` |
| `media_gateway.py` | `WebUIMediaGateway` (@Component) | `com.nanobot.webui` |
| `media_api.py` | `MediaApi` (utility class) | `com.nanobot.webui` |
| `settings_routes.py` | `SettingsRoutesController` (@RestController) | `com.nanobot.webui.settings` |
| `settings_api.py` | `SettingsApi` (service class) | `com.nanobot.webui.settings` |
| `transcript.py` | `WebUITranscriptRecorder` (@Component) | `com.nanobot.webui` |
| `session_list_index.py` | `SessionListIndex` (utility class) | `com.nanobot.webui` |
| `session_automations.py` | `SessionAutomations` (utility class) | `com.nanobot.webui` |
| `thread_disk.py` | `ThreadDisk` (utility class) | `com.nanobot.webui` |
| `forking.py` | `ChatForking` (utility class) | `com.nanobot.webui` |
| `file_preview.py` | `FilePreview` (utility class) | `com.nanobot.webui` |
| `sidebar_state.py` | `SidebarState` (utility class) | `com.nanobot.webui` |
| `skills_api.py` | `SkillsApi` (utility class) | `com.nanobot.webui` |
| `workspaces.py` | `WebUIWorkspaceController` (@Component) | `com.nanobot.webui` |
| `mcp_presets_api.py` | `McpPresetsApi` (utility class) | `com.nanobot.webui` |
| `mcp_presets_runtime.py` | `McpPresetsRuntime` (@Component) | `com.nanobot.webui` |
| `cli_apps_api.py` | `CliAppsApi` (utility class) | `com.nanobot.webui` |
| `token_usage.py` | `TokenUsageHook` / `TokenUsageService` | `com.nanobot.webui` |
| `transcription_ws.py` | `TranscriptionWS` (utility class) | `com.nanobot.webui` |
| `version_check.py` | `VersionCheckApi` (utility class) | `com.nanobot.webui` |

### 20. 关键设计决策

#### WebSocket 层
Python 使用 `websockets` 库同时处理 HTTP 和 WebSocket（同一端口）。Java 使用 Spring WebSocket (`@ServerEndpoint` 或 `WebSocketHandler`) + 共享 HTTP `@RestController`。所有 WebSocket 通道（对标 `nanobot/channels/websocket.py`）在此 controller 的相同端口上注册。

#### Token 存储
`GatewayTokenStore` 使用 `ConcurrentHashMap` — 单进程内存存储（对标 Python 的 `dict`）。对于水平扩展场景，后续可替换为 Redis-backed 实现，但接口不变。

#### 静态 SPA 部署
前端 `webui/dist/` 通过 Maven/Gradle 插件（如 `maven-resources-plugin`）在构建时复制到 `target/classes/static/`。开发时前端 Vite dev server 独立运行并代理 API 到 Java 后端。

#### 虚拟线程
所有 HTTP 请求由 Spring Boot 内嵌 Tomcat（配置虚拟线程执行器）处理。与 Python `asyncio` 等价 —— 阻塞 I/O 自动让出载体线程。

### 21. 验证标准

```bash
# 1. HTTP API 冒烟测试
curl -s http://localhost:18790/webui/bootstrap | jq .
# 预期: {"token": "nbwt_...", "ws_path": "/", "ws_url": "ws://...", ...}

# 2. Token issue
TOKEN=$(curl -s http://localhost:18790/webui/bootstrap | jq -r .token)
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:18790/api/commands | jq .
# 预期: {"commands": [...]}

# 3. SPA fallback
curl -s -o /dev/null -w "%{http_code}" http://localhost:18790/some-spa-route
# 预期: 200 (index.html)

# 4. API 404
curl -s -o /dev/null -w "%{http_code}" http://localhost:18790/api/nonexistent
# 预期: 404

# 5. Media signed URL
# (requires a workspace file to exist)
SIGNED_URL=$(curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:18790/api/sessions/websocket:test/messages | \
  jq -r '.messages[0].media[0].url')
curl -s -o /dev/null -w "%{http_code}" "$SIGNED_URL"
# 预期: 200

# 6. Skills (脱敏)
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:18790/api/webui/skills | \
  jq '.skills[0] | keys'
# 预期: ["name", "description", "source", "available", "unavailable_reason"]
# (不应该有 "path" 或 "filepath")
```

### 22. 代码量估算

| Java 文件 | 行数 |
|---|---|
| `GatewayServices.java` (record) | ~25 |
| `GatewayServicesConfiguration.java` (@Configuration) | ~70 |
| `GatewayHttpController.java` (@RestController) | ~380 |
| `GatewayTokenStore.java` (@Component) | ~130 |
| `HttpUtils.java` (utility) | ~160 |
| `WebSocketLogging.java` (utility) | ~60 |
| `WebUIMediaGateway.java` (@Component) | ~120 |
| `MediaApi.java` (utility) | ~200 |
| `SettingsRoutesController.java` (@RestController) | ~300 |
| `SettingsApi.java` (service) | ~400 |
| `WebUITranscriptRecorder.java` (@Component) | ~700 |
| `SessionListIndex.java` (utility) | ~150 |
| `SessionAutomations.java` (utility) | ~60 |
| `ThreadDisk.java` (utility) | ~50 |
| `ChatForking.java` (utility) | ~60 |
| `FilePreview.java` (utility) | ~100 |
| `SidebarState.java` (utility) | ~230 |
| `SkillsApi.java` (utility) | ~110 |
| `WebUIWorkspaceController.java` (@Component) | ~200 |
| `McpPresetsApi.java` / `McpPresetsRuntime.java` | ~200 |
| `CliAppsApi.java` (utility) | ~70 |
| `TokenUsageHook.java` (@Component) | ~120 |
| `TranscriptionWS.java` (utility) | ~90 |
| `VersionCheckApi.java` (utility) | ~65 |
| `ChatForking.java` (utility) | ~100 |
| `ThreadDisk.java` (utility) | ~40 |
| `SessionAutomations.java` (utility) | ~85 |
| `SpaFallbackConfig.java` (@Configuration) | ~50 |
| `TokenUtils.java` (utility) | ~30 |
| **合计** | **~4,300** |

---

## 23. 复刻完整度

### 23.1 源码对标清单

| Python 文件 | 行数 | Java 类 | 完整度 | 备注 |
|------------|------|---------|--------|------|
| `gateway_services.py` | 82 | `GatewayServices` (record) + `GatewayServicesConfiguration` | **100%** | DI 模型完整对标 |
| `ws_http.py` | 603 | `GatewayHttpController` (@RestController) | **100%** | 所有路由完整对标 |
| `gateway_tokens.py` | 83 | `GatewayTokenStore` (@Component) | **100%** | HMAC token 签发/验证/吊销 |
| `http_utils.py` | 152 | `HttpUtils` (utility) | **100%** | 所有 12 个函数对标 |
| `websocket_logging.py` | 46 | `WebSocketLogging` | **100%** | 握手噪音过滤 |
| `media_gateway.py` | 93 | `WebUIMediaGateway` (@Component) | **100%** | 设计层完成，media_api.py 见备注 |
| `media_api.py` | ~400 | `MediaApi` (utility) | **设计完成** | 待实际编码；核心函数：sign_media_path, serve_signed_media, sign_or_stage_media_path, b64url_encode/decode |
| `settings_api.py` | ~800 | `SettingsApi` (service) | **设计完成** | 待实际编码；settings CRUD payload 构建 |
| `settings_routes.py` | ~400 | `SettingsRoutesController` (@RestController) | **设计完成** | 待实际编码；HTTP 路由适配 |
| `transcript.py` | 1,864 | `WebUITranscriptRecorder` (@Component) | **设计完成** | 待实际编码；转录录制核心逻辑 |
| `session_list_index.py` | 220 | `SessionListIndex` (utility) | **设计完成** | 待实际编码；缓存+重建逻辑 |
| `session_automations.py` | ~50 | `SessionAutomations` (utility) | **100%** | 完整对标 |
| `thread_disk.py` | ~80 | `ThreadDisk` (utility) | **100%** | 完整对标 |
| `forking.py` | ~100 | `ChatForking` (utility) | **100%** | 完整对标；需 WebSocketChannel 方法可见性为 public |
| `file_preview.py` | ~120 | `FilePreview` (utility) | **100%** | 完整对标，含 _clean_preview_path / _language_for_path |
| `sidebar_state.py` | ~60 | `SidebarState` (utility) | **100%** | 完整对标，含 normalize / clean 系列验证函数 |
| `skills_api.py` | 62 | `SkillsApi` (utility) | **100%** | 完整对标 |
| `workspaces.py` | 284 | `WebUIWorkspaceController` (@Component) | **100%** | 见 12-channels-impl.md 1.3.3 节接口定义 |
| `mcp_presets_api.py` | ~150 | `McpPresetsApi` (utility) | **设计完成** | 待实际编码 |
| `mcp_presets_runtime.py` | ~100 | `McpPresetsRuntime` (@Component) | **设计完成** | 待实际编码 |
| `cli_apps_api.py` | ~100 | `CliAppsApi` (utility) | **100%** | 完整对标 |
| `token_usage.py` | ~250 | `TokenUsageHook` (@Component) | **设计完成** | 待实际编码 |
| `transcription_ws.py` | ~80 | `TranscriptionWS` (utility) | **100%** | 完整对标 |
| `version_check.py` | ~60 | `VersionCheckApi` (utility) | **100%** | 完整对标，含 5 分钟缓存 |
| `ws_http.py` (SPA) | — | `SpaFallbackConfig` (@Configuration) | **100%** | SPA 路由回退 |
| — | — | `TokenUtils` (utility) | **100%** | session key 解码/验证 |

### 23.2 方法级差异说明

**已完整对标（含代码块）：**

| 方法/功能 | 状态 | 说明 |
|----------|------|------|
| GatewayTokenStore.checkApiToken/canIssue/issueToken/takeIssuedTokenIfValid/clear | ✅ | 100% 对标 Python |
| HttpUtils（全部 12 个方法） | ✅ | stripTrailingSlash/ normalizeConfigPath/ caseInsensitiveHeader/ safeHostHeader/ hostForUrl/ bearerToken/ issueRouteSecretMatches/ isLocalhost/ hmacCompareDigest/ parseQuery/ queryFirst |
| GatewayHttpController（全部 16 个端点） | ✅ | bootstrap/ sessions/ media/ commands/ workspaces/ skills/ sidebar-state 全部路由 |
| SidebarState（normalize + 5 个 clean 函数） | ✅ | 对标 Python 完整验证链 |
| FilePreview（cleanPreviewPath + languageForPath） | ✅ | 对标 Python _clean_preview_path + _language_for_path |
| ChatForking.handleForkChat | ✅ | session fork + transcript + scope attach 完整流程 |
| TranscriptionWS.processTranscriptionEvent | ✅ | audio data URL → text transcription |
| SkillsApi.webuiSkillsPayload/webuiSkillDetailPayload | ✅ | 安全脱敏 |
| CliAppsApi.normalizeCliAppMentions | ✅ | CLI app 提及标准化 |
| VersionCheckApi.checkForUpdate | ✅ | PyPI 检查 + 5 分钟缓存 |
| SessionAutomations.sessionAutomationsPayload | ✅ | cron job → session 匹配 |
| ThreadDisk.deleteWebuiThread | ✅ | legacy JSON + transcript 清理 |

**ChatForking 可见性说明：**

`ChatForking.handleForkChat()` 位于 `com.nanobot.webui` 包，需要访问 `WebSocketChannel` 的以下方法（位于 `com.nanobot.channels.websocket` 包）：
- `sendErrorEvent(session, detail)` → 需改为 `public`
- `sendEvent(session, event, fields)` → 需改为 `public`
- `attach(session, chatId)` → 需改为 `public`
- `hydrateAfterSubscribe(chatId)` → 需改为 `public`
- `getSessionManager()` → 需新增 `public` getter

**设计完成、待实际编码：**

| Python 文件 | Java 类 | 规模估计 | 备注 |
|------------|---------|---------|------|
| `transcript.py` (1,864 行) | `WebUITranscriptRecorder` | ~700 行 | 转录录制核心，复杂度最高 |
| `settings_api.py` (~800 行) | `SettingsApi` | ~400 行 | Settings CRUD payload |
| `settings_routes.py` (~400 行) | `SettingsRoutesController` | ~300 行 | Settings HTTP 路由 |
| `media_api.py` (~400 行) | `MediaApi` | ~200 行 | HMAC 签名媒体 URL |
| `session_list_index.py` (220 行) | `SessionListIndex` | ~150 行 | 缓存+重建索引 |
| `mcp_presets_api.py` (~150 行) | `McpPresetsApi` | ~100 行 | MCP 预设 CRUD |
| `mcp_presets_runtime.py` (~100 行) | `McpPresetsRuntime` | ~80 行 | MCP 预设运行时 |
| `token_usage.py` (~250 行) | `TokenUsageHook` | ~120 行 | Token 用量统计 |

这 8 个文件因 Python 源码总量较大（~3,700 行），在文档中保留为设计笔记级别。它们的 HTTP 路由已在 `GatewayHttpController` 中定义，实现细节遵循文档中的架构决策。

### 23.3 未修复项

无阻塞项。`ChatForking` 需要 WebSocketChannel 暴露 5 个方法的 public 访问器，这是实现时的微小调整。

### 23.4 测试覆盖

| 测试类型 | 覆盖范围 | 优先级 |
|---------|---------|--------|
| Token 签发/验证测试 | issueToken / takeIssuedTokenIfValid / checkApiToken / 过期清理 | P0 |
| HTTP 端点冒烟测试 | bootstrap / sessions / commands / workspaces / skills / sidebar-state | P0 |
| Media 签名测试 | sign_media_path → serve_signed_media 往返 | P0 |
| Sidebar 状态持久化测试 | read/write/normalize 往返，脏输入拒绝 | P1 |
| File 预览测试 | 文本/图片/二进制/越界路径/超大文件 | P1 |
| Fork 流程测试 | session fork + transcript fork + fork marker | P1 |
| Transcription 测试 | data_url → text 端到端 | P1 |
| SPA fallback 测试 | /api/ 404 vs 非 api/ index.html 回退 | P0 |

### 23.5 编译/启动验证

- [ ] `GatewayServicesConfiguration` @Bean 装配正确（无循环依赖）
- [ ] `GatewayHttpController` 编译通过（0 错误）
- [ ] `GatewayTokenStore` 编译通过
- [ ] `HttpUtils` / `SidebarState` / `FilePreview` 编译通过
- [ ] `ChatForking` / `TranscriptionWS` / `SkillsApi` 等编译通过
- [ ] Spring Boot 启动并监听配置端口
- [ ] `/webui/bootstrap` 返回完整 7 字段响应
- [ ] `/api/sessions` 返回 session 列表
- [ ] SPA fallback 正确返回 index.html
