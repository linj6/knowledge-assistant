package com.lnjecit.knowledge.assistant.ingestion.task;

import com.lnjecit.knowledge.assistant.infrastructure.config.WorkerProperties;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class WorkerStatusLogger {
    private final WorkerProperties properties;
    private final ObjectProvider<TaskProcessor> processors;

    public WorkerStatusLogger(WorkerProperties properties, ObjectProvider<TaskProcessor> processors) {
        this.properties = properties;
        this.processors = processors;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        // 启用开关与处理器条件分别展示，避免把启用配置误认为已经具备领取条件。
        boolean processorAvailable = processors.getIfAvailable() != null;
        ImportEvents.info(
                LoggerFactory.getLogger(WorkerStatusLogger.class),
                "event=IMPORT_WORKER_STATUS enabled={} processorAvailable={} scanIntervalMs={} batchSize={}",
                properties.isEnabled(),
                processorAvailable,
                properties.getScanInterval().toMillis(),
                properties.getBatchSize());
    }
}
