package com.lnjecit.knowledge.assistant.ingestion.task;

import com.lnjecit.knowledge.assistant.infrastructure.config.WorkerProperties;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class WorkerStatusLogger {
    private final WorkerProperties properties;
    private final ObjectProvider<TaskProcessor> processors;

    public WorkerStatusLogger(WorkerProperties properties, ObjectProvider<TaskProcessor> processors) {
        this.properties = properties;
        this.processors = processors;
    }

    public void report() {
        // 启用开关与处理器条件分别展示，避免把启用配置误认为已经具备领取条件。
        boolean processorAvailable = processors.getIfAvailable() != null;
        ImportEvents.info(
                LoggerFactory.getLogger(WorkerStatusLogger.class),
                "event=APP_CONFIG section=WORKER enabled={} processorAvailable={} scanIntervalMs={} batchSize={} leaseDuration={} maxTaskDuration={} maxAttempts={} retryBackoff={} retryJitter={}",
                properties.isEnabled(),
                processorAvailable,
                properties.getScanInterval().toMillis(),
                properties.getBatchSize(),
                properties.getLeaseDuration(),
                properties.getMaxTaskDuration(),
                properties.getMaxAttempts(),
                properties.getRetryBackoff(),
                properties.getRetryJitter());
    }
}
