package com.nanobot.config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 运行时路径工具，提供 nanobot 数据目录、子目录及 workspace 路径的统一解析。
 *
 * <p>独立工具类，不依赖 Config。与 Python 一样，仅 {@code getConfigPath()} 通过懒加载委托给 ConfigLoader。
 *
 * <p>对标 Python {@code nanobot/config/paths.py:1-76}。
 */
public final class AppPaths {

    private AppPaths() {}

    /**
     * 返回当前配置文件路径，默认 ~/.nanobot/config.json。
     *
     * @return 配置文件路径
     */
    // 对标 Python paths.py:10-17 get_config_path()
    public static Path getConfigPath() {
        return ConfigLoader.getConfigPath();
    }

    /**
     * 返回 nanobot 数据根目录（config 文件的父目录），目录不存在时自动创建。
     *
     * @return 数据根目录路径
     */
    // 对标 Python paths.py:20-22 get_data_dir()
    public static Path getDataDir() {
        return ensureDir(getConfigPath().getParent());
    }

    /**
     * 返回 data 目录下的命名子目录，目录不存在时自动创建。
     *
     * @param name 子目录名称（如 "media"、"cron"、"logs"）
     * @return 子目录路径
     */
    // 对标 Python paths.py:25-27 get_runtime_subdir()
    public static Path getRuntimeSubdir(String name) {
        return ensureDir(getDataDir().resolve(name));
    }

    /**
     * 返回媒体文件存储目录，可按 channel 分子目录。
     *
     * @param channel channel 名称，为 null 时返回 media 根目录
     * @return media 目录路径
     */
    // 对标 Python paths.py:30-33 get_media_dir()
    public static Path getMediaDir(String channel) {
        Path base = getRuntimeSubdir("media");
        return channel != null ? ensureDir(base.resolve(channel)) : base;
    }

    /**
     * 返回 cron 任务持久化目录。
     *
     * @return cron 目录路径
     */
    // 对标 Python paths.py:36-38 get_cron_dir()
    public static Path getCronDir() {
        return getRuntimeSubdir("cron");
    }

    /**
     * 返回日志文件目录。
     *
     * @return logs 目录路径
     */
    // 对标 Python paths.py:41-43 get_logs_dir()
    public static Path getLogsDir() {
        return getRuntimeSubdir("logs");
    }

    /**
     * 返回 Web UI 静态资源目录。
     *
     * @return webui 目录路径
     */
    // 对标 Python paths.py:46-48 get_webui_dir()
    public static Path getWebuiDir() {
        return getRuntimeSubdir("webui");
    }

    /**
     * 解析 workspace 路径，支持 ~ 展开为用户主目录。为 null 时使用默认路径
     * ~/.nanobot/workspace。目录不存在时自动创建。
     *
     * @param workspace workspace 路径字符串，可为 null
     * @return 展开并确保存在的 workspace 目录路径
     */
    // 对标 Python paths.py:51-54 get_workspace_path()
    public static Path getWorkspacePath(String workspace) {
        Path path;
        if (workspace != null) {
            String expanded = workspace.replaceFirst("^~", System.getProperty("user.home"));
            path = Path.of(expanded);
        } else {
            path = Path.of(System.getProperty("user.home"), ".nanobot", "workspace");
        }
        return ensureDir(path);
    }

    /**
     * 判断给定 workspace 是否与默认 workspace 为同一目录（解析符号链接后比较）。
     *
     * @param workspace workspace 路径字符串
     * @return true 表示与默认 workspace 相同
     */
    // 对标 Python paths.py:57-61 is_default_workspace()
    public static boolean isDefaultWorkspace(String workspace) {
        Path current = getWorkspacePath(workspace);
        Path defaultWs = getWorkspacePath(null);
        try {
            return current.toRealPath().equals(defaultWs.toRealPath());
        } catch (Exception e) {
            return current.toAbsolutePath().normalize()
                    .equals(defaultWs.toAbsolutePath().normalize());
        }
    }

    /**
     * 返回 CLI 历史记录文件路径（~/.nanobot/history/cli_history）。
     *
     * @return CLI 历史文件路径
     */
    // 对标 Python paths.py:64-66 get_cli_history_path()
    public static Path getCliHistoryPath() {
        return Path.of(System.getProperty("user.home"), ".nanobot", "history", "cli_history");
    }

    /**
     * 返回 bridge 安装目录路径（~/.nanobot/bridge）。
     *
     * @return bridge 安装目录路径
     */
    // 对标 Python paths.py:69-71 get_bridge_install_dir()
    public static Path getBridgeInstallDir() {
        return Path.of(System.getProperty("user.home"), ".nanobot", "bridge");
    }

    /**
     * 返回旧版 sessions 目录路径（~/.nanobot/sessions），用于迁移兼容。
     *
     * @return 旧版 sessions 目录路径
     */
    // 对标 Python paths.py:74-76 get_legacy_sessions_dir()
    public static Path getLegacySessionsDir() {
        return Path.of(System.getProperty("user.home"), ".nanobot", "sessions");
    }

    private static Path ensureDir(Path path) {
        try {
            Files.createDirectories(path);
        } catch (Exception ignored) {
        }
        return path;
    }
}
