package com.nanobot.bus;

/**
 * 所有运行时事件的标记接口，使用 Java {@code sealed interface} 对标 Python 的 {@code Union} 类型。
 *
 * <p>对标 Python {@code nanobot/bus/runtime_events.py:73-79 RuntimeEvent = SessionTurnStarted | TurnRunStatusChanged | ...}。
 */
public sealed interface RuntimeEvent
        permits SessionTurnStarted,
                TurnRunStatusChanged,
                TurnCompleted,
                GoalStateChanged,
                RuntimeModelChanged {
}
