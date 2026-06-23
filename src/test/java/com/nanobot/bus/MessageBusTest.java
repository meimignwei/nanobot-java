package com.nanobot.bus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link MessageBus} 单元测试，覆盖入站/出站队列的发布、消费、阻塞语义和背压控制。
 */
class MessageBusTest {

    private MessageBus bus;

    @BeforeEach
    void setUp() {
        bus = new MessageBus();
    }

    @Test
    void publishAndConsumeInbound() throws InterruptedException {
        InboundMessage msg = new InboundMessage("test", "u1", "c1", "hello");
        bus.publishInbound(msg);
        InboundMessage result = bus.consumeInbound();
        assertThat(result).isEqualTo(msg);
    }

    @Test
    void publishAndConsumeOutbound() throws InterruptedException {
        OutboundMessage msg = new OutboundMessage("test", "c1", "reply");
        bus.publishOutbound(msg);
        OutboundMessage result = bus.consumeOutbound();
        assertThat(result).isEqualTo(msg);
    }

    @Test
    void inboundSizeReflectsPending() throws InterruptedException {
        bus.publishInbound(msg("a"));
        bus.publishInbound(msg("b"));
        assertThat(bus.inboundSize()).isEqualTo(2);
        bus.consumeInbound();
        assertThat(bus.inboundSize()).isEqualTo(1);
    }

    @Test
    void outboundSizeReflectsPending() throws InterruptedException {
        bus.publishOutbound(new OutboundMessage("test", "c1", "x"));
        bus.publishOutbound(new OutboundMessage("test", "c1", "y"));
        assertThat(bus.outboundSize()).isEqualTo(2);
        bus.consumeOutbound();
        assertThat(bus.outboundSize()).isEqualTo(1);
    }

    @Test
    void consumeBlocksUntilMessage() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch consumed = new CountDownLatch(1);

        Thread vt = Thread.ofVirtual().start(() -> {
            started.countDown();
            try {
                bus.consumeInbound();
                consumed.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        // consume should be blocking
        assertThat(consumed.await(200, TimeUnit.MILLISECONDS)).isFalse();
        bus.publishInbound(msg("x"));
        assertThat(consumed.await(2, TimeUnit.SECONDS)).isTrue();
        vt.join(Duration.ofSeconds(1));
    }

    @Test
    void sessionKeyDefault() {
        InboundMessage m = new InboundMessage("tg", "u1", "c1", "hi");
        assertThat(m.sessionKey()).isEqualTo("tg:c1");
    }

    @Test
    void sessionKeyOverride() {
        InboundMessage m = new InboundMessage("tg", "u1", "c1", "hi",
                Instant.now(), List.of(), Map.of(), "override:key");
        assertThat(m.sessionKey()).isEqualTo("override:key");
    }

    @Test
    void sessionKeyEmptyOverrideFallsBack() {
        InboundMessage m = new InboundMessage("tg", "u1", "c1", "hi",
                Instant.now(), List.of(), Map.of(), "");
        assertThat(m.sessionKey()).isEqualTo("tg:c1");
    }

    @Test
    void outboundMessageDefaults() {
        OutboundMessage m = new OutboundMessage("ch", "c1", "text");
        assertThat(m.replyTo()).isNull();
        assertThat(m.media()).isEmpty();
        assertThat(m.metadata()).isEmpty();
        assertThat(m.buttons()).isEmpty();
    }

    @Test
    void outboundMessageCompactConstructorSetsDefaults() {
        OutboundMessage m = new OutboundMessage("ch", "c1", "text",
                null, null, null, null);
        assertThat(m.media()).isEmpty();
        assertThat(m.metadata()).isEmpty();
        assertThat(m.buttons()).isEmpty();
    }

    @Test
    void boundedQueueBlocksOnFull() throws Exception {
        MessageBus bounded = new MessageBus(1, 100);
        bounded.publishInbound(msg("first"));
        // Queue is full — put in another virtual thread should block
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch unblocked = new CountDownLatch(1);

        Thread vt = Thread.ofVirtual().start(() -> {
            try {
                blocked.countDown();
                bounded.publishInbound(msg("second"));
                unblocked.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(blocked.await(1, TimeUnit.SECONDS)).isTrue();
        // Give it time to block
        Thread.sleep(100);
        assertThat(unblocked.getCount()).isEqualTo(1);
        // Drain one → unblocks
        bounded.consumeInbound();
        assertThat(unblocked.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(bounded.inboundSize()).isEqualTo(1);
        vt.join(Duration.ofSeconds(1));
    }

    @Test
    void inboundMessageCompactConstructorSetsDefaults() {
        InboundMessage m = new InboundMessage("ch", "u1", "c1", "hi",
                null, null, null, null);
        assertThat(m.timestamp()).isNotNull();
        assertThat(m.media()).isEmpty();
        assertThat(m.metadata()).isEmpty();
        assertThat(m.sessionKeyOverride()).isNull();
    }

    private static InboundMessage msg(String content) {
        return new InboundMessage("test", "u1", "c1", content);
    }
}
