package com.nanobot.security;

import java.nio.file.Path;

/**
 * 工作区隔离 scope——指定子 agent 执行时的工作目录与隔离策略。
 *
 * <p>对标 Python: nanobot/security/workspace_access 中的 workspace scope 概念。
 */
public class WorkspaceScope {

    private final Path projectPath;
    private final boolean restrictToWorkspace;

    public WorkspaceScope(Path projectPath, boolean restrictToWorkspace) {
        this.projectPath = projectPath;
        this.restrictToWorkspace = restrictToWorkspace;
    }

    public Path getProjectPath() { return projectPath; }
    public boolean isRestrictToWorkspace() { return restrictToWorkspace; }
}
