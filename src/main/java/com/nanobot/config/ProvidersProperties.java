package com.nanobot.config;

/**
 * Mirrors Python ProvidersConfig — 30+ named provider configs + api_type validation.
 */
public record ProvidersProperties(
        ProviderConfig custom,
        ProviderConfig azureOpenai,
        BedrockProviderProperties bedrock,
        ProviderConfig anthropic,
        ProviderConfig openai,
        ProviderConfig openrouter,
        ProviderConfig assemblyai,
        ProviderConfig huggingface,
        ProviderConfig skywork,
        ProviderConfig deepseek,
        ProviderConfig groq,
        ProviderConfig zhipu,
        ProviderConfig dashscope,
        ProviderConfig vllm,
        ProviderConfig ollama,
        ProviderConfig lmStudio,
        ProviderConfig atomicChat,
        ProviderConfig ovms,
        ProviderConfig gemini,
        ProviderConfig moonshot,
        ProviderConfig minimax,
        ProviderConfig minimaxAnthropic,
        ProviderConfig mistral,
        ProviderConfig stepfun,
        ProviderConfig xiaomiMimo,
        ProviderConfig longcat,
        ProviderConfig antLing,
        ProviderConfig aihubmix,
        ProviderConfig siliconflow,
        ProviderConfig novita,
        ProviderConfig volcengine,
        ProviderConfig volcengineCodingPlan,
        ProviderConfig byteplus,
        ProviderConfig byteplusCodingPlan,
        ProviderConfig openaiCodex,
        ProviderConfig githubCopilot,
        ProviderConfig qianfan,
        ProviderConfig nvidia
) {
    public ProvidersProperties {
        if (custom == null) custom = ProviderConfig.defaults();
        if (azureOpenai == null) azureOpenai = ProviderConfig.defaults();
        if (bedrock == null) bedrock = BedrockProviderProperties.defaults();
        if (anthropic == null) anthropic = ProviderConfig.defaults();
        if (openai == null) openai = ProviderConfig.defaults();
        if (openrouter == null) openrouter = ProviderConfig.defaults();
        if (assemblyai == null) assemblyai = ProviderConfig.defaults();
        if (huggingface == null) huggingface = ProviderConfig.defaults();
        if (skywork == null) skywork = ProviderConfig.defaults();
        if (deepseek == null) deepseek = ProviderConfig.defaults();
        if (groq == null) groq = ProviderConfig.defaults();
        if (zhipu == null) zhipu = ProviderConfig.defaults();
        if (dashscope == null) dashscope = ProviderConfig.defaults();
        if (vllm == null) vllm = ProviderConfig.defaults();
        if (ollama == null) ollama = ProviderConfig.defaults();
        if (lmStudio == null) lmStudio = ProviderConfig.defaults();
        if (atomicChat == null) atomicChat = ProviderConfig.defaults();
        if (ovms == null) ovms = ProviderConfig.defaults();
        if (gemini == null) gemini = ProviderConfig.defaults();
        if (moonshot == null) moonshot = ProviderConfig.defaults();
        if (minimax == null) minimax = ProviderConfig.defaults();
        if (minimaxAnthropic == null) minimaxAnthropic = ProviderConfig.defaults();
        if (mistral == null) mistral = ProviderConfig.defaults();
        if (stepfun == null) stepfun = ProviderConfig.defaults();
        if (xiaomiMimo == null) xiaomiMimo = ProviderConfig.defaults();
        if (longcat == null) longcat = ProviderConfig.defaults();
        if (antLing == null) antLing = ProviderConfig.defaults();
        if (aihubmix == null) aihubmix = ProviderConfig.defaults();
        if (siliconflow == null) siliconflow = ProviderConfig.defaults();
        if (novita == null) novita = ProviderConfig.defaults();
        if (volcengine == null) volcengine = ProviderConfig.defaults();
        if (volcengineCodingPlan == null) volcengineCodingPlan = ProviderConfig.defaults();
        if (byteplus == null) byteplus = ProviderConfig.defaults();
        if (byteplusCodingPlan == null) byteplusCodingPlan = ProviderConfig.defaults();
        if (openaiCodex == null) openaiCodex = ProviderConfig.defaults();
        if (githubCopilot == null) githubCopilot = ProviderConfig.defaults();
        if (qianfan == null) qianfan = ProviderConfig.defaults();
        if (nvidia == null) nvidia = ProviderConfig.defaults();
    }

    /** Get a provider config by its registry name (snake_case). */
    public HasProviderConfig getByName(String name) {
        return switch (name) {
            case "custom" -> custom;
            case "azure_openai" -> azureOpenai;
            case "bedrock" -> bedrock;
            case "anthropic" -> anthropic;
            case "openai" -> openai;
            case "openrouter" -> openrouter;
            case "assemblyai" -> assemblyai;
            case "huggingface" -> huggingface;
            case "skywork" -> skywork;
            case "deepseek" -> deepseek;
            case "groq" -> groq;
            case "zhipu" -> zhipu;
            case "dashscope" -> dashscope;
            case "vllm" -> vllm;
            case "ollama" -> ollama;
            case "lm_studio" -> lmStudio;
            case "atomic_chat" -> atomicChat;
            case "ovms" -> ovms;
            case "gemini" -> gemini;
            case "moonshot" -> moonshot;
            case "minimax" -> minimax;
            case "minimax_anthropic" -> minimaxAnthropic;
            case "mistral" -> mistral;
            case "stepfun" -> stepfun;
            case "xiaomi_mimo" -> xiaomiMimo;
            case "longcat" -> longcat;
            case "ant_ling" -> antLing;
            case "aihubmix" -> aihubmix;
            case "siliconflow" -> siliconflow;
            case "novita" -> novita;
            case "volcengine" -> volcengine;
            case "volcengine_coding_plan" -> volcengineCodingPlan;
            case "byteplus" -> byteplus;
            case "byteplus_coding_plan" -> byteplusCodingPlan;
            case "openai_codex" -> openaiCodex;
            case "github_copilot" -> githubCopilot;
            case "qianfan" -> qianfan;
            case "nvidia" -> nvidia;
            default -> null;
        };
    }

    /**
     * Mirrors Python _validate_api_type_scope — api_type != "auto" only valid on openai.
     */
    public void validateApiTypeScope() {
        var fields = ProvidersProperties.class.getRecordComponents();
        for (var field : fields) {
            if ("openai".equals(field.getName())) continue;
            try {
                var value = field.getAccessor().invoke(this);
                if (value instanceof HasProviderConfig pc && !"auto".equals(pc.apiType())) {
                    throw new IllegalArgumentException(
                            "providers.<name>.api_type is only supported for providers.openai");
                }
            } catch (ReflectiveOperationException e) {
                // skip unreadable fields
            }
        }
    }
}
