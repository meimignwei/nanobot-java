package com.nanobot.bus;

/**
 * 一个 turn 已完成最终用户可见的响应。
 *
 * <p>对标 Python {@code nanobot/bus/runtime_events.py:48-54 @dataclass(frozen=True) class TurnCompleted}。
 */
public record TurnCompleted(
        RuntimeEventContext context,
        Integer latencyMs,
        Object runtime) implements RuntimeEvent {
}
