package com.nanobot.bus;

/**
 * 延迟初始化 {@link RuntimeEventPublisher} 的工具，对标 Python 的
 * {@code ensure_runtime_event_publisher(owner)}。
 *
 * <p>对标 Python {@code nanobot/bus/runtime_events.py:238-251 ensure_runtime_event_publisher()}。
 */
public final class RuntimeEventPublishers {

    private RuntimeEventPublishers() {
    }

    /**
     * 返回 owner 的 runtime event publisher，若不存在则延迟创建。
     * owner 必须设置 runtimeEvents 和 runtimeEventPublisher 字段。
     *
     * @param owner 持有 RuntimeEventPublisher 的对象
     * @return 已存在或新创建的 RuntimeEventPublisher
     */
    // 对标 Python runtime_events.py:238-251 ensure_runtime_event_publisher()
    public static RuntimeEventPublisher ensure(RuntimeEventOwner owner) {
        RuntimeEventPublisher publisher = owner.getRuntimeEventPublisher();
        if (publisher != null) {
            return publisher;
        }

        RuntimeEventBus bus = owner.getRuntimeEvents();
        if (bus == null) {
            bus = new RuntimeEventBus();
            owner.setRuntimeEvents(bus);
        }

        publisher = new RuntimeEventPublisher(bus);
        owner.setRuntimeEventPublisher(publisher);
        return publisher;
    }

    /**
     * owner 类必须实现的接口，提供 runtime event 相关字段的 getter/setter。
     * 对标 Python 的 duck typing —— any object with runtime_events / runtime_event_publisher attributes。
     */
    public interface RuntimeEventOwner {
        /** 返回当前的 RuntimeEventPublisher，可能为 null */
        RuntimeEventPublisher getRuntimeEventPublisher();

        /** 设置 RuntimeEventPublisher */
        void setRuntimeEventPublisher(RuntimeEventPublisher publisher);

        /** 返回当前的 RuntimeEventBus，可能为 null */
        RuntimeEventBus getRuntimeEvents();

        /** 设置 RuntimeEventBus */
        void setRuntimeEvents(RuntimeEventBus bus);
    }
}
