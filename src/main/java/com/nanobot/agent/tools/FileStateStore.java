package com.nanobot.agent.tools;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级 FileStates 查找器。
 *
 * <p>对标 Python {@code nanobot/agent/tools/file_state.py} 中的 session 级别
 * ContextVar 机制。多个 session 共享同一进程，每个 session 拥有独立的 tracker，
 * 确保读去重和读前编辑警告限定在一个会话内。
 */
public class FileStateStore {

    private final ConcurrentHashMap<String, FileStates> statesByKey = new ConcurrentHashMap<>();

    /**
     * 获取指定 session key 对应的 FileStates，不存在则创建。
     *
     * @param sessionKey 会话 key（如 "telegram:12345"）
     * @return 该会话的 FileStates 实例
     */
    // 对标 Python file_state 中 per-session 的 ContextVar 绑定
    public FileStates forSession(String sessionKey) {
        String key = (sessionKey != null) ? sessionKey : "__default__";
        return statesByKey.computeIfAbsent(key, k -> new FileStates());
    }

    /** 清除所有会话的状态追踪。 */
    public void clear() {
        statesByKey.clear();
    }
}
