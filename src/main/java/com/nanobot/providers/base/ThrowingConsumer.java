package com.nanobot.providers.base;

/**
 * 可抛出受检异常的 Consumer 函数式接口。
 * 用于流式 delta 回调，允许 InterruptedException 等受检异常向上传播。
 *
 * <p>对应 Python {@code Callable[[str], Awaitable[None]]} 签名。</p>
 */
@FunctionalInterface
public interface ThrowingConsumer<T> {
    void accept(T value) throws Exception;
}
