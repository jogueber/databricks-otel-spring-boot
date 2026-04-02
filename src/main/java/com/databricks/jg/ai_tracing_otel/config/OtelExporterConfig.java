package com.databricks.jg.ai_tracing_otel.config;

import com.databricks.sdk.core.DatabricksConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.instrumentation.micrometer.v1_5.OpenTelemetryMeterRegistry;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.function.Supplier;

@Configuration(proxyBeanMethods = false)
public class OtelExporterConfig {

    @Bean
    ObservationRegistryCustomizer<ObservationRegistry> databricksSpanContentCustomizer() {
        return registry -> registry.observationConfig()
                .observationHandler(new DatabricksSpanContentHandler());
    }

    // --- MLflow mode: OTLP/HTTP, traces visible in the MLflow Experiments UI ---

    @Bean
    @ConditionalOnProperty(prefix = "databricks.otel", name = "export-mode",
            havingValue = "mlflow", matchIfMissing = true)
    OtlpHttpSpanExporter otlpHttpSpanExporter(DatabricksConfig config, DatabricksProperties properties) {
        String endpoint = config.getHost() + "/api/2.0/otel/v1/traces";
        Supplier<Map<String, String>> headers = () -> {
            var auth = config.authenticate();
            return Map.of("Authorization", auth.get("Authorization"),
                    "X-Databricks-UC-Table-Name", properties.otel().spansTable());
        };
        return OtlpHttpSpanExporter.builder().setEndpoint(endpoint).setHeaders(headers).build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "databricks.otel", name = "export-mode",
            havingValue = "mlflow", matchIfMissing = true)
    OtlpHttpLogRecordExporter otlpHttpLogRecordExporter(DatabricksConfig config, DatabricksProperties properties) {
        String endpoint = config.getHost() + "/api/2.0/otel/v1/logs";
        Supplier<Map<String, String>> headers = () -> {
            var auth = config.authenticate();
            return Map.of("Authorization", auth.get("Authorization"),
                    "X-Databricks-UC-Table-Name", properties.otel().logsTable());
        };
        return OtlpHttpLogRecordExporter.builder().setEndpoint(endpoint).setHeaders(headers).build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "databricks.otel", name = "export-mode",
            havingValue = "mlflow", matchIfMissing = true)
    OtlpHttpMetricExporter otlpHttpMetricExporter(DatabricksConfig config, DatabricksProperties properties) {
        String endpoint = config.getHost() + "/api/2.0/otel/v1/metrics";
        Supplier<Map<String, String>> headers = () -> {
            var auth = config.authenticate();
            return Map.of("Authorization", auth.get("Authorization"),
                    "X-Databricks-UC-Table-Name", properties.otel().metricsTable());
        };
        return OtlpHttpMetricExporter.builder().setEndpoint(endpoint).setHeaders(headers).build();
    }

    // --- Zerobus mode: OTLP/gRPC, efficient binary transport, raw Delta tables ---

    @Bean
    @ConditionalOnProperty(prefix = "databricks.otel", name = "export-mode", havingValue = "zerobus")
    OtlpGrpcSpanExporter otlpGrpcSpanExporter(ZerobusTokenService tokenService, DatabricksProperties properties) {
        String table = properties.otel().spansTable();
        return OtlpGrpcSpanExporter.builder()
                .setEndpoint("https://" + properties.otel().zerobusEndpoint())
                .setHeaders(zerobusHeaders(tokenService, table))
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "databricks.otel", name = "export-mode", havingValue = "zerobus")
    OtlpGrpcLogRecordExporter otlpGrpcLogRecordExporter(ZerobusTokenService tokenService, DatabricksProperties properties) {
        String table = properties.otel().logsTable();
        return OtlpGrpcLogRecordExporter.builder()
                .setEndpoint("https://" + properties.otel().zerobusEndpoint())
                .setHeaders(zerobusHeaders(tokenService, table))
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "databricks.otel", name = "export-mode", havingValue = "zerobus")
    OtlpGrpcMetricExporter otlpGrpcMetricExporter(ZerobusTokenService tokenService, DatabricksProperties properties) {
        String table = properties.otel().metricsTable();
        return OtlpGrpcMetricExporter.builder()
                .setEndpoint("https://" + properties.otel().zerobusEndpoint())
                .setHeaders(zerobusHeaders(tokenService, table))
                .build();
    }

    private static Supplier<Map<String, String>> zerobusHeaders(ZerobusTokenService tokenService, String tableFullName) {
        return () -> Map.of(
                "Authorization", "Bearer " + tokenService.getToken(tableFullName),
                "x-databricks-zerobus-table-name", tableFullName);
    }

    // --- Metrics pipeline: bridge Micrometer → OTel SDK → metric exporter ---
    // Spring Boot 4.x auto-configures OTel SDK for traces and logs but NOT metrics.
    // We wire SdkMeterProvider ourselves so the metric exporter beans above are used.

    @Bean
    SdkMeterProvider sdkMeterProvider(MetricExporter metricExporter, Resource resource) {
        return SdkMeterProvider.builder()
                .setResource(resource)
                .registerMetricReader(PeriodicMetricReader.builder(metricExporter).build())
                .build();
    }

    @Bean
    MeterRegistry openTelemetryMeterRegistry(OpenTelemetry openTelemetry) {
        return OpenTelemetryMeterRegistry.builder(openTelemetry).build();
    }
}
