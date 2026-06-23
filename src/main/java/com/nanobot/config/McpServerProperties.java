package com.nanobot.config;

import java.util.List;
import java.util.Map;

/**
 * MCP server 连接配置。
 * 对标 Python: {@code nanobot/config/schema.py:273-284 class MCPServerConfig}
 */
public record McpServerProperties(
        String type,
        String command,
        List<String> args,
        Map<String, String> env,
        String cwd,
        String url,
        Map<String, String> headers,
        int toolTimeout,
        List<String> enabledTools) {

    public McpServerProperties {
        if (args == null) args = List.of();
        if (env == null) env = Map.of();
        if (headers == null) headers = Map.of();
        if (enabledTools == null) enabledTools = List.of("*");
    }
}
