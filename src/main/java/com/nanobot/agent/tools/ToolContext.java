package com.nanobot.agent.tools;

import jakarta.annotation.Nullable;

import java.util.Map;

/**
 * Runtime context for tool construction and execution.
 * Port of Python ToolContext (context.py).
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

    // ---- Thread-local binding (port of Python ContextVar) ----

    private static final ThreadLocal<ToolContext> CURRENT = new ThreadLocal<>();

    public static void bind(ToolContext ctx) { CURRENT.set(ctx); }
    @Nullable public static ToolContext current() { return CURRENT.get(); }
    public static void unbind() { CURRENT.remove(); }

    /** Convenience factory that returns a minimal tool context for testing. */
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
