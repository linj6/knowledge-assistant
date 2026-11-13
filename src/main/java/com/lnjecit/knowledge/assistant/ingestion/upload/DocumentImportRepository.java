package com.lnjecit.knowledge.assistant.ingestion.upload;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import com.lnjecit.knowledge.assistant.document.entity.Document;
import com.lnjecit.knowledge.assistant.document.entity.IndexVersion;
import com.lnjecit.knowledge.assistant.document.entity.IndexVersionStatus;
import com.lnjecit.knowledge.assistant.document.mapper.DocumentMapper;
import com.lnjecit.knowledge.assistant.document.mapper.IndexVersionMapper;
import com.lnjecit.knowledge.assistant.infrastructure.storage.ObjectKeyPrefixes;
import com.lnjecit.knowledge.assistant.ingestion.task.entity.ImportTask;
import com.lnjecit.knowledge.assistant.ingestion.task.entity.ImportTaskStatus;
import com.lnjecit.knowledge.assistant.ingestion.task.mapper.ImportTaskMapper;
import com.lnjecit.knowledge.assistant.knowledge.mapper.KnowledgeBaseMapper;
import java.time.Instant;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentImportRepository {
    public static final int MAX_DOCUMENTS_PER_KNOWLEDGE_BASE = 1000;
    private final KnowledgeBaseMapper capacity;

    public void lockCapacity(Long kbId) {
        if (capacity.lockForImportCapacity(kbId) == null)
            throw new BusinessException(CommonErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
    }

    public void checkCapacity(Long kbId) {
        if (countDocuments(kbId) >= MAX_DOCUMENTS_PER_KNOWLEDGE_BASE)
            throw new BusinessException(ImportErrorCode.KNOWLEDGE_BASE_CAPACITY_EXCEEDED);
    }

    private final DocumentMapper documents;
    private final IndexVersionMapper versions;
    private final ImportTaskMapper tasks;

    public DocumentImportRepository(
            DocumentMapper documents,
            IndexVersionMapper versions,
            ImportTaskMapper tasks,
            KnowledgeBaseMapper capacity) {
        this.capacity = capacity;
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

    public long countDocuments(Long kbId) {
        return documents.selectCount(new LambdaQueryWrapper<Document>().eq(Document::getKnowledgeBaseId, kbId));
    }

    public boolean isObjectReferenced(String key) {
        return documents.selectCount(new LambdaQueryWrapper<Document>().eq(Document::getObjectKey, key)) > 0;
    }

    public ImportTask find(Long id) {
        return tasks.selectById(id);
    }

    public ImportTask create(
            Long kbId,
            String filename,
            String hash,
            String key,
            String snapshot,
            String embeddingModel,
            int dimensions) {
        if (key == null
                || !key.startsWith(ObjectKeyPrefixes.ORIGINALS)
                || key.length() <= ObjectKeyPrefixes.ORIGINALS.length())
            throw new IllegalArgumentException("Invalid final object key");
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
        version.setEmbeddingModel(embeddingModel);
        version.setEmbeddingDimension(dimensions);
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
