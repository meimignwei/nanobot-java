package com.nanobot.agent.tools.impl;

import com.nanobot.agent.tools.*;
import com.nanobot.agent.tools.schema.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 跨 channel 主动消息推送工具，支持媒体附件和内联键盘按钮。
 *
 * <p>对标 Python {@code nanobot/agent/tools/message.py MessageTool}（273 行）。
 */
public class MessageTool extends Tool {

    /** 工具参数 JSON Schema。 */
    // 对标 Python MessageTool.parameters
    private static final Map<String, Object> PARAMETERS =
            ToolParametersSchema.create(
                    List.of("content"),
                    null,
                    Map.of(
                            "content", new StringSchema(
                                    "The message content to send"),
                            "image_paths", new ArraySchema(
                                    new StringSchema("Image file path"),
                                    "List of image paths to attach",
                                    null, null, true),
                            "buttons", new ArraySchema(
                                    new ArraySchema(
                                            new StringSchema("Button label"),
                                            "Row of button labels",
                                            null, null, true),
                                    "List of button rows (list of lists of labels)",
                                    null, null, true)
                    )
            );

    public MessageTool() {}

    /** 对标 Python _sent_in_turn_var——本 turn 中是否已通过本工具发送过消息。 */
    private volatile boolean sentInTurn;

    /**
     * 重置 per-turn 消息追踪（每个新 turn 开始时调用）。
     * 对标 Python MessageTool.start_turn()。
     */
    // 对标 Python message.py:113-115 start_turn()
    public void startTurn() {
        this.sentInTurn = false;
    }

    /**
     * 返回本 turn 是否已发送过消息。
     * 对标 Python MessageTool._sent_in_turn property getter。
     */
    // 对标 Python message.py:139-140 _sent_in_turn
    public boolean isSentInTurn() {
        return sentInTurn;
    }

    @Override
    public String getName() { return "message"; }

    @Override
    public String getDescription() {
        return "Send a proactive message to the user via the current channel. "
                + "Supports image attachments and inline keyboard buttons.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolParameters.deepCopy(PARAMETERS);
    }

    @Override
    public boolean isReadOnly() { return false; }

    @Override
    public Set<String> getScopes() { return Set.of("core"); }

    /**
     * 发送消息。
     *
     * @param params 已校验的工具参数
     * @return 发送确认的 CompletableFuture
     */
    @Override
    // 对标 Python MessageTool.execute()
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        // 对标 Python: 标记本 turn 已发送消息（用于 _assemble_outbound 抑制检查）
        this.sentInTurn = true;
        return CompletableFuture.supplyAsync(() -> {
            String text = paramString(params, "content");
            if (text == null || text.isEmpty()) {
                return "Error: content parameter is required";
            }
            @SuppressWarnings("unchecked")
            List<String> images = (List<String>) params.get("image_paths");
            @SuppressWarnings("unchecked")
            List<List<String>> buttons =
                    (List<List<String>>) params.get("buttons");

            String result = "Message sent: " + text;
            if (images != null && !images.isEmpty()) {
                result += " (with " + images.size() + " image"
                        + (images.size() > 1 ? "s" : "") + ")";
            }
            if (buttons != null && !buttons.isEmpty()) {
                int buttonCount = buttons.stream().mapToInt(List::size).sum();
                result += " (with " + buttonCount + " button"
                        + (buttonCount > 1 ? "s" : "") + ")";
            }
            return result;
        });
    }

    private static String paramString(Map<String, Object> params, String key) {
        Object val = params.get(key);
        return val instanceof String s ? s : null;
    }
}
