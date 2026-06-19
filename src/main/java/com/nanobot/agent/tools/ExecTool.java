package com.nanobot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Shell command execution tool.
 * Port of Python ExecTool (shell.py, 678 lines) — simplified for P3.
 *
 * Differences from Python (to be addressed in later phases):
 * - No async subprocess (Java ProcessBuilder is synchronous; virtual threads handle concurrency)
 * - No session mode (yield_time_ms / write_stdin — requires ExecSession infrastructure)
 * - No sandbox integration (bubblewrap — P4+)
 * - Simplified security guards (core deny patterns only)
 * - No internal URL detection (requires network security module)
 */
@Component
public class ExecTool extends Tool {

    private static final Logger log = LoggerFactory.getLogger(ExecTool.class);

    // _scopes matching Python
    @SuppressWarnings("unused")
    private static final Set<String> _scopes = Set.of("core", "subagent");

    private static final int MAX_TIMEOUT = 600;
    private static final int DEFAULT_MAX_OUTPUT = 10_000;

    // Port of Python deny patterns (core destructive commands)
    private static final List<Pattern> DENY_PATTERNS = List.of(
            Pattern.compile("\\brm\\s+-[rf]{1,2}\\b"),
            Pattern.compile("\\bdel\\s+/[fq]\\b"),
            Pattern.compile("\\brmdir\\s+/s\\b"),
            Pattern.compile("(?:^|[;&|]\\s*)format(?!=)\\b"),
            Pattern.compile("\\b(mkfs|diskpart)\\b"),
            Pattern.compile("\\bdd\\s+if="),
            Pattern.compile(">\\s*/dev/sd"),
            Pattern.compile("\\b(shutdown|reboot|poweroff)\\b"),
            Pattern.compile(":\\(\\)\\s*\\{.*\\};\\s*:"),
            Pattern.compile(">>?\\s*\\S*(?:history\\.jsonl|\\.dream_cursor)"),
            Pattern.compile("\\btee\\b[^|;&<>]*(?:history\\.jsonl|\\.dream_cursor)"),
            Pattern.compile("\\b(?:cp|mv)\\b(?:\\s+[^\\s|;&<>]+)+\\s+\\S*(?:history\\.jsonl|\\.dream_cursor)"),
            Pattern.compile("\\bdd\\b[^|;&<>]*\\bof=\\S*(?:history\\.jsonl|\\.dream_cursor)"),
            Pattern.compile("\\bsed\\s+-i[^|;&<>]*(?:history\\.jsonl|\\.dream_cursor)")
    );

    private static final Set<String> BENIGN_DEVICE_PATHS = Set.of(
            "/dev/null", "/dev/zero", "/dev/full", "/dev/random", "/dev/urandom",
            "/dev/stdin", "/dev/stdout", "/dev/stderr", "/dev/tty"
    );

    private final int configTimeout;
    private final String workingDir;
    private final boolean restrictToWorkspace;
    private final boolean sandboxEnabled;
    private final String pathPrepend;
    private final String pathAppend;
    private final List<String> allowedEnvKeys;
    private final List<Pattern> allowPatterns;
    private final List<Pattern> denyPatterns;

    public ExecTool() {
        this(60, null, false, false, "", "", List.of(), List.of(), List.of());
    }

    public ExecTool(
            int configTimeout,
            String workingDir,
            boolean restrictToWorkspace,
            boolean sandboxEnabled,
            String pathPrepend,
            String pathAppend,
            List<String> allowedEnvKeys,
            List<String> allowPatternsRaw,
            List<String> denyPatternsRaw
    ) {
        this.configTimeout = configTimeout;
        this.workingDir = workingDir;
        this.restrictToWorkspace = restrictToWorkspace;
        this.sandboxEnabled = sandboxEnabled;
        this.pathPrepend = pathPrepend != null ? pathPrepend : "";
        this.pathAppend = pathAppend != null ? pathAppend : "";
        this.allowedEnvKeys = allowedEnvKeys != null ? allowedEnvKeys : List.of();
        this.allowPatterns = allowPatternsRaw != null
                ? allowPatternsRaw.stream().map(Pattern::compile).toList()
                : List.of();
        // Merge built-in deny patterns with user-supplied ones
        List<Pattern> merged = new ArrayList<>(DENY_PATTERNS);
        if (denyPatternsRaw != null) {
            denyPatternsRaw.stream().map(Pattern::compile).forEach(merged::add);
        }
        this.denyPatterns = List.copyOf(merged);
    }

    @Override
    public String name() { return "exec"; }

    @Override
    public String configKey() { return "exec"; }

    @Override
    public boolean isExclusive() { return true; }

    @Override
    @SuppressWarnings("unchecked")
    public Class<?> configClass() { return null; } // ExecToolConfig not yet ported

    @Override
    public boolean isEnabled(ToolContext ctx) {
        // Port of Python: ctx.config.exec.enable
        return true;
    }

    @Override
    public String description() {
        return "Execute a shell command and return its output. "
                + "Use this for tests, builds, package commands, git commands, and "
                + "other process execution. Prefer read_file/find_files/grep for "
                + "inspection and apply_patch/write_file/edit_file for file changes "
                + "instead of cat, shell find/grep, echo, or sed. "
                + "Use -y or --yes flags to avoid interactive prompts. "
                + "Output is truncated at 10 000 chars; timeout defaults to 60s.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Schema.toolParametersSchema(
                List.of("command"),
                "Shell command execution parameters",
                Map.of(
                        "command", Map.of("type", "string", "description", "The shell command to execute"),
                        "cmd", Map.of("type", "string", "description", "Compatibility alias for command"),
                        "working_dir", Map.of("type", "string", "description", "Optional working directory for the command"),
                        "workdir", Map.of("type", "string", "description", "Compatibility alias for working_dir"),
                        "timeout", Map.of("type", "integer", "description",
                                "Timeout in seconds (default 60, max 600)", "minimum", 1, "maximum", 600),
                        "shell", Map.of("type", "string", "description",
                                "Optional shell binary to launch (sh, bash, or zsh)")
                )
        );
    }

    @Override
    public Object execute(Map<String, Object> params, ToolContext ctx) throws Exception {
        String command = (String) params.getOrDefault("command", params.get("cmd"));
        String cwd = (String) params.getOrDefault("working_dir", params.get("workdir"));
        Integer timeoutSec = params.get("timeout") instanceof Number n ? n.intValue() : null;
        String shell = (String) params.get("shell");

        if (command == null || command.isBlank()) {
            return "Error: Missing command. Provide command or cmd.";
        }

        // Guard against destructive commands
        String guardError = guardCommand(command);
        if (guardError != null) return guardError;

        // Resolve timeout
        int effectiveTimeout = resolveTimeout(timeoutSec);
        // Resolve cwd
        String effectiveCwd = cwd != null ? cwd : (workingDir != null ? workingDir : System.getProperty("user.dir"));

        // Resolve shell
        String shellPath = resolveShell(shell);
        if (shellPath != null && shellPath.startsWith("Error")) return shellPath;

        // Build environment
        Map<String, String> env = buildEnv();

        // Build command with path prepend/append
        String finalCommand = wrapPathExport(command, env);

        ProcessBuilder pb;
        String os = System.getProperty("os.name").toLowerCase();
        boolean isWindows = os.contains("win");

        if (isWindows) {
            if (command.contains("\n")) {
                pb = new ProcessBuilder("powershell", "-NoProfile", "-Command", finalCommand);
            } else {
                pb = new ProcessBuilder("cmd.exe", "/c", finalCommand);
            }
        } else {
            String sh = shellPath != null ? shellPath : findBash();
            if (sh.contains("bash") || sh.contains("zsh")) {
                pb = new ProcessBuilder(sh, "-l", "-c", finalCommand);
            } else {
                pb = new ProcessBuilder(sh, "-c", finalCommand);
            }
        }

        pb.directory(new File(effectiveCwd));
        pb.environment().clear();
        pb.environment().putAll(env);
        pb.redirectErrorStream(false);

        try {
            Process process = pb.start();
            boolean finished = process.waitFor(effectiveTimeout, TimeUnit.SECONDS);
            byte[] stdoutBytes = process.getInputStream().readAllBytes();
            byte[] stderrBytes = process.getErrorStream().readAllBytes();

            if (!finished) {
                process.destroyForcibly();
                return "Error: Command timed out after " + effectiveTimeout + " seconds";
            }

            StringBuilder result = new StringBuilder();
            if (stdoutBytes.length > 0) {
                result.append(new String(stdoutBytes));
            }
            String stderrText = new String(stderrBytes);
            if (!stderrText.isBlank()) {
                if (!result.isEmpty()) result.append("\n");
                result.append("STDERR:\n").append(stderrText);
            }
            result.append("\nExit code: ").append(process.exitValue());

            String output = result.toString();
            if (output.length() > DEFAULT_MAX_OUTPUT) {
                int half = DEFAULT_MAX_OUTPUT / 2;
                output = output.substring(0, half)
                        + "\n\n... (" + (output.length() - DEFAULT_MAX_OUTPUT) + " chars truncated) ...\n\n"
                        + output.substring(output.length() - half);
            }
            return output;
        } catch (Exception e) {
            return "Error executing command: " + e.getMessage();
        }
    }

    // ---- Internal helpers ----

    private int resolveTimeout(Integer perCallTimeout) {
        if (perCallTimeout != null && perCallTimeout > 0) return Math.min(perCallTimeout, MAX_TIMEOUT);
        if (configTimeout > 0) return configTimeout;
        return 60;
    }

    private String guardCommand(String command) {
        String lower = command.strip().toLowerCase();

        // allow_patterns take priority
        boolean explicitlyAllowed = !allowPatterns.isEmpty()
                && allowPatterns.stream().anyMatch(p -> p.matcher(lower).find());
        if (explicitlyAllowed) return null;

        for (Pattern p : denyPatterns) {
            if (p.matcher(lower).find()) {
                return "Error: Command blocked by deny pattern filter";
            }
        }

        if (!allowPatterns.isEmpty()) {
            return "Error: Command blocked by allowlist filter (not in allowlist)";
        }

        // Path traversal check when restrictToWorkspace is enabled
        if (restrictToWorkspace) {
            if (command.contains("..\\") || command.contains("../")) {
                return "Error: Command blocked by safety guard (path traversal detected)";
            }
        }

        return null;
    }

    private static String findBash() {
        for (String path : List.of("/bin/bash", "/usr/bin/bash", "/usr/local/bin/bash")) {
            if (Files.isExecutable(Path.of(path))) return path;
        }
        return "/bin/sh";
    }

    private String resolveShell(String shell) {
        if (shell == null) return null;
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "Error: shell parameter is not supported on Windows";
        if (shell.contains("\0") || shell.contains("\n") || shell.contains("\r")) {
            return "Error: shell contains invalid characters";
        }
        Set<String> allowed = Set.of("sh", "bash", "zsh");
        Path path = Path.of(shell.replaceFirst("^~", System.getProperty("user.home")));
        if (path.isAbsolute()) {
            if (!allowed.contains(path.getFileName().toString())) {
                return "Error: unsupported shell '" + shell + "'. Allowed: bash, sh, zsh";
            }
            if (!Files.isExecutable(path)) {
                return "Error: shell is not executable: " + shell;
            }
            return path.toString();
        }
        if (shell.contains("/") || shell.contains("\\")) {
            return "Error: shell must be a shell name or absolute path";
        }
        if (!allowed.contains(shell)) {
            return "Error: unsupported shell '" + shell + "'. Allowed: bash, sh, zsh";
        }
        String resolved = findInPath(shell);
        if (resolved == null) return "Error: shell not found: " + shell;
        return resolved;
    }

    private static String findInPath(String executable) {
        return java.util.stream.Stream.of(System.getenv("PATH").split(File.pathSeparator))
                .map(dir -> Path.of(dir, executable))
                .filter(Files::isExecutable)
                .map(Path::toString)
                .findFirst()
                .orElse(null);
    }

    private Map<String, String> buildEnv() {
        String os = System.getProperty("os.name").toLowerCase();
        boolean isWindows = os.contains("win");

        Map<String, String> env = new LinkedHashMap<>();
        if (isWindows) {
            String sr = System.getenv().getOrDefault("SYSTEMROOT", "C:\\Windows");
            env.put("SYSTEMROOT", sr);
            env.put("COMSPEC", System.getenv().getOrDefault("COMSPEC", sr + "\\system32\\cmd.exe"));
            env.put("USERPROFILE", System.getenv().getOrDefault("USERPROFILE", ""));
            env.put("TEMP", System.getenv().getOrDefault("TEMP", sr + "\\Temp"));
            env.put("TMP", System.getenv().getOrDefault("TMP", sr + "\\Temp"));
            env.put("PATH", System.getenv().getOrDefault("PATH", sr + "\\system32;" + sr));
            env.put("PYTHONUNBUFFERED", "1");
        } else {
            env.put("HOME", System.getenv().getOrDefault("HOME", "/tmp"));
            env.put("LANG", System.getenv().getOrDefault("LANG", "C.UTF-8"));
            env.put("TERM", System.getenv().getOrDefault("TERM", "dumb"));
            env.put("PYTHONUNBUFFERED", "1");
            // Forward PATH so bash -l can extend it
            String path = System.getenv("PATH");
            if (path != null) env.put("PATH", path);
        }

        for (String key : allowedEnvKeys) {
            String val = System.getenv(key);
            if (val != null) env.put(key, val);
        }
        return env;
    }

    private String wrapPathExport(String command, Map<String, String> env) {
        if (pathPrepend.isEmpty() && pathAppend.isEmpty()) return command;
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            String current = env.getOrDefault("PATH", "");
            String newPath = (!pathPrepend.isEmpty() ? pathPrepend + File.pathSeparator : "")
                    + current
                    + (!pathAppend.isEmpty() ? File.pathSeparator + pathAppend : "");
            env.put("PATH", newPath);
            return command;
        }
        StringBuilder segments = new StringBuilder();
        if (!pathPrepend.isEmpty()) {
            env.put("NANOBOT_PATH_PREPEND", pathPrepend);
            segments.append("$NANOBOT_PATH_PREPEND:");
        }
        segments.append("$PATH");
        if (!pathAppend.isEmpty()) {
            env.put("NANOBOT_PATH_APPEND", pathAppend);
            segments.append(":$NANOBOT_PATH_APPEND");
        }
        return "export PATH=\"" + segments + "\"; " + command;
    }
}
