package com.lnjecit.knowledge.assistant.ingestion.publish;

import com.lnjecit.knowledge.assistant.document.entity.Document;
import com.lnjecit.knowledge.assistant.document.entity.IndexVersion;
import com.lnjecit.knowledge.assistant.document.mapper.DocumentMapper;
import com.lnjecit.knowledge.assistant.document.mapper.IndexVersionMapper;
import com.lnjecit.knowledge.assistant.ingestion.task.ClaimedTask;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskMapper;
import com.lnjecit.knowledge.assistant.ingestion.task.entity.ImportTask;
import org.springframework.stereotype.Repository;

@Repository
public class IndexPublishRepository {

    private final DocumentMapper documents;
    private final IndexVersionMapper versions;
    private final TaskMapper tasks;
    private final IndexPublishMapper mapper;

    public IndexPublishRepository(
            DocumentMapper documents, IndexVersionMapper versions, TaskMapper tasks, IndexPublishMapper mapper) {

        this.documents = documents;
        this.versions = versions;
        this.tasks = tasks;
        this.mapper = mapper;
    }

    public record Locked(Document document, IndexVersion version, ImportTask task) {}

    public Locked lock(ClaimedTask claim) {
        // 所有发布者统一先锁文档，再锁版本，最后锁任务，避免反向获取锁。
        Document document = documents.selectForUpdate(claim.documentId());
        IndexVersion version = versions.selectForUpdate(claim.indexVersionId());
        ImportTask task = tasks.lockTask(claim.taskId());
        return new Locked(document, version, task);
    }

    public void publish(ClaimedTask claim) {
        if (mapper.publishDocument(claim) != 1) throw new IllegalStateException("PUBLISH_DOCUMENT_CONFLICT");
        if (mapper.publishVersion(claim) != 1) throw new IllegalStateException("PUBLISH_VERSION_CONFLICT");
        if (mapper.succeedTask(claim) != 1)
            throw new IllegalStateException("PUBLISH_TASK_AUTHORITY_EXPIRED_OR_CONFLICT");
    }
}
