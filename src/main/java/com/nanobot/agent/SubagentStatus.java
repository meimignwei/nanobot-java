package com.nanobot.agent;

import java.util.List;
import java.util.Map;

/**
 * 子 agent 的实时状态数据对象。
 *
 * <p>对标 Python {@code nanobot/agent/subagent.py:32-45 SubagentStatus dataclass}。
 */
public class SubagentStatus {

    private final String taskId;
    private final String label;
    private final String taskDescription;
    private final long startedAt;  // System.nanoTime() 单调时钟

    private volatile String phase = "initializing";
    private volatile int iteration;
    private volatile List<Map<String, Object>> toolEvents = List.of();
    private volatile Map<String, Object> usage = Map.of();
    private volatile String stopReason;
    private volatile String error;

    public SubagentStatus(String taskId, String label, String taskDescription,
                          long startedAt, String phase, int iteration,
                          List<Map<String, Object>> toolEvents,
                          Map<String, Object> usage, String stopReason, String error) {
        this.taskId = taskId;
        this.label = label;
        this.taskDescription = taskDescription;
        this.startedAt = startedAt;
        this.phase = phase;
        this.iteration = iteration;
        this.toolEvents = toolEvents;
        this.usage = usage;
        this.stopReason = stopReason;
        this.error = error;
    }

    public String getTaskId() { return taskId; }
    public String getLabel() { return label; }
    public String getTaskDescription() { return taskDescription; }
    public long getStartedAt() { return startedAt; }

    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }

    public int getIteration() { return iteration; }
    public void setIteration(int iteration) { this.iteration = iteration; }

    public List<Map<String, Object>> getToolEvents() { return toolEvents; }
    public void setToolEvents(List<Map<String, Object>> toolEvents) { this.toolEvents = toolEvents; }

    public Map<String, Object> getUsage() { return usage; }
    public void setUsage(Map<String, Object> usage) { this.usage = usage; }

    public String getStopReason() { return stopReason; }
    public void setStopReason(String stopReason) { this.stopReason = stopReason; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
