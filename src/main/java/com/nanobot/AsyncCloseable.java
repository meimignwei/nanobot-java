package com.nanobot;

import java.util.concurrent.CompletableFuture;

/**
 * 异步可关闭资源。对标 Python 的异步上下文管理器协议（{@code __aenter__} / {@code __aexit__}）。
 * 源码文件：无直接对标（Java 语言级抽象，对标 Python async context manager 协议）
 */
public interface AsyncCloseable {

    /** 对标 Python: {@code await resource.aclose()} 或 {@code await stack.aclose()} */
    CompletableFuture<Void> close();
}
