package com.nanobot.agent.hook;

import com.nanobot.providers.LLMResponse;
import com.nanobot.providers.ToolCallRequest;

import java.util.List;
import java.util.Map;

/**
 * 每次迭代的上下文状态，暴露给 runner hooks。
 * 对标 Python: {@code nanobot/agent/hook.py:13-29 @dataclass(slots=True) class AgentHookContext}
 *
 * <p>runner 会在迭代过程中原地修改此对象，因此使用 mutable POJO 而非 record。
 */
public class AgentHookContext {

    // 对标 Python hook.py:16 iteration
    private int iteration;

    // 对标 Python hook.py:17 messages
    private List<Map<String, Object>> messages;

    // 对标 Python hook.py:18 response
    private LLMResponse response;

    // 对标 Python hook.py:19 usage
    private Map<String, Integer> usage;

    // 对标 Python hook.py:20 tool_calls
    private List<ToolCallRequest> toolCalls;

    // 对标 Python hook.py:21 tool_results
    private List<Object> toolResults;

    // 对标 Python hook.py:22 tool_events
    private List<Map<String, String>> toolEvents;

    // 对标 Python hook.py:23 streamed_content
    private boolean streamedContent;

    // 对标 Python hook.py:24 streamed_reasoning
    private boolean streamedReasoning;

    // 对标 Python hook.py:25 final_content
    private String finalContent;

    // 对标 Python hook.py:26 stop_reason
    private String stopReason;

    // 对标 Python hook.py:27 error
    private String error;

    // 对标 Python hook.py:28 session_key
    private String sessionKey;

    public int getIteration() { return iteration; }
    public void setIteration(int iteration) { this.iteration = iteration; }

    public List<Map<String, Object>> getMessages() { return messages; }
    public void setMessages(List<Map<String, Object>> messages) { this.messages = messages; }

    public LLMResponse getResponse() { return response; }
    public void setResponse(LLMResponse response) { this.response = response; }

    public Map<String, Integer> getUsage() { return usage; }
    public void setUsage(Map<String, Integer> usage) { this.usage = usage; }

    public List<ToolCallRequest> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCallRequest> toolCalls) { this.toolCalls = toolCalls; }

    public List<Object> getToolResults() { return toolResults; }
    public void setToolResults(List<Object> toolResults) { this.toolResults = toolResults; }

    public List<Map<String, String>> getToolEvents() { return toolEvents; }
    public void setToolEvents(List<Map<String, String>> toolEvents) { this.toolEvents = toolEvents; }

    public boolean isStreamedContent() { return streamedContent; }
    public void setStreamedContent(boolean streamedContent) { this.streamedContent = streamedContent; }

    public boolean isStreamedReasoning() { return streamedReasoning; }
    public void setStreamedReasoning(boolean streamedReasoning) { this.streamedReasoning = streamedReasoning; }

    public String getFinalContent() { return finalContent; }
    public void setFinalContent(String finalContent) { this.finalContent = finalContent; }

    public String getStopReason() { return stopReason; }
    public void setStopReason(String stopReason) { this.stopReason = stopReason; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getSessionKey() { return sessionKey; }
    public void setSessionKey(String sessionKey) { this.sessionKey = sessionKey; }
}
