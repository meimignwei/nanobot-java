package com.nanobot.config;

import jakarta.validation.constraints.Min;

/**
 * Dream memory consolidation configuration. Mirrors Python DreamConfig.
 */
public record DreamProperties(
        Boolean enabled,
        @Min(1) Integer intervalH,
        String cron,
        String modelOverride,
        @Min(1) Integer maxBatchSize,
        @Min(1) Integer maxIterations,
        Boolean annotateLineAges
) {
    @Override
    public String cron() {
        return cron != null && !cron.isEmpty() ? cron : null;
    }

    public DreamProperties {
        if (enabled == null) enabled = true;
        if (intervalH == null) intervalH = 2;
        if (maxBatchSize == null) maxBatchSize = 20;
        if (maxIterations == null) maxIterations = 15;
        if (annotateLineAges == null) annotateLineAges = true;
    }

    public static DreamProperties defaults() {
        return new DreamProperties(true, 2, null, null, 20, 15, true);
    }

    public CronSchedule buildSchedule(String timezone) {
        if (cron() != null) {
            return CronSchedule.cron(cron(), timezone);
        }
        return CronSchedule.every(intervalH * HOUR_MS, timezone);
    }

    public String describeSchedule() {
        if (cron() != null) {
            return "cron " + cron() + " (legacy)";
        }
        return "every " + intervalH + "h";
    }

    private static final long HOUR_MS = 3_600_000L;
}
