package com.nanobot.agent.runner;

import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Agent run 的返回结果。
 * 对应 Python AgentRunResult dataclass（runner.py 行 116-127）。
 */
public record AgentRunResult(
        /** 最终回复文本内容 */
        @Nullable String finalContent,
        /** 完整消息列表 */
        List<Map<String, Object>> messages,
        /** 使用的工具名称列表 */
        List<String> toolsUsed,
        /** token 用量统计（prompt_tokens, completion_tokens, total_tokens） */
        Map<String, Integer> usage,
        /** 停止原因（如 "stop"、"max_iterations"、"error"） */
        String stopReason,
        /** 错误信息 */
        @Nullable String error,
        /** 工具事件列表 */
        List<Map<String, String>> toolEvents,
        /** 是否有注入消息 */
        boolean hadInjections
) {
    public AgentRunResult {
        if (messages == null) messages = List.of();
        if (toolsUsed == null) toolsUsed = List.of();
        if (usage == null) usage = Map.of();
        if (stopReason == null) stopReason = "completed";
        if (toolEvents == null) toolEvents = List.of();
    }
}
