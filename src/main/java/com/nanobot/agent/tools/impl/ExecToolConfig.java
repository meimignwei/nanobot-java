package com.nanobot.agent.tools.impl;

import java.util.List;

/**
 * ExecTool 配置 record，对标 Python {@code ExecToolConfig} Pydantic 模型。
 *
 * @param enable                       主开关
 * @param timeout                      硬超时秒数（0 = 无限制）
 * @param pathPrepend                  追加到 PATH 之前的路径
 * @param pathAppend                   追加到 PATH 之后的路径
 * @param sandbox                      sandbox 后端名，如 "bwrap"
 * @param allowedEnvKeys               允许传递给子进程的环境变量名列表
 * @param allowPatterns                白名单正则模式（优先于 deny）
 * @param denyPatterns                 额外的 deny 正则模式
 * @param webuiAllowLocalServiceAccess 是否允许访问本地服务（SSRF 例外）
 */
// 对标 Python ExecToolConfig
public record ExecToolConfig(
        boolean enable,
        int timeout,
        String pathPrepend,
        String pathAppend,
        String sandbox,
        List<String> allowedEnvKeys,
        List<String> allowPatterns,
        List<String> denyPatterns,
        boolean webuiAllowLocalServiceAccess
) {
    public ExecToolConfig {
        if (allowedEnvKeys == null) allowedEnvKeys = List.of();
        if (allowPatterns == null) allowPatterns = List.of();
        if (denyPatterns == null) denyPatterns = List.of();
    }

    /** 返回生产环境默认配置（enable=true, timeout=60s, sandbox=null）。 */
    public static ExecToolConfig defaults() {
        return new ExecToolConfig(
                true, 60, null, null, null,
                List.of(), List.of(), List.of(), false);
    }
}
