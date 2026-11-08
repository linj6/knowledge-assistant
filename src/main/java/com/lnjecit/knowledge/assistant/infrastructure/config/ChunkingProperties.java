package com.lnjecit.knowledge.assistant.infrastructure.config;

import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingConfig;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties("assistant.chunking")
public class ChunkingProperties implements InitializingBean {
    private int version;
    private com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingStrategy strategy;
    private int chunkSize;
    private int chunkOverlap;

    public ChunkingConfig snapshot() {
        return new ChunkingConfig(version, strategy, chunkSize, chunkOverlap);
    }

    @Override
    public void afterPropertiesSet() {
        snapshot();
    }
}
