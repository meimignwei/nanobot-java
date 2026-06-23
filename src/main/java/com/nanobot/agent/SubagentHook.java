package com.nanobot.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanobot.agent.hook.AgentHook;
import com.nanobot.agent.hook.AgentHookContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 子 agent 执行过程中的 AgentHook——在每次工具调用和迭代完成时更新 {@link SubagentStatus}。
 *
 * <p>对标 Python {@code nanobot/agent/subagent.py:48-71 class _SubagentHook(AgentHook)}。
 */
public class SubagentHook implements AgentHook {

    private static final Logger log = LoggerFactory.getLogger(SubagentHook.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String taskId;
    private final SubagentStatus status;

    public SubagentHook(String taskId, SubagentStatus status) {
        this.taskId = taskId;
        this.status = status;
    }

    /**
     * 在每个工具调用前输出调试日志。
     * 对标 Python _SubagentHook.before_execute_tools(context)。
     */
    @Override
    public CompletableFuture<Void> beforeExecuteTools(AgentHookContext context) {
        if (context.getToolCalls() != null) {
            for (var toolCall : context.getToolCalls()) {
                try {
                    String argsStr = MAPPER.writeValueAsString(toolCall.arguments());
                    log.debug("Subagent [{}] executing: {} with arguments: {}",
                            taskId, toolCall.name(), argsStr);
                } catch (JsonProcessingException e) {
                    log.debug("Subagent [{}] executing: {}", taskId, toolCall.name());
                }
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 每次迭代完成后更新 status 的 iteration/toolEvents/usage/error。
     * 对标 Python _SubagentHook.after_iteration(context)。
     */
    @Override
    public CompletableFuture<Void> afterIteration(AgentHookContext context) {
        if (status == null) return CompletableFuture.completedFuture(null);
        status.setIteration(context.getIteration());
        if (context.getToolEvents() != null) {
            List<Map<String, Object>> events = new ArrayList<>();
            for (Map<String, String> e : context.getToolEvents()) {
                events.add(new LinkedHashMap<>(e));
            }
            status.setToolEvents(events);
        }
        if (context.getUsage() != null) {
            status.setUsage(new LinkedHashMap<>(context.getUsage()));
        }
        if (context.getError() != null) {
            status.setError(context.getError());
        }
        return CompletableFuture.completedFuture(null);
    }
}
