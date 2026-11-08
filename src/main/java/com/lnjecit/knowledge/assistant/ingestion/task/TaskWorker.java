package com.lnjecit.knowledge.assistant.ingestion.task;

import org.springframework.transaction.support.TransactionSynchronizationManager;

public class TaskWorker {
    private final TaskService tasks;
    private final TaskProcessor processor;

    public TaskWorker(TaskService tasks, TaskProcessor processor) {
        this.tasks = tasks;
        this.processor = processor;
    }

    public void execute(ClaimedTask task) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Worker cannot execute inside transaction");
        try {
            processor.process(task);
        } catch (RuntimeException failure) {
            // 永不持久化异常正文；条件更新只允许当前且未过期的执行者报告失败。
            tasks.fail(task);
        }
        // 处理器返回不代表已发布，不允许在这里标记 SUCCEEDED。
    }
}
