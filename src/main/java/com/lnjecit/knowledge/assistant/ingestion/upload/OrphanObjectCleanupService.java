package com.lnjecit.knowledge.assistant.ingestion.upload;

import com.lnjecit.knowledge.assistant.infrastructure.config.WorkerProperties;
import com.lnjecit.knowledge.assistant.infrastructure.storage.OriginalFileStore;
import com.lnjecit.knowledge.assistant.infrastructure.storage.OriginalObjectGuard;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class OrphanObjectCleanupService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(OrphanObjectCleanupService.class);
    private final OriginalFileStore store;
    private final OrphanObjectRepository repository;
    private final WorkerProperties properties;
    private final OriginalObjectGuard guard;
    private String cursor;

    public OrphanObjectCleanupService(
            OriginalFileStore store,
            OrphanObjectRepository repository,
            WorkerProperties properties,
            OriginalObjectGuard guard) {
        this.store = store;
        this.repository = repository;
        this.properties = properties;
        this.guard = guard;
    }

    public record Result(int deleted, int skipped, int failed) {}

    public synchronized Result cleanup() {
        return cleanup(Clock.systemUTC());
    }

    public synchronized Result cleanup(Clock clock) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Cleanup cannot run in transaction");
        int deleted = 0, skipped = 0, failed = 0;
        Instant cutoff = clock.instant().minus(properties.getOrphanObjectGracePeriod());
        OriginalFileStore.ObjectPage page = store.listOriginals(cursor, properties.getOrphanCleanupBatchSize());
        for (OriginalFileStore.StoredObject object : page.objects()) {
            if (!object.key().startsWith("originals/")
                    || object.lastModified() == null
                    || !object.lastModified().isBefore(cutoff)) {
                skipped++;
                continue;
            }
            OriginalObjectGuard.Handle lock = guard.tryAcquire(object.key());
            if (lock == null) {
                skipped++;
                continue;
            }
            try {
                // PG 检查失败时立即结束本轮，不允许在引用状态未知时删除。
                boolean referenced;
                try {
                    referenced = repository.referenced(object.key());
                } catch (RuntimeException ex) {
                    LOG.warn("ORPHAN_REFERENCE_CHECK_FAILED");
                    return new Result(deleted, skipped, failed + 1);
                }
                if (referenced) {
                    skipped++;
                    continue;
                }
                try {
                    store.delete(object.key());
                    deleted++;
                } catch (RuntimeException ex) {
                    failed++;
                    LOG.warn("ORPHAN_DELETE_FAILED");
                }
            } finally {
                lock.close();
            }
        }
        cursor = page.nextToken();
        LOG.info("ORPHAN_CLEANUP deleted={} skipped={} failed={}", deleted, skipped, failed);
        return new Result(deleted, skipped, failed);
    }
}
