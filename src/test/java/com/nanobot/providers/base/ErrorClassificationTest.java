package com.nanobot.providers.base;

import com.nanobot.providers.FallbackProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Error classification & retry-after")
class ErrorClassificationTest {

    @Nested
    @DisplayName("LLMProvider.isTransientResponse")
    class IsTransientResponse {

        @Test
        @DisplayName("should return true for status 429 (retryable)")
        void status429() {
            var resp = LLMResponse.builder()
                    .content("rate limit exceeded")
                    .errorStatusCode(429)
                    .build();
            assertThat(LLMProvider.isTransientResponse(resp)).isTrue();
        }

        @Test
        @DisplayName("should check error_should_retry flag")
        void explicitRetryFlag() {
            var resp = LLMResponse.builder()
                    .errorShouldRetry(true)
                    .build();
            assertThat(LLMProvider.isTransientResponse(resp)).isTrue();
        }

        @Test
        @DisplayName("should return true for 500+")
        void status5xx() {
            var resp = LLMResponse.builder().errorStatusCode(503).build();
            assertThat(LLMProvider.isTransientResponse(resp)).isTrue();
        }

        @Test
        @DisplayName("should return true for timeout/connection kind")
        void timeoutKind() {
            var resp = LLMResponse.builder().errorKind("timeout").build();
            assertThat(LLMProvider.isTransientResponse(resp)).isTrue();
        }

        @Test
        @DisplayName("should check content markers")
        void contentMarkers() {
            var resp = LLMResponse.builder()
                    .content("server error: temporarily unavailable")
                    .build();
            assertThat(LLMProvider.isTransientResponse(resp)).isTrue();
        }
    }

    @Nested
    @DisplayName("LLMProvider.isArrearageResponse")
    class IsArrearageResponse {

        @Test
        @DisplayName("should detect HTTP 402")
        void status402() {
            var resp = LLMResponse.builder().errorStatusCode(402).build();
            assertThat(LLMProvider.isArrearageResponse(resp)).isTrue();
        }

        @Test
        @DisplayName("should detect insufficient_quota token")
        void insufficientQuotaToken() {
            var resp = LLMResponse.builder()
                    .errorType("insufficient_quota")
                    .build();
            assertThat(LLMProvider.isArrearageResponse(resp)).isTrue();
        }

        @Test
        @DisplayName("should detect billing text markers in content")
        void billingMarkers() {
            var resp = LLMResponse.builder()
                    .content("Error: you have insufficient quota")
                    .build();
            assertThat(LLMProvider.isArrearageResponse(resp)).isTrue();
        }

        @Test
        @DisplayName("should return false for rate limit (non-arrearage)")
        void notArrearage() {
            var resp = LLMResponse.builder()
                    .content("rate limit exceeded")
                    .build();
            assertThat(LLMProvider.isArrearageResponse(resp)).isFalse();
        }
    }

    @Nested
    @DisplayName("LLMProvider.extractRetryAfter")
    class ExtractRetryAfter {

        @Test
        @DisplayName("should parse 'retry after N seconds'")
        void retryAfterSeconds() {
            assertThat(LLMProvider.extractRetryAfter("please retry after 10 seconds"))
                    .isEqualTo(10.0);
        }

        @Test
        @DisplayName("should parse 'try again in Ns'")
        void tryAgainIn() {
            assertThat(LLMProvider.extractRetryAfter("try again in 5s"))
                    .isEqualTo(5.0);
        }

        @Test
        @DisplayName("should parse 'retry_after: N'")
        void retryAfterColon() {
            assertThat(LLMProvider.extractRetryAfter("retry_after: 3.5"))
                    .isEqualTo(3.5);
        }

        @Test
        @DisplayName("should parse milliseconds")
        void milliseconds() {
            assertThat(LLMProvider.extractRetryAfter("retry after 500ms"))
                    .isEqualTo(0.5);
        }

        @Test
        @DisplayName("should return null when no match")
        void noMatch() {
            assertThat(LLMProvider.extractRetryAfter("something else")).isNull();
        }
    }

    @Nested
    @DisplayName("LLMProvider.normalizeErrorToken")
    class NormalizeErrorToken {

        @Test
        @DisplayName("should lowercase and strip")
        void lowercaseStrip() {
            assertThat(LLMProvider.normalizeErrorToken("  Insufficient_Quota  "))
                    .isEqualTo("insufficient_quota");
        }

        @Test
        @DisplayName("should return null for null/empty")
        void nullEmpty() {
            assertThat(LLMProvider.normalizeErrorToken(null)).isNull();
            assertThat(LLMProvider.normalizeErrorToken("   ")).isNull();
        }
    }

    @Nested
    @DisplayName("FallbackProvider.shouldFallback")
    class ShouldFallback {

        @Test
        @DisplayName("should fallback for rate_limit kind")
        void fallbackForRateLimit() {
            var resp = LLMResponse.builder()
                    .errorKind("rate_limit")
                    .content("rate limit exceeded")
                    .build();
            assertThat(FallbackProvider.shouldFallback(resp)).isTrue();
        }

        @Test
        @DisplayName("should not fallback for 400")
        void noFallback400() {
            var resp = LLMResponse.builder()
                    .errorStatusCode(400)
                    .build();
            assertThat(FallbackProvider.shouldFallback(resp)).isFalse();
        }

        @Test
        @DisplayName("should not fallback for authentication errors")
        void noFallbackAuth() {
            var resp = LLMResponse.builder()
                    .errorKind("authentication")
                    .errorStatusCode(401)
                    .build();
            assertThat(FallbackProvider.shouldFallback(resp)).isFalse();
        }

        @Test
        @DisplayName("should respect error_should_retry = false")
        void explicitNoRetry() {
            var resp = LLMResponse.builder()
                    .errorShouldRetry(false)
                    .errorKind("timeout")
                    .build();
            assertThat(FallbackProvider.shouldFallback(resp)).isFalse();
        }

        @Test
        @DisplayName("should fallback for 429")
        void fallback429() {
            var resp = LLMResponse.builder()
                    .errorStatusCode(429)
                    .build();
            assertThat(FallbackProvider.shouldFallback(resp)).isTrue();
        }
    }
}
