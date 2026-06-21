package com.nanobot.agent.subagent;

import jakarta.annotation.Nullable;

/**
 * 子代理任务状态快照。
 * 对应 Python SubagentStatus dataclass（agent/subagent.py）。
 *
 * <p>SubagentManager 使用此对象追踪每个后台子代理任务的
 * 生命周期（running → completed / error）。</p>
 */
public class SubagentStatus {
    /** 任务唯一标识（UUID 前 8 位） */
    public String taskId;
    /** 显示标签（截断后的任务描述） */
    public String label;
    /** 完整任务描述 */
    public String taskDescription;
    /** 任务开始时间（System.nanoTime()） */
    public long startedAt;
    /** 任务完成时间 */
    @Nullable public Long completedAt;
    /** 当前状态：running / completed / error */
    public String state = "running";
    /** 错误消息（仅 error 状态） */
    @Nullable public String errorMessage;

    public SubagentStatus() {}

    public SubagentStatus(String taskId, String label, String taskDescription, long startedAt) {
        this.taskId = taskId;
        this.label = label;
        this.taskDescription = taskDescription;
        this.startedAt = startedAt;
    }
}
