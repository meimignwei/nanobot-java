package com.nanobot.config;

/**
 * Mirrors Python ApiConfig. OpenAI-compatible API server settings.
 */
public record ApiProperties(
        String host,
        Integer port,
        Double timeout
) {
    public ApiProperties {
        if (host == null) host = "127.0.0.1";
        if (port == null) port = 8900;
        if (timeout == null) timeout = 120.0;
    }
}
