package com.nanobot.agent.tools.impl;

import com.nanobot.agent.tools.Tool;
import com.nanobot.agent.tools.ToolParameters;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 包装单个 MCP 服务器工具，将执行委托给 MCP 客户端会话。
 *
 * <p>对标 Python {@code nanobot/agent/tools/mcp.py MCPToolWrapper}。
 * Schema 在注册时预先规范化以兼容 OpenAI function calling 格式。
 * 支持临时错误重试（ConnectionReset、BrokenPipe 等触发单次重连）。
 *
 * <p>此工具不由 {@code ToolLoader} 自动发现，而是通过 MCP 集成代码在
 * MCP 服务器连接时动态注册到 {@code ToolRegistry}。
 */
public class MCPToolWrapper extends Tool {

    private final String mcpServerName;
    private final String toolName;
    private final String description;
    private final Map<String, Object> parameters;
    private final boolean readOnly;

    /**
     * 构造 MCPToolWrapper。
     *
     * @param mcpServerName MCP 服务器名称
     * @param toolName      工具原始名称（已做名称净化处理）
     * @param description   工具描述
     * @param parameters    已规范化的 JSON Schema 参数定义
     * @param readOnly      工具是否无副作用
     */
    // 对标 Python MCPToolWrapper.__init__()
    public MCPToolWrapper(String mcpServerName, String toolName,
                          String description, Map<String, Object> parameters,
                          boolean readOnly) {
        this.mcpServerName = mcpServerName;
        this.toolName = toolName;
        this.description = description;
        this.parameters = Map.copyOf(parameters);
        this.readOnly = readOnly;
    }

    @Override
    public String getName() { return toolName; }

    @Override
    public String getDescription() { return description; }

    @Override
    public Map<String, Object> getParameters() {
        return ToolParameters.deepCopy(parameters);
    }

    @Override
    public boolean isReadOnly() { return readOnly; }

    @Override
    public Set<String> getScopes() { return Set.of("mcp_" + mcpServerName); }

    /**
     * 执行工具调用，委托给 MCP 客户端会话。
     *
     * @param params 已校验的工具参数
     * @return MCP 调用结果的 CompletableFuture
     */
    @Override
    // 对标 Python MCPToolWrapper.execute()
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() ->
                "MCP tool '" + toolName + "' on server '"
                        + mcpServerName + "': [MCP integration pending - package 10]");
    }

    /**
     * 净化工具名称，将非字母数字字符替换为下划线并压缩连续下划线。
     * 对标 Python {@code re.sub(r"[^a-zA-Z0-9_-]", "_", name)}。
     *
     * @param name 原始名称
     * @return 净化后的名称
     */
    // 对标 Python MCP tool name sanitization
    public static String sanitizeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_")
                .replaceAll("_+", "_");
    }
}
