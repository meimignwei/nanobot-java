package com.nanobot.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runtime path helpers — mirrors nanobot/config/paths.py exactly.
 *
 * All paths are derived from the active config directory. The "data dir" is
 * the parent of the active config file (e.g. ~/.nanobot-java/).
 */
public final class PathUtils {

    private static final Path HOME = Path.of(System.getProperty("user.home"));
    static final Path DEFAULT_DATA_DIR = HOME.resolve(".nanobot-java");

    private PathUtils() {}

    public static Path getConfigPath() {
        return ConfigLoader.getConfigPath();
    }

    public static Path getDataDir() {
        return ensureDir(getConfigPath().getParent());
    }

    public static Path getRuntimeSubdir(String name) {
        return ensureDir(getDataDir().resolve(name));
    }

    public static Path getMediaDir(String channel) {
        Path base = getRuntimeSubdir("media");
        return channel != null ? ensureDir(base.resolve(channel)) : base;
    }

    public static Path getCronDir() {
        return getRuntimeSubdir("cron");
    }

    public static Path getLogsDir() {
        return getRuntimeSubdir("logs");
    }

    public static Path getWebuiDir() {
        return getRuntimeSubdir("webui");
    }

    public static Path getWorkspacePath(String workspace) {
        Path path = workspace != null
                ? resolveHome(Path.of(workspace))
                : HOME.resolve(".nanobot-java/workspace");
        return ensureDir(path);
    }

    public static boolean isDefaultWorkspace(String workspace) {
        Path current = workspace != null ? resolveHome(Path.of(workspace)) : HOME.resolve(".nanobot-java/workspace");
        Path defaultPath = HOME.resolve(".nanobot-java/workspace");
        return current.toAbsolutePath().normalize().equals(defaultPath.toAbsolutePath().normalize());
    }

    public static Path getCliHistoryPath() {
        return HOME.resolve(".nanobot-java/history/cli_history");
    }

    public static Path getBridgeInstallDir() {
        return HOME.resolve(".nanobot-java/bridge");
    }

    public static Path getLegacySessionsDir() {
        return HOME.resolve(".nanobot-java/sessions");
    }

    // ---- internal helpers ----

    static Path resolveHome(Path path) {
        if (path.startsWith("~")) {
            return HOME.resolve(path.toString().substring(1).replaceFirst("^/+", ""));
        }
        return path;
    }

    static Path ensureDir(Path dir) {
        try {
            return Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create directory: " + dir, e);
        }
    }
}
