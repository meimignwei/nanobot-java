package com.nanobot.agent.command;

import com.nanobot.bus.InboundMessage;
import com.nanobot.agent.session.Session;
import jakarta.annotation.Nullable;

/**
 * 命令处理器上下文，包含命令处理所需的全部信息。
 * 对应 Python CommandContext dataclass。
 */
public class CommandContext {

    /** 入站消息 */
    private final InboundMessage msg;
    /** 当前会话（优先级命令可能为 null） */
    @Nullable
    private final Session session;
    /** 会话 key */
    private final String key;
    /** 原始命令文本（含参数） */
    private final String raw;
    /** 命令参数（命令名之后的部分） */
    private String args;
    /** AgentLoop 引用（部分命令需要访问 loop 状态） */
    @Nullable
    private final Object loop;

    public CommandContext(InboundMessage msg, @Nullable Session session,
                          String key, String raw, String args) {
        this(msg, session, key, raw, args, null);
    }

    public CommandContext(InboundMessage msg, @Nullable Session session,
                          String key, String raw, String args, @Nullable Object loop) {
        this.msg = msg;
        this.session = session;
        this.key = key;
        this.raw = raw;
        this.args = args;
        this.loop = loop;
    }

    public InboundMessage msg() { return msg; }
    @Nullable
    public Session session() { return session; }
    public String key() { return key; }
    public String raw() { return raw; }
    public String args() { return args; }
    public void setArgs(String args) { this.args = args; }
    @Nullable
    public Object loop() { return loop; }
}
