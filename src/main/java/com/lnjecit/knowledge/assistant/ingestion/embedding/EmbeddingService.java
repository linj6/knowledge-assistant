package com.lnjecit.knowledge.assistant.ingestion.embedding;

import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures;
import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelResponseChecks;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.DocumentChunk;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersion;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.VectorSchema;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@ConditionalOnProperty(name = "assistant.model.enabled", havingValue = "true")
public class EmbeddingService {
    private final EmbeddingModel model;
    private final EmbeddingRepository repository;
    private final EmbeddingProperties properties;

    public EmbeddingService(EmbeddingModel model, EmbeddingRepository repository, EmbeddingProperties properties) {
        this.model = model;
        this.repository = repository;
        this.properties = properties;
    }

    public void embed(Long documentId, Long versionId) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Embedding cannot run inside a transaction");
        properties.afterPropertiesSet();
        List<DocumentChunk> initial = repository.read(documentId, versionId);
        IndexVersion version = repository.version(versionId);
        VectorSchema.requireCompatible(version.getEmbeddingDimension());
        EmbeddingOptions options = null;
        if (model instanceof OpenAiEmbeddingModel openai) {
            // 使用实际客户端配置校验快照，不修改全局模型或丢失超时配置。
            if (!version.getEmbeddingModel().equals(openai.getOptions().getModel())
                    || !version.getEmbeddingDimension()
                            .equals(openai.getOptions().getDimensions()))
                throw new IllegalStateException("Embedding client snapshot mismatch");
        } else {
            throw new IllegalStateException("Unsupported embedding client");
        }
        List<DocumentChunk> pending =
                initial.stream().filter(c -> c.getEmbedding() == null).toList();
        for (int offset = 0; offset < pending.size(); offset += properties.getBatchSize()) {
            List<DocumentChunk> batch =
                    pending.subList(offset, Math.min(pending.size(), offset + properties.getBatchSize()));
            List<String> texts = batch.stream().map(DocumentChunk::getContent).toList();
            for (String text : texts)
                if (text == null || text.isBlank() || text.codePointCount(0, text.length()) > 800)
                    throw new IllegalStateException("Embedding input limit exceeded");
            List<String> vectors = new ArrayList<>(Collections.nCopies(batch.size(), null));
            try {
                EmbeddingResponse response = model.call(new EmbeddingRequest(texts, options));
                ModelResponseChecks.embeddings(response, batch.size(), version.getEmbeddingDimension());
                for (Embedding result : response.getResults())
                    vectors.set(result.getIndex(), Arrays.toString(result.getOutput()));
            } catch (RuntimeException failure) {
                throw ModelFailures.safe("EMBEDDING_BATCH", failure);
            }
            repository.write(documentId, versionId, batch, vectors);
        }
        List<DocumentChunk> completed = repository.read(documentId, versionId);
        for (int i = 0; i < initial.size(); i++) {
            DocumentChunk a = initial.get(i), b = completed.get(i);
            if (!Objects.equals(a.getId(), b.getId())
                    || !Objects.equals(a.getContent(), b.getContent())
                    || !Objects.equals(a.getHeadingPath(), b.getHeadingPath())
                    || !Objects.equals(a.getSourceStart(), b.getSourceStart())
                    || !Objects.equals(a.getSourceEnd(), b.getSourceEnd())
                    || b.getEmbedding() == null) throw new IllegalStateException("Embedding completion invalid");
        }
    }
}
