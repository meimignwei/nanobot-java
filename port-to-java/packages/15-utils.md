# Package 15: Utilities (`com.nanobot.utils`)

## Overview

The `nanobot/utils/` package contains 18 Python files providing general-purpose helpers, git-backed version control, file-edit event streaming, document text extraction, media decoding, template rendering, and runtime support. This document specifies the Java 21 + Spring Boot 3.2 port.

**Estimated Java lines:** ~3,200 across 12 Java files.

---

## 1. Summary Table — All 18 Utilities

| # | Python File | Lines | Java Class | Purpose | Port Strategy |
|---|-------------|-------|------------|---------|---------------|
| 1 | `helpers.py` | 639 | `Helpers.java` | Message sanitization, content extraction, MIME detection, time formatting, token estimation, tool-result persistence | Direct port. Replace tiktoken with JTokkit (`com.knuddels:jtokkit`). Replace Python `re` with `java.util.regex.Pattern`. |
| 2 | `runtime.py` | 150 | `RuntimeConstants.java` | Prompt constants, runtime state tracking | Static final strings. Simple constants class. |
| 3 | `file_edit_events.py` | 964 | `FileEditEvents.java` | File-edit event streaming — diffs, patches, WebUI notifications | Direct port. Complex state machines for streaming JSON argument parsing. |
| 4 | `prompt_templates.py` | 35 | `PromptTemplates.java` | Jinja2 template rendering | Use Pebble or Thymeleaf. Simple service class. |
| 5 | `gitstore.py` | 120 | `GitStore.java` | Git auto-commit for workspace changes using dulwich | Replace dulwich with JGit (`org.eclipse.jgit`). |
| 6 | `document.py` | 100 | `DocumentUtils.java` | DOCX/PDF/XLSX/PPTX text extraction | Apache Tika (`org.apache.tika:tika-parsers-standard-package`). |
| 7 | `path.py` | 50 | `PathUtils.java` | Path normalization, abbreviation | Direct port to Java NIO Path. |
| 8 | `media_decode.py` | 80 | `MediaDecode.java` | Base64 media decoding, MIME type detection | Direct port. Use `java.util.Base64`. |
| 9 | `artifacts.py` | 50 | `ArtifactUtils.java` | Web artifact rendering, image artifact persistence | Direct port. |
| 10 | `evaluator.py` | 50 | `ResponseEvaluator.java` | Post-run LLM evaluation for background tasks | Port with provider abstraction injected. |
| 11 | `llm_runtime.py` | 50 | `LlmRuntime.java` | LLM runtime information record | Simple JDK record. |
| 12 | `searchusage.py` | 50 | `SearchUsageInfo.java` | Search usage tracking | Record + async HTTP client. |
| 13 | `tool_hints.py` | 30 | `ToolHintFormatter.java` | Tool hint formatting for display | Direct port of formatting logic. |
| 14 | `progress_events.py` | 50 | `ProgressEvents.java` | Progress event type helpers | Direct port. |
| 15 | `restart.py` | 30 | `RestartNotice.java` | Application restart notification mechanism | System properties instead of env vars in Java. |
| 16 | `logging_bridge.py` | 30 | _(not needed)_ | Python-to-JavaScript logging bridge | Not needed in Java — SLF4J/Logback handles this natively. |
| 17 | `subagent_channel_display.py` | 50 | `SubagentDisplay.java` | Subagent status display formatting | Direct port. |
| 18 | `image_generation_intent.py` | 50 | `ImageGenIntent.java` | Image generation intent detection | Direct port. |

---

## 2. `Helpers.java` — General-Purpose Helpers (~700 lines)

### 2.1 Package & Imports

```java
package com.nanobot.utils;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
```

### 2.2 Class Structure

```java
public final class Helpers {

    private Helpers() { /* utility class */ }

    // ── Constants ──────────────────────────────────────────────
    private static final Pattern UNSAFE_CHARS = Pattern.compile("[<>:\"/\\\\|?*]");
    private static final int TOOL_RESULT_PREVIEW_CHARS = 1200;
    private static final String TOOL_RESULTS_DIR = ".nanobot/tool-results";
    private static final long TOOL_RESULT_RETENTION_SECS = 7 * 24 * 60 * 60;
    private static final int TOOL_RESULT_MAX_BUCKETS = 32;

    private static final Pattern THINK_BLOCK = Pattern.compile(
        "<think>[\\s\\S]*?</think>"
    );
    private static final Pattern THINK_OPEN_PREFIX = Pattern.compile(
        "^\\s*<think>[\\s\\S]*$"
    );
    private static final Pattern THOUGHT_BLOCK = Pattern.compile(
        "<thought>[\\s\\S]*?</thought>"
    );
    private static final Pattern THOUGHT_OPEN_PREFIX = Pattern.compile(
        "^\\s*<thought>[\\s\\S]*$"
    );
    // Malformed opening tags: <think / <thought where next char is NOT
    // a valid tag-name continuation (ASCII letters, digits, _, -, :, >, /)
    private static final Pattern THINK_MALFORMED = Pattern.compile(
        "<think(?![A-Za-z0-9_\\-:>/])"
    );
    private static final Pattern THOUGHT_MALFORMED = Pattern.compile(
        "<thought(?![A-Za-z0-9_\\-:>/])"
    );
    private static final Pattern ORPHAN_CLOSE_THINK_START = Pattern.compile(
        "^\\s*</think>\\s*"
    );
    private static final Pattern ORPHAN_CLOSE_THINK_END = Pattern.compile(
        "\\s*</think>\\s*$"
    );
    private static final Pattern ORPHAN_CLOSE_THOUGHT_START = Pattern.compile(
        "^\\s*</thought>\\s*"
    );
    private static final Pattern ORPHAN_CLOSE_THOUGHT_END = Pattern.compile(
        "\\s*</thought>\\s*$"
    );
    private static final Pattern CHANNEL_MARKER_START = Pattern.compile(
        "^\\s*<\\|?channel\\|?>\\s*"
    );

    // MIME detection magic bytes
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF87A_MAGIC = "GIF87a".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GIF89A_MAGIC = "GIF89a".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RIFF_MAGIC = "RIFF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WEBP_MAGIC = "WEBP".getBytes(StandardCharsets.US_ASCII);

    // ── Thinking Block Stripping ───────────────────────────────

    /**
     * Remove &lt;think&gt; / &lt;thought&gt; blocks, unclosed streaming prefixes,
     * malformed opening tags, trailing partial control tags, and channel markers.
     */
    public static String stripThink(String text) {
        if (text == null || text.isEmpty()) return "";

        String result = text;
        // Well-formed blocks
        result = THINK_BLOCK.matcher(result).replaceAll("");
        result = THINK_OPEN_PREFIX.matcher(result).replaceAll("");
        result = THOUGHT_BLOCK.matcher(result).replaceAll("");
        result = THOUGHT_OPEN_PREFIX.matcher(result).replaceAll("");
        // Malformed opening tags
        result = THINK_MALFORMED.matcher(result).replaceAll("");
        result = THOUGHT_MALFORMED.matcher(result).replaceAll("");
        // Edge-only orphan closing tags
        result = ORPHAN_CLOSE_THINK_START.matcher(result).replaceAll("");
        result = ORPHAN_CLOSE_THINK_END.matcher(result).replaceAll("");
        result = ORPHAN_CLOSE_THOUGHT_START.matcher(result).replaceAll("");
        result = ORPHAN_CLOSE_THOUGHT_END.matcher(result).replaceAll("");
        // Channel markers
        result = CHANNEL_MARKER_START.matcher(result).replaceAll("");
        // Partial control tags at end
        result = stripPartialControlTags(result);
        return result.strip();
    }

    private static final Pattern PARTIAL_CONTROL_END = Pattern.compile(
        "(?:</?(?:t|th|thi|thin|think|tho|thou|thoug|though|thought)>?|<\\|?(?:c|ch|cha|chan|chann|channe|channel)(?:\\|?>?)?)$"
    );

    private static String stripPartialControlTags(String text) {
        return PARTIAL_CONTROL_END.matcher(text).replaceAll("");
    }

    /**
     * Extract thinking content from inline &lt;think&gt; / &lt;thought&gt; blocks.
     * Returns a record with (thinkingText, cleanedText).
     */
    public static ThinkExtraction extractThink(String text) {
        List<String> parts = new ArrayList<>();
        Pattern extractPattern = Pattern.compile("<think>([\\s\\S]*?)</think>");
        java.util.regex.Matcher m = extractPattern.matcher(text);
        while (m.find()) {
            parts.add(m.group(1).strip());
        }
        // Repeat for <thought>
        m = Pattern.compile("<thought>([\\s\\S]*?)</thought>").matcher(text);
        while (m.find()) {
            parts.add(m.group(1).strip());
        }
        String thinking = parts.isEmpty() ? null : String.join("\n\n", parts);
        return new ThinkExtraction(thinking, stripThink(text));
    }

    public record ThinkExtraction(String thinking, String cleanedText) {}

    // ── MIME Detection ─────────────────────────────────────────

    /**
     * Detect image MIME type from magic bytes, ignoring file extension.
     * Returns null if unrecognized.
     */
    public static String detectImageMime(byte[] data) {
        if (startsWith(data, PNG_MAGIC))   return "image/png";
        if (startsWith(data, JPEG_MAGIC))  return "image/jpeg";
        if (startsWith(data, GIF87A_MAGIC) || startsWith(data, GIF89A_MAGIC))
            return "image/gif";
        if (data.length >= 12
            && startsWith(data, RIFF_MAGIC)
            && startsWith(data, 8, WEBP_MAGIC))
            return "image/webp";
        return null;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }

    private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
        if (data.length < offset + prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[offset + i] != prefix[i]) return false;
        }
        return true;
    }

    // ── Image Content Blocks ───────────────────────────────────

    public static List<Map<String, Object>> buildImageContentBlocks(
            byte[] raw, String mime, String path, String label) {
        String b64 = Base64.getEncoder().encodeToString(raw);
        return List.of(
            Map.of(
                "type", "image_url",
                "image_url", Map.of("url", "data:" + mime + ";base64," + b64),
                "_meta", Map.of("path", path)
            ),
            Map.of("type", "text", "text", label)
        );
    }

    // ── Time Formatting ────────────────────────────────────────

    /** Current ISO-8601 timestamp. */
    public static String timestamp() {
        return Instant.now().toString();
    }

    /**
     * Human-readable current time string with timezone offset.
     * Example: "2026-06-21 14:30 (Monday) (America/Los_Angeles, UTC-07:00)"
     */
    public static String currentTimeStr(String timezone) {
        ZoneId zone;
        try {
            zone = timezone != null ? ZoneId.of(timezone) : ZoneId.systemDefault();
        } catch (Exception e) {
            zone = ZoneId.systemDefault();
        }
        ZonedDateTime now = ZonedDateTime.now(zone);
        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEEE)", Locale.ENGLISH);
        String datePart = now.format(dateFmt);
        String offsetPart = now.getOffset().toString(); // e.g. "+05:00" or "+05:00:00"
        String tzName = timezone != null ? timezone : zone.getId();
        return datePart + " (" + tzName + ", UTC" + offsetPart + ")";
    }

    // ── Safe Filename ──────────────────────────────────────────

    public static String safeFilename(String name) {
        if (name == null || name.isEmpty()) return "unnamed";
        return UNSAFE_CHARS.matcher(name).replaceAll("_").strip();
    }

    // ── Message Splitting ──────────────────────────────────────

    public static List<String> splitMessage(String content, int maxLen) {
        if (content == null || content.isEmpty()) return List.of();
        if (content.length() <= maxLen) return List.of(content);

        List<String> chunks = new ArrayList<>();
        String remaining = content;
        while (!remaining.isEmpty()) {
            if (remaining.length() <= maxLen) {
                chunks.add(remaining);
                break;
            }
            String cut = remaining.substring(0, maxLen);
            int pos = cut.lastIndexOf('\n');
            if (pos <= 0) pos = cut.lastIndexOf(' ');
            if (pos <= 0) pos = maxLen;
            chunks.add(remaining.substring(0, pos));
            remaining = remaining.substring(pos).stripLeading();
        }
        return chunks;
    }

    // ── Text Truncation ────────────────────────────────────────

    public static String truncateText(String text, int maxChars) {
        if (maxChars <= 0 || text == null || text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n... (truncated)";
    }

    // ── Message Validation ─────────────────────────────────────

    /**
     * Find the first message index whose tool results have matching assistant calls.
     * Used to clean up orphaned tool results in message history.
     */
    public static int findLegalMessageStart(List<Map<String, Object>> messages) {
        Set<String> declared = new HashSet<>();
        int start = 0;
        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            String role = (String) msg.get("role");
            if ("assistant".equals(role)) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> toolCalls =
                    (List<Map<String, Object>>) msg.get("tool_calls");
                if (toolCalls != null) {
                    for (Map<String, Object> tc : toolCalls) {
                        Object id = tc.get("id");
                        if (id != null) declared.add(id.toString());
                    }
                }
            } else if ("tool".equals(role)) {
                Object tid = msg.get("tool_call_id");
                if (tid != null && !declared.contains(tid.toString())) {
                    start = i + 1;
                    declared.clear();
                }
            }
        }
        return start;
    }

    // ── Content Extraction ─────────────────────────────────────

    /** Extract text from a content array (multi-part). Returns null if not all text. */
    @SuppressWarnings("unchecked")
    public static String stringifyTextBlocks(List<Map<String, Object>> content) {
        if (content == null) return null;
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> block : content) {
            if (!"text".equals(block.get("type"))) return null;
            Object text = block.get("text");
            if (!(text instanceof String str)) return null;
            parts.add(str);
        }
        return String.join("\n", parts);
    }

    // ── Assistant Message Builder ──────────────────────────────

    public static Map<String, Object> buildAssistantMessage(
            String content,
            List<Map<String, Object>> toolCalls,
            String reasoningContent,
            List<Map<String, Object>> thinkingBlocks) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");
        msg.put("content", content != null ? content : "");
        if (toolCalls != null && !toolCalls.isEmpty()) {
            msg.put("tool_calls", toolCalls);
        }
        if (reasoningContent != null || (thinkingBlocks != null && !thinkingBlocks.isEmpty())) {
            msg.put("reasoning_content", reasoningContent != null ? reasoningContent : "");
        }
        if (thinkingBlocks != null && !thinkingBlocks.isEmpty()) {
            msg.put("thinking_blocks", thinkingBlocks);
        }
        return msg;
    }

    // ── Token Estimation ───────────────────────────────────────

    private static final EncodingRegistry JTOKKIT_REGISTRY = Encodings.newDefaultEncodingRegistry();
    private static final Encoding CL100K_BASE = JTOKKIT_REGISTRY.getEncoding(EncodingType.CL100K_BASE);
    private static final int PER_MESSAGE_OVERHEAD = 4;

    /** Estimate prompt tokens using JTokkit (cl100k_base). */
    @SuppressWarnings("unchecked")
    public static int estimatePromptTokens(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools) {
        try {
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> msg : messages) {
                Object content = msg.get("content");
                if (content instanceof String s) {
                    sb.append(s);
                } else if (content instanceof List<?> list) {
                    for (Object part : list) {
                        if (part instanceof Map<?, ?> pm && "text".equals(pm.get("type"))) {
                            Object txt = pm.get("text");
                            if (txt != null) sb.append(txt);
                        }
                    }
                }
                // Include tool_calls, reasoning_content, name, tool_call_id in count
                appendJsonIfPresent(sb, msg.get("tool_calls"));
                appendStringIfPresent(sb, msg.get("reasoning_content"));
                appendStringIfPresent(sb, msg.get("name"));
                appendStringIfPresent(sb, msg.get("tool_call_id"));
            }
            if (tools != null) {
                sb.append(tools.toString());
            }
            String payload = sb.toString();
            if (payload.isEmpty()) return messages.size() * PER_MESSAGE_OVERHEAD;
            return CL100K_BASE.encode(payload).size() + messages.size() * PER_MESSAGE_OVERHEAD;
        } catch (Exception e) {
            return 0;
        }
    }

    private static void appendJsonIfPresent(StringBuilder sb, Object value) {
        if (value != null) sb.append(value.toString());
    }

    private static void appendStringIfPresent(StringBuilder sb, Object value) {
        if (value instanceof String s && !s.isEmpty()) sb.append(s);
    }

    // ── Tool Result Persistence ────────────────────────────────

    /**
     * Persist oversized tool output to disk and replace with a stable
     * reference string. Returns the original content if within limits.
     */
    @SuppressWarnings("unchecked")
    public static Object maybePersistToolResult(
            Path workspace,
            String sessionKey,
            String toolCallId,
            Object content,
            int maxChars) {
        if (workspace == null || maxChars <= 0) return content;

        String textPayload = null;
        String suffix = "txt";

        if (content instanceof String s) {
            textPayload = s;
        } else if (content instanceof List<?> list) {
            textPayload = stringifyTextBlocks((List<Map<String, Object>>) content);
            if (textPayload == null) return content;
            suffix = "json";
        } else {
            return content;
        }

        if (textPayload.length() <= maxChars) return content;

        try {
            Path root = ensureDir(workspace.resolve(TOOL_RESULTS_DIR));
            Path bucket = ensureDir(root.resolve(safeFilename(sessionKey != null ? sessionKey : "default")));
            cleanupToolResultBuckets(root, bucket);

            Path filepath = bucket.resolve(safeFilename(toolCallId) + "." + suffix);
            if (!Files.exists(filepath)) {
                writeTextAtomic(filepath, textPayload);
            }

            String preview = textPayload.substring(0,
                Math.min(textPayload.length(), TOOL_RESULT_PREVIEW_CHARS));
            boolean truncated = textPayload.length() > TOOL_RESULT_PREVIEW_CHARS;

            return renderToolResultReference(filepath, textPayload.length(), preview, truncated);
        } catch (IOException e) {
            return "[tool output persisted but write failed: " + e.getMessage() + "]";
        }
    }

    private static String renderToolResultReference(
            Path filepath, int originalSize, String preview, boolean truncated) {
        StringBuilder sb = new StringBuilder();
        sb.append("[tool output persisted]\n");
        sb.append("Full output saved to: ").append(filepath).append("\n");
        sb.append("Original size: ").append(originalSize).append(" chars\n");
        sb.append("Preview:\n").append(preview);
        if (truncated) {
            sb.append("\n...\n(Read the saved file if you need the full output.)");
        }
        return sb.toString();
    }

    private static void cleanupToolResultBuckets(Path root, Path currentBucket) throws IOException {
        List<Path> siblings = Files.list(root)
            .filter(p -> Files.isDirectory(p) && !p.equals(currentBucket))
            .toList();
        long cutoff = System.currentTimeMillis() / 1000 - TOOL_RESULT_RETENTION_SECS;

        for (Path path : siblings) {
            try {
                FileTime mtime = Files.getLastModifiedTime(path);
                if (mtime.toInstant().getEpochSecond() < cutoff) {
                    deleteRecursively(path);
                }
            } catch (IOException ignored) {}
        }

        // Remove excess beyond max buckets
        siblings = Files.list(root)
            .filter(p -> Files.isDirectory(p) && !p.equals(currentBucket) && Files.exists(p))
            .sorted(Comparator.comparingLong(p -> {
                try { return Files.getLastModifiedTime(p).toInstant().getEpochSecond(); }
                catch (IOException e) { return 0L; }
            }).reversed())
            .toList();

        int keep = Math.max(TOOL_RESULT_MAX_BUCKETS - 1, 0);
        if (siblings.size() > keep) {
            for (int i = keep; i < siblings.size(); i++) {
                deleteRecursively(siblings.get(i));
            }
        }
    }

    private static void deleteRecursively(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (var stream = Files.walk(path)) {
                    stream.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
                }
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {}
    }

    public static Path ensureDir(Path path) throws IOException {
        Files.createDirectories(path);
        return path;
    }

    public static void writeTextAtomic(Path path, String content) throws IOException {
        Path tmp = path.resolveSibling("." + path.getFileName() + "." + UUID.randomUUID().toString().replace("-", "") + ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ── Retry-After Parsing ────────────────────────────────────

    private static final Pattern RETRY_AFTER_SECONDS = Pattern.compile("(\\d+)\\s*");

    /**
     * Parse a Retry-After header value to a Duration.
     * Supports both delta-seconds and HTTP-date formats.
     */
    public static Duration parseRetryAfter(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) return Duration.ZERO;
        String trimmed = headerValue.strip();
        // Try delta-seconds (integer)
        if (RETRY_AFTER_SECONDS.matcher(trimmed).matches()) {
            return Duration.ofSeconds(Long.parseLong(trimmed));
        }
        // Try HTTP-date (RFC 1123)
        try {
            DateTimeFormatter fmt = DateTimeFormatter.RFC_1123_DATE_TIME;
            ZonedDateTime retryDate = ZonedDateTime.parse(trimmed, fmt);
            Duration delay = Duration.between(ZonedDateTime.now(), retryDate);
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (Exception e) {
            return Duration.ZERO;
        }
    }

    // ── Workspace Template Sync ────────────────────────────────

    /**
     * Sync bundled templates to workspace. Creates missing files without
     * overwriting existing user files. Returns list of created relative paths.
     */
    public static List<String> syncWorkspaceTemplates(Path workspace, boolean silent) {
        // Implementation reads from classpath resources/templates/ and copies
        // missing files. See Section 4 for the resource layout.
        // ...
        return List.of(); // placeholder
    }

    // ── Status Content Builder ─────────────────────────────────

    public static String buildStatusContent(
            String version, String model, long startTimeSeconds,
            Map<String, Integer> lastUsage, int contextWindowTokens,
            int sessionMsgCount, int contextTokensEstimate,
            String searchUsageText, int activeTaskCount,
            int maxCompletionTokens) {
        long uptimeS = System.currentTimeMillis() / 1000 - startTimeSeconds;
        String uptime = uptimeS >= 3600
            ? (uptimeS / 3600) + "h " + ((uptimeS % 3600) / 60) + "m"
            : (uptimeS / 60) + "m " + (uptimeS % 60) + "s";

        int lastIn = lastUsage.getOrDefault("prompt_tokens", 0);
        int lastOut = lastUsage.getOrDefault("completion_tokens", 0);
        int cached = lastUsage.getOrDefault("cached_tokens", 0);
        int ctxTotal = Math.max(contextWindowTokens, 0);
        int ctxBudget = Math.max(ctxTotal - maxCompletionTokens - 1024, 1);
        int ctxPct = ctxBudget > 0
            ? Math.min((int) ((contextTokensEstimate / (double) ctxBudget) * 100), 999) : 0;

        String ctxUsedStr = contextTokensEstimate >= 1000
            ? (contextTokensEstimate / 1000) + "k"
            : String.valueOf(contextTokensEstimate);
        String ctxTotalStr = ctxTotal > 0 ? (ctxTotal / 1000) + "k" : "n/a";

        StringBuilder lines = new StringBuilder();
        lines.append("nanobot v").append(version).append("\n");
        lines.append("Model: ").append(model).append("\n");
        lines.append("Tokens: ").append(lastIn).append(" in / ").append(lastOut).append(" out");
        if (cached > 0 && lastIn > 0) {
            lines.append(" (").append(cached * 100 / lastIn).append("% cached)");
        }
        lines.append("\n");
        lines.append("Context: ").append(ctxUsedStr).append("/").append(ctxTotalStr)
            .append(" (").append(ctxPct).append("% of input budget)\n");
        lines.append("Session: ").append(sessionMsgCount).append(" messages\n");
        lines.append("Uptime: ").append(uptime).append("\n");
        lines.append("Tasks: ").append(activeTaskCount).append(" active");
        if (searchUsageText != null && !searchUsageText.isEmpty()) {
            lines.append("\n").append(searchUsageText);
        }
        return lines.toString();
    }
}
```

### 2.3 `IncrementalThinkExtractor.java` — Streaming Think Extraction

```java
package com.nanobot.utils;

import java.util.function.Consumer;

/**
 * Stateful inline &lt;think&gt; extractor for streaming buffers.
 * Tracks already-emitted cursor so reasoning is surfaced incrementally
 * without re-emitting earlier text.
 */
public class IncrementalThinkExtractor {

    private String emitted = "";

    public void reset() {
        this.emitted = "";
    }

    /**
     * Emit any new thinking text found in buffer.
     * Returns true if anything was emitted this call.
     *
     * @param buffer the accumulated streaming text so far
     * @param emit   callback to surface new reasoning text
     */
    public boolean feed(String buffer, Consumer<String> emit) {
        Helpers.ThinkExtraction extraction = Helpers.extractThink(buffer);
        String thinking = extraction.thinking();
        if (thinking == null || thinking.equals(emitted)) {
            return false;
        }
        String newText = thinking.substring(emitted.length()).strip();
        emitted = thinking;
        if (newText.isEmpty()) {
            return false;
        }
        emit.accept(newText);
        return true;
    }
}
```

---

## 3. `RuntimeConstants.java` — Runtime Prompt Constants (~80 lines)

```java
package com.nanobot.utils;

/**
 * Runtime prompt constants and helper methods.
 * All prompts are static final strings; no mutable state.
 */
public final class RuntimeConstants {

    private RuntimeConstants() {}

    // ── Constants ──────────────────────────────────────────────

    public static final int MAX_REPEAT_EXTERNAL_LOOKUPS = 2;
    public static final int MAX_REPEAT_WORKSPACE_VIOLATIONS = 2;

    public static final String EMPTY_FINAL_RESPONSE_MESSAGE =
        "I completed the tool steps but couldn't produce a final answer. " +
        "Please try again or narrow the task.";

    public static final String FINALIZATION_RETRY_PROMPT =
        "Please provide your response to the user based on the conversation above.";

    public static final String BUDGET_EXHAUSTED_FINALIZATION_PROMPT =
        "The tool-call budget for this turn is exhausted. Based only on the " +
        "conversation and tool results above, provide a concise final response to " +
        "the user. Do not call or request tools. Do not claim the task is complete " +
        "unless the evidence above clearly shows it is complete. State what was " +
        "done, what remains, and the best next step if anything is incomplete.";

    public static final String LENGTH_RECOVERY_PROMPT =
        "Output limit reached. Continue exactly where you left off " +
        "— no recap, no apology. Break remaining work into smaller steps if needed.";

    public static final String SUSTAINED_GOAL_CONTINUE_PROMPT =
        "You have an active sustained goal. Please continue working toward the " +
        "objective using your tools, or call complete_goal if the work is truly finished.";

    // ── Helper Methods ─────────────────────────────────────────

    public static String emptyToolResultMessage(String toolName) {
        return "(" + toolName + " completed with no output)";
    }

    public static Map<String, String> buildFinalizationRetryMessage() {
        return Map.of("role", "user", "content", FINALIZATION_RETRY_PROMPT);
    }

    public static Map<String, String> buildBudgetExhaustedFinalizationMessage() {
        return Map.of("role", "user", "content", BUDGET_EXHAUSTED_FINALIZATION_PROMPT);
    }

    public static Map<String, String> buildLengthRecoveryMessage() {
        return Map.of("role", "user", "content", LENGTH_RECOVERY_PROMPT);
    }

    public static Map<String, String> buildGoalContinueMessage(String custom) {
        return Map.of("role", "user", "content",
            custom != null ? custom : SUSTAINED_GOAL_CONTINUE_PROMPT);
    }

    // External lookup signature (for throttling repeated lookups)
    public static String externalLookupSignature(String toolName, Map<String, Object> arguments) {
        if (arguments == null) return null;
        if ("web_fetch".equals(toolName)) {
            String url = Objects.toString(arguments.get("url"), "").strip();
            if (!url.isEmpty()) return "web_fetch:" + url.toLowerCase();
        }
        if ("web_search".equals(toolName)) {
            String query = Objects.toString(
                arguments.getOrDefault("query", arguments.get("search_term")), "").strip();
            if (!query.isEmpty()) return "web_search:" + query.toLowerCase();
        }
        return null;
    }
}
```

---

## 4. `FileEditEvents.java` — File Edit Event Streaming (~700 lines)

This is the most complex utility. It tracks streaming JSON tool-call arguments and emits approximate file-edit progress events to the WebUI before the final tool execution.

### 4.1 Key Records

```java
package com.nanobot.utils;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

public final class FileEditEvents {

    private FileEditEvents() {}

    // ── Constants ──────────────────────────────────────────────

    public static final Set<String> TRACKED_FILE_EDIT_TOOLS =
        Set.of("write_file", "edit_file", "apply_patch");
    private static final int MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024;
    private static final double LIVE_EMIT_INTERVAL_S = 0.18;
    private static final int LIVE_EMIT_LINE_STEP = 24;

    // ── Data Classes ───────────────────────────────────────────

    public record FileSnapshot(
        Path path,
        boolean exists,
        String text,
        boolean unreadable,
        boolean binary,
        boolean oversized
    ) {
        public boolean countable() {
            return text != null && !binary && !oversized && !unreadable;
        }
    }

    public record FileEditTracker(
        String callId,
        String tool,
        Path path,
        String displayPath,
        FileSnapshot before
    ) {}

    // ── File Snapshot Reading ──────────────────────────────────

    public static FileSnapshot readFileSnapshot(Path path) {
        return readFileSnapshot(path, MAX_SNAPSHOT_BYTES);
    }

    public static FileSnapshot readFileSnapshot(Path path, int maxBytes) {
        try {
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                return new FileSnapshot(path, false, "", false, false, false);
            }
            long size = Files.size(path);
            if (size > maxBytes) {
                return new FileSnapshot(path, true, null, false, false, true);
            }
            byte[] raw = Files.readAllBytes(path);
            // Check for binary (null byte)
            for (byte b : raw) {
                if (b == 0) {
                    return new FileSnapshot(path, true, null, false, true, false);
                }
            }
            String text = new String(raw, StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
            return new FileSnapshot(path, true, text, false, false, false);
        } catch (IOException e) {
            return new FileSnapshot(path, Files.exists(path), null, true, false, false);
        }
    }

    // ── Diff Stats ─────────────────────────────────────────────

    /**
     * Return (added, deleted) line counts for a UTF-8 text diff.
     * Uses a simple line-based diff algorithm (no external library needed).
     */
    public static int[] lineDiffStats(String before, String after) {
        if (before == null || after == null) return new int[]{0, 0};
        if (before.isEmpty()) return new int[]{textLineCount(after), 0};

        String[] beforeLines = before.replace("\r\n", "\n").split("\n", -1);
        String[] afterLines = after.replace("\r\n", "\n").split("\n", -1);

        // Use Myers diff via java-diff-utils or a simple LCS approach
        DiffResult diff = computeMyersDiff(beforeLines, afterLines);
        return new int[]{diff.added(), diff.deleted()};
    }

    private record DiffResult(int added, int deleted) {}

    private static DiffResult computeMyersDiff(String[] a, String[] b) {
        // Simplified: use java-diff-utils (io.github.java-diff-utils:java-diff-utils)
        // In production code, use:
        //   Patch<String> patch = DiffUtils.diff(Arrays.asList(a), Arrays.asList(b));
        //   and count add/delete deltas from the patch.
        // For the spec, we describe the integration point.
        //
        // Maven dependency:
        //   <groupId>io.github.java-diff-utils</groupId>
        //   <artifactId>java-diff-utils</artifactId>
        //   <version>4.12</version>
        return new DiffResult(0, 0); // placeholder
    }

    public static int textLineCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        int lineCount = 0;
        boolean lastWasNewline = false;
        boolean lastWasCr = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\r') {
                lineCount++;
                lastWasNewline = true;
                lastWasCr = true;
            } else if (ch == '\n') {
                if (!lastWasCr) lineCount++;
                lastWasNewline = true;
                lastWasCr = false;
            } else {
                lastWasNewline = false;
                lastWasCr = false;
            }
        }
        return lastWasNewline ? lineCount : lineCount + 1;
    }

    // ── Tracker Preparation ────────────────────────────────────

    public static FileEditTracker prepareFileEditTracker(
            String callId, String toolName, Path workspace,
            Map<String, Object> params) {
        List<FileEditTracker> trackers = prepareFileEditTrackers(
            callId, toolName, workspace, params);
        return trackers.isEmpty() ? null : trackers.get(0);
    }

    public static List<FileEditTracker> prepareFileEditTrackers(
            String callId, String toolName, Path workspace,
            Map<String, Object> params) {
        if (!TRACKED_FILE_EDIT_TOOLS.contains(toolName)) {
            return List.of();
        }
        List<Path> paths = resolveFileEditPaths(toolName, workspace, params);
        List<FileEditTracker> trackers = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (Path path : paths) {
            Path resolved;
            try {
                resolved = path.toRealPath();
            } catch (IOException e) {
                resolved = path;
            }
            if (!seen.add(resolved)) continue;
            FileSnapshot before = readFileSnapshot(path);
            trackers.add(new FileEditTracker(
                callId, toolName, path,
                displayFileEditPath(path, workspace),
                before
            ));
        }
        return trackers;
    }

    // ... (remaining methods for resolveFileEditPaths, buildFileEditStartEvent,
    //      buildFileEditEndEvent, buildFileEditErrorEvent, buildFileEditLiveEvent,
    //      buildFileEditPendingEvent, StreamingFileEditTracker, etc.)
}
```

---

## 5. `GitStore.java` — Git Auto-Commit via JGit (~250 lines)

### 5.1 Full Implementation

```java
package com.nanobot.utils;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Git-backed version control for memory files.
 * Uses JGit for all Git operations (init, add, commit, log, diff, blame, revert).
 *
 * Maven dependency:
 *   <groupId>org.eclipse.jgit</groupId>
 *   <artifactId>org.eclipse.jgit</artifactId>
 *   <version>6.8.0.202311291450-r</version>
 */
public class GitStore {

    private static final Logger log = LoggerFactory.getLogger(GitStore.class);

    private final Path workspace;
    private final List<String> trackedFiles;
    private static final PersonIdent DEFAULT_IDENT =
        new PersonIdent("nanobot", "nanobot@dream");

    public GitStore(Path workspace, List<String> trackedFiles) {
        this.workspace = workspace;
        this.trackedFiles = List.copyOf(trackedFiles);
    }

    // ── Data Classes ───────────────────────────────────────────

    public record CommitInfo(
        String sha,       // Short SHA (8 chars)
        String message,
        String timestamp  // Formatted datetime
    ) {
        public String format(String diff) {
            String header = "## " + message.lines().findFirst().orElse("") + "\n"
                + "`" + sha + "` — " + timestamp + "\n";
            if (diff != null && !diff.isEmpty()) {
                return header + "\n```diff\n" + diff + "\n```";
            }
            return header + "\n(no file changes)";
        }
    }

    public record LineAge(int ageDays) {}

    // ── Initialization ─────────────────────────────────────────

    public boolean isInitialized() {
        return Files.isDirectory(workspace.resolve(".git"));
    }

    public boolean init() throws IOException {
        if (isInitialized()) return false;
        if (isInsideGitRepo()) {
            log.warn("Workspace {} is already inside a git repo; skipping nested repo init", workspace);
            return false;
        }

        try {
            // Initialize repo
            Git.init().setDirectory(workspace.toFile()).call();

            // Write .gitignore
            Path gitignore = workspace.resolve(".gitignore");
            String dreamEntries = buildGitignore();
            if (Files.exists(gitignore)) {
                String existing = Files.readString(gitignore);
                Set<String> existingLines = new HashSet<>(existing.lines().toList());
                StringBuilder merged = new StringBuilder(existing.stripTrailing());
                for (String line : dreamEntries.lines().toList()) {
                    if (!existingLines.contains(line)) {
                        merged.append("\n").append(line);
                    }
                }
                merged.append("\n");
                Files.writeString(gitignore, merged.toString());
            } else {
                Files.writeString(gitignore, dreamEntries);
            }

            // Touch tracked files so initial commit has content
            for (String rel : trackedFiles) {
                Path p = workspace.resolve(rel);
                Files.createDirectories(p.getParent());
                if (!Files.exists(p)) {
                    Files.writeString(p, "");
                }
            }

            // Initial commit
            try (Git git = Git.open(workspace.toFile())) {
                git.add().addFilepattern(".gitignore").call();
                for (String f : trackedFiles) {
                    git.add().addFilepattern(f).call();
                }
                git.commit()
                    .setMessage("init: nanobot memory store")
                    .setAuthor(DEFAULT_IDENT)
                    .setCommitter(DEFAULT_IDENT)
                    .call();
            }
            log.info("Git store initialized at {}", workspace);
            return true;
        } catch (GitAPIException e) {
            log.error("Git store init failed for {}", workspace, e);
            return false;
        }
    }

    // ── Auto-Commit ────────────────────────────────────────────

    /**
     * Stage tracked memory files and commit if there are changes.
     * Returns the short commit SHA, or null if nothing to commit.
     */
    public String autoCommit(String message) throws IOException {
        if (!isInitialized()) return null;

        try (Git git = Git.open(workspace.toFile())) {
            Status status = git.status().call();
            if (status.isClean()) return null;

            for (String f : trackedFiles) {
                git.add().addFilepattern(f).call();
            }
            RevCommit commit = git.commit()
                .setMessage(message)
                .setAuthor(DEFAULT_IDENT)
                .setCommitter(DEFAULT_IDENT)
                .call();

            String sha = commit.getId().abbreviate(8).name();
            log.debug("Git auto-commit: {} ({})", sha, message);
            return sha;
        } catch (GitAPIException e) {
            log.error("Git auto-commit failed: {}", message, e);
            return null;
        }
    }

    // ── Log ────────────────────────────────────────────────────

    public List<CommitInfo> log(int maxEntries) throws IOException {
        if (!isInitialized()) return List.of();

        try (Git git = Git.open(workspace.toFile());
             RevWalk walk = new RevWalk(git.getRepository())) {

            ObjectId head = git.getRepository().resolve("HEAD");
            if (head == null) return List.of();
            walk.markStart(walk.parseCommit(head));

            List<CommitInfo> entries = new ArrayList<>();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            for (RevCommit commit : walk) {
                if (entries.size() >= maxEntries) break;
                String ts = Instant.ofEpochSecond(commit.getCommitTime())
                    .atZone(ZoneId.systemDefault())
                    .format(fmt);
                String msg = commit.getFullMessage().trim();
                entries.add(new CommitInfo(
                    commit.getId().abbreviate(8).name(), msg, ts));
            }
            return entries;
        } catch (GitAPIException e) {
            log.error("Git log failed", e);
            return List.of();
        }
    }

    // ── Line Ages (Blame) ──────────────────────────────────────

    public List<LineAge> lineAges(String filePath) throws IOException {
        if (!isInitialized()) return List.of();

        Path target = workspace.resolve(filePath);
        if (!Files.exists(target) || Files.size(target) == 0) return List.of();

        // JGit blame is available via BlameCommand
        // For simplicity, the spec notes the integration:
        //   BlameResult result = git.blame().setFilePath(filePath).call();
        //   and compute age_days per line.
        return List.of(); // placeholder
    }

    // ── Diff ───────────────────────────────────────────────────

    public String diffCommits(String sha1, String sha2) throws IOException {
        if (!isInitialized()) return "";
        // Resolve both SHAs and use JGit DiffFormatter to produce a unified diff
        // ...
        return ""; // placeholder
    }

    // ── Find Commit ────────────────────────────────────────────

    public CommitInfo findCommit(String shortSha, int maxEntries) throws IOException {
        for (CommitInfo c : log(maxEntries)) {
            if (c.sha().startsWith(shortSha)) return c;
        }
        return null;
    }

    // ── Revert ─────────────────────────────────────────────────

    public String revert(String commitSha) throws IOException {
        if (!isInitialized()) return null;

        try (Git git = Git.open(workspace.toFile());
             RevWalk walk = new RevWalk(git.getRepository())) {

            ObjectId fullId = git.getRepository().resolve(commitSha);
            if (fullId == null) {
                log.warn("Git revert: SHA not found: {}", commitSha);
                return null;
            }

            RevCommit commit = walk.parseCommit(fullId);
            if (commit.getParentCount() == 0) {
                log.warn("Git revert: cannot revert root commit {}", commitSha);
                return null;
            }

            RevCommit parent = walk.parseCommit(commit.getParent(0));
            try (TreeWalk treeWalk = new TreeWalk(git.getRepository())) {
                treeWalk.addTree(parent.getTree());
                treeWalk.setRecursive(true);

                // Restore each tracked file from parent tree
                for (String filePath : trackedFiles) {
                    // Walk parent tree, find blob matching filePath,
                    // write content to workspace
                }
            }

            String msg = "revert: undo " + commitSha;
            return autoCommit(msg);
        } catch (GitAPIException e) {
            log.error("Git revert failed for {}", commitSha, e);
            return null;
        }
    }

    // ── Internal Helpers ───────────────────────────────────────

    private boolean isInsideGitRepo() {
        Path current = workspace.toAbsolutePath();
        while (current != null && current.getParent() != null) {
            if (Files.exists(current.resolve(".git"))) return true;
            current = current.getParent();
        }
        return false;
    }

    private String buildGitignore() {
        Set<String> dirs = new LinkedHashSet<>();
        for (String f : trackedFiles) {
            Path parent = Path.of(f).getParent();
            if (parent != null && !parent.toString().equals("")) {
                dirs.add(parent.toString());
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("/*\n");
        for (String d : dirs.stream().sorted().toList()) {
            sb.append("!").append(d).append("/\n");
        }
        for (String f : trackedFiles) {
            sb.append("!").append(f).append("\n");
        }
        sb.append("!.gitignore\n");
        return sb.toString();
    }
}
```

### 5.2 Maven Dependencies

```xml
<!-- JGit -->
<dependency>
    <groupId>org.eclipse.jgit</groupId>
    <artifactId>org.eclipse.jgit</artifactId>
    <version>6.8.0.202311291450-r</version>
</dependency>
```

---

## 6. `DocumentUtils.java` — Apache Tika Integration (~150 lines)

```java
package com.nanobot.utils;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Document text extraction using Apache Tika.
 * Supports PDF, DOCX, XLSX, PPTX and plain-text formats.
 *
 * Maven dependency:
 *   <groupId>org.apache.tika</groupId>
 *   <artifactId>tika-parsers-standard-package</artifactId>
 *   <version>2.9.1</version>
 */
public final class DocumentUtils {

    private static final Logger log = LoggerFactory.getLogger(DocumentUtils.class);
    private static final Tika TIKA = new Tika();
    private static final int MAX_TEXT_LENGTH = 200_000;
    private static final long MAX_EXTRACT_FILE_SIZE = 50L * 1024 * 1024; // 50 MB

    // Supported extensions set
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
        ".pdf", ".docx", ".xlsx", ".pptx",
        ".txt", ".md", ".csv", ".json", ".xml", ".html", ".htm",
        ".log", ".yaml", ".yml", ".toml", ".ini", ".cfg",
        ".png", ".jpg", ".jpeg", ".gif", ".webp"
    );

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
        ".txt", ".md", ".csv", ".json", ".xml", ".html", ".htm",
        ".log", ".yaml", ".yml", ".toml", ".ini", ".cfg"
    );

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
        ".png", ".jpg", ".jpeg", ".gif", ".webp"
    );

    private DocumentUtils() {}

    /**
     * Extract text from a file. Returns null for unsupported types,
     * or an error string for failures.
     */
    public static String extractText(Path path) {
        if (!Files.exists(path)) {
            return "[error: file not found: " + path + "]";
        }

        String ext = getExtension(path).toLowerCase();

        if (IMAGE_EXTENSIONS.contains(ext)) {
            return "[image: " + path.getFileName() + "]";
        }

        if (TEXT_EXTENSIONS.contains(ext)) {
            return extractTextFile(path);
        }

        return extractWithTika(path);
    }

    private static String extractTextFile(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return truncate(content, MAX_TEXT_LENGTH);
        } catch (IOException e) {
            log.error("Failed to read text file {}", path, e);
            return "[error: failed to read file: " + e.getMessage() + "]";
        }
    }

    private static String extractWithTika(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            BodyContentHandler handler = new BodyContentHandler(-1); // no write limit
            Metadata metadata = new Metadata();
            Parser parser = new AutoDetectParser();
            ParseContext context = new ParseContext();
            parser.parse(is, handler, metadata, context);
            return truncate(handler.toString().trim(), MAX_TEXT_LENGTH);
        } catch (IOException | SAXException | TikaException e) {
            log.error("Failed to extract document text {}: {}", path, e.getMessage());
            return "[error: failed to extract document: " + e.getMessage() + "]";
        }
    }

    /**
     * Separate images from documents in media paths.
     * Documents (PDF, DOCX, XLSX, PPTX, plain-text) have their text
     * extracted and appended to the provided text.
     * Only image paths are kept in the returned list.
     */
    public static DocumentExtractionResult extractDocuments(
            String text, List<String> mediaPaths, long maxFileSize) {
        List<String> imagePaths = new ArrayList<>();
        List<String> docTexts = new ArrayList<>();

        for (String pathStr : mediaPaths) {
            Path p = Path.of(pathStr);
            if (!Files.isRegularFile(p)) continue;

            try {
                long size = Files.size(p);
                if (size > maxFileSize) {
                    log.warn("Skipping oversized file for extraction: {} ({} MB > {} MB limit)",
                        p.getFileName(), size / (1024 * 1024), maxFileSize / (1024 * 1024));
                    continue;
                }
            } catch (IOException e) {
                continue;
            }

            if (isImageFile(pathStr)) {
                imagePaths.add(pathStr);
            } else {
                String extracted = extractText(p);
                if (extracted != null && !extracted.startsWith("[error:")) {
                    docTexts.add("[File: " + p.getFileName() + "]\n" + extracted);
                }
            }
        }

        String resultText = text;
        if (!docTexts.isEmpty()) {
            resultText = text + "\n\n" + String.join("\n\n", docTexts);
        }
        return new DocumentExtractionResult(resultText, imagePaths);
    }

    public record DocumentExtractionResult(String text, List<String> imagePaths) {}

    public static boolean isImageFile(String path) {
        Path p = Path.of(path);
        String ext = getExtension(p).toLowerCase();
        if (IMAGE_EXTENSIONS.contains(ext)) return true;

        // Try magic-byte detection
        if (Files.isRegularFile(p)) {
            try {
                byte[] head = Files.readAllBytes(p);
                byte[] first16 = head.length > 16
                    ? Arrays.copyOf(head, 16) : head;
                return Helpers.detectImageMime(first16) != null;
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    private static String getExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }

    public static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) return text;
        return text.substring(0, maxLength)
            + "... (truncated, " + text.length() + " chars total)";
    }
}
```

### 6.1 Maven Dependencies

```xml
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-parsers-standard-package</artifactId>
    <version>2.9.1</version>
</dependency>
```

---

## 7. Remaining Utility Classes (Brief Specs)

### 7.1 `MediaDecode.java` (~100 lines)

```java
package com.nanobot.utils;

import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public final class MediaDecode {

    private static final int DEFAULT_MAX_BYTES = 10 * 1024 * 1024;
    private static final Pattern DATA_URL_RE =
        Pattern.compile("^data:([^;,]+)(?:;[^,]*)*;base64,(.+)$", Pattern.DOTALL);

    private static final Map<String, String> MIME_EXTENSION_OVERRIDES = Map.ofEntries(
        Map.entry("application/ogg", ".ogg"),
        Map.entry("audio/ogg", ".ogg"),
        Map.entry("audio/mpga", ".mpga"),
        Map.entry("audio/wav", ".wav"),
        Map.entry("audio/webm", ".webm"),
        Map.entry("audio/x-m4a", ".m4a"),
        Map.entry("audio/x-wav", ".wav"),
        Map.entry("audio/vnd.wave", ".wav"),
        Map.entry("video/webm", ".webm")
    );

    public static class FileSizeExceededException extends Exception {
        public FileSizeExceededException(String message) { super(message); }
    }

    /**
     * Decode a data:<mime>;base64,<payload> URL and persist to disk.
     */
    public static String saveBase64DataUrl(String dataUrl, Path mediaDir, Integer maxBytes)
            throws FileSizeExceededException {
        var m = DATA_URL_RE.matcher(dataUrl);
        if (!m.matches()) return null;

        String mimeType = m.group(1).strip().toLowerCase();
        try {
            byte[] raw = Base64.getDecoder().decode(m.group(2));
            int limit = maxBytes != null ? maxBytes : DEFAULT_MAX_BYTES;
            if (raw.length > limit) {
                throw new FileSizeExceededException(
                    "File exceeds " + (limit / (1024 * 1024)) + "MB limit");
            }
            String ext = MIME_EXTENSION_OVERRIDES.getOrDefault(mimeType, ".bin");
            String filename = UUID.randomUUID().toString().replace("-", "").substring(0, 12) + ext;
            Path dest = mediaDir.resolve(Helpers.safeFilename(filename));
            Files.write(dest, raw);
            return dest.toString();
        } catch (IllegalArgumentException e) {
            return null; // invalid base64
        } catch (IOException e) {
            return null;
        }
    }
}
```

### 7.2 `PathUtils.java` (~80 lines)

```java
package com.nanobot.utils;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class PathUtils {

    /** Abbreviate a file path or URL, preserving basename and key directories. */
    public static String abbreviatePath(String path, int maxLen) {
        if (path == null || path.isEmpty()) return path;
        if (path.matches("^https?://.*")) return abbreviateUrl(path, maxLen);

        // Normalize separators, replace home directory
        String normalized = path.replace("\\", "/");
        String home = System.getProperty("user.home").replace("\\", "/");
        if (normalized.startsWith(home + "/")) normalized = "~" + normalized.substring(home.length());
        else if (normalized.equals(home)) normalized = "~";

        if (normalized.length() <= maxLen) return normalized;

        String[] parts = normalized.replaceAll("/$", "").split("/");
        if (parts.length <= 1) return normalized.substring(0, maxLen - 1) + "…";

        String basename = parts[parts.length - 1];
        int budget = maxLen - basename.length() - 3; // "…/" + "/"

        List<String> kept = new ArrayList<>();
        for (int i = parts.length - 2; i >= 0 && budget > 0; i--) {
            String seg = parts[i];
            int needed = seg.length() + 1;
            if (needed <= budget) {
                kept.add(0, seg);
                budget -= needed;
            } else break;
        }
        if (!kept.isEmpty()) return "…/" + String.join("/", kept) + "/" + basename;
        return "…/" + basename;
    }

    private static String abbreviateUrl(String url, int maxLen) { /* similar logic */ return url; }
}
```

### 7.3 `PromptTemplates.java` (~60 lines)

Uses **Pebble** template engine. Loads templates from `classpath:templates/agent/`.

```xml
<!-- Maven -->
<dependency>
    <groupId>io.pebbletemplates</groupId>
    <artifactId>pebble-spring-boot-starter</artifactId>
    <version>3.2.2</version>
</dependency>
```

### 7.4 Other Small Classes

| Class | Lines | Key Methods |
|-------|-------|-------------|
| `ArtifactUtils.java` | 80 | `decodeImageDataUrl()`, `storeGeneratedImageArtifact()`, `generatedImageToolResult()` |
| `ResponseEvaluator.java` | 80 | `evaluateResponse()` — async LLM call to decide notification-worthiness |
| `LlmRuntime.java` | 20 | Record `(LLMProvider provider, String model)` plus resolver lambda |
| `SearchUsageInfo.java` | 80 | Record with usage counters, `format()` for /status, `fetchSearchUsage()` via HttpClient |
| `ToolHintFormatter.java` | 100 | `formatToolHints(List<ToolCall>, int maxLength)` — formats tool calls as "read path", "$ cmd", "search \"query\"" |
| `ProgressEvents.java` | 80 | `buildToolEventStartPayload()`, `buildToolEventFinishPayloads()`, reflection-based parameter inspection |
| `RestartNotice.java` | 60 | Record with env-based persisting (use System properties in Java) |
| `SubagentDisplay.java` | 60 | `scrubSubagentAnnounceBody()` — strips internal inject scaffolding for channel surfaces |
| `ImageGenIntent.java` | 40 | `imageGenerationPrompt()` — decorates user prompt when WebUI image mode is enabled |

---

## 8. Maven Dependencies Summary

```xml
<dependencies>
    <!-- Token estimation (replaces tiktoken) -->
    <dependency>
        <groupId>com.knuddels</groupId>
        <artifactId>jtokkit</artifactId>
        <version>0.6.1</version>
    </dependency>

    <!-- Git operations (replaces dulwich) -->
    <dependency>
        <groupId>org.eclipse.jgit</groupId>
        <artifactId>org.eclipse.jgit</artifactId>
        <version>6.8.0.202311291450-r</version>
    </dependency>

    <!-- Document text extraction (replaces pypdf, python-docx, openpyxl, python-pptx) -->
    <dependency>
        <groupId>org.apache.tika</groupId>
        <artifactId>tika-parsers-standard-package</artifactId>
        <version>2.9.1</version>
    </dependency>

    <!-- Template engine (replaces Jinja2) -->
    <dependency>
        <groupId>io.pebbletemplates</groupId>
        <artifactId>pebble-spring-boot-starter</artifactId>
        <version>3.2.2</version>
    </dependency>

    <!-- Diff algorithm (for file_edit_events) -->
    <dependency>
        <groupId>io.github.java-diff-utils</groupId>
        <artifactId>java-diff-utils</artifactId>
        <version>4.12</version>
    </dependency>
</dependencies>
```

---

## 9. File Layout

```
src/main/java/com/nanobot/utils/
  Helpers.java                 (~700 lines)
  IncrementalThinkExtractor.java (~50 lines)
  RuntimeConstants.java        (~80 lines)
  FileEditEvents.java          (~700 lines)
  GitStore.java                (~250 lines)
  DocumentUtils.java           (~150 lines)
  MediaDecode.java             (~100 lines)
  PathUtils.java               (~80 lines)
  PromptTemplates.java         (~60 lines)
  ArtifactUtils.java           (~80 lines)
  ResponseEvaluator.java       (~80 lines)
  LlmRuntime.java              (~20 lines)
  SearchUsageInfo.java         (~80 lines)
  ToolHintFormatter.java       (~100 lines)
  ProgressEvents.java          (~80 lines)
  RestartNotice.java           (~60 lines)
  SubagentDisplay.java         (~60 lines)
  ImageGenIntent.java          (~40 lines)
  package-info.java            (~5 lines)
```

Total estimated: ~2,875 lines (not counting `logging_bridge.py` which is omitted, and including `package-info.java`).
