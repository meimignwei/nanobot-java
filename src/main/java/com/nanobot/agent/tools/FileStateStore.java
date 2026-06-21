package com.nanobot.agent.tools;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 session 分区的文件读写状态查找表。
 * 对应 Python FileStateStore（agent/tools/file_state.py:133-151）。
 *
 * <p>每个 session 跟踪自己的 FileStates（已读文件、已编辑文件集合），
 * 用于去重通知和 diff 展示。</p>
 */
public class FileStateStore {

    private final Map<String, FileStates> statesByKey = new ConcurrentHashMap<>();

    /** 获取或创建指定 session 的 FileStates。
     *  对应 Python FileStateStore.for_session()。 */
    public FileStates forSession(String sessionKey) {
        var key = sessionKey != null ? sessionKey : "__default__";
        return statesByKey.computeIfAbsent(key, k -> new FileStates());
    }

    /** 清空全部状态。
     *  对应 Python FileStateStore.clear()。 */
    public void clear() {
        statesByKey.clear();
    }

    /**
     * 单个 session 的文件状态：已读/已编辑文件集合。
     * 对应 Python FileStates。
     */
    public static class FileStates {
        public final java.util.Set<String> readFiles = ConcurrentHashMap.newKeySet();
        public final java.util.Set<String> editedFiles = ConcurrentHashMap.newKeySet();

        public void markRead(String path) { readFiles.add(path); }
        public void markEdited(String path) { editedFiles.add(path); }
        public boolean isRead(String path) { return readFiles.contains(path); }
        public boolean isEdited(String path) { return editedFiles.contains(path); }
    }
}
