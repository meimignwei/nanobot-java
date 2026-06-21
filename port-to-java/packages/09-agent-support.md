# 09 — agent 支撑：Memory + Subagent + Skills + AutoCompact + ModelPresets

**对标 Python：** `memory.py` (1,015行), `subagent.py` (394行), `skills.py` (~120行), `autocompact.py` (~100行), `model_presets.py` (65行)

## 1. MemoryStore + Consolidator (`memory.py`)

### Python 源码分析

```
MemoryStore:
    持久化文件（在 workspace/memory/ 下）:
    - MEMORY.md           → 记忆索引文件
    - SOUL.md, USER.md    → bootstrap 身份文件
    - history.jsonl        → 完整的对话历史（JSONL 追加写）
    - AGENTS.md           → 项目指令

    读取:
    - load_memory()       → 读取 MEMORY.md + history.jsonl（cursor 增量）
    - load_bootstrap_file(name) → 读取 AGENTS.md, USER.md, SOUL.md

    写入:
    - save_memory(content) → 追加写入 history.jsonl (带 fsync)
    - update_memory_cursor() → 更新读取位置

Consolidator:
    - consolidate(session, provider)
      → 当消息量超过 consolidation_ratio 时触发
      → 用 LLM 总结历史对话 → 写入 MemoryStore
      → 加 consolidation_lock 防止并发

Dream 两阶段记忆整合:
    - 第一阶段: 小模型快速分类
    - 第二阶段: 大模型深度整合
```

### Java 实现

```java
package com.nanobot.agent;

import com.nanobot.session.Session;
import com.nanobot.session.SessionManager;
import com.nanobot.providers.LLMProvider;
import com.nanobot.utils.GitStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class MemoryStore {

    private final Path workspacePath;
    private final GitStore gitStore;
    private long cursor;  // JSONL 读取位置

    // 文件路径
    private final Path memoryMdPath;
    private final Path historyJsonlPath;
    private final Path agentsMdPath;
    private final Path soulMdPath;
    private final Path userMdPath;

    public MemoryStore(Path workspacePath, @Nullable GitStore gitStore) {
        this.workspacePath = workspacePath;
        this.gitStore = gitStore;
        Path memoryDir = workspacePath.resolve("memory");
        this.memoryMdPath = memoryDir.resolve("MEMORY.md");
        this.historyJsonlPath = memoryDir.resolve("history.jsonl");
        this.agentsMdPath = workspacePath.resolve("AGENTS.md");
        this.soulMdPath = memoryDir.resolve("SOUL.md");
        this.userMdPath = memoryDir.resolve("USER.md");
        this.cursor = 0;
    }

    /**
     * 对标 Python MemoryStore.load_memory().
     * 读取 MEMORY.md 索引 + 增量读取 history.jsonl.
     */
    public List<Map<String, Object>> loadMemory() {
        List<Map<String, Object>> records = new ArrayList<>();

        // 加载 MEMORY.md (索引)
        String memoryMd = readFileIfExists(memoryMdPath);
        if (memoryMd != null && !memoryMd.isEmpty()) {
            Map<String, Object> systemMsg = new LinkedHashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", "<memory>\n" + memoryMd + "\n</memory>");
            records.add(systemMsg);
        }

        // 增量读取 history.jsonl
        List<String> lines = readJsonlLines(historyJsonlPath, cursor);
        for (String line : lines) {
            try {
                Map<String, Object> record = parseJson(line);
                if (!"_type".equals(record.get("_type"))) {
                    records.add(record);
                }
            } catch (IOException e) {
                log.warn("Skipping corrupt JSONL line: {}", e.getMessage());
            }
        }
        cursor += lines.size();

        return records;
    }

    /**
     * 对标 Python MemoryStore.save_memory().
     * 追加写入 history.jsonl，原子写入 + fsync.
     */
    public void saveMemory(Map<String, Object> record) {
        try {
            String line = toJson(record) + "\n";
            Files.writeString(historyJsonlPath, line,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.SYNC);  // fsync

            // Git 自动提交
            if (gitStore != null) {
                gitStore.commit("nanobot: memory update");
            }
        } catch (IOException e) {
            log.error("Failed to save memory", e);
        }
    }

    public String loadBootstrapFile(String name) {
        Path path = switch (name) {
            case "AGENTS.md" -> agentsMdPath;
            case "SOUL.md" -> soulMdPath;
            case "USER.md" -> userMdPath;
            default -> workspacePath.resolve(name);
        };
        return readFileIfExists(path);
    }

    // --- 工具方法 ---

    @Nullable
    private String readFileIfExists(Path path) {
        try {
            if (Files.exists(path)) {
                return Files.readString(path);
            }
        } catch (IOException ignored) {}
        return null;
    }

    private List<String> readJsonlLines(Path path, long startLine) {
        try {
            if (!Files.exists(path)) return List.of();
            List<String> allLines = Files.readAllLines(path);
            if (startLine >= allLines.size()) return List.of();
            return allLines.subList((int) startLine, allLines.size());
        } catch (IOException e) {
            return List.of();
        }
    }

    private Map<String, Object> parseJson(String line) throws IOException {
        // 使用 Jackson ObjectMapper
        @SuppressWarnings("unchecked")
        Map<String, Object> result = new ObjectMapper()
            .readValue(line, Map.class);
        return result;
    }

    private String toJson(Map<String, Object> record) throws IOException {
        return new ObjectMapper().writeValueAsString(record);
    }
}

// === Consolidator ===

@Slf4j
@RequiredArgsConstructor
public class Consolidator {

    private final MemoryStore memoryStore;
    private final SessionManager sessionManager;
    private final double consolidationRatio;  // 默认 0.5

    private LLMProvider provider;
    private String model;
    private final ReentrantLock consolidationLock = new ReentrantLock();

    public void setProvider(LLMProvider provider, String model) {
        this.provider = provider;
        this.model = model;
    }

    /**
     * 对标 Python Consolidator.consolidate().
     * 当 session 消息量超过阈值时触发 LLM 总结.
     */
    public boolean maybeConsolidate(Session session, int maxMessages) {
        int threshold = (int) (maxMessages * consolidationRatio);
        if (session.getMessages().size() < threshold) {
            return false;
        }
        if (!consolidationLock.tryLock()) {
            return false;  // 已有 consolidation 在进行
        }
        try {
            String summary = generateSummary(session);
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("_type", "consolidation");
            record.put("summary", summary);
            record.put("timestamp", System.currentTimeMillis());
            memoryStore.saveMemory(record);

            // 裁剪 session 消息
            session.retainRecent(threshold);
            sessionManager.save(session);

            return true;
        } catch (Exception e) {
            log.error("Consolidation failed", e);
            return false;
        } finally {
            consolidationLock.unlock();
        }
    }

    private String generateSummary(Session session) {
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> sysMsg = new LinkedHashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", """
            Summarize the key facts, decisions, and context from this conversation.
            Focus on:
            1. User preferences and goals
            2. Important decisions made
            3. Key facts discovered
            4. Ongoing tasks or action items
            Be concise. This summary will replace the conversation in memory.
            """);
        messages.add(sysMsg);
        messages.addAll(session.getMessages());

        LLMResponse response = provider.chat(
            messages, null, model, 1024, 0.1, null
        );
        return response.content();
    }
}
```

## 2. SubagentManager (`subagent.py`)

### Python 源码分析

```
SubagentManager:
    管理子 agent 的生命周期:
    - spawn(label, task_description) → SubagentStatus
    - track() → 后台轮询子 agent 状态
    - cancel(task_id) → 取消子 agent

SubagentStatus:
    task_id, label, task_description, started_at,
    phase, iteration, tool_events, usage,
    stop_reason, error
```

### Java 实现

```java
package com.nanobot.agent;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public class SubagentManager {

    private final ConcurrentMap<String, SubagentStatus> activeSubagents = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ScheduledFuture<?>> watchers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
        2, Thread.ofVirtual().factory()
    );

    public SubagentStatus spawn(String label, String taskDescription) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        SubagentStatus status = new SubagentStatus(
            taskId, label, taskDescription,
            Instant.now(), "starting", 0, 0,
            new CopyOnWriteArrayList<>(), new ConcurrentHashMap<>(),
            null, null
        );
        activeSubagents.put(taskId, status);

        // 启动子 agent 虚拟线程
        Thread.ofVirtual().start(() -> runSubagent(status));

        return status;
    }

    private void runSubagent(SubagentStatus status) {
        // 创建子 agent 上下文，调用 AgentRunner.run()
        status.setPhase("running");
        // ... 执行
    }

    public boolean cancel(String taskId) {
        SubagentStatus status = activeSubagents.remove(taskId);
        ScheduledFuture<?> watcher = watchers.remove(taskId);
        if (watcher != null) watcher.cancel(true);
        return status != null;
    }

    public Collection<SubagentStatus> listActive() {
        return List.copyOf(activeSubagents.values());
    }
}

// SubagentStatus.java
public class SubagentStatus {
    private final String taskId;
    private final String label;
    private final String taskDescription;
    private final Instant startedAt;
    private volatile String phase;
    private volatile int iteration;
    private volatile int toolEvents;
    private final List<String> toolHistory;
    private final Map<String, Integer> usage;
    private volatile String stopReason;
    private volatile String error;

    // constructor, getters, setters
}
```

## 3. SkillsLoader (`skills.py`)

加载 workspace/skills/ 目录和内置 skill 定义。

```java
package com.nanobot.agent;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class SkillsLoader {

    private final Path skillsWorkspacePath;
    private final List<String> disabledSkills;

    public SkillsLoader(Path skillsWorkspacePath, List<String> disabledSkills) {
        this.skillsWorkspacePath = skillsWorkspacePath;
        this.disabledSkills = disabledSkills;
    }

    /**
     * 加载所有活跃 skills 的 prompt 定义.
     * 对标 Python SkillsLoader.load_always_skills().
     */
    public String buildSkillsSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("<available_skills>\n");

        // 内置 skills (从 classpath resources/skills/)
        loadResourceSkills(sb);

        // 用户自定义 skills (从 workspace/skills/)
        loadWorkspaceSkills(sb);

        sb.append("</available_skills>\n");
        return sb.toString();
    }

    private void loadResourceSkills(StringBuilder sb) {
        // 从 src/main/resources/skills/ 加载
        // 读取每个 skill 的 SKILL.md
    }

    private void loadWorkspaceSkills(StringBuilder sb) {
        if (!Files.exists(skillsWorkspacePath)) return;

        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(skillsWorkspacePath)) {
            for (Path dir : dirs) {
                String skillName = dir.getFileName().toString();
                if (disabledSkills.contains(skillName)) continue;

                Path skillMd = dir.resolve("SKILL.md");
                if (Files.exists(skillMd)) {
                    String content = Files.readString(skillMd);
                    sb.append("## ").append(skillName).append("\n");
                    sb.append(content).append("\n\n");
                }
            }
        } catch (IOException e) {
            // ignore
        }
    }
}
```

## 4. AutoCompact (`autocompact.py`)

```java
package com.nanobot.agent;

import com.nanobot.session.Session;
import com.nanobot.session.SessionManager;

public final class AutoCompact {

    private AutoCompact() {}

    /**
     * 对标 Python AutoCompact.
     * TTL 检查: session 空闲超过 session_ttl_minutes 时自动压缩.
     */
    public static void maybeCompact(
            Session session,
            Consolidator consolidator,
            int sessionTtlMinutes,
            int maxMessages) {

        if (sessionTtlMinutes <= 0) return;

        long idleMs = System.currentTimeMillis() - session.getUpdatedAt().toEpochMilli();
        long ttlMs = sessionTtlMinutes * 60_000L;

        if (idleMs > ttlMs) {
            consolidator.maybeConsolidate(session, maxMessages);
        }
    }
}
```

## 5. ModelPresets (`model_presets.py`)

```java
package com.nanobot.agent;

import com.nanobot.config.NanobotProperties;
import com.nanobot.config.ModelPresetProperties;
import com.nanobot.providers.GenerationSettings;

public final class ModelPresets {

    private ModelPresets() {}

    /**
     * 对标 Python resolve_preset() + PresetSnapshotLoader.
     */
    public static ModelPresetProperties resolve(
            NanobotProperties properties,
            @Nullable String presetName) {
        return properties.resolvePreset(presetName);
    }

    public static GenerationSettings toGenerationSettings(ModelPresetProperties preset) {
        return new GenerationSettings(
            preset.temperature(),
            preset.maxTokens(),
            preset.reasoningEffort()
        );
    }
}
```

## 代码量估算

| 文件 | 行数 |
|------|------|
| MemoryStore.java | ~200 |
| Consolidator.java | ~150 |
| SubagentManager.java | ~200 |
| SubagentStatus.java (class) | ~60 |
| SkillsLoader.java | ~100 |
| AutoCompact.java | ~40 |
| ModelPresets.java | ~25 |
| **合计** | **~775** |
