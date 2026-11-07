package com.lnjecit.knowledge.assistant.infrastructure.ai;

import com.lnjecit.knowledge.assistant.infrastructure.config.RerankProperties;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "assistant.rerank.enabled", havingValue = "true")
public class RerankConfiguration {
    @Bean
    public Reranker reranker(RerankProperties properties) {
        properties.afterPropertiesSet();
        return new DashScopeReranker(
                URI.create(properties.getEndpoint()),
                properties.getApiKey(),
                properties.getModel(),
                Duration.ofSeconds(properties.getTimeoutSeconds()));
    }
}
