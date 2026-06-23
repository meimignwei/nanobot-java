package com.nanobot.cron.types;

/**
 * 定时任务调度配置。
 * 对标 Python: {@code nanobot/cron/types.py} 中的 CronSchedule
 *
 * <p>完整实现见 18-remaining.md。此处为 02-config 所需的最小 stub。
 */
public record CronSchedule(
        String kind,
        Object interval,
        String tz) {

    /**
     * 创建固定间隔调度（"every" 模式）。
     *
     * @param tz      时区标识符
     * @param everyMs 间隔毫秒数
     * @return kind="every"、interval 为毫秒数的 CronSchedule
     */
    // 对标 Python DreamConfig.build_schedule() — every 类型
    public static CronSchedule every(String tz, long everyMs) {
        return new CronSchedule("every", everyMs, tz);
    }

    /**
     * 创建 cron 表达式调度（"cron" 模式）。
     *
     * @param tz   时区标识符
     * @param expr cron 表达式字符串
     * @return kind="cron"、interval 为表达式的 CronSchedule
     */
    // 对标 Python DreamConfig.build_schedule() — cron 类型
    public static CronSchedule cron(String tz, String expr) {
        return new CronSchedule("cron", expr, tz);
    }
}
