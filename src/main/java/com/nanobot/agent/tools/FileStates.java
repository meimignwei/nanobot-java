package com.nanobot.agent.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级文件读写状态追踪器，用于读去重和读前编辑警告。
 *
 * <p>对标 Python {@code nanobot/agent/tools/file_state.py}。
 *
 * <p>功能：
 * <ul>
 *   <li>读去重：同一文件 + 相同 offset/limit + 内容哈希不变时可跳过重复读取</li>
 *   <li>读前编辑警告：文件自上次读取后被外部修改时告警</li>
 * </ul>
 */
public class FileStates {

    /** 单次读取的状态快照。 */
    // 对标 Python file_state 中的状态记录
    public record ReadState(
            double mtime,
            int offset,
            Integer limit,
            String contentHash,
            boolean canDedup
    ) {}

    private final ConcurrentHashMap<String, ReadState> state = new ConcurrentHashMap<>();

    /**
     * 记录一次成功读取，保存文件修改时间、偏移量、限制和内容哈希。
     *
     * @param path   文件路径
     * @param offset 读取起始行
     * @param limit  读取行数限制（可为 null）
     */
    // 对标 Python record_read()
    public void recordRead(Path path, int offset, Integer limit) {
        String key = path.toAbsolutePath().normalize().toString();
        try {
            double mtime = Files.getLastModifiedTime(path).toMillis() / 1000.0;
            String hash = sha256Hex(path);
            state.put(key, new ReadState(mtime, offset, limit, hash, true));
        } catch (IOException e) {
            // 文件在读取和记录之间消失，跳过
        }
    }

    /**
     * 记录一次成功写入，更新修改时间并重置去重标记。
     *
     * @param path 文件路径
     */
    // 对标 Python record_write()
    public void recordWrite(Path path) {
        String key = path.toAbsolutePath().normalize().toString();
        try {
            double mtime = Files.getLastModifiedTime(path).toMillis() / 1000.0;
            String hash = sha256Hex(path);
            state.put(key, new ReadState(mtime, 1, null, hash, false));
        } catch (IOException e) {
            state.remove(key);
        }
    }

    /**
     * 检查文件自上次读取后是否仍为最新。
     * 返回 null 表示正常，否则返回警告字符串。
     *
     * @param path 文件路径
     * @return null（正常）或警告消息
     */
    // 对标 Python check_read()
    public String checkRead(Path path) {
        String key = path.toAbsolutePath().normalize().toString();
        ReadState entry = state.get(key);
        if (entry == null) {
            return "Warning: file has not been read yet. "
                    + "Read it first to verify content before editing.";
        }
        try {
            double currentMtime = Files.getLastModifiedTime(path).toMillis() / 1000.0;
            if (currentMtime != entry.mtime()) {
                String currentHash = sha256Hex(path);
                if (entry.contentHash() != null && currentHash.equals(entry.contentHash())) {
                    state.put(key, new ReadState(currentMtime, entry.offset(),
                            entry.limit(), entry.contentHash(), entry.canDedup()));
                    return null;
                }
                return "Warning: file has been modified since last read. "
                        + "Re-read to verify content before editing.";
            }
            if (entry.contentHash() != null) {
                String currentHash = sha256Hex(path);
                if (!currentHash.equals(entry.contentHash())) {
                    return "Warning: file has been modified since last read. "
                            + "Re-read to verify content before editing.";
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 获取文件路径对应的 ReadState。
     *
     * @param path 文件路径
     * @return ReadState，不存在返回 null
     */
    public ReadState get(Path path) {
        return state.get(path.toAbsolutePath().normalize().toString());
    }

    /** 清除所有追踪状态。 */
    public void clear() {
        state.clear();
    }

    /**
     * 检查文件自上次读取后（相同 offset/limit）是否内容不变，可用于跳过去重。
     *
     * @param path   文件路径
     * @param offset 读取起始行
     * @param limit  读取行数限制（可为 null）
     * @return 内容未变且参数相同返回 true
     */
    // 对标 Python is_unchanged()
    public boolean isUnchanged(Path path, int offset, Integer limit) {
        ReadState entry = get(path);
        if (entry == null || !entry.canDedup()) return false;
        if (entry.offset() != offset) return false;
        if (!Objects.equals(entry.limit(), limit)) return false;
        try {
            double currentMtime = Files.getLastModifiedTime(path).toMillis() / 1000.0;
            if (currentMtime != entry.mtime()) {
                // 对标 Python: mtime 变化时先检查内容哈希
                String currentHash = sha256Hex(path);
                String key = path.toAbsolutePath().normalize().toString();
                if (currentHash != null && !currentHash.equals(entry.contentHash())) {
                    // 内容确实变了，不进行去重
                    state.put(key, new ReadState(currentMtime, entry.offset(),
                            entry.limit(), entry.contentHash(), false));
                    return false;
                }
                // 内容相同（如 touch 操作），标记为不可去重但仍返回 true
                state.put(key, new ReadState(currentMtime, entry.offset(),
                        entry.limit(), entry.contentHash(), false));
                return true;
            }
            // 对标 Python: mtime 未变，内容必定相同
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 计算文件的 SHA-256 哈希（十六进制字符串）。
     *
     * @param path 文件路径
     * @return 哈希字符串，失败返回 null
     */
    public static String sha256Hex(Path path) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(Files.readAllBytes(path));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }
}
