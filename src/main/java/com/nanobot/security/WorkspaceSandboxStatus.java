package com.nanobot.security;

/**
 * 工作区沙箱状态快照，供工具上下文消费。
 * 对应 Python WorkspaceSandboxStatus。
 */
public record WorkspaceSandboxStatus(String workspace, boolean restrictToWorkspace) {
    public boolean isRestricted() { return restrictToWorkspace; }
}
