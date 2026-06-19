package com.nanobot.bus;

import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * 出站消息，agent 回复给聊天渠道的消息体。
 * 对应 Python OutboundMessage dataclass（nanobot/bus/queue.py）。
 */
public record OutboundMessage(
        /** 目标渠道标识，如 "webui"、"telegram" */
        String channel,
        /** 目标会话 ID */
        String chatId,
        /** 回复文本内容 */
        String content,
        /** 可选，被回复的消息 ID */
        @Nullable String replyTo,
        /** 附件媒体文件路径列表 */
        List<String> media,
        /** 扩展元数据 */
        Map<String, Object> metadata,
        /** 按钮组，每组为一个行内按钮列表 */
        List<List<String>> buttons
) {
    public OutboundMessage {
        if (media == null) media = List.of();
        if (metadata == null) metadata = Map.of();
        if (buttons == null) buttons = List.of();
    }
}
