package com.lnjecit.knowledge.assistant.retrieval.embedding;

import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures;
import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelResponseChecks;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.VectorSchema;
import java.util.List;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@ConditionalOnProperty(
        name = {"assistant.model.enabled", "assistant.model.online-authorized"},
        havingValue = "true")
public class QueryEmbeddingService {
    private final EmbeddingModel model;

    public QueryEmbeddingService(EmbeddingModel model) {
        this.model = model;
    }

    public QueryVector embed(String query) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("RETRIEVAL_QUERY_REQUIRED");
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("QUERY_EMBEDDING_TRANSACTION_FORBIDDEN");
        // 校验实际客户端，不能仅凭配置或相同维度认定向量空间兼容。
        if (!(model instanceof OpenAiEmbeddingModel client))
            throw ModelFailures.safe("CONFIGURATION", new IllegalStateException("Embedding client type mismatch"));
        OpenAiEmbeddingOptions options = client.getOptions();
        if (!"text-embedding-v4".equals(options.getModel())
                || !Integer.valueOf(VectorSchema.DIMENSION).equals(options.getDimensions())
                || !Integer.valueOf(0).equals(options.getMaxRetries())
                || options.getEncodingFormat() != OpenAiEmbeddingOptions.EncodingFormat.FLOAT)
            throw ModelFailures.safe(
                    "CONFIGURATION",
                    new IllegalStateException(
                            "Embedding options require text-embedding-v4 dimensions=1024 maxRetries=0 encoding=FLOAT"));
        try {
            // 单次调用，不覆盖客户端的超时、编码及零重试选项。
            EmbeddingResponse response = model.call(new EmbeddingRequest(List.of(query), null));
            ModelResponseChecks.embeddings(response, 1, VectorSchema.DIMENSION);
            return new QueryVector(response.getResults().getFirst().getOutput());
        } catch (RuntimeException failure) {
            throw ModelFailures.safe("EMBEDDING_SINGLE", failure);
        }
    }
}
