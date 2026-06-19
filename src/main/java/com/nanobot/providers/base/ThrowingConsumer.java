package com.nanobot.providers.base;

/**
 * A {@code Consumer}-like functional interface that may throw checked exceptions.
 * Used for streaming delta callbacks where InterruptedException must propagate.
 * <p>
 * Mirrors Python {@code Callable[[str], Awaitable[None]]}.
 */
@FunctionalInterface
public interface ThrowingConsumer<T> {
    void accept(T value) throws Exception;
}
