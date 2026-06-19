package com.nanobot.agent.command;

import com.nanobot.bus.InboundMessage;
import com.nanobot.agent.session.Session;
import jakarta.annotation.Nullable;

/**
 * Everything a command handler needs to produce a response.
 * Mirrors Python CommandContext dataclass.
 */
public class CommandContext {

    private final InboundMessage msg;
    @Nullable
    private final Session session;
    private final String key;
    private final String raw;
    private String args;
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
