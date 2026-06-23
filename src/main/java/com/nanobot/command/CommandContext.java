package com.nanobot.command;

import com.nanobot.agent.AgentLoop;
import com.nanobot.bus.InboundMessage;
import com.nanobot.session.Session;

import javax.annotation.Nullable;

/**
 * 命令分发上下文——包含当前消息、会话、AgentLoop 引用等。
 *
 * <p>对标 Python {@code nanobot/command/router.py} CommandContext。
 */
public class CommandContext {

    private final InboundMessage msg;
    private final Session session;
    private final String sessionKey;
    private final String rawContent;
    private final AgentLoop agentLoop;

    public CommandContext(InboundMessage msg, @Nullable Session session,
                          String sessionKey, String rawContent,
                          AgentLoop agentLoop) {
        this.msg = msg;
        this.session = session;
        this.sessionKey = sessionKey;
        this.rawContent = rawContent;
        this.agentLoop = agentLoop;
    }

    public InboundMessage getMsg() { return msg; }

    @Nullable
    public Session getSession() { return session; }

    public String getSessionKey() { return sessionKey; }

    public String getRawContent() { return rawContent; }

    public AgentLoop getAgentLoop() { return agentLoop; }
}
