package com.databricks.jg.ai_tracing_otel.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

@Service
public class TextAiService {

    private static final Logger logger = LoggerFactory.getLogger(TextAiService.class);

    private final ChatClient chatClient;
    private final Timer operationTimer;
    private final Counter operationCounter;
    private final Counter errorCounter;


    @Value("classpath:/prompts/summarize.st")
    private Resource summarizePrompt;

    @Value("classpath:/prompts/extract.st")
    private Resource extractPrompt;

    @Value("classpath:/prompts/tone.st")
    private Resource tonePrompt;

    public TextAiService(ChatClient.Builder chatClientBuilder, MeterRegistry registry,
                         ObservationRegistry observationRegistry) {
        this.chatClient = chatClientBuilder.build();
        this.operationTimer = Timer.builder("ai.text.toolkit.operation.duration")
                .description("Time spent on AI text operations")
                .tag("service", "text-ai")
                .register(registry);
        this.operationCounter = Counter.builder("ai.text.toolkit.operation.count")
                .description("Number of AI text operations performed")
                .tag("service", "text-ai")
                .register(registry);
        this.errorCounter = Counter.builder("ai.text.toolkit.operation.errors")
                .description("Number of failed AI text operations")
                .tag("service", "text-ai")
                .register(registry);
    }

    public record TextResult(String operation, String output, long durationMs) {
    }

    public TextResult summarize(String text) {
        return executeOperation(() ->
                        chatClient.prompt()
                                .user(u -> u.text(summarizePrompt).param("text", text))
                                .call()
                                .content(),
                "Summarize");
    }

    public TextResult extractTopics(String text) {
        return executeOperation(() ->
                        chatClient.prompt()
                                .user(u -> u.text(extractPrompt).param("text", text))
                                .call()
                                .content(),
                "Extract Topics");
    }

    public TextResult changeTone(String text, String tone) {
        return executeOperation(() ->
                        chatClient.prompt()
                                .user(u -> u.text(tonePrompt).param("text", text).param("tone", tone))
                                .call()
                                .content(),
                "Change Tone (" + tone + ")");
    }


    private TextResult executeOperation(Supplier<String> operation, String displayName) {
        operationCounter.increment();
        long start = System.currentTimeMillis();
        try {
            String result = operationTimer.record(operation);
            return new TextResult(displayName, result, System.currentTimeMillis() - start);
        } catch (Exception e) {
            errorCounter.increment();
            throw e;
        }
    }
}
