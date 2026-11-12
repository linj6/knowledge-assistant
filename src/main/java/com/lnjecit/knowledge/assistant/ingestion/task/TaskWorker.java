package com.lnjecit.knowledge.assistant.ingestion.task;

import com.lnjecit.knowledge.assistant.infrastructure.config.WorkerProperties;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class TaskWorker {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TaskWorker.class);
    private final TaskService tasks;
    private final TaskProcessor processor;
    private final java.time.Duration duration;

    public TaskWorker(TaskService tasks, TaskProcessor processor) {
        this(tasks, processor, new WorkerProperties());
    }

    public TaskWorker(TaskService tasks, TaskProcessor processor, WorkerProperties properties) {
        this.duration = properties.getMaxTaskDuration();
        this.tasks = tasks;
        this.processor = processor;
    }

    public void execute(ClaimedTask task) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Worker cannot execute inside transaction");
        long started = System.nanoTime();
        try {
            TaskExecutionContext context = new TaskExecutionContext(duration);
            context.checkTimeout();
            // 发布必须是处理器最后一步；返回后不再按截止时间尝试失败转换。
            processor.process(task, context);
        } catch (RuntimeException failure) {
            com.lnjecit.knowledge.assistant.infrastructure.logging.FailureLog.error(
                    LOG, "TaskWorker", new IllegalStateException("IMPORT_TASK_EXECUTION_FAILED"));
            // 永不持久化异常正文；条件更新只允许当前且未过期的执行者报告失败。
            TaskExecutionException.Code code = failure instanceof TaskExecutionException known
                    ? known.code()
                    : TaskExecutionException.Code.EXECUTION_FAILED;
            try {
                tasks.fail(task, code);
            } catch (RuntimeException updateFailure) {
                ImportEvents.warn(
                        LOG,
                        "event=IMPORT_TASK_FAILURE_UPDATE_FAILED taskId={} documentId={} indexVersionId={} errorCode={} exceptionType={} updateExceptionType={} elapsedMs={}",
                        task.taskId(),
                        task.documentId(),
                        task.indexVersionId(),
                        code.name(),
                        failure.getClass().getName(),
                        updateFailure.getClass().getName(),
                        ImportEvents.elapsed(started));
                throw updateFailure;
            }
            ImportEvents.warn(
                    LOG,
                    "event=IMPORT_TASK_FAILED taskId={} documentId={} indexVersionId={} errorCode={} exceptionType={} elapsedMs={}",
                    task.taskId(),
                    task.documentId(),
                    task.indexVersionId(),
                    code.name(),
                    failure.getClass().getName(),
                    ImportEvents.elapsed(started));
        }
        // 处理器返回不代表已发布，不允许在这里标记 SUCCEEDED。
    }
}
