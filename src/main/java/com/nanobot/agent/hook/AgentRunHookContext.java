package com.nanobot.agent.hook;

import java.util.List;
import java.util.Map;

/**
 * Run 级别的状态快照，暴露给 runner hooks。
 * 对标 Python: {@code nanobot/agent/hook.py:32-44 @dataclass(slots=True) class AgentRunHookContext}
 */
public class AgentRunHookContext {

    // 对标 Python hook.py:35 messages
    private List<Map<String, Object>> messages;

    // 对标 Python hook.py:36 final_content
    private String finalContent;

    // 对标 Python hook.py:37 tools_used
    private List<String> toolsUsed;

    // 对标 Python hook.py:38 usage
    private Map<String, Integer> usage;

    // 对标 Python hook.py:39 stop_reason
    private String stopReason;

    // 对标 Python hook.py:40 error
    private String error;

    // 对标 Python hook.py:41 tool_events
    private List<Map<String, String>> toolEvents;

    // 对标 Python hook.py:42 had_injections
    private boolean hadInjections;

    // 对标 Python hook.py:43 exception
    private Throwable exception;

    public List<Map<String, Object>> getMessages() { return messages; }
    public void setMessages(List<Map<String, Object>> messages) { this.messages = messages; }

    public String getFinalContent() { return finalContent; }
    public void setFinalContent(String finalContent) { this.finalContent = finalContent; }

    public List<String> getToolsUsed() { return toolsUsed; }
    public void setToolsUsed(List<String> toolsUsed) { this.toolsUsed = toolsUsed; }

    public Map<String, Integer> getUsage() { return usage; }
    public void setUsage(Map<String, Integer> usage) { this.usage = usage; }

    public String getStopReason() { return stopReason; }
    public void setStopReason(String stopReason) { this.stopReason = stopReason; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public List<Map<String, String>> getToolEvents() { return toolEvents; }
    public void setToolEvents(List<Map<String, String>> toolEvents) { this.toolEvents = toolEvents; }

    public boolean isHadInjections() { return hadInjections; }
    public void setHadInjections(boolean hadInjections) { this.hadInjections = hadInjections; }

    public Throwable getException() { return exception; }
    public void setException(Throwable exception) { this.exception = exception; }
}
