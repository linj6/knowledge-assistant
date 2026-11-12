package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelConfiguration;
import com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.DocumentChunk;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.IndexVersion;
import com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingProperties;
import com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingRepository;
import com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class EmbeddingBatchingTests {
    @Test
    void limitsAreModelSpecificAndFailClosed() {
        EmbeddingProperties p = new EmbeddingProperties();
        assertThat(p.effectiveBatchSize("text-embedding-v4")).isEqualTo(10);
        p.setBatchSize(4);
        assertThat(p.effectiveBatchSize("text-embedding-v4")).isEqualTo(4);
        p.setModelMaxBatchSizes(Map.of("text-embedding-v4", 99, "future-model", 3));
        assertThat(p.modelMaxBatchSize("text-embedding-v4")).isEqualTo(10);
        assertThat(p.effectiveBatchSize("future-model")).isEqualTo(3);
        assertThatThrownBy(() -> p.effectiveBatchSize("unknown")).hasMessageContaining("explicitly configured");
        p.setModelMaxBatchSizes(Map.of("future-model", 0));
        assertThatThrownBy(p::afterPropertiesSet).hasMessageContaining("positive sizes");
        p.setModelMaxBatchSizes(Map.of("", 2));
        assertThatThrownBy(p::afterPropertiesSet).hasMessageContaining("nonblank");
        p.setModelMaxBatchSizes(null);
        assertThatThrownBy(p::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownModelNeverCallsClient() {
        EmbeddingRepository repository = mock(EmbeddingRepository.class);
        OpenAiEmbeddingModel model = mock(OpenAiEmbeddingModel.class);
        IndexVersion version = new IndexVersion();
        version.setEmbeddingModel("unknown");
        version.setEmbeddingDimension(1024);
        when(repository.version(3L)).thenReturn(version);
        when(repository.read(2L, 3L)).thenReturn(List.of());
        when(model.getOptions())
                .thenReturn(OpenAiEmbeddingOptions.builder()
                        .model("unknown")
                        .dimensions(1024)
                        .build());
        assertThatThrownBy(() -> new EmbeddingService(model, repository, new EmbeddingProperties()).embed(2L, 3L))
                .hasMessageContaining("explicitly configured");
        verify(model, never()).call(any(EmbeddingRequest.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {16, 4})
    void realSdkSendsOnlyEffectiveBatchesAndMapsReversedIndices(int configured, CapturedOutput output)
            throws Exception {
        run(configured, "none", output);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "write"})
    void failureStopsWithoutFallbackAndResumeSkipsCommittedVectors(String failure, CapturedOutput output)
            throws Exception {
        run(16, failure, output);
    }

    private void run(int configured, String failure, CapturedOutput output) throws Exception {
        List<List<String>> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            JsonNode input = JsonMapper.builder()
                    .build()
                    .readTree(exchange.getRequestBody())
                    .get("input");
            List<String> texts = new ArrayList<>();
            input.forEach(node -> texts.add(node.asString()));
            requests.add(texts);
            int status = failure.equals("http") && requests.size() == 2 ? 500 : 200;
            String body;
            if (status == 500) body = "{\"error\":{\"message\":\"synthetic failure\",\"type\":\"server_error\"}}";
            else {
                List<String> vectors = new ArrayList<>();
                for (int i = texts.size() - 1; i >= 0; i--) {
                    int id = Integer.parseInt(texts.get(i).substring("private-chunk-".length()));
                    vectors.add("{\"object\":\"embedding\",\"index\":" + i + ",\"embedding\":[" + id + ",0".repeat(1023)
                            + "]}");
                }
                body = "{\"object\":\"list\",\"model\":\"text-embedding-v4\",\"data\":[" + String.join(",", vectors)
                        + "],\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            ModelProperties models = new ModelProperties();
            models.setApiKey("fixture-only-key");
            models.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            models.setEmbeddingModel("text-embedding-v4");
            models.setDimensions(1024);
            OpenAiEmbeddingModel client = new ModelConfiguration().embeddingModel(models);
            assertThat(client.getOptions().getMaxRetries()).isZero();
            EmbeddingRepository repository = mock(EmbeddingRepository.class);
            IndexVersion version = new IndexVersion();
            version.setEmbeddingModel("text-embedding-v4");
            version.setEmbeddingDimension(1024);
            when(repository.version(3L)).thenReturn(version);
            List<DocumentChunk> chunks = new ArrayList<>();
            for (int i = 0; i < 27; i++) {
                DocumentChunk chunk = new DocumentChunk();
                chunk.setId((long) i);
                chunk.setContent("private-chunk-" + i);
                chunks.add(chunk);
            }
            when(repository.read(2L, 3L)).thenReturn(chunks);
            AtomicInteger writes = new AtomicInteger();
            doAnswer(call -> {
                        if (failure.equals("write") && writes.incrementAndGet() == 2)
                            throw new IllegalStateException("synthetic PG failure");
                        List<DocumentChunk> batch = call.getArgument(2);
                        List<String> vectors = call.getArgument(3);
                        for (int i = 0; i < batch.size(); i++) {
                            assertThat(vectors.get(i))
                                    .startsWith("[" + batch.get(i).getId() + ".0,");
                            batch.get(i).setEmbedding(vectors.get(i));
                        }
                        return null;
                    })
                    .when(repository)
                    .write(eq(2L), eq(3L), anyList(), anyList());
            EmbeddingProperties properties = new EmbeddingProperties();
            properties.setBatchSize(configured);
            EmbeddingService service = new EmbeddingService(client, repository, properties);
            if (!failure.equals("none")) {
                assertThatThrownBy(() -> service.embed(2L, 3L)).isInstanceOf(IllegalStateException.class);
                assertThat(requests).hasSize(2);
                assertThat(chunks.subList(0, 10)).allMatch(chunk -> chunk.getEmbedding() != null);
                assertThat(chunks.subList(10, 27)).allMatch(chunk -> chunk.getEmbedding() == null);
            }
            service.embed(2L, 3L);
            if (configured == 16)
                assertThat(requests.stream().map(List::size).toList())
                        .containsExactlyElementsOf(
                                failure.equals("none") ? List.of(10, 10, 7) : List.of(10, 10, 10, 7));
            else assertThat(requests.stream().map(List::size).toList()).containsExactly(4, 4, 4, 4, 4, 4, 3);
            assertThat(chunks).allMatch(chunk -> chunk.getEmbedding() != null);
            int count = requests.size();
            service.embed(2L, 3L);
            assertThat(requests).hasSize(count);
            List<String> expected = java.util.stream.IntStream.range(0, 27)
                    .mapToObj(i -> "private-chunk-" + i)
                    .toList();
            if (failure.equals("none"))
                assertThat(requests.stream().flatMap(List::stream).toList()).containsExactlyElementsOf(expected);
            else
                assertThat(requests.subList(2, requests.size()).stream()
                                .flatMap(List::stream)
                                .toList())
                        .containsExactlyElementsOf(expected.subList(10, 27));
            assertThat(output.getOut())
                    .contains(
                            "event=EMBEDDING_BATCH_STARTED",
                            "event=EMBEDDING_BATCH_COMPLETED",
                            "documentId=2 indexVersionId=3",
                            "elapsedMs=")
                    .doesNotContain("private-chunk-", "fixture-only-key");
            if (!failure.equals("none")) assertThat(output.getOut()).contains("event=EMBEDDING_BATCH_FAILED");
        } finally {
            server.stop(0);
        }
    }
}
