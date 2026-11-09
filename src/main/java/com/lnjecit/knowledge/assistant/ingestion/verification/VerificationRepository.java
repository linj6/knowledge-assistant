package com.lnjecit.knowledge.assistant.ingestion.verification;

import com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class VerificationRepository {
    private final EmbeddingMapper locks;
    private final VerificationMapper mapper;

    public VerificationRepository(EmbeddingMapper locks, VerificationMapper mapper) {
        this.locks = locks;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockSnapshot(Long documentId, Long indexVersionId) {
        // 与所有合规构建写入者遵守同一锁顺序。
        locks.lockDocument(documentId);
        locks.lockVersion(indexVersionId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markVerified(Long indexVersionId) {
        if (mapper.markVerified(indexVersionId) != 1) throw new IllegalStateException("VERIFICATION_STATE_CONFLICT");
    }
}
