package com.nanobot.config;

/**
 * Mirrors Python CronSchedule from nanobot.cron.types.
 */
public record CronSchedule(String kind, String expr, long everyMs, String tz) {

    public static CronSchedule cron(String expr, String tz) {
        return new CronSchedule("cron", expr, 0, tz);
    }

    public static CronSchedule every(long everyMs, String tz) {
        return new CronSchedule("every", "", everyMs, tz);
    }
}
