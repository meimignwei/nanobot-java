package com.nanobot.agent.context;

import com.nanobot.providers.base.LLMResponse;

import java.util.List;
import java.util.Map;

/**
 * Consolidator 使用的轻量 LLM 提供者抽象，用于压缩/摘要。
 * 对应 Python Consolidator._call_llm() 中使用的 provider 接口。
 */
public interface ConsolidatorProvider {

    /** 调用 LLM 聊天，返回响应。
     *  对应 Python Consolidator._call_llm()。 */
    LLMResponse chat(String model, List<Map<String, Object>> messages) throws Exception;

    /** 估算 prompt token 数，默认使用 Session.estimateMessageTokens 逐条估算。
     *  对应 Python provider.count_tokens_for_messages() 的路由逻辑。 */
    default int estimatePromptTokens(List<Map<String, Object>> messages,
                                     List<Map<String, Object>> toolDefs) {
        int total = 0;
        for (var msg : messages) {
            total += com.nanobot.agent.session.Session.estimateMessageTokens(msg);
        }
        return Math.max(1, total);
    }
}
