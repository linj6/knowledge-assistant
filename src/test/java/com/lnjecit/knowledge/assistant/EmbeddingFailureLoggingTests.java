package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lnjecit.knowledge.assistant.document.entity.DocumentChunk;
import com.lnjecit.knowledge.assistant.document.entity.IndexVersion;
import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelConfiguration;
import com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties;
import com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingProperties;
import com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingRepository;
import com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingService;
import com.lnjecit.knowledge.assistant.ingestion.task.ClaimedTask;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskExecutionException;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskService;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskWorker;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class EmbeddingFailureLoggingTests {
    @ParameterizedTest
    @ValueSource(strings = {"http", "response", "pg"})
    void realOfflineEmbeddingFailuresKeepCauseAndOneBoundaryStack(String scenario) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String vector = "[1" + ",0".repeat(1023) + "]";
        String body = scenario.equals("http")
                ? "{\"error\":{\"message\":\"synthetic rejection token=fixture-response-token\",\"type\":\"authentication_error\"}}"
                : "{\"object\":\"list\",\"model\":\"text-embedding-v4\",\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1},\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":"
                        + (scenario.equals("response") ? "[1,0]" : vector) + "}]}";
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(scenario.equals("http") ? 401 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        Logger logger = (Logger) LoggerFactory.getLogger(TaskWorker.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            ModelProperties properties = new ModelProperties();
            properties.setApiKey("fixture-http-key");
            properties.setEmbeddingModel("text-embedding-v4");
            properties.setDimensions(1024);
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            EmbeddingRepository repository = mock(EmbeddingRepository.class);
            IndexVersion version = new IndexVersion();
            version.setEmbeddingModel("text-embedding-v4");
            version.setEmbeddingDimension(1024);
            DocumentChunk chunk = new DocumentChunk();
            chunk.setId(4L);
            chunk.setContent("synthetic input");
            when(repository.version(3L)).thenReturn(version);
            when(repository.read(2L, 3L)).thenReturn(List.of(chunk));
            if (scenario.equals("pg"))
                doThrow(new IllegalStateException(
                                "Embedding database write failed",
                                new SQLException(
                                        "synthetic vector write rejected password=fixture-vector-password", "22000")))
                        .when(repository)
                        .write(eq(2L), eq(3L), anyList(), anyList());
            EmbeddingService service = new EmbeddingService(
                    new ModelConfiguration().embeddingModel(properties), repository, embeddingProperties());
            TaskService tasks = mock(TaskService.class);
            ClaimedTask claim = new ClaimedTask(1L, 2L, 3L, "owner", 1);
            new TaskWorker(tasks, (task, context) -> service.embed(2L, 3L)).execute(claim);
            List<ILoggingEvent> failures = events.list.stream()
                    .filter(event -> event.getThrowableProxy() != null)
                    .toList();
            assertThat(failures).hasSize(1);
            String output = LoggingBoundaryTests.render(failures.getFirst());
            assertThat(output)
                    .contains("taskId=1", "Caused by", "EmbeddingService")
                    .doesNotContain("fixture-response-token", "fixture-vector-password", "fixture-http-key");
            if (scenario.equals("http"))
                assertThat(output).contains("AUTHENTICATION", "UnauthorizedException", "synthetic rejection");
            if (scenario.equals("response"))
                assertThat(output).contains("INVALID_RESPONSE", "Invalid embedding response");
            if (scenario.equals("pg")) assertThat(output).contains("SQLException", "synthetic vector write rejected");
            verify(tasks).fail(claim, TaskExecutionException.Code.EXECUTION_FAILED);
        } finally {
            logger.detachAppender(events);
            events.stop();
            server.stop(0);
        }
    }

    private static EmbeddingProperties embeddingProperties() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setMaxInputCodePoints(800);
        return properties;
    }
}
