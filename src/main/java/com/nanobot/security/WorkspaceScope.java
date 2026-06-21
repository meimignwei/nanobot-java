package com.nanobot.security;

import java.nio.file.Path;

/**
 * 工作区作用域——指定工具可访问的工作区根路径和限制策略。
 * 对应 Python WorkspaceScope dataclass。
 */
public record WorkspaceScope(Path workspace, boolean restrictToWorkspace) {}

