package com.nanobot.bus;

/**
 * 同步运行时事件处理器，在 {@link RuntimeEventBus#publish(RuntimeEvent)} 调用线程中同步执行。
 *
 * <p>对标 Python {@code runtime_events.py:87 RuntimeEventHandler = Callable[[Any], Awaitable[None] | None]}
 * 中返回 None 的同步分支。
 */
@FunctionalInterface
public interface SyncRuntimeEventHandler {

    /**
     * 处理运行时事件。抛出的异常会被 RuntimeEventBus 日志记录，不会中断其他 handler。
     *
     * @param event 运行时事件
     * @throws Exception 处理异常（会被总线隔离）
     */
    void handle(RuntimeEvent event) throws Exception;
}
