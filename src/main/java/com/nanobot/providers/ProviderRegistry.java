package com.nanobot.providers;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Provider Registry — mirrors nanobot/providers/registry.py exactly.
 * Order matters: it controls match priority and fallback. Gateways first.
 */
public final class ProviderRegistry {

    private static final Map<String, ProviderSpec> BY_NAME;

    public static final List<ProviderSpec> PROVIDERS = List.of(
            // Custom (direct)
            ProviderSpec.builder("custom")
                    .keywords()
                    .envKey("")
                    .displayName("Custom")
                    .backend("openai_compat")
                    .isDirect(true)
                    .build(),

            // Azure OpenAI
            ProviderSpec.builder("azure_openai")
                    .keywords("azure", "azure-openai")
                    .envKey("")
                    .displayName("Azure OpenAI")
                    .backend("azure_openai")
                    .isDirect(true)
                    .build(),

            // Bedrock
            ProviderSpec.builder("bedrock")
                    .keywords("bedrock", "anthropic.claude", "amazon.nova", "meta.",
                            "mistral.", "cohere.", "qwen.", "deepseek.", "openai.gpt-oss",
                            "ai21.", "moonshot.", "writer.", "zai.")
                    .envKey("AWS_BEARER_TOKEN_BEDROCK")
                    .displayName("AWS Bedrock")
                    .backend("bedrock")
                    .isDirect(true)
                    .build(),

            // OpenRouter
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

            // Hugging Face
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

            // Skywork
            ProviderSpec.builder("skywork")
                    .keywords("skywork", "skyclaw", "apifree")
                    .envKey("SKYWORK_API_KEY")
                    .displayName("Skywork")
                    .backend("openai_compat")
                    .isGateway(true)
                    .detectByBaseKeyword("apifree.ai")
                    .defaultApiBase("https://api.apifree.ai/agent/v1")
                    .envExtras(new EnvExtra("APIFREE_API_KEY", "{api_key}"))
                    .build(),

            // AiHubMix
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

            // SiliconFlow
            ProviderSpec.builder("siliconflow")
                    .keywords("siliconflow")
                    .envKey("OPENAI_API_KEY")
                    .displayName("SiliconFlow")
                    .backend("openai_compat")
                    .isGateway(true)
                    .detectByBaseKeyword("siliconflow")
                    .defaultApiBase("https://api.siliconflow.cn/v1")
                    .build(),

            // Novita
            ProviderSpec.builder("novita")
                    .keywords("novita")
                    .envKey("NOVITA_API_KEY")
                    .displayName("Novita AI")
                    .backend("openai_compat")
                    .isGateway(true)
                    .detectByBaseKeyword("novita")
                    .defaultApiBase("https://api.novita.ai/openai")
                    .build(),

            // VolcEngine
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

            // VolcEngine Coding Plan
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

            // BytePlus
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

            // BytePlus Coding Plan
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

            // Anthropic
            ProviderSpec.builder("anthropic")
                    .keywords("anthropic", "claude")
                    .envKey("ANTHROPIC_API_KEY")
                    .displayName("Anthropic")
                    .backend("anthropic")
                    .supportsPromptCaching(true)
                    .build(),

            // OpenAI
            ProviderSpec.builder("openai")
                    .keywords("openai", "gpt")
                    .envKey("OPENAI_API_KEY")
                    .displayName("OpenAI")
                    .backend("openai_compat")
                    .supportsMaxCompletionTokens(true)
                    .build(),

            // OpenAI Codex
            ProviderSpec.builder("openai_codex")
                    .keywords("openai-codex")
                    .envKey("")
                    .displayName("OpenAI Codex")
                    .backend("openai_codex")
                    .detectByBaseKeyword("codex")
                    .defaultApiBase("https://chatgpt.com/backend-api")
                    .isOauth(true)
                    .build(),

            // GitHub Copilot
            ProviderSpec.builder("github_copilot")
                    .keywords("github_copilot", "copilot")
                    .envKey("")
                    .displayName("Github Copilot")
                    .backend("github_copilot")
                    .defaultApiBase("https://api.githubcopilot.com")
                    .stripModelPrefix(true)
                    .isOauth(true)
                    .supportsMaxCompletionTokens(true)
                    .build(),

            // DeepSeek
            ProviderSpec.builder("deepseek")
                    .keywords("deepseek")
                    .envKey("DEEPSEEK_API_KEY")
                    .displayName("DeepSeek")
                    .backend("openai_compat")
                    .defaultApiBase("https://api.deepseek.com")
                    .thinkingStyle("thinking_type")
                    .build(),

            // Gemini
            ProviderSpec.builder("gemini")
                    .keywords("gemini", "gemma")
                    .envKey("GEMINI_API_KEY")
                    .displayName("Gemini")
                    .backend("openai_compat")
                    .defaultApiBase("https://generativelanguage.googleapis.com/v1beta/openai/")
                    .build(),

            // Zhipu
            ProviderSpec.builder("zhipu")
                    .keywords("zhipu", "glm", "zai")
                    .envKey("ZAI_API_KEY")
                    .displayName("Zhipu AI")
                    .backend("openai_compat")
                    .defaultApiBase("https://open.bigmodel.cn/api/paas/v4")
                    .envExtras(new EnvExtra("ZHIPUAI_API_KEY", "{api_key}"))
                    .build(),

            // DashScope
            ProviderSpec.builder("dashscope")
                    .keywords("qwen", "dashscope")
                    .envKey("DASHSCOPE_API_KEY")
                    .displayName("DashScope")
                    .backend("openai_compat")
                    .defaultApiBase("https://dashscope.aliyuncs.com/compatible-mode/v1")
                    .thinkingStyle("enable_thinking")
                    .build(),

            // Moonshot
            ProviderSpec.builder("moonshot")
                    .keywords("moonshot", "kimi")
                    .envKey("MOONSHOT_API_KEY")
                    .displayName("Moonshot")
                    .backend("openai_compat")
                    .defaultApiBase("https://api.moonshot.ai/v1")
                    .modelOverrides(
                            new ModelOverride("kimi-k2.5", Map.of("temperature", 1.0)),
                            new ModelOverride("kimi-k2.6", Map.of("temperature", 1.0))
                    )
                    .build(),

            // MiniMax
            ProviderSpec.builder("minimax")
                    .keywords("minimax")
                    .envKey("MINIMAX_API_KEY")
                    .displayName("MiniMax")
                    .backend("openai_compat")
                    .defaultApiBase("https://api.minimax.io/v1")
                    .thinkingStyle("reasoning_split")
                    .build(),

            // MiniMax Anthropic
            ProviderSpec.builder("minimax_anthropic")
                    .keywords("minimax_anthropic")
                    .envKey("MINIMAX_API_KEY")
                    .displayName("MiniMax (Anthropic)")
                    .backend("anthropic")
                    .defaultApiBase("https://api.minimax.io/anthropic")
                    .build(),

            // Mistral
            ProviderSpec.builder("mistral")
                    .keywords("mistral")
                    .envKey("MISTRAL_API_KEY")
                    .displayName("Mistral")
                    .backend("openai_compat")
                    .defaultApiBase("https://api.mistral.ai/v1")
                    .build(),

            // Step Fun
            ProviderSpec.builder("stepfun")
                    .keywords("stepfun", "step")
                    .envKey("STEPFUN_API_KEY")
                    .displayName("Step Fun")
                    .backend("openai_compat")
                    .defaultApiBase("https://api.stepfun.com/v1")
                    .reasoningAsContent(true)
                    .build(),

            // Xiaomi MIMO
            ProviderSpec.builder("xiaomi_mimo")
                    .keywords("xiaomi_mimo", "mimo")
                    .envKey("XIAOMIMIMO_API_KEY")
                    .displayName("Xiaomi MIMO")
                    .backend("openai_compat")
                    .defaultApiBase("https://api.xiaomimimo.com/v1")
                    .thinkingStyle("thinking_type")
                    .build(),

            // LongCat
            ProviderSpec.builder("longcat")
                    .keywords("longcat")
                    .envKey("LONGCAT_API_KEY")
                    .displayName("LongCat")
                    .backend("openai_compat")
                    .defaultApiBase("https://api.longcat.chat/openai/v1")
                    .build(),

            // Ant Ling
            ProviderSpec.builder("ant_ling")
                    .keywords("ant_ling", "ant-ling", "ling-", "ring-")
                    .envKey("ANT_LING_API_KEY")
                    .displayName("Ant Ling")
                    .backend("openai_compat")
                    .detectByBaseKeyword("ant-ling.com")
                    .defaultApiBase("https://api.ant-ling.com/v1")
                    .build(),

            // vLLM
            ProviderSpec.builder("vllm")
                    .keywords("vllm")
                    .envKey("HOSTED_VLLM_API_KEY")
                    .displayName("vLLM")
                    .backend("openai_compat")
                    .isLocal(true)
                    .build(),

            // Ollama
            ProviderSpec.builder("ollama")
                    .keywords("ollama", "nemotron")
                    .envKey("OLLAMA_API_KEY")
                    .displayName("Ollama")
                    .backend("openai_compat")
                    .isLocal(true)
                    .detectByBaseKeyword("11434")
                    .defaultApiBase("http://localhost:11434/v1")
                    .build(),

            // LM Studio
            ProviderSpec.builder("lm_studio")
                    .keywords("lm-studio", "lmstudio", "lm_studio")
                    .envKey("LM_STUDIO_API_KEY")
                    .displayName("LM Studio")
                    .backend("openai_compat")
                    .isLocal(true)
                    .detectByBaseKeyword("1234")
                    .defaultApiBase("http://localhost:1234/v1")
                    .build(),

            // Atomic Chat
            ProviderSpec.builder("atomic_chat")
                    .keywords("atomic-chat", "atomic_chat", "atomicchat")
                    .envKey("ATOMIC_CHAT_API_KEY")
                    .displayName("Atomic Chat")
                    .backend("openai_compat")
                    .isLocal(true)
                    .detectByBaseKeyword("1337")
                    .defaultApiBase("http://localhost:1337/v1")
                    .build(),

            // OVMS
            ProviderSpec.builder("ovms")
                    .keywords("openvino", "ovms")
                    .envKey("")
                    .displayName("OpenVINO Model Server")
                    .backend("openai_compat")
                    .isDirect(true)
                    .isLocal(true)
                    .defaultApiBase("http://localhost:8000/v3")
                    .build(),

            // NVIDIA NIM
            ProviderSpec.builder("nvidia")
                    .keywords("nvidia", "nemotron", "nvapi")
                    .envKey("NVIDIA_NIM_API_KEY")
                    .displayName("NVIDIA NIM")
                    .backend("openai_compat")
                    .detectByKeyPrefix("nvapi-")
                    .detectByBaseKeyword("nvidia.com")
                    .defaultApiBase("https://integrate.api.nvidia.com/v1")
                    .build(),

            // Groq
            ProviderSpec.builder("groq")
                    .keywords("groq")
                    .envKey("GROQ_API_KEY")
                    .displayName("Groq")
                    .backend("openai_compat")
                    .defaultApiBase("https://api.groq.com/openai/v1")
                    .build(),

            // AssemblyAI
            ProviderSpec.builder("assemblyai")
                    .keywords("assemblyai")
                    .envKey("ASSEMBLYAI_API_KEY")
                    .displayName("AssemblyAI")
                    .backend("openai_compat")
                    .defaultApiBase("https://api.assemblyai.com/v2")
                    .isTranscriptionOnly(true)
                    .build(),

            // Qianfan
            ProviderSpec.builder("qianfan")
                    .keywords("qianfan", "ernie")
                    .envKey("QIANFAN_API_KEY")
                    .displayName("Qianfan")
                    .backend("openai_compat")
                    .defaultApiBase("https://qianfan.baidubce.com/v2")
                    .build()
    );

    static {
        BY_NAME = PROVIDERS.stream()
                .collect(Collectors.toUnmodifiableMap(
                        spec -> spec.name().toLowerCase().replace("-", "_"),
                        Function.identity()
                ));
    }

    public static Optional<ProviderSpec> findByName(String name) {
        return Optional.ofNullable(BY_NAME.get(name.toLowerCase().replace("-", "_")));
    }

    private ProviderRegistry() {}
}
