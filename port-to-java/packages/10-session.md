# 10 — session 包：会话管理与持久化

**对标 Python：** `nanobot/session/manager.py` (817行), `nanobot/session/goal_state.py` (~127行), `nanobot/session/turn_continuation.py` (~263行), `nanobot/session/webui_turns.py` (~450行)

## 1. Python 源码分析

### 1.1 Session 数据结构 (`manager.py`)

```python
@dataclass
class Session:
    key: str                               # "channel:chat_id"
    messages: list[dict[str, Any]]          # 可变的消息列表
    created_at: datetime
    updated_at: datetime
    metadata: dict[str, Any]               # title, goal_state, webui 等
    last_consolidated: int                 # 已整合到 memory 的消息数

    def add_message(self, role, content, **kwargs) -> None
    def get_history(self, max_messages=120, *, max_tokens=0, include_timestamps=False) -> list[dict]
    def clear(self) -> None
    def retain_recent_legal_suffix(self, max_messages: int) -> tuple[list[dict], int]
    def enforce_file_cap(self, on_archive=None, limit=2000) -> None
```

核心设计要点:
- `messages` 是可变列表，Session **不能**用 record 实现
- `last_consolidated` 标记已整合到 MEMORY.md 的位置，`get_history()` 只返回之后的未整合消息
- `get_history()` 有复杂的消息修复: 对齐 user turn、删除 orphan tool results、sanitize 内部标记、合成 image/cli_apps/mcp_presets breadcrumbs
- `retain_recent_legal_suffix()` 基于 identity (id()) 做 diff，返回 (dropped, already_consolidated)
- Python `@dataclass` 中 `__post_init__` 验证 `last_consolidated` 范围

### 1.2 SessionManager

```python
class SessionManager:
    def __init__(self, workspace: Path):
        self.sessions_dir = ensure_dir(workspace / "sessions")
        self.legacy_sessions_dir = get_legacy_sessions_dir()
        self._cache: dict[str, Session] = {}
```

核心方法:
| 方法 | 功能 | 关键细节 |
|------|------|----------|
| `get_or_create(key)` | 缓存 → 磁盘 → 新建 | `putIfAbsent` 语义 |
| `save(session, *, fsync=False)` | 原子写入 | tmp → `os.replace()` → 可选 fsync 文件+目录 |
| `_load(key)` | 读 JSONL | 抛异常时 fallback 到 `_repair()` |
| `_repair(key)` | 逐行解析 | 跳过 `JSONDecodeError` 行，记录跳过数量 |
| `read_session_file(key)` | 只读 HTTP endpoint | 不缓存 |
| `list_sessions()` | 扫描 `*.jsonl` | 读 metadata + 第一条 user 消息作为预览 |
| `delete_session(key)` | 删文件+缓存 | 返回是否找到文件 |
| `invalidate(key)` | 仅删缓存 | 不影响磁盘文件 |
| `flush_all()` | 遍历缓存 fsync | 单个失败不阻止其他 |
| `fork_session_before_user_index(...)` | 按 user 索引 fork | 删除 volatile metadata |

JSONL 格式:
```
第一行: {"_type": "metadata", "key": "tg:123", "created_at": "...", "updated_at": "...", "metadata": {...}, "last_consolidated": 0}
后续行: {"role": "user", "content": "hello", "timestamp": "..."}
后续行: {"role": "assistant", "content": "Hi!", "timestamp": "..."}
```

### 1.3 goal_state.py

```python
GOAL_STATE_KEY = "goal_state"
_LEGACY_GOAL_STATE_SESSION_KEY = "thread_goal"

def goal_state_raw(metadata) -> Any
def parse_goal_state(blob) -> dict | None     # JSON string → dict
def sustained_goal_active(metadata) -> bool   # status == "active"?
def sustained_goal_turn(metadata, *, message_metadata) -> bool
def goal_state_runtime_lines(metadata) -> list[str]  # LLM context (max 4000 chars)
def goal_state_ws_blob(metadata) -> dict      # WebSocket state (max 600 chars)
def runner_wall_llm_timeout_s(sessions, session_key, *, metadata, message_metadata) -> float | None
    # goal turn → 返回 0.0 (无超时)，否则 → None (使用默认)
```

### 1.4 turn_continuation.py

当 LLM turn 达到 `max_iterations` 且 session 有活跃 sustained goal 时，自动排队内部延续:

```python
INTERNAL_CONTINUATION_META = "_internal_continuation"
_INTERNAL_CONTINUATION_PENDING_META = "_internal_continuation_pending"
_GOAL_CONTINUATION_KIND = "sustained_goal"
_MAX_GOAL_CONTINUATION_ROUNDS = 12
_GOAL_CONTINUATION_SENDER = "system:continuation"

# maybe_continue_turn(ctx):
#   1. 检查 stop_reason == "max_iterations" && pending_queue 可用
#   2. 检查 sustained_goal active && rounds < 12
#   3. 构建内部延续 metadata (_internal_continuation: True)
#   4. 从 all_messages 中去除终端 assistant message
#   5. 递增 _sustained_goal_continuation_rounds
#   6. 将修改后的 InboundMessage 放入 pending_queue
```

### 1.5 webui_turns.py

`WebuiTurnCoordinator` 是 dataclass，持有 `MessageBus`、`SessionManager`、`schedule_background` 回调和 `_title_contexts: dict[str, LLMRuntime]`。订阅所有 5 种 RuntimeEvent 并翻译为 WebSocket wire messages。

`maybe_generate_webui_title()` 使用 LLM (max_tokens=96, temperature=0.2) 生成最多 60 字符的会话标题。

## 2. Java 实现方案

### 2.1 包结构

```
com.nanobot.session/
├── Session.java                  # 会话类 (非 record)
├── SessionManager.java           # JSONL I/O + cache
├── SessionSanitizer.java         # 消息清洁 (package-private)
├── SessionTokenBudget.java       # Token 预算裁剪 (package-private)
├── SessionHelpers.java           # 列表操作工具 (package-private)
├── SessionConstants.java         # 常量定义
├── GoalState.java                # 持续目标状态
├── TurnContinuation.java         # Turn 延续策略
├── TurnContinuationContext.java  # maybeContinueTurn 上下文接口
├── WebuiTurnCoordinator.java     # WebUI turn 协调器
└── WebuiTitleGenerator.java      # WebUI 标题生成
```

### 2.2 Session.java

**关键设计决策: Session 不能用 Java record。** `messages` 是 `CopyOnWriteArrayList<Map<String, Object>>`，每个 turn 追加消息。`metadata` 是可变 Map。`lastConsolidated` 在 Dream 整合后递增。

线程安全: `CopyOnWriteArrayList` 提供安全的迭代（无需外部同步即可读取），`ReentrantReadWriteLock` 保护多步骤操作 (`getHistory`, `retainRecentLegalSuffix`, `clear`)。

```java
package com.nanobot.session;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * NOT a Java record — messages list and metadata map are mutable.
 *
 * Thread safety: CopyOnWriteArrayList for messages, ReentrantReadWriteLock for
 * multi-step operations. python_analog: nanobot.session.manager.Session
 */
public class Session {

    private static final DateTimeFormatter ISO_FMT =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);

    private final String key;
    private final CopyOnWriteArrayList<Map<String, Object>> messages;
    private final Map<String, Object> metadata;
    private volatile Instant createdAt;
    private volatile Instant updatedAt;
    private volatile int lastConsolidated;

    /** Per-session lock. Agent writes (single writer), WebUI reads (concurrent). */
    private final transient ReentrantReadWriteLock lock;

    public Session(String key) {
        this.key = Objects.requireNonNull(key);
        this.messages = new CopyOnWriteArrayList<>();
        this.metadata = new LinkedHashMap<>();
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        this.lastConsolidated = 0;
        this.lock = new ReentrantReadWriteLock(false);
    }

    /** Full constructor — used by SessionManager when loading from disk. */
    public Session(String key, List<Map<String, Object>> messages, Map<String, Object> metadata,
                   Instant createdAt, Instant updatedAt, int lastConsolidated) {
        this.key = Objects.requireNonNull(key);
        this.messages = new CopyOnWriteArrayList<>(messages);
        this.metadata = new LinkedHashMap<>(metadata);
        this.createdAt = Objects.requireNonNullElse(createdAt, Instant.now());
        this.updatedAt = Objects.requireNonNullElse(updatedAt, Instant.now());
        this.lock = new ReentrantReadWriteLock(false);
        // __post_init__ validation
        this.lastConsolidated = (lastConsolidated >= 0 && lastConsolidated <= this.messages.size())
            ? lastConsolidated : 0;
    }

    // --- accessors ---
    public String getKey() { return key; }
    public List<Map<String, Object>> getMessages() { return messages; }
    public Map<String, Object> getMetadata() { return metadata; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public int getLastConsolidated() { return lastConsolidated; }
    public ReentrantReadWriteLock getLock() { return lock; }
    public void setLastConsolidated(int v) { if (v >= 0 && v <= messages.size()) this.lastConsolidated = v; }

    // --- mutation ---
    /** python_analog: add_message(role, content, **kwargs) */
    public void addMessage(String role, String content, @Nullable Map<String, Object> extra) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", role);
        msg.put("content", content);
        msg.put("timestamp", ISO_FMT.format(Instant.now()));
        if (extra != null) msg.putAll(extra);
        messages.add(msg);
        this.updatedAt = Instant.now();
    }
    public void addMessage(String role, String content) { addMessage(role, content, null); }
    public void addMessages(List<Map<String, Object>> msgs) {
        if (msgs != null) { messages.addAll(msgs); this.updatedAt = Instant.now(); }
    }
    public void touch() { this.updatedAt = Instant.now(); }

    // --- history retrieval ---
    /**
     * Return messages for LLM input. Slices from lastConsolidated, then by count and token budget.
     * python_analog: get_history(max_messages, max_tokens, include_timestamps)
     */
    public List<Map<String, Object>> getHistory(int maxMessages, int maxTokens, boolean includeTimestamps) {
        ReentrantReadWriteLock.ReadLock rl = lock.readLock();
        rl.lock();
        try {
            List<Map<String, Object>> unconsolidated =
                new ArrayList<>(messages.subList(lastConsolidated, messages.size()));
            int max = (maxMessages > 0) ? maxMessages : 120;
            List<Map<String, Object>> sliced = SessionHelpers.tailSublist(unconsolidated, max);
            sliced = SessionHelpers.alignToUserTurn(sliced);
            int ls = SessionHelpers.findLegalMessageStart(sliced);
            if (ls > 0) sliced = new ArrayList<>(sliced.subList(ls, sliced.size()));

            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> msg : sliced) {
                if (Boolean.TRUE.equals(msg.get("_command"))) continue;
                String content = Objects.toString(msg.get("content"), "");
                String role = Objects.toString(msg.get("role"), "");
                if ("assistant".equals(role) && !content.isEmpty())
                    content = SessionSanitizer.sanitizeAssistantReplayText(content);
                if ("user".equals(role)) {
                    content = SessionSanitizer.addMediaBreadcrumbs(msg, content);
                    content = SessionSanitizer.addCliAppsBreadcrumbs(msg, content);
                    content = SessionSanitizer.addMcpPresetsBreadcrumbs(msg, content);
                }
                if (includeTimestamps) content = SessionSanitizer.annotateMessageTime(msg, content);
                if ("assistant".equals(role) && content.strip().isEmpty()) {
                    if (!msg.containsKey("tool_calls") && !msg.containsKey("reasoning_content")
                        && !msg.containsKey("thinking_blocks")) continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("role", msg.get("role"));
                entry.put("content", content);
                for (String k : SessionConstants.LLM_MESSAGE_KEYS) {
                    Object v = msg.get(k);
                    if (v != null) entry.put(k, v);
                }
                out.add(entry);
            }
            if (maxTokens > 0 && !out.isEmpty()) out = SessionTokenBudget.trim(out, maxTokens);
            return Collections.unmodifiableList(out);
        } finally { rl.unlock(); }
    }

    /** python_analog: Session.clear() */
    public void clear() {
        ReentrantReadWriteLock.WriteLock wl = lock.writeLock();
        wl.lock();
        try { messages.clear(); lastConsolidated = 0; updatedAt = Instant.now(); metadata.remove("_last_summary"); }
        finally { wl.unlock(); }
    }

    /** python_analog: retain_recent_legal_suffix(max_messages) → (dropped, already_consolidated_count) */
    public RetainResult retainRecentLegalSuffix(int maxMessages) {
        ReentrantReadWriteLock.WriteLock wl = lock.writeLock();
        wl.lock();
        try {
            if (maxMessages <= 0) {
                List<Map<String, Object>> dropped = new ArrayList<>(messages);
                int lcBefore = lastConsolidated; clear();
                return new RetainResult(dropped, Math.min(lcBefore, dropped.size()));
            }
            if (messages.size() <= maxMessages) return new RetainResult(List.of(), 0);

            List<Map<String, Object>> original = new ArrayList<>(messages);
            int beforeLc = lastConsolidated;
            List<Map<String, Object>> retained = SessionHelpers.tailSublist(messages, maxMessages);

            int firstUser = SessionHelpers.findFirstRole(retained, "user");
            if (firstUser >= 0) retained = new ArrayList<>(retained.subList(firstUser, retained.size()));
            else {
                int lastUser = SessionHelpers.findLastRole(original, "user");
                if (lastUser >= 0) retained = new ArrayList<>(original.subList(lastUser,
                    Math.min(lastUser + maxMessages, original.size())));
            }
            int ls = SessionHelpers.findLegalMessageStart(retained);
            if (ls > 0) retained = new ArrayList<>(retained.subList(ls, retained.size()));
            if (retained.size() > maxMessages) {
                retained = SessionHelpers.tailSublist(retained, maxMessages);
                int ls2 = SessionHelpers.findLegalMessageStart(retained);
                if (ls2 > 0) retained = new ArrayList<>(retained.subList(ls2, retained.size()));
            }

            Set<Integer> retainedIds = SessionHelpers.identitySet(retained);
            List<Map<String, Object>> dropped = new ArrayList<>();
            for (Map<String, Object> m : original)
                if (!retainedIds.contains(System.identityHashCode(m))) dropped.add(m);

            int alreadyConsolidated = 0;
            for (int i = 0; i < original.size() && i < beforeLc; i++)
                if (!retainedIds.contains(System.identityHashCode(original.get(i)))) alreadyConsolidated++;

            int newLc = 0;
            for (int i = 0; i < original.size() && i < beforeLc; i++)
                if (retainedIds.contains(System.identityHashCode(original.get(i)))) newLc++;

            this.messages.clear(); this.messages.addAll(retained);
            this.lastConsolidated = newLc; this.updatedAt = Instant.now();
            return new RetainResult(dropped, alreadyConsolidated);
        } finally { wl.unlock(); }
    }

    /** python_analog: enforce_file_cap(on_archive, limit) */
    public void enforceFileCap(@Nullable Consumer<List<Map<String, Object>>> onArchive, int limit) {
        if (limit <= 0 || messages.size() <= limit) return;
        RetainResult r = retainRecentLegalSuffix(limit);
        if (r.dropped().isEmpty()) return;
        int ac = r.alreadyConsolidatedCount();
        if (ac < r.dropped().size() && onArchive != null)
            onArchive.accept(new ArrayList<>(r.dropped().subList(ac, r.dropped().size())));
    }

    public record RetainResult(List<Map<String, Object>> dropped, int alreadyConsolidatedCount) {}
}
```

### 2.3 SessionHelpers.java (package-private)

```java
package com.nanobot.session;

import java.util.*;

/** Internal list helpers. python_analog: module-level helpers in manager.py */
final class SessionHelpers {
    private SessionHelpers() {}

    static boolean isValidConsolidated(int v, int msgCount) { return v >= 0 && v <= msgCount; }

    static List<Map<String, Object>> tailSublist(List<Map<String, Object>> list, int count) {
        int start = Math.max(0, list.size() - count);
        return new ArrayList<>(list.subList(start, list.size()));
    }

    /** Align slice to start on a user turn (or preceding _channel_delivery). */
    static List<Map<String, Object>> alignToUserTurn(List<Map<String, Object>> sliced) {
        for (int i = 0; i < sliced.size(); i++) {
            if ("user".equals(sliced.get(i).get("role"))) {
                int s = (i > 0 && Boolean.TRUE.equals(sliced.get(i - 1).get("_channel_delivery"))) ? i - 1 : i;
                return (s > 0) ? new ArrayList<>(sliced.subList(s, sliced.size())) : sliced;
            }
        }
        return sliced;
    }

    /** Find first assistant with tool_calls or first user. python_analog: find_legal_message_start() */
    static int findLegalMessageStart(List<Map<String, Object>> list) {
        for (int i = 0; i < list.size(); i++) {
            String role = Objects.toString(list.get(i).get("role"), "");
            if (("assistant".equals(role) && list.get(i).containsKey("tool_calls")) || "user".equals(role))
                return i;
        }
        return 0;
    }

    static int findFirstRole(List<Map<String, Object>> list, String target) {
        for (int i = 0; i < list.size(); i++) if (target.equals(list.get(i).get("role"))) return i;
        return -1;
    }

    static int findLastRole(List<Map<String, Object>> list, String target) {
        for (int i = list.size() - 1; i >= 0; i--) if (target.equals(list.get(i).get("role"))) return i;
        return -1;
    }

    /** Build identity hash set for id()-based diff. */
    static Set<Integer> identitySet(List<Map<String, Object>> list) {
        Set<Integer> s = new HashSet<>();
        for (Map<String, Object> m : list) s.add(System.identityHashCode(m));
        return s;
    }
}
```

### 2.4 SessionSanitizer.java (package-private)

```java
package com.nanobot.session;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Message sanitization. python_analog: module-level helpers:
 *   _sanitize_assistant_replay_text, image_placeholder_text,
 *   _text_preview, _message_preview_text, _metadata_title
 */
final class SessionSanitizer {
    private SessionSanitizer() {}

    private static final Pattern MSG_TIME_RE = Pattern.compile("^\\[Message Time: [^\\]]+\\]\n?");
    private static final Pattern IMG_BREAD_RE = Pattern.compile("^\\[image: (?:/|~)[^\\]]+\\]\\s*$");
    private static final Pattern TOOL_ECHO_RE = Pattern.compile("^\\s*(?:generate_image|message)\\([^)]*\\)\\s*$");
    static final int PREVIEW_MAX_CHARS = 120;

    static String sanitizeAssistantReplayText(String content) {
        String c = MSG_TIME_RE.matcher(content).replaceFirst("");
        StringBuilder sb = new StringBuilder();
        for (String line : c.split("\n"))
            if (!IMG_BREAD_RE.matcher(line).matches() && !TOOL_ECHO_RE.matcher(line).matches()) {
                if (sb.length() > 0) sb.append('\n'); sb.append(line);
            }
        return sb.toString().strip();
    }

    static String addMediaBreadcrumbs(Map<String, Object> msg, String content) {
        Object m = msg.get("media");
        if (!(m instanceof List<?> ml) || ml.isEmpty()) return content;
        StringBuilder c = new StringBuilder();
        for (Object item : ml)
            if (item instanceof String s && !s.isEmpty()) {
                if (c.length() > 0) c.append('\n'); c.append("[image: ").append(s).append("]");
            }
        String t = c.toString();
        return t.isEmpty() ? content : (content.isEmpty() ? t : content + "\n" + t);
    }

    static String addCliAppsBreadcrumbs(Map<String, Object> msg, String content) {
        Object cli = msg.get("cli_apps");
        if (!(cli instanceof List<?> cl)) return content;
        StringBuilder c = new StringBuilder(); int n = 0;
        for (Object item : cl) {
            if (n >= 8 || !(item instanceof Map<?, ?> app)) { n++; continue; } n++;
            String name = Objects.toString(app.get("name"), "").strip().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) continue;
            String ep = Objects.toString(app.get("entry_point"), "unknown").strip();
            if (ep.isEmpty()) ep = "unknown";
            if (c.length() > 0) c.append('\n');
            c.append(String.format("[CLI App Attachment: @%s; tool=run_cli_app; entry_point=%s; skill=skills/cli-app-%s/SKILL.md]", name, ep, name));
        }
        String t = c.toString();
        return t.isEmpty() ? content : (content.isEmpty() ? t : content + "\n" + t);
    }

    static String addMcpPresetsBreadcrumbs(Map<String, Object> msg, String content) {
        Object mcp = msg.get("mcp_presets");
        if (!(mcp instanceof List<?> ml)) return content;
        StringBuilder c = new StringBuilder(); int n = 0;
        for (Object item : ml) {
            if (n >= 8 || !(item instanceof Map<?, ?> p)) { n++; continue; } n++;
            String name = Objects.toString(p.get("name"), "").strip().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) continue;
            String transport = Objects.toString(p.get("transport"), "mcp").strip();
            if (transport.isEmpty()) transport = "mcp";
            if (c.length() > 0) c.append('\n');
            c.append(String.format("[MCP Preset Attachment: @%s; tool_prefix=mcp_%s_; transport=%s]", name, name, transport));
        }
        String t = c.toString();
        return t.isEmpty() ? content : (content.isEmpty() ? t : content + "\n" + t);
    }

    static String annotateMessageTime(Map<String, Object> msg, String content) {
        Object ts = msg.get("timestamp");
        if (ts == null || !"user".equals(msg.get("role"))) return content;
        return "[Message Time: " + ts + "]\n" + content;
    }

    static String textPreview(Object content) {
        String text;
        if (content instanceof String s) text = s;
        else if (content instanceof List<?> blocks) {
            List<String> parts = new ArrayList<>();
            for (Object b : blocks)
                if (b instanceof Map<?, ?> m && "text".equals(m.get("type"))) {
                    Object v = m.get("text"); if (v instanceof String s) parts.add(s);
                }
            text = String.join(" ", parts);
        } else return "";
        text = sanitizeAssistantReplayText(text).replaceAll("\\s+", " ").strip();
        if (text.length() > PREVIEW_MAX_CHARS)
            text = text.substring(0, PREVIEW_MAX_CHARS - 1).stripTrailing() + "…";
        return text;
    }

    static String messagePreviewText(Map<String, Object> msg) {
        Object content = msg.get("content");
        if ("subagent_result".equals(msg.get("injected_event")) && content instanceof String s)
            content = truncate(s, 300);
        return textPreview(content);
    }

    static String metadataTitle(Map<String, Object> metadata) {
        Object t = metadata.get("title");
        if (!(t instanceof String s) || s.isEmpty()) return "";
        if (Boolean.TRUE.equals(metadata.get("title_user_edited"))) return s;
        return s.replaceAll("(?s)<thinking>.*?</thinking>", "").strip();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max).stripTrailing() + "…";
    }
}
```

### 2.5 SessionTokenBudget.java (package-private)

```java
package com.nanobot.session;

import java.util.*;

/**
 * Token-budget trimming for get_history().
 * python_analog: max_tokens path in Session.get_history()
 */
final class SessionTokenBudget {
    private SessionTokenBudget() {}

    static List<Map<String, Object>> trim(List<Map<String, Object>> out, int maxTokens) {
        List<Map<String, Object>> kept = new ArrayList<>();
        int used = 0;
        for (int i = out.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = out.get(i);
            int tokens = estimateTokens(msg);
            if (!kept.isEmpty() && used + tokens > maxTokens) break;
            kept.add(msg); used += tokens;
        }
        Collections.reverse(kept);

        int firstUser = -1;
        for (int i = 0; i < kept.size(); i++)
            if ("user".equals(kept.get(i).get("role"))) { firstUser = i; break; }
        if (firstUser >= 0) kept = new ArrayList<>(kept.subList(firstUser, kept.size()));
        else {
            int recovered = -1;
            for (int i = out.size() - 1; i >= 0; i--)
                if ("user".equals(out.get(i).get("role"))) { recovered = i; break; }
            if (recovered >= 0) kept = new ArrayList<>(out.subList(recovered, out.size()));
        }
        int ls = SessionHelpers.findLegalMessageStart(kept);
        if (ls > 0) kept = new ArrayList<>(kept.subList(ls, kept.size()));
        return kept;
    }

    /** Rough token count: ~4 chars/token + per-message overhead. */
    private static int estimateTokens(Map<String, Object> msg) {
        String text = Objects.toString(msg.get("content"), "");
        int base = 8 + (text.length() + 3) / 4;
        Object tc = msg.get("tool_calls");
        if (tc instanceof List<?> tcl)
            for (Object item : tcl)
                if (item instanceof Map<?, ?> tcm && tcm.get("function") instanceof Map<?, ?> fm) {
                    base += 32;
                    Object args = fm.get("arguments");
                    if (args instanceof String a) base += (a.length() + 3) / 4;
                }
        return base;
    }
}
```

### 2.6 SessionConstants.java

```java
package com.nanobot.session;

import java.util.List;
import java.util.Set;

/** python_analog: module-level constants across session/*.py */
public final class SessionConstants {
    private SessionConstants() {}

    public static final int FILE_MAX_MESSAGES = 2000;
    public static final List<String> LLM_MESSAGE_KEYS = List.of(
        "tool_calls", "tool_call_id", "name", "reasoning_content", "thinking_blocks");
    public static final Set<String> FORK_VOLATILE_METADATA_KEYS = Set.of(
        "goal_state", "pending_user_turn", "runtime_checkpoint",
        "thread_goal", "title", "title_user_edited");
}
```

### 2.7 SessionManager.java

```java
package com.nanobot.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages conversation sessions stored as JSONL.
 *
 * File format: line 1 = metadata (_type: "metadata"), subsequent lines = message records.
 * Atomic writes: write .jsonl.tmp → Files.move(ATOMIC_MOVE) → optional fsync.
 * Corruption repair: parse line-by-line, skip JSONDecodeError lines.
 *
 * python_analog: nanobot.session.manager.SessionManager
 */
@Component
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final Path sessionsDir;
    private final Path legacySessionsDir;
    private final ConcurrentHashMap<String, Session> cache;

    public SessionManager(Path workspacePath) {
        this.sessionsDir = createDir(workspacePath.resolve("sessions"));
        this.legacySessionsDir = Path.of(System.getProperty("user.home"), ".nanobot", "sessions");
        this.cache = new ConcurrentHashMap<>();
    }

    // --- path helpers ---

    public static String safeKey(String key) {
        return key.replace(":", "_").replaceAll("[^a-zA-Z0-9_.\\-]", "_");
    }

    public Path getSessionPath(String key) { return sessionsDir.resolve(safeKey(key) + ".jsonl"); }
    private Path legacyPath(String key) { return legacySessionsDir.resolve(safeKey(key) + ".jsonl"); }

    // --- get_or_create (python_analog: get_or_create) ---

    public Session getOrCreate(String key) {
        Session cached = cache.get(key);
        if (cached != null) return cached;
        Session loaded = load(key);
        if (loaded == null) loaded = new Session(key);
        Session existing = cache.putIfAbsent(key, loaded);
        return existing != null ? existing : loaded;
    }

    // --- load (python_analog: _load) ---

    private Session load(String key) {
        Path path = getSessionPath(key);
        if (!Files.exists(path)) {
            Path lp = legacyPath(key);
            if (Files.exists(lp)) {
                try { Files.move(lp, path, StandardCopyOption.ATOMIC_MOVE);
                    log.info("Migrated session {} from legacy path", key); }
                catch (IOException e) { log.warn("Failed to migrate session {}: {}", key, e.getMessage()); }
            }
        }
        if (!Files.exists(path)) return null;
        try { return parseFile(key, path); }
        catch (Exception e) {
            log.warn("Failed to load session {}: {}", key, e.getMessage());
            Session repaired = repair(key);
            if (repaired != null) log.info("Recovered session {} from corrupt file ({} messages)", key, repaired.getMessages().size());
            return repaired;
        }
    }

    // --- read-only load (python_analog: read_session_file) ---

    public Map<String, Object> readSessionFile(String key) {
        Path path = getSessionPath(key);
        if (!Files.exists(path)) return null;
        try { return sessionPayload(parseFile(key, path)); }
        catch (Exception e) {
            log.warn("Failed to read session {}: {}", key, e.getMessage());
            Session repaired = repair(key);
            return (repaired != null) ? sessionPayload(repaired) : null;
        }
    }

    // --- save (python_analog: save, with atomic write) ---

    /**
     * Save a session atomically:
     * 1. Write metadata line + message lines to {key}.jsonl.tmp
     * 2. Files.move(tmp, path, ATOMIC_MOVE) — observers see old or new, never partial
     * 3. Optionally fsync file + directory
     * On exception: delete .tmp
     */
    public void save(Session session, boolean fsync) {
        Path path = getSessionPath(session.getKey());
        Path tmpPath = path.resolveSibling(path.getFileName() + ".tmp");

        ReentrantReadWriteLock.ReadLock rl = session.getLock().readLock();
        rl.lock();
        try {
            try (BufferedWriter w = Files.newBufferedWriter(tmpPath, StandardCharsets.UTF_8)) {
                // Line 1: metadata
                Map<String, Object> metaLine = new LinkedHashMap<>();
                metaLine.put("_type", "metadata");
                metaLine.put("key", session.getKey());
                metaLine.put("created_at", ISO_FMT.format(
                    session.getCreatedAt().atOffset(java.time.ZoneOffset.UTC)));
                metaLine.put("updated_at", ISO_FMT.format(
                    session.getUpdatedAt().atOffset(java.time.ZoneOffset.UTC)));
                metaLine.put("metadata", session.getMetadata());
                metaLine.put("last_consolidated", session.getLastConsolidated());
                w.write(MAPPER.writeValueAsString(metaLine)); w.newLine();
                // Subsequent lines: messages
                for (Map<String, Object> msg : session.getMessages()) {
                    w.write(MAPPER.writeValueAsString(msg)); w.newLine();
                }
                w.flush();
            }

            // Fsync file if requested
            if (fsync) {
                try (FileChannel fc = FileChannel.open(tmpPath, StandardOpenOption.READ)) {
                    fc.force(true);
                }
            }

            // Atomic rename
            Files.move(tmpPath, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

            // Fsync directory (skip on Windows — NTFS journals metadata)
            if (fsync) {
                try { FileChannel.open(path.getParent(), StandardOpenOption.READ).force(true); }
                catch (Exception e) { log.debug("Dir fsync skipped: {}", e.getMessage()); }
            }
        } catch (IOException e) {
            try { Files.deleteIfExists(tmpPath); } catch (IOException ignored) {}
            throw new UncheckedIOException("Failed to save session " + session.getKey(), e);
        } finally { rl.unlock(); }
        cache.put(session.getKey(), session);
    }

    public void save(Session session) { save(session, false); }

    // --- repair (python_analog: _repair) ---

    /**
     * Recover from corrupt JSONL: parse line by line, skip any JSONDecodeError.
     * Returns null only if no valid data was found.
     */
    private Session repair(String key) {
        Path path = getSessionPath(key);
        if (!Files.exists(path)) return null;
        try {
            List<Map<String, Object>> messages = new ArrayList<>();
            Map<String, Object> metadata = new LinkedHashMap<>();
            String createdStr = null, updatedStr = null;
            int lc = 0, skipped = 0;

            try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.strip();
                    if (line.isEmpty()) continue;
                    Map<String, Object> data;
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> parsed = (Map<String, Object>) MAPPER.readValue(line, Map.class);
                        data = parsed;
                    } catch (Exception e) { skipped++; continue; }

                    if ("metadata".equals(data.get("_type"))) {
                        metadata = safeMap(data, "metadata");
                        createdStr = safeStr(data, "created_at");
                        updatedStr = safeStr(data, "updated_at");
                        Object lcObj = data.get("last_consolidated");
                        lc = (lcObj instanceof Number n) ? n.intValue() : 0;
                    } else { messages.add(data); }
                }
            }
            if (skipped > 0) log.warn("Skipped {} corrupt lines in session {}", skipped, key);
            if (messages.isEmpty() && metadata.isEmpty()) return null;

            Instant ct = parseIso(createdStr), ut = parseIso(updatedStr);
            return new Session(key, messages, metadata,
                ct != null ? ct : Instant.now(), ut != null ? ut : Instant.now(), lc);
        } catch (Exception e) {
            log.warn("Repair failed for session {}: {}", key, e.getMessage());
            return null;
        }
    }

    // --- list (python_analog: list_sessions) ---

    public List<Map<String, Object>> listSessions() {
        List<Map<String, Object>> result = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(sessionsDir, "*.jsonl")) {
            for (Path p : ds) {
                String fk = p.getFileName().toString().replace(".jsonl", "").replace("_", ":", 1);
                try {
                    Map<String, Object> info = readPreview(p, fk);
                    if (info != null) result.add(info);
                } catch (Exception e) {
                    Session repaired = repair(fk);
                    if (repaired != null) result.add(previewFromRepaired(repaired, p));
                }
            }
        } catch (IOException e) { log.warn("Failed to list sessions: {}", e.getMessage()); }
        result.sort(Comparator.comparing(
            m -> Objects.toString(m.get("updated_at"), ""), Comparator.reverseOrder()));
        return result;
    }

    // --- delete / invalidate / flush (python_analog) ---

    public boolean deleteSession(String key) {
        invalidate(key);
        try { return Files.deleteIfExists(getSessionPath(key)); }
        catch (IOException e) { log.warn("Failed to delete {}: {}", key, e.getMessage()); return false; }
    }

    public void invalidate(String key) { cache.remove(key); }

    /** python_analog: flush_all() */
    public int flushAll() {
        int n = 0;
        for (Map.Entry<String, Session> e : cache.entrySet()) {
            try { save(e.getValue(), true); n++; }
            catch (Exception ex) { log.warn("Failed to flush session {}", e.getKey(), ex); }
        }
        return n;
    }

    // --- fork (python_analog: fork_session_before_user_index) ---

    public Session forkSessionBeforeUserIndex(String srcKey, String tgtKey, int beforeUserIdx) {
        if (beforeUserIdx < 0) return null;
        Session source = cache.getOrDefault(srcKey, load(srcKey));
        if (source == null) return null;

        List<Map<String, Object>> copied = new ArrayList<>();
        int userIdx = 0;
        boolean found = false;
        for (Map<String, Object> msg : source.getMessages()) {
            if ("user".equals(msg.get("role"))) {
                if (userIdx == beforeUserIdx) { found = true; break; }
                userIdx++;
            }
            copied.add(deepCopy(msg));
        }
        if (userIdx == beforeUserIdx) found = true;
        if (!found) return null;

        Map<String, Object> meta = deepCopy(source.getMetadata());
        for (String k : SessionConstants.FORK_VOLATILE_METADATA_KEYS) meta.remove(k);
        int lc = Math.min(source.getLastConsolidated(), copied.size());
        if (source.getLastConsolidated() > copied.size()) { meta.remove("_last_summary"); lc = 0; }

        Instant now = Instant.now();
        Session target = new Session(tgtKey, copied, meta, now, now, lc);
        save(target, true);
        return target;
    }

    // --- internal ---

    private Session parseFile(String key, Path path) throws IOException {
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> metadata = new LinkedHashMap<>();
        String createdStr = null, updatedStr = null;
        int lc = 0;

        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) MAPPER.readValue(line, Map.class);
                if ("metadata".equals(data.get("_type"))) {
                    metadata = safeMap(data, "metadata");
                    createdStr = safeStr(data, "created_at");
                    updatedStr = safeStr(data, "updated_at");
                    Object lcObj = data.get("last_consolidated");
                    lc = (lcObj instanceof Number n) ? n.intValue() : 0;
                } else { messages.add(data); }
            }
        }
        Instant ct = parseIso(createdStr), ut = parseIso(updatedStr);
        return new Session(key, messages, metadata,
            ct != null ? ct : Instant.now(), ut != null ? ut : Instant.now(), lc);
    }

    /** Read first user/assistant message as list preview. */
    private Map<String, Object> readPreview(Path path, String fallbackKey) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String fl = r.readLine();
            if (fl == null) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) MAPPER.readValue(fl.strip(), Map.class);
            if (!"metadata".equals(data.get("_type"))) return null;

            String key = Objects.toString(data.get("key"), fallbackKey);
            Map<String, Object> meta = safeMap(data, "metadata");
            String title = SessionSanitizer.metadataTitle(meta);
            String preview = "", fallback = "";
            int records = 0, chars = 0;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.strip().isEmpty()) continue;
                records++; chars += line.length();
                if (records > 200 || chars > 1_000_000) break;
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) MAPPER.readValue(line, Map.class);
                if ("metadata".equals(item.get("_type"))) continue;
                String text = SessionSanitizer.messagePreviewText(item);
                if (text.isEmpty()) continue;
                if ("user".equals(item.get("role"))) { preview = text; break; }
                if (fallback.isEmpty() && "assistant".equals(item.get("role"))) fallback = text;
            }
            preview = preview.isEmpty() ? fallback : preview;
            return Map.of("key", key, "created_at", data.get("created_at"),
                "updated_at", data.get("updated_at"), "title", title, "preview", preview, "path", path.toString());
        }
    }

    private Map<String, Object> previewFromRepaired(Session s, Path path) {
        String preview = "";
        for (Map<String, Object> m : s.getMessages()) {
            String t = SessionSanitizer.messagePreviewText(m);
            if (!t.isEmpty()) { preview = t; break; }
        }
        return Map.of("key", s.getKey(),
            "created_at", ISO_FMT.format(s.getCreatedAt().atOffset(java.time.ZoneOffset.UTC)),
            "updated_at", ISO_FMT.format(s.getUpdatedAt().atOffset(java.time.ZoneOffset.UTC)),
            "title", SessionSanitizer.metadataTitle(s.getMetadata()),
            "preview", preview, "path", path.toString());
    }

    private Map<String, Object> sessionPayload(Session s) {
        return Map.of("key", s.getKey(),
            "created_at", ISO_FMT.format(s.getCreatedAt().atOffset(java.time.ZoneOffset.UTC)),
            "updated_at", ISO_FMT.format(s.getUpdatedAt().atOffset(java.time.ZoneOffset.UTC)),
            "metadata", s.getMetadata(), "messages", s.getMessages());
    }

    // --- static utilities ---
    @SuppressWarnings("unchecked")
    private static Map<String, Object> safeMap(Map<String, Object> d, String k) {
        Object v = d.get(k); return (v instanceof Map) ? (Map<String, Object>) v : new LinkedHashMap<>();
    }
    private static String safeStr(Map<String, Object> d, String k) {
        Object v = d.get(k); return (v instanceof String) ? (String) v : null;
    }
    private static Instant parseIso(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return OffsetDateTime.parse(s, ISO_FMT).toInstant(); }
        catch (DateTimeParseException e) { return null; }
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> src) {
        try {
            return (Map<String, Object>) MAPPER.readValue(
                MAPPER.writeValueAsString(src), Map.class);
        } catch (IOException e) { return new LinkedHashMap<>(src); }
    }
    private static Path createDir(Path dir) {
        try { Files.createDirectories(dir); } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return dir;
    }
}
```

## 3. 原子 I/O 与正确性保证

### 3.1 写入流程

```
save(session, fsync=false):
  1. Serialize metadata + messages → {key}.jsonl.tmp
  2. writer.flush()
  3. if fsync: FileChannel.open(tmp).force(true)     // 文件 fsync
  4. Files.move(tmp, path, ATOMIC_MOVE, REPLACE_EXISTING)  // 原子重命名
  5. if fsync: FileChannel.open(sessionsDir).force(true)   // 目录 fsync
  6. On IOException: Files.deleteIfExists(tmp)
```

**保证:**
- `ATOMIC_MOVE`: 观察者永远不会看到部分写入的文件
- 异常安全: temp 文件在 finally 块中删除
- `fsync` 仅在 `flushAll()` 关闭时使用；正常运行依赖 OS page cache
- 目录 fsync: Windows 上尝试打开目录可能失败 → 捕获并忽略（NTFS 日志化 metadata）

### 3.2 Corruption Repair

修复 (`_repair`) 与正常加载 (`parseFile`) 使用相同逻辑，区别在于:
- `parseFile`: 任何 JSON parse 异常都会向上传播 → 触发 `_repair()`
- `_repair`: 逐行解析，每个 `JSONDecodeError` 跳过并计数，不抛异常
- 修复后验证至少有 1 条有效数据，否则返回 null

## 4. GoalState.java

```java
package com.nanobot.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;
import java.util.*;

/**
 * Session metadata helpers for sustained goals.
 * python_analog: nanobot.session.goal_state
 */
public final class GoalState {
    private GoalState() {}

    public static final String GOAL_STATE_KEY = "goal_state";
    private static final String LEGACY_KEY = "thread_goal";
    private static final int MAX_RUNTIME = 4000;
    private static final int MAX_WS = 600;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** python_analog: _session_goal_raw(metadata) */
    @Nullable
    public static Object goalStateRaw(@Nullable Map<String, Object> metadata) {
        if (metadata == null) return null;
        if (metadata.containsKey(GOAL_STATE_KEY)) return metadata.get(GOAL_STATE_KEY);
        return metadata.get(LEGACY_KEY);
    }

    public static void discardLegacyKey(Map<String, Object> metadata) {
        metadata.remove(LEGACY_KEY);
    }

    /** python_analog: parse_goal_state(blob) */
    @Nullable
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseGoalState(@Nullable Object blob) {
        if (blob == null) return null;
        if (blob instanceof Map) return (Map<String, Object>) blob;
        if (blob instanceof String s) {
            try {
                Object parsed = MAPPER.readValue(s, Object.class);
                return (parsed instanceof Map) ? (Map<String, Object>) parsed : null;
            } catch (JsonProcessingException e) { return null; }
        }
        return null;
    }

    /** python_analog: sustained_goal_active(metadata) */
    public static boolean sustainedGoalActive(@Nullable Map<String, Object> metadata) {
        Map<String, Object> goal = parseGoalState(goalStateRaw(metadata));
        return goal != null && "active".equals(goal.get("status"));
    }

    /** python_analog: sustained_goal_turn(metadata, *, message_metadata) */
    public static boolean sustainedGoalTurn(
        @Nullable Map<String, Object> metadata, @Nullable Map<String, Object> messageMetadata) {
        if (sustainedGoalActive(metadata)) return true;
        if (messageMetadata == null) return false;
        return "/goal".equals(Objects.toString(messageMetadata.get("original_command"), "").strip());
    }

    /** python_analog: goal_state_runtime_lines(metadata) — generates LLM context lines */
    public static List<String> goalStateRuntimeLines(@Nullable Map<String, Object> metadata) {
        if (metadata == null) return List.of();
        Map<String, Object> goal = parseGoalState(goalStateRaw(metadata));
        if (goal == null || !"active".equals(goal.get("status"))) return List.of();
        String obj = Objects.toString(goal.get("objective"), "").strip();
        if (obj.isEmpty()) return List.of("Goal: active (no objective text stored).");
        if (obj.length() > MAX_RUNTIME) obj = obj.substring(0, MAX_RUNTIME).stripTrailing() + "\n… (truncated)";
        List<String> out = new ArrayList<>(); out.add("Goal (active):"); out.add(obj);
        String hint = Objects.toString(goal.get("ui_summary"), "").strip();
        if (!hint.isEmpty()) out.add("Summary: " + hint);
        return out;
    }

    /** python_analog: goal_state_ws_blob(metadata) — WebSocket snapshot */
    public static Map<String, Object> goalStateWsBlob(@Nullable Map<String, Object> metadata) {
        if (metadata == null) return Map.of("active", false);
        Map<String, Object> goal = parseGoalState(goalStateRaw(metadata));
        if (goal == null || !"active".equals(goal.get("status"))) return Map.of("active", false);
        String obj = Objects.toString(goal.get("objective"), "").strip();
        if (obj.length() > MAX_WS) obj = obj.substring(0, MAX_WS).stripTrailing() + "…";
        String summary = Objects.toString(goal.get("ui_summary"), "").strip();
        if (summary.length() > 120) summary = summary.substring(0, 120);
        Map<String, Object> blob = new LinkedHashMap<>();
        blob.put("active", true);
        if (!summary.isEmpty()) blob.put("ui_summary", summary);
        if (!obj.isEmpty()) blob.put("objective", obj);
        return blob;
    }

    /** python_analog: runner_wall_llm_timeout_s(...) → 0.0 for goal turns, null for normal */
    @Nullable
    public static Double runnerWallLlmTimeoutS(
        SessionManager sessions, @Nullable String sessionKey,
        @Nullable Map<String, Object> metadata, @Nullable Map<String, Object> messageMetadata) {
        Map<String, Object> meta = metadata;
        if (meta == null && sessionKey != null) meta = sessions.getOrCreate(sessionKey).getMetadata();
        return sustainedGoalTurn(meta, messageMetadata) ? 0.0 : null;
    }
}
```

## 5. TurnContinuation.java

```java
package com.nanobot.session;

import com.nanobot.bus.InboundMessage;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.BlockingQueue;

/**
 * Budget-boundary continuation policy. When a turn hits max_iterations and a
 * sustained goal is active, queues an internal continuation via the pending queue.
 *
 * python_analog: nanobot.session.turn_continuation
 */
public final class TurnContinuation {
    private static final Logger log = LoggerFactory.getLogger(TurnContinuation.class);
    private TurnContinuation() {}

    // --- metadata keys ---
    public static final String INTERNAL_CONTINUATION_META = "_internal_continuation";
    public static final String INTERNAL_CONTINUATION_KIND_META = "_internal_continuation_kind";
    public static final String INTERNAL_CONTINUATION_PENDING_META = "_internal_continuation_pending";
    public static final String INTERNAL_CONTINUATION_RUN_STARTED_META = "_internal_continuation_run_started_at";
    private static final String GOAL_KIND = "sustained_goal";
    private static final String GOAL_SENDER = "system:continuation";
    private static final String GOAL_ROUNDS_KEY = "_sustained_goal_continuation_rounds";
    private static final int MAX_ROUNDS = 12;
    private static final Set<String> STRIPPED_KEYS = Set.of(
        "_stream_id", "_stream_delta", "_stream_end", "_resuming", INTERNAL_CONTINUATION_PENDING_META);

    // --- predicates ---
    public static boolean isInternalContinuation(@Nullable Map<String, Object> meta) {
        return meta != null && Boolean.TRUE.equals(meta.get(INTERNAL_CONTINUATION_META));
    }
    public static boolean isInternalContinuationPending(@Nullable Map<String, Object> meta) {
        return meta != null && Boolean.TRUE.equals(meta.get(INTERNAL_CONTINUATION_PENDING_META));
    }
    /** python_analog: internal_continuation_run_started_at(metadata) */
    @Nullable
    public static Double internalContinuationRunStartedAt(@Nullable Map<String, Object> meta) {
        if (meta == null) return null;
        Object v = meta.get(INTERNAL_CONTINUATION_RUN_STARTED_META);
        return (v instanceof Number n) ? (n.doubleValue() > 0 ? n.doubleValue() : null) : null;
    }
    public static boolean shouldPersistUserMessage(@Nullable Map<String, Object> meta) {
        return !isInternalContinuation(meta);
    }
    public static boolean shouldStreamBudgetResponse(
        String stopReason, boolean pendingAvailable,
        @Nullable Map<String, Object> sessionMeta, @Nullable Map<String, Object> msgMeta) {
        return !"max_iterations".equals(stopReason)
            || shouldFinalizeOnMaxIterations(pendingAvailable, sessionMeta, msgMeta);
    }
    public static boolean shouldFinalizeOnMaxIterations(
        boolean pendingAvailable, @Nullable Map<String, Object> sessionMeta,
        @Nullable Map<String, Object> msgMeta) {
        return !(pendingAvailable && goalContinuationAvailable(sessionMeta, msgMeta));
    }
    public static void clearInternalContinuationState(Map<String, Object> meta) {
        if (!GoalState.sustainedGoalActive(meta)) meta.remove(GOAL_ROUNDS_KEY);
    }
    /** python_analog: _save_skip_for_turn(...) */
    public static int saveSkipForTurn(
        @Nullable Map<String, Object> msgMeta, int initialCount, int historyCount, boolean userEarly) {
        return isInternalContinuation(msgMeta) ? initialCount
            : 1 + historyCount + (userEarly ? 1 : 0);
    }

    /** python_analog: _goal_continuation_prompt(metadata) */
    public static String goalContinuationPrompt(@Nullable Map<String, Object> meta) {
        List<String> lines = GoalState.goalStateRuntimeLines(meta);
        if (!lines.isEmpty())
            return "Continue the active sustained goal after the previous turn reached its tool-call budget.\n\n"
                + String.join("\n", lines) + "\n\nContinue from the saved context. Do not mention the continuation "
                + "boundary to the user. Use tools as needed, and call complete_goal when the objective is truly finished.";
        return "Continue the active sustained goal after the previous turn reached its tool-call budget. "
            + "Continue from the saved context. Do not mention the continuation boundary to the user. "
            + "Use tools as needed, and call complete_goal when the objective is truly finished.";
    }

    /** python_analog: _strip_terminal_assistant(messages, final_content) */
    public static List<Map<String, Object>> stripTerminalAssistant(
        List<Map<String, Object>> messages, @Nullable String finalContent) {
        if (messages.isEmpty()) return messages;
        int last = messages.size() - 1;
        Map<String, Object> lastMsg = messages.get(last);
        if (!"assistant".equals(lastMsg.get("role"))) return messages;
        if (finalContent == null || !finalContent.equals(lastMsg.get("content"))) return messages;
        if (lastMsg.containsKey("tool_calls")) return messages;
        List<Map<String, Object>> result = new ArrayList<>(messages);
        result.remove(last);
        return result;
    }

    // --- internal ---
    static boolean goalContinuationAvailable(
        @Nullable Map<String, Object> sessionMeta, @Nullable Map<String, Object> msgMeta) {
        if (!GoalState.sustainedGoalTurn(sessionMeta, msgMeta)) return false;
        if (!GoalState.sustainedGoalActive(sessionMeta)) return false;
        int rounds = (sessionMeta != null && sessionMeta.get(GOAL_ROUNDS_KEY) instanceof Number n)
            ? n.intValue() : 0;
        return rounds < MAX_ROUNDS;
    }

    private static boolean continuationAvailable(
        String stopReason, boolean pendingAvailable,
        @Nullable Map<String, Object> sessionMeta, @Nullable Map<String, Object> msgMeta) {
        return "max_iterations".equals(stopReason) && pendingAvailable
            && goalContinuationAvailable(sessionMeta, msgMeta);
    }

    private static Map<String, Object> buildContinuationMeta(
        @Nullable Map<String, Object> msgMeta, @Nullable Double runStartedAt) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (msgMeta != null) meta.putAll(msgMeta);
        meta.put(INTERNAL_CONTINUATION_META, Boolean.TRUE);
        meta.put(INTERNAL_CONTINUATION_KIND_META, GOAL_KIND);
        if (runStartedAt != null && runStartedAt > 0)
            meta.put(INTERNAL_CONTINUATION_RUN_STARTED_META, runStartedAt);
        for (String k : STRIPPED_KEYS) meta.remove(k);
        return meta;
    }

    private static void incrementGoalRound(Map<String, Object> meta) {
        int r = (meta.get(GOAL_ROUNDS_KEY) instanceof Number n) ? n.intValue() : 0;
        meta.put(GOAL_ROUNDS_KEY, r + 1);
    }

    /**
     * The core continuation trigger. Called by AgentLoop after a turn completes.
     *
     * python_analog: async def maybe_continue_turn(ctx) -> bool
     */
    public static boolean maybeContinueTurn(TurnContinuationContext ctx) {
        if (ctx.getSession() == null || ctx.getPendingQueue() == null) return false;
        if (!continuationAvailable(ctx.getStopReason(), true,
            ctx.getSession().getMetadata(), ctx.getMessageMetadata())) return false;

        Map<String, Object> newMeta = buildContinuationMeta(
            ctx.getMessageMetadata(), ctx.getVisibleRunStartedAt());
        String prompt = goalContinuationPrompt(ctx.getSession().getMetadata());
        List<Map<String, Object>> stripped = stripTerminalAssistant(
            ctx.getAllMessages(), ctx.getFinalContent());
        incrementGoalRound(ctx.getSession().getMetadata());

        log.info("Turn budget reached; scheduling internal continuation");
        ctx.getMessageMetadata().put(INTERNAL_CONTINUATION_PENDING_META, Boolean.TRUE);

        InboundMessage contMsg = new InboundMessage(
            ctx.getOriginalMsg().channel(), GOAL_SENDER, ctx.getOriginalMsg().chatId(),
            prompt, null, List.of(), newMeta, ctx.getSessionKey());

        try { ctx.getPendingQueue().put(contMsg); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        return true;
    }

    /** Context interface implemented by AgentLoop's turn state. */
    public interface TurnContinuationContext {
        Session getSession();
        @Nullable BlockingQueue<InboundMessage> getPendingQueue();
        InboundMessage getOriginalMsg();
        @Nullable Map<String, Object> getMessageMetadata();
        String getStopReason();
        @Nullable Double getVisibleRunStartedAt();
        List<Map<String, Object>> getAllMessages();
        @Nullable String getFinalContent();
        @Nullable String getSessionKey();
    }
}
```

## 6. WebuiTurnCoordinator.java

```java
package com.nanobot.session;

import com.nanobot.bus.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Translates runtime events into WebUI/WebSocket wire messages.
 * Subscribes to all 5 RuntimeEvent types.
 *
 * python_analog: nanobot.session.webui_turns.WebuiTurnCoordinator
 */
public class WebuiTurnCoordinator {

    private final MessageBus bus;
    private final SessionManager sessions;
    private final ExecutorService background;
    private final ConcurrentHashMap<String, Object> titleContexts;  // sessionKey → LLMRuntime

    public WebuiTurnCoordinator(MessageBus bus, SessionManager sessions) {
        this.bus = bus;
        this.sessions = sessions;
        this.background = Executors.newVirtualThreadPerTaskExecutor();
        this.titleContexts = new ConcurrentHashMap<>();
    }

    // --- subscribe (python_analog: WebuiTurnCoordinator.subscribe) ---
    public Runnable subscribe(RuntimeEventBus events) {
        Runnable u1 = events.subscribe(this::onSessionTurnStarted, SessionTurnStarted.class);
        Runnable u2 = events.subscribe(this::onRunStatusChanged, TurnRunStatusChanged.class);
        Runnable u3 = events.subscribe(this::onTurnCompleted, TurnCompleted.class);
        Runnable u4 = events.subscribe(this::onGoalStateChanged, GoalStateChanged.class);
        Runnable u5 = events.subscribe(this::onRuntimeModelChanged, RuntimeModelChanged.class);
        return () -> { u5.run(); u4.run(); u3.run(); u2.run(); u1.run(); };
    }

    public void captureTitleContext(String sk, InboundMessage msg, Object llm) {
        if ("websocket".equals(msg.channel()) && Boolean.TRUE.equals(msg.metadata().get("webui")))
            titleContexts.put(sk, llm);
    }
    public void discard(String sk) { titleContexts.remove(sk); }

    // --- event handlers ---
    private void onSessionTurnStarted(SessionTurnStarted e) {
        if (!isWs(e.context())) return;
        Session s = sessions.getOrCreate(e.context().sessionKey());
        if (Boolean.TRUE.equals(e.context().metadata().get("webui")))
            s.getMetadata().put("webui", Boolean.TRUE);
    }

    private void onRunStatusChanged(TurnRunStatusChanged e) {
        if (!isWs(e.context())) return;
        String cid = e.context().chatId();
        Map<String, Object> meta = new LinkedHashMap<>(e.context().metadata());
        meta.put("_goal_status", Boolean.TRUE); meta.put("goal_status", e.status());
        if ("running".equals(e.status()))
            meta.put("started_at", (e.startedAt() != null && e.startedAt() > 0) ? e.startedAt() : currentEpoch());
        publish(cid, e.context().channel(), meta);
    }

    private void onTurnCompleted(TurnCompleted e) {
        if (!isWs(e.context())) return;
        Map<String, Object> meta = new LinkedHashMap<>(e.context().metadata());
        meta.put("_turn_end", Boolean.TRUE);
        if (e.latencyMs() != null) meta.put("latency_ms", e.latencyMs());
        Session s = sessions.getOrCreate(e.context().sessionKey());
        meta.put("goal_state", GoalState.goalStateWsBlob(s.getMetadata()));
        publish(e.context().chatId(), e.context().channel(), meta);
    }

    private void onGoalStateChanged(GoalStateChanged e) {
        if (!isWs(e.context())) return;
        publish(e.context().chatId(), e.context().channel(), Map.of(
            "_goal_state_sync", Boolean.TRUE,
            "goal_state", GoalState.goalStateWsBlob(e.sessionMetadata())));
    }

    private void onRuntimeModelChanged(RuntimeModelChanged e) {
        publish("*", "websocket", Map.of(
            "_runtime_model_updated", Boolean.TRUE,
            "model", e.model(),
            "model_preset", Objects.toString(e.modelPreset(), "")));
    }

    private void publish(String chatId, String channel, Map<String, Object> meta) {
        try { bus.publishOutbound(new OutboundMessage(channel, chatId, "", null, List.of(), meta, List.of())); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    private static boolean isWs(RuntimeEventContext c) { return "websocket".equals(c.channel()); }
    private static double currentEpoch() { return System.currentTimeMillis() / 1000.0; }
}
```

## 7. WebuiTitleGenerator.java

```java
package com.nanobot.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Generates short session titles for WebUI-owned sessions via LLM.
 * python_analog: maybe_generate_webui_title, clean_generated_title, _title_inputs
 */
public final class WebuiTitleGenerator {
    private static final Logger log = LoggerFactory.getLogger(WebuiTitleGenerator.class);
    private WebuiTitleGenerator() {}

    private static final int MAX_CHARS = 60;
    private static final Pattern LEAD_RE = Pattern.compile("^\\s*(title|标题)\\s*[:：]\\s*", Pattern.CASE_INSENSITIVE);

    public static String cleanGeneratedTitle(String raw) {
        String text = (raw != null ? raw : "").strip();
        if (text.isEmpty()) return "";
        text = LEAD_RE.matcher(text).replaceFirst("");
        text = text.strip().replaceAll("^[\"'`" + "“" + "”" + "‘’]+|[\"'`" + "“" + "”" + "‘’]+$", "");
        text = text.replaceAll("(?s)<thinking>.*?</thinking>", "").strip();
        text = text.replaceAll("\\s+", " ").strip();
        text = text.replaceAll("[。.!！?？,，;；:]$", "");
        if (text.length() > MAX_CHARS) text = text.substring(0, MAX_CHARS - 1).stripTrailing() + "…";
        return text;
    }

    /** Extract first user + first assistant text for title input. */
    public static TitleInputs titleInputs(Session session) {
        String u = "", a = "";
        for (Map<String, Object> msg : session.getMessages()) {
            if (Boolean.TRUE.equals(msg.get("_command"))) continue;
            String role = Objects.toString(msg.get("role"), "");
            Object content = msg.get("content");
            if (!(content instanceof String s) || s.strip().isEmpty()) continue;
            String clean = s.replaceAll("(?s)<thinking>.*?</thinking>", "").strip();
            if (clean.isEmpty()) continue;
            if ("user".equals(role) && u.isEmpty()) u = clean;
            else if ("assistant".equals(role) && a.isEmpty()) a = clean;
            if (!u.isEmpty() && !a.isEmpty()) break;
        }
        return new TitleInputs(u, a);
    }

    public record TitleInputs(String userText, String assistantText) {}

    /**
     * Generate and persist a title for WebUI sessions only.
     * Returns true if a title was generated.
     */
    public static boolean maybeGenerateTitle(
        SessionManager sm, String sessionKey,
        com.nanobot.providers.base.LLMProvider provider, String model) {
        Session s = sm.getOrCreate(sessionKey);
        if (!Boolean.TRUE.equals(s.getMetadata().get("webui"))) return false;
        if (Boolean.TRUE.equals(s.getMetadata().get("title_user_edited"))) return false;

        Object cur = s.getMetadata().get("title");
        if (cur instanceof String c && !c.strip().isEmpty()) {
            String cleaned = cleanGeneratedTitle(c);
            if (!cleaned.isEmpty()) {
                if (!cleaned.equals(c)) { s.getMetadata().put("title", cleaned); sm.save(s); }
                return false;
            }
            s.getMetadata().remove("title");
        }

        TitleInputs inputs = titleInputs(s);
        if (inputs.userText().isEmpty()) return false;

        String prompt = "Generate a concise title for this chat.\nRules:\n"
            + "- Use the same language as the user when practical.\n- 3 to 8 words.\n"
            + "- No quotes.\n- No punctuation at the end.\n- Return only the title.\n\n"
            + "User: " + truncate(inputs.userText(), 1000);
        if (!inputs.assistantText().isEmpty()) prompt += "\nAssistant: " + truncate(inputs.assistantText(), 1000);

        try {
            // LLM call stub — in production: provider.chatWithRetry(systemPrompt, userMsg,
            //   model=model, max_tokens=96, temperature=0.2, reasoning_effort="none", retry_mode="standard")
            String generated = ""; // placeholder
            String title = cleanGeneratedTitle(generated);
            if (title.isEmpty() || title.toLowerCase(Locale.ROOT).startsWith("error")) {
                log.debug("Title generation returned no usable title for {}", sessionKey);
                return false;
            }
            s.getMetadata().put("title", title);
            sm.save(s);
            return true;
        } catch (Exception e) {
            log.debug("Failed to generate title for {}", sessionKey, e);
            return false;
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max).stripTrailing() + "…";
    }
}
```

## 8. 线程安全分析

| 数据结构 | 策略 | 理由 |
|----------|------|------|
| `Session.messages` | `CopyOnWriteArrayList` | 追加为主，读取频繁，COW 零开销读 |
| `Session` 多步操作 | `ReentrantReadWriteLock` | `getHistory`/`save` 读锁，`clear`/`retain` 写锁 |
| `SessionManager.cache` | `ConcurrentHashMap` | 内置线程安全，`putIfAbsent` 防重复创建 |
| `WebuiTurnCoordinator.titleContexts` | `ConcurrentHashMap` | 可能并发访问 |

**CopyOnWriteArrayList vs synchronized wrapper:**
- COW: 写时复制 O(n)，读取零开销 — 适合 session 场景（消息 < 2000，写频率低）
- `Collections.synchronizedList`: 所有操作加锁 — 读取也要竞争锁，性能差

**锁层次：** SessionManager 的 `save()` 持有 Session 的 read lock — 与 `getHistory()` 共存，与 `clear()`/`retain` 互斥。

## 9. Spring 集成

```java
@Bean
@ConditionalOnMissingBean
public SessionManager sessionManager(AppPaths paths) {
    return new SessionManager(paths.workspaceDir());
}

@Bean
@ConditionalOnMissingBean
public WebuiTurnCoordinator webuiTurnCoordinator(MessageBus bus, SessionManager sm) {
    return new WebuiTurnCoordinator(bus, sm);
}
```

## 10. 测试用例

```java
class SessionTest {
    @Test void newSessionHasDefaults() { /* key, empty messages, 0 lastConsolidated */ }
    @Test void addMessageAppendsAndTouches() { /* messages.size()==1, updatedAt >= before */ }
    @Test void getHistorySkipsConsolidatedPrefix() { /* setLastConsolidated(2), history has 2 */ }
    @Test void getHistoryAlignsToUserTurn() { /* orphan assistant skipped */ }
    @Test void clearResetsSession() { /* messages empty, lc=0 */ }
    @Test void retainRecentLegalSuffixDropsOldMessages() { /* 20→4 */ }
}

class SessionManagerTest {
    @TempDir Path tmp;
    @Test void getOrCreateReturnsNewSession() { /* non-null */ }
    @Test void getOrCreateReturnsCached() { /* same reference */ }
    @Test void saveAndReload() { /* save → invalidate → load, messages preserved */ }
    @Test void repairSkipsCorruptLines() { /* 2 valid + 1 bad line → 2 messages */ }
    @Test void deleteRemovesFileAndCache() { /* file gone, cache empty */ }
    @Test void listSessionsReturnsPreviews() { /* 2 sessions → 2 previews */ }
}

class GoalStateTest {
    @Test void sustainedGoalActiveTrue/False() { /* status=="active" check */ }
    @Test void legacyKeyFallback() { /* thread_goal works */ }
    @Test void goalStateRuntimeLinesGeneratesContext() { /* LLM context lines */ }
    @Test void runnerWallLlmTimeoutReturnsZeroForGoalTurn() { /* 0.0 */ }
}

class TurnContinuationTest {
    @Test void isInternalContinuationDetectsFlag() { /* true for _internal_continuation */ }
    @Test void shouldPersistUserMessageFalseForContinuations() { /* false */ }
    @Test void stripTerminalAssistantRemovesLastIfMatch() { /* 2→1 */ }
    @Test void stripTerminalAssistantKeepsIfToolCalls() { /* keeps */ }
}
```

## 11. 验证标准

```bash
mvn test -Dtest=SessionTest,SessionManagerTest,GoalStateTest,TurnContinuationTest
# [x] Session: new/append/history/clear/retain all correct
# [x] SessionManager: get/create/save/reload/repair/delete/flush/fork
# [x] JSONL corruption repair: bad lines skipped, good data preserved
# [x] Atomic write: no partial files visible via ATOMIC_MOVE
# [x] GoalState: all predicates match Python behavior
# [x] TurnContinuation: continuation detection, stripping, round tracking
```

## 12. 代码量估算

| 文件 | 行数 |
|------|------|
| Session.java | ~200 |
| SessionHelpers.java | ~60 |
| SessionSanitizer.java | ~120 |
| SessionTokenBudget.java | ~50 |
| SessionConstants.java | ~20 |
| SessionManager.java | ~310 |
| GoalState.java | ~120 |
| TurnContinuation.java | ~190 |
| TurnContinuationContext.java | ~15 |
| WebuiTurnCoordinator.java | ~100 |
| WebuiTitleGenerator.java | ~100 |
| 测试 | ~300 |
| **合计** | **~1585** |
