package com.nanobot.config;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/**
 * Mirrors Python TranscriptionConfig.
 */
public record TranscriptionProperties(
        Boolean enabled,
        @Nullable String provider,
        @Nullable String model,
        @Nullable @Pattern(regexp = "^[a-z]{2,3}$") String language,
        @Min(1) @Max(600) Integer maxDurationSec,
        @Min(1) @Max(100) Integer maxUploadMb
) {
    public TranscriptionProperties {
        if (enabled == null) enabled = true;
        if (maxDurationSec == null) maxDurationSec = 120;
        if (maxUploadMb == null) maxUploadMb = 25;
    }
}
