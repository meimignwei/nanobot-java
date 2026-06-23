package com.nanobot.agent.tools.impl;

import com.nanobot.agent.tools.Tool;
import com.nanobot.agent.tools.ToolParameters;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 包装 MCP prompt 模板为可调用工具。
 *
 * <p>对标 Python {@code nanobot/agent/tools/mcp.py MCPPromptWrapper}。
 * 暴露参数 Schema，委托给 {@code session.getPrompt(promptName, arguments)}。
 *
 * <p>由 MCP 集成代码在连接时动态注册。
 */
public class MCPPromptWrapper extends Tool {

    private final String mcpServerName;
    private final String promptName;
    private final String description;
    private final Map<String, Object> parameters;

    /**
     * 构造 MCPPromptWrapper。
     *
     * @param mcpServerName MCP 服务器名称
     * @param promptName    prompt 模板名称
     * @param description   prompt 描述
     * @param parameters    prompt 参数 Schema
     */
    // 对标 Python MCPPromptWrapper.__init__()
    public MCPPromptWrapper(String mcpServerName, String promptName,
                            String description, Map<String, Object> parameters) {
        this.mcpServerName = mcpServerName;
        this.promptName = promptName;
        this.description = description;
        this.parameters = Map.copyOf(parameters);
    }

    @Override
    public String getName() { return MCPToolWrapper.sanitizeName(promptName); }

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
     * 获取 MCP prompt 内容。
     *
     * @param params 已校验的工具参数
     * @return prompt 渲染结果的 CompletableFuture
     */
    @Override
    // 对标 Python MCPPromptWrapper.execute()
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() ->
                "MCP prompt '" + promptName + "' on server '"
                        + mcpServerName + "': [MCP integration pending - package 10]");
    }
}
