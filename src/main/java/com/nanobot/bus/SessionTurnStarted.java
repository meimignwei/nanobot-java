package com.nanobot.bus;

/**
 * 一个 user/system turn 已加载 session 并准备构建上下文。
 *
 * <p>对标 Python {@code nanobot/bus/runtime_events.py:32-36 @dataclass(frozen=True) class SessionTurnStarted}。
 */
public record SessionTurnStarted(
        RuntimeEventContext context) implements RuntimeEvent {
}
