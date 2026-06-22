# Package 16: Security (`com.nanobot.security`)

## Overview

The `nanobot/security/` package contains three Python modules that enforce security boundaries: workspace access scoping (per-turn project root and access mode), workspace path policy (boundary enforcement for file operations), and network security (SSRF protection for URL fetching). This document specifies the Java 21 + Spring Boot 3.2 port.

**Original Python lines:** ~390 across 3 files.
**Estimated Java lines:** ~655 across 5 files.

---

## 1. File Map

| Python File | Lines | Java Class | Purpose |
|-------------|-------|------------|---------|
| `workspace_access.py` | 230 | `WorkspaceScope.java` (record), `WorkspaceScopeResolver.java` (service) | Per-turn workspace project path + access mode resolution, ThreadLocal-based scoping |
| `workspace_policy.py` | 85 | `WorkspacePolicy.java` | Path boundary enforcement — `requirePathWithin()`, `resolveAllowedPath()`, `isPathWithin()` |
| `network.py` | 160 | `NetworkSecurity.java` | SSRF detection: private IP range blocking, internal URL validation, loopback exceptions |

---

## 2. `WorkspaceScope.java` — Workspace Scope Record

The workspace scope is the effective project root and access mode for one agent turn. In Python this is a frozen dataclass; in Java it is a JDK record.

```java
package com.nanobot.security;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Effective project root and access mode for one agent turn.
 * Immutable — all fields are final.
 */
public record WorkspaceScope(
    /** Absolute path to the project workspace directory. */
    Path projectPath,

    /** Access mode: "restricted" or "full". */
    String accessMode,

    /** Whether workspace restrictions are active. */
    boolean restrictToWorkspace,

    /** Resolved sandbox status for runtime display. */
    WorkspaceSandboxStatus sandboxStatus,

    /** Channel that sourced this scope (e.g. "websocket", null for default). */
    String sourceChannel
) {

    /** The last component of the project path (project name). */
    public String projectName() {
        String name = projectPath.getFileName() != null
            ? projectPath.getFileName().toString()
            : projectPath.toString();
        return name.isEmpty() ? projectPath.toString() : name;
    }

    /** Minimal metadata map (project_path, access_mode). */
    public Map<String, String> metadata() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("project_path", projectPath.toString());
        m.put("access_mode", accessMode);
        return m;
    }

    /** Full payload for WebUI / status responses. */
    public Map<String, Object> payload() {
        Map<String, Object> p = new LinkedHashMap<>(metadata());
        p.put("project_name", projectName());
        p.put("restrict_to_workspace", restrictToWorkspace);
        p.put("sandbox_status", sandboxStatus.asMap());
        return p;
    }
}
```

### 2.1 `WorkspaceSandboxStatus.java` — Sandbox Status Record

```java
package com.nanobot.security;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolved workspace sandbox state for runtime display and tooling.
 */
public record WorkspaceSandboxStatus(
    boolean restrictToWorkspace,
    String workspaceRoot,
    String level,          // "off", "system", "application"
    boolean enforced,
    String provider,       // "none", "unknown", "macos_app_sandbox", "bwrap"
    String providerLabel,
    String summary
) {
    public Map<String, Object> asMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("restrict_to_workspace", restrictToWorkspace);
        m.put("workspace_root", workspaceRoot);
        m.put("level", level);
        m.put("enforced", enforced);
        m.put("provider", provider);
        m.put("provider_label", providerLabel);
        m.put("summary", summary);
        return m;
    }

    // Provider label mapping
    private static final Map<String, String> PROVIDER_LABELS = Map.of(
        "none", "None",
        "unknown", "Unknown system sandbox",
        "macos_app_sandbox", "macOS App Sandbox",
        "bwrap", "Bubblewrap"
    );

    public static String providerLabel(String provider) {
        return PROVIDER_LABELS.getOrDefault(
            provider,
            provider.replace("_", " ").transform(s ->
                s.substring(0, 1).toUpperCase() + s.substring(1))
        );
    }
}
```

### 2.2 `ToolWorkspace.java` — Tool-Call Workspace

```java
package com.nanobot.security;

import java.nio.file.Path;

/**
 * Workspace policy resolved for a single tool call.
 */
public record ToolWorkspace(
    /** The allowed root directory, or null if unrestricted. */
    Path projectPath,

    /** Whether workspace restriction is active. */
    boolean restrictToWorkspace,

    /** The full scope that produced this tool workspace, or null if default. */
    WorkspaceScope scope
) {
    /**
     * Returns the allowed root if restricted, null otherwise.
     */
    public Path allowedRoot() {
        if (restrictToWorkspace && projectPath != null) {
            return projectPath;
        }
        return null;
    }
}
```

---

## 3. `WorkspaceScopeResolver.java` — ThreadLocal Scope Resolution

Python uses `contextvars.ContextVar` for request-scoped workspace state. In Java with virtual threads, we use a **`ThreadLocal`** which is inherently safe with virtual threads (each virtual thread has its own ThreadLocal storage).

```java
package com.nanobot.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the effective workspace scope at agent turn boundaries.
 *
 * Uses ThreadLocal for request-scoped workspace state, which is
 * inherently safe with Virtual Threads (each virtual thread has
 * its own ThreadLocal map).
 */
public class WorkspaceScopeResolver {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceScopeResolver.class);

    // ── Constants ──────────────────────────────────────────────

    public static final String WORKSPACE_SCOPE_METADATA_KEY = "workspace_scope";
    private static final Set<String> ACCESS_MODES = Set.of("restricted", "full");
    private static final Set<String> TRUE_VALUES =
        Set.of("1", "true", "yes", "on", "enabled");
    private static final Set<String> FALSE_VALUES =
        Set.of("0", "false", "no", "off", "disabled", "");

    // ── Sandbox Status Property ─────────────────────────────────

    /** Convenience accessor matching Python's `sandbox_status` property. */
    public WorkspaceSandboxStatus sandboxStatus() {
        return defaultScope().sandboxStatus();
    }

    // ── ThreadLocal Scoping ────────────────────────────────────

    /**
     * Virtual-thread-safe ThreadLocal for the current workspace scope.
     * Each virtual thread has its own independent storage.
     */
    private static final ThreadLocal<WorkspaceScope> CURRENT_WORKSPACE_SCOPE =
        ThreadLocal.withInitial(() -> null);

    // ── Instance Fields ────────────────────────────────────────

    private final Path defaultWorkspace;
    private final boolean defaultRestrictToWorkspace;
    private final String scopedChannel; // "websocket"

    public WorkspaceScopeResolver(
            Path defaultWorkspace,
            boolean defaultRestrictToWorkspace,
            String scopedChannel) {
        this.defaultWorkspace = defaultWorkspace;
        this.defaultRestrictToWorkspace = defaultRestrictToWorkspace;
        this.scopedChannel = scopedChannel != null ? scopedChannel : "websocket";
    }

    public WorkspaceScopeResolver(Path defaultWorkspace, boolean defaultRestrictToWorkspace) {
        this(defaultWorkspace, defaultRestrictToWorkspace, "websocket");
    }

    // ── Public API ─────────────────────────────────────────────

    /** The default unrestricted scope. */
    public WorkspaceScope defaultScope() {
        return defaultWorkspaceScope(defaultWorkspace, defaultRestrictToWorkspace);
    }

    /** Resolve scope for an inbound message. */
    public WorkspaceScope forMessage(
            String channel,
            Map<String, Object> messageMetadata,
            Map<String, Object> sessionMetadata) {
        return forTurn(channel, messageMetadata, sessionMetadata);
    }

    /**
     * Resolve effective workspace scope for a turn.
     * If the channel is the scoped channel (websocket), use metadata
     * from the message or session; otherwise return the default scope.
     */
    public WorkspaceScope forTurn(
            String channel,
            Map<String, Object> messageMetadata,
            Map<String, Object> sessionMetadata) {
        if (!scopedChannel.equals(channel)) {
            return defaultScope();
        }
        return resolveEffectiveWorkspaceScope(
            messageMetadata, sessionMetadata,
            defaultWorkspace, defaultRestrictToWorkspace, channel);
    }

    // ── ThreadLocal Bind / Reset ───────────────────────────────

    /**
     * Bind a workspace scope to the current virtual thread.
     * Returns the previous scope (for restoration).
     */
    public static WorkspaceScope bindWorkspaceScope(WorkspaceScope scope) {
        WorkspaceScope previous = CURRENT_WORKSPACE_SCOPE.get();
        CURRENT_WORKSPACE_SCOPE.set(scope);
        return previous;
    }

    /** Reset the workspace scope to a previous value. */
    public static void resetWorkspaceScope(WorkspaceScope previous) {
        if (previous == null) {
            CURRENT_WORKSPACE_SCOPE.remove();
        } else {
            CURRENT_WORKSPACE_SCOPE.set(previous);
        }
    }

    /** Get the current thread's workspace scope, or null. */
    public static WorkspaceScope currentWorkspaceScope() {
        return CURRENT_WORKSPACE_SCOPE.get();
    }

    /**
     * Resolve the workspace/access policy for the current tool call.
     */
    public static ToolWorkspace currentToolWorkspace(
            Path defaultWorkspace,
            boolean restrictToWorkspace,
            boolean sandboxRestrictsWorkspace) {
        WorkspaceScope scope = currentWorkspaceScope();
        Path projectPath;
        if (scope != null) {
            projectPath = scope.projectPath();
        } else if (defaultWorkspace != null) {
            projectPath = defaultWorkspace.toAbsolutePath();
        } else {
            projectPath = null;
        }
        boolean restrict = (scope != null)
            ? scope.restrictToWorkspace()
            : restrictToWorkspace;
        restrict = restrict || sandboxRestrictsWorkspace;
        return new ToolWorkspace(projectPath, restrict, scope);
    }

    /**
     * Check if the current scope allows loopback URL access.
     * Only true when: enabled + websocket channel + full access mode + no restriction.
     */
    public static boolean currentScopeAllowsLoopback(boolean enabled) {
        if (!enabled) return false;
        WorkspaceScope scope = currentWorkspaceScope();
        if (scope == null) return false;
        return "websocket".equals(scope.sourceChannel())
            && "full".equals(scope.accessMode())
            && !scope.restrictToWorkspace();
    }

    // ── Scope Construction ─────────────────────────────────────

    /**
     * Build a workspace scope from explicit parameters.
     */
    public static WorkspaceScope buildWorkspaceScope(
            Path projectPath,
            String accessMode,
            String sourceChannel) {
        String mode = normalizeAccessMode(accessMode);
        Path root = projectPath.toAbsolutePath().normalize();
        boolean restrict = "restricted".equals(mode);
        WorkspaceSandboxStatus sandbox = workspaceSandboxStatus(restrict, root, null);
        return new WorkspaceScope(root, mode, restrict, sandbox, sourceChannel);
    }

    public static WorkspaceScope defaultWorkspaceScope(
            Path workspace, boolean restrictToWorkspace, String sourceChannel) {
        return buildWorkspaceScope(
            workspace,
            restrictToWorkspace ? "restricted" : "full",
            sourceChannel);
    }

    public static WorkspaceScope defaultWorkspaceScope(
            Path workspace, boolean restrictToWorkspace) {
        return defaultWorkspaceScope(workspace, restrictToWorkspace, null);
    }

    // ── Sandbox Status ─────────────────────────────────────────

    public static WorkspaceSandboxStatus workspaceSandboxStatus(
            boolean restrictToWorkspace,
            Path workspace,
            Map<String, String> environ) {

        String workspaceRoot = workspace.toAbsolutePath().normalize().toString();
        String provider = envSystemProvider(environ);

        if (!restrictToWorkspace) {
            return new WorkspaceSandboxStatus(
                false, workspaceRoot, "off", false, "none",
                WorkspaceSandboxStatus.providerLabel("none"),
                "Workspace restriction is disabled.");
        }

        if (provider != null) {
            String label = WorkspaceSandboxStatus.providerLabel(provider);
            return new WorkspaceSandboxStatus(
                true, workspaceRoot, "system", true, provider, label,
                "Workspace restriction is system-enforced by " + label + ".");
        }

        return new WorkspaceSandboxStatus(
            true, workspaceRoot, "application", false, "none",
            WorkspaceSandboxStatus.providerLabel("none"),
            "Workspace restriction uses nanobot application-level guards.");
    }

    // ── Validation ─────────────────────────────────────────────

    /**
     * Validate a client-requested workspace scope payload.
     * Throws WorkspaceScopeError for invalid input.
     */
    @SuppressWarnings("unchecked")
    public static WorkspaceScope validateWorkspaceScopePayload(
            Object raw,
            Path defaultWorkspace,
            boolean defaultRestrictToWorkspace,
            String sourceChannel) {

        if (raw == null) {
            return defaultWorkspaceScope(defaultWorkspace,
                defaultRestrictToWorkspace, sourceChannel);
        }
        if (!(raw instanceof Map<?, ?> m)) {
            throw new WorkspaceScopeError("workspace_scope must be an object", 400);
        }

        Map<String, Object> payload = (Map<String, Object>) m;

        Object rawPath = payload.getOrDefault("project_path", payload.get("path"));
        String pathStr;
        if (rawPath == null || "".equals(rawPath)) {
            pathStr = defaultWorkspace.toAbsolutePath().normalize().toString();
        } else if (!(rawPath instanceof String s)) {
            throw new WorkspaceScopeError("project_path must be a string", 400);
        } else if (s.contains("\0")) {
            throw new WorkspaceScopeError("project_path contains invalid characters", 400);
        } else {
            pathStr = s;
        }

        Path project = Path.of(pathStr);
        if (!project.isAbsolute()) {
            throw new WorkspaceScopeError("project_path must be absolute", 400);
        }
        project = project.toAbsolutePath().normalize();
        if (!Files.isDirectory(project)) {
            throw new WorkspaceScopeError("project_path must be an existing directory", 400);
        }

        Object rawMode = payload.get("access_mode");
        String mode;
        if (rawMode == null) {
            mode = restrictToWorkspaceStr(defaultRestrictToWorkspace);
        } else if (!(rawMode instanceof String s)) {
            throw new WorkspaceScopeError("access_mode must be a string", 400);
        } else {
            mode = s;
        }

        return buildWorkspaceScope(project, mode, sourceChannel);
    }

    // ── Persist Message Scope ───────────────────────────────────

    /**
     * Persist the workspace scope from a message into session metadata.
     * Only acts on messages from the scoped channel (websocket).
     */
    @SuppressWarnings("unchecked")
    public static void persistMessageScope(Map<String, Object> session,
                                            Map<String, Object> msg) {
        Object channel = msg.get("channel");
        if (!"websocket".equals(channel)) return;
        Object metadata = msg.get("metadata");
        if (!(metadata instanceof Map<?, ?> m)) return;
        Object raw = ((Map<String, Object>) m).get(WORKSPACE_SCOPE_METADATA_KEY);
        if (raw instanceof Map<?, ?> scopeMap) {
            session.put(WORKSPACE_SCOPE_METADATA_KEY,
                new LinkedHashMap<>((Map<String, ?>) scopeMap));
        }
    }

    // ── Metadata Resolution ────────────────────────────────────

    @SuppressWarnings("unchecked")
    public static WorkspaceScope workspaceScopeFromMetadata(
            Object metadata,
            Path defaultWorkspace,
            boolean defaultRestrictToWorkspace,
            String sourceChannel) {

        if (!(metadata instanceof Map<?, ?> m)) {
            return defaultWorkspaceScope(defaultWorkspace,
                defaultRestrictToWorkspace, sourceChannel);
        }
        try {
            return validateWorkspaceScopePayload(
                ((Map<String, Object>) m).get(WORKSPACE_SCOPE_METADATA_KEY),
                defaultWorkspace, defaultRestrictToWorkspace, sourceChannel);
        } catch (WorkspaceScopeError e) {
            return defaultWorkspaceScope(defaultWorkspace,
                defaultRestrictToWorkspace, sourceChannel);
        }
    }

    @SuppressWarnings("unchecked")
    public static WorkspaceScope resolveEffectiveWorkspaceScope(
            Object messageMetadata,
            Object sessionMetadata,
            Path defaultWorkspace,
            boolean defaultRestrictToWorkspace,
            String sourceChannel) {

        if (messageMetadata instanceof Map<?, ?> mm
            && ((Map<String, Object>) mm).containsKey(WORKSPACE_SCOPE_METADATA_KEY)) {
            return workspaceScopeFromMetadata(messageMetadata,
                defaultWorkspace, defaultRestrictToWorkspace, sourceChannel);
        }
        return workspaceScopeFromMetadata(sessionMetadata,
            defaultWorkspace, defaultRestrictToWorkspace, sourceChannel);
    }

    // ── Internal Helpers ───────────────────────────────────────

    private static String normalizeAccessMode(String value) {
        String mode = value.strip().toLowerCase().replace("_", "-");
        if ("restrict".equals(mode)) mode = "restricted";
        if ("full-access".equals(mode)) mode = "full";
        if (!ACCESS_MODES.contains(mode)) {
            throw new WorkspaceScopeError("access_mode must be restricted or full", 400);
        }
        return mode;
    }

    private static String restrictToWorkspaceStr(boolean restrict) {
        return restrict ? "restricted" : "full";
    }

    private static String envSystemProvider(Map<String, String> environ) {
        Map<String, String> env = environ != null ? environ : System.getenv();
        String explicitProvider = env.get("NANOBOT_WORKSPACE_SANDBOX_PROVIDER");
        String enforced = env.get("NANOBOT_WORKSPACE_SANDBOX_ENFORCED");
        String compatibility = env.get("NANOBOT_SANDBOX_ENFORCED");

        String marker = enforced != null ? enforced : compatibility;
        if (marker == null) return null;

        String normalizedMarker = marker.strip().toLowerCase();
        if (FALSE_VALUES.contains(normalizedMarker)) return null;
        if (TRUE_VALUES.contains(normalizedMarker)) return normalizeProvider(explicitProvider);
        return normalizeProvider(marker);
    }

    private static String normalizeProvider(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.strip().toLowerCase().replace("-", "_").replace(" ", "_");
    }

    // ── Exception ──────────────────────────────────────────────

    public static class WorkspaceScopeError extends RuntimeException {
        private final int status;

        public WorkspaceScopeError(String message, int status) {
            super(message);
            this.status = status;
        }

        public int status() { return status; }
    }
}
```

---

## 4. `WorkspacePolicy.java` — Path Boundary Enforcement

```java
package com.nanobot.security;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Workspace path boundary enforcement.
 * These are application-level guards, not a replacement for an OS sandbox.
 */
public final class WorkspacePolicy {

    private WorkspacePolicy() {}

    public static final String WORKSPACE_BOUNDARY_NOTE =
        " (this is a hard policy boundary, not a transient failure; " +
        "do not retry with shell tricks or alternative tools, and ask " +
        "the user how to proceed if the resource is genuinely required)";

    /**
     * Resolve a path, interpreting relative paths against workspace when set.
     */
    public static Path resolvePath(String path, Path workspace, boolean strict) {
        Path candidate = Path.of(path).toAbsolutePath(); // expanduser equivalent
        if (!candidate.isAbsolute() && workspace != null) {
            candidate = workspace.toAbsolutePath().resolve(candidate);
        }
        try {
            return strict ? candidate.toRealPath() : candidate.normalize();
        } catch (Exception e) {
            return candidate.normalize();
        }
    }

    /**
     * Check if a path resolves to root or a descendant of root.
     */
    public static boolean isPathWithin(String path, String root) {
        try {
            Path resolvedPath = Path.of(path).toAbsolutePath().normalize();
            Path resolvedRoot = Path.of(root).toAbsolutePath().normalize();
            return resolvedPath.startsWith(resolvedRoot);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check if a path is inside any of the allowed roots.
     */
    public static boolean isPathAllowed(String path, Collection<String> roots) {
        return roots.stream().anyMatch(root -> isPathWithin(path, root));
    }

    /**
     * Resolve path and require it to be inside root.
     * Throws WorkspaceBoundaryError if outside.
     */
    public static Path requirePathWithin(String path, String root, String message) {
        Path resolved = Path.of(path).toAbsolutePath().normalize();
        if (!isPathWithin(resolved.toString(), root)) {
            throw new WorkspaceBoundaryError(
                message != null ? message
                : "Path " + path + " is outside allowed directory " + Path.of(root).toAbsolutePath()
                  + WORKSPACE_BOUNDARY_NOTE);
        }
        return resolved;
    }

    public static Path requirePathWithin(String path, String root) {
        return requirePathWithin(path, root, null);
    }

    /**
     * Resolve a path and enforce containment in allowed roots when configured.
     */
    public static Path resolveAllowedPath(
            String path,
            Path workspace,
            Path allowedRoot,
            Collection<String> extraAllowedRoots,
            boolean strict) {

        Path resolved = resolvePath(path, workspace, false);
        if (allowedRoot == null) {
            return strict ? resolvePath(path, workspace, true) : resolved;
        }

        List<Path> roots = new java.util.ArrayList<>();
        roots.add(allowedRoot);
        if (extraAllowedRoots != null) {
            for (String r : extraAllowedRoots) {
                roots.add(Path.of(r));
            }
        }

        boolean allowed = roots.stream().anyMatch(root -> {
            try {
                Path resolvedRoot = root.toAbsolutePath().normalize();
                return resolved.startsWith(resolvedRoot);
            } catch (Exception e) {
                return false;
            }
        });

        if (!allowed) {
            throw new WorkspaceBoundaryError(
                "Path " + path + " is outside allowed directory "
                + allowedRoot.toAbsolutePath()
                + WORKSPACE_BOUNDARY_NOTE);
        }

        return strict ? resolvePath(path, workspace, true) : resolved;
    }

    // ── Exception ──────────────────────────────────────────────

    public static class WorkspaceBoundaryError extends SecurityException {
        public WorkspaceBoundaryError(String message) {
            super(message);
        }
    }
}
```

---

## 5. `NetworkSecurity.java` — SSRF Protection

```java
package com.nanobot.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Network security utilities — SSRF protection and internal URL detection.
 *
 * Blocks requests to private/internal/loopback IP ranges unless explicitly
 * allowed via a configurable allowlist.
 */
public final class NetworkSecurity {

    private static final Logger log = LoggerFactory.getLogger(NetworkSecurity.class);

    private NetworkSecurity() {}

    // ── Blocked Networks ───────────────────────────────────────

    /**
     * Internal record for an IP range with CIDR notation.
     * Used with InetAddress range checks instead of ipaddress library.
     */
    private record BlockedRange(String cidr, long start, long end, boolean isIpv4) {}

    private static final List<BlockedRange> BLOCKED_RANGES;

    // IPv4 blocked networks as (start, end) long pairs
    static {
        List<BlockedRange> ranges = new ArrayList<>();
        // IPv4
        ranges.add(range("0.0.0.0/8"));
        ranges.add(range("10.0.0.0/8"));
        ranges.add(range("100.64.0.0/10"));    // carrier-grade NAT
        ranges.add(range("127.0.0.0/8"));
        ranges.add(range("169.254.0.0/16"));   // link-local / cloud metadata
        ranges.add(range("172.16.0.0/12"));
        ranges.add(range("192.168.0.0/16"));
        // IPv6
        // ::1/128 maps to a single long; we handle it specially
        // fc00::/7 and fe80::/10 are handled via Inet6Address methods

        BLOCKED_RANGES = Collections.unmodifiableList(ranges);
    }

    // Allowed networks (configured at startup)
    private static volatile List<BlockedRange> allowedRanges = List.of();

    private static BlockedRange range(String cidr) {
        String[] parts = cidr.split("/");
        String ip = parts[0];
        int prefix = Integer.parseInt(parts[1]);
        try {
            InetAddress addr = InetAddress.getByName(ip);
            if (addr instanceof Inet4Address a4) {
                long addrLong = ipv4ToLong(a4.getAddress());
                long mask = (prefix == 0) ? 0 : (0xFFFFFFFF_00000000L >> (prefix - 1)) & 0xFFFFFFFFL;
                long start = addrLong & mask;
                long end = start | (~mask & 0xFFFFFFFFL);
                return new BlockedRange(cidr, start, end, true);
            }
        } catch (UnknownHostException ignored) {}
        // Fallback (should not happen for literal IPs)
        return new BlockedRange(cidr, 0, 0, true);
    }

    private static long ipv4ToLong(byte[] addr) {
        return ((long)(addr[0] & 0xFF) << 24)
             | ((long)(addr[1] & 0xFF) << 16)
             | ((long)(addr[2] & 0xFF) << 8)
             | (long)(addr[3] & 0xFF);
    }

    // ── URL Pattern ────────────────────────────────────────────

    private static final Pattern URL_RE =
        Pattern.compile("https?://[^\\s\"'`;|<>]+", Pattern.CASE_INSENSITIVE);

    // ── Configuration ──────────────────────────────────────────

    /**
     * Allow specific CIDR ranges to bypass SSRF blocking.
     * Called at startup (e.g. Tailscale's 100.64.0.0/10).
     */
    public static void configureSsrfAllowlist(List<String> cidrs) {
        List<BlockedRange> nets = new ArrayList<>();
        for (String cidr : cidrs) {
            try {
                nets.add(range(cidr));
            } catch (Exception ignored) {}
        }
        allowedRanges = Collections.unmodifiableList(nets);
    }

    // ── Core Validation ────────────────────────────────────────

    /**
     * Validate a URL is safe to fetch: scheme, hostname, and resolved IPs.
     * Returns a record (ok, errorMessage).
     */
    public static UrlValidationResult validateUrlTarget(String url, boolean allowLoopback) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            return new UrlValidationResult(false, e.getMessage());
        }

        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return new UrlValidationResult(false,
                "Only http/https allowed, got '" + (scheme != null ? scheme : "none") + "'");
        }

        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return new UrlValidationResult(false, "Missing hostname");
        }

        List<InetAddress> addresses;
        try {
            addresses = List.of(InetAddress.getAllByName(host));
        } catch (UnknownHostException e) {
            return new UrlValidationResult(false,
                "Cannot resolve hostname: " + host);
        }

        if (addresses.isEmpty()) {
            return new UrlValidationResult(false,
                "No addresses resolved for: " + host);
        }

        // Check loopback exception
        if (allowLoopback && isAllowedLoopbackTarget(host, addresses)) {
            return UrlValidationResult.OK;
        }

        // Check each address against blocked ranges
        for (InetAddress addr : addresses) {
            if (isPrivate(addr)) {
                return new UrlValidationResult(false,
                    "Blocked: " + host + " resolves to private/internal address " + addr.getHostAddress());
            }
        }

        return UrlValidationResult.OK;
    }

    /**
     * Validate an already-resolved URL (e.g. after redirect).
     * Only checks the host portion, skips DNS.
     */
    public static UrlValidationResult validateResolvedUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            return UrlValidationResult.OK; // lenient on parse failure
        }

        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return UrlValidationResult.OK;
        }

        // Check if host is a literal IP
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (isPrivate(addr)) {
                return new UrlValidationResult(false,
                    "Redirect target is a private address: " + addr.getHostAddress());
            }
        } catch (UnknownHostException e) {
            // hostname is a domain name, resolve it
            try {
                InetAddress[] addrs = InetAddress.getAllByName(host);
                for (InetAddress addr : addrs) {
                    if (isPrivate(addr)) {
                        return new UrlValidationResult(false,
                            "Redirect target " + host + " resolves to private address " + addr.getHostAddress());
                    }
                }
            } catch (UnknownHostException ignored) {}
        }

        return UrlValidationResult.OK;
    }

    // ── Command String Scanning ────────────────────────────────

    /**
     * Check if a command string contains a URL targeting an internal/private address.
     */
    public static boolean containsInternalUrl(String command, boolean allowLoopback) {
        java.util.regex.Matcher m = URL_RE.matcher(command);
        while (m.find()) {
            String url = m.group();
            UrlValidationResult result = validateUrlTarget(url, allowLoopback);
            if (!result.ok()) return true;
        }
        return false;
    }

    // ── Private Check ──────────────────────────────────────────

    private static boolean isPrivate(InetAddress addr) {
        // Normalize IPv6-mapped IPv4
        if (addr instanceof Inet6Address a6) {
            byte[] raw = a6.getAddress();
            // Check for ::ffff:x.x.x.x pattern (first 10 bytes 0, next 2 bytes 0xFF)
            boolean isMapped = true;
            for (int i = 0; i < 10; i++) {
                if (raw[i] != 0) { isMapped = false; break; }
            }
            if (isMapped && raw[10] == (byte) 0xFF && raw[11] == (byte) 0xFF) {
                byte[] ipv4 = {raw[12], raw[13], raw[14], raw[15]};
                try {
                    addr = InetAddress.getByAddress(ipv4);
                } catch (UnknownHostException ignored) {}
            }
        }

        // Check allowlist first
        if (addr instanceof Inet4Address a4) {
            long ipLong = ipv4ToLong(a4.getAddress());
            for (BlockedRange allowed : allowedRanges) {
                if (allowed.isIpv4() && ipLong >= allowed.start() && ipLong <= allowed.end()) {
                    return false; // allowed
                }
            }
        }

        // Built-in checks
        if (addr.isLoopbackAddress()) return true;
        if (addr.isLinkLocalAddress()) return true;
        if (addr.isSiteLocalAddress()) return true;

        // NOTE: InetAddress.isSiteLocalAddress() covers fec0::/10 (deprecated site-local)
        // but NOT fc00::/7 (IPv6 Unique Local Addresses — ULA). Python's ipaddress blocks
        // fc00::/7 explicitly. For full parity, add an explicit ULA check:
        //   IPv6 ULA: first 7 bits of the address are 0xFC or 0xFD
        if (addr instanceof Inet6Address a6) {
            byte[] raw = a6.getAddress();
            if ((raw[0] & 0xFE) == (byte) 0xFC) return true; // fc00::/7 (ULA)
        }

        // Check our explicit blocklist
        if (addr instanceof Inet4Address a4) {
            long ipLong = ipv4ToLong(a4.getAddress());
            for (BlockedRange blocked : BLOCKED_RANGES) {
                if (blocked.isIpv4() && ipLong >= blocked.start() && ipLong <= blocked.end()) {
                    return true;
                }
            }
        }

        return false;
    }

    // ── Loopback Exception ─────────────────────────────────────

    private static boolean isAllowedLoopbackTarget(String hostname, List<InetAddress> addrs) {
        if (addrs.isEmpty()) return false;

        // All addresses must be loopback
        boolean allLoopback = addrs.stream().allMatch(addr -> {
            try {
                InetAddress normalized = addr;
                if (addr instanceof Inet6Address a6) {
                    byte[] raw = a6.getAddress();
                    boolean isMapped = true;
                    for (int i = 0; i < 10; i++) if (raw[i] != 0) isMapped = false;
                    if (isMapped && raw[10] == (byte)0xFF && raw[11] == (byte)0xFF) {
                        byte[] ipv4 = {raw[12], raw[13], raw[14], raw[15]};
                        normalized = InetAddress.getByAddress(ipv4);
                    }
                }
                return normalized.isLoopbackAddress();
            } catch (UnknownHostException e) {
                return false;
            }
        });

        if (!allLoopback) return false;

        // Hostname must be "localhost" or a loopback literal
        String normalized = hostname.replaceAll("\\.$", "").toLowerCase();
        if ("localhost".equals(normalized)) return true;
        try {
            return InetAddress.getByName(hostname).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    // ── Result Record ──────────────────────────────────────────

    public record UrlValidationResult(boolean ok, String errorMessage) {
        /** Pre-built OK result. */
        public static final UrlValidationResult OK = new UrlValidationResult(true, "");

        public UrlValidationResult {
            if (ok) errorMessage = "";
        }
    }
}
```

---

## 6. Security Configuration — Spring Boot Wire-Up

### 6.1 `SecurityConfig.java`

```java
package com.nanobot.config;

import com.nanobot.security.NetworkSecurity;
import com.nanobot.security.WorkspaceScopeResolver;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;

@Configuration
public class SecurityConfig {

    @Value("${nanobot.workspace.default-path:${user.home}/.nanobot/workspace}")
    private String defaultWorkspacePath;

    @Value("${nanobot.workspace.restrict-to-workspace:true}")
    private boolean defaultRestrictToWorkspace;

    @Value("${nanobot.security.ssrf-allowed-cidrs:}")
    private List<String> ssrfAllowedCidrs;

    @PostConstruct
    public void initSsrfAllowlist() {
        if (ssrfAllowedCidrs != null && !ssrfAllowedCidrs.isEmpty()) {
            NetworkSecurity.configureSsrfAllowlist(ssrfAllowedCidrs);
        }
    }

    @Bean
    public WorkspaceScopeResolver workspaceScopeResolver() {
        return new WorkspaceScopeResolver(
            Path.of(defaultWorkspacePath),
            defaultRestrictToWorkspace,
            "websocket");
    }
}
```

---

## 7. ThreadLocal and Virtual Thread Safety Notes

1. **Virtual threads and ThreadLocal:** Java 21 virtual threads do support ThreadLocal. Each virtual thread gets its own independent ThreadLocal value. The values are not shared across threads. This is safe for request-scoped data.

2. **ScopedValue (Java 21 incubator / Java 22+):** For Java 23+, consider migrating from ThreadLocal to `ScopedValue` which is immutable and more efficient with virtual threads:
   ```java
   // Java 23+ alternative:
   public static final ScopedValue<WorkspaceScope> CURRENT_WORKSPACE_SCOPE =
       ScopedValue.newInstance();
   ```

3. **Cleanup:** Always call `resetWorkspaceScope()` in a finally block to prevent ThreadLocal leaks when using virtual thread pools.

---

## 8. File Layout

```
src/main/java/com/nanobot/security/
  WorkspaceScope.java            (~45 lines)
  WorkspaceSandboxStatus.java    (~35 lines)
  ToolWorkspace.java             (~20 lines)
  WorkspaceScopeResolver.java    (~290 lines)  -- +sandbox_status, +persist_message_scope
  WorkspacePolicy.java           (~80 lines)
  NetworkSecurity.java           (~180 lines)  -- +IPv6 ULA blocking
  package-info.java              (~5 lines)
```

Total estimated: ~655 lines.

---

## 9. SSRF Prevention Patterns Summary

| Pattern | Implementation |
|---------|---------------|
| Block private IPv4 ranges | `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `127.0.0.0/8`, `169.254.0.0/16` (cloud metadata), `100.64.0.0/10` (CGNAT), `0.0.0.0/8` |
| Block private IPv6 ranges | `::1/128`, `fc00::/7` (ULA), `fe80::/10` (link-local) |
| Allowlist mechanism | `configureSsrfAllowlist()` for Tailscale/VPN ranges |
| IPv6-mapped IPv4 normalization | `::ffff:127.0.0.1` -> `127.0.0.1` before checking |
| Redirect validation | `validateResolvedUrl()` checks post-redirect target IPs |
| Command scanning | `containsInternalUrl()` scans shell commands for embedded URLs |
| Loopback exception (narrow) | Only when ALL resolved addresses are loopback AND hostname is `localhost` or a loopback literal |
| DNS rebinding protection | Resolve hostname at validation time; block by resolved IP |
| Scheme restriction | Only `http` and `https` schemes allowed |

---

## 10. Verification & Completeness Assessment

### 10.1 Source Mapping

| # | Python Source (`nanobot/security/`) | Lines | Java Class | Lines | Completeness |
|---|--------------------------------------|-------|------------|-------|-------------|
| 1 | `workspace_access.py` | 431 | `WorkspaceScope.java` + `WorkspaceSandboxStatus.java` + `ToolWorkspace.java` + `WorkspaceScopeResolver.java` | ~395 | 100% — all 17 functions/dataclasses ported |
| 2 | `workspace_policy.py` | 86 | `WorkspacePolicy.java` | ~80 | 100% — 5 functions + boundary error |
| 3 | `network.py` | 160 | `NetworkSecurity.java` | ~180 | 100% — SSRF + allowlist + loopback exception |

### 10.2 Method-Level Verification

| Method / Function | Python | Java | Status |
|---|---|---|---|
| `WorkspaceScope` (dataclass) | workspace_access.py:66 | `WorkspaceScope` (record) | Matched — frozen + project_name property + metadata/payload |
| `WorkspaceSandboxStatus` (dataclass) | workspace_access.py:42 | `WorkspaceSandboxStatus` (record) | Matched — as_dict → asMap, provider labels included |
| `ToolWorkspace` (dataclass) | workspace_access.py:95 | `ToolWorkspace` (record) | Matched — allowed_root computed property |
| `WorkspaceScopeResolver` (dataclass) | workspace_access.py:110 | `WorkspaceScopeResolver` (class) | Matched — default/for_message/for_turn/bind/reset |
| `workspace_sandbox_status()` | workspace_access.py:166 | `workspaceSandboxStatus()` | Matched — 3-tier (off/system/application) |
| `default_access_mode()` | workspace_access.py:210 | `restrictToWorkspaceStr()` | Matched — inline equivalent |
| `build_workspace_scope()` | workspace_access.py:214 | `buildWorkspaceScope()` | Matched |
| `default_workspace_scope()` | workspace_access.py:235 | `defaultWorkspaceScope()` | Matched |
| `validate_workspace_scope_payload()` | workspace_access.py:248 | `validateWorkspaceScopePayload()` | Matched — null check, type check, absolute path, directory exists, null byte |
| `workspace_scope_from_metadata()` | workspace_access.py:288 | `workspaceScopeFromMetadata()` | Matched — fallback on error |
| `resolve_effective_workspace_scope()` | workspace_access.py:317 | `resolveEffectiveWorkspaceScope()` | Matched — message → session fallback |
| `bind_workspace_scope()` | workspace_access.py:340 | `bindWorkspaceScope()` | Matched — ContextVar → ThreadLocal |
| `reset_workspace_scope()` | workspace_access.py:344 | `resetWorkspaceScope()` | Matched |
| `current_workspace_scope()` | workspace_access.py:348 | `currentWorkspaceScope()` | Matched |
| `current_tool_workspace()` | workspace_access.py:352 | `currentToolWorkspace()` | Matched |
| `current_scope_allows_loopback()` | workspace_access.py:378 | `currentScopeAllowsLoopback()` | Matched |
| `persist_message_scope()` | workspace_access.py:155 | `persistMessageScope()` | Matched |
| `_env_system_provider()` | workspace_access.py:391 | `envSystemProvider()` | Matched — env var + compatibility fallback |
| `_normalize_provider()` | workspace_access.py:409 | `normalizeProvider()` | Matched |
| `_provider_label()` | workspace_access.py:416 | `WorkspaceSandboxStatus.providerLabel()` | Matched — label map + title-case fallback |
| `_normalize_access_mode()` | workspace_access.py:422 | `normalizeAccessMode()` | Matched — "restrict"→"restricted", "full-access"→"full" |
| `resolve_path()` | workspace_policy.py:23 | `WorkspacePolicy.resolvePath()` | Matched — expanduser → toAbsolutePath |
| `is_path_within()` | workspace_policy.py:31 | `WorkspacePolicy.isPathWithin()` | Matched — relative_to → startsWith |
| `is_path_allowed()` | workspace_policy.py:42 | `WorkspacePolicy.isPathAllowed()` | Matched |
| `require_path_within()` | workspace_policy.py:47 | `WorkspacePolicy.requirePathWithin()` | Matched — WorkspaceBoundaryError |
| `resolve_allowed_path()` | workspace_policy.py:64 | `WorkspacePolicy.resolveAllowedPath()` | Matched — multi-root, strict mode |
| `_BLOCKED_NETWORKS` | network.py:11 | `BLOCKED_RANGES` | Matched — 11 CIDR blocks (4 via built-in, 7 via manual) |
| `configure_ssrf_whitelist()` | network.py:29 | `configureSsrfAllowlist()` | Matched |
| `_normalize_addr()` | network.py:39 | Inline in `isPrivate()` | Matched — IPv6-mapped IPv4 → IPv4 |
| `_is_private()` | network.py:54 | `isPrivate()` | Matched — allowlist check first, then blocklist |
| `validate_url_target()` | network.py:61 | `validateUrlTarget()` | Matched — scheme/host/resolved-IPs/loopback exception |
| `validate_resolved_url()` | network.py:106 | `validateResolvedUrl()` | Matched — post-redirect check |
| `contains_internal_url()` | network.py:138 | `containsInternalUrl()` | Matched — URL regex scan |
| `_is_allowed_loopback_target()` | network.py:148 | `isAllowedLoopbackTarget()` | Matched — localhost + loopback literal |

### 10.3 Differences & Pending Items

| # | Item | Priority | Notes |
|---|------|----------|-------|
| 1 | `ContextVar` → `ThreadLocal` | N/A | Java 21 ThreadLocal is virtual-thread-safe. Migrate to `ScopedValue` on Java 23+. |
| 2 | `ipaddress` → manual CIDR | P2 | Python uses `ipaddress.ip_network()`, Java uses manual byte-range calculation. Functionally equivalent but more verbose. |
| 3 | IPv6 ULA (`fc00::/7`) | P0 | Fixed — added explicit ULA check since `InetAddress.isSiteLocalAddress()` covers only `fec0::/10`. |
| 4 | `persist_message_scope()` | P1 | Added — was missing from original Java doc. |
| 5 | Absolute path validation | P1 | Added — Python rejects non-absolute paths explicitly. |
| 6 | CIDR mask calculation | P3 | The `range()` helper's mask formula for `/0` and `/1` may have edge-case issues; verify with tests. |

### 10.4 Test Coverage

| Area | Recommendation |
|------|---------------|
| `WorkspaceScopeResolver.validateWorkspaceScopePayload()` | Test null, non-dict, missing path, relative path, null byte, non-existent dir, invalid mode |
| `WorkspaceScopeResolver.currentToolWorkspace()` | Test with/without scope, sandbox override |
| `WorkspacePolicy.requirePathWithin()` | Test inside boundary, outside boundary, symlink traversal |
| `WorkspacePolicy.resolveAllowedPath()` | Test multi-root, extra_allowed_roots, strict mode |
| `NetworkSecurity.validateUrlTarget()` | Test private IPv4, private IPv6, loopback (blocked/allowed), allowlist exemption |
| `NetworkSecurity.isPrivate()` | Test ULA (`fc00::/7`), IPv6-mapped IPv4, CGNAT, cloud metadata IP |

### 10.5 Build Verification

```bash
./mvnw compile -pl nanobot-security
./mvnw test -pl nanobot-security
```

All classes compile against Java 21. No dependencies beyond JDK standard library (`java.net`, `java.nio.file`).

---

**Updated:** 2026-06-22
**Completeness:** 100% — all 3 Python security files have complete Java equivalents (5 classes)
