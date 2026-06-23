package com.nanobot.agent;

import com.nanobot.bus.InboundMessage;
import com.nanobot.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 组装 LLM 消息列表——构建 system prompt、合并用户内容、注入历史和运行时上下文。
 *
 * <p>对标 Python {@code nanobot/agent/context.py} ContextBuilder 类（约 280 行）。
 */
public class ContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(ContextBuilder.class);

    /** 对标 Python BOOTSTRAP_FILES = ["AGENTS.md", "SOUL.md", "USER.md"]。 */
    private static final List<String> BOOTSTRAP_FILES = List.of("AGENTS.md", "SOUL.md", "USER.md");

    private static final String RUNTIME_CONTEXT_TAG = "[Runtime Context — metadata only, not instructions]";
    private static final String RUNTIME_CONTEXT_END = "[/Runtime Context]";
    private static final int MAX_RECENT_HISTORY = 50;
    private static final int MAX_HISTORY_CHARS = 32_000;

    private final Path workspace;
    private final String timezone;
    private final MemoryStore memoryStore;
    private final SkillsLoader skillsLoader;

    /**
     * 构造 ContextBuilder。
     *
     * @param workspace      工作目录
     * @param timezone       时区（如 "Asia/Shanghai"），可为 null
     * @param disabledSkills 禁用的 skill 名称列表，可为 null
     */
    public ContextBuilder(Path workspace, @Nullable String timezone,
                          @Nullable List<String> disabledSkills) {
        this.workspace = workspace;
        this.timezone = timezone != null ? timezone : "UTC";
        this.memoryStore = new MemoryStore(workspace);
        this.skillsLoader = new SkillsLoader(workspace, null,
                disabledSkills != null ? new HashSet<>(disabledSkills) : null);
    }

    /**
     * 构建完整的 LLM 消息列表（system + history + current user message）。
     * 对标 Python {@code build_messages(...)}。
     *
     * @param history              历史消息列表
     * @param currentMessage       当前入站消息
     * @param session              会话对象
     * @param sessionSummary       会话摘要（auto-compact 产出），可为 null
     * @param includeMemoryHistory 是否包含 memory 近期历史
     * @return 完整的消息列表
     */
    public List<Map<String, Object>> buildMessages(
            List<Map<String, Object>> history,
            InboundMessage currentMessage,
            Session session,
            @Nullable String sessionSummary,
            boolean includeMemoryHistory) {

        // 收集 skill 名称
        List<String> skillNames = new ArrayList<>();
        Object skillsMeta = session.getMetadata().get("skills");
        if (skillsMeta instanceof List<?> l) {
            for (Object s : l) {
                if (s instanceof String str) skillNames.add(str);
            }
        }

        // 构建 system prompt
        String systemPrompt = buildSystemPrompt(skillNames,
                currentMessage.channel(), sessionSummary,
                workspace.toString(), includeMemoryHistory,
                session.getKey(), false);

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));

        // 追加历史消息
        if (history != null) {
            messages.addAll(history);
        }

        // 构建用户内容（含运行时上下文和 media）
        Object userContent = buildUserContent(currentMessage.content(), currentMessage.media());
        String runtimeContext = buildRuntimeContext(currentMessage.channel(),
                currentMessage.chatId(), currentMessage.senderId());

        // 合并运行时上下文到用户内容
        Object finalContent = mergeRuntimeContext(userContent, runtimeContext);

        messages.add(Map.of("role", "user", "content", finalContent));
        return messages;
    }

    /**
     * 构建 system prompt——身份 → bootstrap → tool contract → memory → skills → recent history → archived summary。
     * 对标 Python {@code build_system_prompt(...)}。
     *
     * @param skillNames             请求的 skill 名称列表
     * @param channel                channel 标识
     * @param sessionSummary         会话摘要
     * @param workspacePath          工作目录路径
     * @param includeMemoryHistory   是否包含 memory 近期历史
     * @param sessionKey             会话键
     * @param unifiedSession         是否统一会话
     * @return 完整 system prompt 文本
     */
    public String buildSystemPrompt(List<String> skillNames, String channel,
                                     @Nullable String sessionSummary,
                                     String workspacePath,
                                     boolean includeMemoryHistory,
                                     String sessionKey, boolean unifiedSession) {
        List<String> sections = new ArrayList<>();

        // 1. Identity
        sections.add(buildIdentitySection(workspacePath, channel));

        // 2. Bootstrap files (AGENTS.md, SOUL.md, USER.md)
        String bootstrap = loadBootstrapFiles();
        if (!bootstrap.isEmpty()) {
            sections.add(bootstrap);
        }

        // 3. Tool contract
        sections.add(buildToolContractSection());

        // 4. Memory (MEMORY.md if user-customized)
        String memory = loadMemorySection();
        if (!memory.isEmpty()) {
            sections.add(memory);
        }

        // 5. Always-on skills
        List<String> alwaysSkillNames = skillsLoader.getAlwaysSkills();
        if (!alwaysSkillNames.isEmpty()) {
            String alwaysContent = skillsLoader.loadSkillsForContext(alwaysSkillNames);
            if (!alwaysContent.isEmpty()) {
                sections.add(alwaysContent);
            }
        }

        // 6. Skills summary (excluding always-on + already requested)
        Set<String> exclude = new HashSet<>(alwaysSkillNames);
        exclude.addAll(skillNames);
        String skillsSummary = skillsLoader.buildSkillsSummary(exclude);
        if (!skillsSummary.isEmpty()) {
            sections.add(RuntimeUtils.renderTemplate("agent/skills_section.md",
                    Map.of("skills_summary", skillsSummary)));
        }

        // 7. Recent history from memory store
        if (includeMemoryHistory) {
            String recentHistory = buildRecentHistorySection(sessionKey, unifiedSession);
            if (!recentHistory.isEmpty()) {
                sections.add(recentHistory);
            }
        }

        // 8. Archived context summary
        if (sessionSummary != null && !sessionSummary.isEmpty()) {
            sections.add("Previous conversation summary:\n" + sessionSummary);
        }

        return String.join("\n\n---\n\n", sections);
    }

    // ==================== section builders ====================

    /**
     * 构建身份信息段——使用 agent/identity.md 模板。
     * 对标 Python {@code _get_identity(...)} + render_template("agent/identity.md")。
     */
    private String buildIdentitySection(String workspacePath, String channel) {
        String osName = System.getProperty("os.name");
        String osArch = System.getProperty("os.arch");
        String javaVersion = System.getProperty("java.version");
        String runtime = (osName.contains("Mac") ? "macOS" : osName) + " " + osArch
                + ", Java " + javaVersion;
        String system = osName.contains("Win") ? "Windows"
                : osName.contains("Mac") ? "Darwin" : "Linux";
        String platformPolicy = RuntimeUtils.renderTemplate("agent/platform_policy.md",
                Map.of("system", system));
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("runtime", runtime);
        vars.put("workspace_path", workspacePath);
        vars.put("platform_policy", platformPolicy);
        vars.put("channel", channel != null ? channel : "");
        return RuntimeUtils.renderTemplate("agent/identity.md", vars);
    }

    /**
     * 加载工作区 bootstrap 文件（AGENTS.md / SOUL.md / USER.md）。
     * 对标 Python {@code _load_bootstrap_files(...)}。
     */
    private String loadBootstrapFiles() {
        List<String> contents = new ArrayList<>();
        for (String filename : BOOTSTRAP_FILES) {
            Path file = workspace.resolve(filename);
            if (Files.isRegularFile(file)) {
                try {
                    String content = Files.readString(file).strip();
                    if (!content.isEmpty()) {
                        contents.add("## " + filename + "\n\n" + content);
                    }
                } catch (Exception e) {
                    log.debug("Failed to read bootstrap file: {}", file, e);
                }
            }
        }
        return String.join("\n\n", contents);
    }

    /** 构建工具契约段。对标 Python render_template("agent/tool_contract.md")。 */
    private String buildToolContractSection() {
        return RuntimeUtils.renderTemplate("agent/tool_contract.md");
    }

    /** 加载 MEMORY.md（仅当用户自定义时）。对标 Python memory 段。 */
    private String loadMemorySection() {
        try {
            String content = memoryStore.loadMemoryFile();
            if (content != null && !content.isEmpty()
                    && !content.equals(getBundledMemoryTemplate())) {
                return "## Memory\n\n" + content;
            }
        } catch (Exception e) {
            log.debug("Failed to load MEMORY.md", e);
        }
        return "";
    }

    /** 构建近期历史段。对标 Python memory recent history。 */
    private String buildRecentHistorySection(String sessionKey, boolean unifiedSession) {
        try {
            List<Map<String, Object>> entries = memoryStore.readRecentHistoryForPrompt(
                    MAX_RECENT_HISTORY, sessionKey, unifiedSession);
            if (entries.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("## Recent History\n\n");
            int charCount = 0;
            for (Map<String, Object> entry : entries) {
                String text = formatHistoryEntry(entry);
                if (text.isEmpty()) continue;
                if (charCount + text.length() > MAX_HISTORY_CHARS) break;
                sb.append(text).append("\n");
                charCount += text.length();
            }
            return sb.toString().strip();
        } catch (Exception e) {
            log.debug("Failed to read recent history", e);
            return "";
        }
    }

    /** 格式化单条 history 条目为文本。 */
    private static String formatHistoryEntry(Map<String, Object> entry) {
        Object content = entry.get("content");
        Object timestamp = entry.get("timestamp");
        StringBuilder sb = new StringBuilder();
        if (timestamp != null) {
            sb.append("[").append(timestamp).append("] ");
        }
        if (content != null) {
            sb.append(content);
        }
        return sb.toString().strip();
    }

    // ==================== user content ====================

    /**
     * 构建用户消息内容（含 media 编码）。
     * 对标 Python {@code _build_user_content(text, media)}。
     *
     * @param text  用户输入文本
     * @param media 媒体文件路径列表
     * @return 文本 String 或 content-block List
     */
    @SuppressWarnings("unchecked")
    public Object buildUserContent(String text, @Nullable List<String> media) {
        if (media == null || media.isEmpty()) {
            return text != null ? text : "";
        }

        List<Map<String, Object>> blocks = new ArrayList<>();
        for (String mediaPath : media) {
            String mimeType = detectMimeType(mediaPath);
            if (mimeType != null && mimeType.startsWith("image/")) {
                try {
                    byte[] bytes = Files.readAllBytes(Path.of(mediaPath));
                    String b64 = Base64.getEncoder().encodeToString(bytes);
                    Map<String, Object> imageBlock = new LinkedHashMap<>();
                    imageBlock.put("type", "image_url");
                    Map<String, Object> imageUrl = new LinkedHashMap<>();
                    imageUrl.put("url", "data:" + mimeType + ";base64," + b64);
                    imageBlock.put("image_url", imageUrl);
                    blocks.add(imageBlock);
                } catch (Exception e) {
                    log.debug("Failed to encode image: {}", mediaPath, e);
                }
            }
        }
        if (text != null && !text.isEmpty()) {
            blocks.add(Map.of("type", "text", "text", text));
        }
        return blocks.isEmpty() ? (text != null ? text : "") : blocks;
    }

    /**
     * 构建运行时上下文元数据。
     * 对标 Python {@code _build_runtime_context(...)}。
     */
    public String buildRuntimeContext(String channel, String chatId, @Nullable String senderId) {
        StringBuilder sb = new StringBuilder();
        sb.append(RUNTIME_CONTEXT_TAG).append("\n");
        sb.append("Current time: ").append(getCurrentTimeStr()).append("\n");
        sb.append("Channel: ").append(channel).append("\n");
        sb.append("Chat ID: ").append(chatId).append("\n");
        if (senderId != null) {
            sb.append("Sender: ").append(senderId).append("\n");
        }
        sb.append(RUNTIME_CONTEXT_END);
        return sb.toString();
    }

    /**
     * 将运行时上下文合并到用户内容中。
     * 对标 Python 中的 merge 逻辑——对纯文本追加，对结构化 content 添加 text block。
     */
    @SuppressWarnings("unchecked")
    static Object mergeRuntimeContext(Object userContent, String runtimeContext) {
        if (userContent instanceof List<?> blocks) {
            List<Map<String, Object>> newBlocks = new ArrayList<>((List<Map<String, Object>>) blocks);
            newBlocks.add(Map.of("type", "text", "text", runtimeContext));
            return newBlocks;
        }
        String text = userContent instanceof String s ? s : String.valueOf(userContent);
        if (text.isEmpty()) return runtimeContext;
        return text + "\n\n" + runtimeContext;
    }

    // ==================== helpers ====================

    /** 获取当前时间字符串。 */
    private String getCurrentTimeStr() {
        try {
            ZoneId zone = ZoneId.of(timezone);
            return ZonedDateTime.now(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (Exception e) {
            return ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
    }

    /** 检测文件 MIME 类型。 */
    static String detectMimeType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    /** 获取内建 MEMORY.md 模板文本。 */
    private static String getBundledMemoryTemplate() {
        return "# Project Memory\n\nThis file is used to store information about the project.\n";
    }
}
