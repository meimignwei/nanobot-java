package com.nanobot.config;

/**
 * 工具运行时配置——与 {@link ToolsProperties} 解耦，供 tool 上下文使用。
 *
 * <p>对标 Python: nanobot 中 tool 上下文使用的工具配置对象。
 */
public class ToolsConfig {

    private boolean exec;
    private boolean web;
    private boolean restrictToWorkspace;

    public ToolsConfig() {
        this.exec = false;
        this.web = false;
        this.restrictToWorkspace = false;
    }

    private ToolsConfig(Builder builder) {
        this.exec = builder.exec;
        this.web = builder.web;
        this.restrictToWorkspace = builder.restrictToWorkspace;
    }

    public boolean isExec() { return exec; }
    public boolean isWeb() { return web; }
    public boolean isRestrictToWorkspace() { return restrictToWorkspace; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private boolean exec;
        private boolean web;
        private boolean restrictToWorkspace;

        public Builder exec(boolean v) { this.exec = v; return this; }
        public Builder web(boolean v) { this.web = v; return this; }
        public Builder restrictToWorkspace(boolean v) { this.restrictToWorkspace = v; return this; }
        public ToolsConfig build() { return new ToolsConfig(this); }
    }
}
