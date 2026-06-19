package com.nanobot.bus;

import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * Message to send to a chat channel. Mirrors Python OutboundMessage dataclass exactly.
 */
public record OutboundMessage(
        String channel,
        String chatId,
        String content,
        @Nullable String replyTo,
        List<String> media,
        Map<String, Object> metadata,
        List<List<String>> buttons
) {
    public OutboundMessage {
        if (media == null) media = List.of();
        if (metadata == null) metadata = Map.of();
        if (buttons == null) buttons = List.of();
    }
}
