package com.nanobot.agent.tools.impl;

import java.util.regex.Pattern;

/**
 * 网络安全检查工具，用于 SSRF 和内部 URL 检测。
 *
 * <p>对标 Python {@code nanobot/security/network.py}。
 * 当前为占位实现，后续在 package 16-security 中完成完整版本。
 */
public final class NetworkSecurity {

    private NetworkSecurity() {}

    /** 内部/私有 IP 地址模式。 */
    private static final Pattern INTERNAL_IP = Pattern.compile(
            "\\b(?:127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"
                    + "|10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"
                    + "|172\\.(?:1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}"
                    + "|192\\.168\\.\\d{1,3}\\.\\d{1,3}"
                    + "|169\\.254\\.\\d{1,3}\\.\\d{1,3}"
                    + "|\\[?::1]?)\\b");

    /** localhost 主机名模式。 */
    private static final Pattern LOCALHOST = Pattern.compile(
            "\\blocalhost\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 检查字符串中是否包含内部/私有 URL。
     *
     * @param text                         待检查的文本
     * @param allowLocalServiceAccess 是否允许本地服务访问
     * @return 包含内部 URL 返回 true
     */
    // 对标 Python contains_internal_url()
    public static boolean containsInternalUrl(String text,
                                               boolean allowLocalServiceAccess) {
        if (allowLocalServiceAccess) return false;
        if (INTERNAL_IP.matcher(text).find()) return true;
        if (LOCALHOST.matcher(text).find()) return true;
        return false;
    }
}
