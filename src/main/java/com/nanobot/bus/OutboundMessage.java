package com.nanobot.bus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 发送到 channel 的出站消息。
 *
 * <p>{@code metadata} 可携带路由信息（{@code message_id} 等）、追踪标记（{@code _progress}）
 * 以及可选的 {@code _agent_ui} 结构化 payload（供 rich client 渲染，非 WebUI channel 可忽略）。
 *
 * <p>对标 Python {@code nanobot/bus/events.py:38-53 @dataclass class OutboundMessage}。
 */
public record OutboundMessage(
        String channel,
        String chatId,
        String content,
        String replyTo,
        List<String> media,
        Map<String, Object> metadata,
        List<List<String>> buttons) {

    /**
     * Compact constructor：为可选字段提供默认值（对标 Python dataclass 的 default_factory）。
     */
    public OutboundMessage {
        if (media == null) media = new ArrayList<>();
        if (metadata == null) metadata = new HashMap<>();
        if (buttons == null) buttons = new ArrayList<>();
    }

    /**
     * 最小字段便捷构造器，可选字段使用默认值。
     *
     * @param channel channel 标识
     * @param chatId  会话标识
     * @param content 消息文本
     */
    public OutboundMessage(String channel, String chatId, String content) {
        this(channel, chatId, content, null, new ArrayList<>(), new HashMap<>(), new ArrayList<>());
    }
}
