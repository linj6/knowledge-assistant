package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lnjecit.knowledge.assistant.infrastructure.config.WorkerProperties;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskExecutionContext;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskExecutionException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TaskExecutionTests {
    @Test
    void monotonicDeadline() {
        AtomicLong time = new AtomicLong(100);
        TaskExecutionContext context = new TaskExecutionContext(Duration.ofNanos(10), time::get);
        assertThat(context.remaining()).isEqualTo(Duration.ofNanos(10));
        time.addAndGet(4);
        assertThat(context.remaining()).isEqualTo(Duration.ofNanos(6));
        context.checkTimeout();
        time.addAndGet(6);
        assertThatThrownBy(context::checkTimeout)
                .isInstanceOfSatisfying(TaskExecutionException.class, e -> assertThat(e.code())
                        .isEqualTo(TaskExecutionException.Code.TASK_TIMEOUT));
    }

    @Test
    void validatesConfiguration() {
        WorkerProperties p = new WorkerProperties();
        p.afterPropertiesSet();
        p.setMaxTaskDuration(p.getLeaseDuration());
        assertThatThrownBy(p::afterPropertiesSet).isInstanceOf(IllegalArgumentException.class);
        p.setMaxTaskDuration(Duration.ofMinutes(10));
        p.setMaxAttempts(0);
        assertThatThrownBy(p::afterPropertiesSet).isInstanceOf(IllegalArgumentException.class);
        p.setMaxAttempts(4);
        p.setRetryBackoff(List.of(Duration.ofSeconds(1)));
        assertThatThrownBy(p::afterPropertiesSet).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidCleanupAndJitter() {
        java.util.List<java.util.function.Consumer<WorkerProperties>> changes = List.of(
                p -> p.setRetryJitter(Double.NaN),
                p -> p.setRetryJitter(-0.1),
                p -> p.setRetryJitter(1.1),
                p -> p.setOrphanObjectGracePeriod(Duration.ZERO),
                p -> p.setOrphanCleanupBatchSize(0),
                p -> p.setOrphanCleanupBatchSize(1001),
                p -> p.setLeaseDuration(Duration.ZERO),
                p -> p.setBatchSize(11),
                p -> p.setRetryBackoff(List.of(Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1))));
        for (java.util.function.Consumer<WorkerProperties> change : changes) {
            WorkerProperties p = new WorkerProperties();
            change.accept(p);
            assertThatThrownBy(p::afterPropertiesSet).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
