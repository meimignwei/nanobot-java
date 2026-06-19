package com.nanobot.providers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("AzureOpenAiProvider")
class AzureOpenAiProviderTest {

    // ---- supportsTemperature ----

    @Nested
    @DisplayName("supportsTemperature")
    class SupportsTemperature {

        @Test
        @DisplayName("should return false when reasoning_effort is active")
        void reasoningEffortActive() {
            assertThat(AzureOpenAiProvider.supportsTemperature("gpt-5.2", "medium")).isFalse();
        }

        @Test
        @DisplayName("should return false for gpt-5 models even without reasoning")
        void gpt5NoTemperature() {
            assertThat(AzureOpenAiProvider.supportsTemperature("gpt-5.2-chat", "none")).isFalse();
        }

        @Test
        @DisplayName("should return true for standard gpt-4o")
        void standardModel() {
            assertThat(AzureOpenAiProvider.supportsTemperature("gpt-4o", null)).isTrue();
        }
    }

    // ---- handleError ----

    @Test
    @DisplayName("should return error LLMResponse")
    void handleError() {
        var resp = AzureOpenAiProvider.handleError(new RuntimeException("test error"));
        assertThat(resp.finishReason()).isEqualTo("error");
        assertThat(resp.content()).contains("test error");
    }
}
