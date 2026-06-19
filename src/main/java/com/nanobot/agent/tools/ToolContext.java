package com.nanobot.agent.tools;

import jakarta.annotation.Nullable;

import java.util.Map;

/**
 * 工具构建和执行时的运行时上下文。
 * 对应 Python ToolContext（context.py）。
 *
 * <p>提供配置、工作区、消息总线、子代理管理器、会话管理器、文件状态存储、
 * 时区等全局依赖。通过 ThreadLocal 绑定到当前线程。</p>
 */
public class ToolContext {

    private final Object config;
    private final String workspace;
    @Nullable private final Object bus;
    @Nullable private final Object subagentManager;
    @Nullable private final Object sessions;
    @Nullable private final Object fileStateStore;
    private final String timezone;

    private ToolContext(Builder builder) {
        this.config = builder.config;
        this.workspace = builder.workspace;
        this.bus = builder.bus;
        this.subagentManager = builder.subagentManager;
        this.sessions = builder.sessions;
        this.fileStateStore = builder.fileStateStore;
        this.timezone = builder.timezone != null ? builder.timezone : "UTC";
    }

    public Object config() { return config; }
    public String workspace() { return workspace; }
    @Nullable public Object bus() { return bus; }
    @Nullable public Object subagentManager() { return subagentManager; }
    @Nullable public Object sessions() { return sessions; }
    @Nullable public Object fileStateStore() { return fileStateStore; }
    public String timezone() { return timezone; }

    // ---- ThreadLocal 绑定（对应 Python ContextVar） ----

    private static final ThreadLocal<ToolContext> CURRENT = new ThreadLocal<>();

    /** 绑定到当前线程。对应 Python ctx.set()。 */
    public static void bind(ToolContext ctx) { CURRENT.set(ctx); }
    /** 获取当前线程绑定的上下文。对应 Python ctx.get()。 */
    @Nullable public static ToolContext current() { return CURRENT.get(); }
    /** 解绑。对应 Python ctx.reset()。 */
    public static void unbind() { CURRENT.remove(); }

    /** 便捷工厂：返回测试用最小 ToolContext */
    public static ToolContext create() {
        return builder().config(Map.of()).workspace(".").build();
    }

    // ---- Builder ----

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Object config;
        private String workspace;
        @Nullable private Object bus;
        @Nullable private Object subagentManager;
        @Nullable private Object sessions;
        @Nullable private Object fileStateStore;
        private String timezone = "UTC";

        public Builder config(Object config) { this.config = config; return this; }
        public Builder workspace(String workspace) { this.workspace = workspace; return this; }
        public Builder bus(Object bus) { this.bus = bus; return this; }
        public Builder subagentManager(Object sm) { this.subagentManager = sm; return this; }
        public Builder sessions(Object sessions) { this.sessions = sessions; return this; }
        public Builder fileStateStore(Object store) { this.fileStateStore = store; return this; }
        public Builder timezone(String tz) { this.timezone = tz; return this; }

        public ToolContext build() {
            return new ToolContext(this);
        }
    }
}
