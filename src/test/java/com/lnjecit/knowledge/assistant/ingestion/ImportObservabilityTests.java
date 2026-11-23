package com.lnjecit.knowledge.assistant.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lnjecit.knowledge.assistant.common.RequestIdContext;
import com.lnjecit.knowledge.assistant.ingestion.task.ClaimedTask;
import com.lnjecit.knowledge.assistant.ingestion.task.ImportEvents;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskExecutionException;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskProcessor;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskScheduler;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskService;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskWorker;
import com.lnjecit.knowledge.assistant.ingestion.task.WorkerProperties;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

class ImportObservabilityTests {
    @Test
    void loggerFailureDoesNotChangeBusinessResult() {
        org.slf4j.Logger logger = mock(org.slf4j.Logger.class);
        doThrow(new IllegalStateException("secret")).when(logger).info(anyString(), any(Object[].class));
        doThrow(new IllegalStateException("secret")).when(logger).warn(anyString(), any(Object[].class));
        assertThatCode(() -> {
                    ImportEvents.info(logger, "event={}", "IMPORT_PUBLISHED");
                    ImportEvents.warn(logger, "event={}", "IMPORT_TASK_FAILED");
                })
                .doesNotThrowAnyException();
    }

    @Test
    void failedStateWriteHasSafeDiagnosticAndKeepsOriginalPropagation() {
        TaskService tasks = mock(TaskService.class);
        ClaimedTask claim = new ClaimedTask(1L, 2L, 3L, "owner", 1);
        RuntimeException update = new IllegalStateException("database secret");
        doThrow(update).when(tasks).fail(claim, TaskExecutionException.Code.EXECUTION_FAILED);
        TaskProcessor processor = (task, context) -> {
            throw new IllegalArgumentException("document secret");
        };
        Logger logger = (Logger) LoggerFactory.getLogger(TaskWorker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> new TaskWorker(tasks, processor).execute(claim))
                    .isSameAs(update);
            assertThat(appender.list).hasSize(2);
            assertThat(appender.list.getFirst().getThrowableProxy().getMessage())
                    .contains("document secret");
            String message = appender.list.getLast().getFormattedMessage();
            assertThat(message)
                    .contains(
                            "IMPORT_TASK_FAILURE_UPDATE_FAILED",
                            "taskId=1",
                            "errorCode=EXECUTION_FAILED",
                            "IllegalArgumentException",
                            "IllegalStateException")
                    .doesNotContain("secret");
            assertThat(appender.list.getLast().getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void schedulerClearsStaleRequestIdAndLogsOnlyAfterClaimReturns() {
        TaskService tasks = mock(TaskService.class);
        ObjectProvider<TaskProcessor> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn((task, context) -> assertThat(RequestIdContext.current())
                .isNull());
        when(tasks.claimNext(anyString())).thenAnswer(call -> {
            assertThat(RequestIdContext.current()).isNull();
            return Optional.of(new ClaimedTask(1L, 2L, 3L, call.getArgument(0), 1));
        });
        RequestIdContext.set("stale-request");
        try {
            new TaskScheduler(tasks, provider, new WorkerProperties()).scan();
            assertThat(RequestIdContext.current()).isNull();
            verify(tasks).claimNext(anyString());
            verify(tasks, never()).fail(any(), any());
        } finally {
            RequestIdContext.clear();
        }
    }
}
