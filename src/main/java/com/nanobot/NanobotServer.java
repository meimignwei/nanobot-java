package com.nanobot;

import com.nanobot.agent.AgentLoop;
import com.nanobot.api.OpenAiApiServer;
import com.nanobot.bus.MessageBus;
import com.nanobot.channels.ChannelManager;
import com.nanobot.cron.CronService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/**
 * 服务端生命周期管理，统一启动和关闭 AgentLoop、ChannelManager、API Server、CronService 等子系统。
 *
 * <p>此逻辑提取自 Python CLI 命令（{@code nanobot/cli/commands.py} 的 serve()/gateway()），
 * Python 中由 CLI 命令手动创建各组件并启动；Java 中通过 Spring {@code @PostConstruct}/
 * {@code @PreDestroy} 自动管理生命周期。
 */
@Component
public class NanobotServer {

    private final MessageBus bus;
    private final AgentLoop agentLoop;
    private final ChannelManager channelManager;
    private final CronService cronService;
    private final OpenAiApiServer apiServer;

    /** Spring 构造函数注入所有子系统组件 */
    public NanobotServer(MessageBus bus, AgentLoop agentLoop,
                         ChannelManager channelManager, CronService cronService,
                         OpenAiApiServer apiServer) {
        this.bus = bus;
        this.agentLoop = agentLoop;
        this.channelManager = channelManager;
        this.cronService = cronService;
        this.apiServer = apiServer;
    }

    /**
     * 启动所有子系统：AgentLoop 作为后台异步任务启动（对标 Python asyncio.create_task），
     * Channels、API Server、Cron 同步启动。
     */
    @PostConstruct
    public void start() {
        agentLoop.run()
                .exceptionally(ex -> {
                    throw new RuntimeException("AgentLoop crashed", ex);
                });
        channelManager.startAll();
        apiServer.start();
        cronService.start();
    }

    /** 关闭所有子系统 */
    @PreDestroy
    public void stop() {
        channelManager.stopAll();
        apiServer.stop();
        cronService.stop();
    }
}
