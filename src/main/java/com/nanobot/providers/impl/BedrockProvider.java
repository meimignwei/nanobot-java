package com.nanobot.providers.impl;

import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.LLMResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * AWS Bedrock Converse API provider。
 *
 * <p>对标 Python {@code nanobot/providers/bedrock_provider.py BedrockProvider}（754 行）。
 * 使用 AWS Bedrock Converse API 调用模型。支持 api_key、api_base（endpoint_url）、
 * region、profile、extra_body 配置。
 *
 * <p>完整实现需要 AWS SDK for Java v2（software.amazon.awssdk:bedrockruntime）。
 * 当前为占位实现，待添加 Maven 依赖后完成。
 */
public class BedrockProvider extends LLMProvider {

    private static final Logger log = LoggerFactory.getLogger(BedrockProvider.class);

    private final String defaultModel;
    private final String region;
    private final String profile;

    /**
     * 构造 BedrockProvider。
     *
     * @param apiKey       AWS access key（可为 null，使用默认凭证链）
     * @param apiBase      Bedrock endpoint URL（可为 null）
     * @param defaultModel 默认模型名
     * @param region       AWS region
     * @param profile      AWS profile 名
     * @param extraBody    额外请求体字段
     */
    // 对标 Python BedrockProvider.__init__()
    public BedrockProvider(
            String apiKey,
            String apiBase,
            String defaultModel,
            String region,
            String profile,
            Map<String, Object> extraBody) {
        super(apiKey, apiBase);
        this.defaultModel = (defaultModel != null) ? defaultModel : "anthropic.claude-sonnet-4-20250514-v1:0";
        this.region = region;
        this.profile = profile;
        // extraBody 在完整实现中注入到 additionalModelRequestFields
    }

    @Override
    public String getDefaultModel() {
        return defaultModel;
    }

    /**
     * 发送 chat 请求到 Bedrock Converse API。
     *
     * <p>完整实现对标 Python BedrockProvider.chat()。
     *
     * @param messages        消息列表
     * @param tools           工具定义列表
     * @param model           模型标识
     * @param maxTokens       最大 token 数
     * @param temperature     温度
     * @param reasoningEffort reasoning 力度
     * @param toolChoice      工具选择
     * @return LLMResponse 的 CompletableFuture
     */
    @Override
    public CompletableFuture<LLMResponse> chat(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort,
            Object toolChoice) {
        return CompletableFuture.completedFuture(new LLMResponse(
                "BedrockProvider requires AWS SDK for Java v2. "
                        + "Add software.amazon.awssdk:bedrockruntime dependency.",
                "error"));
    }

    /**
     * 发送流式 chat 请求到 Bedrock ConverseStream API。
     *
     * <p>完整实现对标 Python BedrockProvider.chat_stream()。
     *
     * @param messages        消息列表
     * @param tools           工具定义列表
     * @param model           模型标识
     * @param maxTokens       最大 token 数
     * @param temperature     温度
     * @param reasoningEffort reasoning 力度
     * @param toolChoice      工具选择
     * @param onContentDelta  文本增量回调
     * @param onThinkingDelta thinking 增量回调
     * @param onToolCallDelta tool call 增量回调
     * @return LLMResponse 的 CompletableFuture
     */
    @Override
    public CompletableFuture<LLMResponse> chatStream(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort,
            Object toolChoice,
            ContentDeltaCallback onContentDelta,
            ContentDeltaCallback onThinkingDelta,
            ToolCallDeltaCallback onToolCallDelta) {
        return chat(messages, tools, model, maxTokens, temperature,
                reasoningEffort, toolChoice);
    }
}
