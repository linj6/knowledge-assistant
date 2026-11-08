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
    public static final int DEFAULT_BATCH_SIZE = 16;
    public static final int MAX_BATCH_SIZE = 16;
    private int batchSize = DEFAULT_BATCH_SIZE;

    public void afterPropertiesSet() {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE)
            throw new IllegalStateException("Embedding batch size must be between 1 and 16");
    }
}
