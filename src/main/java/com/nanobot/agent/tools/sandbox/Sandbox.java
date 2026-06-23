package com.nanobot.agent.tools.sandbox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * shell 命令执行的 sandbox 后端。
 *
 * <p>对标 Python {@code nanobot/agent/tools/sandbox.py}（65 行）。
 * 目前支持 "bwrap"（bubblewrap），提供 Linux 容器式隔离。
 * 工作空间以读写方式 bind mount；media 目录以只读方式 bind mount，
 * 使 exec 命令可读取上传的附件。
 */
public final class Sandbox {

    private Sandbox() {}

    /** 必需的 bind mount 路径（只读）。 */
    // 对标 Python _bwrap() required
    public static final List<String> REQUIRED_BINDS = List.of("/usr");
    /** 可选的 bind mount 路径（只读，失败不报错）。 */
    // 对标 Python _bwrap() optional
    public static final List<String> OPTIONAL_BINDS = List.of(
            "/bin", "/lib", "/lib64", "/etc/alternatives",
            "/etc/ssl/certs", "/etc/resolv.conf", "/etc/ld.so.cache"
    );

    /**
     * 用 bubblewrap sandbox 包装 shell 命令。
     *
     * @param command   原始 shell 命令
     * @param workspace 工作空间绝对路径
     * @param cwd       工作空间内的工作目录
     * @param mediaDir  media 目录绝对路径（只读挂载）
     * @return bwrap 包装后的命令字符串
     */
    // 对标 Python _bwrap(command, workspace, cwd)
    public static String bwrap(String command, Path workspace, Path cwd, Path mediaDir) {
        Path ws = workspace.normalize().toAbsolutePath();
        Path media = mediaDir.normalize().toAbsolutePath();

        Path sandboxCwd;
        try {
            sandboxCwd = ws.resolve(ws.relativize(cwd.normalize().toAbsolutePath()));
        } catch (IllegalArgumentException e) {
            sandboxCwd = ws;
        }

        List<String> args = new ArrayList<>();
        args.add("bwrap");
        args.add("--new-session");
        args.add("--die-with-parent");
        args.add("--setenv");
        args.add("HOME");
        args.add(ws.toString());

        // 对标 Python: required bind mounts（只读）
        for (String p : REQUIRED_BINDS) {
            args.add("--ro-bind");
            args.add(p);
            args.add(p);
        }
        // 对标 Python: optional bind mounts（失败不报错）
        for (String p : OPTIONAL_BINDS) {
            args.add("--ro-bind-try");
            args.add(p);
            args.add(p);
        }

        args.addAll(List.of("--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp"));
        // 对标 Python: 隐藏 config 所在父目录
        args.addAll(List.of("--tmpfs", ws.getParent().toString()));
        args.addAll(List.of("--dir", ws.toString()));
        args.addAll(List.of("--bind", ws.toString(), ws.toString()));

        // 对标 Python: media 只读访问
        args.add("--ro-bind-try");
        args.add(media.toString());
        args.add(media.toString());

        args.addAll(List.of("--chdir", sandboxCwd.toString()));
        args.addAll(List.of("--", "sh", "-c", command));

        return joinCommand(args);
    }

    /**
     * 使用指定 sandbox 后端包装命令。
     *
     * @param sandbox   sandbox 后端名称（当前仅 "bwrap"）
     * @param command   原始 shell 命令
     * @param workspace 工作空间绝对路径
     * @param cwd       工作目录
     * @param mediaDir  media 目录绝对路径
     * @return 包装后的命令字符串
     * @throws IllegalArgumentException 如果 sandbox 后端未知
     */
    // 对标 Python wrap_command(sandbox, command, workspace, cwd)
    public static String wrapCommand(String sandbox, String command,
                                      Path workspace, Path cwd, Path mediaDir) {
        return switch (sandbox) {
            case "bwrap" -> bwrap(command, workspace, cwd, mediaDir);
            default -> throw new IllegalArgumentException(
                    "Unknown sandbox backend '" + sandbox + "'. Available: [bwrap]");
        };
    }

    /**
     * 将命令部分列表拼接为 shell 安全的字符串，使用单引号转义。
     *
     * @param parts 命令各部分
     * @return 拼接并转义后的命令字符串
     */
    // 对标 Python shlex.join(args)
    private static String joinCommand(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!sb.isEmpty()) sb.append(' ');
            if (part.contains(" ") || part.contains("'") || part.isEmpty()) {
                sb.append('\'').append(part.replace("'", "'\\''")).append('\'');
            } else {
                sb.append(part);
            }
        }
        return sb.toString();
    }
}
