package com.nanobot.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * OpenTelemetry SDK 配置，替代 Python 版 langfuse.openai wrapper。
 *
 * <p>机制：当 {@code nanobot.observability.langfuse.public-key} 被设置时，
 * 初始化 OTLP exporter 指向 LangFuse 的 OTel 端点，自动批量上报 trace 数据。
 * 不需要 JVM agent 参数，纯代码级集成。</p>
 *
 * <p>对应 Python openai_compat_provider.py:419-428 的 LangFuse 集成逻辑，
 * 但通过 OTLP 标准协议实现，不依赖特定 LLM SDK wrapper。</p>
 */
@Configuration
public class OpenTelemetryConfig {

    private static final Logger log = LoggerFactory.getLogger(OpenTelemetryConfig.class);

    /** LangFuse Cloud OTel 端点（EU 区） */
    private static final String LANGFUSE_CLOUD_OTEL_ENDPOINT =
            "https://cloud.langfuse.com/api/public/otel";

    private final String endpoint;
    private final String publicKey;
    private final String secretKey;
    private final boolean enabled;

    public OpenTelemetryConfig(
            @Value("${nanobot.observability.langfuse.otel-endpoint:#{null}}") String endpoint,
            @Value("${nanobot.observability.langfuse.public-key:#{null}}") String publicKey,
            @Value("${nanobot.observability.langfuse.secret-key:#{null}}") String secretKey,
            @Value("${nanobot.observability.langfuse.enabled:true}") boolean enabled) {
        this.endpoint = endpoint;
        this.publicKey = publicKey;
        this.secretKey = secretKey;
        this.enabled = enabled;
    }

    /**
     * 构建 OpenTelemetry SDK 实例。
     * 仅在 LangFuse 凭证已配置时启用 trace 导出，否则返回空 no-op SDK。
     */
    @Bean
    public OpenTelemetry openTelemetry() {
        if (!enabled || publicKey == null || publicKey.isBlank()
                || secretKey == null || secretKey.isBlank()) {
            log.info("LangFuse 凭证未配置，trace 导出已禁用（使用 no-op OpenTelemetry SDK）");
            return OpenTelemetry.noop();
        }

        String effectiveEndpoint = (endpoint != null && !endpoint.isBlank())
                ? endpoint : LANGFUSE_CLOUD_OTEL_ENDPOINT;

        // 构造 Basic Auth header: base64(publicKey:secretKey)
        String authHeader = java.util.Base64.getEncoder()
                .encodeToString((publicKey + ":" + secretKey).getBytes());

        log.info("LangFuse OTel 追踪已启用，端点: {}", effectiveEndpoint);

        // 使用 HTTP/protobuf 协议 exporter（LangFuse 推荐）
        OtlpHttpSpanExporter spanExporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(effectiveEndpoint + "/v1/traces")
                .addHeader("Authorization", "Basic " + authHeader)
                .setTimeout(Duration.ofSeconds(10))
                .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter)
                        .setMaxQueueSize(2048)
                        .setMaxExportBatchSize(512)
                        .setScheduleDelay(Duration.ofSeconds(5))
                        .build())
                .setSampler(Sampler.alwaysOn())
                .build();

        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .buildAndRegisterGlobal();
    }

    /**
     * 提供 nanobot 专用的 Tracer 实例，LLM provider 和 agent 层都用它创建 span。
     */
    @Bean
    public Tracer nanobotTracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("nanobot", "0.1.0");
    }
}
