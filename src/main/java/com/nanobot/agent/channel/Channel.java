package com.nanobot.agent.channel;

import com.nanobot.bus.OutboundMessage;

/**
 * 聊天渠道接口，定义渠道的名称、发送、流式支持。
 * 对应 Python Channel 抽象基类（channel/base.py）。
 */
public interface Channel {

    /** 渠道名称，如 "webui"、"telegram"。
     *  对应 Python Channel.name property。 */
    String name();

    /** 发送出站消息到渠道。
     *  对应 Python Channel.send()。 */
    void send(OutboundMessage msg) throws Exception;

    /** 发送流式增量（delta）到渠道。默认空实现。
     *  对应 Python Channel.send_delta()。 */
    default void sendDelta(String chatId, String delta) throws Exception {}

    /** 是否支持流式输出。默认 false。
     *  对应 Python Channel.supports_streaming()。 */
    default boolean supportsStreaming() {
        return false;
    }
}
