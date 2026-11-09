package com.lnjecit.knowledge.assistant.ingestion.verification;

import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@ConditionalOnProperty(name = "assistant.elasticsearch.enabled", havingValue = "true")
public class IndexVerificationService {
    private final PgIntegrityService pg;
    private final EsIntegrityService es;

    public IndexVerificationService(PgIntegrityService pg, EsIntegrityService es) {
        this.pg = pg;
        this.es = es;
    }

    public record Result(
            Long documentId,
            Long indexVersionId,
            int expectedChunkCount,
            int esChunkCount,
            int searchableSampleCount,
            Instant verifiedAt,
            String verificationStatus) {}

    public Result verify(Long documentId, Long indexVersionId) {
        // 拒绝外层事务，保证 ES 请求期间不持有 PG 事务或行锁。
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("VERIFICATION_TRANSACTION_NOT_ALLOWED");
        PgIntegrityService.Snapshot snapshot = pg.snapshot(documentId, indexVersionId);
        EsIntegrityService.Result esResult = es.verify(
                snapshot.knowledgeBaseId(),
                documentId,
                indexVersionId,
                snapshot.chunks().stream()
                        .map(PgIntegrityService.ChunkSnapshot::indexed)
                        .toList());
        pg.complete(snapshot);
        return new Result(
                documentId,
                indexVersionId,
                snapshot.expectedChunkCount(),
                esResult.esChunkCount(),
                esResult.searchableSampleCount(),
                Instant.now(),
                "VERIFIED");
    }
}
