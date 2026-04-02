package com.databricks.jg.ai_tracing_otel.config;

import com.databricks.sdk.core.DatabricksConfig;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DatabricksProperties.class)
public class DatabricksConfiguration {

    @Bean
    DatabricksConfig databricksConfig(DatabricksProperties properties) {
        var config = new DatabricksConfig()
                .setProfile(properties.configProfile());
        config.resolve();
        return config;
    }

    @Bean
    OpenAiChatModel chatModel(
            DatabricksConfig config,
            ObservationRegistry observationRegistry,
            @Value("${spring.ai.openai.chat.options.model}") String model,
            @Value("${spring.ai.openai.chat.options.temperature}") double temperature) {

        var openAiApi = OpenAiApi.builder()
                .apiKey(() -> {
                    var auth = config.authenticate();
                    return auth.get("Authorization").replace("Bearer ", "");
                })
                .baseUrl(config.getHost() + "/serving-endpoints")
                .build();

        var chatOptions = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .observationRegistry(observationRegistry)
                .defaultOptions(chatOptions)
                .build();
    }
}
