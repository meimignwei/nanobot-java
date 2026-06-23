package com.nanobot.bus;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 进度回调函数式接口，将 agent 进度更新包装为 {@link OutboundMessage} 发布到消息总线。
 *
 * <p>每次调用将进度内容包装为携带 {@code _progress: true} 标记的出站消息，
 * 供 channel 区分进度更新和最终回复。
 *
 * <p>对标 Python {@code nanobot/bus/progress.py:17-70 build_bus_progress_callback()}。
 */
@FunctionalInterface
public interface BusProgressCallback {

    /**
     * 发布一条进度更新到消息总线。
     *
     * @param content        进度文本
     * @param toolHint       是否为工具执行提示
     * @param toolEvents     工具调用事件 payload，可为 null
     * @param fileEditEvents 文件编辑事件 payload，可为 null
     * @param reasoning      是否为推理/preview delta
     * @param reasoningEnd   推理块是否已结束
     * @throws InterruptedException 发布期间线程被中断
     */
    void onProgress(
            String content,
            boolean toolHint,
            List<Map<String, Object>> toolEvents,
            List<Map<String, Object>> fileEditEvents,
            boolean reasoning,
            boolean reasoningEnd) throws InterruptedException;

    /**
     * 创建绑定到指定总线和入站消息的进度回调。
     * 对标 Python {@code build_bus_progress_callback(bus, msg)} 闭包工厂。
     *
     * @param bus 消息总线实例
     * @param msg 入站消息（用于提取 channel/chatId）
     * @return 绑定后的进度回调
     */
    // 对标 Python progress.py:17-70 build_bus_progress_callback()
    static BusProgressCallback create(MessageBus bus, InboundMessage msg) {
        return (content, toolHint, toolEvents, fileEditEvents, reasoning, reasoningEnd) -> {
            Map<String, Object> meta = new HashMap<>();
            Map<String, Object> originalMeta = msg.metadata();
            if (originalMeta != null && !originalMeta.isEmpty()) {
                meta.putAll(originalMeta);
            }
            meta.put("_progress", Boolean.TRUE);
            meta.put("_tool_hint", toolHint);
            if (reasoning) {
                meta.put("_reasoning_delta", Boolean.TRUE);
            }
            if (reasoningEnd) {
                meta.put("_reasoning_end", Boolean.TRUE);
            }
            if (toolEvents != null && !toolEvents.isEmpty()) {
                meta.put("_tool_events", toolEvents);
            }
            if (fileEditEvents != null && !fileEditEvents.isEmpty()) {
                meta.put("_file_edit_events", fileEditEvents);
            }

            OutboundMessage outMsg = new OutboundMessage(
                    msg.channel(),
                    msg.chatId(),
                    content,
                    null,
                    List.of(),
                    Collections.unmodifiableMap(meta),
                    List.of());
            bus.publishOutbound(outMsg);
        };
    }
}
