package com.nanobot.bus;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * 异步消息总线，解耦 chat channel 和 agent 核心。
 *
 * <p>Channel 向 inbound 队列推送用户消息，agent 消费后处理后，将响应推入 outbound 队列供 channel 分发。
 * 使用 {@link LinkedBlockingQueue} 实现，在 Virtual Thread 中阻塞获取时不会消耗 OS 线程资源。
 *
 * <p>对标 Python {@code nanobot/bus/queue.py:8-44 class MessageBus}。
 */
public class MessageBus {

    /** 入站消息队列（channel → agent），对标 Python queue.py:17 asyncio.Queue[InboundMessage] */
    private final LinkedBlockingQueue<InboundMessage> inbound;

    /** 出站消息队列（agent → channel），对标 Python queue.py:18 asyncio.Queue[OutboundMessage] */
    private final LinkedBlockingQueue<OutboundMessage> outbound;

    /**
     * 创建无界队列的消息总线。
     * 对标 Python {@code MessageBus.__init__()} 中的 asyncio.Queue()（无界）。
     */
    public MessageBus() {
        this.inbound = new LinkedBlockingQueue<>();
        this.outbound = new LinkedBlockingQueue<>();
    }

    /**
     * 创建可指定容量的消息总线，用于背压控制。非正数容量表示无界。
     *
     * @param inboundCapacity  入站队列最大容量，≤0 表示无界
     * @param outboundCapacity 出站队列最大容量，≤0 表示无界
     */
    public MessageBus(int inboundCapacity, int outboundCapacity) {
        this.inbound = (inboundCapacity > 0)
                ? new LinkedBlockingQueue<>(inboundCapacity)
                : new LinkedBlockingQueue<>();
        this.outbound = (outboundCapacity > 0)
                ? new LinkedBlockingQueue<>(outboundCapacity)
                : new LinkedBlockingQueue<>();
    }

    /**
     * 从 channel 向 agent 发布一条入站消息。队列满时阻塞当前虚拟线程。
     *
     * @param msg 入站消息（含 channel、sender、content 等）
     * @throws InterruptedException 等待期间线程被中断
     */
    // 对标 Python queue.py:20-22 publish_inbound()
    public void publishInbound(InboundMessage msg) throws InterruptedException {
        inbound.put(msg);
    }

    /**
     * 消费下一条入站消息，队列空时阻塞当前虚拟线程直到有消息可用。
     *
     * @return 入站消息
     * @throws InterruptedException 等待期间线程被中断
     */
    // 对标 Python queue.py:24-26 consume_inbound()
    public InboundMessage consumeInbound() throws InterruptedException {
        return inbound.take();
    }

    /**
     * 从 agent 向 channel 发布一条出站响应。队列满时阻塞当前虚拟线程。
     *
     * @param msg 出站消息（含 channel、chatId、content、metadata 等）
     * @throws InterruptedException 等待期间线程被中断
     */
    // 对标 Python queue.py:28-30 publish_outbound()
    public void publishOutbound(OutboundMessage msg) throws InterruptedException {
        outbound.put(msg);
    }

    /**
     * 消费下一条出站消息，队列空时阻塞当前虚拟线程直到有消息可用。
     *
     * @return 出站消息
     * @throws InterruptedException 等待期间线程被中断
     */
    // 对标 Python queue.py:32-34 consume_outbound()
    public OutboundMessage consumeOutbound() throws InterruptedException {
        return outbound.take();
    }

    /**
     * 返回当前积压的入站消息数量（近似值）。
     *
     * @return 入站队列中待处理的消息数
     */
    // 对标 Python queue.py:36-38 inbound_size
    public int inboundSize() {
        return inbound.size();
    }

    /**
     * 返回当前积压的出站消息数量（近似值）。
     *
     * @return 出站队列中待发送的消息数
     */
    // 对标 Python queue.py:40-42 outbound_size
    public int outboundSize() {
        return outbound.size();
    }
}
