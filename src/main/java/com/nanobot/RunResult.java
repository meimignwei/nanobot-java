package com.nanobot;

import java.util.List;
import java.util.Map;

/**
 * 单次 agent 运行结果。
 * 对标 Python: {@code nanobot/nanobot.py:14-20 @dataclass(slots=True) class RunResult}
 */
public record RunResult(
        String content,
        List<String> toolsUsed,
        List<Map<String, Object>> messages) {
}
