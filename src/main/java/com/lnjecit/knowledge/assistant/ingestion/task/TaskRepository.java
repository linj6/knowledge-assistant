package com.lnjecit.knowledge.assistant.ingestion.task;

import com.lnjecit.knowledge.assistant.infrastructure.persistence.ImportTask;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.ImportTaskStatus;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class TaskRepository {
    private final TaskMapper mapper;

    public TaskRepository(TaskMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<ClaimedTask> claimNext(String owner) {
        return claim(mapper.lockNext(), owner);
    }

    public Optional<ClaimedTask> claimById(Long id, String owner) {
        ImportTask task = mapper.lockTask(id);
        if (task == null) throw new TaskFailure(TaskFailure.Code.NOT_FOUND);
        if (task.getStatus() == ImportTaskStatus.RUNNING) throw new TaskFailure(TaskFailure.Code.ALREADY_CLAIMED);
        if (task.getStatus() != ImportTaskStatus.PENDING) throw new TaskFailure(TaskFailure.Code.NOT_CLAIMABLE);
        return claim(task, owner);
    }

    private Optional<ClaimedTask> claim(ImportTask task, String owner) {
        if (task == null) return Optional.empty();
        TaskStateMachine.require(task.getStatus(), ImportTaskStatus.RUNNING);
        // 这里只锁任务行；关联检查是普通快照查询，禁止反向获取文档或版本行锁。
        if (!mapper.validOwnership(task)) throw new TaskFailure(TaskFailure.Code.OWNERSHIP_INVALID);
        if (mapper.claim(task.getId(), owner) != 1) throw new TaskFailure(TaskFailure.Code.TRANSITION_CONFLICT);
        return Optional.of(new ClaimedTask(
                task.getId(),
                task.getDocumentId(),
                task.getIndexVersionId(),
                owner,
                Math.addExact(task.getClaimEpoch(), 1L)));
    }

    public void fail(ClaimedTask task) {
        if (mapper.fail(task) != 1) throw new TaskFailure(TaskFailure.Code.AUTHORITY_EXPIRED);
    }
}
