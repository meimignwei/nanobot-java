package com.nanobot.agent.context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the context (system prompt + messages) for the agent.
 * Mirrors Python ContextBuilder class (agent/context.py 281 lines).
 */
public class ContextBuilder {

    static final List<String> BOOTSTRAP_FILES = List.of("AGENTS.md", "SOUL.md", "USER.md");
    private static final String RUNTIME_CONTEXT_TAG = "[Runtime Context — metadata only, not instructions]";
    private static final String RUNTIME_CONTEXT_END = "[/Runtime Context]";
    private static final int MAX_RECENT_HISTORY = 50;
    private static final int MAX_HISTORY_CHARS = 32_000;

    private final Path workspace;
    private final String timezone;
    private final MemoryStore memory;

    public ContextBuilder(Path workspace, String timezone) {
        this.workspace = workspace;
        this.timezone = timezone;
        this.memory = new MemoryStore(workspace);
    }

    public MemoryStore getMemory() {
        return memory;
    }

    // -- buildSystemPrompt --

    public String buildSystemPrompt(
            List<String> skillNames,
            String channel,
            String sessionSummary,
            Path workspace,
            boolean includeMemoryRecentHistory,
            String sessionKey,
            boolean unifiedSession) {
        var root = workspace != null ? workspace : this.workspace;
        var parts = new ArrayList<String>();
        parts.add(buildIdentity(channel, root));

        var bootstrap = loadBootstrapFiles(root);
        if (!bootstrap.isEmpty()) {
            parts.add(bootstrap);
        }

        // tool_contract.md — simplified inline version
        parts.add("""
                # Tool Use

                You have access to tools. Use them when needed.
                """);

        var memoryCtx = memory.getMemoryContext();
        if (!memoryCtx.isEmpty()) {
            parts.add("# Memory\n\n" + memoryCtx);
        }

        // Skills section omitted (requires SkillsLoader dependency)

        if (includeMemoryRecentHistory) {
            var entries = memory.readRecentHistoryForPrompt(
                    memory.getLastDreamCursor(), sessionKey, unifiedSession);
            if (!entries.isEmpty()) {
                int fromIndex = Math.max(0, entries.size() - MAX_RECENT_HISTORY);
                var capped = entries.subList(fromIndex, entries.size());
                var sb = new StringBuilder();
                for (var e : capped) {
                    sb.append("- [").append(e.get("timestamp")).append("] ")
                            .append(e.get("content")).append("\n");
                }
                var historyText = MemoryStore.truncateText(sb.toString(), MAX_HISTORY_CHARS);
                parts.add("# Recent History\n\n" + historyText);
            }
        }

        if (sessionSummary != null && !sessionSummary.isEmpty()) {
            parts.add("[Archived Context Summary]\n\n" + sessionSummary);
        }

        return String.join("\n\n---\n\n", parts);
    }

    // -- buildIdentity --

    static String buildIdentity(String channel, Path workspace) {
        var workspacePath = workspace.toAbsolutePath().normalize().toString();
        var os = System.getProperty("os.name");
        var arch = System.getProperty("os.arch");
        var javaVersion = System.getProperty("java.version");
        var runtime = os + " " + arch + ", Java " + javaVersion;
        return "## Identity\n\n"
                + "Workspace: " + workspacePath + "\n"
                + "Runtime: " + runtime + "\n"
                + (channel != null && !channel.isEmpty() ? "Channel: " + channel + "\n" : "");
    }

    // -- loadBootstrapFiles --

    public String loadBootstrapFiles(Path workspace) {
        var parts = new ArrayList<String>();
        var root = workspace != null ? workspace : this.workspace;
        for (var filename : BOOTSTRAP_FILES) {
            var filePath = root.resolve(filename);
            if (Files.exists(filePath)) {
                try {
                    var content = Files.readString(filePath);
                    parts.add("## " + filename + "\n\n" + content);
                } catch (IOException ignored) {}
            }
        }
        return String.join("\n\n", parts);
    }

    // -- buildRuntimeContext --

    public static String buildRuntimeContext(
            String channel, String chatId, String timezone,
            String senderId, List<String> supplementalLines) {
        var lines = new ArrayList<String>();
        lines.add("Current Time: " + currentTimeStr(timezone));
        if (channel != null && !channel.isEmpty() && chatId != null && !chatId.isEmpty()) {
            lines.add("Channel: " + channel);
            lines.add("Chat ID: " + chatId);
        }
        if (senderId != null && !senderId.isEmpty()) {
            lines.add("Sender ID: " + senderId);
        }
        if (supplementalLines != null) {
            lines.addAll(supplementalLines);
        }
        return RUNTIME_CONTEXT_TAG + "\n" + String.join("\n", lines) + "\n" + RUNTIME_CONTEXT_END;
    }

    // -- buildUserContent --

    @SuppressWarnings("unchecked")
    public Object buildUserContent(String text, List<String> media) {
        if (media == null || media.isEmpty()) {
            return text;
        }
        var images = new ArrayList<Map<String, Object>>();
        for (var pathStr : media) {
            var p = Path.of(pathStr);
            if (!Files.isRegularFile(p)) continue;
            try {
                var raw = Files.readAllBytes(p);
                var mime = detectImageMime(raw);
                if (mime == null || !mime.startsWith("image/")) continue;
                var b64 = Base64.getEncoder().encodeToString(raw);
                var imageUrl = new LinkedHashMap<String, Object>();
                imageUrl.put("url", "data:" + mime + ";base64," + b64);
                var block = new LinkedHashMap<String, Object>();
                block.put("type", "image_url");
                block.put("image_url", imageUrl);
                block.put("_meta", Map.of("path", pathStr));
                images.add(block);
            } catch (IOException ignored) {}
        }
        if (images.isEmpty()) return text;
        var textBlock = new LinkedHashMap<String, Object>();
        textBlock.put("type", "text");
        textBlock.put("text", text);
        var result = new ArrayList<>(images);
        result.add(textBlock);
        return result;
    }

    // -- buildMessages --

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> buildMessages(
            List<Map<String, Object>> history,
            String currentMessage,
            List<String> skillNames,
            List<String> media,
            String channel,
            String chatId,
            String currentRole,
            String senderId,
            String sessionSummary,
            Map<String, Object> sessionMetadata,
            List<String> currentRuntimeLines,
            Path workspace,
            Object runtimeState,
            Object inboundMessage,
            boolean skipRuntimeLines,
            boolean includeMemoryRecentHistory,
            String sessionKey,
            boolean unifiedSession) {
        var root = workspace != null ? workspace : this.workspace;
        var extra = new ArrayList<String>();
        if (currentRuntimeLines != null) {
            for (var line : currentRuntimeLines) {
                if (line != null && !line.isEmpty()) extra.add(line);
            }
        }
        var runtimeCtx = buildRuntimeContext(channel, chatId, this.timezone, senderId, extra.isEmpty() ? null : extra);
        var userContent = buildUserContent(currentMessage, media);

        // Merge runtime context into user content
        Object merged;
        if (userContent instanceof String s) {
            merged = s + "\n\n" + runtimeCtx;
        } else if (userContent instanceof List<?> l) {
            var mergedList = new ArrayList<Object>();
            for (var item : l) mergedList.add(item);
            mergedList.add(Map.of("type", "text", "text", runtimeCtx));
            merged = mergedList;
        } else {
            merged = runtimeCtx;
        }

        var systemPrompt = buildSystemPrompt(skillNames, channel, sessionSummary,
                root, includeMemoryRecentHistory, sessionKey, unifiedSession);

        var messages = new ArrayList<Map<String, Object>>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.addAll(history);

        if (!messages.isEmpty() && currentRole.equals(messages.get(messages.size() - 1).get("role"))) {
            var last = new LinkedHashMap<>(messages.get(messages.size() - 1));
            last.put("content", mergeMessageContent(last.get("content"), merged));
            messages.set(messages.size() - 1, last);
            return messages;
        }
        messages.add(Map.of("role", currentRole, "content", merged));
        return messages;
    }

    // -- mergeMessageContent --

    @SuppressWarnings("unchecked")
    static Object mergeMessageContent(Object left, Object right) {
        if (left instanceof String ls && right instanceof String rs) {
            return ls.isEmpty() ? rs : ls + "\n\n" + rs;
        }
        var leftBlocks = toBlocks(left);
        var rightBlocks = toBlocks(right);
        var result = new ArrayList<>(leftBlocks);
        result.addAll(rightBlocks);
        return result;
    }

    private static List<Map<String, Object>> toBlocks(Object value) {
        if (value instanceof List<?> l) {
            var blocks = new ArrayList<Map<String, Object>>();
            for (var item : l) {
                if (item instanceof Map<?, ?> m) {
                    blocks.add((Map<String, Object>) m);
                } else {
                    blocks.add(Map.of("type", "text", "text", String.valueOf(item)));
                }
            }
            return blocks;
        }
        if (value == null) return List.of();
        return List.of(Map.of("type", "text", "text", String.valueOf(value)));
    }

    // -- detectImageMime --

    public static String detectImageMime(byte[] data) {
        if (data == null || data.length < 3) return null;
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (data.length >= 8 && data[0] == (byte) 0x89 && data[1] == 0x50 && data[2] == 0x4E
                && data[3] == 0x47) return "image/png";
        // JPEG: FF D8 FF
        if (data[0] == (byte) 0xFF && data[1] == (byte) 0xD8 && data[2] == (byte) 0xFF)
            return "image/jpeg";
        // GIF: 47 49 46 38
        if (data[0] == 0x47 && data[1] == 0x49 && data[2] == 0x46 && data[3] == 0x38)
            return "image/gif";
        // WebP: 52 49 46 46 ... 57 45 42 50
        if (data.length >= 12 && data[0] == 0x52 && data[1] == 0x49 && data[2] == 0x46
                && data[3] == 0x46 && data[8] == 0x57 && data[9] == 0x45
                && data[10] == 0x42 && data[11] == 0x50)
            return "image/webp";
        return null;
    }

    // -- currentTimeStr --

    static String currentTimeStr(String timezone) {
        ZoneId zone;
        try {
            zone = timezone != null ? ZoneId.of(timezone) : ZoneId.systemDefault();
        } catch (Exception e) {
            zone = ZoneId.systemDefault();
        }
        var now = ZonedDateTime.now(zone);
        var fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEEE)");
        var offset = now.getOffset().getId();
        return now.format(fmt) + " (" + zone + ", UTC" + offset + ")";
    }
}
