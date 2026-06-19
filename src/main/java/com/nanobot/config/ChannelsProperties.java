package com.nanobot.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import java.util.HashMap;
import java.util.Map;

/**
 * Mirrors Python ChannelsConfig. extra="allow" enables per-channel custom fields.
 */
public record ChannelsProperties(
        Boolean sendProgress,
        Boolean sendToolHints,
        Boolean showReasoning,
        Boolean extractDocumentText,
        @Min(0) @Max(10) Integer sendMaxRetries,
        String transcriptionProvider,
        @Pattern(regexp = "^[a-z]{2,3}$") String transcriptionLanguage,
        Map<String, Object> extra
) {
    public ChannelsProperties {
        if (sendProgress == null) sendProgress = true;
        if (sendToolHints == null) sendToolHints = false;
        if (showReasoning == null) showReasoning = true;
        if (extractDocumentText == null) extractDocumentText = true;
        if (sendMaxRetries == null) sendMaxRetries = 3;
        if (transcriptionProvider == null) transcriptionProvider = "groq";
        if (transcriptionLanguage == null) transcriptionLanguage = null; // nullable
        if (extra == null) extra = new HashMap<>();
    }
}
