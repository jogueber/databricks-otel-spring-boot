package com.databricks.jg.ai_tracing_otel.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.stream.Collectors;

/**
 * Writes prompt and completion content as span attributes in the format
 * Databricks MLflow expects: gen_ai.input.messages and gen_ai.output.messages.
 * <p>
 * Spring AI's built-in handlers only log content to SLF4J.
 * Databricks requires these as OTel span attributes to display inputs/outputs.
 * <p>
 * Not a @Component — registered via ObservationRegistryCustomizer to avoid
 * circular dependency with ObservationRegistry autoconfiguration.
 *
 * @see <a href="https://docs.databricks.com/aws/en/mlflow3/genai/tracing/third-party/otel-span-attributes">Databricks OTel Span Attributes</a>
 */
public class DatabricksSpanContentHandler implements ObservationHandler<ChatModelObservationContext> {

    private static final AttributeKey<String> INPUT_MESSAGES = AttributeKey.stringKey("gen_ai.input.messages");
    private static final AttributeKey<String> OUTPUT_MESSAGES = AttributeKey.stringKey("gen_ai.output.messages");

    @Override
    public void onStop(ChatModelObservationContext context) {
        Span span = Span.current();
        if (!span.isRecording()) {
            return;
        }

        // Set input messages
        var instructions = context.getRequest().getInstructions();
        if (!CollectionUtils.isEmpty(instructions)) {
            String inputJson = instructions.stream()
                    .map(msg -> "{\"role\":\"%s\",\"content\":\"%s\"}".formatted(
                            msg.getMessageType().getValue(),
                            escapeJson(msg.getText())))
                    .collect(Collectors.joining(",", "[", "]"));
            span.setAttribute(INPUT_MESSAGES, inputJson);
        }

        // Set output messages
        if (context.getResponse() != null && !CollectionUtils.isEmpty(context.getResponse().getResults())) {
            String outputJson = context.getResponse().getResults().stream()
                    .filter(gen -> StringUtils.hasText(gen.getOutput().getText()))
                    .map(gen -> "{\"role\":\"assistant\",\"content\":\"%s\"}".formatted(
                            escapeJson(gen.getOutput().getText())))
                    .collect(Collectors.joining(",", "[", "]"));
            span.setAttribute(OUTPUT_MESSAGES, outputJson);
        }
    }

    @Override
    public boolean supportsContext(Observation.@NonNull Context context) {
        return context instanceof ChatModelObservationContext;
    }

    private static String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
