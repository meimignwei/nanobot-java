package com.nanobot.config;

/**
 * 音频转录配置。对标 Python: {@code nanobot/config/schema.py:46-54 class TranscriptionConfig}
 */
public class TranscriptionProperties {

    // 对标 Python schema.py:49
    private boolean enabled = true;

    // 对标 Python schema.py:50
    private String provider;

    // 对标 Python schema.py:51
    private String model;

    // 对标 Python schema.py:52
    private String language;

    // 对标 Python schema.py:53 max_duration_sec
    private int maxDurationSec = 120;

    // 对标 Python schema.py:54 max_upload_mb
    private int maxUploadMb = 25;

    public static final TranscriptionProperties DEFAULTS = new TranscriptionProperties();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public int getMaxDurationSec() { return maxDurationSec; }
    public void setMaxDurationSec(int maxDurationSec) { this.maxDurationSec = maxDurationSec; }

    public int getMaxUploadMb() { return maxUploadMb; }
    public void setMaxUploadMb(int maxUploadMb) { this.maxUploadMb = maxUploadMb; }
}
