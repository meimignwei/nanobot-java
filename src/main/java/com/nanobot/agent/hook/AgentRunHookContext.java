package com.nanobot.agent.hook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Run-level state snapshot exposed to runner hooks.
 * Mirrors Python AgentRunHookContext dataclass.
 */
public class AgentRunHookContext {

    public List<Map<String, Object>> messages;
    public String finalContent;
    public List<String> toolsUsed = new ArrayList<>();
    public Map<String, Integer> usage = new LinkedHashMap<>();
    public String stopReason;
    public String error;
    public List<Map<String, String>> toolEvents = new ArrayList<>();
    public boolean hadInjections;
    public Throwable exception;

    public AgentRunHookContext(List<Map<String, Object>> messages) {
        this.messages = messages;
    }
}
