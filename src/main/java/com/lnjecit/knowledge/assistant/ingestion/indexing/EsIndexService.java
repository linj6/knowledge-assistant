package com.lnjecit.knowledge.assistant.ingestion.indexing;

import com.lnjecit.knowledge.assistant.document.entity.Document;
import com.lnjecit.knowledge.assistant.document.entity.DocumentChunk;
import com.lnjecit.knowledge.assistant.document.entity.IndexVersion;
import com.lnjecit.knowledge.assistant.document.entity.IndexVersionStatus;
import com.lnjecit.knowledge.assistant.infrastructure.elasticsearch.EsChunkDocument;
import com.lnjecit.knowledge.assistant.infrastructure.elasticsearch.EsIndexNames;
import com.lnjecit.knowledge.assistant.infrastructure.elasticsearch.EsIndexRepository;
import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@ConditionalOnProperty(name = "assistant.elasticsearch.enabled", havingValue = "true")
public class EsIndexService {
    public static final int DEFAULT_BATCH_SIZE = 100;
    public static final int MAX_BATCH_SIZE = 100;
    private final ChunkRepository chunks;
    private final EsIndexRepository es;
    private final int batchSize;

    public EsIndexService(
            ChunkRepository chunks,
            EsIndexRepository es,
            @Value(
                            "${assistant.elasticsearch.batch-size:#{T(com.lnjecit.knowledge.assistant.ingestion.indexing.EsIndexService).DEFAULT_BATCH_SIZE}}")
                    int batchSize) {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) throw new IllegalArgumentException("ES batch size invalid");
        this.chunks = chunks;
        this.es = es;
        this.batchSize = batchSize;
    }

    public void index(Long documentId, Long versionId) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("ES cannot run inside a transaction");
        List<ChunkSnapshot> documents = read(documentId, versionId);
        es.ensureIndex(EsIndexNames.CHUNKS);
        for (int offset = 0; offset < documents.size(); offset += batchSize) {
            // 同时比较 PG 记录版本，识别正文改动后恢复原值；检查后到请求间仍有竞态，M1-09 须提供 fencing。
            if (!documents.equals(read(documentId, versionId)))
                throw new IllegalStateException("ES chunk snapshot changed");
            es.create(
                    EsIndexNames.CHUNKS,
                    documents.subList(offset, Math.min(documents.size(), offset + batchSize)).stream()
                            .map(ChunkSnapshot::document)
                            .toList());
        }
        if (!documents.equals(read(documentId, versionId)))
            throw new IllegalStateException("ES chunk snapshot changed");
        // 此处仅写入完成，不刷新、不修改版本状态或发布指针。
    }

    private record ChunkSnapshot(EsChunkDocument document, int version) {}

    private List<ChunkSnapshot> read(Long documentId, Long versionId) {
        Document document = chunks.document(documentId);
        IndexVersion version = chunks.version(versionId);
        if (document == null
                || version == null
                || !Objects.equals(documentId, version.getDocumentId())
                || document.getKnowledgeBaseId() == null
                || document.getActiveVersionId() != null
                || version.getPublishedAt() != null
                || version.getStatus() != IndexVersionStatus.BUILDING)
            throw new IllegalStateException("ES version unavailable");
        List<DocumentChunk> rows = chunks.find(versionId);
        if (version.getExpectedChunkCount() == null
                || version.getExpectedChunkCount() < 1
                || rows.size() != version.getExpectedChunkCount())
            throw new IllegalStateException("ES chunk count invalid");
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            DocumentChunk c = rows.get(i);
            if (c.getId() == null
                    || !ids.add(c.getId())
                    || !Objects.equals(c.getChunkOrder(), i)
                    || !Objects.equals(documentId, c.getDocumentId())
                    || !Objects.equals(versionId, c.getIndexVersionId())
                    || !Objects.equals(document.getKnowledgeBaseId(), c.getKnowledgeBaseId())
                    || c.getContent() == null
                    || c.getVersion() == null
                    || c.getVersion() < 0) throw new IllegalStateException("ES chunk identity invalid");
        }
        return rows.stream()
                .map(c -> new ChunkSnapshot(EsChunkDocument.from(c), c.getVersion()))
                .toList();
    }
}
