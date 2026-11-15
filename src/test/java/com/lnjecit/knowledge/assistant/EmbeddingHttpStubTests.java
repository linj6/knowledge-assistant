package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lnjecit.knowledge.assistant.document.entity.DocumentChunk;
import com.lnjecit.knowledge.assistant.document.entity.IndexVersion;
import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelConfiguration;
import com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties;
import com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingProperties;
import com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingRepository;
import com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmbeddingHttpStubTests {
    @Test
    void realClientAndServiceAcceptLocalResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String vector = "[1" + ",0".repeat(1023) + "]";
        server.createContext("/", exchange -> {
            byte[] body =
                    ("{\"object\":\"list\",\"model\":\"text-embedding-v4\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":"
                                    + vector + "}],\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}")
                            .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ModelProperties properties = new ModelProperties();
            properties.setApiKey("local-stub");
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            properties.setEmbeddingModel("text-embedding-v4");
            properties.setDimensions(1024);
            EmbeddingRepository repository = mock(EmbeddingRepository.class);
            IndexVersion version = new IndexVersion();
            version.setEmbeddingModel("text-embedding-v4");
            version.setEmbeddingDimension(1024);
            when(repository.version(3L)).thenReturn(version);
            DocumentChunk pending = new DocumentChunk();
            pending.setId(4L);
            pending.setContent("hello");
            DocumentChunk complete = new DocumentChunk();
            complete.setId(4L);
            complete.setContent("hello");
            complete.setEmbedding(vector);
            when(repository.read(2L, 3L)).thenReturn(List.of(pending), List.of(complete));
            new EmbeddingService(new ModelConfiguration().embeddingModel(properties), repository, embeddingProperties())
                    .embed(2L, 3L);
            verify(repository).write(eq(2L), eq(3L), eq(List.of(pending)), anyList());
            assertThat(complete.getEmbedding()).isNotNull();
            org.mockito.Mockito.clearInvocations(repository);
            when(repository.read(2L, 3L)).thenReturn(List.of(pending));
            java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new EmbeddingService(
                                    new ModelConfiguration().embeddingModel(properties),
                                    repository,
                                    embeddingProperties())
                            .embed(2L, 3L, () -> {
                                if (checks.incrementAndGet() == 3)
                                    throw new com.lnjecit.knowledge.assistant.ingestion.task.TaskExecutionException(
                                            com.lnjecit.knowledge.assistant.ingestion.task.TaskExecutionException.Code
                                                    .TASK_TIMEOUT);
                            }))
                    .isInstanceOf(com.lnjecit.knowledge.assistant.ingestion.task.TaskExecutionException.class);
            verify(repository, org.mockito.Mockito.never()).write(eq(2L), eq(3L), anyList(), anyList());

        } finally {
            server.stop(0);
        }
    }

    private static EmbeddingProperties embeddingProperties() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setMaxInputCodePoints(800);
        return properties;
    }
}
