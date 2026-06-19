package com.nanobot.bus;

import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 消息总线，解耦聊天渠道与 agent 核心。
 * 对应 Python MessageBus（nanobot/bus/queue.py）。
 *
 * <p>Python asyncio.Queue → Java LinkedBlockingQueue。
 * 在虚拟线程下，take()/put() 阻塞会自动挂起载体线程。</p>
 */
@Component
public class MessageBus {

    /** 入站消息队列（channe → agent） */
    private final BlockingQueue<InboundMessage> inbound = new LinkedBlockingQueue<>();
    /** 出站消息队列（agent → channel） */
    private final BlockingQueue<OutboundMessage> outbound = new LinkedBlockingQueue<>();

    /** 发布入站消息到队列（channel 调用） */
    public void publishInbound(InboundMessage msg) throws InterruptedException {
        inbound.put(msg);
    }

    /** 消费入站消息（agent 调用，阻塞等待） */
    public InboundMessage consumeInbound() throws InterruptedException {
        return inbound.take();
    }

    /** 发布出站消息到队列（agent 调用） */
    public void publishOutbound(OutboundMessage msg) throws InterruptedException {
        outbound.put(msg);
    }

    /** 消费出站消息（channel 调用，阻塞等待） */
    public OutboundMessage consumeOutbound() throws InterruptedException {
        return outbound.take();
    }

    /** 查看入站队列当前大小 */
    public int inboundSize() {
        return inbound.size();
    }

    /** 查看出站队列当前大小 */
    public int outboundSize() {
        return outbound.size();
    }
}
