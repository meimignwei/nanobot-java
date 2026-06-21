# Package 07: Agent Tools — All Tool Implementations

> **Language**: Java 21 + Spring Boot 3.2, Virtual Threads
> **Base Package**: `com.nanobot.agent.tools`
> **Source**: `nanobot/agent/tools/shell.py`, `filesystem.py`, `web.py`, `search.py`, `apply_patch.py`, `exec_session.py`, `mcp.py`, `spawn.py`, `long_task.py`, `cron.py`, `image_generation.py`, `message.py`, `self.py`, `cli_apps.py`

## Overview

This document covers all 23 tool implementations in the nanobot agent framework. Each tool extends `Tool` (or `_FsTool` for filesystem tools) and follows the same lifecycle: enabled-check, factory creation, parameter casting/validation, and async execution.

### Class Hierarchy

```
Tool (abstract)
  |-- ExecTool                         (shell.py)
  |-- WebSearchTool                    (web.py)
  |-- WebFetchTool                     (web.py)
  |-- WriteStdinTool                   (exec_session.py)
  |-- ListExecSessionsTool             (exec_session.py)
  |-- LongTaskTool                     (long_task.py)
  |-- CompleteGoalTool                 (long_task.py)
  |-- CronTool                         (cron.py)
  |-- ImageGenerationTool              (image_generation.py)
  |-- MessageTool                      (message.py)
  |-- SpawnTool                        (spawn.py)
  |-- MyTool                           (self.py)
  |-- MCPToolWrapper                   (mcp.py — wraps external MCP tools)
  |-- _FsTool (abstract intermediate)
  |     |-- ReadFileTool               (filesystem.py)
  |     |-- WriteFileTool              (filesystem.py)
  |     |-- EditFileTool               (filesystem.py)
  |     |-- ListDirTool                (filesystem.py)
  |     |-- ApplyPatchTool             (apply_patch.py)
  |     |-- _SearchTool (abstract intermediate)
  |           |-- FindFilesTool        (search.py)
  |           |-- GrepTool             (search.py)
```

---

## 1. ExecTool — Shell Command Execution

### Source: `shell.py` (677 lines)
### Package: `com.nanobot.agent.tools.impl`

The most complex tool in the system. Wraps shell commands with multiple security layers: deny/allow pattern matching, workspace boundary guards, internal URL detection, platform-specific shell selection, and session-based long-running execution.

### Security Architecture

```
User Command
     |
     v
  _guardCommand()
     |-- denyPattern check (blacklist regex)
     |-- allowPattern check (whitelist regex, priority over deny)
     |-- internal URL detection (SSRF guard)
     |-- workspace boundary guard (.. traversal, absolute path extraction)
     v
  _prepareCommand()
     |-- working_dir boundary check
     |-- sandbox wrapping (bwrap)
     |-- PATH composition (prepend/append)
     |-- shell resolution (bash/zsh/sh on Unix; powershell/cmd on Windows)
     v
  _spawn() -> Process
     |-- ProcessBuilder with minimal env
     |-- stdin=DEVNULL for one-shot, stdin=PIPE for session mode
     v
  execute() -> String
     |-- one-shot: waitFor with timeout, kill+FIFO reaping on timeout
     |-- session: delegate to ExecSessionManager
```

### Config

```java
/**
 * Configuration record for ExecTool.
 * Mirrors the Python ExecToolConfig Pydantic model.
 */
public record ExecToolConfig(
        boolean enable,            // master switch
        int timeout,               // hard timeout seconds (0 = no limit)
        String pathPrepend,        // prepended to PATH
        String pathAppend,         // appended to PATH
        String sandbox,            // sandbox backend name, e.g. "bwrap"
        List<String> allowedEnvKeys, // env vars to forward
        List<String> allowPatterns,  // override deny patterns
        List<String> denyPatterns    // additional deny patterns
) {
    public ExecToolConfig {
        if (allowedEnvKeys == null) allowedEnvKeys = List.of();
        if (allowPatterns == null) allowPatterns = List.of();
        if (denyPatterns == null) denyPatterns = List.of();
    }
}
```

### Deny Patterns (Hardcoded)

```java
private static final List<String> HARD_DENY_PATTERNS = List.of(
    "\\brm\\s+-[rf]{1,2}\\b",                    // rm -r, rm -rf, rm -fr
    "\\bdel\\s+/[fq]\\b",                         // del /f, del /q
    "\\brmdir\\s+/s\\b",                           // rmdir /s
    "(?:^|[;&|]\\s*)format(?!=)\\b",              // format (standalone only)
    "\\b(mkfs|diskpart)\\b",                       // disk operations
    "\\bdd\\s+if=",                                // dd
    ">\\s*/dev/sd",                                // write to disk
    "\\b(shutdown|reboot|poweroff)\\b",            // system power
    ":\\(\\)\\s*\\{.*\\};\\s*:",                   // fork bomb
    ">>?\\s*\\S*(?:history\\.jsonl|\\.dream_cursor)", // redirect to history
    "\\btee\\b[^|;&<>]*(?:history\\.jsonl|\\.dream_cursor)",
    "\\b(?:cp|mv)\\b(?:\\s+[^\\s|;&<>]+)+\\s+\\S*(?:history\\.jsonl|\\.dream_cursor)",
    "\\bdd\\b[^|;&<>]*\\bof=\\S*(?:history\\.jsonl|\\.dream_cursor)",
    "\\bsed\\s+-i[^|;&<>]*(?:history\\.jsonl|\\.dream_cursor)"
);
```

### Key Implementation: _guardCommand

```java
/**
 * Best-effort safety guard for potentially destructive commands.
 * Returns null if the command passes all checks, or an error String.
 */
public String guardCommand(String command, Path cwd,
                           boolean restrictToWorkspace,
                           boolean webuiAllowLocalServiceAccess) {

    String cmd = command.strip();
    String lower = cmd.toLowerCase();

    // 1. Allow patterns take priority over deny patterns
    boolean explicitlyAllowed = !allowPatterns.isEmpty() && allowPatterns.stream()
            .anyMatch(pattern -> Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
                    .matcher(lower).find());

    if (!explicitlyAllowed) {
        // 2. Check deny patterns
        for (String pattern : denyPatterns) {
            if (Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
                    .matcher(lower).find()) {
                return "Error: Command blocked by deny pattern filter";
            }
        }

        // 3. If allow-patterns are configured and command isn't in them
        if (!allowPatterns.isEmpty()) {
            return "Error: Command blocked by allowlist filter (not in allowlist)";
        }
    }

    // 4. Internal URL detection (SSRF guard)
    if (NetworkSecurity.containsInternalUrl(cmd, webuiAllowLocalServiceAccess)) {
        return "Error: Command blocked by safety guard (internal/private URL detected)";
    }

    // 5. Workspace boundary guard
    if (restrictToWorkspace) {
        if (cmd.contains("..\\") || cmd.contains("../")) {
            return "Error: Command blocked by safety guard (path traversal detected)"
                    + WORKSPACE_BOUNDARY_NOTE;
        }

        for (String absPath : extractAbsolutePaths(cmd)) {
            try {
                String expanded = System.getenv().entrySet().stream()
                        .reduce(absPath, (s, e) ->
                                s.replace("$" + e.getKey(), e.getValue()),
                                (a, b) -> a);
                if (isBenignDevicePath(expanded)) continue;

                Path resolved = Path.of(expanded.replaceFirst("^~",
                        System.getProperty("user.home")));
                resolved = resolved.normalize().toAbsolutePath();
                if (isBenignDevicePath(resolved.toString())) continue;

                if (!PathUtils.isPathWithin(resolved, cwd) &&
                        !PathUtils.isPathWithin(resolved, mediaDir)) {
                    return "Error: Command blocked by safety guard (path outside working dir)"
                            + WORKSPACE_BOUNDARY_NOTE;
                }
            } catch (Exception e) {
                continue;
            }
        }
    }
    return null; // all clear
}
```

### Process Spawning

```java
/**
 * Launch a command in a platform-appropriate shell.
 * On Windows: powershell for multi-line, cmd for single-line.
 * On Unix: bash -l (login shell) for proper PATH/profile.
 */
public Process spawn(String command, Path cwd, Map<String, String> env,
                     String shellProgram, boolean login) throws IOException {

    ProcessBuilder pb;
    if (IS_WINDOWS) {
        if (command.contains("\n")) {
            pb = new ProcessBuilder("powershell", "-NoProfile", "-Command", command);
        } else {
            pb = new ProcessBuilder("cmd", "/c", command);
        }
    } else {
        String shell = (shellProgram != null) ? shellProgram
                : (Files.exists(Path.of("/bin/bash")) ? "/bin/bash" : "/bin/sh");
        List<String> args = new ArrayList<>();
        args.add(shell);
        String shellName = Path.of(shell).getFileName().toString().toLowerCase();
        if (login && (shellName.equals("bash") || shellName.equals("zsh"))) {
            args.add("-l");
        }
        args.add("-c");
        args.add(command);
        pb = new ProcessBuilder(args);
    }

    pb.directory(cwd.toFile());
    pb.environment().clear();
    pb.environment().putAll(env);
    pb.redirectInput(ProcessBuilder.Redirect.PIPE); // or DEVNULL for one-shot
    pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
    pb.redirectError(ProcessBuilder.Redirect.PIPE);

    return pb.start();
}

/**
 * Build a minimal environment for subprocess execution.
 * On Unix: only HOME/LANG/TERM (bash -l sources user profile).
 * On Windows: curated set of system variables, no secrets.
 */
public Map<String, String> buildEnv() {
    if (IS_WINDOWS) {
        Map<String, String> env = new LinkedHashMap<>();
        String sysRoot = System.getenv().getOrDefault("SYSTEMROOT", "C:\\Windows");
        env.put("SYSTEMROOT", sysRoot);
        env.put("COMSPEC", System.getenv().getOrDefault("COMSPEC",
                sysRoot + "\\system32\\cmd.exe"));
        env.put("USERPROFILE", System.getenv().getOrDefault("USERPROFILE", ""));
        env.put("TEMP", System.getenv().getOrDefault("TEMP", sysRoot + "\\Temp"));
        env.put("TMP", System.getenv().getOrDefault("TMP", sysRoot + "\\Temp"));
        env.put("PATH", System.getenv().getOrDefault("PATH", sysRoot + "\\system32;" + sysRoot));
        env.put("PYTHONUNBUFFERED", "1");
        for (String key : allowedEnvKeys) {
            String val = System.getenv(key);
            if (val != null) env.put(key, val);
        }
        return env;
    } else {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("HOME", System.getenv().getOrDefault("HOME", "/tmp"));
        env.put("LANG", System.getenv().getOrDefault("LANG", "C.UTF-8"));
        env.put("TERM", System.getenv().getOrDefault("TERM", "dumb"));
        env.put("PYTHONUNBUFFERED", "1");
        for (String key : allowedEnvKeys) {
            String val = System.getenv(key);
            if (val != null) env.put(key, val);
        }
        return env;
    }
}
```

### Parameter Schema

```java
private static final Map<String, Object> PARAMETERS =
    ToolParametersSchema.create(
        null, null,
        Map.of(
            "command", new StringSchema("The shell command to execute"),
            "cmd", new StringSchema("Compatibility alias for command"),
            "working_dir", new StringSchema("Optional working directory for the command"),
            "workdir", new StringSchema("Compatibility alias for working_dir"),
            "timeout", new IntegerSchema(60,
                    "Timeout in seconds (default 60, max 600)", 1, 600),
            "shell", new StringSchema(
                    "Optional shell binary. On Unix: sh, bash, or zsh.",
                    null, null, null, true), // nullable
            "login", new BooleanSchema(
                    "Whether to run bash/zsh with login shell semantics (default true)"),
            "yield_time_ms", new IntegerSchema(null,
                    "Milliseconds to wait before returning output. When set, returns session_id",
                    0, 30_000, null, true), // nullable
            "max_output_chars", new IntegerSchema(
                    "Maximum output chars when yield_time_ms is used (default 10000, max 50000)",
                    1000, 50_000, null, true)
        )
    );
```

### Key Properties

| Property | Value | Reason |
|----------|-------|--------|
| `name` | `"exec"` | Used in LLM function calls |
| `exclusive` | `true` | Shell commands should run alone (not parallelized) |
| `isReadOnly` | `false` | Commands have side effects |
| `scopes` | `{"core", "subagent"}` | Available to both main agent and subagents |

### Timeout Resolution Logic

```java
/**
 * Resolve effective timeout: per-call limit (capped at MAX_TIMEOUT=600s),
 * or config-level default. Value 0 disables limit entirely.
 */
public Integer resolveTimeout(Integer perCallTimeout) {
    if (perCallTimeout != null && perCallTimeout > 0) {
        return Math.min(perCallTimeout, MAX_TIMEOUT);
    }
    if (this.timeout > 0) {
        return this.timeout;
    }
    return null; // no limit
}
```

---

## 2. _FsTool Base Class — Filesystem Tool Foundation

### Package: `com.nanobot.agent.tools.impl`

All filesystem tools extend this intermediate abstract class, which provides:
- Workspace path resolution
- Allowed directory containment
- FileState tracking integration

```java
/**
 * Shared base for all filesystem tools.
 * Provides workspace path resolution, allowed-directory enforcement,
 * and FileStates integration for deduplication/read-before-edit tracking.
 */
public abstract class FsTool extends Tool {

    protected final Path workspace;
    protected final Path allowedDir;
    protected final List<Path> extraAllowedDirs;
    protected final boolean restrictToWorkspace;
    protected final boolean sandboxRestrictsWorkspace;
    protected final FileStates explicitFileStates;
    private final FileStates fallbackFileStates = new FileStates();

    public FsTool(Path workspace, Path allowedDir, List<Path> extraAllowedDirs,
                  FileStates fileStates, boolean restrictToWorkspace,
                  boolean sandboxRestrictsWorkspace) {
        this.workspace = workspace;
        this.allowedDir = allowedDir;
        this.extraAllowedDirs = extraAllowedDirs;
        this.explicitFileStates = fileStates;
        this.restrictToWorkspace = restrictToWorkspace;
        this.sandboxRestrictsWorkspace = sandboxRestrictsWorkspace;
    }

    /** Factory from ToolContext (shared by all FsTool subclasses). */
    public static Tool create(ToolContext ctx, Class<? extends FsTool> toolClass) {
        // Extract workspace, allowed_dir, extra_allowed_dirs from ctx
        // ...
    }

    /** Resolve a user-supplied path against the workspace with containment check. */
    protected Path resolve(String path) {
        return PathUtils.resolveWorkspacePath(path, workspace, allowedDir, extraAllowedDirs);
    }

    /** Get the FileStates — prefers explicit, falls back to ContextVar-bound, then local. */
    protected FileStates fileStates() {
        if (explicitFileStates != null) return explicitFileStates;
        // In Java: use ThreadLocal<FileStates> for per-task binding
        FileStates bound = FileStatesContext.current();
        return (bound != null) ? bound : fallbackFileStates;
    }
}
```

---

## 3. ReadFileTool

### Source: `filesystem.py` (lines 141-385)

Reads files with support for:
- Text files: line numbering, offset/limit pagination, UTF-8 decoding
- Binary detection + image MIME handling (returns image content blocks)
- PDF support via PDFBox
- DOCX, XLSX, PPTX support via Apache POI
- Read deduplication via FileStates (mtime + content hash)
- Device path blocking (/dev/zero, /proc/self/fd/0, etc.)

```java
@Component
@ToolParametersDef({
    @Param(name = "path", type = StringSchema.class, description = "The file path to read"),
    @Param(name = "offset", type = IntegerSchema.class, description = "Line number (1-indexed, default 1)"),
    @Param(name = "limit", type = IntegerSchema.class, description = "Max lines to read (default 2000)"),
    @Param(name = "pages", type = StringSchema.class, description = "Page range for PDF, e.g. '1-5'"),
    @Param(name = "force", type = BooleanSchema.class, description = "Bypass deduplication")
})
public class ReadFileTool extends FsTool {

    private static final int MAX_CHARS = 128_000;
    private static final int DEFAULT_LIMIT = 2000;
    private static final int MAX_PDF_PAGES = 20;

    // Blocked device paths (infinite output / hang risk)
    private static final Set<String> BLOCKED_DEVICE_PATHS = Set.of(
            "/dev/zero", "/dev/random", "/dev/urandom", "/dev/full",
            "/dev/stdin", "/dev/stdout", "/dev/stderr",
            "/dev/tty", "/dev/console"
    );

    @Override
    public String getName() { return "read_file"; }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public Set<String> getScopes() { return Set.of("core", "subagent", "memory"); }

    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            String path = (String) params.get("path");
            int offset = params.containsKey("offset") ? (int) params.get("offset") : 1;
            Integer limit = (Integer) params.getOrDefault("limit", null);
            String pages = (String) params.get("pages");
            boolean force = Boolean.TRUE.equals(params.get("force"));

            if (path == null || path.isEmpty()) {
                return "Error reading file: Unknown path";
            }

            // 1. Device path block
            if (isBlockedDevice(path)) {
                return "Error: Reading " + path + " is blocked (device path)";
            }

            // 2. Resolve and verify
            Path fp = resolve(path);
            if (isBlockedDevice(fp.toString())) {
                return "Error: Reading " + fp + " is blocked (device path)";
            }
            if (!Files.exists(fp)) return "Error: File not found: " + path;
            if (!Files.isRegularFile(fp)) return "Error: Not a file: " + path;

            // 3. PDF support
            if (fp.getFileName().toString().toLowerCase().endsWith(".pdf")) {
                return readPdf(fp, pages);
            }

            // 4. Office document support
            String ext = fp.getFileName().toString().toLowerCase();
            if (ext.endsWith(".docx") || ext.endsWith(".xlsx") || ext.endsWith(".pptx")) {
                return readOfficeDoc(fp);
            }

            // 5. Read bytes, detect MIME
            byte[] raw = Files.readAllBytes(fp);
            if (raw.length == 0) return "(Empty file: " + path + ")";

            String mime = detectMimeType(raw, path);
            if (mime != null && mime.startsWith("image/")) {
                return ImageHelper.buildImageContentBlocks(raw, mime, fp.toString(),
                        "(Image file: " + path + ")");
            }

            // 6. Read dedup
            FileStates.ReadState entry = fileStates().get(fp);
            if (!force && entry != null && entry.canDedup()
                    && entry.offset() == offset && Objects.equals(entry.limit(), limit)) {
                try {
                    double currentMtime = Files.getLastModifiedTime(fp).toMillis() / 1000.0;
                    if (currentMtime != entry.mtime()) {
                        // File touched externally
                        fileStates().recordRead(fp, offset, limit);
                    } else {
                        String currentHash = FileStates.sha256Hex(fp);
                        if (currentHash != null && currentHash.equals(entry.contentHash())) {
                            return "[File unchanged since last read: " + path + "]";
                        }
                        fileStates().recordRead(fp, offset, limit);
                    }
                } catch (IOException e) {
                    // fall through to full read
                }
            } else {
                fileStates().recordRead(fp, offset, limit);
            }

            // 7. Read as text
            try {
                String content = new String(raw, StandardCharsets.UTF_8);
                content = content.replace("\r\n", "\n"); // normalize CRLF

                List<String> allLines = List.of(content.split("\n", -1));
                int total = allLines.size();

                int start = Math.max(0, offset - 1);
                if (start >= total) {
                    return "Error: offset " + offset + " is beyond end of file ("
                            + total + " lines)";
                }

                int actualLimit = (limit != null) ? limit : DEFAULT_LIMIT;
                int end = Math.min(start + actualLimit, total);

                StringBuilder result = new StringBuilder();
                for (int i = start; i < end; i++) {
                    result.append(i + 1).append("| ").append(allLines.get(i)).append('\n');
                }

                if (result.length() > MAX_CHARS) {
                    // Truncate to ~128K chars
                    List<String> lines = result.toString().lines().toList();
                    StringBuilder truncated = new StringBuilder();
                    int charCount = 0;
                    int truncatedEnd = start;
                    for (String line : lines) {
                        if (charCount + line.length() + 1 > MAX_CHARS) break;
                        truncated.append(line).append('\n');
                        charCount += line.length() + 1;
                        truncatedEnd++;
                    }
                    result = truncated;
                    end = truncatedEnd;
                }

                if (end < total) {
                    result.append("\n(Showing lines ").append(offset).append("-")
                          .append(end).append(" of ").append(total)
                          .append(". Use offset=").append(end + 1)
                          .append(" to continue.)");
                } else {
                    result.append("\n(End of file — ").append(total)
                          .append(" lines total)");
                }

                fileStates().recordRead(fp, offset, limit);
                return result.toString();

            } catch (CharacterCodingException e) {
                String mime2 = detectMimeType(raw, path);
                if (mime2 != null && mime2.startsWith("image/")) {
                    return ImageHelper.buildImageContentBlocks(raw, mime2, fp.toString(),
                            "(Image file: " + path + ")");
                }
                return "Error: Cannot read binary file " + path
                        + " (MIME: " + (mime2 != null ? mime2 : "unknown") + ")";
            }

        }, Thread.ofVirtual().factory());
    }
}
```

---

## 4. WebSearchTool

### Source: `web.py` (lines 207-783)

Multi-provider web search with automatic fallback to DuckDuckGo. Supports 10+ providers (Brave, Tavily, SearXNG, Jina, Kagi, Exa, Olostep, Bocha, Volcengine, DuckDuckGo).

```java
@Component
public class WebSearchTool extends Tool {

    @Override public String getName() { return "web_search"; }
    @Override public boolean isReadOnly() { return true; }

    /** DuckDuckGo is not concurrency-safe — serialize when it's the active provider. */
    @Override
    public boolean isExclusive() {
        return "duckduckgo".equals(effectiveProvider());
    }

    /**
     * Resolve the backend provider. If the configured provider (e.g. Brave)
     * has no API key, falls back silently to DuckDuckGo.
     */
    public String effectiveProvider() {
        refreshConfig();
        String provider = config.provider().strip().toLowerCase();
        if (provider.isEmpty()) provider = "brave";

        // Check credentials for API-backed providers; fall back to DDG if missing
        return switch (provider) {
            case "duckduckgo" -> "duckduckgo";
            case "brave" -> hasApiKey("BRAVE_API_KEY") ? "brave" : "duckduckgo";
            case "tavily" -> hasApiKey("TAVILY_API_KEY") ? "tavily" : "duckduckgo";
            case "searxng" -> hasConfig("searxng_base_url") ? "searxng" : "duckduckgo";
            case "jina" -> hasApiKey("JINA_API_KEY") ? "jina" : "duckduckgo";
            case "kagi" -> hasApiKey("KAGI_API_KEY") ? "kagi" : "duckduckgo";
            case "exa" -> hasApiKey("EXA_API_KEY") ? "exa" : "duckduckgo";
            case "olostep" -> hasApiKey("OLOSTEP_API_KEY") ? "olostep" : "duckduckgo";
            case "bocha" -> hasApiKey("BOCHA_API_KEY") ? "bocha" : "duckduckgo";
            case "volcengine" -> (hasApiKey("VOLCENGINE_SEARCH_API_KEY")
                    || hasApiKey("WEB_SEARCH_API_KEY")) ? "volcengine" : "duckduckgo";
            default -> provider;
        };
    }

    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            refreshConfig();
            String provider = config.provider().strip().toLowerCase();
            String query = (String) params.get("query");
            int count = Math.min(Math.max(
                    params.containsKey("count") ? (int) params.get("count")
                            : config.maxResults(), 1), 10);

            try {
                return switch (provider) {
                    case "duckduckgo" -> searchDuckDuckGo(query, count);
                    case "brave" -> searchBrave(query, count);
                    case "tavily" -> searchTavily(query, count);
                    case "searxng" -> searchSearXNG(query, count);
                    case "jina" -> searchJina(query, count);
                    case "kagi" -> searchKagi(query, count);
                    case "exa" -> searchExa(query, count);
                    case "olostep" -> searchOlostep(query, count);
                    case "bocha" -> searchBocha(query, count);
                    case "volcengine" -> searchVolcengine(query, count, params);
                    default -> "Error: unknown search provider '" + provider + "'";
                };
            } catch (Exception e) {
                log.warn("Search provider '{}' failed, falling back to DuckDuckGo. Error: {}",
                        provider, e.getMessage());
                return searchDuckDuckGo(query, count);
            }
        }, Thread.ofVirtual().factory());
    }

    /** Format results into the shared plaintext output format. */
    private String formatResults(String query, List<SearchResult> items, int n) {
        if (items.isEmpty()) return "No results for: " + query;
        StringBuilder sb = new StringBuilder("Results for: ").append(query).append("\n\n");
        int limit = Math.min(items.size(), n);
        for (int i = 0; i < limit; i++) {
            SearchResult item = items.get(i);
            sb.append(i + 1).append(". ").append(item.title()).append('\n');
            sb.append("   ").append(item.url()).append('\n');
            if (item.content() != null && !item.content().isEmpty()) {
                sb.append("   ").append(item.content()).append('\n');
            }
        }
        return sb.toString();
    }

    // SearchResult record
    public record SearchResult(String title, String url, String content) {}
}
```

### Brave Search Implementation (example provider)

```java
private String searchBrave(String query, int count) {
    String apiKey = config.apiKey();
    if (apiKey == null || apiKey.isEmpty()) {
        apiKey = System.getenv("BRAVE_API_KEY");
    }
    if (apiKey == null || apiKey.isEmpty()) {
        log.warn("BRAVE_API_KEY not set, falling back to DuckDuckGo");
        return searchDuckDuckGo(query, count);
    }

    var client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    var request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.search.brave.com/res/v1/web/search?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&count=" + count))
            .header("Accept", "application/json")
            .header("X-Subscription-Token", apiKey)
            .header("User-Agent", userAgent)
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();

    try {
        // Retry once on 429
        for (int attempt = 0; attempt < 2; attempt++) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 429) {
                if (response.statusCode() >= 400) {
                    return "Error: Brave search HTTP " + response.statusCode();
                }
                // Parse results
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(response.body());
                List<SearchResult> results = new ArrayList<>();
                for (JsonNode item : root.at("/web/results")) {
                    results.add(new SearchResult(
                            item.path("title").asText(""),
                            item.path("url").asText(""),
                            item.path("description").asText("")));
                }
                return formatResults(query, results, count);
            }
            if (attempt == 0) {
                log.warn("Brave search rate limited; retrying in 1s");
                Thread.sleep(1000);
            }
        }
        return "Error: Brave search rate limited after retry.";
    } catch (Exception e) {
        return "Error: " + e.getMessage();
    }
}
```

---

## Summary Table: All 23 Tools

| # | Tool Name | Class | Lines | ReadOnly | Exclusive | Scopes | Key Design Notes |
|---|-----------|-------|-------|----------|-----------|--------|-------------------|
| 1 | `exec` | `ExecTool` | 677 | No | Yes | core, subagent | ProcessBuilder + deny/allow patterns + workspace guards + session mode |
| 2 | `read_file` | `ReadFileTool` | 244 | Yes | No | core, subagent, memory | UTF-8 text + PDF/DOCX/XLSX/PPTX + image MIME + dedup via FileStates |
| 3 | `write_file` | `WriteFileTool` | 32 | No | No | core, subagent, memory | Simple overwrite + mkdir parents |
| 4 | `edit_file` | `EditFileTool` | 564 | No | No | core, subagent, memory | Multi-strategy match (exact->trim->quote->diagnostics), preserve quote style + indentation |
| 5 | `list_dir` | `ListDirTool` | 70 | Yes | No | core, subagent | Flat/recursive, auto-ignores .git/node_modules/venv/etc. |
| 6 | `find_files` | `FindFilesTool` | 207 | Yes | No | core, subagent | Glob + type filter + path fragment query + sort by path/modified |
| 7 | `grep` | `GrepTool` | 315 | Yes | No | core, subagent | Regex search, content/files_with_matches/count modes, context lines, binary skip |
| 8 | `apply_patch` | `ApplyPatchTool` | 301 | No | No | core, subagent | Multi-file structured edits, dry-run, atomic write with backup+rollback |
| 9 | `web_search` | `WebSearchTool` | 577 | Yes | Conditional | core, subagent | 10+ providers, auto-fallback to DDG, exclusive when DDG active |
| 10 | `web_fetch` | `WebFetchTool` | 192 | Yes | No | core, subagent | Jina Reader + readability fallback, HTML->markdown, SSRF-safe redirects |
| 11 | `write_stdin` | `WriteStdinTool` | 230 | No | Yes | core, subagent | Poll/write/terminate long-running exec sessions, wait_for pattern matching |
| 12 | `list_exec_sessions` | `ListExecSessionsTool` | 56 | Yes | No | core, subagent | Enumerate active exec sessions with elapsed/idle/remaining |
| 13 | `spawn` | `SpawnTool` | 97 | No | No | core | One-shot subagent spawn, concurrency cap via SubagentManager |
| 14 | `long_task` | `LongTaskTool` | 68 | No | No | core | Session-scoped sustained goal registration (JSON metadata on session) |
| 15 | `complete_goal` | `CompleteGoalTool` | 68 | No | No | core | Mark goal done + recap, idempotent, publishes GoalStateChanged event |
| 16 | `cron` | `CronTool` | ~300 | No | No | core | add/list/remove actions: every_seconds, cron_expr, at; IANA tz validation |
| 17 | `generate_image` | `ImageGenerationTool` | ~200 | No | No | core | Multi-provider image gen via ImageGenerationProvider interface |
| 18 | `message` | `MessageTool` | 273 | No | No | core | Cross-channel proactive delivery, media attachments, inline keyboard buttons |
| 19 | `my` | `MyTool` | 485 | No | No | core | Runtime state inspection + modification (check/set), sensitive field redaction |
| 20-23 | `mcp_*` | `MCPToolWrapper` | ~1122 | varies | varies | core | Dynamic tools from MCP servers, schema normalization, transient error retry |

### Tools Requiring Specific Dependencies

| Tool | Java Dependency | Pulled in For |
|------|----------------|---------------|
| `read_file` (PDF) | `org.apache.pdfbox:pdfbox` | PDF text extraction |
| `read_file` (Office) | `org.apache.poi:poi-ooxml` | DOCX, XLSX, PPTX text extraction |
| `web_search` (DDG) | None (use `java.net.http.HttpClient` directly) | DDG API calls |
| `web_fetch` | `org.jsoup:jsoup` | HTML parsing + readability extraction |
| `generate_image` | Provider-specific HTTP clients | Image generation API calls |
| `mcp_*` | MCP Java SDK | MCP server connections |

---

## 5. ExecSessionManager — Long-Running Command Sessions

### Source: `exec_session.py` (611 lines)

Manages up to 8 concurrent long-running shell sessions per process. Sessions are identified by a 12-char hex ID, owned by a session key, and auto-cleaned after 30 minutes of idle time.

```java
package com.nanobot.agent.tools.exec;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Manages up to 8 concurrent long-running exec sessions.
 * Each session wraps a Process with async stdout/stderr readers,
 * supports stdin writes, EOF, terminate, and poll with yield timing.
 */
public class ExecSessionManager {

    private static final int MAX_SESSIONS = 8;
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

    public static final int DEFAULT_YIELD_MS = 1000;
    public static final int MAX_YIELD_MS = 30_000;
    public static final int DEFAULT_MAX_OUTPUT_CHARS = 10_000;
    public static final int MAX_OUTPUT_CHARS = 50_000;

    private final ConcurrentHashMap<String, ExecSession> sessions = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Start a new exec session. Spawns the process, returns session_id + initial poll.
     */
    public record StartResult(String sessionId, SessionPoll poll) {}

    public StartResult start(String command, Path cwd, Map<String, String> env,
                              Integer timeout, String shellProgram, boolean login,
                              int yieldTimeMs, int maxOutputChars,
                              String ownerSessionKey) throws Exception {

        lock.lock();
        try {
            cleanupStale();
            if (sessions.size() >= MAX_SESSIONS) {
                throw new RuntimeException("maximum exec sessions reached (" + MAX_SESSIONS + ")");
            }

            Process process = ExecTool.spawn(command, cwd, env, shellProgram, login);
            String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            ExecSession session = new ExecSession(
                    sessionId, process, command, cwd.toString(),
                    timeout, ownerSessionKey);
            sessions.put(sessionId, session);
        } finally {
            lock.unlock();
        }

        // Initial poll outside lock
        ExecSession session = sessions.get(sessionId);
        SessionPoll poll = session.poll(yieldTimeMs, maxOutputChars);
        if (poll.done()) {
            lock.lock();
            try { sessions.remove(sessionId); }
            finally { lock.unlock(); }
        }
        return new StartResult(sessionId, poll);
    }

    /** Poll/write to/close/terminate an existing session. */
    public SessionPoll interact(String sessionId, String chars, boolean closeStdin,
                                 boolean terminate, int yieldTimeMs, int maxOutputChars,
                                 String ownerSessionKey) throws Exception {
        ExecSession session = sessions.get(sessionId);
        if (session == null) throw new NoSuchElementException(sessionId);
        if (ownerSessionKey != null && session.ownerSessionKey() != null
                && !session.ownerSessionKey().equals(ownerSessionKey)) {
            throw new NoSuchElementException(sessionId);
        }

        if (chars != null && !chars.isEmpty()) {
            String error = session.write(chars);
            if (error != null) throw new RuntimeException(error);
        }
        if (closeStdin) session.closeStdin();
        if (terminate) session.kill();

        SessionPoll poll = session.poll(yieldTimeMs, maxOutputChars, terminate, closeStdin);
        if (poll.done()) {
            lock.lock();
            try { sessions.remove(sessionId); }
            finally { lock.unlock(); }
        }
        return poll;
    }

    public List<ExecSessionInfo> list(String ownerSessionKey) {
        lock.lock();
        try {
            cleanupStale();
            Instant now = Instant.now();
            return sessions.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .filter(e -> ownerSessionKey == null
                            || e.getValue().ownerSessionKey() == null
                            || e.getValue().ownerSessionKey().equals(ownerSessionKey))
                    .map(e -> e.getValue().toInfo(now))
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    private void cleanupStale() {
        Instant now = Instant.now();
        sessions.entrySet().removeIf(e -> {
            Duration idle = Duration.between(e.getValue().lastAccess(), now);
            if (idle.compareTo(IDLE_TIMEOUT) > 0) {
                e.getValue().kill();
                return true;
            }
            return false;
        });
    }
}
```

### SessionPoll Record

```java
/**
 * Result of polling an exec session.
 * @param output       recent stdout/stderr output
 * @param done         true when process has exited
 * @param exitCode     process exit code (null if still running)
 * @param elapsedSec   seconds since session start
 * @param timedOut     true if killed due to timeout
 * @param terminated   true if explicitly terminated
 * @param stdinClosed  true if stdin was closed in this interaction
 * @param truncatedChars number of chars omitted from output
 */
public record SessionPoll(
        String output,
        boolean done,
        Integer exitCode,
        double elapsedSec,
        boolean timedOut,
        boolean terminated,
        boolean stdinClosed,
        int truncatedChars
) {}
```

---

## 6. EditFileTool — Smart Text Replacement

### Source: `filesystem.py` (lines 697-932)

The most sophisticated filesystem tool. Uses a progressive match strategy:

```
1. Exact substring match
2. Line-trimmed sliding window (handles indentation diffs)
3. Line-trimmed + quote-normalized (curly/smart quotes → straight)
4. Quote-normalized substring (full-content quote normalization)
5. Near-match diagnostics via SequenceMatcher (Python difflib → Apache commons-text)
```

```java
/**
 * Multi-level fallback matching chain for edit_file.
 */
public record Match(String text, int start, int end, int line) {}

public MatchResult findMatch(String content, String oldText) {
    // Strategy 1: Exact match
    List<Match> matches = findExactMatches(content, oldText);
    if (!matches.isEmpty()) return new MatchResult(matches, MatchStrategy.EXACT);

    // Strategy 2: Line-trimmed sliding window
    matches = findTrimMatches(content, oldText, false);
    if (!matches.isEmpty()) return new MatchResult(matches, MatchStrategy.TRIM);

    // Strategy 3: Trim + quote normalization
    matches = findTrimMatches(content, oldText, true);
    if (!matches.isEmpty()) return new MatchResult(matches, MatchStrategy.TRIM_QUOTES);

    // Strategy 4: Full quote normalization
    matches = findQuoteMatches(content, oldText);
    if (!matches.isEmpty()) return new MatchResult(matches, MatchStrategy.QUOTES);

    // Strategy 5: Near-match diagnostics
    NearMatchDiagnostics diag = bestWindow(oldText, content);
    return new MatchResult(List.of(), MatchStrategy.NONE, diag);
}
```

### Replacement Rules

- **Preserve quote style**: If the matched text uses curly quotes ("smart quotes" or "smart quotes") and the replacement uses straight quotes, auto-convert the replacement to match the original style
- **Preserve indentation**: If the matched text is at a different indentation level than the search text (common in Python), re-indent the replacement to match
- **Trailing whitespace**: Stripped for non-Markdown files to keep diffs clean
- **Delete-line cleanup**: When `newText=""`, consume the trailing newline to avoid leaving a blank line

---

## 7. WebFetchTool — URL Content Extraction

### Source: `web.py` (lines 798-988)

Two-tier extraction strategy:

1. **Jina Reader API** (preferred): `https://r.jina.ai/{url}` with JSON response, returns structured markdown. Falls back on 429 or failure.
2. **Readability-lxml fallback** (local): Fetch HTML via HttpClient, extract readable content with JSoup + custom readability algorithm, convert to markdown.

Security: Every redirect target is validated against internal IP ranges (SSRF protection) before the request is made. Maximum 5 redirect hops.

```java
/** SSRF-safe redirect-aware fetch. Validates every redirect URL against internal IP ranges. */
private record FetchResult(HttpResponse<String> response, String error) {}

public FetchResult getWithSafeRedirects(HttpClient client, String url,
                                         Map<String, String> headers) throws Exception {
    String currentUrl = url;
    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
        if (!NetworkSecurity.isSafeUrl(currentUrl)) {
            return new FetchResult(null, "Redirect blocked: internal/private IP detected");
        }

        var request = HttpRequest.newBuilder()
                .uri(URI.create(currentUrl))
                .headers(toHeaderArray(headers))
                .GET()
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        boolean isRedirect = response.statusCode() >= 300 && response.statusCode() < 400;
        if (!isRedirect) {
            return new FetchResult(response, null);
        }

        // Follow Location header to next hop
        String location = response.headers().firstValue("Location").orElse(null);
        if (location == null) return new FetchResult(response, null);

        currentUrl = resolveRedirect(response.uri().toString(), location);
        if (!NetworkSecurity.isSafeUrl(currentUrl)) {
            return new FetchResult(null, "Redirect blocked: internal/private IP detected");
        }
    }
    return new FetchResult(null, "Too many redirects: exceeded " + MAX_REDIRECTS);
}
```

---

## 8. MCPToolWrapper — MCP Server Tools

### Source: `mcp.py` (1122 lines)

Wraps tools exposed by external MCP servers as native nanobot tools. Key behaviors:

- **Schema normalization**: Nullable unions (`{"type": ["string", "null"]}`) are flattened for OpenAI compatibility
- **Transient error retry**: ConnectionResetError, BrokenPipeError, etc. trigger a single reconnection attempt
- **Name sanitization**: `re.sub(r"[^a-zA-Z0-9_-]", "_", name)` → collapse runs of underscores
- **HTTP probe**: Before connecting to streamable HTTP MCP servers, a quick TCP probe avoids crashing the event loop
- **Windows shell wrapping**: `npx`, `npm`, `pnpm`, `yarn` commands are wrapped through `cmd /d /c` for reliability

```java
/**
 * Wraps a single MCP server tool. Delegates execution to the MCP client.
 * Schema is pre-normalized for OpenAI compatibility at registration time.
 */
public class MCPToolWrapper extends Tool {

    private final String mcpServerName;
    private final String toolName;
    private final String description;
    private final Map<String, Object> parameters;
    private final MCPClientSession session;
    private final boolean readOnly;

    // ... constructor, delegate execute() to MCP session.callTool()
}
```

---

## 9. Remaining Tools — Summaries

### CronTool (`cron.py`, ~300 lines)

Registers scheduled/recurring tasks with the CronService. Three actions:
- `add` — requires `message` + one schedule (`every_seconds`, `cron_expr`, or `at`); validates IANA timezone
- `list` — enumerates active jobs for the current chat
- `remove` — deletes job by `job_id`

Jobs fire in a cron context that routes back through the agent loop.

### SpawnTool (`spawn.py`, 97 lines)

One-shot subagent creation. Accepts a `task` description, optional `label` and `temperature`. Enforces concurrency cap via `SubagentManager.maxConcurrentSubagents`. Reports concurrency limit errors rather than failing silently.

### LongTaskTool + CompleteGoalTool (`long_task.py`, 252 lines)

Session-scoped sustained goal tracking. LongTaskTool writes JSON metadata to the session (status=active, objective text, ui_summary, started_at timestamp). CompleteGoalTool transitions to status=completed with a recap. Both publish `GoalStateChanged` runtime events for real-time UI updates. Goal state is mirrored in every turn's Runtime Context block so compaction cannot hide it.

### ImageGenerationTool (`image_generation.py`, ~200 lines)

Delegates to configured `ImageGenerationProvider` (e.g. OpenRouter, OpenAI). Supports prompt, reference_images, aspect_ratio, image_size, count (up to 8). Stores generated images as persistent artifacts in the workspace and returns artifact paths for use with `message` tool delivery.

### MessageTool (`message.py`, 273 lines)

Proactive cross-channel message delivery. Key features:
- Resolves media attachments with workspace boundary enforcement
- Supports inline keyboard `buttons` (list of rows of labels)
- Prevents misuse: `chat_id` mismatch detection for WebSocket conversations
- Internal `suppress_delivery` flag for heartbeat internal checks (acknowledge without sending)
- Tracks per-turn sent/delivered media paths for dedup

### MyTool (`self.py`, 485 lines)

Runtime state introspection and modification. Key features:
- `check` (inspect): dot-path navigation into runtime state, smart formatting for config/model/token-usage/subagents
- `set` (modify): type-safe writes to whitelisted keys (max_iterations, context_window_tokens, model) and scratchpad storage
- Sensitive field redaction (api_key, secret, password, token, credential, etc.)
- Block list prevents access to core infrastructure (bus, provider, tools, runner, sessions)
- `allow_set` config toggle for read-only deployments

---

## Directory Structure

```
com/nanobot/agent/tools/
  |-- Tool.java                     (abstract base)
  |-- ToolParameters.java           (deepCopy helper)
  |-- ToolRegistry.java             (ConcurrentHashMap registry)
  |-- ToolLoader.java               (Spring ComponentScan + ServiceLoader)
  |-- ToolContext.java              (DI container record)
  |-- RequestContext.java           (ThreadLocal per-request routing)
  |
  |-- schema/
  |     |-- Schema.java             (abstract base)
  |     |-- StringSchema.java
  |     |-- IntegerSchema.java
  |     |-- NumberSchema.java
  |     |-- BooleanSchema.java
  |     |-- ArraySchema.java
  |     |-- ObjectSchema.java
  |     |-- ToolParametersSchema.java (convenience factory)
  |
  |-- path/
  |     |-- PathUtils.java          (workspace resolution)
  |
  |-- sandbox/
  |     |-- Sandbox.java            (bwrap wrapping)
  |
  |-- file/
  |     |-- FileStates.java         (read/write tracker)
  |     |-- FileStateStore.java     (per-session lookup)
  |
  |-- exec/
  |     |-- ExecSessionManager.java (long-running sessions)
  |     |-- ExecSession.java        (single session wrapper)
  |
  |-- impl/
        |-- ExecTool.java
        |-- FsTool.java             (abstract intermediate)
        |-- ReadFileTool.java
        |-- WriteFileTool.java
        |-- EditFileTool.java
        |-- ListDirTool.java
        |-- ApplyPatchTool.java
        |-- FindFilesTool.java
        |-- GrepTool.java
        |-- WebSearchTool.java
        |-- WebFetchTool.java
        |-- WriteStdinTool.java
        |-- ListExecSessionsTool.java
        |-- SpawnTool.java
        |-- LongTaskTool.java
        |-- CompleteGoalTool.java
        |-- CronTool.java
        |-- ImageGenerationTool.java
        |-- MessageTool.java
        |-- MyTool.java
        |-- MCPToolWrapper.java
```

---

## Concurrency Model

All tools run on **Java 21 virtual threads** via `Thread.ofVirtual().factory()`. Key implications:

1. **`Tool.execute()` returns `CompletableFuture<Object>`** — the agent loop can run multiple non-exclusive tools in parallel via `CompletableFuture.allOf()`
2. **Process.waitFor() is blocking** — virtual threads handle this efficiently by unmounting from the carrier thread during the blocking wait (no need for async Process API)
3. **ThreadLocal is safe** — each virtual thread has its own ThreadLocal storage. RequestContext is bound per-invocation via `RequestContext.bind()` / `RequestContext.unbind()` in try-finally blocks
4. **Exclusive tools serialize** — when `isExclusive() == true` (ExecTool, WriteStdinTool, WebSearchTool when DDG), the agent loop runs them one at a time
5. **ReadOnly tools can parallelize** — when all tools in a batch are `isReadOnly() == true` and `isConcurrencySafe() == true`, they run concurrently

```java
// Agent loop tool execution pattern (conceptual)
List<CompletableFuture<Object>> futures = new ArrayList<>();
for (ToolCall call : batch) {
    PreparedCall prepared = registry.prepareCall(call.name(), call.params());
    if (prepared.error() != null) {
        futures.add(CompletableFuture.completedFuture(prepared.error()));
    } else {
        futures.add(prepared.tool().execute(prepared.params()));
    }
}
// Wait for all, collect results
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
```
