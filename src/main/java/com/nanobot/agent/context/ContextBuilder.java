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
 * 构建发给 LLM 的上下文（系统提示词 + 消息列表）。
 * 对应 Python ContextBuilder 类（agent/context.py，281 行）。
 *
 * <p>职责：系统提示词组装（含 identity、bootstrap 文件、SOUL/USER）、运行时上下文注入、
 * 用户消息内容构建（含图片 base64 编码）、历史消息与当前消息合并。</p>
 */
public class ContextBuilder {

    /** 工作区根目录下按序加载的 bootstrap 文件 */
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
    // 对应 Python ContextBuilder.build_system_prompt()

    /**
     * 构建系统提示词，依次拼接 identity、bootstrap 文件、工具契约、记忆上下文、
     * 最近历史、会话摘要。
     * 对应 Python ContextBuilder.build_system_prompt()。
     */
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
    // 对应 Python ContextBuilder.build_identity()

    /** 构建 identity 段：工作区路径、运行时信息、渠道。
     *  对应 Python ContextBuilder.build_identity()。 */
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
    // 对应 Python ContextBuilder._load_bootstrap_files()

    /** 加载工作区根目录下的 AGENTS.md、SOUL.md、USER.md。
     *  对应 Python ContextBuilder._load_bootstrap_files()。 */
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
    // 对应 Python ContextBuilder.build_runtime_context()

    /** 构建运行时上下文块，包含当前时间、渠道、会话 ID、发送者 ID 等。
     *  对应 Python ContextBuilder.build_runtime_context()。 */
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
    // 对应 Python ContextBuilder.build_user_content()

    /** 构建用户消息内容，图片附件做 base64 编码。
     *  对应 Python ContextBuilder.build_user_content()。 */
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
    // 对应 Python ContextBuilder.build_messages()

    /** 构建完整的消息列表（system + history + 当前用户消息 + 运行时上下文）。
     *  对应 Python ContextBuilder.build_messages()。 */
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

        // 若最后一条消息与当前角色相同，合并内容而非新增一条
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
    // 对应 Python ContextBuilder.merge_message_content()

    /** 合并两条消息的内容（字符串拼接或 content-block 列表合并）。
     *  对应 Python ContextBuilder.merge_message_content()。 */
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

    /** 将消息内容统一转为 content-block 列表 */
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
    // 对应 Python ContextBuilder._detect_image_mime()

    /** 通过魔数检测图片 MIME 类型，支持 PNG/JPEG/GIF/WebP。
     *  对应 Python ContextBuilder._detect_image_mime()。 */
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
    // 对应 Python ContextBuilder._current_time_str()

    /** 格式化当前时间字符串，含时区和 UTC 偏移。
     *  对应 Python ContextBuilder._current_time_str()。 */
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
