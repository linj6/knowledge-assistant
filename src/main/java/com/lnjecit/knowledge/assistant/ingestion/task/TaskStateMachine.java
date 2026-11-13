package com.lnjecit.knowledge.assistant.ingestion.task;

import com.lnjecit.knowledge.assistant.ingestion.task.entity.ImportTaskStatus;

public final class TaskStateMachine {
    private TaskStateMachine() {}

    public static void require(ImportTaskStatus from, ImportTaskStatus to) {
        // 成功只能由后续真实验证和发布事务写入，本阶段没有成功转换入口。
        if (!((from == ImportTaskStatus.PENDING && to == ImportTaskStatus.RUNNING)
                || (from == ImportTaskStatus.FAILED && to == ImportTaskStatus.RUNNING)
                || (from == ImportTaskStatus.RUNNING && to == ImportTaskStatus.FAILED)))
            throw new TaskFailure(TaskFailure.Code.TRANSITION_CONFLICT);
    }
}
