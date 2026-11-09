package com.lnjecit.knowledge.assistant.ingestion.publish;

import com.lnjecit.knowledge.assistant.infrastructure.persistence.Document;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.ImportTask;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersion;
import com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingMapper;
import com.lnjecit.knowledge.assistant.ingestion.task.ClaimedTask;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskMapper;
import org.springframework.stereotype.Repository;

@Repository
public class IndexPublishRepository {
    private final EmbeddingMapper locks;
    private final TaskMapper tasks;
    private final IndexPublishMapper mapper;

    public IndexPublishRepository(EmbeddingMapper locks, TaskMapper tasks, IndexPublishMapper mapper) {
        this.locks = locks;
        this.tasks = tasks;
        this.mapper = mapper;
    }

    public record Locked(Document document, IndexVersion version, ImportTask task) {}

    public Locked lock(ClaimedTask claim) {
        // 所有发布者统一先锁文档，再锁版本，最后锁任务，避免反向获取锁。
        Document document = locks.lockDocument(claim.documentId());
        IndexVersion version = locks.lockVersion(claim.indexVersionId());
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
