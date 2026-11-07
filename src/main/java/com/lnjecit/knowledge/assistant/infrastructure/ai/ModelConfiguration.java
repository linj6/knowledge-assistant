package com.lnjecit.knowledge.assistant.infrastructure.ai;

import com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties;
import java.net.URI;
import java.time.Duration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "assistant.model.enabled", havingValue = "true")
public class ModelConfiguration {
    public static void validate(ModelProperties p) {
        p.afterPropertiesSet();
        try {
            URI uri = URI.create(p.getBaseUrl());
            if (uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !(uri.getScheme().equals("https") || uri.getScheme().equals("http"))) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException ex) {
            throw new IllegalStateException("assistant.model.base-url must be an HTTP endpoint without credentials");
        }
        if (p.getChatModel() == null
                || p.getChatModel().isBlank()
                || p.getEmbeddingModel() == null
                || p.getEmbeddingModel().isBlank()) {
            throw new IllegalStateException("assistant.model chat-model and embedding-model are required when enabled");
        }
        if (p.getDimensions() != null && (p.getDimensions() < 1 || p.getDimensions() > 16000)) {
            throw new IllegalStateException("assistant.model.dimensions must be between 1 and 16000");
        }
    }

    @Bean
    public OpenAiChatModel chatModel(ModelProperties p) {
        validate(p);
        // 使用当前版本的客户端构建路径，禁用 SDK 自动重试以限制调用次数。
        return OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .apiKey(p.getApiKey())
                        .baseUrl(p.getBaseUrl())
                        .model(p.getChatModel())
                        .timeout(Duration.ofSeconds(p.getTimeoutSeconds()))
                        .maxRetries(0)
                        .maxTokens(128)
                        .build())
                .build();
    }

    @Bean
    public OpenAiEmbeddingModel embeddingModel(ModelProperties p) {
        validate(p);
        return OpenAiEmbeddingModel.builder()
                .options(OpenAiEmbeddingOptions.builder()
                        .apiKey(p.getApiKey())
                        .baseUrl(p.getBaseUrl())
                        .model(p.getEmbeddingModel())
                        .timeout(Duration.ofSeconds(p.getTimeoutSeconds()))
                        .maxRetries(0)
                        .dimensions(p.getDimensions())
                        .encodingFormat(OpenAiEmbeddingOptions.EncodingFormat.FLOAT)
                        .build())
                .build();
    }
}
