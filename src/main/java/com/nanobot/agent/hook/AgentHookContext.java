package com.nanobot.agent.hook;

import com.nanobot.providers.base.LLMResponse;
import com.nanobot.providers.base.ToolCallRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每次 iteration 的可变状态，暴露给 AgentHook。
 * 对应 Python AgentHookContext dataclass（agent/hook.py）。
 */
public class AgentHookContext {

    /** 当前迭代轮次 */
    public int iteration;
    /** 发送给 LLM 的消息列表 */
    public List<Map<String, Object>> messages;
    /** LLM 响应 */
    public LLMResponse response;
    /** token 用量统计 */
    public Map<String, Integer> usage = new LinkedHashMap<>();
    /** LLM 请求的工具调用列表 */
    public List<ToolCallRequest> toolCalls = new ArrayList<>();
    /** 工具执行结果列表 */
    public List<Object> toolResults = new ArrayList<>();
    /** 工具事件列表 */
    public List<Map<String, String>> toolEvents = new ArrayList<>();
    /** 是否有流式内容 */
    public boolean streamedContent;
    /** 是否有流式推理 */
    public boolean streamedReasoning;
    /** 最终回复内容 */
    public String finalContent;
    /** 停止原因 */
    public String stopReason;
    /** 错误信息 */
    public String error;
    /** 会话 key */
    public String sessionKey;

    public AgentHookContext() {}

    public AgentHookContext(int iteration, List<Map<String, Object>> messages) {
        this.iteration = iteration;
        this.messages = messages;
    }

    public void setIteration(int iteration) { this.iteration = iteration; }
    public void setMessages(List<Map<String, Object>> messages) { this.messages = messages; }
    public void setSessionKey(String sessionKey) { this.sessionKey = sessionKey; }
}
