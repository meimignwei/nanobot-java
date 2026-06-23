package com.nanobot;

import com.nanobot.agent.AgentLoop;
import com.nanobot.agent.hook.AgentHook;
import com.nanobot.agent.hook.SDKCaptureHook;
import com.nanobot.bus.OutboundMessage;
import com.nanobot.config.Config;
import com.nanobot.config.ConfigLoader;
import com.nanobot.providers.image_generation.ImageGenerationProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * nanobot 的编程式 SDK 门面，薄封装 AgentLoop，对外提供简洁的配置→运行→结果收集接口。
 *
 * <p>真正的组件装配（MessageBus、SessionManager、providers、tools 等）在
 * {@link AgentLoop#fromConfig} 中完成，本类只负责配置加载、hook 注入、结果捕获。
 *
 * <p>对标 Python {@code nanobot/nanobot.py:23-113 class Nanobot}。
 */
public class Nanobot implements AsyncCloseable {

    /** 内部的 AgentLoop 实例，对标 Python {@code nanobot.py:34 self._loop} */
    private final AgentLoop loop;

    /**
     * 使用已有的 AgentLoop 构造 SDK 门面。
     *
     * @param loop 已装配的 AgentLoop 实例
     */
    // 对标 Python nanobot.py:33-34 __init__(self, loop)
    public Nanobot(AgentLoop loop) {
        this.loop = loop;
    }

    // ── fromConfig 工厂方法（同步）─────────────────────────────────────────

    /**
     * 使用默认配置（~/.nanobot/config.json）创建 Nanobot 实例。
     *
     * @return 配置完成的 Nanobot 实例
     */
    // 对标 Python nanobot.py from_config() 无参重载
    public static Nanobot fromConfig() {
        return fromConfig(null, null);
    }

    /**
     * 从指定配置文件创建 Nanobot 实例。
     *
     * @param configPath 配置文件路径，{@code null} 则使用默认路径
     * @return 配置完成的 Nanobot 实例
     */
    // 对标 Python nanobot.py from_config(config_path) 重载
    public static Nanobot fromConfig(Path configPath) {
        return fromConfig(configPath, null);
    }

    /**
     * 从指定配置文件创建 Nanobot 实例，可选覆盖 workspace 目录。
     *
     * <p>流程：
     * <ol>
     *   <li>解析并展开配置文件路径（~ → 用户主目录）</li>
     *   <li>{@code ConfigLoader.load()} 加载 JSON + 迁移旧格式 + 解析 {@code ${VAR}} 引用</li>
     *   <li>若指定 workspace，覆盖 config.agents.defaults.workspace</li>
     *   <li>{@code AgentLoop.fromConfig()} 装配所有内部组件</li>
     * </ol>
     *
     * @param configPath 配置文件路径，{@code null} 则使用 ~/.nanobot/config.json
     * @param workspace  覆盖的 workspace 目录，{@code null} 则使用配置中的值
     * @return 配置完成并装配好内部组件的 Nanobot 实例
     * @throws IllegalArgumentException 配置文件不存在时抛出
     */
    // 对标 Python nanobot.py:36-69 from_config(config_path, *, workspace)
    public static Nanobot fromConfig(Path configPath, Path workspace) {
        Path resolvedConfig = null;
        if (configPath != null) {
            resolvedConfig = expandUser(configPath).toAbsolutePath().normalize();
            if (!Files.exists(resolvedConfig)) {
                throw new IllegalArgumentException("Config not found: " + resolvedConfig);
            }
        }

        // 对标 Python nanobot.py:59 — load_config() 内部已调用 resolve_config_env_vars
        Config config = ConfigLoader.load(resolvedConfig);

        // 对标 Python nanobot.py:60-63 — 可选覆盖 workspace
        if (workspace != null) {
            config.getAgents().getDefaults().setWorkspace(
                    expandUser(workspace).toAbsolutePath().normalize().toString());
        }

        // 对标 Python nanobot.py:65-69 — AgentLoop.from_config(...)
        AgentLoop loop = AgentLoop.fromConfig(config, Map.of(
                "imageGenerationProviderConfigs",
                ImageGenerationProvider.providerConfigs(config)));
        return new Nanobot(loop);
    }

    // ── run（异步）─────────────────────────────────────────────────────────

    /**
     * 使用默认 session key "sdk:default" 运行 agent。
     *
     * @param message 用户输入消息
     * @return 异步运行结果，包含 agent 回复文本、使用的工具列表、消息快照
     */
    // 对标 Python nanobot.py:71 run(message) 重载
    public CompletableFuture<RunResult> run(String message) {
        return run(message, "sdk:default", null);
    }

    /**
     * 使用指定 session key 运行 agent。不同 key 拥有独立会话历史。
     *
     * @param message    用户输入消息
     * @param sessionKey 会话标识符，用于隔离不同对话上下文
     * @return 异步运行结果
     */
    // 对标 Python nanobot.py:71 run(message, *, session_key) 重载
    public CompletableFuture<RunResult> run(String message, String sessionKey) {
        return run(message, sessionKey, null);
    }

    /**
     * 运行 agent 并通过 SDKCaptureHook 收集结果，支持自定义生命周期 hook。
     *
     * <p>Hook 注入与恢复逻辑与 Python 源码完全一致：
     * <ol>
     *   <li>创建 SDKCaptureHook 放在 hook 链最前面以捕获工具调用和消息</li>
     *   <li>保存当前 extra hooks，临时替换为 [capture, ...baseHooks]</li>
     *   <li>调用 loop.processDirect 驱动一轮对话</li>
     *   <li>在 handle 回调中恢复原始 extra hooks（无论成功或失败）</li>
     *   <li>从 capture hook 提取 tools_used 和 messages 构建 RunResult</li>
     * </ol>
     *
     * @param message    用户输入消息
     * @param sessionKey 会话标识符，按 session 串行化并发请求
     * @param hooks      额外的自定义 hook 列表，{@code null} 表示仅使用 capture hook
     * @return 异步运行结果，含最终文本、工具调用列表、消息快照
     */
    // 对标 Python nanobot.py:71-102 run(message, *, session_key, hooks)
    public CompletableFuture<RunResult> run(String message, String sessionKey, List<AgentHook> hooks) {
        SDKCaptureHook capture = new SDKCaptureHook();
        List<AgentHook> prev = loop.getExtraHooks();
        List<AgentHook> baseHooks = hooks != null
                ? new ArrayList<>(hooks)
                : new ArrayList<>(prev != null ? prev : List.of());
        List<AgentHook> combined = new ArrayList<>();
        combined.add(capture);
        combined.addAll(baseHooks);
        loop.setExtraHooks(combined);

        return loop.processDirect(message, sessionKey)
                .handle((response, ex) -> {
                    loop.setExtraHooks(prev);
                    if (ex != null) {
                        throw new CompletionException(ex);
                    }
                    String raw = response != null ? response.content() : null;
                    String contentValue = (raw == null || raw.isEmpty()) ? "" : raw;
                    return new RunResult(contentValue, capture.getToolsUsed(), capture.getMessages());
                });
    }

    // ── 资源释放（异步）─────────────────────────────────────────────────────

    /**
     * 异步关闭并释放 MCP 连接、后台任务等资源。
     * Python 的 close_mcp 内部会 await asyncio.gather() 等待所有后台任务
     * 并逐连接 await stack.aclose()；Java 中代理给 AgentLoop#closeMcp 的 CompletableFuture。
     *
     * @return 所有资源释放完成后完成的 CompletableFuture
     */
    // 对标 Python nanobot.py:104-106 aclose()
    @Override
    public CompletableFuture<Void> close() {
        return loop.closeMcp();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** 展开路径中的 ~ 为用户主目录，对标 Python Path.expanduser() */
    private static Path expandUser(Path p) {
        String s = p.toString();
        if (s.startsWith("~" + java.io.File.separator) || s.equals("~")) {
            return Path.of(System.getProperty("user.home"), s.substring(1));
        }
        return p;
    }
}
