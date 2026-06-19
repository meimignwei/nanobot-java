package com.nanobot.agent.runner;

import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Result from an agent run. Mirrors Python AgentRunResult dataclass
 * (runner.py lines 116-127).
 */
public record AgentRunResult(
        @Nullable String finalContent,
        List<Map<String, Object>> messages,
        List<String> toolsUsed,
        Map<String, Integer> usage,
        String stopReason,
        @Nullable String error,
        List<Map<String, String>> toolEvents,
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
