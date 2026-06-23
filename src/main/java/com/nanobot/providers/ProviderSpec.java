package com.nanobot.providers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 单个 LLM provider 的不可变元数据，包含标识、后端、网关/本地探测、认证模式及高级特性等 30+ 字段。
 *
 * <p>对标 Python {@code nanobot/providers/registry.py ProviderSpec frozen dataclass}（30+ 字段）。
 * 使用 Builder 模式保证 30+ 字段 record 的可读性，同时保持不可变性。
 */
public record ProviderSpec(
        // —— 标识 ——
        /** provider 配置字段名，如 "dashscope" */
        String name,
        /** 模型名匹配关键词（小写），如 ("qwen", "dashscope") */
        List<String> keywords,
        /** API key 环境变量名，如 "DASHSCOPE_API_KEY" */
        String envKey,
        /** 状态展示名，如 "DashScope" */
        String displayName,

        // —— 后端选择 ——
        /** "openai_compat" | "anthropic" | "azure_openai" | "openai_codex" | "github_copilot" | "bedrock" */
        String backend,

        // —— 额外环境变量（env_var_name, value_template_with_{api_key}） ——
        List<Map.Entry<String, String>> envExtras,

        // —— 网关 / 本地检测 ——
        /** 可路由任意模型的网关（OpenRouter、AiHubMix 等） */
        boolean isGateway,
        /** 本地部署（vLLM、Ollama 等） */
        boolean isLocal,
        /** 按 api_key 前缀探测，如 "sk-or-" */
        String detectByKeyPrefix,
        /** 按 api_base URL 子串探测，如 "openrouter" */
        String detectByBaseKeyword,
        /** OpenAI 兼容的 base URL */
        String defaultApiBase,

        // —— 网关行为 ——
        /** 发送前剥离 "provider/" 前缀 */
        boolean stripModelPrefix,
        boolean supportsMaxCompletionTokens,

        // —— 按模型参数覆盖（model_pattern, param_overrides） ——
        List<Map.Entry<String, Map<String, Object>>> modelOverrides,

        // —— 认证模式 ——
        /** OAuth 认证（Codex、Copilot 等） */
        boolean isOauth,
        /** 跳过 API key 校验（用户自行提供全部信息） */
        boolean isDirect,
        /** 仅语音转文字，不可用于 chat */
        boolean isTranscriptionOnly,

        // —— 高级特性 ——
        /** 支持 content block 上的 cache_control（如 Anthropic prompt caching） */
        boolean supportsPromptCaching,
        /** "" | "thinking_type" | "enable_thinking" | "reasoning_split" */
        String thinkingStyle,
        /** "reasoning_effort"（OpenRouter 等网关原生 reasoning 控制） */
        String gatewayReasoningStyle,
        /** 当 content 为空时将 "reasoning" 响应字段作为正式内容 */
        boolean reasoningAsContent) {

    /** Compact constructor 为可选字段提供默认值，对标 Python dataclass 的 default */
    public ProviderSpec {
        if (keywords == null) keywords = List.of();
        if (displayName == null) displayName = "";
        if (backend == null) backend = "openai_compat";
        if (envExtras == null) envExtras = List.of();
        if (defaultApiBase == null) defaultApiBase = "";
        if (detectByKeyPrefix == null) detectByKeyPrefix = "";
        if (detectByBaseKeyword == null) detectByBaseKeyword = "";
        if (modelOverrides == null) modelOverrides = List.of();
        if (thinkingStyle == null) thinkingStyle = "";
        if (gatewayReasoningStyle == null) gatewayReasoningStyle = "";
    }

    /**
     * 返回展示标签，优先用 displayName 否则将 name 首字母大写。
     *
     * @return 人类可读的 provider 标签
     */
    // 对标 Python ProviderSpec.label property
    public String label() {
        return (displayName != null && !displayName.isEmpty())
                ? displayName
                : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    // —— Builder 模式，提供命名参数风格的可读性 ——

    /**
     * 创建 Builder 实例，name 为必填字段。
     *
     * @param name provider 配置字段名
     * @return 新的 Builder
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static class Builder {
        private final String name;
        private final List<String> keywords = new ArrayList<>();
        private String envKey = "";
        private String displayName = "";
        private String backend = "openai_compat";
        private final List<Map.Entry<String, String>> envExtras = new ArrayList<>();
        private boolean isGateway;
        private boolean isLocal;
        private String detectByKeyPrefix = "";
        private String detectByBaseKeyword = "";
        private String defaultApiBase = "";
        private boolean stripModelPrefix;
        private boolean supportsMaxCompletionTokens;
        private final List<Map.Entry<String, Map<String, Object>>> modelOverrides = new ArrayList<>();
        private boolean isOauth;
        private boolean isDirect;
        private boolean isTranscriptionOnly;
        private boolean supportsPromptCaching;
        private String thinkingStyle = "";
        private String gatewayReasoningStyle = "";
        private boolean reasoningAsContent;

        Builder(String name) {
            this.name = name;
        }

        public Builder keywords(String... values) { keywords.addAll(List.of(values)); return this; }
        public Builder envKey(String value) { this.envKey = value != null ? value : ""; return this; }
        public Builder displayName(String value) { this.displayName = value != null ? value : ""; return this; }
        public Builder backend(String value) { this.backend = value != null ? value : "openai_compat"; return this; }
        public Builder envExtra(String envName, String template) {
            envExtras.add(Map.entry(envName, template)); return this;
        }
        public Builder isGateway(boolean value) { this.isGateway = value; return this; }
        public Builder isLocal(boolean value) { this.isLocal = value; return this; }
        public Builder detectByKeyPrefix(String value) { this.detectByKeyPrefix = value != null ? value : ""; return this; }
        public Builder detectByBaseKeyword(String value) { this.detectByBaseKeyword = value != null ? value : ""; return this; }
        public Builder defaultApiBase(String value) { this.defaultApiBase = value != null ? value : ""; return this; }
        public Builder stripModelPrefix(boolean value) { this.stripModelPrefix = value; return this; }
        public Builder supportsMaxCompletionTokens(boolean value) { this.supportsMaxCompletionTokens = value; return this; }
        @SuppressWarnings("unchecked")
        public Builder modelOverride(String pattern, Map<String, Object> overrides) {
            modelOverrides.add(Map.entry(pattern, (Map<String, Object>) Map.copyOf(overrides))); return this;
        }
        public Builder isOauth(boolean value) { this.isOauth = value; return this; }
        public Builder isDirect(boolean value) { this.isDirect = value; return this; }
        public Builder isTranscriptionOnly(boolean value) { this.isTranscriptionOnly = value; return this; }
        public Builder supportsPromptCaching(boolean value) { this.supportsPromptCaching = value; return this; }
        public Builder thinkingStyle(String value) { this.thinkingStyle = value != null ? value : ""; return this; }
        public Builder gatewayReasoningStyle(String value) { this.gatewayReasoningStyle = value != null ? value : ""; return this; }
        public Builder reasoningAsContent(boolean value) { this.reasoningAsContent = value; return this; }

        /**
         * 构建不可变 ProviderSpec。
         *
         * @return 新的 ProviderSpec 实例
         */
        public ProviderSpec build() {
            return new ProviderSpec(
                    name, List.copyOf(keywords), envKey, displayName,
                    backend, List.copyOf(envExtras),
                    isGateway, isLocal, detectByKeyPrefix, detectByBaseKeyword, defaultApiBase,
                    stripModelPrefix, supportsMaxCompletionTokens,
                    List.copyOf(modelOverrides),
                    isOauth, isDirect, isTranscriptionOnly,
                    supportsPromptCaching, thinkingStyle, gatewayReasoningStyle, reasoningAsContent);
        }
    }
}
