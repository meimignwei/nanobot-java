package com.nanobot.security;

import com.nanobot.bus.InboundMessage;
import jakarta.annotation.Nullable;

import java.nio.file.Path;
import java.util.Map;

/**
 * 在 agent turn 边界解析有效的工作区作用域。
 * 对应 Python WorkspaceScopeResolver（security/workspace_access.py:110-164）。
 *
 * <p>核心职责：根据渠道和消息元数据决定是否限制工具对工作区外部的访问，
 * 并为子代理提供沙箱状态。</p>
 */
public class WorkspaceScopeResolver {

    private final Path defaultWorkspace;
    private final boolean defaultRestrictToWorkspace;

    public WorkspaceScopeResolver(Path defaultWorkspace, boolean defaultRestrictToWorkspace) {
        this.defaultWorkspace = defaultWorkspace;
        this.defaultRestrictToWorkspace = defaultRestrictToWorkspace;
    }

    /** 沙箱状态访问器。
     *  对应 Python WorkspaceScopeResolver.sandbox_status property。 */
    public WorkspaceSandboxStatus sandboxStatus() {
        return new WorkspaceSandboxStatus(defaultWorkspace.toString(), defaultRestrictToWorkspace);
    }

    /** 默认工作区作用域。
     *  对应 Python WorkspaceScopeResolver.default()。 */
    public WorkspaceScope defaultScope() {
        return new WorkspaceScope(defaultWorkspace, defaultRestrictToWorkspace);
    }

    /** 根据消息解析作用域（委托给 forTurn）。
     *  对应 Python WorkspaceScopeResolver.for_message()。 */
    public WorkspaceScope forMessage(InboundMessage msg, @Nullable Map<String, Object> sessionMetadata) {
        return forTurn(
                msg != null ? msg.channel() : null,
                msg != null ? msg.metadata() : null,
                sessionMetadata);
    }

    /** 根据渠道和元数据解析有效作用域。
     *  对应 Python WorkspaceScopeResolver.for_turn()。 */
    public WorkspaceScope forTurn(@Nullable String channel,
                                   @Nullable Map<String, Object> messageMetadata,
                                   @Nullable Map<String, Object> sessionMetadata) {
        // 非 websocket 渠道使用默认作用域（无限制）
        if (!"websocket".equals(channel)) {
            return defaultScope();
        }
        // websocket 渠道根据元数据中的 workspace_scope 字段解析
        return resolveEffectiveScope(messageMetadata, sessionMetadata);
    }

    /** 从消息/会话元数据中解析有效作用域。
     *  对应 Python resolve_effective_workspace_scope()。 */
    private WorkspaceScope resolveEffectiveScope(
            @Nullable Map<String, Object> messageMetadata,
            @Nullable Map<String, Object> sessionMetadata) {
        // 先查消息级元数据
        var scopeMeta = getScopeMeta(messageMetadata);
        if (scopeMeta == null) scopeMeta = getScopeMeta(sessionMetadata);
        if (scopeMeta instanceof Map<?, ?> sm) {
            var ws = sm.get("workspace");
            var restrict = sm.get("restrict_to_workspace");
            if (ws instanceof String wsStr) {
                return new WorkspaceScope(
                        Path.of(wsStr),
                        restrict instanceof Boolean b ? b : defaultRestrictToWorkspace);
            }
        }
        return defaultScope();
    }

    private static final String WORKSPACE_SCOPE_KEY = "_workspace_scope";

    private static Object getScopeMeta(@Nullable Map<String, Object> metadata) {
        if (metadata == null) return null;
        return metadata.get(WORKSPACE_SCOPE_KEY);
    }

    // -- 持久化消息级作用域到 session --
    // 对应 Python WorkspaceScopeResolver.persist_message_scope()

    /** 将消息中的工作区作用域元数据持久化到 session。
     *  对应 Python WorkspaceScopeResolver.persist_message_scope()。 */
    public void persistMessageScope(Map<String, Object> sessionMetadata, InboundMessage msg) {
        if (msg == null || !"websocket".equals(msg.channel())) return;
        var metadata = msg.metadata();
        if (metadata == null) return;
        var raw = metadata.get(WORKSPACE_SCOPE_KEY);
        if (raw instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            var scopeMap = (Map<String, Object>) m;
            sessionMetadata.put(WORKSPACE_SCOPE_KEY, Map.copyOf(scopeMap));
        }
    }
}
