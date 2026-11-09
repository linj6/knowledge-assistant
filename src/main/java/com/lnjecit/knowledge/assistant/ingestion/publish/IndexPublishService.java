package com.lnjecit.knowledge.assistant.ingestion.publish;

import com.lnjecit.knowledge.assistant.infrastructure.persistence.Document;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.ImportTask;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.ImportTaskStatus;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersion;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersionStatus;
import com.lnjecit.knowledge.assistant.ingestion.task.ClaimedTask;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IndexPublishService {
    private final IndexPublishRepository repository;

    public IndexPublishService(IndexPublishRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void publish(ClaimedTask claimedTask) {
        if (claimedTask == null
                || claimedTask.documentId() == null
                || claimedTask.indexVersionId() == null
                || claimedTask.taskId() == null
                || claimedTask.leaseOwner() == null
                || claimedTask.leaseOwner().isBlank()) throw new IllegalArgumentException("PUBLISH_INVALID_CLAIM");
        IndexPublishRepository.Locked locked = repository.lock(claimedTask);
        Document document = locked.document();
        IndexVersion version = locked.version();
        ImportTask task = locked.task();
        if (document == null || version == null || task == null)
            throw new IllegalStateException("PUBLISH_ENTITY_NOT_FOUND");
        if (!Objects.equals(version.getDocumentId(), claimedTask.documentId())
                || !Objects.equals(task.getDocumentId(), claimedTask.documentId())
                || !Objects.equals(task.getIndexVersionId(), claimedTask.indexVersionId()))
            throw new IllegalStateException("PUBLISH_OWNERSHIP_INVALID");
        if (!Objects.equals(task.getClaimEpoch(), claimedTask.claimEpoch()))
            throw new IllegalStateException("PUBLISH_CLAIM_CONFLICT");
        // 已提交的完整发布事实允许原领取者重试，不再要求已清理的租约有效。
        if (Objects.equals(document.getActiveVersionId(), claimedTask.indexVersionId())
                && version.getStatus() == IndexVersionStatus.PUBLISHED
                && version.getPublishedAt() != null
                && task.getStatus() == ImportTaskStatus.SUCCEEDED) return;
        if (document.getActiveVersionId() != null
                || version.getStatus() != IndexVersionStatus.VERIFIED
                || version.getPublishedAt() != null
                || version.getExpectedChunkCount() == null
                || version.getExpectedChunkCount() <= 0
                || task.getStatus() != ImportTaskStatus.RUNNING)
            throw new IllegalStateException("PUBLISH_STATE_CONFLICT");
        if (!Objects.equals(task.getLeaseOwner(), claimedTask.leaseOwner()))
            throw new IllegalStateException("PUBLISH_CLAIM_CONFLICT");
        // 租约以最后一条条件更新执行时的数据库时间为准，失败回滚前两条更新。
        repository.publish(claimedTask);
    }
}
