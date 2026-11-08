package com.lnjecit.knowledge.assistant.ingestion.indexing;

import com.lnjecit.knowledge.assistant.infrastructure.persistence.Document;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.DocumentChunk;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersion;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersionStatus;
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
    public static final String INDEX = "ka-chunks-v1";
    public static final int DEFAULT_BATCH_SIZE = 100;
    public static final int MAX_BATCH_SIZE = 100;
    private final ChunkRepository chunks;
    private final EsIndexRepository es;
    private final int batchSize;

    public EsIndexService(
            ChunkRepository chunks,
            EsIndexRepository es,
            @Value("${assistant.elasticsearch.batch-size:100}") int batchSize) {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) throw new IllegalArgumentException("ES batch size invalid");
        this.chunks = chunks;
        this.es = es;
        this.batchSize = batchSize;
    }

    public void index(Long documentId, Long versionId) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("ES cannot run inside a transaction");
        List<EsChunkDocument> documents = read(documentId, versionId);
        es.ensureIndex(INDEX);
        for (int offset = 0; offset < documents.size(); offset += batchSize) {
            // 每批前重新校验 PG 身份和正文；这不是 Worker fencing，不能阻止迟到请求。
            if (!documents.equals(read(documentId, versionId)))
                throw new IllegalStateException("ES chunk snapshot changed");
            es.create(INDEX, documents.subList(offset, Math.min(documents.size(), offset + batchSize)));
        }
        if (!documents.equals(read(documentId, versionId)))
            throw new IllegalStateException("ES chunk snapshot changed");
        // 此处仅写入完成，不刷新、不修改版本状态或发布指针。
    }

    private List<EsChunkDocument> read(Long documentId, Long versionId) {
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
        return rows.stream().map(EsChunkDocument::from).toList();
    }
}
