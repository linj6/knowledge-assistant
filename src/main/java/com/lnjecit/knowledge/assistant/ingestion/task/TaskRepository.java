package com.lnjecit.knowledge.assistant.ingestion.task;

import com.lnjecit.knowledge.assistant.infrastructure.config.WorkerProperties;
import com.lnjecit.knowledge.assistant.ingestion.task.entity.ImportTask;
import com.lnjecit.knowledge.assistant.ingestion.task.entity.ImportTaskStatus;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class TaskRepository {
    private final TaskMapper mapper;
    private final WorkerProperties properties;

    public TaskRepository(TaskMapper mapper, WorkerProperties properties) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public Optional<ClaimedTask> claimNext(String owner) {
        return claim(mapper.lockNext(properties.getMaxAttempts()), owner);
    }

    public Optional<ClaimedTask> claimById(Long id, String owner) {
        ImportTask task = mapper.lockTask(id);
        if (task == null) throw new TaskFailure(TaskFailure.Code.NOT_FOUND);
        if (task.getStatus() == ImportTaskStatus.RUNNING) throw new TaskFailure(TaskFailure.Code.ALREADY_CLAIMED);
        if (task.getStatus() != ImportTaskStatus.PENDING && task.getStatus() != ImportTaskStatus.FAILED)
            throw new TaskFailure(TaskFailure.Code.NOT_CLAIMABLE);
        return claim(task, owner);
    }

    private Optional<ClaimedTask> claim(ImportTask task, String owner) {
        if (task == null) return Optional.empty();
        TaskStateMachine.require(task.getStatus(), ImportTaskStatus.RUNNING);
        // 这里只锁任务行；关联检查是普通快照查询，禁止反向获取文档或版本行锁。
        if (!mapper.validOwnership(task)) throw new TaskFailure(TaskFailure.Code.OWNERSHIP_INVALID);
        if (mapper.claim(task.getId(), owner, properties.getLeaseDuration().toMillis(), properties.getMaxAttempts())
                != 1) throw new TaskFailure(TaskFailure.Code.TRANSITION_CONFLICT);
        return Optional.of(new ClaimedTask(
                task.getId(),
                task.getDocumentId(),
                task.getIndexVersionId(),
                owner,
                Math.addExact(task.getClaimEpoch(), 1L)));
    }

    public void fail(ClaimedTask task, TaskExecutionException.Code code) {
        // 在任务行锁内读取已执行次数；最终条件更新再次核对执行权和数据库租约。
        ImportTask current = mapper.lockTask(task.taskId());
        Long delay = retryDelay(current, code.retryable());
        if (mapper.fail(task, code.name(), code.safeMessage(), delay) != 1)
            throw new TaskFailure(TaskFailure.Code.AUTHORITY_EXPIRED);
    }

    public boolean recoverNext(String owner) {
        // 仅恢复不同执行者的过期任务；不获取文档或版本行锁，也不假定旧进程已停止。
        ImportTask task = mapper.lockExpired(owner);
        if (task == null) return false;
        Long delay = retryDelay(task, true);
        if (mapper.recover(task.getId(), owner, delay) != 1)
            throw new TaskFailure(TaskFailure.Code.TRANSITION_CONFLICT);
        return true;
    }
    // null 表示不再安排自动重试；重试间隔策略统一放在一处。
    private Long retryDelay(ImportTask task, boolean retryable) {
        if (task == null || !retryable || task.getAttemptCount() >= properties.getMaxAttempts()) return null;
        return properties.retryDelayMillis(task.getAttemptCount());
    }
}
