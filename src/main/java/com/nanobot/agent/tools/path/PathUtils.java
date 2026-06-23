package com.nanobot.agent.tools.path;

import java.nio.file.Path;
import java.util.List;

/**
 * 工作空间作用域工具的共享路径工具方法。
 *
 * <p>对标 Python {@code nanobot/agent/tools/path_utils.py}（31 行）。
 * 提供工作空间路径解析与目录包含性校验，自动将 media 目录加入额外可读根。
 */
public final class PathUtils {

    private PathUtils() {}

    /**
     * 将用户提供的路径相对于工作空间解析，并强制限制在允许的目录内。
     *
     * <p>对标 Python {@code resolve_workspace_path}：media 目录自动作为额外
     * 可读根加入（附件只读访问），同时支持调用方提供的额外允许目录。
     *
     * @param path             用户输入路径
     * @param workspace        工作空间根目录
     * @param allowedDir       允许写入/读取的主目录
     * @param mediaDir         媒体附件目录（自动加入额外可读根）
     * @param extraAllowedDirs 调用方提供的额外允许目录
     * @return 解析并规范化后的路径
     * @throws SecurityException 如果路径在允许目录之外
     */
    // 对标 Python resolve_workspace_path()
    public static Path resolveWorkspacePath(
            String path,
            Path workspace,
            Path allowedDir,
            Path mediaDir,
            List<Path> extraAllowedDirs) {

        Path resolved = workspace.resolve(path).normalize();

        if (allowedDir != null) {
            if (isPathWithin(resolved, allowedDir)) {
                return resolved;
            }
            // 对标 Python: get_media_dir() 自动加入 extra_allowed_roots
            if (mediaDir != null && isPathWithin(resolved, mediaDir)) {
                return resolved;
            }
            if (extraAllowedDirs != null) {
                for (Path extra : extraAllowedDirs) {
                    if (isPathWithin(resolved, extra)) {
                        return resolved;
                    }
                }
            }
            throw new SecurityException(
                    "Path '" + path + "' is outside the allowed workspace directory");
        }
        return resolved;
    }

    /**
     * 检查子路径是否在父目录内（含相等情况）。
     *
     * @param child  子路径
     * @param parent 父目录
     * @return child 以 parent 开头时返回 true
     */
    // 对标 Python is_under() → is_path_within()
    public static boolean isPathWithin(Path child, Path parent) {
        Path cp = child.normalize().toAbsolutePath();
        Path pp = parent.normalize().toAbsolutePath();
        return cp.startsWith(pp);
    }
}
