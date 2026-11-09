package com.lnjecit.knowledge.assistant.ingestion.verification;

import com.lnjecit.knowledge.assistant.infrastructure.persistence.Document;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.DocumentChunk;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersion;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersionStatus;
import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkRepository;
import com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingRepository;
import com.lnjecit.knowledge.assistant.ingestion.indexing.EsChunkDocument;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 只读取 PG 完整快照，不赋予发布或检索可见性。 */
@Service
public class PgIntegrityService {
    private final EmbeddingRepository embeddings;

    private final ChunkRepository chunks;
    private final VerificationRepository verificationRepository;

    public PgIntegrityService(
            EmbeddingRepository embeddings, ChunkRepository chunks, VerificationRepository verificationRepository) {
        this.embeddings = embeddings;
        this.chunks = chunks;
        this.verificationRepository = verificationRepository;
    }

    public record Result(Long documentId, Long indexVersionId, int chunkCount) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public Result verify(Long documentId, Long indexVersionId) {
        Snapshot snapshot = capture(documentId, indexVersionId, false);
        return new Result(documentId, indexVersionId, snapshot.chunks().size());
    }

    public record ChunkSnapshot(
            EsChunkDocument indexed,
            Integer recordVersion,
            Integer sourceStart,
            Integer sourceEnd,
            String headingPath,
            String embedding) {}

    public record Snapshot(
            Long documentId,
            Long knowledgeBaseId,
            Long indexVersionId,
            int expectedChunkCount,
            IndexVersionStatus status,
            String embeddingModel,
            Integer embeddingDimension,
            String chunkingConfig,
            String filename,
            String contentHash,
            String objectKey,
            Instant documentCreatedAt,
            Instant versionCreatedAt,
            List<ChunkSnapshot> chunks) {
        public Snapshot {
            chunks = List.copyOf(chunks);
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public Snapshot snapshot(Long documentId, Long indexVersionId) {
        return capture(documentId, indexVersionId, true);
    }

    /** 锁内重新读取并校验全部数据；仅在完整快照未变化时提交诊断状态。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Snapshot verifiedSnapshot) {
        verificationRepository.lockSnapshot(verifiedSnapshot.documentId(), verifiedSnapshot.indexVersionId());
        Snapshot current = capture(verifiedSnapshot.documentId(), verifiedSnapshot.indexVersionId(), true);
        if (!verifiedSnapshot.equals(current)) throw new IllegalStateException("VERIFICATION_SNAPSHOT_CHANGED");
        // 幂等成功仍要求本轮 PG、ES 和锁内快照全部通过，不重复执行状态更新。
        if (current.status() == IndexVersionStatus.VERIFIED) return;
        if (current.status() != IndexVersionStatus.BUILDING)
            throw new IllegalStateException("VERIFICATION_STATE_CONFLICT");
        verificationRepository.markVerified(current.indexVersionId());
    }

    private Snapshot capture(Long documentId, Long indexVersionId, boolean allowVerified) {
        if (documentId == null || indexVersionId == null) throw new IllegalStateException("PG_INTEGRITY_INVALID");
        // 复用已有归属、状态、数量、顺序、记录版本及有限向量校验；事务保证多次读取来自同一快照。
        List<DocumentChunk> rows = allowVerified
                ? embeddings.readForVerification(documentId, indexVersionId)
                : embeddings.read(documentId, indexVersionId);
        for (DocumentChunk chunk : rows) {
            if (chunk.getEmbedding() == null
                    || chunk.getContent() == null
                    || chunk.getContent().isBlank()
                    || chunk.getSourceStart() == null
                    || chunk.getSourceEnd() == null
                    || chunk.getSourceStart() < 0
                    || chunk.getSourceEnd() <= chunk.getSourceStart())
                throw new IllegalStateException("PG_INTEGRITY_INVALID");
        }
        Document document = chunks.document(documentId);
        IndexVersion version = chunks.version(indexVersionId);
        return new Snapshot(
                documentId,
                document.getKnowledgeBaseId(),
                indexVersionId,
                version.getExpectedChunkCount(),
                version.getStatus(),
                version.getEmbeddingModel(),
                version.getEmbeddingDimension(),
                version.getChunkingConfig(),
                document.getFilename(),
                document.getContentHash(),
                document.getObjectKey(),
                document.getCreatedAt(),
                version.getCreatedAt(),
                rows.stream()
                        .map(c -> new ChunkSnapshot(
                                EsChunkDocument.from(c),
                                c.getVersion(),
                                c.getSourceStart(),
                                c.getSourceEnd(),
                                c.getHeadingPath(),
                                c.getEmbedding()))
                        .toList());
    }
}
