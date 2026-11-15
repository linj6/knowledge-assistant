package com.lnjecit.knowledge.assistant.ingestion.embedding;

import java.util.LinkedHashMap;
import java.util.Map;
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
    // 应用与持久化批次保护范围，不代表任意供应商模型的输入上限。
    public static final int DEFAULT_BATCH_SIZE = 16;
    public static final int MAX_BATCH_SIZE = 16;
    private int maxInputCodePoints;
    private int batchSize = DEFAULT_BATCH_SIZE;
    private Map<String, Integer> modelMaxBatchSizes = new LinkedHashMap<>();

    public int modelMaxBatchSize(String model) {
        afterPropertiesSet();
        Integer configured = modelMaxBatchSizes.get(model);
        // 已核实的供应商上限不可被配置抬高；允许配置更保守的限制。
        if ("text-embedding-v4".equals(model)) return configured == null ? 10 : Math.min(10, configured);
        if (configured == null)
            throw new IllegalStateException("Embedding model batch limit must be explicitly configured");
        return configured;
    }

    public int effectiveBatchSize(String model) {
        return Math.min(batchSize, modelMaxBatchSize(model));
    }

    public void afterPropertiesSet() {
        if (maxInputCodePoints <= 0)
            throw new IllegalStateException("Embedding max input code points must be positive");
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE)
            throw new IllegalStateException("Embedding batch size must be between 1 and 16");
        if (modelMaxBatchSizes == null
                || modelMaxBatchSizes.entrySet().stream()
                        .anyMatch(entry -> entry.getKey() == null
                                || entry.getKey().isBlank()
                                || entry.getValue() == null
                                || entry.getValue() < 1))
            throw new IllegalStateException(
                    "Embedding model batch limits must have nonblank model names and positive sizes");
    }
}
