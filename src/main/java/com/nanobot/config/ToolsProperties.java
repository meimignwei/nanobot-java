package com.nanobot.config;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;
import java.util.Map;

/**
 * 工具配置。对标 Python: {@code nanobot/config/schema.py:294-320 class ToolsConfig}
 *
 * <p>工具子配置（web、exec、cli_apps、my、image_generation）在对应工具模块中定义，
 * 此处用 {@code Map<String, Object>} 占位以解决循环引用。见 06/07-agent-tools。
 */
public record ToolsProperties(
        Map<String, Object> web,
        Map<String, Object> exec,
        Map<String, Object> cliApps,
        Map<String, Object> my,
        Map<String, Object> imageGeneration,
        boolean restrictToWorkspace,
        @JsonAlias({"webuiAllowLocalServiceAccess", "webui_allow_local_service_access",
                "allowLocalPreviewAccess", "allow_local_preview_access"})
        boolean webuiAllowLocalServiceAccess,
        Map<String, McpServerProperties> mcpServers,
        List<String> ssrfWhitelist) {

    public static final ToolsProperties DEFAULTS = new ToolsProperties(
            Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
            false, true, Map.of(), List.of());
}
