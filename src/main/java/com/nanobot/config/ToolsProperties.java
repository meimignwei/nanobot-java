package com.nanobot.config;

import java.util.List;
import java.util.Map;

/**
 * Mirrors Python ToolsConfig. Tool-specific sub-configs are loaded lazily
 * (like Python's _lazy_default) via Spring's provider-registry pattern.
 * For now, we store the core fields; tool sub-configs are plain Maps.
 */
public record ToolsProperties(
        Map<String, Object> web,
        Map<String, Object> exec,
        Map<String, Object> cliApps,
        Map<String, Object> my,
        Map<String, Object> imageGeneration,
        Boolean restrictToWorkspace,
        Boolean webuiAllowLocalServiceAccess,
        Map<String, McpServerProperties> mcpServers,
        List<String> ssrfWhitelist
) {
    public ToolsProperties {
        if (web == null) web = Map.of();
        if (exec == null) exec = Map.of();
        if (cliApps == null) cliApps = Map.of();
        if (my == null) my = Map.of();
        if (imageGeneration == null) imageGeneration = Map.of();
        if (restrictToWorkspace == null) restrictToWorkspace = false;
        if (webuiAllowLocalServiceAccess == null) webuiAllowLocalServiceAccess = true;
        if (mcpServers == null) mcpServers = Map.of();
        if (ssrfWhitelist == null) ssrfWhitelist = List.of();
    }
}
