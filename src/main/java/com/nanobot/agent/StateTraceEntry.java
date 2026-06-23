package com.nanobot.agent;

/**
 * 记录每个状态阶段的开始时间、耗时和触发事件，用于性能追踪。
 *
 * <p>对标 Python {@code nanobot/agent/loop.py} StateTraceEntry 数据类。
 *
 * @param state          当前状态
 * @param startedAtNanos 开始时刻（System.nanoTime()）
 * @param durationMs     耗时毫秒
 * @param event          触发事件名
 * @param error          错误信息（可为 null）
 */
public record StateTraceEntry(
        TurnState state,
        long startedAtNanos,
        long durationMs,
        String event,
        String error) {

    /** 无错误信息的便捷构造器。 */
    public StateTraceEntry(TurnState state, long startedAtNanos, long durationMs, String event) {
        this(state, startedAtNanos, durationMs, event, null);
    }
}
