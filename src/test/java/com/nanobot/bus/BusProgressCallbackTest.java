package com.nanobot.bus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link BusProgressCallback} 单元测试，验证进度回调正确构建 OutboundMessage 的 metadata。
 */
class BusProgressCallbackTest {

    @Test
    void callbackPublishesOutboundMessage() throws InterruptedException {
        MessageBus bus = new MessageBus();
        InboundMessage msg = new InboundMessage("tg", "u1", "c1", "hello");
        BusProgressCallback cb = BusProgressCallback.create(bus, msg);

        cb.onProgress("thinking...", false, null, null, true, false);

        OutboundMessage out = bus.consumeOutbound();
        assertThat(out.channel()).isEqualTo("tg");
        assertThat(out.chatId()).isEqualTo("c1");
        assertThat(out.content()).isEqualTo("thinking...");
        assertThat(out.metadata()).containsEntry("_progress", Boolean.TRUE);
        assertThat(out.metadata()).containsEntry("_reasoning_delta", Boolean.TRUE);
    }

    @Test
    void callbackIncludesToolEvents() throws InterruptedException {
        MessageBus bus = new MessageBus();
        InboundMessage msg = new InboundMessage("tg", "u1", "c1", "hello");
        BusProgressCallback cb = BusProgressCallback.create(bus, msg);

        List<Map<String, Object>> toolEvents = List.of(
                Map.of("name", "read_file", "status", "running"));

        cb.onProgress("reading file...", true, toolEvents, null, false, false);

        OutboundMessage out = bus.consumeOutbound();
        assertThat(out.metadata()).containsEntry("_tool_hint", Boolean.TRUE);
        assertThat(out.metadata()).containsKey("_tool_events");
    }

    @Test
    void callbackIncludesFileEditEvents() throws InterruptedException {
        MessageBus bus = new MessageBus();
        InboundMessage msg = new InboundMessage("tg", "u1", "c1", "hello");
        BusProgressCallback cb = BusProgressCallback.create(bus, msg);

        List<Map<String, Object>> fileEditEvents = List.of(
                Map.of("path", "/tmp/test.txt", "action", "edit"));

        cb.onProgress("editing file...", false, null, fileEditEvents, false, false);

        OutboundMessage out = bus.consumeOutbound();
        assertThat(out.metadata()).containsKey("_file_edit_events");
    }

    @Test
    void callbackIncludesReasoningEnd() throws InterruptedException {
        MessageBus bus = new MessageBus();
        InboundMessage msg = new InboundMessage("tg", "u1", "c1", "hello");
        BusProgressCallback cb = BusProgressCallback.create(bus, msg);

        cb.onProgress("done thinking", false, null, null, false, true);

        OutboundMessage out = bus.consumeOutbound();
        assertThat(out.metadata()).containsEntry("_reasoning_end", Boolean.TRUE);
    }

    @Test
    void callbackPreservesOriginalMetadata() throws InterruptedException {
        MessageBus bus = new MessageBus();
        Map<String, Object> originalMeta = Map.of("thread_id", "t123");
        InboundMessage msg = new InboundMessage("tg", "u1", "c1", "hello",
                java.time.Instant.now(), List.of(), originalMeta, null);
        BusProgressCallback cb = BusProgressCallback.create(bus, msg);

        cb.onProgress("ok", false, null, null, false, false);

        OutboundMessage out = bus.consumeOutbound();
        assertThat(out.metadata()).containsEntry("thread_id", "t123");
    }
}
