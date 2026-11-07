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
