package com.databricks.jg.ai_tracing_otel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "databricks")
public record DatabricksProperties(
    String configProfile,
    OtelProperties otel
) {
    public record OtelProperties(
        String catalog,
        String schema,
        String tablePrefix,
        ExportMode exportMode,
        String zerobusEndpoint
    ) {
        /** mlflow = OTLP/HTTP to the MLflow endpoint (traces visible in Experiments UI).
         *  zerobus = OTLP/gRPC to the Zerobus endpoint (raw Delta tables, more efficient). */
        public enum ExportMode { MLFLOW, ZEROBUS }

        public String spansTable() {
            return catalog + "." + schema + "." + tablePrefix + "_spans";
        }

        public String logsTable() {
            return catalog + "." + schema + "." + tablePrefix + "_logs";
        }

        public String metricsTable() {
            return catalog + "." + schema + "." + tablePrefix + "_metrics";
        }
    }
}
