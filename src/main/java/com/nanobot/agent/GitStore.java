package com.nanobot.agent;

import java.nio.file.Path;
import java.util.List;

/**
 * 工作区内存文件的 Git 版本控制（无外部依赖的存根——GitStore 主要被 Dream
 * 通过外部 git CLI 命令使用，此处仅提供 tracked_files 状态追踪）。
 *
 * <p>对标 Python {@code nanobot/utils/gitstore.py GitStore}。
 */
public class GitStore {

    private final Path workspace;
    private final List<String> trackedFiles;

    public GitStore(Path workspace, List<String> trackedFiles) {
        this.workspace = workspace;
        this.trackedFiles = List.copyOf(trackedFiles);
    }

    public Path workspace() { return workspace; }
    public List<String> trackedFiles() { return trackedFiles; }

    /** 对标 Python is_initialized()——检查 .git 目录是否存在。 */
    public boolean isInitialized() {
        return java.nio.file.Files.isDirectory(workspace.resolve(".git"));
    }
}
