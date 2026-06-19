package com.nanobot.providers;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One LLM provider's metadata. Mirrors Python ProviderSpec dataclass field-for-field.
 */
public record ProviderSpec(
        String name,
        List<String> keywords,
        String envKey,
        String displayName,
        String backend,
        boolean isGateway,
        boolean isLocal,
        boolean isOauth,
        boolean isDirect,
        boolean isTranscriptionOnly,
        boolean supportsPromptCaching,
        boolean supportsMaxCompletionTokens,
        boolean stripModelPrefix,
        boolean reasoningAsContent,
        String thinkingStyle,
        String gatewayReasoningStyle,
        String detectByKeyPrefix,
        String detectByBaseKeyword,
        String defaultApiBase,
        List<EnvExtra> envExtras,
        List<ModelOverride> modelOverrides
) {

    /** Mirrors Python: self.display_name or self.name.title() */
    public String label() {
        if (!displayName.isEmpty()) return displayName;
        return Arrays.stream(name.split("_"))
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1))
                .collect(Collectors.joining(" "));
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static class Builder {
        private final String name;
        private List<String> keywords = List.of();
        private String envKey = "";
        private String displayName = "";
        private String backend = "openai_compat";
        private boolean isGateway;
        private boolean isLocal;
        private boolean isOauth;
        private boolean isDirect;
        private boolean isTranscriptionOnly;
        private boolean supportsPromptCaching;
        private boolean supportsMaxCompletionTokens;
        private boolean stripModelPrefix;
        private boolean reasoningAsContent;
        private String thinkingStyle = "";
        private String gatewayReasoningStyle = "";
        private String detectByKeyPrefix = "";
        private String detectByBaseKeyword = "";
        private String defaultApiBase = "";
        private List<EnvExtra> envExtras = List.of();
        private List<ModelOverride> modelOverrides = List.of();

        Builder(String name) { this.name = name; }

        public Builder keywords(String... kw) { this.keywords = List.of(kw); return this; }
        public Builder envKey(String v) { this.envKey = v; return this; }
        public Builder displayName(String v) { this.displayName = v; return this; }
        public Builder backend(String v) { this.backend = v; return this; }
        public Builder isGateway(boolean v) { this.isGateway = v; return this; }
        public Builder isLocal(boolean v) { this.isLocal = v; return this; }
        public Builder isOauth(boolean v) { this.isOauth = v; return this; }
        public Builder isDirect(boolean v) { this.isDirect = v; return this; }
        public Builder isTranscriptionOnly(boolean v) { this.isTranscriptionOnly = v; return this; }
        public Builder supportsPromptCaching(boolean v) { this.supportsPromptCaching = v; return this; }
        public Builder supportsMaxCompletionTokens(boolean v) { this.supportsMaxCompletionTokens = v; return this; }
        public Builder stripModelPrefix(boolean v) { this.stripModelPrefix = v; return this; }
        public Builder reasoningAsContent(boolean v) { this.reasoningAsContent = v; return this; }
        public Builder thinkingStyle(String v) { this.thinkingStyle = v; return this; }
        public Builder gatewayReasoningStyle(String v) { this.gatewayReasoningStyle = v; return this; }
        public Builder detectByKeyPrefix(String v) { this.detectByKeyPrefix = v; return this; }
        public Builder detectByBaseKeyword(String v) { this.detectByBaseKeyword = v; return this; }
        public Builder defaultApiBase(String v) { this.defaultApiBase = v; return this; }
        public Builder envExtras(EnvExtra... extras) { this.envExtras = List.of(extras); return this; }
        public Builder modelOverrides(ModelOverride... overrides) { this.modelOverrides = List.of(overrides); return this; }

        public ProviderSpec build() {
            return new ProviderSpec(name, keywords, envKey, displayName, backend,
                    isGateway, isLocal, isOauth, isDirect, isTranscriptionOnly,
                    supportsPromptCaching, supportsMaxCompletionTokens, stripModelPrefix,
                    reasoningAsContent, thinkingStyle, gatewayReasoningStyle,
                    detectByKeyPrefix, detectByBaseKeyword, defaultApiBase,
                    envExtras, modelOverrides);
        }
    }
}
