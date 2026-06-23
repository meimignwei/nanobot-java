package com.nanobot.agent.tools;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 横切依赖注入容器，在工具创建时注入到每个 Tool。
 *
 * <p>对标 Python {@code nanobot/agent/tools/context.py ToolContext}（13 行）。
 * Java record 实现，不可变设计。工具在工厂方法中解构它以提取所需依赖。
 * 除 config、workspace、timezone 外，所有字段可为 null。
 *
 * @param config                       完整应用配置 POJO
 * @param workspace                    工作空间绝对路径
 * @param bus                          MessageBus 消息总线（可为 null）
 * @param subagentManager              子 agent 管理器（可为 null）
 * @param cronService                  定时任务服务（可为 null）
 * @param sessions                     SessionManager（可为 null）
 * @param fileStateStore               会话级 FileStates 追踪器（可为 null）
 * @param providerSnapshotLoader       provider 配置快照延迟加载器（可为 null）
 * @param imageGenerationProviderConfigs 图像生成 provider 配置 Map（可为 null）
 * @param timezone                     IANA 时区字符串，默认 "UTC"
 * @param workspaceSandbox             sandbox 名称（如 "bwrap"）或 null
 * @param runtimeEvents                RuntimeEventBus（可为 null）
 */
// 对标 Python @dataclass ToolContext
public record ToolContext(
        Object config,
        String workspace,
        Object bus,
        Object subagentManager,
        Object cronService,
        Object sessions,
        Object fileStateStore,
        Supplier<Object> providerSnapshotLoader,
        Map<String, Object> imageGenerationProviderConfigs,
        String timezone,
        Object workspaceSandbox,
        Object runtimeEvents
) {
    public ToolContext {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        if (workspace == null) throw new IllegalArgumentException("workspace must not be null");
        if (timezone == null) timezone = "UTC";
    }

    /**
     * 返回 Builder 实例，便于测试和手动构造。
     *
     * @return 新的 Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** ToolContext 的 Builder，提供链式调用设置各字段。 */
    public static class Builder {
        private Object config;
        private String workspace = ".";
        private Object bus;
        private Object subagentManager;
        private Object cronService;
        private Object sessions;
        private Object fileStateStore;
        private Supplier<Object> providerSnapshotLoader;
        private Map<String, Object> imageGenerationProviderConfigs;
        private String timezone = "UTC";
        private Object workspaceSandbox;
        private Object runtimeEvents;

        public Builder config(Object v) { this.config = v; return this; }
        public Builder workspace(String v) { this.workspace = v; return this; }
        public Builder bus(Object v) { this.bus = v; return this; }
        public Builder subagentManager(Object v) { this.subagentManager = v; return this; }
        public Builder cronService(Object v) { this.cronService = v; return this; }
        public Builder sessions(Object v) { this.sessions = v; return this; }
        public Builder fileStateStore(Object v) { this.fileStateStore = v; return this; }
        public Builder providerSnapshotLoader(Supplier<Object> v) {
            this.providerSnapshotLoader = v; return this;
        }
        public Builder imageGenerationProviderConfigs(Map<String, Object> v) {
            this.imageGenerationProviderConfigs = v; return this;
        }
        public Builder timezone(String v) { this.timezone = v; return this; }
        public Builder workspaceSandbox(Object v) { this.workspaceSandbox = v; return this; }
        public Builder runtimeEvents(Object v) { this.runtimeEvents = v; return this; }

        /**
         * 构建 ToolContext 实例。
         *
         * @return 新的 ToolContext
         */
        public ToolContext build() {
            return new ToolContext(config, workspace, bus, subagentManager, cronService,
                    sessions, fileStateStore, providerSnapshotLoader,
                    imageGenerationProviderConfigs, timezone, workspaceSandbox, runtimeEvents);
        }
    }
}
