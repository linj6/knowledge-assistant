package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lnjecit.knowledge.assistant.ingestion.task.ClaimedTask;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskProcessor;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskScheduler;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskService;
import com.lnjecit.knowledge.assistant.ingestion.task.WorkerProperties;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class TaskSchedulerRecoveryTests {
    @Test
    void nextScanCanRecoverPreviousOwnerOnlyAfterExecutionExits() throws Exception {
        TaskService tasks = mock(TaskService.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CopyOnWriteArrayList<String> owners = new CopyOnWriteArrayList<>();
        when(tasks.recoverNext(anyString())).thenAnswer(call -> {
            owners.add(call.getArgument(0));
            return false;
        });
        when(tasks.claimNext(anyString()))
                .thenAnswer(call -> owners.size() == 1
                        ? Optional.of(new ClaimedTask(1L, 2L, 3L, call.getArgument(0), 1))
                        : Optional.empty());
        TaskProcessor processor = (task, context) -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            } catch (InterruptedException failure) {
                throw new IllegalStateException(failure);
            }
        };
        ObjectProvider<TaskProcessor> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(processor);
        WorkerProperties properties = new WorkerProperties();
        properties.setBatchSize(1);
        TaskScheduler scheduler = new TaskScheduler(tasks, provider, properties);
        try (java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Future<?> first = executor.submit(scheduler::scan);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                CountDownLatch secondStarted = new CountDownLatch(1);
                java.util.concurrent.Future<?> second = executor.submit(() -> {
                    secondStarted.countDown();
                    scheduler.scan();
                });
                assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> second.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                assertThat(owners).hasSize(1);
                release.countDown();
                first.get(5, TimeUnit.SECONDS);
                second.get(5, TimeUnit.SECONDS);
                assertThat(owners).hasSize(2);
                assertThat(owners.get(1)).isNotEqualTo(owners.get(0));
            } finally {
                release.countDown();
            }
        }
    }
}
