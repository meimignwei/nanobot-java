package com.nanobot.bus;

import java.util.Collections;
import java.util.Map;

/**
 * turn 级运行时事件的路由上下文，包含 channel、chat、session 标识和 metadata。
 *
 * <p>对标 Python {@code nanobot/bus/runtime_events.py:22-29 @dataclass(frozen=True) class RuntimeEventContext}。
 */
public record RuntimeEventContext(
        String channel,
        String chatId,
        String sessionKey,
        Map<String, Object> metadata) {

    public RuntimeEventContext {
        if (metadata == null) metadata = Collections.emptyMap();
    }
}
