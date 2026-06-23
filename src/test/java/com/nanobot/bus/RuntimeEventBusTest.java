package com.nanobot.bus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link RuntimeEventBus} 单元测试，覆盖订阅、发布、类型过滤、取消订阅和异常隔离。
 */
class RuntimeEventBusTest {

    private RuntimeEventBus bus;

    @BeforeEach
    void setUp() {
        bus = new RuntimeEventBus();
    }

    @Test
    void subscribeSyncAndPublish() {
        List<RuntimeEvent> received = new CopyOnWriteArrayList<>();
        bus.subscribe((SyncRuntimeEventHandler) received::add, SessionTurnStarted.class);

        RuntimeEventContext ctx = new RuntimeEventContext("ws", "c1", "ws:c1", Map.of());
        bus.publish(new SessionTurnStarted(ctx));

        assertThat(received).hasSize(1);
        assertThat(received.get(0)).isInstanceOf(SessionTurnStarted.class);
    }

    @Test
    void typeFilterOnlyMatchesCorrectType() {
        List<RuntimeEvent> all = new CopyOnWriteArrayList<>();
        List<RuntimeEvent> turnOnly = new CopyOnWriteArrayList<>();
        bus.subscribe((SyncRuntimeEventHandler) all::add);
        bus.subscribe((SyncRuntimeEventHandler) turnOnly::add, TurnCompleted.class);

        RuntimeEventContext ctx = new RuntimeEventContext("ws", "c1", "ws:c1", Map.of());
        bus.publish(new TurnCompleted(ctx, 100, null));
        bus.publish(new RuntimeModelChanged("claude", null));

        assertThat(all).hasSize(2);
        assertThat(turnOnly).hasSize(1);
        assertThat(turnOnly.get(0)).isInstanceOf(TurnCompleted.class);
    }

    @Test
    void unsubscribeRemovesHandler() {
        List<RuntimeEvent> received = new CopyOnWriteArrayList<>();
        Runnable unsub = bus.subscribe((SyncRuntimeEventHandler) received::add);
        unsub.run();

        RuntimeEventContext ctx = new RuntimeEventContext("ws", "c1", "ws:c1", Map.of());
        bus.publish(new SessionTurnStarted(ctx));
        assertThat(received).isEmpty();
    }

    @Test
    void handlerExceptionDoesNotCrashBus() {
        bus.subscribe((SyncRuntimeEventHandler) (e -> {
            throw new RuntimeException("boom");
        }));
        List<RuntimeEvent> received = new CopyOnWriteArrayList<>();
        bus.subscribe((SyncRuntimeEventHandler) received::add);

        RuntimeEventContext ctx = new RuntimeEventContext("ws", "c1", "ws:c1", Map.of());
        bus.publish(new TurnCompleted(ctx, 100, null));

        // Second handler should still run despite first throwing
        assertThat(received).hasSize(1);
    }

    @Test
    void publishNowaitRunsAsynchronously() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        bus.subscribe((SyncRuntimeEventHandler) (e -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
            done.countDown();
        }));

        RuntimeEventContext ctx = new RuntimeEventContext("ws", "c1", "ws:c1", Map.of());
        long start = System.nanoTime();
        bus.publishNowait(new TurnCompleted(ctx, 100, null));
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // publishNowait should return almost immediately
        assertThat(elapsed).isLessThan(200);
        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void asyncHandlerIsAwaited() {
        List<RuntimeEvent> received = new CopyOnWriteArrayList<>();
        bus.subscribe((AsyncRuntimeEventHandler) (event -> {
            received.add(event);
            return CompletableFuture.completedFuture(null);
        }));

        RuntimeEventContext ctx = new RuntimeEventContext("ws", "c1", "ws:c1", Map.of());
        bus.publish(new RuntimeModelChanged("claude", null));

        assertThat(received).hasSize(1);
    }

    @Test
    void nullTypeFilterReceivesAllEvents() {
        List<RuntimeEvent> received = new CopyOnWriteArrayList<>();
        bus.subscribe((SyncRuntimeEventHandler) received::add, null);

        bus.publish(new RuntimeModelChanged("claude", null));
        bus.publish(new RuntimeModelChanged("gemini", null));

        assertThat(received).hasSize(2);
    }

    @Test
    void unsubscribingDuringPublishIsSafe() {
        // Subscribe handler that unsubscribes itself on first invocation
        Runnable[] unsubHolder = new Runnable[1];
        List<RuntimeEvent> received = new CopyOnWriteArrayList<>();

        unsubHolder[0] = bus.subscribe((SyncRuntimeEventHandler) (e -> {
            received.add(e);
            unsubHolder[0].run(); // self-unsubscribe
        }));

        // Publish twice — handler should only receive the first event
        RuntimeEventContext ctx = new RuntimeEventContext("ws", "c1", "ws:c1", Map.of());
        bus.publish(new SessionTurnStarted(ctx));
        bus.publish(new SessionTurnStarted(ctx));

        assertThat(received).hasSize(1);
    }
}
