package com.nanobot.bus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 进程内轻量级 pub/sub 总线，用于运行时状态通知。
 *
 * <p>与 {@link MessageBus} 完全独立——MessageBus 处理 user/chat 投递，
 * RuntimeEventBus 处理进程内状态通知，供 WebUI 等内部消费者订阅。
 *
 * <p>Subscriber 按注册顺序执行。{@code publish} 同步执行同步 handler 并
 * {@code join()} 等待异步 handler 完成；{@code publishNowait} 在虚拟线程上异步发布。
 *
 * <p>对标 Python {@code nanobot/bus/runtime_events.py:91-133 class RuntimeEventBus}。
 */
public class RuntimeEventBus {

    private static final Logger log = LoggerFactory.getLogger(RuntimeEventBus.class);

    /**
     * Handler 条目列表。{@code null} typeFilter 表示接收所有事件类型。
     * 使用 CopyOnWriteArrayList — publish（高频读）无锁，subscribe/unsubscribe（低频写）COW。
     *
     * <p>对标 Python runtime_events.py:100 self._handlers: list[_HandlerEntry]
     */
    private final CopyOnWriteArrayList<HandlerEntry> handlers;

    /** 用于 publishNowait 的虚拟线程执行器，对标 Python asyncio.create_task() */
    private final ExecutorService asyncExecutor;

    /**
     * 创建空的运行时事件总线。
     * 对标 Python {@code RuntimeEventBus.__init__()}
     */
    public RuntimeEventBus() {
        this.handlers = new CopyOnWriteArrayList<>();
        this.asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 订阅同步 handler 到指定事件类型。
     *
     * @param handler   同步事件处理器
     * @param eventType 事件类型过滤，null 表示接收所有事件
     * @return 取消订阅的回调（调用即移除 handler）
     */
    // 对标 Python runtime_events.py:102-114 subscribe()
    public Runnable subscribe(SyncRuntimeEventHandler handler, Class<? extends RuntimeEvent> eventType) {
        HandlerEntry entry = new HandlerEntry(eventType, handler);
        handlers.add(entry);
        return () -> handlers.remove(entry);
    }

    /** 订阅同步 handler 到所有事件类型 */
    public Runnable subscribe(SyncRuntimeEventHandler handler) {
        return subscribe(handler, null);
    }

    /**
     * 订阅异步 handler 到指定事件类型，其返回的 CompletableFuture 会被 publish() await。
     *
     * @param handler   异步事件处理器
     * @param eventType 事件类型过滤，null 表示接收所有事件
     * @return 取消订阅的回调
     */
    public Runnable subscribe(AsyncRuntimeEventHandler handler, Class<? extends RuntimeEvent> eventType) {
        HandlerEntry entry = new HandlerEntry(eventType, handler);
        handlers.add(entry);
        return () -> handlers.remove(entry);
    }

    /** 订阅异步 handler 到所有事件类型 */
    public Runnable subscribe(AsyncRuntimeEventHandler handler) {
        return subscribe(handler, null);
    }

    /**
     * 按注册顺序发布事件到所有匹配的 subscriber。
     * 同步 handler 在调用线程中执行；异步 handler 通过 {@code CompletableFuture.join()} 等待完成。
     *
     * <p>handler 抛出的异常会被日志记录但不会阻止后续 handler 执行（异常隔离）。
     *
     * @param event 要发布的运行时事件
     */
    // 对标 Python runtime_events.py:116-125 publish()
    public void publish(RuntimeEvent event) {
        for (HandlerEntry entry : handlers) {
            if (entry.typeFilter() != null && !entry.typeFilter().isInstance(event)) {
                continue;
            }
            try {
                Object h = entry.handler();
                if (h instanceof AsyncRuntimeEventHandler async) {
                    async.handleAsync(event).join();
                } else if (h instanceof SyncRuntimeEventHandler sync) {
                    sync.handle(event);
                }
            } catch (Exception e) {
                log.error("runtime event handler failed for {}", event.getClass().getSimpleName(), e);
            }
        }
    }

    /**
     * 在虚拟线程上异步发布事件，调用立即返回不阻塞。
     * 若当前不在事件循环上下文中则静默丢弃事件。
     *
     * @param event 要发布的运行时事件
     */
    // 对标 Python runtime_events.py:127-133 publish_nowait()
    public void publishNowait(RuntimeEvent event) {
        asyncExecutor.submit(() -> publish(event));
    }

    /**
     * 单个 subscriber 条目：可选类型过滤器 + handler（sync 或 async）。
     * 对标 Python runtime_events.py:88 _HandlerEntry = tuple[RuntimeEventType | None, RuntimeEventHandler]
     */
    private record HandlerEntry(
            Class<? extends RuntimeEvent> typeFilter,
            Object handler) {
    }
}
