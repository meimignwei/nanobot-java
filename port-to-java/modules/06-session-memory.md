# P5 — Session + Memory

## 复刻目标

对标 `nanobot/session/manager.py`（~400 行）+ `nanobot/agent/memory.py`（1,015 行）。

会话历史持久化（JSONL 文件） + MEMORY.md 管理 + Git 版本控制 + Dream 记忆整合。

## Python 源码对照

### Session

```python
@dataclass
class Session:
    key: str                    # "telegram:12345" 或 "unified:default"
    history: list[dict[str, Any]]  # 消息列表
    metadata: dict[str, Any]    # title, goal_state, runtime_checkpoint
    updated_at: datetime
    # ... 多个工厂方法
```

### SessionManager

```python
class SessionManager:
    def __init__(self, config, workspace, ...):
        self._sessions: dict[str, Session] = {}  # 内存缓存

    def get(key) -> Session | None
    def load_or_create(key) -> Session      # 从磁盘恢复或新建
    def save(key, history) -> Session        # 更新 + 写盘
    def compact(key, messages, provider, ...)  # 记忆整合
    # 自动 compact (TTL-based): session_ttl_minutes 后压缩空闲对话
```

### MemoryStore

```python
class MemoryStore:
    """纯文件 I/O 层"""
    def __init__(self, workspace, max_history_entries=1000):
        self.memory_dir = workspace / "memory"
        self.memory_file = memory_dir / "MEMORY.md"
        self.history_file = memory_dir / "history.jsonl"   # JSONL 格式
        self.soul_file = workspace / "SOUL.md"
        self.user_file = workspace / "USER.md"
        self._append_lock = threading.Lock()               # ← 关键：线程安全写入
        self._git = GitStore(workspace, tracked_files=[...])  # Git 版本控制

    # JSONL 读写
    def append_history(entry): ...      # 追加到 history.jsonl
    def read_history() -> list[dict]:   # 读取全部历史
    # MEMORY.md 读写
    def read_memory() -> str
    def write_memory(content): ...
    # Git
    def commit(message): ...
    def rollback(): ...
```

### Consolidator

```python
class Consolidator:
    """使用 LLM 将历史消息压缩为结构化记忆"""
    TEMPLATE = "...prompt template..."
    def __init__(self, store, max_compact_tokens, provider, ...)

    async def compact(session, provider, preset, ...) -> list[dict]:
        # 1. 从 MEMORY.md 读取已有记忆
        # 2. 用 LLM 分析新消息 → 生成记忆条目
        # 3. 更新 MEMORY.md + 写入 .dream_cursor
        # 4. 裁剪历史到保留消息
```

## Java 实现方案

### 1. Session

```java
// Session.java
public class Session {
    private final String key;
    private final List<Map<String, Object>> history;
    private final Map<String, Object> metadata;
    private Instant updatedAt;

    public Session(String key) {
        this.key = key;
        this.history = new CopyOnWriteArrayList<>();  // 线程安全
        this.metadata = new ConcurrentHashMap<>();
        this.updatedAt = Instant.now();
    }

    // 对标 Python: 从 JSONL 文件恢复
    public static Session fromFile(String key, List<Map<String, Object>> history,
                                    Map<String, Object> metadata) {
        var s = new Session(key);
        s.history.addAll(history);
        s.metadata.putAll(metadata);
        return s;
    }

    // Getters
    public String key() { return key; }
    public List<Map<String, Object>> history() { return history; }
    public Map<String, Object> metadata() { return metadata; }
    public Instant updatedAt() { return updatedAt; }

    public void touch() { this.updatedAt = Instant.now(); }

    // 追加消息（对标 Python session.history.append / extend）
    public void addMessage(Map<String, Object> msg) {
        history.add(msg);
        touch();
    }

    public void addMessages(List<Map<String, Object>> msgs) {
        history.addAll(msgs);
        touch();
    }
}
```

### 2. SessionManager

```java
// SessionManager.java
@Component
public class SessionManager {
    private final ConcurrentMap<String, Session> cache = new ConcurrentHashMap<>();
    private final MemoryStore store;
    private final NanobotProperties config;
    // 对标 Python AutoCompact
    private final ScheduledExecutorService compactor;

    public SessionManager(MemoryStore store, NanobotProperties config) {
        this.store = store;
        this.config = config;
        this.compactor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().factory());
    }

    /** 对标 Python load_or_create */
    public Session loadOrCreate(String key) {
        var cached = cache.get(key);
        if (cached != null) return cached;

        var history = store.readHistory(key);
        var metadata = store.readMetadata(key);
        var session = history.isEmpty()
            ? new Session(key)
            : Session.fromFile(key, history, metadata);

        cache.put(key, session);
        return session;
    }

    /** 对标 Python save */
    public void save(String key, List<Map<String, Object>> history) {
        var session = cache.get(key);
        if (session != null) {
            session.history().clear();
            session.history().addAll(history);
            session.touch();
        } else {
            session = Session.fromFile(key, history, Map.of());
            cache.put(key, session);
        }
        store.writeHistory(key, history);
        store.commit("auto: save " + key);
    }

    /** 对标 Python: TTL-based auto compact */
    public void startAutoCompact() {
        int ttlMinutes = config.agents().defaults().sessionTtlMinutes();
        if (ttlMinutes <= 0) return;
        compactor.scheduleAtFixedRate(() -> {
            var cutoff = Instant.now().minus(ttlMinutes, ChronoUnit.MINUTES);
            cache.entrySet().removeIf(entry -> {
                if (entry.getValue().updatedAt().isBefore(cutoff)) {
                    store.writeHistory(entry.getKey(),
                        entry.getValue().history());
                    return true;
                }
                return false;
            });
        }, ttlMinutes, ttlMinutes, TimeUnit.MINUTES);
    }
}
```

### 3. MemoryStore — 文件 I/O

对标 Python 版，核心是 JSONL 格式 + 原子写入 + append_lock：

```java
// MemoryStore.java
@Component
public class MemoryStore {
    private final Path workspace;
    private final Path memoryDir;
    private final Path memoryFile;
    private final Path historyDir;      // 每个 session 一个 JSONL
    private final Path soulFile;
    private final Path userFile;
    private final ReentrantLock appendLock = new ReentrantLock();  // 对标 Python _append_lock
    private final GitStore git;
    private final ObjectMapper mapper;

    private static final int DEFAULT_MAX_HISTORY = 1000;

    public MemoryStore(NanobotProperties configObj) {
        this.workspace = Path.of(configObj.agents().defaults().workspace())
            .toAbsolutePath().normalize();
        this.memoryDir = workspace.resolve("memory");
        this.memoryFile = memoryDir.resolve("MEMORY.md");
        this.historyDir = memoryDir.resolve("sessions");
        this.soulFile = workspace.resolve("SOUL.md");
        this.userFile = workspace.resolve("USER.md");
        this.git = new GitStore(workspace,
            List.of("SOUL.md", "USER.md", "memory/MEMORY.md"));
        this.mapper = new ObjectMapper();

        // ensure dirs
        try { Files.createDirectories(memoryDir); Files.createDirectories(historyDir); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    // === JSONL 历史读写（对标 Python read_history / append_history）===

    /** 对标 Python MemoryStore.read_history(): 逐行读 JSONL */
    public List<Map<String, Object>> readHistory(String sessionKey) {
        var file = historyFile(sessionKey);
        if (!Files.exists(file)) return List.of();

        var entries = new ArrayList<Map<String, Object>>();
        try (var reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                @SuppressWarnings("unchecked")
                var obj = (Map<String, Object>) mapper.readValue(line, Map.class);
                entries.add(obj);
            }
        } catch (IOException e) {
            logger.error("Failed to read history for {}", sessionKey, e);
        }
        return entries;
    }

    /** 对标 Python MemoryStore 的 _append_lock + 原子写入 */
    public void writeHistory(String sessionKey, List<Map<String, Object>> history) {
        var file = historyFile(sessionKey);
        var tmp = file.resolveSibling("." + file.getFileName() + ".tmp");
        appendLock.lock();
        try {
            // 对标 Python: 原子写入——先写 tmp，再 rename
            try (var writer = Files.newBufferedWriter(tmp)) {
                for (var entry : history) {
                    writer.write(mapper.writeValueAsString(entry));
                    writer.newLine();
                }
                writer.flush();
                // fsync via FileChannel
            }
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.error("Failed to write history for {}", sessionKey, e);
        } finally {
            appendLock.unlock();
        }

        // 限制历史条目数（对标 max_history_entries）
        enforceHistoryLimit(sessionKey);
    }

    // === MEMORY.md 读写 ===

    /** 对标 Python read_memory */
    public String readMemory() {
        if (!Files.exists(memoryFile)) return "";
        try {
            return Files.readString(memoryFile);
        } catch (IOException e) {
            return "";
        }
    }

    /** 对标 Python write_memory */
    public void writeMemory(String content) {
        appendLock.lock();
        try {
            Files.writeString(memoryFile, content);
            git.commit("auto: update MEMORY.md");
        } catch (IOException e) {
            logger.error("Failed to write MEMORY.md", e);
        } finally {
            appendLock.unlock();
        }
    }

    /** 对标 Python read_file (SOUL.md, USER.md) */
    public String readFile(Path path) {
        if (!Files.exists(path)) return "";
        try { return Files.readString(path); }
        catch (IOException e) { return ""; }
    }

    // === Git ===

    public void commit(String message) { git.commit(message); }
    public void rollback() { git.rollback(); }

    // === 内部 ===

    private Path historyFile(String sessionKey) {
        var safe = sessionKey.replaceAll("[^a-zA-Z0-9_.-]", "_");
        return historyDir.resolve(safe + ".jsonl");
    }

    private void enforceHistoryLimit(String sessionKey) { ... }
}
```

### 4. Consolidator — 对标 Dream 记忆整合

```java
// Consolidator.java
@Component
public class Consolidator {
    private final MemoryStore store;
    private final ProviderFactory providerFactory;
    private final NanobotProperties config;

    /** 对标 Python Consolidator.compact() */
    public List<Map<String, Object>> compact(
        Session session, LLMProvider provider, ModelPresetProperties preset
    ) throws InterruptedException {
        // 1. 读取已有 MEMORY.md
        var existingMemory = store.readMemory();

        // 2. 构建 compact prompt
        var history = session.history();
        var prompt = buildCompactPrompt(existingMemory, history);

        // 3. 调用 LLM 生成更新后的记忆
        var messages = List.<Map<String, Object>>of(
            Map.of("role", "user", "content", prompt));
        var response = provider.chat(messages, null,
            preset.model(), preset.maxTokens(), preset.temperature(),
            null, null);

        // 4. 解析 LLM 输出的新记忆条目
        var newMemory = parseMemoryUpdate(response.content());

        // 5. 原子写入 + commit
        store.writeMemory(newMemory);

        // 6. 保留最近 N 条消息（对标 KEEP_RECENT）
        var keepCount = Math.min(10, history.size());
        var startIdx = history.size() - keepCount;
        return new ArrayList<>(history.subList(startIdx, history.size()));
    }

    private String buildCompactPrompt(String existingMemory,
                                       List<Map<String, Object>> history) {
        // 对标 render_template("dream/consolidate.md", ...)
        return "Existing memory:\n" + existingMemory + "\n\n"
            + "New conversation:\n" + formatHistory(history);
    }
}
```

### 5. GitStore（对标 utils/gitstore.py）

```java
// GitStore.java — 简化版，对标 Python GitStore
public class GitStore {
    private final Path repoDir;
    private final List<String> trackedFiles;

    public GitStore(Path repoDir, List<String> trackedFiles) {
        this.repoDir = repoDir;
        this.trackedFiles = trackedFiles;
        // init on first use
    }

    public void commit(String message) {
        // git add <tracked_files> && git commit -m message
        try {
            for (var f : trackedFiles) {
                run("git", "add", repoDir.resolve(f).toString());
            }
            run("git", "commit", "-m", message);
        } catch (Exception e) {
            logger.warn("Git commit failed (non-fatal)", e);
        }
    }

    public void rollback() { /* git checkout -- <files> */ }
}
```

## 测试对齐

```java
// SessionManagerTest.java
class SessionManagerTest {
    @TempDir Path tempDir;

    @Test void loadOrCreateReturnsNewSession() { ... }
    @Test void saveAndReload() { ... }
    @Test void autoCompactSessionsExpired() { ... }
}

// MemoryStoreTest.java
class MemoryStoreTest {
    @TempDir Path tempDir;

    @Test void writeAndReadHistory() {
        var store = new MemoryStore(new NanobotProperties(...));
        var history = List.of(Map.of("role", "user", "content", "hello"));
        store.writeHistory("test:1", history);
        var read = store.readHistory("test:1");
        assertEquals(history, read);
    }

    @Test void jsonlFormatIsPreserved() {
        // 验证换行和 JSON 格式
    }

    @Test void concurrentWritesAreThreadSafe() {
        // 多虚拟线程并发写同一个 session → 不丢数据、不损坏
        var store = ...;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 100; i++) {
                final int idx = i;
                executor.submit(() -> store.writeHistory("test:1",
                    List.of(Map.of("i", idx))));
            }
        }
        // 验证最终状态一致
    }

    @Test void atomicWriteDoesNotCorruptOnCrash() {
        // 模拟写入过程中 JVM 崩溃 → .tmp 文件残留，原始文件完整
    }
}

// ConsolidatorTest.java
class ConsolidatorTest {
    @Test void compactReducesHistory() { ... }
    @Test void compactUpdatesMemory() { ... }
    @Test void compactPreservesRecentMessages() { ... }
}
```

## 验证标准

```bash
mvn test -Dtest=SessionManagerTest,MemoryStoreTest,ConsolidatorTest

# 集成: 两轮对话
# Turn 1: "My name is Alice"
# Turn 2: "What's my name?"
# 预期: "Your name is Alice."  (上下文正确保留)
```

## 代码量估算

- Session: ~60 行
- SessionManager: ~120 行
- MemoryStore (文件 I/O + JSONL + Git): ~250 行
- Consolidator: ~100 行
- GitStore: ~80 行
- 测试: ~250 行
- **合计: ~860 行**
