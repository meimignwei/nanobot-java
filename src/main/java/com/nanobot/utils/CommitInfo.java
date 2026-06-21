package com.nanobot.utils;

/**
 * Git 提交信息快照。
 * 对应 Python CommitInfo dataclass（utils/gitstore.py:14-25）。
 */
public record CommitInfo(String sha, String message, String timestamp) {

    /** 格式化提交信息，可选带 diff。
     *  对应 Python CommitInfo.format()。 */
    public String format(String diff) {
        var header = "## " + message.split("\n")[0] + "\n`" + sha + "` — " + timestamp + "\n";
        if (diff != null && !diff.isEmpty()) {
            return header + "\n```diff\n" + diff + "\n```";
        }
        return header + "\n(no file changes)";
    }
}
