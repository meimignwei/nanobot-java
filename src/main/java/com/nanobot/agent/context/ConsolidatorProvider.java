package com.nanobot.agent.context;

import com.nanobot.providers.base.LLMResponse;

import java.util.List;
import java.util.Map;

/**
 * Minimal provider abstraction needed by the Consolidator for LLM summarization.
 */
public interface ConsolidatorProvider {

    LLMResponse chat(String model, List<Map<String, Object>> messages) throws Exception;

    default int estimatePromptTokens(List<Map<String, Object>> messages,
                                     List<Map<String, Object>> toolDefs) {
        int total = 0;
        for (var msg : messages) {
            total += com.nanobot.agent.session.Session.estimateMessageTokens(msg);
        }
        return Math.max(1, total);
    }
}
