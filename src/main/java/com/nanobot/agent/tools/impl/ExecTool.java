package com.nanobot.agent.tools.impl;

import com.nanobot.agent.tools.*;
import com.nanobot.agent.tools.path.PathUtils;
import com.nanobot.agent.tools.sandbox.Sandbox;
import com.nanobot.agent.tools.schema.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shell 命令执行工具，是系统中最复杂的工具。
 *
 * <p>对标 Python {@code nanobot/agent/tools/shell.py ExecTool}（677 行）。
 * 支持多层安全检查：deny/allow 模式匹配、工作空间边界保护、
 * 内部 URL 检测（SSRF 防护）、平台特定 shell 选择、
 * 以及基于会话的长时间运行命令执行。
 *
 * <p>安全检查流水线：guardCommand → prepareCommand → spawn → execute
 */
public class ExecTool extends Tool {

    private static final Logger log = LoggerFactory.getLogger(ExecTool.class);

    // ==================== 配置常量 ====================

    /** 硬编码 deny 模式：阻止危险命令（对标 Python HARD_DENY_PATTERNS）。 */
    // 对标 Python ExecTool._HARD_DENY_PATTERNS
    static final List<String> HARD_DENY_PATTERNS = List.of(
            "\\brm\\s+-[rf]{1,2}\\b",
            "\\bdel\\s+/[fq]\\b",
            "\\brmdir\\s+/s\\b",
            "(?:^|[;&|]\\s*)format(?!=)\\b",
            "\\b(mkfs|diskpart)\\b",
            "\\bdd\\s+if=",
            ">\\s*/dev/sd",
            "\\b(shutdown|reboot|poweroff)\\b",
            ":\\(\\)\\s*\\{.*\\};\\s*:",
            ">>?\\s*\\S*(?:history\\.jsonl|\\.dream_cursor)",
            "\\btee\\b[^|;&<>]*(?:history\\.jsonl|\\.dream_cursor)",
            "\\b(?:cp|mv)\\b(?:\\s+[^\\s|;&<>]+)+\\s+\\S*(?:history\\.jsonl|\\.dream_cursor)",
            "\\bdd\\b[^|;&<>]*\\bof=\\S*(?:history\\.jsonl|\\.dream_cursor)",
            "\\bsed\\s+-i[^|;&<>]*(?:history\\.jsonl|\\.dream_cursor)"
    );

    /** 工作空间边界违反时的提示信息。 */
    // 对标 Python WORKSPACE_BOUNDARY_NOTE
    static final String WORKSPACE_BOUNDARY_NOTE =
            "\n\nNote: this is a hard policy boundary, not a transient failure. "
                    + "Do NOT retry with shell tricks (symlinks, base64 piping, alternative "
                    + "tools, working_dir overrides). If the user genuinely needs this "
                    + "resource, tell them you cannot reach it under the current "
                    + "restrict_to_workspace policy and ask how to proceed.";

    /** 安全的 stdio 重定向目标 —— 内核设备文件。 */
    // 对标 Python BENIGN_DEVICE_PATHS
    static final Set<String> BENIGN_DEVICE_PATHS = Set.of(
            "/dev/null", "/dev/zero", "/dev/full", "/dev/random", "/dev/urandom",
            "/dev/stdin", "/dev/stdout", "/dev/stderr", "/dev/tty"
    );

    /** 最大超时秒数。 */
    static final int MAX_TIMEOUT = 600;
    /** Windows 平台检测。 */
    static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase().contains("windows");

    // ==================== 参数 Schema ====================

    /** 工具参数 JSON Schema 定义。 */
    // 对标 Python ExecTool.parameters
    private static final Map<String, Object> PARAMETERS =
            ToolParametersSchema.create(
                    null, null,
                    Map.of(
                            "command", new StringSchema("The shell command to execute"),
                            "cmd", new StringSchema("Compatibility alias for command"),
                            "working_dir", new StringSchema(
                                    "Optional working directory for the command"),
                            "workdir", new StringSchema(
                                    "Compatibility alias for working_dir"),
                            "timeout", new IntegerSchema(60,
                                    "Timeout in seconds (default 60, max 600)",
                                    1, 600, null, false),
                            "shell", new StringSchema(
                                    "Optional shell binary. On Unix: sh, bash, or zsh.",
                                    null, null, null, true),
                            "login", new BooleanSchema(
                                    "Whether to run bash/zsh with login shell semantics "
                                            + "(default true)", true, true),
                            "yield_time_ms", new IntegerSchema(0,
                                    "Milliseconds to wait before returning output. "
                                            + "When set, returns session_id",
                                    0, 30_000, null, true),
                            "max_output_chars", new IntegerSchema(10000,
                                    "Maximum output chars when yield_time_ms is used "
                                            + "(default 10000, max 50000)",
                                    1000, 50_000, null, true),
                            "max_output_tokens", new IntegerSchema(10000,
                                    "Compatibility alias for max_output_chars",
                                    1000, 50_000, null, true)
                    )
            );

    // ==================== 实例字段 ====================

    private final ToolContext ctx;
    private final ExecToolConfig config;
    private final List<Pattern> compiledDenyPatterns;
    private final List<Pattern> compiledAllowPatterns;

    /**
     * 构造 ExecTool。
     *
     * @param ctx    工具上下文
     * @param config ExecTool 配置
     */
    // 对标 Python ExecTool.__init__()
    public ExecTool(ToolContext ctx, ExecToolConfig config) {
        this.ctx = ctx;
        this.config = config;
        this.compiledDenyPatterns = new ArrayList<>();
        for (String p : config.denyPatterns()) {
            compiledDenyPatterns.add(Pattern.compile(p, Pattern.CASE_INSENSITIVE));
        }
        this.compiledAllowPatterns = new ArrayList<>();
        for (String p : config.allowPatterns()) {
            compiledAllowPatterns.add(Pattern.compile(p, Pattern.CASE_INSENSITIVE));
        }
    }

    // ==================== Tool 抽象方法实现 ====================

    @Override
    public String getName() { return "exec"; }

    @Override
    public String getDescription() {
        return "Execute a shell command in the agent's workspace.\n\n"
                + "Security: commands are sandboxed and checked against deny/allow patterns.\n"
                + "Paths outside the workspace are blocked unless explicitly allowed.\n"
                + "For long-running commands, use yield_time_ms to run in session mode.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolParameters.deepCopy(PARAMETERS);
    }

    @Override
    public boolean isReadOnly() { return false; }

    @Override
    public boolean isExclusive() { return true; }

    @Override
    public Set<String> getScopes() { return Set.of("core", "subagent"); }

    // ==================== 执行入口 ====================

    /**
     * 执行 shell 命令（one-shot 模式）或启动会话模式执行。
     *
     * @param params 已校验的工具参数
     * @return 输出字符串或错误信息的 CompletableFuture
     */
    @Override
    // 对标 Python ExecTool.execute()
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String command = paramString(params, "command", "cmd");
                if (command == null || command.isBlank()) {
                    return "Error: command parameter is required";
                }

                Path cwd = resolveWorkingDir(params);
                boolean restrictToWorkspace = isRestrictToWorkspace();
                Path mediaDir = resolveMediaDir();

                // 1. 安全检查
                String guardError = guardCommand(command, cwd, restrictToWorkspace,
                        config.webuiAllowLocalServiceAccess());
                if (guardError != null) return guardError;

                // 2. 准备命令（PATH、sandbox、shell、working_dir 验证）
                String shellParam = paramString(params, "shell");
                PreparedCommand prepared = prepareCommand(command, cwd,
                        shellParam, params);
                if (prepared.error() != null) return prepared.error();

                // 3. 判断是否会话模式
                Integer yieldTimeMs = paramInt(params, "yield_time_ms");
                if (yieldTimeMs != null && yieldTimeMs > 0) {
                    return executeSession(prepared, params);
                }
                return executeOneShot(prepared, params);

            } catch (Exception e) {
                log.error("ExecTool error", e);
                return "Error executing command: " + e.getMessage();
            }
        });
    }

    // ==================== 安全检查 ====================

    /**
     * 对潜在危险命令进行最佳安全检查。
     * 返回 null 表示通过所有检查，否则返回错误消息。
     *
     * @param command                   原始命令
     * @param cwd                       工作目录
     * @param restrictToWorkspace       是否限制在工作空间内
     * @param webuiAllowLocalServiceAccess 是否允许访问本地服务
     * @return null（通过）或错误消息
     */
    // 对标 Python ExecTool._guardCommand()
    public String guardCommand(String command, Path cwd,
                                boolean restrictToWorkspace,
                                boolean webuiAllowLocalServiceAccess) {

        String cmd = command.strip();
        String lower = cmd.toLowerCase();

        // 1. Allow 模式优先于 deny 模式
        boolean explicitlyAllowed = !compiledAllowPatterns.isEmpty()
                && compiledAllowPatterns.stream().anyMatch(p -> p.matcher(lower).find());

        if (!explicitlyAllowed) {
            // 2. 先检查硬编码 deny 模式
            for (String pattern : HARD_DENY_PATTERNS) {
                if (Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
                        .matcher(lower).find()) {
                    return "Error: Command blocked by deny pattern filter";
                }
            }
            // 3. 检查配置级别 deny 模式
            for (Pattern p : compiledDenyPatterns) {
                if (p.matcher(lower).find()) {
                    return "Error: Command blocked by deny pattern filter";
                }
            }
            // 4. 如果配置了 allow 模式且命令不在其中
            if (!compiledAllowPatterns.isEmpty()) {
                return "Error: Command blocked by allowlist filter (not in allowlist)";
            }
        }

        // 5. 内部 URL 检测（SSRF 防护）
        if (NetworkSecurity.containsInternalUrl(cmd, webuiAllowLocalServiceAccess)) {
            return "Error: Command blocked by safety guard (internal/private URL detected)";
        }

        // 6. 工作空间边界保护
        if (restrictToWorkspace) {
            if (cmd.contains("../") || cmd.contains("..\\")) {
                return "Error: Command blocked by safety guard (path traversal detected)"
                        + WORKSPACE_BOUNDARY_NOTE;
            }

            List<String> absPaths = extractAbsolutePaths(cmd);
            for (String absPath : absPaths) {
                try {
                    String expanded = expandEnvVars(absPath);
                    if (isBenignDevicePath(expanded)) continue;

                    Path resolved = Path.of(expanded.replaceFirst("^~",
                            System.getProperty("user.home")));
                    resolved = resolved.normalize().toAbsolutePath();
                    if (isBenignDevicePath(resolved.toString())) continue;

                    Path mediaDir = resolveMediaDir();
                    if (!PathUtils.isPathWithin(resolved, cwd)
                            && (mediaDir == null
                                || !PathUtils.isPathWithin(resolved, mediaDir))) {
                        return "Error: Command blocked by safety guard "
                                + "(path outside working dir)"
                                + WORKSPACE_BOUNDARY_NOTE;
                    }
                } catch (Exception e) {
                    continue;
                }
            }
        }
        return null;
    }

    /**
     * 展开字符串中的环境变量引用（$HOME 等）。
     *
     * @param path 包含环境变量的路径
     * @return 展开后的字符串
     */
    private String expandEnvVars(String path) {
        String result = path;
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            result = result.replace("$" + e.getKey(), e.getValue());
        }
        return result;
    }

    // ==================== 路径提取 ====================

    /**
     * 从命令字符串中提取绝对路径，用于工作空间边界检查。
     *
     * @param command 命令字符串
     * @return 提取到的绝对路径列表
     */
    // 对标 Python ExecTool._extract_absolute_paths()
    static List<String> extractAbsolutePaths(String command) {
        List<String> paths = new ArrayList<>();
        // Windows 路径：C:\path, \\server\share
        Matcher win = Pattern.compile(
                "(?<![A-Za-z])(?:[A-Za-z]:[^\\s\"'|><;]*|"
                        + "\\\\[^\\s\"'|><;]+(?:\\[^\\s\"'|><;]+)*)").matcher(command);
        while (win.find()) paths.add(win.group());
        // POSIX 绝对路径
        Matcher posix = Pattern.compile(
                "(?:^|[\\s|>'\"])(/[^\\s\"'>;|<]+)").matcher(command);
        while (posix.find()) paths.add(posix.group(1));
        // Home 快捷路径
        Matcher home = Pattern.compile(
                "(?:^|[\\s>'\"])(~[^\\s\"'>;|<]*)").matcher(command);
        while (home.find()) paths.add(home.group(1));
        return paths;
    }

    /**
     * 判断路径是否为安全的设备文件路径。
     *
     * @param path 路径字符串
     * @return 是安全设备路径返回 true
     */
    // 对标 Python _is_benign_device_path()
    static boolean isBenignDevicePath(String path) {
        if (BENIGN_DEVICE_PATHS.contains(path)) return true;
        return path.startsWith("/dev/fd/");
    }

    // ==================== 命令准备 ====================

    /**
     * 准备命令：处理工作目录、sandbox 包装和 PATH 组合。
     *
     * @param command    原始命令
     * @param cwd        工作目录
     * @param shell      指定 shell 程序（可为 null）
     * @param params     工具参数（用于 working_dir 验证）
     * @return 准备好的 PreparedCommand 或错误消息字符串
     */
    // 对标 Python ExecTool._prepareCommand()
    private PreparedCommand prepareCommand(String command, Path cwd,
                                           String shell,
                                           Map<String, Object> params) {
        // working_dir 验证（对标 Python 第 381-394 行）
        if (isRestrictToWorkspace() && ctx.workspace() != null) {
            try {
                Path requested = Path.of(
                        cwd.toString().replaceFirst("^~",
                                System.getProperty("user.home")))
                        .toRealPath();
                Path resolvedRoot = Path.of(ctx.workspace())
                        .toRealPath();
                if (!PathUtils.isPathWithin(requested, resolvedRoot)) {
                    return new PreparedCommand(null, null, null, null,
                            "Error: working_dir is outside the configured workspace"
                                    + WORKSPACE_BOUNDARY_NOTE);
                }
            } catch (Exception e) {
                return new PreparedCommand(null, null, null, null,
                        "Error: working_dir could not be resolved"
                                + WORKSPACE_BOUNDARY_NOTE);
            }
        }

        String result = command;
        Path effectiveCwd = cwd;

        if (config.sandbox() != null && !config.sandbox().isEmpty()) {
            Path mediaDir = resolveMediaDir();
            result = Sandbox.wrapCommand(config.sandbox(), result,
                    effectiveCwd, effectiveCwd, mediaDir);
            effectiveCwd = Path.of(ctx.workspace()).toAbsolutePath().normalize();
        }

        Map<String, String> env = buildEnv();

        // path_prepend / path_append
        String pp = config.pathPrepend();
        String pa = config.pathAppend();
        if ((pp != null && !pp.isEmpty()) || (pa != null && !pa.isEmpty())) {
            if (IS_WINDOWS) {
                env.put("PATH", composePath(env.getOrDefault("PATH", "")));
            } else {
                result = wrapPathExport(result, env);
            }
        }

        // shell 解析
        String shellProgram;
        String shellError = resolveShell(shell);
        if (shellError != null) {
            if (shellError.startsWith("Error:")) {
                return new PreparedCommand(null, null, null, null, shellError);
            }
            shellProgram = shellError; // resolveShell returns the path
        } else {
            shellProgram = null;
        }

        return new PreparedCommand(result, effectiveCwd.toString(), env,
                shellProgram, null);
    }

    /** 准备好的命令及其上下文。 */
    // 对标 Python _PreparedCommand
    private record PreparedCommand(String command, String cwd,
                                   Map<String, String> env,
                                   String shellProgram, String error) {}

    /**
     * 组合路径段为 Windows PATH 变量。
     *
     * @param currentPath 当前 PATH 值
     * @return 组合后的 PATH 字符串
     */
    // 对标 Python ExecTool._compose_path()
    private String composePath(String currentPath) {
        StringBuilder sb = new StringBuilder();
        String pp = config.pathPrepend();
        String pa = config.pathAppend();
        if (pp != null && !pp.isEmpty()) sb.append(pp);
        if (currentPath != null && !currentPath.isEmpty()) {
            if (!sb.isEmpty()) sb.append(File.pathSeparator);
            sb.append(currentPath);
        }
        if (pa != null && !pa.isEmpty()) {
            if (!sb.isEmpty()) sb.append(File.pathSeparator);
            sb.append(pa);
        }
        return sb.toString();
    }

    /**
     * 在 Unix 上通过 export PATH 包装命令以注入路径段。
     *
     * @param command 原始命令
     * @param env     环境变量 Map
     * @return 包装后的命令字符串
     */
    // 对标 Python ExecTool._wrap_path_export()
    private String wrapPathExport(String command, Map<String, String> env) {
        List<String> segments = new ArrayList<>();
        String pp = config.pathPrepend();
        String pa = config.pathAppend();
        if (pp != null && !pp.isEmpty()) {
            env.put("NANOBOT_PATH_PREPEND", pp);
            segments.add("$NANOBOT_PATH_PREPEND");
        }
        segments.add("$PATH");
        if (pa != null && !pa.isEmpty()) {
            env.put("NANOBOT_PATH_APPEND", pa);
            segments.add("$NANOBOT_PATH_APPEND");
        }
        String pathExpr = String.join(File.pathSeparator, segments);
        return "export PATH=\"" + pathExpr + "\"; " + command;
    }

    /**
     * 解析 shell 参数，返回 shell 程序路径或错误消息。
     *
     * @param shell shell 名称或绝对路径，null 表示使用默认值
     * @return shell 程序路径，或错误消息字符串，null 表示使用默认值
     */
    // 对标 Python ExecTool._resolve_shell()
    static String resolveShell(String shell) {
        if (shell == null) return null;
        if (IS_WINDOWS)
            return "Error: shell parameter is not supported on Windows";
        if (shell.contains("\0") || shell.contains("\n")
                || shell.contains("\r"))
            return "Error: shell contains invalid characters";
        Set<String> allowed = Set.of("sh", "bash", "zsh");
        Path path = Path.of(shell.replaceFirst("^~",
                System.getProperty("user.home")));
        if (path.isAbsolute()) {
            String name = path.getFileName().toString();
            if (!allowed.contains(name))
                return "Error: unsupported shell '" + shell
                        + "'. Allowed: bash, sh, zsh";
            if (!Files.isRegularFile(path) || !Files.isExecutable(path))
                return "Error: shell is not executable: " + shell;
            return path.toString();
        }
        if (shell.contains("/") || shell.contains("\\"))
            return "Error: shell must be a shell name or absolute path";
        if (!allowed.contains(shell))
            return "Error: unsupported shell '" + shell
                    + "'. Allowed: bash, sh, zsh";
        // 在 PATH 中查找
        for (String dir : System.getenv("PATH").split(File.pathSeparator)) {
            Path candidate = Path.of(dir, shell);
            if (Files.isExecutable(candidate)) return candidate.toString();
        }
        return "Error: shell not found: " + shell;
    }

    // ==================== 进程派生 ====================

    /**
     * 在平台合适的 shell 中启动命令。
     * Windows：多行用 powershell，单行用 cmd。
     * Unix：优先 bash -l（登录 shell），回退到 sh。
     *
     * @param command      准备好的命令
     * @param cwd          工作目录
     * @param env          环境变量
     * @param shellProgram 指定 shell 程序（可为 null）
     * @param login        是否使用登录 shell
     * @param sessionMode  是否会话模式（PIPE stdin vs DEVNULL stdin）
     * @return 已启动 Process 的 CompletableFuture
     */
    // 对标 Python ExecTool._spawn()
    public static CompletableFuture<Process> spawn(
            String command, Path cwd, Map<String, String> env,
            String shellProgram, boolean login, boolean sessionMode) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ProcessBuilder pb;
                if (IS_WINDOWS) {
                    if (command.contains("\n")) {
                        pb = new ProcessBuilder(
                                "powershell", "-NoProfile", "-Command", command);
                    } else {
                        pb = new ProcessBuilder("cmd", "/c", command);
                    }
                } else {
                    String shell = (shellProgram != null) ? shellProgram
                            : (Files.exists(Path.of("/bin/bash"))
                                ? "/bin/bash" : "/bin/sh");
                    List<String> args = new ArrayList<>();
                    args.add(shell);
                    String shellName = Path.of(shell).getFileName()
                            .toString().toLowerCase();
                    if (login && (shellName.equals("bash")
                            || shellName.equals("zsh"))) {
                        args.add("-l");
                    }
                    args.add("-c");
                    args.add(command);
                    pb = new ProcessBuilder(args);
                }

                pb.directory(cwd.toFile());
                pb.environment().clear();
                pb.environment().putAll(env);
                if (sessionMode) {
                    pb.redirectInput(ProcessBuilder.Redirect.PIPE);
                } else {
                    pb.redirectInput(ProcessBuilder.Redirect.DISCARD);
                }
                pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                pb.redirectError(ProcessBuilder.Redirect.PIPE);

                return pb.start();
            } catch (Exception e) {
                throw new RuntimeException("Failed to spawn process: "
                        + e.getMessage(), e);
            }
        });
    }

    /**
     * 构建子进程执行的最小环境变量集。
     * Unix：仅 HOME/LANG/TERM（bash -l 会 source 用户 profile）。
     * Windows：精选系统变量集，不含密钥。
     *
     * @return 环境变量 Map
     */
    // 对标 Python ExecTool._build_env()
    public Map<String, String> buildEnv() {
        if (IS_WINDOWS) {
            Map<String, String> env = new LinkedHashMap<>();
            String sysRoot = System.getenv().getOrDefault(
                    "SYSTEMROOT", "C:\\Windows");
            env.put("SYSTEMROOT", sysRoot);
            env.put("COMSPEC", System.getenv().getOrDefault("COMSPEC",
                    sysRoot + "\\system32\\cmd.exe"));
            env.put("USERPROFILE", System.getenv().getOrDefault(
                    "USERPROFILE", ""));
            env.put("TEMP", System.getenv().getOrDefault(
                    "TEMP", sysRoot + "\\Temp"));
            env.put("TMP", System.getenv().getOrDefault(
                    "TMP", sysRoot + "\\Temp"));
            env.put("PATH", System.getenv().getOrDefault(
                    "PATH", sysRoot + "\\system32;" + sysRoot));
            env.put("PYTHONUNBUFFERED", "1");
            for (String key : config.allowedEnvKeys()) {
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
            for (String key : config.allowedEnvKeys()) {
                String val = System.getenv(key);
                if (val != null) env.put(key, val);
            }
            return env;
        }
    }

    // ==================== 执行模式 ====================

    /**
     * One-shot 命令执行：等待进程完成，处理超时和输出截断。
     *
     * @param command 准备好的命令
     * @param cwd     工作目录
     * @param params  工具参数
     * @return 命令输出字符串
     */
    // 对标 Python ExecTool._execute_one_shot()
    private String executeOneShot(PreparedCommand prepared,
                                   Map<String, Object> params) {
        Integer timeoutSec = resolveTimeout(paramInt(params, "timeout"));
        String shellProgram = prepared.shellProgram();
        boolean login = paramBool(params, "login", true);

        try {
            Process process = spawn(prepared.command(),
                    Path.of(prepared.cwd()), prepared.env(), shellProgram,
                    login, false).get(30, TimeUnit.SECONDS);

            if (timeoutSec != null && timeoutSec > 0) {
                boolean finished = process.waitFor(
                        timeoutSec.longValue(), TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    // 对标 Python: FIFO reaping
                    killFifoChain(process);
                    return "Error: Command timed out after " + timeoutSec + "s";
                }
            } else {
                process.waitFor();
            }

            String stdout = new String(
                    process.getInputStream().readAllBytes()).stripTrailing();
            String stderr = new String(
                    process.getErrorStream().readAllBytes()).stripTrailing();

            StringBuilder result = new StringBuilder();
            if (!stdout.isEmpty()) result.append(stdout);
            if (!stderr.isEmpty()) {
                if (!result.isEmpty()) result.append('\n');
                result.append(stderr);
            }

            int exitCode = process.exitValue();
            if (result.isEmpty()) {
                result.append("Command exited with code ").append(exitCode);
            }

            // 截断过长输出
            int maxChars = resolveMaxOutputChars(params);
            if (result.length() > maxChars) {
                result.setLength(maxChars);
                result.append("\n... [truncated at ")
                        .append(maxChars).append(" chars]");
            }

            return result.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 会话模式执行：委托给 ExecSessionManager。
     *
     * @param command 准备好的命令
     * @param cwd     工作目录
     * @param params  工具参数
     * @return session_id 字符串
     */
    private String executeSession(PreparedCommand prepared,
                                   Map<String, Object> params) {
        // 会话模式委托给 ExecSessionManager（后续实现）
        return "Error: Session mode requires ExecSessionManager. "
                + "Use one-shot mode (omit yield_time_ms).";
    }

    /**
     * 递归终止进程的 FIFO chain（杀掉管道中所有子进程）。
     *
     * @param process 根进程
     */
    // 对标 Python ExecTool._kill_fifo_chain()
    private void killFifoChain(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
    }

    // ==================== 配置解析 ====================

    /**
     * 解析有效超时：每次调用的限制（上限 MAX_TIMEOUT=600s）
     * 或配置级默认值。值为 0 表示无限制。
     *
     * @param perCallTimeout 每次调用的超时参数
     * @return 超时秒数，null 表示无限制
     */
    // 对标 Python ExecTool._resolve_timeout()
    Integer resolveTimeout(Integer perCallTimeout) {
        if (perCallTimeout != null && perCallTimeout > 0) {
            return Math.min(perCallTimeout, MAX_TIMEOUT);
        }
        if (config.timeout() > 0) {
            return config.timeout();
        }
        return null;
    }

    /**
     * 获取最大输出字符数。
     *
     * @param params 工具参数
     * @return 最大字符数
     */
    private int resolveMaxOutputChars(Map<String, Object> params) {
        Integer chars = paramInt(params, "max_output_chars");
        if (chars == null) chars = paramInt(params, "max_output_tokens");
        return (chars != null && chars > 0) ? Math.min(chars, 50_000) : 50_000;
    }

    /**
     * 解析工作目录参数。
     *
     * @param params 工具参数
     * @return 解析后的工作目录路径
     */
    private Path resolveWorkingDir(Map<String, Object> params) {
        String dir = paramString(params, "working_dir", "workdir");
        if (dir != null && !dir.isEmpty()) {
            return Path.of(ctx.workspace()).resolve(dir).normalize();
        }
        return Path.of(ctx.workspace());
    }

    /**
     * 判断是否限制在工作空间内。
     *
     * @return 默认 true
     */
    private boolean isRestrictToWorkspace() {
        return true;
    }

    /**
     * 获取 media 目录路径。
     *
     * @return media 目录路径
     */
    private Path resolveMediaDir() {
        // 对标 Python get_media_dir()
        return Path.of(ctx.workspace(), "media");
    }

    // ==================== 参数辅助方法 ====================

    /**
     * 从参数 Map 中获取字符串值，支持别名回退。
     *
     * @param params 参数 Map
     * @param primary 主参数名
     * @param fallback 回退参数名
     * @return 字符串值，不存在返回 null
     */
    private static String paramString(Map<String, Object> params,
                                       String primary, String fallback) {
        Object val = params.get(primary);
        if (val instanceof String s && !s.isEmpty()) return s;
        val = params.get(fallback);
        if (val instanceof String s && !s.isEmpty()) return s;
        return null;
    }

    private static String paramString(Map<String, Object> params, String key) {
        Object val = params.get(key);
        return val instanceof String s ? s : null;
    }

    /**
     * 从参数 Map 中获取整数值。
     *
     * @param params 参数 Map
     * @param key    参数名
     * @return 整数值，不存在或无效返回 null
     */
    private static Integer paramInt(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val instanceof Integer i) return i;
        if (val instanceof Number n) return n.intValue();
        return null;
    }

    /**
     * 从参数 Map 中获取布尔值。
     *
     * @param params 参数 Map
     * @param key    参数名
     * @param def    默认值
     * @return 布尔值
     */
    private static boolean paramBool(Map<String, Object> params,
                                      String key, boolean def) {
        Object val = params.get(key);
        if (val instanceof Boolean b) return b;
        return def;
    }
}
