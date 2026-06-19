package com.nanobot.agent.hook;

import com.nanobot.providers.base.LLMResponse;
import com.nanobot.providers.base.ToolCallRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable per-iteration state exposed to runner hooks.
 * Mirrors Python AgentHookContext dataclass.
 */
public class AgentHookContext {

    public int iteration;
    public List<Map<String, Object>> messages;
    public LLMResponse response;
    public Map<String, Integer> usage = new LinkedHashMap<>();
    public List<ToolCallRequest> toolCalls = new ArrayList<>();
    public List<Object> toolResults = new ArrayList<>();
    public List<Map<String, String>> toolEvents = new ArrayList<>();
    public boolean streamedContent;
    public boolean streamedReasoning;
    public String finalContent;
    public String stopReason;
    public String error;
    public String sessionKey;

    public AgentHookContext(int iteration, List<Map<String, Object>> messages) {
        this.iteration = iteration;
        this.messages = messages;
    }
}
