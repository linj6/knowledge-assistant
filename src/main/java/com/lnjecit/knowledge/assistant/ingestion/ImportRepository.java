package com.lnjecit.knowledge.assistant.ingestion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.Document;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.DocumentMapper;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.ImportTask;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.ImportTaskMapper;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.ImportTaskStatus;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersion;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersionMapper;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersionStatus;
import java.time.Instant;
import org.springframework.stereotype.Repository;

@Repository
public class ImportRepository {
    private final DocumentMapper documents;
    private final IndexVersionMapper versions;
    private final ImportTaskMapper tasks;

    public ImportRepository(DocumentMapper documents, IndexVersionMapper versions, ImportTaskMapper tasks) {
        this.documents = documents;
        this.versions = versions;
        this.tasks = tasks;
    }

    public ImportTask findExisting(Long kbId, String hash) {
        Document document = documents.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getKnowledgeBaseId, kbId)
                .eq(Document::getContentHash, hash));
        if (document == null) return null;
        ImportTask task = tasks.selectOne(new LambdaQueryWrapper<ImportTask>()
                .eq(ImportTask::getDocumentId, document.getId())
                .orderByAsc(ImportTask::getCreatedAt)
                .orderByAsc(ImportTask::getId)
                .last("LIMIT 1"));
        if (task == null) throw new IllegalStateException("Document has no import task");
        return task;
    }

    public ImportTask find(Long id) {
        return tasks.selectById(id);
    }

    public ImportTask create(Long kbId, String filename, String hash, String key, String snapshot) {
        Instant now = Instant.now();
        Document document = new Document();
        document.setKnowledgeBaseId(kbId);
        document.setFilename(filename);
        document.setContentHash(hash);
        document.setObjectKey(key);
        document.setCreatedAt(now);
        documents.insert(document);
        IndexVersion version = new IndexVersion();
        version.setDocumentId(document.getId());
        version.setStatus(IndexVersionStatus.BUILDING);
        version.setExpectedChunkCount(0);
        version.setEmbeddingModel("text-embedding-v4");
        version.setEmbeddingDimension(1024);
        version.setChunkingConfig(snapshot);
        version.setCreatedAt(now);
        versions.insert(version);
        ImportTask task = new ImportTask();
        task.setDocumentId(document.getId());
        task.setIndexVersionId(version.getId());
        task.setStatus(ImportTaskStatus.PENDING);
        task.setAttemptCount(0);
        task.setClaimEpoch(0L);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        tasks.insert(task);
        return task;
    }
}
