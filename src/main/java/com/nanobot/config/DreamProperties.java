package com.nanobot.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.nanobot.cron.types.CronSchedule;

/**
 * Dream memory consolidation 配置。
 * 对标 Python: {@code nanobot/config/schema.py:57-84 class DreamConfig}
 */
public record DreamProperties(
        boolean enabled,
        int intervalH,
        String cron,
        @JsonAlias({"modelOverride", "model", "model_override"})
        String modelOverride,
        int maxBatchSize,
        int maxIterations,
        boolean annotateLineAges) {

    private static final int HOUR_MS = 3_600_000;

    public static final DreamProperties DEFAULTS = new DreamProperties(
            true, 2, null, null, 20, 15, true);

    /**
     * 根据配置构建 {@link CronSchedule}：若显式设置了 cron 表达式则使用 cron 模式，
     * 否则按 intervalH 构建固定间隔调度。
     *
     * @param timezone 时区标识符（如 "Asia/Shanghai"）
     * @return 构建好的 CronSchedule 实例
     */
    // 对标 Python DreamConfig:73-77 build_schedule()
    public CronSchedule buildSchedule(String timezone) {
        if (cron != null && !cron.isEmpty()) {
            return CronSchedule.cron(timezone, cron);
        }
        return CronSchedule.every(timezone, (long) intervalH * HOUR_MS);
    }

    /**
     * 返回人类可读的调度描述字符串，用于日志和 CLI 展示。
     *
     * @return 如 "every 2h" 或 "cron 0 3 * * * (legacy)" 的描述
     */
    // 对标 Python DreamConfig:79-84 describe_schedule()
    public String describeSchedule() {
        if (cron != null && !cron.isEmpty()) {
            return "cron " + cron + " (legacy)";
        }
        return "every " + intervalH + "h";
    }
}
