package com.nanobot.agent.loop;

import jakarta.annotation.Nullable;

/**
 * A single entry in the state machine trace.
 * Mirrors Python StateTraceEntry dataclass (loop.py lines 89-94).
 */
public class StateTraceEntry {

    private final TurnState state;
    private final double startedAt;
    private final double durationMs;
    private final String event;
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
