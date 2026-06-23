package com.nanobot.providers;

import java.util.*;

/**
 * 所有受支持 LLM provider 的静态注册表，顺序即匹配优先级。
 *
 * <p>对标 Python {@code nanobot/providers/registry.py PROVIDERS tuple}（约 35 个 ProviderSpec）。
 * 顺序：Custom → Azure → Bedrock → Gateways → 标准提供商 → 本地部署 → 辅助。
 */
public final class ProviderRegistry {

    private ProviderRegistry() {
    }

    /**
     * 不可变的 provider 基础列表，顺序即优先级。Gateways 在前。
     *
     * <p>对标 Python {@code nanobot/providers/registry.py:96-533 PROVIDERS tuple}
     */
    public static final List<ProviderSpec> PROVIDERS = List.of(
            // === Custom（直接 OpenAI 兼容端点） ===
            ProviderSpec.builder("custom")
                    .displayName("Custom")
                    .backend("openai_compat")
                    .isDirect(true)
                    .build(),

            // === Azure OpenAI（直接 API 调用，API version 2024-10-21） ===
            ProviderSpec.builder("azure_openai")
                    .keywords("azure", "azure-openai")
                    .displayName("Azure OpenAI")
                    .backend("azure_openai")
                    .isDirect(true)
                    .build(),

            // === AWS Bedrock（原生 Converse API，通过 bedrock-runtime） ===
            ProviderSpec.builder("bedrock")
                    .keywords("bedrock", "anthropic.claude", "amazon.nova",
                            "meta.", "mistral.", "cohere.", "qwen.",
                            "deepseek.", "openai.gpt-oss", "ai21.",
                            "moonshot.", "writer.", "zai.")
                    .envKey("AWS_BEARER_TOKEN_BEDROCK")
                    .displayName("AWS Bedrock")
                    .backend("bedrock")
                    .isDirect(true)
                    .build(),

            // === Gateways（通过 api_key / api_base 探测，非模型名） ===

            // OpenRouter：全球网关，key 以 "sk-or-" 开头
            ProviderSpec.builder("openrouter")
                    .keywords("openrouter")
                    .envKey("OPENROUTER_API_KEY")
                    .displayName("OpenRouter")
                    .backend("openai_compat")
                    .isGateway(true)
                    .detectByKeyPrefix("sk-or-")
                    .detectByBaseKeyword("openrouter")
                    .defaultApiBase("https://openrouter.ai/api/v1")
                    .supportsPromptCaching(true)
                    .gatewayReasoningStyle("reasoning_effort")
                    .build(),

            // Hugging Face Inference Providers：OpenAI 兼容 chat 模型路由器
            ProviderSpec.builder("huggingface")
                    .keywords("huggingface", "hugging-face")
                    .envKey("HF_TOKEN")
                    .displayName("Hugging Face")
                    .backend("openai_compat")
                    .isGateway(true)
                    .detectByKeyPrefix("hf_")
                    .detectByBaseKeyword("huggingface")
                    .defaultApiBase("https://router.huggingface.co/v1")
                    .build(),

            // Skywork API 平台（APIFree）：OpenAI 兼容 MaaS 网关
            ProviderSpec.builder("skywork")
                    .keywords("skywork", "skyclaw", "apifree")
                    .envKey("SKYWORK_API_KEY")
                    .displayName("Skywork")
                    .backend("openai_compat")
                    .envExtra("APIFREE_API_KEY", "{api_key}")
                    .isGateway(true)
                    .detectByBaseKeyword("apifree.ai")
                    .defaultApiBase("https://api.apifree.ai/agent/v1")
                    .build(),

            // AiHubMix：全球网关，OpenAI 兼容接口，strip_model_prefix=True
            ProviderSpec.builder("aihubmix")
                    .keywords("aihubmix")
                    .envKey("OPENAI_API_KEY")
                    .displayName("AiHubMix")
                    .backend("openai_compat")
                    .isGateway(true)
                    .detectByBaseKeyword("aihubmix")
                    .defaultApiBase("https://aihubmix.com/v1")
                    .stripModelPrefix(true)
                    .build(),

            // SiliconFlow（硅基流动）：OpenAI 兼容网关，模型名保留 org 前缀
            ProviderSpec.builder("siliconflow")
                    .keywords("siliconflow")
                    .envKey("OPENAI_API_KEY")
                    .displayName("SiliconFlow")
                    .backend("openai_compat")
                    .isGateway(true)
                    .detectByBaseKeyword("siliconflow")
                    .defaultApiBase("https://api.siliconflow.cn/v1")
                    .build(),

            // Novita AI：OpenAI 兼容网关，托管模型 API
            ProviderSpec.builder("novita")
                    .keywords("novita")
                    .envKey("NOVITA_API_KEY")
                    .displayName("Novita AI")
                    .backend("openai_compat")
                    .isGateway(true)
                    .detectByBaseKeyword("novita")
                    .defaultApiBase("https://api.novita.ai/openai")
                    .build(),

            // VolcEngine（火山引擎）：OpenAI 兼容网关，按量付费模型
            ProviderSpec.builder("volcengine")
                    .keywords("volcengine", "volces", "ark")
                    .envKey("OPENAI_API_KEY")
                    .displayName("VolcEngine")
                    .backend("openai_compat")
                    .isGateway(true)
                    .detectByBaseKeyword("volces")
                    .defaultApiBase("https://ark.cn-beijing.volces.com/api/v3")
                    .thinkingStyle("thinking_type")
                    .supportsMaxCompletionTokens(true)
                    .build(),

            // VolcEngine Coding Plan（火山引擎 Coding Plan）：与 volcengine 共用 key
            ProviderSpec.builder("volcengine_coding_plan")
                    .keywords("volcengine-plan")
                    .envKey("OPENAI_API_KEY")
                    .displayName("VolcEngine Coding Plan")
                    .backend("openai_compat")
                    .isGateway(true)
                    .defaultApiBase("https://ark.cn-beijing.volces.com/api/coding/v3")
                    .stripModelPrefix(true)
                    .thinkingStyle("thinking_type")
                    .supportsMaxCompletionTokens(true)
                    .build(),

            // BytePlus：VolcEngine 国际版，按量付费模型
            ProviderSpec.builder("byteplus")
                    .keywords("byteplus")
                    .envKey("OPENAI_API_KEY")
                    .displayName("BytePlus")
                    .backend("openai_compat")
                    .isGateway(true)
                    .detectByBaseKeyword("bytepluses")
                    .defaultApiBase("https://ark.ap-southeast.bytepluses.com/api/v3")
                    .stripModelPrefix(true)
                    .thinkingStyle("thinking_type")
                    .build(),

            // BytePlus Coding Plan：与 byteplus 共用 key
            ProviderSpec.builder("byteplus_coding_plan")
                    .keywords("byteplus-plan")
                    .envKey("OPENAI_API_KEY")
                    .displayName("BytePlus Coding Plan")
                    .backend("openai_compat")
                    .isGateway(true)
                    .defaultApiBase("https://ark.ap-southeast.bytepluses.com/api/coding/v3")
                    .stripModelPrefix(true)
                    .thinkingStyle("thinking_type")
                    .build(),

            // === 标准提供商（按模型名关键词匹配） ===

            // Anthropic：原生 Anthropic SDK
            ProviderSpec.builder("anthropic")
                    .keywords("anthropic", "claude")
                    .envKey("ANTHROPIC_API_KEY")
                    .displayName("Anthropic")
                    .backend("anthropic")
                    .supportsPromptCaching(true)
                    .build(),

            // OpenAI：SDK 默认 base URL（无需覆盖）
            ProviderSpec.builder("openai")
                    .keywords("openai", "gpt")
                    .envKey("OPENAI_API_KEY")
                    .displayName("OpenAI")
                    .backend("openai_compat")
                    .supportsMaxCompletionTokens(true)
                    .build(),

            // OpenAI Codex：OAuth 认证，专用 provider
            ProviderSpec.builder("openai_codex")
                    .keywords("openai-codex")
                    .displayName("OpenAI Codex")
                    .backend("openai_codex")
                    .detectByBaseKeyword("codex")
                    .defaultApiBase("https://chatgpt.com/backend-api")
                    .isOauth(true)
                    .build(),

            // GitHub Copilot：OAuth 认证
            ProviderSpec.builder("github_copilot")
                    .keywords("github_copilot", "copilot")
                    .displayName("Github Copilot")
                    .backend("github_copilot")
                    .defaultApiBase("https://api.githubcopilot.com")
                    .stripModelPrefix(true)
                    .isOauth(true)
                    .supportsMaxCompletionTokens(true)
                    .build(),

            // DeepSeek：OpenAI 兼容，api.deepseek.com
            ProviderSpec.builder("deepseek")
                    .keywords("deepseek")
                    .envKey("DEEPSEEK_API_KEY")
                    .displayName("DeepSeek")
                    .backend("openai_compat")
                    .defaultApiBase("https://api.deepseek.com")
                    .thinkingStyle("thinking_type")
                    .build(),

            // Gemini：Google 的 OpenAI 兼容端点
            ProviderSpec.builder("gemini")
                    .keywords("gemini", "gemma")
                    .envKey("GEMINI_API_KEY")
                    .displayName("Gemini")
                    .backend("openai_compat")
                    .defaultApiBase("https://generativelanguage.googleapis.com/v1beta/openai/")
                    .build(),

            // Zhipu（智谱）：OpenAI 兼容，open.bigmodel.cn
            ProviderSpec.builder("zhipu")
                    .keywords("zhipu", "glm", "zai")
                    .envKey("ZAI_API_KEY")
                    .displayName("Zhipu AI")
                    .backend("openai_compat")
                    .envExtra("ZHIPUAI_API_KEY", "{api_key}")
                    .defaultApiBase("https://open.bigmodel.cn/api/paas/v4")
                    .build(),

            // DashScope（通义）：Qwen 模型，OpenAI 兼容端点
            ProviderSpec.builder("dashscope")
                    .keywords("qwen", "dashscope")
                    .envKey("DASHSCOPE_API_KEY")
                    .displayName("DashScope")
                    .backend("openai_compat")
                    .defaultApiBase("https://dashscope.aliyuncs.com/compatible-mode/v1")
                    .thinkingStyle("enable_thinking")
                    .build(),

            // Moonshot（月之暗面）：Kimi K2.5 / K2.6 强制 temperature >= 1.0
            ProviderSpec.builder("moonshot")
                    .keywords("moonshot", "kimi")
                    .envKey("MOONSHOT_API_KEY")
                    .displayName("Moonshot")
                    .backend("openai_compat")
                    .defaultApiBase("https://api.moonshot.ai/v1")
                    .modelOverride("kimi-k2.5", Map.of("temperature", 1.0))
                    .modelOverride("kimi-k2.6", Map.of("temperature", 1.0))
                    .build(),

            // MiniMax：OpenAI 兼容 API
            ProviderSpec.builder("minimax")
                    .keywords("minimax")
                    .envKey("MINIMAX_API_KEY")
                    .displayName("MiniMax")
                    .backend("openai_compat")
                    .defaultApiBase("https://api.minimax.io/v1")
                    .thinkingStyle("reasoning_split")
                    .build(),

            // MiniMax Anthropic 兼容端点：支持 thinking mode
            ProviderSpec.builder("minimax_anthropic")
                    .keywords("minimax_anthropic")
                    .envKey("MINIMAX_API_KEY")
                    .displayName("MiniMax (Anthropic)")
                    .backend("anthropic")
                    .defaultApiBase("https://api.minimax.io/anthropic")
                    .build(),

            // Mistral AI：OpenAI 兼容 API
            ProviderSpec.builder("mistral")
                    .keywords("mistral")
                    .envKey("MISTRAL_API_KEY")
                    .displayName("Mistral")
                    .backend("openai_compat")
                    .defaultApiBase("https://api.mistral.ai/v1")
                    .build(),

            // Step Fun（阶跃星辰）：OpenAI 兼容 API，reasoning_as_content=True
            ProviderSpec.builder("stepfun")
                    .keywords("stepfun", "step")
                    .envKey("STEPFUN_API_KEY")
                    .displayName("Step Fun")
                    .backend("openai_compat")
                    .defaultApiBase("https://api.stepfun.com/v1")
                    .reasoningAsContent(true)
                    .build(),

            // Xiaomi MIMO（小米）：OpenAI 兼容 API，thinking_style="thinking_type"
            ProviderSpec.builder("xiaomi_mimo")
                    .keywords("xiaomi_mimo", "mimo")
                    .envKey("XIAOMIMIMO_API_KEY")
                    .displayName("Xiaomi MIMO")
                    .backend("openai_compat")
                    .defaultApiBase("https://api.xiaomimimo.com/v1")
                    .thinkingStyle("thinking_type")
                    .build(),

            // LongCat：OpenAI 兼容 API
            ProviderSpec.builder("longcat")
                    .keywords("longcat")
                    .envKey("LONGCAT_API_KEY")
                    .displayName("LongCat")
                    .backend("openai_compat")
                    .defaultApiBase("https://api.longcat.chat/openai/v1")
                    .build(),

            // Ant Ling：OpenAI 兼容 API，Ling/Ring 模型族
            ProviderSpec.builder("ant_ling")
                    .keywords("ant_ling", "ant-ling", "ling-", "ring-")
                    .envKey("ANT_LING_API_KEY")
                    .displayName("Ant Ling")
                    .backend("openai_compat")
                    .detectByBaseKeyword("ant-ling.com")
                    .defaultApiBase("https://api.ant-ling.com/v1")
                    .build(),

            // === 本地部署（按配置 key 匹配，非 api_base） ===

            // vLLM / 任意 OpenAI 兼容本地服务器
            ProviderSpec.builder("vllm")
                    .keywords("vllm")
                    .envKey("HOSTED_VLLM_API_KEY")
                    .displayName("vLLM")
                    .backend("openai_compat")
                    .isLocal(true)
                    .build(),

            // Ollama（本地，OpenAI 兼容）
            ProviderSpec.builder("ollama")
                    .keywords("ollama", "nemotron")
                    .envKey("OLLAMA_API_KEY")
                    .displayName("Ollama")
                    .backend("openai_compat")
                    .isLocal(true)
                    .detectByBaseKeyword("11434")
                    .defaultApiBase("http://localhost:11434/v1")
                    .build(),

            // LM Studio（本地，OpenAI 兼容）
            ProviderSpec.builder("lm_studio")
                    .keywords("lm-studio", "lmstudio", "lm_studio")
                    .envKey("LM_STUDIO_API_KEY")
                    .displayName("LM Studio")
                    .backend("openai_compat")
                    .isLocal(true)
                    .detectByBaseKeyword("1234")
                    .defaultApiBase("http://localhost:1234/v1")
                    .build(),

            // Atomic Chat（本地，OpenAI 兼容）— https://atomic.chat/
            ProviderSpec.builder("atomic_chat")
                    .keywords("atomic-chat", "atomic_chat", "atomicchat")
                    .envKey("ATOMIC_CHAT_API_KEY")
                    .displayName("Atomic Chat")
                    .backend("openai_compat")
                    .isLocal(true)
                    .detectByBaseKeyword("1337")
                    .defaultApiBase("http://localhost:1337/v1")
                    .build(),

            // OpenVINO Model Server（直接、本地、OpenAI 兼容 /v3）
            ProviderSpec.builder("ovms")
                    .keywords("openvino", "ovms")
                    .displayName("OpenVINO Model Server")
                    .backend("openai_compat")
                    .isDirect(true)
                    .isLocal(true)
                    .defaultApiBase("http://localhost:8000/v3")
                    .build(),

            // NVIDIA NIM（NVIDIA Inference Microservices），key 以 "nvapi-" 开头
            ProviderSpec.builder("nvidia")
                    .keywords("nvidia", "nemotron", "nvapi")
                    .envKey("NVIDIA_NIM_API_KEY")
                    .displayName("NVIDIA NIM")
                    .backend("openai_compat")
                    .detectByKeyPrefix("nvapi-")
                    .detectByBaseKeyword("nvidia.com")
                    .defaultApiBase("https://integrate.api.nvidia.com/v1")
                    .build(),

            // === 辅助（非主要 LLM provider） ===

            // Groq：主要用于 Whisper 语音转文字，也可用于 LLM
            ProviderSpec.builder("groq")
                    .keywords("groq")
                    .envKey("GROQ_API_KEY")
                    .displayName("Groq")
                    .backend("openai_compat")
                    .defaultApiBase("https://api.groq.com/openai/v1")
                    .build(),

            // AssemblyAI：仅语音转文字，WebUI 排除在 chat 模型选择器之外
            ProviderSpec.builder("assemblyai")
                    .keywords("assemblyai")
                    .envKey("ASSEMBLYAI_API_KEY")
                    .displayName("AssemblyAI")
                    .backend("openai_compat")
                    .defaultApiBase("https://api.assemblyai.com/v2")
                    .isTranscriptionOnly(true)
                    .build(),

            // Qianfan（百度千帆）：OpenAI 兼容 API
            ProviderSpec.builder("qianfan")
                    .keywords("qianfan", "ernie")
                    .envKey("QIANFAN_API_KEY")
                    .displayName("Qianfan")
                    .backend("openai_compat")
                    .defaultApiBase("https://qianfan.baidubce.com/v2")
                    .build()
    );

    /** 运行时可变副本，供启动时注册插件 provider */
    private static final List<ProviderSpec> REGISTRY = new ArrayList<>(PROVIDERS);

    // —— 查找辅助方法 ——

    /**
     * 按配置字段名查找 provider spec，兼容 snake_case 和 camelCase。
     *
     * @param name provider 名称（如 "azure_openai" 或 "azureOpenai"）
     * @return 匹配的 ProviderSpec，未找到返回 Optional.empty()
     */
    // 对标 Python registry.py find_by_name()
    public static Optional<ProviderSpec> findByName(String name) {
        if (name == null) return Optional.empty();
        String normalized = toSnake(name.replace("-", "_"));
        for (ProviderSpec spec : REGISTRY) {
            if (spec.name().equals(normalized)) {
                return Optional.of(spec);
            }
        }
        return Optional.empty();
    }

    /**
     * 返回当前注册表（含插件）的不可变快照。
     *
     * @return 所有已注册 ProviderSpec 的列表
     */
    public static List<ProviderSpec> all() {
        return List.copyOf(REGISTRY);
    }

    /**
     * 注册插件 provider（启动时调用）。
     *
     * @param spec 要注册的 ProviderSpec
     */
    public static synchronized void register(ProviderSpec spec) {
        REGISTRY.add(spec);
    }

    /**
     * 将 camelCase 字符串转为 snake_case。
     *
     * @param camel camelCase 输入
     * @return snake_case 输出
     */
    private static String toSnake(String camel) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) result.append('_');
                result.append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}
