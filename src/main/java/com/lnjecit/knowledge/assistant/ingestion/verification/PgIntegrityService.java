package com.lnjecit.knowledge.assistant.ingestion.verification;

import com.lnjecit.knowledge.assistant.infrastructure.persistence.DocumentChunk;
import com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 只读取 PG 完整快照，不赋予发布或检索可见性。 */
@Service
public class PgIntegrityService {
    private final EmbeddingRepository embeddings;

    public PgIntegrityService(EmbeddingRepository embeddings) {
        this.embeddings = embeddings;
    }

    public record Result(Long documentId, Long indexVersionId, int chunkCount) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public Result verify(Long documentId, Long indexVersionId) {
        if (documentId == null || indexVersionId == null) throw new IllegalStateException("PG_INTEGRITY_INVALID");
        // 复用已有归属、状态、数量、顺序、记录版本及有限向量校验；事务保证多次读取来自同一快照。
        List<DocumentChunk> chunks = embeddings.read(documentId, indexVersionId);
        for (DocumentChunk chunk : chunks) {
            if (chunk.getEmbedding() == null
                    || chunk.getContent() == null
                    || chunk.getContent().isBlank()
                    || chunk.getSourceStart() == null
                    || chunk.getSourceEnd() == null
                    || chunk.getSourceStart() < 0
                    || chunk.getSourceEnd() <= chunk.getSourceStart())
                throw new IllegalStateException("PG_INTEGRITY_INVALID");
        }
        return new Result(documentId, indexVersionId, chunks.size());
    }
}
