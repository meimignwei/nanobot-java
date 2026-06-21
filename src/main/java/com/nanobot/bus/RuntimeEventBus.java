package com.nanobot.bus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 进程内运行时事件 pub/sub 总线。
 * 对应 Python RuntimeEventBus（bus/runtime_events.py:91-134）。
 *
 * <p>订阅者按注册顺序执行。publish 同步调用处理器；
 * publish_nowait 在当前虚拟线程中直接调度。</p>
 */
public class RuntimeEventBus {

    private static final Logger log = LoggerFactory.getLogger(RuntimeEventBus.class);

    /** 处理器条目：(eventType filter, handler) */
    private final List<HandlerEntry> handlers = new CopyOnWriteArrayList<>();

    /** 订阅事件。返回取消订阅的回调。
     *  对应 Python RuntimeEventBus.subscribe()。 */
    public Runnable subscribe(Class<?> eventType, Consumer<Object> handler) {
        var entry = new HandlerEntry(eventType, handler);
        handlers.add(entry);
        return () -> handlers.remove(entry);
    }

    /** 发布事件（同步）。
     *  对应 Python RuntimeEventBus.publish()。 */
    public void publish(Object event) {
        for (var entry : handlers) {
            if (entry.eventType != null && !entry.eventType.isInstance(event)) {
                continue;
            }
            try {
                entry.handler.accept(event);
            } catch (Exception e) {
                log.error("runtime event handler failed for {}", event.getClass().getSimpleName(), e);
            }
        }
    }

    /** 发布事件（非等待，在当前线程直接调用 publish）。
     *  对应 Python RuntimeEventBus.publish_nowait()。 */
    public void publishNowait(Object event) {
        publish(event);
    }

    private record HandlerEntry(Class<?> eventType, Consumer<Object> handler) {}

    // ---- 标准运行时事件类型 ----
    // 对应 Python RuntimeEvent 子类

    public record SessionTurnStarted(Map<String, Object> context) {}
    public record TurnRunStatusChanged(Map<String, Object> context, String status, Double startedAt) {}
    public record TurnCompleted(Map<String, Object> context, Integer latencyMs, Object runtime) {}
    public record RuntimeModelChanged(String model, String modelPreset) {}
}
