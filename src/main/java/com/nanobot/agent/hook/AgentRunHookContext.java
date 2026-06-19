package com.nanobot.agent.hook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Run 级别的状态快照，暴露给 AgentHook 的 beforeRun/afterRun/onError/onFinally。
 * 对应 Python AgentRunHookContext dataclass（agent/hook.py）。
 */
public class AgentRunHookContext {

    /** 发送给 LLM 的消息列表 */
    public List<Map<String, Object>> messages;
    /** 最终回复内容 */
    public String finalContent;
    /** 使用的工具列表 */
    public List<String> toolsUsed = new ArrayList<>();
    /** token 用量统计 */
    public Map<String, Integer> usage = new LinkedHashMap<>();
    /** 停止原因 */
    public String stopReason;
    /** 错误信息 */
    public String error;
    /** 工具事件列表 */
    public List<Map<String, String>> toolEvents = new ArrayList<>();
    /** 是否有注入消息 */
    public boolean hadInjections;
    /** 异常对象 */
    public Throwable exception;

    public AgentRunHookContext() {}

    public AgentRunHookContext(List<Map<String, Object>> messages) {
        this.messages = messages;
    }

    public void setFinalContent(String finalContent) { this.finalContent = finalContent; }
    public void setStopReason(String stopReason) { this.stopReason = stopReason; }
    public void setError(String error) { this.error = error; }
    public void setException(Throwable exception) { this.exception = exception; }
    public void setHadInjections(boolean hadInjections) { this.hadInjections = hadInjections; }
}
