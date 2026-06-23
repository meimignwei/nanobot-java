package com.nanobot.agent.tools.impl;

import com.nanobot.agent.tools.*;
import com.nanobot.agent.tools.schema.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 定时任务调度工具，支持添加、列出和删除 cron 作业。
 *
 * <p>对标 Python {@code nanobot/agent/tools/cron.py CronTool}（296 行）。
 * 三种动作：add（含 schedule）、list（枚举）、remove（按 job_id 删除）。
 * 作业在 cron 上下文中触发，通过 agent loop 路由回传。
 *
 * <p>依赖 package 08 的 CronService，当前为桩实现。
 */
public class CronTool extends Tool {

    /** 工具参数 JSON Schema。 */
    // 对标 Python CronTool._CRON_PARAMETERS
    private static final Map<String, Object> PARAMETERS =
            ToolParametersSchema.create(
                    List.of("action"),
                    "Action-specific parameters: add requires a non-empty message "
                            + "plus one schedule (every_seconds, cron_expr, or at); "
                            + "remove requires job_id; list only needs action. "
                            + "Per-action requirements are enforced at runtime.",
                    Map.of(
                            "action", new StringSchema(
                                    "Action to perform",
                                    null, null,
                                    List.of("add", "list", "remove"), false),
                            "name", new StringSchema(
                                    "Optional short human-readable label for the job "
                                            + "(e.g., 'weather-monitor', 'daily-standup'). "
                                            + "Defaults to first 30 chars of message.",
                                    null, null, null, true),
                            "message", new StringSchema(
                                    "REQUIRED when action='add'. Instruction for the agent "
                                            + "to execute when the job triggers. Not used "
                                            + "for action='list' or action='remove'.",
                                    null, null, null, true),
                            "every_seconds", new IntegerSchema(0,
                                    "Interval in seconds (for recurring tasks)",
                                    0, Integer.MAX_VALUE, null, true),
                            "cron_expr", new StringSchema(
                                    "Cron expression like '0 9 * * *' "
                                            + "(for scheduled tasks)",
                                    null, null, null, true),
                            "tz", new StringSchema(
                                    "Optional IANA timezone for cron expressions "
                                            + "(e.g. 'America/Vancouver'). "
                                            + "When omitted, the tool's default timezone applies.",
                                    null, null, null, true),
                            "at", new StringSchema(
                                    "ISO datetime for one-time execution "
                                            + "(e.g. '2026-02-12T10:30:00'). "
                                            + "Naive values use the tool's default timezone.",
                                    null, null, null, true),
                            "deliver", new BooleanSchema(
                                    "Whether to deliver the execution result to the "
                                            + "user channel (default true)",
                                    true, true),
                            "job_id", new StringSchema(
                                    "REQUIRED when action='remove'. Job ID to remove "
                                            + "(obtain via action='list').",
                                    null, null, null, true)
                    )
            );

    public CronTool() {}

    @Override
    public String getName() { return "cron"; }

    @Override
    public String getDescription() {
        return "Schedule reminders and recurring tasks. Actions: add, list, remove. "
                + "If tz is omitted, cron expressions and naive ISO times default to UTC.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolParameters.deepCopy(PARAMETERS);
    }

    @Override
    public boolean isReadOnly() { return false; }

    @Override
    public Set<String> getScopes() { return Set.of("core"); }

    /**
     * 校验参数：add 动作需要 message，remove 动作需要 job_id。
     *
     * @param params 待校验的参数 Map
     * @return 错误消息列表
     */
    @Override
    // 对标 Python CronTool.validate_params()
    public List<String> validateParams(Map<String, Object> params) {
        List<String> errors = new ArrayList<>(super.validateParams(params));
        String action = paramString(params, "action");
        if ("add".equals(action)) {
            String message = paramString(params, "message");
            if (message == null || message.isBlank()) {
                errors.add("message is required when action='add'");
            }
        }
        if ("remove".equals(action)) {
            String jobId = paramString(params, "job_id");
            if (jobId == null || jobId.isBlank()) {
                errors.add("job_id is required when action='remove'");
            }
        }
        return errors;
    }

    /**
     * 执行 cron 操作：add / list / remove。
     *
     * @param params 已校验的工具参数
     * @return 操作结果的 CompletableFuture
     */
    @Override
    // 对标 Python CronTool.execute()
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            String action = paramString(params, "action", "list");
            return switch (action) {
                case "add" -> addJob(params);
                case "list" -> listJobs();
                case "remove" -> removeJob(params);
                default -> "Unknown action: " + action;
            };
        });
    }

    /**
     * 添加定时作业。
     *
     * @param params 工具参数
     * @return 操作结果字符串
     */
    // 对标 Python CronTool._add_job()
    private String addJob(Map<String, Object> params) {
        String message = paramString(params, "message");
        if (message == null || message.isBlank()) {
            return "Error: cron action='add' requires a non-empty 'message' parameter "
                    + "describing what to do when the job triggers "
                    + "(e.g. the reminder text). Retry including message=\"...\".";
        }
        // 校验至少一个调度参数
        boolean hasEverySeconds = paramInt(params, "every_seconds") != null
                && paramInt(params, "every_seconds") > 0;
        String cronExpr = paramString(params, "cron_expr");
        String at = paramString(params, "at");
        if (!hasEverySeconds && (cronExpr == null || cronExpr.isEmpty())
                && (at == null || at.isEmpty())) {
            return "Error: either every_seconds, cron_expr, or at is required";
        }
        // tz 只能与 cron_expr 配合使用
        String tz = paramString(params, "tz");
        if (tz != null && !tz.isEmpty() && (cronExpr == null || cronExpr.isEmpty())) {
            return "Error: tz can only be used with cron_expr";
        }
        return "Cron job add: [CronService integration pending - package 08]";
    }

    /**
     * 列出当前 chat 的所有活跃作业。
     *
     * @return 作业列表字符串
     */
    // 对标 Python CronTool._list_jobs()
    private String listJobs() {
        return "Cron job list: [CronService integration pending - package 08]";
    }

    /**
     * 按 job_id 删除作业。
     *
     * @param params 工具参数
     * @return 删除结果字符串
     */
    // 对标 Python CronTool._remove_job()
    private String removeJob(Map<String, Object> params) {
        String jobId = paramString(params, "job_id");
        if (jobId == null || jobId.isBlank()) {
            return "Error: job_id is required for remove";
        }
        return "Cron job remove '" + jobId + "': [CronService integration pending - package 08]";
    }

    // ==================== 参数辅助 ====================

    private static String paramString(Map<String, Object> params, String key) {
        Object val = params.get(key);
        return val instanceof String s ? s : null;
    }

    private static String paramString(Map<String, Object> params,
                                       String key, String def) {
        Object val = params.get(key);
        return (val instanceof String s && !s.isEmpty()) ? s : def;
    }

    private static Integer paramInt(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val instanceof Integer i) return i;
        if (val instanceof Number n) return n.intValue();
        return null;
    }
}
