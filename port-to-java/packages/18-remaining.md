# Package 18: Remaining Packages — API, Apps, Audio, Cron, Pairing

## Overview

This document covers the last five Python packages that need Java porting: the OpenAI-compatible HTTP API server, CLI app subprocess management, audio transcription, cron scheduling, and DM sender pairing. These are smaller, self-contained modules.

**Estimated total Java lines:** ~2,000 across ~15 files.

---

## 1. API Server (`com.nanobot.api`)

**Python source:** `api/server.py` (~400 lines)
**Java target:** `OpenAiApiServer.java` (~350 lines)

### 1.1 Overview

Provides OpenAI-compatible HTTP endpoints (`/v1/chat/completions`, `/v1/models`, `/health`) backed by the agent loop. Supports both JSON request bodies and `multipart/form-data` uploads. Streaming responses use Server-Sent Events (SSE).

### 1.2 Spring Boot REST Controller

```java
package com.nanobot.api;

import com.nanobot.agent.loop.AgentLoop;
import com.nanobot.utils.MediaDecode;
import com.nanobot.utils.RuntimeConstants;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

@RestController
public class OpenAiApiServer {

    private static final Logger log = LoggerFactory.getLogger(OpenAiApiServer.class);

    private static final String API_SESSION_KEY = "api:default";
    private static final String API_CHAT_ID = "default";

    private final AgentLoop agentLoop;
    private final String modelName;
    private final long requestTimeoutSeconds;
    private final Path mediaDir;
    private final ConcurrentHashMap<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

    public OpenAiApiServer(
            AgentLoop agentLoop,
            @Value("${nanobot.api.model-name:nanobot}") String modelName,
            @Value("${nanobot.api.request-timeout:120}") long requestTimeoutSeconds,
            @Value("${nanobot.api.media-dir:${user.home}/.nanobot/media/api}") String mediaDirPath) {
        this.agentLoop = agentLoop;
        this.modelName = modelName;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.mediaDir = Path.of(mediaDirPath);
    }

    // ── POST /v1/chat/completions ─────────────────────────────

    @PostMapping("/v1/chat/completions")
    public ResponseEntity<?> chatCompletions(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestParam(value = "message", required = false) String multipartMessage,
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "session_id", required = false) String sessionId,
            @RequestParam(value = "model", required = false) String requestedModel,
            HttpServletRequest request) {

        boolean isMultipart = request.getContentType() != null
            && request.getContentType().startsWith("multipart/");

        String text;
        List<String> mediaPaths = new ArrayList<>();
        boolean stream = false;

        try {
            if (isMultipart) {
                // multipart/form-data handling
                text = multipartMessage != null ? multipartMessage : "";
                if (files != null) {
                    for (MultipartFile file : files) {
                        Path dest = saveMultipartFile(file);
                        if (dest != null) mediaPaths.add(dest.toString());
                    }
                }
                if (text.isEmpty()) text = "Please analyze the uploaded files";
            } else {
                // JSON body
                String requestedModelFromBody = safeString(body.get("model"));
                sessionId = safeString(body.get("session_id"));
                stream = Boolean.TRUE.equals(body.get("stream"));

                RequestParseResult parsed = parseJsonContent(body);
                text = parsed.text();
                mediaPaths = parsed.mediaPaths();
            }
        } catch (IllegalArgumentException e) {
            return errorResponse(400, e.getMessage());
        } catch (MediaDecode.FileSizeExceededException e) {
            return errorResponse(413, e.getMessage());
        } catch (Exception e) {
            log.error("Error parsing upload", e);
            return errorResponse(413, "File too large or invalid upload");
        }

        // Validate model
        if (requestedModel != null && !requestedModel.isEmpty()
            && !requestedModel.equals(modelName)) {
            return errorResponse(400, "Only configured model '" + modelName + "' is available");
        }

        String sessionKey = sessionId != null && !sessionId.isEmpty()
            ? "api:" + sessionId : API_SESSION_KEY;
        ReentrantLock sessionLock = sessionLocks.computeIfAbsent(
            sessionKey, k -> new ReentrantLock());

        log.info("API request sessionKey={} media={} text={} stream={}",
            sessionKey, mediaPaths.size(),
            text.length() > 80 ? text.substring(0, 80) : text, stream);

        if (stream) {
            return handleStreaming(text, mediaPaths, sessionKey, sessionLock);
        } else {
            return handleNonStreaming(text, mediaPaths, sessionKey, sessionLock);
        }
    }

    // ── Streaming Handler ──────────────────────────────────────

    private ResponseEntity<SseEmitter> handleStreaming(
            String text, List<String> mediaPaths,
            String sessionKey, ReentrantLock sessionLock) {

        SseEmitter emitter = new SseEmitter(requestTimeoutSeconds * 1000);

        Thread.ofVirtual().start(() -> {
            sessionLock.lock();
            try {
                // Execute agent loop with streaming callback
                // Emit SSE chunks via emitter.send(SseEmitter.event()...)
                // On completion: emitter.complete()
                // On error: emitter.completeWithError()
            } catch (Exception e) {
                log.error("Streaming error for session {}", sessionKey, e);
                emitter.completeWithError(e);
            } finally {
                sessionLock.unlock();
            }
        });

        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .header("Cache-Control", "no-cache")
            .header("Connection", "keep-alive")
            .body(emitter);
    }

    // ── Non-Streaming Handler ──────────────────────────────────

    private ResponseEntity<?> handleNonStreaming(
            String text, List<String> mediaPaths,
            String sessionKey, ReentrantLock sessionLock) {

        String fallback = RuntimeConstants.EMPTY_FINAL_RESPONSE_MESSAGE;

        sessionLock.lock();
        try {
            // Execute agent loop directly
            String responseText = "..."; // agentLoop.processDirect(...)

            if (responseText == null || responseText.isBlank()) {
                // Retry once
                responseText = "...";
                if (responseText == null || responseText.isBlank()) {
                    responseText = fallback;
                }
            }

            return ResponseEntity.ok(chatCompletionResponse(responseText, modelName));
        } catch (TimeoutException e) {
            return errorResponse(504, "Request timed out after " + requestTimeoutSeconds + "s");
        } catch (Exception e) {
            log.error("Error processing request for session {}", sessionKey, e);
            return errorResponse(500, "Internal server error", "server_error");
        } finally {
            sessionLock.unlock();
        }
    }

    // ── GET /v1/models ─────────────────────────────────────────

    @GetMapping("/v1/models")
    public ResponseEntity<Map<String, Object>> models() {
        return ResponseEntity.ok(Map.of(
            "object", "list",
            "data", List.of(Map.of(
                "id", modelName,
                "object", "model",
                "created", 0,
                "owned_by", "nanobot"
            ))
        ));
    }

    // ── GET /health ────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    // ── SSE Formatting ─────────────────────────────────────────

    /**
     * Format a single OpenAI-compatible SSE chunk for Server-Sent Events.
     */
    public static String sseChunk(String delta, String model, String chunkId, String finishReason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", chunkId);
        payload.put("object", "chat.completion.chunk");
        payload.put("created", Instant.now().getEpochSecond());
        payload.put("model", model);

        Map<String, Object> deltaMap = new LinkedHashMap<>();
        if (delta != null && !delta.isEmpty()) {
            deltaMap.put("content", delta);
        }
        payload.put("choices", List.of(Map.of(
            "index", 0,
            "delta", deltaMap,
            "finish_reason", finishReason
        )));

        return "data: " + toJson(payload) + "\n\n";
    }

    // ── Response Helpers ───────────────────────────────────────

    private static ResponseEntity<Map<String, Object>> errorResponse(
            int status, String message) {
        return errorResponse(status, message, "invalid_request_error");
    }

    private static ResponseEntity<Map<String, Object>> errorResponse(
            int status, String message, String errorType) {
        return ResponseEntity.status(status).body(Map.of(
            "error", Map.of(
                "message", message,
                "type", errorType,
                "code", status
            )
        ));
    }

    private Map<String, Object> chatCompletionResponse(String content, String model) {
        return Map.of(
            "id", "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
            "object", "chat.completion",
            "created", Instant.now().getEpochSecond(),
            "model", model,
            "choices", List.of(Map.of(
                "index", 0,
                "message", Map.of("role", "assistant", "content", content),
                "finish_reason", "stop"
            )),
            "usage", Map.of(
                "prompt_tokens", 0,
                "completion_tokens", 0,
                "total_tokens", 0
            )
        );
    }

    // ── JSON Content Parsing ───────────────────────────────────

    @SuppressWarnings("unchecked")
    private RequestParseResult parseJsonContent(Map<String, Object> body) {
        Object messages = body.get("messages");
        if (!(messages instanceof List<?> msgList) || msgList.size() != 1) {
            throw new IllegalArgumentException("Only a single user message is supported");
        }
        Object message = msgList.get(0);
        if (!(message instanceof Map<?, ?> msgMap)
            || !"user".equals(msgMap.get("role"))) {
            throw new IllegalArgumentException("Only a single user message is supported");
        }

        Map<String, Object> userMsg = (Map<String, Object>) message;
        Object userContent = userMsg.get("content");
        List<String> mediaPaths = new ArrayList<>();

        String text;
        if (userContent instanceof List<?> parts) {
            StringBuilder textBuilder = new StringBuilder();
            for (Object part : parts) {
                if (!(part instanceof Map<?, ?> pm)) continue;
                if ("text".equals(pm.get("type"))) {
                    textBuilder.append(Objects.toString(pm.get("text"), "")).append(" ");
                } else if ("image_url".equals(pm.get("type"))) {
                    Object imageUrl = pm.get("image_url");
                    if (imageUrl instanceof Map<?, ?> iu) {
                        String url = Objects.toString(iu.get("url"), "");
                        if (url.startsWith("data:")) {
                            try {
                                String saved = MediaDecode.saveBase64DataUrl(
                                    url, mediaDir, null);
                                if (saved != null) mediaPaths.add(saved);
                            } catch (MediaDecode.FileSizeExceededException e) {
                                throw new IllegalArgumentException(e.getMessage());
                            }
                        } else if (!url.isEmpty()) {
                            throw new IllegalArgumentException(
                                "Remote image URLs are not supported. " +
                                "Use base64 data URLs or upload files via multipart/form-data.");
                        }
                    }
                }
            }
            text = textBuilder.toString().strip();
        } else if (userContent instanceof String s) {
            text = s;
        } else {
            throw new IllegalArgumentException("Invalid content format");
        }

        return new RequestParseResult(text, mediaPaths);
    }

    private record RequestParseResult(String text, List<String> mediaPaths) {}

    // ── Multipart Helper ───────────────────────────────────────

    private Path saveMultipartFile(MultipartFile file)
            throws MediaDecode.FileSizeExceededException, IOException {
        if (file.isEmpty()) return null;
        int maxBytes = 10 * 1024 * 1024; // 10 MB
        if (file.getSize() > maxBytes) {
            throw new MediaDecode.FileSizeExceededException(
                "File '" + file.getOriginalFilename() + "' exceeds "
                + (maxBytes / (1024 * 1024)) + "MB limit");
        }
        Files.createDirectories(mediaDir);
        String base = safeString(file.getOriginalFilename(), "upload.bin");
        String filename = UUID.randomUUID().toString().replace("-", "").substring(0, 12)
            + "_" + base;
        Path dest = mediaDir.resolve(filename);
        file.transferTo(dest);
        return dest;
    }

    private static String safeString(Object obj) {
        return safeString(obj, "");
    }

    private static String safeString(Object obj, String defaultVal) {
        if (obj instanceof String s) return s.strip();
        return defaultVal;
    }

    // ── JSON Serialization (simple, no Jackson for this utility) ──

    // In production, inject an ObjectMapper from Spring context.
    private static String toJson(Map<String, Object> map) {
        // Use Jackson or Gson; simplified placeholder
        return map.toString(); // replace with real JSON serialization
    }
}
```

### 1.3 Key Design Decisions

| Aspect | Decision |
|--------|----------|
| Framework | Spring `@RestController` with Spring MVC |
| Multipart upload | Spring `MultipartFile` handling |
| SSE streaming | Spring `SseEmitter` or raw `StreamingResponseBody` |
| Session locking | `ConcurrentHashMap<String, ReentrantLock>` per session key |
| Virtual thread | Use `Thread.ofVirtual().start()` for async agent loop execution |
| Request timeout | Configured via `spring.mvc.async.request-timeout` |
| Max upload size | `spring.servlet.multipart.max-file-size=20MB` |

### 1.4 application.yml Configuration

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 20MB
      max-request-size: 20MB
  mvc:
    async:
      request-timeout: 120000

nanobot:
  api:
    model-name: nanobot
    request-timeout: 120
```

---

## 2. Apps / CLI App Protocol (`com.nanobot.apps`)

**Python source:** `apps/protocol.py` (57 lines) + `apps/cli/service.py` (1,263 lines)
**Java target:** `AppManifest.java`, `CliAppService.java` (~600 lines)

### 2.1 `AppManifest.java` — App Protocol Record

```java
package com.nanobot.apps;

import java.util.*;

/**
 * Neutral manifest shape for settings-managed agent apps.
 * Schema: "agent-app.v1"
 */
public record AppManifest(
    String appId,
    String displayName,
    String description,
    String category,
    String source,
    List<Map<String, Object>> capabilities,
    Map<String, Object> install,
    Map<String, Object> remove,
    Map<String, Object> trust,
    String version,
    String logoUrl,
    String brandColor,
    String docsUrl
) {
    public static final String SCHEMA = "agent-app.v1";

    public Map<String, Object> toCompactMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        m.put("id", appId);
        m.put("display_name", displayName);
        putIf(m, "version", version);
        m.put("description", description);
        m.put("category", category);
        m.put("source", source);
        putIf(m, "logo_url", logoUrl);
        putIf(m, "brand_color", brandColor);
        putIf(m, "docs_url", docsUrl);
        m.put("capabilities", capabilities);
        m.put("install", install);
        m.put("remove", remove);
        putIf(m, "trust", trust);
        return m;
    }

    private static void putIf(Map<String, Object> m, String key, Object value) {
        if (value != null && !"".equals(value) && !Collections.emptyList().equals(value)
            && !Collections.emptyMap().equals(value)) {
            m.put(key, value);
        }
    }
}
```

### 2.2 `CliAppService.java` — CLI App Subprocess Manager (~500 lines)

Manages external CLI applications (like `gh`, `npm`, etc.) that are installed/removed/run as subprocesses. The Python implementation is 1,263 lines handling platform-specific installation (brew, apt, npm, pip), version checks, and subprocess lifecycle.

Key design elements:

```java
package com.nanobot.apps;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

@Service
public class CliAppService {

    // ── Capability registry ────────────────────────────────────

    public record CliCapability(String id, String label, String binary) {}

    // ── App lifecycle ──────────────────────────────────────────

    public record InstalledApp(String id, String version, Path binPath) {}

    /** Check if a CLI app is installed and functional. */
    public boolean isInstalled(String appId) { /* check PATH + version */ return false; }

    /** Get installed version string. */
    public String getVersion(String appId) { return null; }

    /** Install an app using the configured installer (brew, apt, npm, pip). */
    public CompletableFuture<InstalledApp> install(String appId) { return null; }

    /** Remove an installed app. */
    public CompletableFuture<Boolean> remove(String appId) { return null; }

    /** Execute a subprocess with timeout. Returns (exitCode, stdout, stderr). */
    public record SubprocessResult(int exitCode, String stdout, String stderr) {}

    public CompletableFuture<SubprocessResult> execute(
            List<String> command,
            Path workingDir,
            Map<String, String> env,
            long timeoutSeconds) {
        return CompletableFuture.supplyAsync(() -> {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (workingDir != null) pb.directory(workingDir.toFile());
            if (env != null) pb.environment().putAll(env);
            pb.redirectErrorStream(false);

            try {
                Process process = pb.start();
                String stdout = readStream(process.getInputStream());
                String stderr = readStream(process.getErrorStream());
                boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return new SubprocessResult(-1, stdout, "TIMEOUT after " + timeoutSeconds + "s");
                }
                return new SubprocessResult(process.exitValue(), stdout, stderr);
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
                return new SubprocessResult(-1, "", e.getMessage());
            }
        });
    }

    private static String readStream(InputStream is) throws IOException {
        return new String(is.readAllBytes()).strip();
    }
}
```

### 2.3 Estimated Lines

| Class | Lines |
|-------|-------|
| `AppManifest.java` | 50 |
| `CliAppService.java` | 500 |
| `CliInstaller.java` (platform-specific: brew, apt, npm) | 200 |
| `package-info.java` | 5 |
| **Total** | ~755 |

---

## 3. Audio Transcription (`com.nanobot.audio`)

**Python source:** `audio/transcription.py` (208 lines) + `audio/transcription_registry.py` (102 lines)
**Java target:** ~250 lines across 4 files

### 3.1 `TranscriptionProviderSpec.java` — Registry Entry

```java
package com.nanobot.audio;

/**
 * Registry entry for a transcription provider.
 */
public record TranscriptionProviderSpec(
    String name,
    String defaultModel,
    List<String> aliases
) {
    // Static registry of known providers
    public static final List<TranscriptionProviderSpec> PROVIDERS = List.of(
        new TranscriptionProviderSpec("groq", "whisper-large-v3", List.of()),
        new TranscriptionProviderSpec("openai", "whisper-1", List.of()),
        new TranscriptionProviderSpec("openrouter", "openai/whisper-1", List.of()),
        new TranscriptionProviderSpec("xiaomi_mimo", "mimo-v2.5-asr", List.of("mimo", "xiaomi")),
        new TranscriptionProviderSpec("stepfun", "stepaudio-2.5-asr", List.of()),
        new TranscriptionProviderSpec("assemblyai", "universal-3-pro,universal-2", List.of()),
        new TranscriptionProviderSpec("siliconflow", "FunAudioLLM/SenseVoiceSmall", List.of("silicon"))
    );
}
```

### 3.2 `TranscriptionRegistry.java` — Lookup

```java
package com.nanobot.audio;

import java.util.*;

public final class TranscriptionRegistry {

    private static final Map<String, TranscriptionProviderSpec> BY_NAME;
    private static final Map<String, TranscriptionProviderSpec> BY_ALIAS;

    static {
        Map<String, TranscriptionProviderSpec> names = new LinkedHashMap<>();
        Map<String, TranscriptionProviderSpec> aliases = new LinkedHashMap<>();
        for (TranscriptionProviderSpec spec : TranscriptionProviderSpec.PROVIDERS) {
            names.put(spec.name(), spec);
            for (String alias : spec.aliases()) {
                aliases.put(alias, spec);
            }
        }
        BY_NAME = Collections.unmodifiableMap(names);
        BY_ALIAS = Collections.unmodifiableMap(aliases);
    }

    public static TranscriptionProviderSpec getProvider(String name) {
        return BY_NAME.get(name);
    }

    public static TranscriptionProviderSpec resolveProvider(String value) {
        if (value == null) return null;
        String name = value.strip().toLowerCase();
        TranscriptionProviderSpec spec = BY_NAME.get(name);
        return spec != null ? spec : BY_ALIAS.get(name);
    }

    public static List<String> providerNames() {
        return new ArrayList<>(BY_NAME.keySet());
    }
}
```

### 3.3 `EffectiveTranscriptionConfig.java` — Config Record

```java
package com.nanobot.audio;

public record EffectiveTranscriptionConfig(
    boolean enabled,
    String provider,
    String model,
    String language,
    String apiKey,
    String apiBase,
    int maxDurationSec,
    int maxUploadMb
) {
    public boolean configured() {
        return apiKey != null && !apiKey.isEmpty();
    }
}
```

### 3.4 `TranscriptionService.java` — Main Service

```java
package com.nanobot.audio;

import java.nio.file.Path;

/**
 * Application-level transcription service.
 * Validates audio uploads, persists and transcribes, cleans up temp files.
 */
public class TranscriptionService {

    // Allowed audio MIME types
    private static final Set<String> AUDIO_MIME_ALLOWED = Set.of(
        "audio/aac", "audio/flac", "audio/m4a", "audio/mp4",
        "audio/mpeg", "audio/ogg", "audio/wav", "audio/webm",
        "audio/x-m4a", "audio/x-wav"
    );

    private static final String DEFAULT_PROVIDER = "groq";
    private static final int MAX_AUDIO_BYTES_FALLBACK = 25 * 1024 * 1024;

    // Resolve transcription config from application config
    public static EffectiveTranscriptionConfig resolveConfig(/* AppConfig config */) {
        // Resolve provider, model, api_key, etc. from config
        // Falls back to channels.transcription_provider (legacy)
        return null; // placeholder
    }

    // Validate, persist, transcribe, and clean up a WebUI audio data URL
    public static String transcribeAudioDataUrl(
            String dataUrl,
            EffectiveTranscriptionConfig config,
            Long durationMs) {
        // 1. Validate MIME type
        // 2. Validate file size
        // 3. Validate duration < maxDurationSec
        // 4. Save base64 to temp file
        // 5. Call provider adapter's transcribe()
        // 6. Clean up temp file
        // 7. Return transcribed text
        return ""; // placeholder
    }

    // Transcribe an existing file
    public static String transcribeAudioFile(
            Path filePath,
            EffectiveTranscriptionConfig config) {
        // Dispatch to provider-specific HTTP adapter
        return ""; // placeholder
    }
}
```

### 3.5 Estimated Lines

| Class | Lines |
|-------|-------|
| `TranscriptionProviderSpec.java` | 25 |
| `TranscriptionRegistry.java` | 40 |
| `EffectiveTranscriptionConfig.java` | 20 |
| `TranscriptionService.java` | 150 |
| `package-info.java` | 5 |
| **Total** | ~240 |

---

## 4. Cron Service (`com.nanobot.cron`)

**Python source:** `cron/service.py` (664 lines) + `cron/types.py` (84 lines)
**Java target:** ~600 lines across 5 files

### 4.1 Data Records

```java
package com.nanobot.cron;

import java.util.ArrayList;
import java.util.List;

// ── Schedule ──────────────────────────────────────────────────

public record CronSchedule(
    String kind,          // "at", "every", "cron"
    Long atMs,            // timestamp in ms for "at"
    Long everyMs,         // interval in ms for "every"
    String expr,          // cron expression for "cron"
    String tz             // IANA timezone for cron expressions
) {
    public CronSchedule {
        if (kind == null) throw new IllegalArgumentException("kind is required");
    }

    /** Create an "every" schedule. */
    public static CronSchedule every(long intervalMs) {
        return new CronSchedule("every", null, intervalMs, null, null);
    }

    /** Create an "at" schedule. */
    public static CronSchedule at(long timestampMs) {
        return new CronSchedule("at", timestampMs, null, null, null);
    }

    /** Create a "cron" schedule. */
    public static CronSchedule cron(String expr, String tz) {
        return new CronSchedule("cron", null, null, expr, tz);
    }
}

// ── Payload ───────────────────────────────────────────────────

public record CronPayload(
    String kind,           // "system_event", "agent_turn"
    String message,
    boolean deliver,
    String channel,        // e.g. "whatsapp"
    String to,             // e.g. phone number
    String channelMetaJson,// channel-specific routing metadata as JSON
    String sessionKey      // original session key for recording
) {
    public CronPayload {
        if (kind == null) kind = "agent_turn";
        if (message == null) message = "";
    }
}

// ── Run Record ────────────────────────────────────────────────

public record CronRunRecord(
    long runAtMs,
    String status,         // "ok", "error", "skipped"
    long durationMs,
    String error
) {}

// ── Job State ─────────────────────────────────────────────────

public record CronJobState(
    Long nextRunAtMs,
    Long lastRunAtMs,
    String lastStatus,
    String lastError,
    List<CronRunRecord> runHistory
) {
    public CronJobState() {
        this(null, null, null, null, new ArrayList<>());
    }
}

// ── Job ───────────────────────────────────────────────────────

public record CronJob(
    String id,
    String name,
    boolean enabled,
    CronSchedule schedule,
    CronPayload payload,
    CronJobState state,
    long createdAtMs,
    long updatedAtMs,
    boolean deleteAfterRun
) {}

// ── Store ─────────────────────────────────────────────────────

public record CronStore(
    int version,
    List<CronJob> jobs
) {
    public CronStore() {
        this(1, new ArrayList<>());
    }
}
```

### 4.2 `CronService.java` — Scheduler Service

```java
package com.nanobot.cron;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Cron service for managing and executing scheduled jobs.
 * Uses ScheduledExecutorService with virtual threads.
 *
 * Maven dependency:
 *   <groupId>com.cronutils</groupId>
 *   <artifactId>cron-utils</artifactId>
 *   <version>9.2.1</version>
 */
@Service
public class CronService {

    private static final Logger log = LoggerFactory.getLogger(CronService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path storePath;
    private final Path actionPath;
    private final ReentrantLock fileLock = new ReentrantLock();
    private final Function<CronJob, CompletableFuture<String>> onJob;

    private volatile CronStore store;
    private volatile ScheduledFuture<?> timerFuture;
    private volatile boolean running = false;
    private volatile boolean timerActive = false;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        Thread.ofVirtual().factory());
    private final long maxSleepMs;

    private static final int MAX_RUN_HISTORY = 20;
    private static final CronParser CRON_PARSER = new CronParser(
        CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

    public CronService(
            Path storePath,
            Function<CronJob, CompletableFuture<String>> onJob,
            long maxSleepMs) {
        this.storePath = storePath;
        this.actionPath = storePath.resolveSibling("action.jsonl");
        this.onJob = onJob;
        this.maxSleepMs = maxSleepMs;
    }

    // ── Lifecycle ──────────────────────────────────────────────

    public void start() {
        running = true;
        CronStore loaded = loadStore();
        if (loaded == null) {
            running = false;
            throw new RuntimeException(
                "cron store at " + storePath + " is corrupt and was preserved; " +
                "refusing to start with an empty job list.");
        }
        recomputeNextRuns();
        saveStore();
        armTimer();
        log.info("Cron service started with {} jobs", store.jobs().size());
    }

    public void stop() {
        running = false;
        if (timerFuture != null) {
            timerFuture.cancel(false);
            timerFuture = null;
        }
    }

    // ── Public API ─────────────────────────────────────────────

    public List<CronJob> listJobs(boolean includeDisabled) {
        CronStore s = loadStore();
        return s.jobs().stream()
            .filter(j -> includeDisabled || j.enabled())
            .sorted(Comparator.comparing(
                j -> j.state().nextRunAtMs() != null
                    ? j.state().nextRunAtMs() : Long.MAX_VALUE))
            .toList();
    }

    public CronJob addJob(String name, CronSchedule schedule, String message,
                          boolean deliver, String channel, String to,
                          boolean deleteAfterRun, String sessionKey) {
        validateSchedule(schedule);
        long now = nowMs();

        CronJob job = new CronJob(
            UUID.randomUUID().toString().substring(0, 8),
            name, true, schedule,
            new CronPayload("agent_turn", message, deliver, channel, to, "{}", sessionKey),
            new CronJobState(computeNextRun(schedule, now), null, null, null, new ArrayList<>()),
            now, now, deleteAfterRun);

        if (running) {
            CronStore s = loadStore();
            List<CronJob> jobs = new ArrayList<>(s.jobs());
            jobs.add(job);
            store = new CronStore(s.version(), jobs);
            saveStore();
            armTimer();
        }

        log.info("Cron: added job '{}' ({})", name, job.id());
        return job;
    }

    public String removeJob(String jobId) {
        CronStore s = loadStore();
        CronJob job = s.jobs().stream()
            .filter(j -> j.id().equals(jobId)).findFirst().orElse(null);
        if (job == null) return "not_found";
        if ("system_event".equals(job.payload().kind())) return "protected";

        List<CronJob> jobs = new ArrayList<>(s.jobs());
        jobs.removeIf(j -> j.id().equals(jobId));
        store = new CronStore(s.version(), jobs);
        saveStore();
        armTimer();
        log.info("Cron: removed job {}", jobId);
        return "removed";
    }

    public CronJob enableJob(String jobId, boolean enabled) {
        CronStore s = loadStore();
        for (CronJob job : s.jobs()) {
            if (job.id().equals(jobId)) {
                CronJobState newState = new CronJobState(
                    enabled ? computeNextRun(job.schedule(), nowMs()) : null,
                    job.state().lastRunAtMs(),
                    job.state().lastStatus(),
                    job.state().lastError(),
                    job.state().runHistory()
                );
                CronJob updated = new CronJob(
                    job.id(), job.name(), enabled, job.schedule(), job.payload(),
                    newState, job.createdAtMs(), nowMs(), job.deleteAfterRun());
                List<CronJob> jobs = new ArrayList<>(s.jobs());
                jobs.replaceAll(j -> j.id().equals(jobId) ? updated : j);
                store = new CronStore(s.version(), jobs);
                saveStore();
                armTimer();
                return updated;
            }
        }
        return null;
    }

    // ── Timer Logic ────────────────────────────────────────────

    private void recomputeNextRuns() {
        if (store == null) return;
        long now = nowMs();
        List<CronJob> jobs = new ArrayList<>();
        for (CronJob job : store.jobs()) {
            if (job.enabled()) {
                CronJobState state = new CronJobState(
                    computeNextRun(job.schedule(), now),
                    job.state().lastRunAtMs(),
                    job.state().lastStatus(),
                    job.state().lastError(),
                    job.state().runHistory()
                );
                jobs.add(new CronJob(job.id(), job.name(), job.enabled(),
                    job.schedule(), job.payload(), state,
                    job.createdAtMs(), job.updatedAtMs(), job.deleteAfterRun()));
            } else {
                jobs.add(job);
            }
        }
        store = new CronStore(store.version(), jobs);
    }

    private Long getNextWakeMs() {
        if (store == null) return null;
        return store.jobs().stream()
            .filter(j -> j.enabled() && j.state().nextRunAtMs() != null)
            .mapToLong(j -> j.state().nextRunAtMs())
            .min().orElse(Long.MAX_VALUE);
    }

    private void armTimer() {
        if (timerFuture != null) {
            timerFuture.cancel(false);
        }
        if (!running) return;

        Long nextWake = getNextWakeMs();
        long delayMs = nextWake != null
            ? Math.min(maxSleepMs, Math.max(0, nextWake - nowMs()))
            : maxSleepMs;
        long delayS = delayMs / 1000;

        // Use virtual-thread-based scheduled executor
        // In production: use ScheduledExecutorService
        timerFuture = scheduler.schedule(() -> {
            if (running) onTimer();
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void onTimer() {
        loadStore();
        if (store == null) {
            armTimer();
            return;
        }

        timerActive = true;
        try {
            long now = nowMs();
            List<CronJob> dueJobs = store.jobs().stream()
                .filter(j -> j.enabled() && j.state().nextRunAtMs() != null
                    && now >= j.state().nextRunAtMs())
                .toList();

            for (CronJob job : dueJobs) {
                executeJob(job);
            }

            saveStore();
        } finally {
            timerActive = false;
        }
        armTimer();
    }

    private void executeJob(CronJob job) {
        long startMs = nowMs();
        log.info("Cron: executing job '{}' ({})", job.name(), job.id());

        try {
            if (onJob != null) {
                onJob.apply(job).get(60, TimeUnit.SECONDS);
            }
            // Update state
            // ...
        } catch (Exception e) {
            log.error("Cron: job '{}' failed", job.name(), e);
        } finally {
            // Update lastRunAtMs, run history, next run
            // For "at" jobs: disable or delete based on deleteAfterRun
        }
    }

    // ── Schedule Computation ───────────────────────────────────

    static Long computeNextRun(CronSchedule schedule, long nowMs) {
        switch (schedule.kind()) {
            case "at":
                return (schedule.atMs() != null && schedule.atMs() > nowMs)
                    ? schedule.atMs() : null;
            case "every":
                return (schedule.everyMs() != null && schedule.everyMs() > 0)
                    ? nowMs + schedule.everyMs() : null;
            case "cron":
                if (schedule.expr() == null) return null;
                try {
                    Cron cron = CRON_PARSER.parse(schedule.expr());
                    ZoneId zone = schedule.tz() != null
                        ? ZoneId.of(schedule.tz()) : ZoneId.systemDefault();
                    ZonedDateTime now = ZonedDateTime.now(zone);
                    ExecutionTime execTime = ExecutionTime.forCron(cron);
                    Optional<ZonedDateTime> next = execTime.nextExecution(now);
                    return next.map(zdt -> zdt.toInstant().toEpochMilli()).orElse(null);
                } catch (Exception e) {
                    return null;
                }
            default:
                return null;
        }
    }

    // ── Persistence ────────────────────────────────────────────

    private CronStore loadStore() {
        if (timerActive && store != null) return store;

        try {
            if (Files.exists(storePath)) {
                byte[] data = Files.readAllBytes(storePath);
                // Parse JSON into CronStore
                // ... (deserialization logic)
            }
        } catch (IOException e) {
            // Preserve corrupt file
            Path backup = storePath.resolveSibling(
                storePath.getFileName() + ".corrupt-" + System.currentTimeMillis() / 1000);
            try { Files.move(storePath, backup); } catch (IOException ignored) {}
            log.error("Failed to load cron store. Corrupt file preserved at {}.", backup, e);
            return null;
        }

        if (store == null) store = new CronStore();
        return store;
    }

    private void saveStore() {
        if (store == null) return;
        try {
            Files.createDirectories(storePath.getParent());
            Path tmp = storePath.resolveSibling(storePath.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.writeValueAsString(store), StandardCharsets.UTF_8);
            Files.move(tmp, storePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Failed to save cron store", e);
        }
    }

    // ── Helpers ────────────────────────────────────────────────

    private static long nowMs() {
        return System.currentTimeMillis();
    }

    private static void validateSchedule(CronSchedule schedule) {
        if (schedule.tz() != null && !"cron".equals(schedule.kind())) {
            throw new IllegalArgumentException("tz can only be used with cron schedules");
        }
        if ("cron".equals(schedule.kind()) && schedule.tz() != null) {
            try {
                ZoneId.of(schedule.tz());
            } catch (Exception e) {
                throw new IllegalArgumentException("unknown timezone '" + schedule.tz() + "'");
            }
        }
    }
}
```

### 4.3 Estimated Lines

| Class | Lines |
|-------|-------|
| Data records (CronSchedule, CronPayload, CronRunRecord, CronJobState, CronJob, CronStore) | 120 |
| `CronService.java` | 400 |
| `CronConfig.java` (Spring config bean) | 40 |
| `package-info.java` | 5 |
| **Total** | ~565 |

### 4.4 Maven Dependency

```xml
<dependency>
    <groupId>com.cronutils</groupId>
    <artifactId>cron-utils</artifactId>
    <version>9.2.1</version>
</dependency>
```

---

## 5. Pairing Store (`com.nanobot.pairing`)

**Python source:** `pairing/store.py` (255 lines)
**Java target:** `PairingStore.java` (~200 lines)

### 5.1 Full `PairingStore.java`

```java
package com.nanobot.pairing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Persistent pairing store for DM sender approval.
 *
 * Stores approved senders and pending pairing codes per channel
 * at ~/.nanobot/pairing.json. Uses ReentrantLock for thread safety.
 */
public class PairingStore {

    private static final Logger log = LoggerFactory.getLogger(PairingStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        + "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toLowerCase() + "0123456789";
    private static final int CODE_LENGTH = 8;  // e.g. ABCD-EFGH
    private static final long TTL_DEFAULT_MS = 600_000;  // 10 minutes

    private final Path storePath;
    private final ReentrantLock lock = new ReentrantLock();

    public PairingStore(Path storePath) {
        this.storePath = storePath;
    }

    // ── Data structure ─────────────────────────────────────────

    private record PairingStoreData(
        Map<String, Set<String>> approved,  // channel -> set of sender IDs
        Map<String, PendingEntry> pending    // code -> PendingEntry
    ) {
        PairingStoreData() {
            this(new LinkedHashMap<>(), new LinkedHashMap<>());
        }
    }

    private record PendingEntry(
        String channel,
        String senderId,
        long createdAtMs,
        long expiresAtMs
    ) {}

    // ── Load / Save ────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private PairingStoreData load() {
        try {
            if (!Files.exists(storePath)) return new PairingStoreData();
            TypeReference<Map<String, Map<String, Object>>> typeRef = new TypeReference<>() {};
            Map<String, Map<String, Object>> raw = JSON.readValue(
                Files.readString(storePath), typeRef);

            Map<String, Set<String>> approved = new LinkedHashMap<>();
            Map<String, PendingEntry> pending = new LinkedHashMap<>();

            Map<String, Object> approvedRaw = raw.getOrDefault("approved", Map.of());
            for (Map.Entry<String, Object> e : approvedRaw.entrySet()) {
                if (e.getValue() instanceof List<?> list) {
                    Set<String> users = new LinkedHashSet<>();
                    for (Object u : list) users.add(u.toString());
                    approved.put(e.getKey(), users);
                }
            }

            Map<String, Object> pendingRaw = raw.getOrDefault("pending", Map.of());
            for (Map.Entry<String, Object> e : pendingRaw.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> pm) {
                    Map<String, Object> m = (Map<String, Object>) pm;
                    pending.put(e.getKey(), new PendingEntry(
                        Objects.toString(m.get("channel"), ""),
                        Objects.toString(m.get("sender_id"), ""),
                        ((Number) m.getOrDefault("created_at", 0L)).longValue(),
                        ((Number) m.getOrDefault("expires_at", 0L)).longValue()
                    ));
                }
            }

            return new PairingStoreData(approved, pending);
        } catch (IOException e) {
            log.warn("Corrupted pairing store, resetting");
            return new PairingStoreData();
        }
    }

    private void save(PairingStoreData data) {
        try {
            Files.createDirectories(storePath.getParent());
            Map<String, Object> payload = new LinkedHashMap<>();
            Map<String, List<String>> approvedJson = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> e : data.approved().entrySet()) {
                List<String> sorted = new ArrayList<>(e.getValue());
                Collections.sort(sorted);
                approvedJson.put(e.getKey(), sorted);
            }
            payload.put("approved", approvedJson);
            payload.put("pending", data.pending());
            // Atomic write with tmp file
            Path tmp = storePath.resolveSibling("." + storePath.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(payload), StandardCharsets.UTF_8);
            Files.move(tmp, storePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Failed to save pairing store", e);
        }
    }

    private void gcPending(PairingStoreData data) {
        long now = System.currentTimeMillis();
        data.pending().entrySet().removeIf(
            e -> e.getValue().expiresAtMs() < now);
    }

    // ── Public API ─────────────────────────────────────────────

    /** Create a new pairing code for a sender on a channel. */
    public String generateCode(String channel, String senderId, long ttlMs) {
        lock.lock();
        try {
            PairingStoreData data = load();
            gcPending(data);

            StringBuilder raw = new StringBuilder();
            for (int i = 0; i < CODE_LENGTH; i++) {
                raw.append(ALPHABET.charAt(SECURE_RANDOM.nextInt(ALPHABET.length())));
            }
            String code = raw.substring(0, 4) + "-" + raw.substring(4);

            long now = System.currentTimeMillis();
            data.pending().put(code, new PendingEntry(
                channel, senderId, now, now + ttlMs));
            save(data);
            log.info("Generated pairing code {} for {}@{}", code, senderId, channel);
            return code;
        } finally {
            lock.unlock();
        }
    }

    public String generateCode(String channel, String senderId) {
        return generateCode(channel, senderId, TTL_DEFAULT_MS);
    }

    /** Approve a pending pairing code. Returns (channel, senderId) or null. */
    public Pair<String, String> approveCode(String code) {
        lock.lock();
        try {
            PairingStoreData data = load();
            gcPending(data);
            PendingEntry info = data.pending().remove(code);
            if (info == null) return null;

            data.approved()
                .computeIfAbsent(info.channel(), k -> new LinkedHashSet<>())
                .add(info.senderId());
            save(data);
            log.info("Approved pairing code {} for {}@{}",
                code, info.senderId(), info.channel());
            return Pair.of(info.channel(), info.senderId());
        } finally {
            lock.unlock();
        }
    }

    /** Reject a pending pairing code. */
    public boolean denyCode(String code) {
        lock.lock();
        try {
            PairingStoreData data = load();
            gcPending(data);
            if (data.pending().remove(code) != null) {
                save(data);
                log.info("Denied pairing code {}", code);
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** Check if a sender is approved on a channel. */
    public boolean isApproved(String channel, String senderId) {
        lock.lock();
        try {
            PairingStoreData data = load();
            Set<String> users = data.approved().get(channel);
            return users != null && users.contains(senderId.toString());
        } finally {
            lock.unlock();
        }
    }

    /** List all non-expired pending pairing requests. */
    public List<Map<String, Object>> listPending() {
        lock.lock();
        try {
            PairingStoreData data = load();
            gcPending(data);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map.Entry<String, PendingEntry> e : data.pending().entrySet()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("code", e.getKey());
                item.put("channel", e.getValue().channel());
                item.put("sender_id", e.getValue().senderId());
                item.put("created_at", e.getValue().createdAtMs());
                item.put("expires_at", e.getValue().expiresAtMs());
                result.add(item);
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    /** Revoke an approved sender. */
    public boolean revoke(String channel, String senderId) {
        lock.lock();
        try {
            PairingStoreData data = load();
            Set<String> users = data.approved().get(channel);
            if (users != null && users.remove(senderId)) {
                if (users.isEmpty()) data.approved().remove(channel);
                save(data);
                log.info("Revoked {} from {}", senderId, channel);
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** Get all approved sender IDs for a channel. */
    public List<String> getApproved(String channel) {
        lock.lock();
        try {
            PairingStoreData data = load();
            Set<String> users = data.approved().getOrDefault(channel, Set.of());
            List<String> sorted = new ArrayList<>(users);
            Collections.sort(sorted);
            return sorted;
        } finally {
            lock.unlock();
        }
    }

    // ── Formatting ─────────────────────────────────────────────

    public static String formatPairingReply(String code) {
        return "Hi there! This assistant only responds to approved users.\n\n"
            + "Your pairing code is: `" + code + "`\n\n"
            + "To get access, ask the owner to approve this code:\n"
            + "- In this chat: send `/pairing approve " + code + "`";
    }

    public static String formatExpiry(long expiresAtMs) {
        long remaining = (expiresAtMs - System.currentTimeMillis()) / 1000;
        return remaining > 0 ? remaining + "s" : "expired";
    }
}
```

### 5.2 Estimated Lines

| Class | Lines |
|-------|-------|
| `PairingStore.java` | 200 |
| `PairingCommandHandler.java` (slash command handling) | 80 |
| `package-info.java` | 5 |
| **Total** | ~285 |

---

## 6. Web Package Placeholder (`com.nanobot.web`)

**Python source:** `web/__init__.py` (empty)
**Java target:** Just a `package-info.java`

```java
/**
 * Web package.
 * Historically a routing/static file package. In the Java port,
 * static WebUI assets are served by Spring Boot from classpath:/static/
 * or by an external web server (nginx/caddy).
 */
package com.nanobot.web;
```

---

## 7. Complete File Layout for Remaining Packages

```
src/main/java/com/nanobot/
  api/
    OpenAiApiServer.java       (~350 lines)
    package-info.java          (~5 lines)
  apps/
    AppManifest.java           (~50 lines)
    CliAppService.java         (~500 lines)
    CliInstaller.java          (~200 lines)
    package-info.java          (~5 lines)
  audio/
    TranscriptionProviderSpec.java  (~25 lines)
    TranscriptionRegistry.java      (~40 lines)
    EffectiveTranscriptionConfig.java (~20 lines)
    TranscriptionService.java       (~150 lines)
    package-info.java               (~5 lines)
  cron/
    cron-data-records.java     (inline or multi-file) (~120 lines)
    CronService.java           (~400 lines)
    CronConfig.java            (~40 lines)
    package-info.java          (~5 lines)
  pairing/
    PairingStore.java          (~200 lines)
    PairingCommandHandler.java (~80 lines)
    package-info.java          (~5 lines)
  web/
    package-info.java          (~5 lines)
```

---

## 8. Line Count Summary

| Package | Python Lines | Java Estimated Lines |
|---------|-------------|---------------------|
| API (`api/`) | 400 | 355 |
| Apps (`apps/`) | 1,350 | 755 |
| Audio (`audio/`) | 310 | 240 |
| Cron (`cron/`) | 748 | 565 |
| Pairing (`pairing/`) | 255 | 285 |
| Web (`web/`) | 0 | 5 |
| **Total Remaining** | **3,063** | **2,205** |

---

## 9. Key Design Decisions

| Package | Decision |
|---------|----------|
| API | Spring `@RestController` with `SseEmitter` for streaming; `ConcurrentHashMap<String, ReentrantLock>` for per-session locking |
| Apps | `ProcessBuilder` for subprocess execution; platform-specific installers via strategy pattern |
| Audio | Static provider registry with lazy-loaded HTTP adapters; same provider dispatch pattern as LLM providers |
| Cron | `ScheduledExecutorService` with virtual threads; cron-utils for cron expression parsing; atomic JSON file persistence |
| Pairing | JSON file store with `ReentrantLock` for thread safety; `SecureRandom` for code generation |
| Web | Placeholder only; static assets served by Spring Boot or external server |
