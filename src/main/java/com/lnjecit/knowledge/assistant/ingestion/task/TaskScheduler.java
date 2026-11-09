package com.lnjecit.knowledge.assistant.ingestion.task;

import com.lnjecit.knowledge.assistant.infrastructure.config.WorkerProperties;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "assistant.worker.enabled", havingValue = "true")
public class TaskScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(TaskScheduler.class);
    private final TaskService tasks;
    private final ObjectProvider<TaskProcessor> processors;
    private final int batchSize;
    private final WorkerProperties properties;
    private final String owner = UUID.randomUUID().toString();

    public TaskScheduler(TaskService tasks, ObjectProvider<TaskProcessor> processors, WorkerProperties properties) {
        int batchSize = properties.getBatchSize();
        this.properties = properties;
        if (batchSize < 1 || batchSize > 10) throw new IllegalArgumentException("Invalid worker batch size");
        this.tasks = tasks;
        this.processors = processors;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${assistant.worker.scan-interval:5s}")
    public void scan() {
        // 没有安全生产处理器时连领取也不执行，避免无意义地消耗租约。
        TaskProcessor processor = processors.getIfAvailable();
        if (processor == null) return;
        for (int i = 0; i < batchSize; i++) {
            try {
                tasks.recoverNext(owner);
                Optional<ClaimedTask> task = tasks.claimNext(owner);
                if (task.isEmpty()) return;
                new TaskWorker(tasks, processor, properties).execute(task.orElseThrow());
            } catch (RuntimeException failure) {
                LOG.warn("IMPORT_TASK_SCAN_FAILED");
                return;
            }
        }
    }
}
