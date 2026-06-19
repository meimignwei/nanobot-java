package com.nanobot.config;

import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * Mirrors Python MCPServerConfig.
 */
public record McpServerProperties(
        @Nullable String type,
        String command,
        List<String> args,
        Map<String, String> env,
        String cwd,
        String url,
        Map<String, String> headers,
        Integer toolTimeout,
        List<String> enabledTools
) {
    public McpServerProperties {
        if (command == null) command = "";
        if (args == null) args = List.of();
        if (env == null) env = Map.of();
        if (cwd == null) cwd = "";
        if (url == null) url = "";
        if (headers == null) headers = Map.of();
        if (toolTimeout == null) toolTimeout = 30;
        if (enabledTools == null) enabledTools = List.of("*");
    }
}
