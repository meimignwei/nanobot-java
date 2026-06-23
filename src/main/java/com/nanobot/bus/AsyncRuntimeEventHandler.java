package com.nanobot.bus;

import java.util.concurrent.CompletableFuture;

/**
 * 异步运行时事件处理器，其返回的 {@link CompletableFuture} 会被
 * {@link RuntimeEventBus#publish(RuntimeEvent)} 等待完成。
 *
 * <p>对标 Python {@code runtime_events.py:87 RuntimeEventHandler = Callable[[Any], Awaitable[None] | None]}
 * 中返回 Awaitable[None] 的异步分支。
 */
@FunctionalInterface
public interface AsyncRuntimeEventHandler {

    /**
     * 异步处理运行时事件。
     *
     * @param event 运行时事件
     * @return 处理完成后 resolved 的 CompletableFuture
     */
    CompletableFuture<Void> handleAsync(RuntimeEvent event);
}
