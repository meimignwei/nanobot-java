package com.nanobot.bus;

import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Message bus decoupling chat channels from the agent core.
 * Mirrors Python MessageBus (nanobot/bus/queue.py) exactly.
 *
 * Python asyncio.Queue → Java LinkedBlockingQueue.
 * Under virtual threads, take()/put() blocking suspends the carrier thread automatically.
 */
@Component
public class MessageBus {

    private final BlockingQueue<InboundMessage> inbound = new LinkedBlockingQueue<>();
    private final BlockingQueue<OutboundMessage> outbound = new LinkedBlockingQueue<>();

    public void publishInbound(InboundMessage msg) throws InterruptedException {
        inbound.put(msg);
    }

    public InboundMessage consumeInbound() throws InterruptedException {
        return inbound.take();
    }

    public void publishOutbound(OutboundMessage msg) throws InterruptedException {
        outbound.put(msg);
    }

    public OutboundMessage consumeOutbound() throws InterruptedException {
        return outbound.take();
    }

    public int inboundSize() {
        return inbound.size();
    }

    public int outboundSize() {
        return outbound.size();
    }
}
