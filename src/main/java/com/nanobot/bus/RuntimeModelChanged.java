package com.nanobot.bus;

/**
 * 活动运行时模型/preset 发生变化。
 *
 * <p>对标 Python {@code nanobot/bus/runtime_events.py:65-70 @dataclass(frozen=True) class RuntimeModelChanged}。
 */
public record RuntimeModelChanged(
        String model,
        String modelPreset) implements RuntimeEvent {
}
