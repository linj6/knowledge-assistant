package com.lnjecit.knowledge.assistant.ingestion.embedding;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties("assistant.embedding")
public class EmbeddingProperties implements InitializingBean {
    private int batchSize = 16;

    public void afterPropertiesSet() {
        if (batchSize < 1 || batchSize > 16)
            throw new IllegalStateException("Embedding batch size must be between 1 and 16");
    }
}
