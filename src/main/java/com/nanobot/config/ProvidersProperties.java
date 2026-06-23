package com.nanobot.config;

import java.util.Map;

/**
 * LLM provider 配置集合。对标 Python: {@code nanobot/config/schema.py:196-246 class ProvidersConfig}
 */
public class ProvidersProperties {

    private ProviderProperties custom = ProviderProperties.DEFAULTS;
    private ProviderProperties azureOpenai = ProviderProperties.DEFAULTS;
    private BedrockProviderProperties bedrock = BedrockProviderProperties.DEFAULTS;
    private ProviderProperties anthropic = ProviderProperties.DEFAULTS;
    private ProviderProperties openai = ProviderProperties.DEFAULTS;
    private ProviderProperties openrouter = ProviderProperties.DEFAULTS;
    private ProviderProperties assemblyai = ProviderProperties.DEFAULTS;
    private ProviderProperties huggingface = ProviderProperties.DEFAULTS;
    private ProviderProperties skywork = ProviderProperties.DEFAULTS;
    private ProviderProperties deepseek = ProviderProperties.DEFAULTS;
    private ProviderProperties groq = ProviderProperties.DEFAULTS;
    private ProviderProperties zhipu = ProviderProperties.DEFAULTS;
    private ProviderProperties dashscope = ProviderProperties.DEFAULTS;
    private ProviderProperties vllm = ProviderProperties.DEFAULTS;
    private ProviderProperties ollama = ProviderProperties.DEFAULTS;
    private ProviderProperties lmStudio = ProviderProperties.DEFAULTS;
    private ProviderProperties atomicChat = ProviderProperties.DEFAULTS;
    private ProviderProperties ovms = ProviderProperties.DEFAULTS;
    private ProviderProperties gemini = ProviderProperties.DEFAULTS;
    private ProviderProperties moonshot = ProviderProperties.DEFAULTS;
    private ProviderProperties minimax = ProviderProperties.DEFAULTS;
    private ProviderProperties minimaxAnthropic = ProviderProperties.DEFAULTS;
    private ProviderProperties mistral = ProviderProperties.DEFAULTS;
    private ProviderProperties stepfun = ProviderProperties.DEFAULTS;
    private ProviderProperties xiaomiMimo = ProviderProperties.DEFAULTS;
    private ProviderProperties longcat = ProviderProperties.DEFAULTS;
    private ProviderProperties antLing = ProviderProperties.DEFAULTS;
    private ProviderProperties aihubmix = ProviderProperties.DEFAULTS;
    private ProviderProperties siliconflow = ProviderProperties.DEFAULTS;
    private ProviderProperties novita = ProviderProperties.DEFAULTS;
    private ProviderProperties volcengine = ProviderProperties.DEFAULTS;
    private ProviderProperties volcengineCodingPlan = ProviderProperties.DEFAULTS;
    private ProviderProperties byteplus = ProviderProperties.DEFAULTS;
    private ProviderProperties byteplusCodingPlan = ProviderProperties.DEFAULTS;
    private ProviderProperties openaiCodex = ProviderProperties.DEFAULTS;
    private ProviderProperties githubCopilot = ProviderProperties.DEFAULTS;
    private ProviderProperties qianfan = ProviderProperties.DEFAULTS;
    private ProviderProperties nvidia = ProviderProperties.DEFAULTS;

    public static final ProvidersProperties DEFAULTS = new ProvidersProperties();

    /**
     * 对标 Python: {@code getattr(self, name)} — 按 provider 名获取配置。
     * 兼容 snake_case（如 "azure_openai"）和 camelCase（如 "azureOpenai"）。
     */
    // 对标 Python ProvidersConfig 字段访问 — 按名称查找 provider 配置
    public ProviderProperties getByName(String name) {
        return switch (name) {
            case "custom" -> custom;
            case "azure_openai", "azureOpenai" -> azureOpenai;
            case "bedrock" -> bedrock.toGeneric();
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
            case "lm_studio", "lmStudio" -> lmStudio;
            case "atomic_chat", "atomicChat" -> atomicChat;
            case "ovms" -> ovms;
            case "gemini" -> gemini;
            case "moonshot" -> moonshot;
            case "minimax" -> minimax;
            case "minimax_anthropic", "minimaxAnthropic" -> minimaxAnthropic;
            case "mistral" -> mistral;
            case "stepfun" -> stepfun;
            case "xiaomi_mimo", "xiaomiMimo" -> xiaomiMimo;
            case "longcat" -> longcat;
            case "ant_ling", "antLing" -> antLing;
            case "aihubmix" -> aihubmix;
            case "siliconflow" -> siliconflow;
            case "novita" -> novita;
            case "volcengine" -> volcengine;
            case "volcengine_coding_plan", "volcengineCodingPlan" -> volcengineCodingPlan;
            case "byteplus" -> byteplus;
            case "byteplus_coding_plan", "byteplusCodingPlan" -> byteplusCodingPlan;
            case "openai_codex", "openaiCodex" -> openaiCodex;
            case "github_copilot", "githubCopilot" -> githubCopilot;
            case "qianfan" -> qianfan;
            case "nvidia" -> nvidia;
            default -> null;
        };
    }

    // --- Getters/Setters (37 个 provider) ---

    public ProviderProperties getCustom() { return custom; }
    public void setCustom(ProviderProperties custom) { this.custom = custom; }

    public ProviderProperties getAzureOpenai() { return azureOpenai; }
    public void setAzureOpenai(ProviderProperties azureOpenai) { this.azureOpenai = azureOpenai; }

    public BedrockProviderProperties getBedrock() { return bedrock; }
    public void setBedrock(BedrockProviderProperties bedrock) { this.bedrock = bedrock; }

    public ProviderProperties getAnthropic() { return anthropic; }
    public void setAnthropic(ProviderProperties anthropic) { this.anthropic = anthropic; }

    public ProviderProperties getOpenai() { return openai; }
    public void setOpenai(ProviderProperties openai) { this.openai = openai; }

    public ProviderProperties getOpenrouter() { return openrouter; }
    public void setOpenrouter(ProviderProperties openrouter) { this.openrouter = openrouter; }

    public ProviderProperties getAssemblyai() { return assemblyai; }
    public void setAssemblyai(ProviderProperties assemblyai) { this.assemblyai = assemblyai; }

    public ProviderProperties getHuggingface() { return huggingface; }
    public void setHuggingface(ProviderProperties huggingface) { this.huggingface = huggingface; }

    public ProviderProperties getSkywork() { return skywork; }
    public void setSkywork(ProviderProperties skywork) { this.skywork = skywork; }

    public ProviderProperties getDeepseek() { return deepseek; }
    public void setDeepseek(ProviderProperties deepseek) { this.deepseek = deepseek; }

    public ProviderProperties getGroq() { return groq; }
    public void setGroq(ProviderProperties groq) { this.groq = groq; }

    public ProviderProperties getZhipu() { return zhipu; }
    public void setZhipu(ProviderProperties zhipu) { this.zhipu = zhipu; }

    public ProviderProperties getDashscope() { return dashscope; }
    public void setDashscope(ProviderProperties dashscope) { this.dashscope = dashscope; }

    public ProviderProperties getVllm() { return vllm; }
    public void setVllm(ProviderProperties vllm) { this.vllm = vllm; }

    public ProviderProperties getOllama() { return ollama; }
    public void setOllama(ProviderProperties ollama) { this.ollama = ollama; }

    public ProviderProperties getLmStudio() { return lmStudio; }
    public void setLmStudio(ProviderProperties lmStudio) { this.lmStudio = lmStudio; }

    public ProviderProperties getAtomicChat() { return atomicChat; }
    public void setAtomicChat(ProviderProperties atomicChat) { this.atomicChat = atomicChat; }

    public ProviderProperties getOvms() { return ovms; }
    public void setOvms(ProviderProperties ovms) { this.ovms = ovms; }

    public ProviderProperties getGemini() { return gemini; }
    public void setGemini(ProviderProperties gemini) { this.gemini = gemini; }

    public ProviderProperties getMoonshot() { return moonshot; }
    public void setMoonshot(ProviderProperties moonshot) { this.moonshot = moonshot; }

    public ProviderProperties getMinimax() { return minimax; }
    public void setMinimax(ProviderProperties minimax) { this.minimax = minimax; }

    public ProviderProperties getMinimaxAnthropic() { return minimaxAnthropic; }
    public void setMinimaxAnthropic(ProviderProperties minimaxAnthropic) { this.minimaxAnthropic = minimaxAnthropic; }

    public ProviderProperties getMistral() { return mistral; }
    public void setMistral(ProviderProperties mistral) { this.mistral = mistral; }

    public ProviderProperties getStepfun() { return stepfun; }
    public void setStepfun(ProviderProperties stepfun) { this.stepfun = stepfun; }

    public ProviderProperties getXiaomiMimo() { return xiaomiMimo; }
    public void setXiaomiMimo(ProviderProperties xiaomiMimo) { this.xiaomiMimo = xiaomiMimo; }

    public ProviderProperties getLongcat() { return longcat; }
    public void setLongcat(ProviderProperties longcat) { this.longcat = longcat; }

    public ProviderProperties getAntLing() { return antLing; }
    public void setAntLing(ProviderProperties antLing) { this.antLing = antLing; }

    public ProviderProperties getAihubmix() { return aihubmix; }
    public void setAihubmix(ProviderProperties aihubmix) { this.aihubmix = aihubmix; }

    public ProviderProperties getSiliconflow() { return siliconflow; }
    public void setSiliconflow(ProviderProperties siliconflow) { this.siliconflow = siliconflow; }

    public ProviderProperties getNovita() { return novita; }
    public void setNovita(ProviderProperties novita) { this.novita = novita; }

    public ProviderProperties getVolcengine() { return volcengine; }
    public void setVolcengine(ProviderProperties volcengine) { this.volcengine = volcengine; }

    public ProviderProperties getVolcengineCodingPlan() { return volcengineCodingPlan; }
    public void setVolcengineCodingPlan(ProviderProperties volcengineCodingPlan) { this.volcengineCodingPlan = volcengineCodingPlan; }

    public ProviderProperties getByteplus() { return byteplus; }
    public void setByteplus(ProviderProperties byteplus) { this.byteplus = byteplus; }

    public ProviderProperties getByteplusCodingPlan() { return byteplusCodingPlan; }
    public void setByteplusCodingPlan(ProviderProperties byteplusCodingPlan) { this.byteplusCodingPlan = byteplusCodingPlan; }

    public ProviderProperties getOpenaiCodex() { return openaiCodex; }
    public void setOpenaiCodex(ProviderProperties openaiCodex) { this.openaiCodex = openaiCodex; }

    public ProviderProperties getGithubCopilot() { return githubCopilot; }
    public void setGithubCopilot(ProviderProperties githubCopilot) { this.githubCopilot = githubCopilot; }

    public ProviderProperties getQianfan() { return qianfan; }
    public void setQianfan(ProviderProperties qianfan) { this.qianfan = qianfan; }

    public ProviderProperties getNvidia() { return nvidia; }
    public void setNvidia(ProviderProperties nvidia) { this.nvidia = nvidia; }
}
