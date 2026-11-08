package com.lnjecit.knowledge.assistant.ingestion.embedding;

import com.lnjecit.knowledge.assistant.infrastructure.persistence.Document;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.DocumentChunk;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.DocumentChunkMapper;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersion;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersionStatus;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.VectorSchema;
import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class EmbeddingRepository {
    private final ChunkRepository chunks;
    private final EmbeddingMapper mapper;
    private final DocumentChunkMapper records;

    public EmbeddingRepository(ChunkRepository chunks, EmbeddingMapper mapper, DocumentChunkMapper records) {
        this.chunks = chunks;
        this.mapper = mapper;
        this.records = records;
    }

    public IndexVersion version(Long id) {
        return chunks.version(id);
    }

    public List<DocumentChunk> read(Long documentId, Long versionId) {
        Document d = chunks.document(documentId);
        IndexVersion v = chunks.version(versionId);
        if (d == null
                || v == null
                || !documentId.equals(v.getDocumentId())
                || d.getActiveVersionId() != null
                || v.getPublishedAt() != null
                || v.getStatus() != IndexVersionStatus.BUILDING)
            throw new IllegalStateException("Embedding version unavailable");
        VectorSchema.requireCompatible(v.getEmbeddingDimension());
        List<DocumentChunk> rows = chunks.find(versionId);
        if (v.getExpectedChunkCount() == null
                || v.getExpectedChunkCount() < 1
                || rows.size() != v.getExpectedChunkCount())
            throw new IllegalStateException("Embedding chunk count invalid");
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            DocumentChunk c = rows.get(i);
            if (c.getEmbedding() != null) validateVector(c.getEmbedding(), v.getEmbeddingDimension());
            if (c.getVersion() == null
                    || c.getVersion() < 0
                    || c.getId() == null
                    || !ids.add(c.getId())
                    || !Objects.equals(c.getChunkOrder(), i)
                    || !documentId.equals(c.getDocumentId())
                    || !versionId.equals(c.getIndexVersionId())
                    || !Objects.equals(d.getKnowledgeBaseId(), c.getKnowledgeBaseId()))
                throw new IllegalStateException("Embedding chunk ownership invalid");
        }
        return rows;
    }

    private static void validateVector(String vector, int dimension) {
        // 只接受与现有 TypeHandler 一致的有限 float 文本，禁止把原始值带入异常。
        try {
            if (vector == null || !vector.startsWith("[") || !vector.endsWith("]"))
                throw new IllegalArgumentException();
            String[] values = vector.substring(1, vector.length() - 1).split(",", -1);
            if (values.length != dimension) throw new IllegalArgumentException();
            for (String value : values)
                if (!Float.isFinite(Float.parseFloat(value.trim()))) throw new IllegalArgumentException();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Embedding stored vector invalid");
        }
    }

    @Transactional
    public void write(Long documentId, Long versionId, List<DocumentChunk> batch, List<String> vectors) {
        // 仅批次写入使用事务，外部模型调用不持有数据库事务。
        read(documentId, versionId);
        IndexVersion snapshot = version(versionId);
        if (batch.isEmpty() || batch.size() > EmbeddingProperties.MAX_BATCH_SIZE || batch.size() != vectors.size())
            throw new IllegalStateException("Embedding batch invalid");
        for (String vector : vectors) validateVector(vector, snapshot.getEmbeddingDimension());
        for (int i = 0; i < batch.size(); i++) {
            DocumentChunk c = batch.get(i);
            if (!documentId.equals(c.getDocumentId()) || !versionId.equals(c.getIndexVersionId()))
                throw new IllegalStateException("Embedding batch ownership invalid");
            if (c.getVersion() == null || c.getVersion() < 0)
                throw new IllegalStateException("Embedding chunk version invalid");
            int updated;
            try {
                updated = mapper.write(
                        c,
                        vectors.get(i),
                        c.getVersion(),
                        snapshot.getEmbeddingModel(),
                        snapshot.getEmbeddingDimension());
            } catch (RuntimeException failure) {
                throw new IllegalStateException("Embedding database write failed");
            }
            if (updated != 1) {
                Document document = chunks.document(documentId);
                IndexVersion current = version(versionId);
                if (document == null
                        || current == null
                        || !documentId.equals(current.getDocumentId())
                        || document.getActiveVersionId() != null
                        || current.getPublishedAt() != null
                        || current.getStatus() != IndexVersionStatus.BUILDING
                        || !Objects.equals(current.getEmbeddingModel(), snapshot.getEmbeddingModel())
                        || !Objects.equals(current.getEmbeddingDimension(), snapshot.getEmbeddingDimension()))
                    throw new IllegalStateException("Embedding version unavailable");
                DocumentChunk stored = records.selectById(c.getId());
                if (stored == null) throw new IllegalStateException("Embedding record missing");
                if (!Objects.equals(stored.getKnowledgeBaseId(), document.getKnowledgeBaseId())
                        || !documentId.equals(stored.getDocumentId())
                        || !versionId.equals(stored.getIndexVersionId())
                        || !Objects.equals(stored.getChunkOrder(), c.getChunkOrder()))
                    throw new IllegalStateException("Embedding chunk ownership invalid");
                if (stored.getEmbedding() != null)
                    validateVector(stored.getEmbedding(), snapshot.getEmbeddingDimension());
                if (stored.getEmbedding() == null && !Objects.equals(stored.getVersion(), c.getVersion()))
                    throw new IllegalStateException("Embedding optimistic lock conflict");
                if (!Objects.equals(stored.getContent(), c.getContent())
                        || !Objects.equals(stored.getHeadingPath(), c.getHeadingPath())
                        || !Objects.equals(stored.getSourceStart(), c.getSourceStart())
                        || !Objects.equals(stored.getSourceEnd(), c.getSourceEnd())
                        || stored.getEmbedding() == null)
                    throw new IllegalStateException("Embedding conditional write conflict");
                // 已完成的记录保留数据库向量，重试绝不覆盖。
            }
        }
    }
}
