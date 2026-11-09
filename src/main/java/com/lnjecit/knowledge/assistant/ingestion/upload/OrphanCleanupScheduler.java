package com.lnjecit.knowledge.assistant.ingestion.upload;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "assistant.worker.enabled", havingValue = "true")
public class OrphanCleanupScheduler {
    private final OrphanObjectCleanupService cleanup;

    public OrphanCleanupScheduler(OrphanObjectCleanupService cleanup) {
        this.cleanup = cleanup;
    }

    @Scheduled(fixedDelayString = "${assistant.worker.orphan-cleanup-interval:1h}")
    public void scan() {
        try {
            cleanup.cleanup();
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("ORPHAN_SCAN_FAILED");
        }
    }
}
