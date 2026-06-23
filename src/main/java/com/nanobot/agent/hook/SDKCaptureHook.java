package com.nanobot.agent.hook;

import com.nanobot.providers.ToolCallRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 在 agent run 过程中收集工具调用名称和消息列表，供 {@code RunResult} 使用。
 *
 * <p>runner 在迭代过程中原地修改 context.messages，因此每次 afterIteration 都会刷新快照。
 * 最后一次迭代的回调反映调用方关心的回合结束状态。afterRun 提供的 tools_used 是运行器
 * 汇总后的权威列表，可覆盖逐迭代累积的数据。
 *
 * <p>对标 Python {@code nanobot/agent/hook.py:165-187 class SDKCaptureHook}。
 */
public class SDKCaptureHook implements AgentHook {

    /** 已使用的工具名称列表（按调用顺序），对标 Python hook.py:176 */
    private List<String> toolsUsed = new ArrayList<>();

    /** 当前消息快照，对标 Python hook.py:177 */
    private List<Map<String, Object>> messages = new ArrayList<>();

    /**
     * 每次迭代后从 context 中提取工具调用名称并刷新消息快照。
     *
     * @param context 当前迭代上下文，含 tool_calls 列表和 messages 列表
     */
    // 对标 Python hook.py:180-183 after_iteration()
    @Override
    public CompletableFuture<Void> afterIteration(AgentHookContext context) {
        for (ToolCallRequest call : context.getToolCalls()) {
            toolsUsed.add(call.name());
        }
        messages = new ArrayList<>(context.getMessages());
        return CompletableFuture.completedFuture(null);
    }

    /**
     * run 结束后使用 runner 汇总的权威数据覆盖逐迭代收集的数据。
     *
     * @param context run-level 上下文，含汇总后的 tools_used 和 messages
     */
    // 对标 Python hook.py:185-187 after_run()
    @Override
    public CompletableFuture<Void> afterRun(AgentRunHookContext context) {
        toolsUsed = new ArrayList<>(context.getToolsUsed());
        messages = new ArrayList<>(context.getMessages());
        return CompletableFuture.completedFuture(null);
    }

    /** @return 本次 run 中使用的所有工具名称列表 */
    public List<String> getToolsUsed() {
        return toolsUsed;
    }

    /** @return 本次 run 结束时的消息列表快照 */
    public List<Map<String, Object>> getMessages() {
        return messages;
    }
}
