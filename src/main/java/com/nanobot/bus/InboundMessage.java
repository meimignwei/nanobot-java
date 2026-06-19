package com.nanobot.bus;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 入站消息，从聊天渠道接收的用户消息体。
 * 对应 Python InboundMessage dataclass（nanobot/bus/queue.py）。
 */
public record InboundMessage(
        /** 来源渠道标识，如 "webui"、"telegram" */
        String channel,
        /** 发送者 ID */
        String senderId,
        /** 会话 ID */
        String chatId,
        /** 用户消息文本 */
        String content,
        /** 消息时间戳 */
        Instant timestamp,
        /** 附件媒体文件路径列表 */
        List<String> media,
        /** 扩展元数据 */
        Map<String, Object> metadata,
        /** 可选，覆盖默认 session key 生成规则 */
        @Nullable String sessionKeyOverride
) {
    public InboundMessage {
        if (timestamp == null) timestamp = Instant.now();
        if (media == null) media = List.of();
        if (metadata == null) metadata = Map.of();
    }

    /** 推导会话 key：优先使用 override，否则按 channel:chatId 拼接。
     *  对应 Python InboundMessage.session_key property。 */
    public String sessionKey() {
        return sessionKeyOverride != null ? sessionKeyOverride : channel + ":" + chatId;
    }

    /** 返回一个带指定 session key override 的副本。
     *  对应 Python InboundMessage.with_session_key() 工厂方法。 */
    public InboundMessage withSessionKey(String key) {
        return new InboundMessage(channel, senderId, chatId, content,
                timestamp, media, metadata, key);
    }
}
