package com.lnjecit.knowledge.assistant.ingestion.embedding;

import com.lnjecit.knowledge.assistant.infrastructure.persistence.Document;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.DocumentChunk;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersion;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersionStatus;
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

    public EmbeddingRepository(ChunkRepository chunks, EmbeddingMapper mapper) {
        this.chunks = chunks;
        this.mapper = mapper;
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
        List<DocumentChunk> rows = chunks.find(versionId);
        if (v.getExpectedChunkCount() == null
                || v.getExpectedChunkCount() < 1
                || rows.size() != v.getExpectedChunkCount())
            throw new IllegalStateException("Embedding chunk count invalid");
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            DocumentChunk c = rows.get(i);
            if (c.getEmbedding() != null) validateVector(c.getEmbedding());
            if (c.getId() == null
                    || !ids.add(c.getId())
                    || !Objects.equals(c.getChunkOrder(), i)
                    || !documentId.equals(c.getDocumentId())
                    || !versionId.equals(c.getIndexVersionId())
                    || !Objects.equals(d.getKnowledgeBaseId(), c.getKnowledgeBaseId()))
                throw new IllegalStateException("Embedding chunk ownership invalid");
        }
        return rows;
    }

    private static void validateVector(String vector) {
        // 只接受与现有 TypeHandler 一致的有限 float 文本，禁止把原始值带入异常。
        try {
            if (vector == null || !vector.startsWith("[") || !vector.endsWith("]"))
                throw new IllegalArgumentException();
            String[] values = vector.substring(1, vector.length() - 1).split(",", -1);
            if (values.length != 1024) throw new IllegalArgumentException();
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
        if (batch.isEmpty() || batch.size() > 16 || batch.size() != vectors.size())
            throw new IllegalStateException("Embedding batch invalid");
        for (String vector : vectors) validateVector(vector);
        for (int i = 0; i < batch.size(); i++) {
            DocumentChunk c = batch.get(i);
            validateVector(vectors.get(i));
            if (!documentId.equals(c.getDocumentId()) || !versionId.equals(c.getIndexVersionId()))
                throw new IllegalStateException("Embedding batch ownership invalid");
            int updated;
            try {
                updated = mapper.write(c, vectors.get(i));
            } catch (RuntimeException failure) {
                throw new IllegalStateException("Embedding database write failed");
            }
            if (updated != 1) {
                DocumentChunk stored = read(documentId, versionId).stream()
                        .filter(r -> r.getId().equals(c.getId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("Embedding record missing"));
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
