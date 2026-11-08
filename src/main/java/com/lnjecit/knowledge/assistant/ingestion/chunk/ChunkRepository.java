package com.lnjecit.knowledge.assistant.ingestion.chunk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.Document;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.DocumentChunk;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.DocumentChunkMapper;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.DocumentMapper;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersion;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersionMapper;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class ChunkRepository {
    private final DocumentMapper documents;
    private final IndexVersionMapper versions;
    private final DocumentChunkMapper chunks;

    public ChunkRepository(DocumentMapper documents, IndexVersionMapper versions, DocumentChunkMapper chunks) {
        this.documents = documents;
        this.versions = versions;
        this.chunks = chunks;
    }

    public Document document(Long id) {
        return documents.selectById(id);
    }

    public IndexVersion version(Long id) {
        return versions.selectById(id);
    }

    public List<DocumentChunk> find(Long versionId) {
        return chunks.selectList(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getIndexVersionId, versionId)
                .orderByAsc(DocumentChunk::getChunkOrder));
    }

    public void insert(DocumentChunk chunk) {
        chunks.insert(chunk);
    }

    public void initializeCount(Long versionId, int count) {
        if (versions.update(new LambdaUpdateWrapper<IndexVersion>()
                        .eq(IndexVersion::getId, versionId)
                        .eq(IndexVersion::getExpectedChunkCount, 0)
                        .set(IndexVersion::getExpectedChunkCount, count))
                != 1) throw new IllegalStateException("Chunk count initialization conflict");
    }
}
