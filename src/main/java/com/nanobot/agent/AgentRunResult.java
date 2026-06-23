package com.nanobot.agent;

import java.util.List;
import java.util.Map;

/**
 * AgentRunner 执行结果，包含最终回复内容、完整消息历史、工具使用统计等信息。
 *
 * <p>对标 Python {@code nanobot/agent/runner.py} AgentRunResult 数据类。
 *
 * @param finalContent   最终回复文本
 * @param messages       完整消息列表
 * @param toolsUsed      使用的工具名称列表
 * @param usage          token 用量统计
 * @param stopReason     停止原因（completed/error/tool_error/max_iterations/cancelled/empty_final_response）
 * @param error          错误信息
 * @param toolEvents     工具执行事件列表
 * @param hadInjections  是否发生过消息注入
 */
public record AgentRunResult(
        String finalContent,
        List<Map<String, Object>> messages,
        List<String> toolsUsed,
        Map<String, Integer> usage,
        String stopReason,
        String error,
        List<Map<String, String>> toolEvents,
        boolean hadInjections) {

    public AgentRunResult {
        if (toolsUsed == null) toolsUsed = List.of();
        if (usage == null) usage = Map.of();
        if (toolEvents == null) toolEvents = List.of();
        if (stopReason == null) stopReason = "completed";
    }
}
