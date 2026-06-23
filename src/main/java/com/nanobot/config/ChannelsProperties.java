package com.nanobot.config;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Channel 配置。对标 Python: {@code nanobot/config/schema.py:27-43 class ChannelsConfig(Base, extra="allow")}
 *
 * <p>{@link JsonAnySetter} 对标 Python Pydantic 的 {@code extra="allow"}，接收插件 channel 的自定义配置。
 */
public class ChannelsProperties {

    // 对标 Python schema.py:37 send_progress
    private boolean sendProgress = true;

    // 对标 Python schema.py:38 send_tool_hints
    private boolean sendToolHints;

    // 对标 Python schema.py:39 show_reasoning
    private boolean showReasoning = true;

    // 对标 Python schema.py:40 extract_document_text
    private boolean extractDocumentText = true;

    // 对标 Python schema.py:41 send_max_retries
    private int sendMaxRetries = 3;

    // 对标 Python schema.py:42 (Deprecated) transcription_provider
    private String transcriptionProvider = "groq";

    // 对标 Python schema.py:43 (Deprecated) transcription_language
    private String transcriptionLanguage;

    // 对标 Python extra="allow" — 插件自定义配置
    private Map<String, Object> extras = new ConcurrentHashMap<>();

    public static final ChannelsProperties DEFAULTS = new ChannelsProperties();

    // --- Getters/Setters ---

    public boolean isSendProgress() { return sendProgress; }
    public void setSendProgress(boolean sendProgress) { this.sendProgress = sendProgress; }

    public boolean isSendToolHints() { return sendToolHints; }
    public void setSendToolHints(boolean sendToolHints) { this.sendToolHints = sendToolHints; }

    public boolean isShowReasoning() { return showReasoning; }
    public void setShowReasoning(boolean showReasoning) { this.showReasoning = showReasoning; }

    public boolean isExtractDocumentText() { return extractDocumentText; }
    public void setExtractDocumentText(boolean extractDocumentText) { this.extractDocumentText = extractDocumentText; }

    public int getSendMaxRetries() { return sendMaxRetries; }
    public void setSendMaxRetries(int sendMaxRetries) { this.sendMaxRetries = sendMaxRetries; }

    public String getTranscriptionProvider() { return transcriptionProvider; }
    public void setTranscriptionProvider(String transcriptionProvider) { this.transcriptionProvider = transcriptionProvider; }

    public String getTranscriptionLanguage() { return transcriptionLanguage; }
    public void setTranscriptionLanguage(String transcriptionLanguage) { this.transcriptionLanguage = transcriptionLanguage; }

    public Map<String, Object> getExtras() { return extras; }
    public void setExtras(Map<String, Object> extras) { this.extras = extras; }

    @JsonAnySetter
    public void setExtra(String key, Object value) {
        extras.put(key, value);
    }
}
