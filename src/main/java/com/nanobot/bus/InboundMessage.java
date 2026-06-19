package com.nanobot.bus;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Message received from a chat channel. Mirrors Python InboundMessage dataclass exactly.
 */
public record InboundMessage(
        String channel,
        String senderId,
        String chatId,
        String content,
        Instant timestamp,
        List<String> media,
        Map<String, Object> metadata,
        @Nullable String sessionKeyOverride
) {
    public InboundMessage {
        if (timestamp == null) timestamp = Instant.now();
        if (media == null) media = List.of();
        if (metadata == null) metadata = Map.of();
    }

    /** Mirrors Python InboundMessage.session_key property. */
    public String sessionKey() {
        return sessionKeyOverride != null ? sessionKeyOverride : channel + ":" + chatId;
    }
}
