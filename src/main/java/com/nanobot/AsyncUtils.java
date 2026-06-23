package com.nanobot;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 异步工具类，提供 {@code withAsync} 模拟 Python 的 {@code async with} 语法。
 *
 * <p>无论 action 成功或失败，都确保 resource.close() 在返回/抛异常前完成。
 */
public final class AsyncUtils {

    private AsyncUtils() {}

    /**
     * 在异步资源上下文中执行操作，确保操作完成后自动关闭资源。
     * 对标 Python: {@code async with resource as r: result = await action(r)}。
     *
     * @param resource 异步可关闭资源
     * @param action   在资源上下文中执行的操作，接受资源实例并返回 CompletableFuture
     * @param <T>      资源类型
     * @param <R>      操作返回类型
     * @return 操作结果（正常完成）或失败（异常传播），均会先等待 resource.close()
     */
    public static <T extends AsyncCloseable, R> CompletableFuture<R> withAsync(
            T resource,
            Function<T, CompletableFuture<R>> action) {
        CompletableFuture<R> result = action.apply(resource);
        return result
                .thenCompose(r -> resource.close().thenApply(v -> r))
                .exceptionallyCompose(ex ->
                        resource.close().thenCompose(v -> CompletableFuture.failedFuture(ex)));
    }
}
