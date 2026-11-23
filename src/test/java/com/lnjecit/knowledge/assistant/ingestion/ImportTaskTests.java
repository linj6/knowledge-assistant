package com.lnjecit.knowledge.assistant.ingestion;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lnjecit.knowledge.assistant.ingestion.task.ClaimedTask;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskFailure;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskProcessor;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskScheduler;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskService;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskStateMachine;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskWorker;
import com.lnjecit.knowledge.assistant.ingestion.task.entity.ImportTaskStatus;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ImportTaskTests {
    @Test
    void transitions() {
        for (ImportTaskStatus from : ImportTaskStatus.values())
            for (ImportTaskStatus to : ImportTaskStatus.values()) {
                if ((from == ImportTaskStatus.PENDING && to == ImportTaskStatus.RUNNING)
                        || (from == ImportTaskStatus.FAILED && to == ImportTaskStatus.RUNNING)
                        || (from == ImportTaskStatus.RUNNING && to == ImportTaskStatus.FAILED))
                    TaskStateMachine.require(from, to);
                else
                    assertThatThrownBy(() -> TaskStateMachine.require(from, to)).isInstanceOf(TaskFailure.class);
            }
    }

    @Test
    void workerDoesNotPublishAndSanitizesFailure() {
        TaskService service = mock(TaskService.class);
        ClaimedTask claim = new ClaimedTask(1L, 2L, 3L, "worker", 1);
        new TaskWorker(service, (t, context) -> {}).execute(claim);
        verifyNoInteractions(service);
        new TaskWorker(service, (t, context) -> {
                    throw new IllegalStateException("secret");
                })
                .execute(claim);
        verify(service)
                .fail(
                        claim,
                        com.lnjecit.knowledge.assistant.ingestion.task.TaskExecutionException.Code.EXECUTION_FAILED);
    }

    @Test
    void schedulerSurvivesFailureAndRequiresProcessor() {
        TaskService service = mock(TaskService.class);
        ObjectProvider<TaskProcessor> provider = mock(ObjectProvider.class);
        TaskScheduler scheduler = new TaskScheduler(
                service, provider, new com.lnjecit.knowledge.assistant.ingestion.task.WorkerProperties());
        scheduler.scan();
        verifyNoInteractions(service);
        when(provider.getIfAvailable()).thenReturn((t, context) -> {
            throw new IllegalStateException("secret");
        });
        ClaimedTask claim = new ClaimedTask(1L, 2L, 3L, "worker", 1);
        when(service.claimNext(anyString())).thenReturn(Optional.of(claim), Optional.empty());
        doThrow(new TaskFailure(TaskFailure.Code.AUTHORITY_EXPIRED))
                .when(service)
                .fail(
                        claim,
                        com.lnjecit.knowledge.assistant.ingestion.task.TaskExecutionException.Code.EXECUTION_FAILED);
        scheduler.scan();
        scheduler.scan();
        verify(service, times(2)).claimNext(anyString());
    }
}
