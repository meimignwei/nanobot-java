package com.nanobot.bus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 channel 接收的入站消息。
 *
 * <p>对标 Python {@code nanobot/bus/events.py:19-31 @dataclass class InboundMessage}。
 */
public record InboundMessage(
        String channel,
        String senderId,
        String chatId,
        String content,
        Instant timestamp,
        List<String> media,
        Map<String, Object> metadata,
        String sessionKeyOverride) {

    /**
     * Compact constructor：为可选字段提供默认值（对标 Python dataclass 的 default_factory）。
     */
    public InboundMessage {
        if (timestamp == null) timestamp = Instant.now();
        if (media == null) media = new ArrayList<>();
        if (metadata == null) metadata = new HashMap<>();
    }

    /**
     * 最小字段便捷构造器，可选字段使用默认值。
     *
     * @param channel  channel 标识（如 "telegram"、"discord"）
     * @param senderId 发送者标识
     * @param chatId   会话标识
     * @param content  消息文本
     */
    public InboundMessage(String channel, String senderId, String chatId, String content) {
        this(channel, senderId, chatId, content,
                Instant.now(), new ArrayList<>(), new HashMap<>(), null);
    }

    /**
     * 返回会话标识键：若设置了 {@code sessionKeyOverride} 则使用覆盖值，
     * 否则按 {@code channel:chatId} 格式自动生成。
     *
     * @return 会话标识字符串
     */
    // 对标 Python events.py:32-35 session_key property
    public String sessionKey() {
        return sessionKeyOverride != null && !sessionKeyOverride.isEmpty()
                ? sessionKeyOverride
                : channel + ":" + chatId;
    }

    /**
     * 返回覆盖了 sessionKeyOverride 的新 InboundMessage 副本。
     * 对标 Python 中设置 msg.session_key_override 属性。
     *
     * @param override 新的 session key 覆盖值
     * @return 新 InboundMessage 实例
     */
    public InboundMessage withSessionKeyOverride(String override) {
        return new InboundMessage(channel, senderId, chatId, content,
                timestamp, media, metadata, override);
    }
}
