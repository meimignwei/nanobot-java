package com.nanobot.bus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@DisplayName("MessageBus")
class MessageBusTest {

    private MessageBus bus;

    @BeforeEach
    void setUp() {
        bus = new MessageBus();
    }

    @Test
    @DisplayName("should publish and consume inbound in order")
    void publishAndConsumeInbound() throws InterruptedException {
        var msg = new InboundMessage("test", "u1", "c1", "hello", null, null, null, null);
        bus.publishInbound(msg);
        assertThat(bus.consumeInbound()).isEqualTo(msg);
    }

    @Test
    @DisplayName("should publish and consume outbound in order")
    void publishAndConsumeOutbound() throws InterruptedException {
        var msg = new OutboundMessage("test", "c1", "reply", null, null, null, null);
        bus.publishOutbound(msg);
        assertThat(bus.consumeOutbound()).isEqualTo(msg);
    }

    @Test
    @DisplayName("should maintain FIFO order for multiple messages")
    void maintainFifoOrder() throws InterruptedException {
        var m1 = inboundMsg("first");
        var m2 = inboundMsg("second");
        var m3 = inboundMsg("third");

        bus.publishInbound(m1);
        bus.publishInbound(m2);
        bus.publishInbound(m3);

        assertThat(bus.consumeInbound()).isEqualTo(m1);
        assertThat(bus.consumeInbound()).isEqualTo(m2);
        assertThat(bus.consumeInbound()).isEqualTo(m3);
    }

    @Test
    @DisplayName("should track inbound size correctly")
    void inboundSizeTracksPending() throws InterruptedException {
        assertThat(bus.inboundSize()).isZero();
        bus.publishInbound(inboundMsg("a"));
        bus.publishInbound(inboundMsg("b"));
        assertThat(bus.inboundSize()).isEqualTo(2);
        bus.consumeInbound();
        assertThat(bus.inboundSize()).isEqualTo(1);
        bus.consumeInbound();
        assertThat(bus.inboundSize()).isZero();
    }

    @Test
    @DisplayName("should track outbound size correctly")
    void outboundSizeTracksPending() throws InterruptedException {
        assertThat(bus.outboundSize()).isZero();
        bus.publishOutbound(outboundMsg("a"));
        bus.publishOutbound(outboundMsg("b"));
        assertThat(bus.outboundSize()).isEqualTo(2);
        bus.consumeOutbound();
        assertThat(bus.outboundSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("consumeInbound should block until message available")
    void consumeBlocksUntilMessage() throws InterruptedException {
        var latch = new CountDownLatch(1);
        Thread.startVirtualThread(() -> {
            try {
                bus.consumeInbound();
                latch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // consume should be blocked
        assertThat(latch.await(200, TimeUnit.MILLISECONDS)).isFalse();
        bus.publishInbound(inboundMsg("wake"));
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("consumeOutbound should block until message available")
    void consumeOutboundBlocks() throws InterruptedException {
        var latch = new CountDownLatch(1);
        Thread.startVirtualThread(() -> {
            try {
                bus.consumeOutbound();
                latch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(latch.await(200, TimeUnit.MILLISECONDS)).isFalse();
        bus.publishOutbound(outboundMsg("wake"));
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Nested
    @DisplayName("InboundMessage")
    class InboundMessageTests {

        @Test
        @DisplayName("should compute sessionKey from channel:chatId")
        void sessionKeyDefault() {
            var msg = new InboundMessage("tg", "u1", "c42", "hi", null, null, null, null);
            assertThat(msg.sessionKey()).isEqualTo("tg:c42");
        }

        @Test
        @DisplayName("should respect sessionKeyOverride")
        void sessionKeyOverride() {
            var msg = new InboundMessage("tg", "u1", "c42", "hi", null, null, null, "custom:thread");
            assertThat(msg.sessionKey()).isEqualTo("custom:thread");
        }

        @Test
        @DisplayName("should default timestamp to now")
        void defaultTimestamp() {
            var before = Instant.now();
            var msg = new InboundMessage("test", "u1", "c1", "hi", null, null, null, null);
            assertThat(msg.timestamp()).isBetween(before, Instant.now());
        }

        @Test
        @DisplayName("should default media and metadata to empty")
        void defaultMediaAndMetadata() {
            var msg = new InboundMessage("test", "u1", "c1", "hi", null, null, null, null);
            assertThat(msg.media()).isEmpty();
            assertThat(msg.metadata()).isEmpty();
        }
    }

    @Nested
    @DisplayName("OutboundMessage")
    class OutboundMessageTests {

        @Test
        @DisplayName("should default replyTo to null")
        void defaultReplyTo() {
            var msg = new OutboundMessage("test", "c1", "hi", null, null, null, null);
            assertThat(msg.replyTo()).isNull();
        }

        @Test
        @DisplayName("should default media, metadata, buttons to empty")
        void defaultCollections() {
            var msg = new OutboundMessage("test", "c1", "hi", null, null, null, null);
            assertThat(msg.media()).isEmpty();
            assertThat(msg.metadata()).isEmpty();
            assertThat(msg.buttons()).isEmpty();
        }
    }

    // ---- helpers ----

    private static InboundMessage inboundMsg(String content) {
        return new InboundMessage("test", "u1", "c1", content, null, null, null, null);
    }

    private static OutboundMessage outboundMsg(String content) {
        return new OutboundMessage("test", "c1", content, null, null, null, null);
    }
}
