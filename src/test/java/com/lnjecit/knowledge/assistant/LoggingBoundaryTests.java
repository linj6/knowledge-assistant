package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.lnjecit.knowledge.assistant.infrastructure.logging.CredentialRedactingConverter;
import com.lnjecit.knowledge.assistant.infrastructure.logging.FailureLog;
import com.lnjecit.knowledge.assistant.infrastructure.web.ApiExceptionHandler;
import com.lnjecit.knowledge.assistant.ingestion.task.ClaimedTask;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskExecutionException;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskProcessor;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskScheduler;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskService;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskWorker;
import com.lnjecit.knowledge.assistant.ingestion.task.WorkerProperties;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

class LoggingBoundaryTests {
    static String render(ILoggingEvent event) {
        PatternLayout layout = new PatternLayout();
        layout.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        layout.getInstanceConverterMap().put("credentials", CredentialRedactingConverter::new);
        layout.getInstanceConverterMap()
                .put(
                        "credentialEx",
                        com.lnjecit.knowledge.assistant.infrastructure.logging.CredentialThrowableConverter::new);
        layout.setPattern("%credentials%n%credentialEx{full}");
        layout.start();
        try {
            return layout.doLayout(event);
        } finally {
            layout.stop();
        }
    }

    @Test
    void productionConsolePatternProtectsDirectSdkAndBindingFailures() throws Exception {
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
        ch.qos.logback.classic.joran.JoranConfigurator configurator =
                new ch.qos.logback.classic.joran.JoranConfigurator();
        configurator.setContext(context);
        try {
            configurator.doConfigure(getClass().getResource("/logback-spring.xml"));
            com.lnjecit.knowledge.assistant.infrastructure.logging.CredentialEnvironmentPostProcessor processor =
                    new com.lnjecit.knowledge.assistant.infrastructure.logging.CredentialEnvironmentPostProcessor();
            processor.postProcessEnvironment(
                    new MockEnvironment().withProperty("spring.datasource.password", "fixture-bound-password"), null);
            Logger logger = context.getLogger("ROOT");
            ch.qos.logback.core.OutputStreamAppender<ILoggingEvent> console =
                    (ch.qos.logback.core.OutputStreamAppender<ILoggingEvent>) logger.getAppender("CONSOLE");
            RuntimeException failure = new IllegalStateException(
                    "Failed to bind rejected value fixture-bound-password",
                    new com.openai.errors.OpenAIIoException("HTTP failed Authorization: Bearer fixture-sdk-token"));
            ch.qos.logback.classic.spi.LoggingEvent event = new ch.qos.logback.classic.spi.LoggingEvent(
                    getClass().getName(), logger, ch.qos.logback.classic.Level.ERROR, "event=BINDING", failure, null);
            event.setMDCPropertyMap(java.util.Map.of("requestId", "fixture-bound-password"));
            String output = new String(console.getEncoder().encode(event), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(output)
                    .contains("Failed to bind", "OpenAIIoException", "Caused by", "LoggingBoundaryTests")
                    .doesNotContain("fixture-bound-password", "fixture-sdk-token", "%nopex", "PARSER_ERROR");
            assertThat(context.getStatusManager().getCopyOfStatusList().stream()
                            .filter(status -> status.getLevel() == ch.qos.logback.core.status.Status.ERROR)
                            .toList())
                    .isEmpty();
        } finally {
            context.stop();
        }
    }

    @Test
    void directSlf4jAlsoRedactsMessagesCausesSuppressedAndDeepChains() {
        new FailureLog(new MockEnvironment().withProperty("assistant.model.api-key", "fixture-api-value"));
        RuntimeException failure = new RuntimeException("root fixture-api-value password=fixture-password");
        failure.addSuppressed(new IllegalStateException("Authorization: Bearer fixture-bearer"));
        for (int i = 0; i < 24; i++) failure = new IllegalStateException("layer-" + i, failure);
        Logger logger = (Logger) LoggerFactory.getLogger("direct-redaction-test");
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            logger.error("event=DIRECT token=fixture-token", failure);
            assertThat(((ThrowableProxy) events.list.getFirst().getThrowableProxy()).getThrowable())
                    .isSameAs(failure);
            String output = render(events.list.getFirst());
            assertThat(output)
                    .contains("layer-23", "root", "Caused by", "Suppressed", "LoggingBoundaryTests")
                    .doesNotContain("fixture-api-value", "fixture-password", "fixture-bearer", "fixture-token");
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void schedulerLogsActualFailureOnceAndDoesNotClaimAfterRecoveryFailure() {
        TaskService tasks = mock(TaskService.class);
        ObjectProvider<TaskProcessor> processors = mock(ObjectProvider.class);
        when(processors.getIfAvailable()).thenReturn((task, context) -> {});
        RuntimeException failure = new IllegalStateException(
                "recovery failed", new SQLException("connection rejected password=fixture-db", "08006"));
        when(tasks.recoverNext(anyString())).thenThrow(failure);
        Logger logger = (Logger) LoggerFactory.getLogger(TaskScheduler.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            new TaskScheduler(tasks, processors, new WorkerProperties()).scan();
            assertThat(events.list.stream()
                            .filter(e -> e.getThrowableProxy() != null)
                            .toList())
                    .hasSize(1);
            ILoggingEvent event = events.list.stream()
                    .filter(e -> e.getThrowableProxy() != null)
                    .findFirst()
                    .orElseThrow();
            assertThat(((ThrowableProxy) event.getThrowableProxy()).getThrowable())
                    .isSameAs(failure);
            assertThat(render(event))
                    .contains("recovery failed", "SQLException", "connection rejected")
                    .doesNotContain("fixture-db");
            verify(tasks, never()).claimNext(anyString());
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void workerKeepsOriginalCauseAndCodeAndControllerKeepsApiProtocol() {
        RuntimeException original =
                new IllegalStateException("vector write failed", new SQLException("constraint rejected", "23505"));
        TaskService tasks = mock(TaskService.class);
        ClaimedTask claim = new ClaimedTask(1L, 2L, 3L, "owner", 1);
        Logger logger = (Logger) LoggerFactory.getLogger(TaskWorker.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            new TaskWorker(tasks, (task, context) -> {
                        throw original;
                    })
                    .execute(claim);
            assertThat(events.list.stream()
                            .filter(e -> e.getThrowableProxy() != null)
                            .toList())
                    .hasSize(1);
            assertThat(render(events.list.getFirst()))
                    .contains("taskId=1", "errorCode=EXECUTION_FAILED", "vector write failed", "SQLException");
            verify(tasks).fail(claim, TaskExecutionException.Code.EXECUTION_FAILED);
            assertThat(new ApiExceptionHandler()
                            .unexpected(original)
                            .getStatusCode()
                            .value())
                    .isEqualTo(500);
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }
}
