package com.nanobot.agent.channel;

import com.nanobot.bus.OutboundMessage;

public interface Channel {

    String name();

    void send(OutboundMessage msg) throws Exception;

    default void sendDelta(String chatId, String delta) throws Exception {}

    default boolean supportsStreaming() {
        return false;
    }
}
