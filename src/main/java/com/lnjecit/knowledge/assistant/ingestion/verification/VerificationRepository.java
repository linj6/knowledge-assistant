package com.lnjecit.knowledge.assistant.ingestion.verification;

import com.lnjecit.knowledge.assistant.document.mapper.DocumentMapper;
import com.lnjecit.knowledge.assistant.document.mapper.IndexVersionMapper;
import org.springframework.stereotype.Repository;

@Repository
public class VerificationRepository {

    private final DocumentMapper documents;
    private final IndexVersionMapper versions;
    private final VerificationMapper mapper;

    public VerificationRepository(DocumentMapper documents, IndexVersionMapper versions, VerificationMapper mapper) {

        this.documents = documents;
        this.versions = versions;
        this.mapper = mapper;
    }

    public void lockSnapshot(Long documentId, Long indexVersionId) {
        // 与所有合规构建写入者遵守同一锁顺序。
        documents.selectForUpdate(documentId);
        versions.selectForUpdate(indexVersionId);
    }

    public void markVerified(Long indexVersionId) {
        if (mapper.markVerified(indexVersionId) != 1) throw new IllegalStateException("VERIFICATION_STATE_CONFLICT");
    }
}
