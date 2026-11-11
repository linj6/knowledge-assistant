package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelConfiguration;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class EmbeddingBaselineTests {
    @Test
    void configurationFailuresAreSafeAndDoNotSendRequests() throws Exception {
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            for (boolean missingKey : new boolean[] {true, false}) {
                com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties properties =
                        EmbeddingBaselineOnlineIT.properties(endpoint, missingKey ? null : "secret-fixture-value");
                if (!missingKey) properties.setEmbeddingModel(" ");
                RuntimeException failure = org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalStateException.class, () -> new ModelConfiguration()
                                .embeddingModel(properties)
                                .call(new EmbeddingRequest(java.util.List.of("test"), null)));
                assertThat(com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures.classify(failure))
                        .isEqualTo(com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures.Kind.CONFIGURATION);
                IllegalStateException safe =
                        com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures.safe("CONFIGURATION", failure);
                assertThat(safe).hasMessageContaining(missingKey ? "MISSING_API_KEY" : "MISSING_EMBEDDING_MODEL");
                assertThat(safe).hasMessageNotContaining("secret-fixture-value").hasCauseInstanceOf(Throwable.class);
                assertThat(safe.getCause()).isSameAs(failure);
            }
            assertThat(requests.get()).isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rawMessagesStayOutOfPublicMessageAndCausesArePreserved() {
        RuntimeException raw = new IllegalStateException(
                "secret-fixture-value", new RuntimeException("Authorization: secret-fixture-value"));
        assertThat(com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures.safe("EMBEDDING_SINGLE", raw))
                .hasMessageContaining("stage=EMBEDDING_SINGLE")
                .hasMessageNotContaining("secret-fixture-value")
                .hasCauseInstanceOf(Throwable.class);
        assertThat(com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures.safe("CONFIGURATION", raw))
                .hasMessageContaining("kind=CONFIGURATION")
                .hasMessageNotContaining("secret-fixture-value")
                .hasCauseInstanceOf(Throwable.class);
    }

    @Test
    void explicitModelAndDimensionsReachWire() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes =
                    "{\"model\":\"text-embedding-v4\",\"data\":[{\"index\":0,\"embedding\":[1,2]}],\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}"
                            .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            new ModelConfiguration()
                    .embeddingModel(EmbeddingBaselineOnlineIT.properties(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "offline-test-value"))
                    .call(new EmbeddingRequest(EmbeddingBaselineOnlineIT.TEXTS.subList(0, 1), null));
            JsonNode request = JsonMapper.builder().build().readTree(captured.get());
            assertThat(request.path("model").asText()).isEqualTo("text-embedding-v4");
            assertThat(request.path("dimensions").asInt()).isEqualTo(1024);
            assertThat(request.path("encoding_format").asText()).isEqualTo("float");
            assertThat(request.path("input").toString()).contains("RabbitMQ");
        } finally {
            server.stop(0);
        }
    }
}
