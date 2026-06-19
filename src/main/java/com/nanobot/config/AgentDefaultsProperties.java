package com.nanobot.config;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;

/**
 * Mirrors Python AgentDefaults (excluding fallback_models which is typed as
 * List<FallbackCandidate>, handled as Object in properties then resolved later).
 */
public record AgentDefaultsProperties(
        String workspace,
        @Nullable String modelPreset,
        String model,
        String provider,
        Integer maxTokens,
        Integer contextWindowTokens,
        @Nullable Integer contextBlockLimit,
        Double temperature,
        List<Object> fallbackModels,   // String | InlineFallbackConfig
        Integer maxToolIterations,
        @Min(1) Integer maxConcurrentSubagents,
        Integer maxToolResultChars,
        String providerRetryMode,
        @Min(20) @Max(500) Integer toolHintMaxLength,
        @Nullable String reasoningEffort,
        String timezone,
        String botName,
        String botIcon,
        Boolean unifiedSession,
        List<String> disabledSkills,
        @Min(0) Integer sessionTtlMinutes,
        @Min(0) Integer maxMessages,
        @DecimalMin("0.1") @DecimalMax("0.95") Double consolidationRatio,
        DreamProperties dream
) {
    public AgentDefaultsProperties {
        if (workspace == null) workspace = "~/.nanobot-java/workspace";
        if (model == null) model = "anthropic/claude-opus-4-5";
        if (provider == null) provider = "auto";
        if (maxTokens == null) maxTokens = 8192;
        if (contextWindowTokens == null) contextWindowTokens = 65_536;
        if (temperature == null) temperature = 0.1;
        if (fallbackModels == null) fallbackModels = List.of();
        if (maxToolIterations == null) maxToolIterations = 200;
        if (maxConcurrentSubagents == null) maxConcurrentSubagents = 1;
        if (maxToolResultChars == null) maxToolResultChars = 16_000;
        if (providerRetryMode == null) providerRetryMode = "standard";
        if (toolHintMaxLength == null) toolHintMaxLength = 40;
        if (timezone == null) timezone = "UTC";
        if (botName == null) botName = "nanobot-java";
        if (botIcon == null) botIcon = "🐈"; // 🐈
        if (unifiedSession == null) unifiedSession = false;
        if (disabledSkills == null) disabledSkills = List.of();
        if (sessionTtlMinutes == null) sessionTtlMinutes = 0;
        if (maxMessages == null) maxMessages = 120;
        if (consolidationRatio == null) consolidationRatio = 0.5;
        if (dream == null) dream = DreamProperties.defaults();
    }
}
