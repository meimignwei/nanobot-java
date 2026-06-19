package com.nanobot.agent.loop;

import jakarta.annotation.Nullable;

/**
 * 状态机追踪记录，记录每个状态的耗时和事件。
 * 对应 Python StateTraceEntry dataclass（loop.py 行 89-94）。
 */
public class StateTraceEntry {

    /** 当前状态 */
    private final TurnState state;
    /** 状态进入时间（epoch 秒） */
    private final double startedAt;
    /** 状态持续时间（毫秒） */
    private final double durationMs;
    /** 事件描述 */
    private final String event;
    /** 错误信息（可选） */
    @Nullable
    private final String error;

    public StateTraceEntry(TurnState state, double startedAt, double durationMs,
                           String event, @Nullable String error) {
        this.state = state;
        this.startedAt = startedAt;
        this.durationMs = durationMs;
        this.event = event;
        this.error = error;
    }

    public TurnState state() { return state; }
    public double startedAt() { return startedAt; }
    public double durationMs() { return durationMs; }
    public String event() { return event; }
    @Nullable
    public String error() { return error; }
}
