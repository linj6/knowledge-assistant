package com.lnjecit.knowledge.assistant.ingestion.chunk;

import com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingProperties;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties("assistant.chunking")
public class ChunkingProperties implements InitializingBean {
    @Autowired
    private EmbeddingProperties embeddingProperties;

    private int version;
    private ChunkingStrategy strategy;
    private int chunkSize;
    private int chunkOverlap;

    public ChunkingConfig snapshot() {
        ChunkingConfig config = new ChunkingConfig(version, strategy, chunkSize, chunkOverlap);
        config.validateMaxCodePoints(embeddingProperties.getMaxInputCodePoints());
        return config;
    }

    @Override
    public void afterPropertiesSet() {
        snapshot();
    }
}
