package com.nanobot.bus;

import java.util.Collections;
import java.util.Map;

/**
 * session 的 sustained-goal 状态发生变化。
 *
 * <p>对标 Python {@code nanobot/bus/runtime_events.py:57-62 @dataclass(frozen=True) class GoalStateChanged}。
 */
public record GoalStateChanged(
        RuntimeEventContext context,
        Map<String, Object> sessionMetadata) implements RuntimeEvent {

    public GoalStateChanged {
        if (sessionMetadata == null) sessionMetadata = Collections.emptyMap();
    }
}
