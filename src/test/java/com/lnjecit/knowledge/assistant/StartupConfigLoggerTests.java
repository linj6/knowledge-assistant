package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties;
import com.lnjecit.knowledge.assistant.infrastructure.logging.StartupConfigLogger;
import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingProperties;
import com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingProperties;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskProcessor;
import com.lnjecit.knowledge.assistant.ingestion.task.WorkerProperties;
import com.lnjecit.knowledge.assistant.ingestion.task.WorkerStatusLogger;
import com.lnjecit.knowledge.assistant.ingestion.upload.UploadProperties;
import com.lnjecit.knowledge.assistant.rag.context.ContextProperties;
import com.lnjecit.knowledge.assistant.retrieval.rerank.RerankProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

class StartupConfigLoggerTests {
    @Test
    void readyEventEmitsOnceWithExistingWorkerBean() {
        MockEnvironment env = new MockEnvironment();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        WorkerStatusLogger worker =
                new WorkerStatusLogger(new WorkerProperties(), beans.getBeanProvider(TaskProcessor.class));
        StartupConfigLogger logger = new StartupConfigLogger(
                env,
                new ModelProperties(),
                new RerankProperties(),
                new EmbeddingProperties(),
                new UploadProperties(),
                new ChunkingProperties(),
                new ContextProperties(),
                worker);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(events);
        try (org.springframework.context.annotation.AnnotationConfigApplicationContext ctx =
                new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.registerBean(WorkerStatusLogger.class, () -> worker);
            ctx.registerBean(StartupConfigLogger.class, () -> logger);
            ctx.refresh();
            org.springframework.boot.context.event.ApplicationReadyEvent event =
                    new org.springframework.boot.context.event.ApplicationReadyEvent(
                            new org.springframework.boot.SpringApplication(),
                            new String[0],
                            ctx,
                            java.time.Duration.ZERO);
            ctx.publishEvent(event);
            ctx.publishEvent(event);
            assertThat(events.list.stream()
                            .filter(e -> e.getFormattedMessage().contains("event=APP_CONFIG"))
                            .toList())
                    .hasSize(11);
            assertThat(events.list.stream()
                            .filter(e -> e.getFormattedMessage().contains("section=WORKER"))
                            .toList())
                    .hasSize(1);
        } finally {
            root.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void finalBoundConfigurationIsGroupedOnceAndNeverReadsSecretsOrExecutesTasks() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.application.name", "fixture-app")
                .withProperty("server.port", "0")
                .withProperty("local.server.port", "18080")
                .withProperty("spring.mvc.servlet.path", "/api")
                .withProperty("assistant.worker.enabled", "false")
                .withProperty("assistant.model.embedding-model", "original-model")
                .withProperty("assistant.model.dimensions", "1024")
                .withProperty("assistant.model.timeout-seconds", "12")
                .withProperty("assistant.model.online-authorized", "true")
                .withProperty(
                        "assistant.model.base-url",
                        "https://user:fixture-url-password@model.example/v1?api_key=fixture-url-key#fixture-fragment")
                .withProperty(
                        "assistant.rerank.endpoint",
                        "https://user:fixture-rerank-pass@rerank.example/api?token=fixture-token")
                .withProperty("assistant.rerank.online-authorized", "false")
                .withProperty(
                        "spring.datasource.url",
                        "jdbc:postgresql://dbuser:fixture-db-pass@localhost:5432/ka?password=fixture-query-pass")
                .withProperty("spring.datasource.username", "fixture-user")
                .withProperty("spring.datasource.driver-class-name", "org.postgresql.Driver")
                .withProperty("assistant.elasticsearch.enabled", "true")
                .withProperty(
                        "assistant.elasticsearch.endpoint",
                        "http://u:fixture-es-pass@es.example:9200?token=fixture-es-token")
                .withProperty(
                        "assistant.minio.endpoint",
                        "http://u:fixture-minio-pass@minio.example:9000?secret=fixture-minio-token")
                .withProperty("assistant.minio.bucket", "fixture-bucket")
                .withProperty("spring.flyway.enabled", "false")
                .withProperty("assistant.chunking.strategy", "FIXED_CODE_POINT")
                .withProperty("assistant.chunking.version", "1")
                .withProperty("assistant.chunking.chunk-size", "800")
                .withProperty("assistant.chunking.chunk-overlap", "120")
                .withProperty("assistant.rag.context.estimated-token-budget", "4500");
        env.setActiveProfiles("fixture", "override");
        env.getPropertySources()
                .addFirst(new MapPropertySource(
                        "overrides",
                        java.util.Map.of(
                                "assistant.worker.enabled",
                                "true",
                                "assistant.worker.batch-size",
                                "2",
                                "assistant.model.embedding-model",
                                "text-embedding-v4")));
        Binder binder = Binder.get(env);
        ModelProperties model = spy(binder.bindOrCreate("assistant.model", ModelProperties.class));
        RerankProperties rerank = spy(binder.bindOrCreate("assistant.rerank", RerankProperties.class));
        doThrow(new AssertionError("must not read model key")).when(model).getApiKey();
        doThrow(new AssertionError("must not read rerank key")).when(rerank).getApiKey();
        WorkerProperties worker = binder.bindOrCreate("assistant.worker", WorkerProperties.class);
        TaskProcessor processor = mock(TaskProcessor.class);
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("processor", processor);
        StartupConfigLogger logger = new StartupConfigLogger(
                env,
                model,
                rerank,
                new EmbeddingProperties(),
                binder.bindOrCreate("assistant.upload", UploadProperties.class),
                binder.bindOrCreate("assistant.chunking", ChunkingProperties.class),
                binder.bindOrCreate("assistant.rag.context", ContextProperties.class),
                new WorkerStatusLogger(worker, beans.getBeanProvider(TaskProcessor.class)));
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(events);
        try {
            logger.report();
            logger.report();
            List<String> messages = events.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(s -> s.contains("event=APP_CONFIG"))
                    .toList();
            assertThat(messages).hasSize(11);
            String all = String.join("\n", messages);
            assertThat(all)
                    .contains(
                            "activeProfiles=[fixture, override]",
                            "serverPort=18080",
                            "servletPath=/api",
                            "section=WORKER enabled=true processorAvailable=true",
                            "batchSize=2",
                            "maxAttempts=4",
                            "retryBackoff=",
                            "modelName=text-embedding-v4 dimensions=1024 configuredBatchSize=16 modelMaxBatchSize=10 effectiveBatchSize=10",
                            "baseUrl=https://model.example/v1",
                            "datasourceUrl=jdbc:postgresql://localhost:5432/ka",
                            "datasourceUsername=fixture-user",
                            "flywayEnabled=false",
                            "endpoint=http://es.example:9200",
                            "endpoint=http://minio.example:9000",
                            "bucket=fixture-bucket",
                            "endpoint=https://rerank.example/api",
                            "contextTokenBudget=4500",
                            "promptVersion=rag-answer-v1",
                            "defaultVectorTopK=20");
            assertThat(all)
                    .doesNotContain(
                            "fixture-url-password",
                            "fixture-url-key",
                            "fixture-fragment",
                            "fixture-rerank-pass",
                            "fixture-token",
                            "fixture-db-pass",
                            "fixture-query-pass",
                            "fixture-es-pass",
                            "fixture-es-token",
                            "fixture-minio-pass",
                            "fixture-minio-token",
                            "apiKey",
                            "Authorization",
                            "Bearer");
            assertThat(events.list).allMatch(event -> event.getThrowableProxy() == null);
            verify(processor, never()).process(any(), any());
            verify(model, never()).getApiKey();
            verify(rerank, never()).getApiKey();
        } finally {
            root.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void absentOptionalFieldsAndUnknownDisabledModelDoNotBreakReporting() {
        MockEnvironment env = new MockEnvironment();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        StartupConfigLogger logger = new StartupConfigLogger(
                env,
                new ModelProperties(),
                new RerankProperties(),
                new EmbeddingProperties(),
                new UploadProperties(),
                new ChunkingProperties(),
                new ContextProperties(),
                new WorkerStatusLogger(new WorkerProperties(), beans.getBeanProvider(TaskProcessor.class)));
        org.assertj.core.api.Assertions.assertThatCode(logger::report).doesNotThrowAnyException();
        assertThat(StartupConfigLogger.safeUrl("not a URL password=fixture-secret"))
                .isEqualTo("INVALID_URL");
        assertThat(StartupConfigLogger.safeUrl(null)).isEqualTo("UNSET");
    }
}
