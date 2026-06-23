package com.nanobot.bus;

/**
 * turn 的可见运行状态发生变化。
 *
 * <p>对标 Python {@code nanobot/bus/runtime_events.py:39-45 @dataclass(frozen=True) class TurnRunStatusChanged}。
 */
public record TurnRunStatusChanged(
        RuntimeEventContext context,
        String status,
        Double startedAt) implements RuntimeEvent {
}
