package com.nanobot.agent.tools.impl;

import com.nanobot.agent.tools.Tool;
import com.nanobot.agent.tools.ToolParameters;
import com.nanobot.agent.tools.schema.StringSchema;
import com.nanobot.agent.tools.schema.ToolParametersSchema;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 包装 MCP 资源（URI 可寻址数据）为可调用工具。
 *
 * <p>对标 Python {@code nanobot/agent/tools/mcp.py MCPResourceWrapper}。
 * 暴露包含 {@code uri} 参数的 Schema，委托给 {@code session.readResource(uri)}。
 *
 * <p>由 MCP 集成代码在连接时动态注册。
 */
public class MCPResourceWrapper extends Tool {

    private final String mcpServerName;
    private final String resourceUri;
    private final String resourceName;
    private final String description;
    private final String mimeType;
    private final Map<String, Object> parameters;

    /**
     * 构造 MCPResourceWrapper。
     *
     * @param mcpServerName MCP 服务器名称
     * @param resourceUri   资源 URI
     * @param resourceName  资源名称
     * @param description   资源描述
     * @param mimeType      MIME 类型
     */
    // 对标 Python MCPResourceWrapper.__init__()
    public MCPResourceWrapper(String mcpServerName, String resourceUri,
                              String resourceName, String description,
                              String mimeType) {
        this.mcpServerName = mcpServerName;
        this.resourceUri = resourceUri;
        this.resourceName = resourceName;
        this.description = description;
        this.mimeType = mimeType;

        this.parameters = ToolParametersSchema.create(
                null, null,
                Map.of(
                        "uri", new StringSchema("Resource URI to read")
                )
        );
    }

    @Override
    public String getName() { return MCPToolWrapper.sanitizeName(resourceName); }

    @Override
    public String getDescription() { return description; }

    @Override
    public Map<String, Object> getParameters() {
        return ToolParameters.deepCopy(parameters);
    }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public Set<String> getScopes() { return Set.of("mcp_" + mcpServerName); }

    /**
     * 读取 MCP 资源。
     *
     * @param params 已校验的工具参数
     * @return 资源内容的 CompletableFuture
     */
    @Override
    // 对标 Python MCPResourceWrapper.execute()
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() ->
                "MCP resource '" + resourceName + "' (uri: " + resourceUri + "): "
                        + "[MCP integration pending - package 10]");
    }
}
