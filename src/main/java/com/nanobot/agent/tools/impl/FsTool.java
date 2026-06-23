package com.nanobot.agent.tools.impl;

import com.nanobot.agent.tools.*;
import com.nanobot.agent.tools.path.PathUtils;

import java.nio.file.Path;
import java.util.List;

/**
 * 所有文件系统工具的共享基类，提供工作空间路径解析、
 * 允许目录强制限制和 FileStates 集成。
 *
 * <p>对标 Python {@code nanobot/agent/tools/filesystem.py _FsTool}。
 */
public abstract class FsTool extends Tool {

    protected final Path workspace;
    protected final Path allowedDir;
    protected final Path mediaDir;
    protected final List<Path> extraAllowedDirs;
    protected final boolean restrictToWorkspace;
    protected final boolean sandboxRestrictsWorkspace;
    protected final FileStates explicitFileStates;
    private final FileStates fallbackFileStates = new FileStates();

    /** FileStates 的 ThreadLocal 绑定，对标 Python file_state ContextVar。 */
    // 对标 Python bind_file_states() / reset_file_states()
    private static final ThreadLocal<FileStates> FILE_STATES_CONTEXT = new ThreadLocal<>();

    /**
     * 构造 FsTool，注入工作空间相关的路径和策略配置。
     *
     * @param workspace                 工作空间根路径
     * @param allowedDir                允许操作的主目录
     * @param mediaDir                  media 目录
     * @param extraAllowedDirs          额外允许的目录列表
     * @param fileStates                FileStates 实例（可为 null）
     * @param restrictToWorkspace       是否限制在工作空间内
     * @param sandboxRestrictsWorkspace sandbox 是否额外限制工作空间
     */
    // 对标 Python _FsTool.__init__()
    public FsTool(Path workspace, Path allowedDir, Path mediaDir,
                  List<Path> extraAllowedDirs, FileStates fileStates,
                  boolean restrictToWorkspace, boolean sandboxRestrictsWorkspace) {
        this.workspace = workspace;
        this.allowedDir = allowedDir;
        this.mediaDir = mediaDir;
        this.extraAllowedDirs = extraAllowedDirs;
        this.explicitFileStates = fileStates;
        this.restrictToWorkspace = restrictToWorkspace;
        this.sandboxRestrictsWorkspace = sandboxRestrictsWorkspace;
    }

    /**
     * 解析用户提供的路径，并强制限制在允许的目录内。
     *
     * @param path 用户输入路径
     * @return 解析后的绝对路径
     * @throws SecurityException 如果路径在允许目录之外
     */
    // 对标 Python _FsTool._resolve_path()
    protected Path resolve(String path) {
        return PathUtils.resolveWorkspacePath(
                path, workspace, allowedDir, mediaDir, extraAllowedDirs);
    }

    /**
     * 获取当前上下文的 FileStates，优先级：显式传入 > ThreadLocal > 本地回退。
     *
     * @return FileStates 实例
     */
    // 对标 Python _FsTool._file_states
    protected FileStates fileStates() {
        if (explicitFileStates != null) return explicitFileStates;
        FileStates bound = FILE_STATES_CONTEXT.get();
        return (bound != null) ? bound : fallbackFileStates;
    }

    /**
     * 将 FileStates 绑定到当前线程。
     *
     * @param fs FileStates 实例
     */
    // 对标 Python bind_file_states()
    public static void bindFileStates(FileStates fs) {
        FILE_STATES_CONTEXT.set(fs);
    }

    /** 解绑当前线程的 FileStates。 */
    // 对标 Python reset_file_states()
    public static void unbindFileStates() {
        FILE_STATES_CONTEXT.remove();
    }

    @Override
    public boolean isReadOnly() { return true; }
}
