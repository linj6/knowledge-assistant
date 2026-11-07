package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelConfiguration;
import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelResponseChecks;
import com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingResponse;

class ModelCapabilityTests {
    static ModelProperties properties(String url) {
        var p = new ModelProperties();
        p.setEnabled(true);
        p.setApiKey("offline-test-value");
        p.setChatModel("fake-chat");
        p.setEmbeddingModel("fake-embedding");
        p.setBaseUrl(url);
        p.setTimeoutSeconds(1);
        return p;
    }

    @Test
    void embeddingBoundaryFailuresAreSafe() {
        var nullResults = org.mockito.Mockito.mock(EmbeddingResponse.class);
        org.mockito.Mockito.when(nullResults.getResults()).thenReturn(null);
        var nullOutput = org.mockito.Mockito.mock(Embedding.class);
        org.mockito.Mockito.when(nullOutput.getOutput()).thenReturn(null);
        for (var response : java.util.Arrays.asList(
                null,
                nullResults,
                new EmbeddingResponse(java.util.Arrays.asList((Embedding) null)),
                new EmbeddingResponse(List.of(nullOutput)),
                new EmbeddingResponse(List.of(new Embedding(new float[0], 0))),
                new EmbeddingResponse(List.of(new Embedding(new float[] {1}, -1))))) {
            assertInvalid(response, 1);
        }
        assertInvalid(
                new EmbeddingResponse(List.of(new Embedding(new float[] {1}, 0), new Embedding(new float[] {2}, 0))),
                2);
        assertInvalid(
                new EmbeddingResponse(List.of(new Embedding(new float[] {1}, 0), new Embedding(new float[] {2, 3}, 1))),
                2);
    }

    private void assertInvalid(EmbeddingResponse response, int count) {
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(
                () -> ModelResponseChecks.embeddings(response, count, null));
        assertThat(failure)
                .isInstanceOf(ModelResponseChecks.InvalidResponseException.class)
                .hasNoCause();
        assertThat(com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures.classify(failure)
                        .name())
                .isEqualTo("INVALID_RESPONSE");
    }

    @Test
    void safeDiagnosticsPreserveStage() {
        var secret = new IllegalStateException("offline-test-value");
        for (String stage :
                List.of("CONFIGURATION", "CHAT_PLAIN", "CHAT_JSON", "EMBEDDING_SINGLE", "EMBEDDING_BATCH")) {
            assertThat(com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures.safe(stage, secret))
                    .hasMessageContaining(stage)
                    .hasMessageNotContaining("offline-test-value")
                    .hasNoCause();
        }
        assertThat(com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures.safe("CONFIGURATION", secret))
                .hasMessageContaining("kind=CONFIGURATION");
    }

    @Test
    void embeddingHttpPreservesProviderIndex() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            var request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(request).contains("fake-embedding", "float", "input");
            byte[] bytes =
                    "{\"object\":\"list\",\"model\":\"fake-embedding\",\"data\":[{\"object\":\"embedding\",\"index\":1,\"embedding\":[3,4]},{\"object\":\"embedding\",\"index\":0,\"embedding\":[1,2]}],\"usage\":{\"prompt_tokens\":2,\"total_tokens\":2}}"
                            .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var model = new ModelConfiguration()
                    .embeddingModel(
                            properties("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"));
            var response =
                    model.call(new org.springframework.ai.embedding.EmbeddingRequest(List.of("first", "second"), null));
            assertThat(ModelResponseChecks.embeddings(response, 2, 2)).isEqualTo(2);
            assertThat(response.getResults().stream()
                            .filter(e -> e.getIndex() == 0)
                            .findFirst()
                            .orElseThrow()
                            .getOutput())
                    .containsExactly(1, 2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpFailuresAreClassifiedAndSanitized() throws Exception {
        for (int status : new int[] {401, 403, 429, 503, 200}) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                byte[] bytes = "{invalid offline-test-value".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            try {
                var model = new ModelConfiguration()
                        .chatModel(properties(
                                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"));
                Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> model.call("test"));
                assertThat(failure).isNotNull();
                if (status == 200) assertThat(failure).isInstanceOf(com.openai.errors.OpenAIInvalidDataException.class);
                var kind = com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures.classify(failure);
                if (status == 401 || status == 403) assertThat(kind.name()).isEqualTo("AUTHENTICATION");
                if (status == 200) assertThat(kind.name()).isEqualTo("INVALID_RESPONSE");
                if (status == 429) assertThat(kind.name()).isEqualTo("RATE_LIMIT");
                if (status == 503) assertThat(kind.name()).isEqualTo("SERVER");
                assertThat(com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures.safe(failure))
                        .hasNoCause()
                        .hasMessageNotContaining("offline-test-value");
            } finally {
                server.stop(0);
            }
        }
        assertThat(com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures.classify(
                                new RuntimeException(new java.net.SocketTimeoutException()))
                        .name())
                .isEqualTo("TIMEOUT");
    }

    @Test
    void constructsModels() {
        var config = new ModelConfiguration();
        var p = properties("http://localhost:1/v1");
        assertThat(config.chatModel(p)).isNotNull();
        assertThat(config.embeddingModel(p)).isNotNull();
    }

    @Test
    void validatesConfiguration() {
        var p = properties("invalid");
        assertThatThrownBy(() -> ModelConfiguration.validate(p)).hasMessageContaining("base-url");
        p.setBaseUrl("https://localhost/v1");
        p.setChatModel("");
        assertThatThrownBy(() -> ModelConfiguration.validate(p)).hasMessageContaining("chat-model");
        p.setChatModel("fake");
        p.setDimensions(0);
        assertThatThrownBy(() -> ModelConfiguration.validate(p)).hasMessageContaining("dimensions");
    }

    @Test
    void parsesJson() {
        ModelResponseChecks.json("{\"answer\":\"测试答案\",\"citations\":[\"E1\"]}");
        for (String value :
                List.of("{", "{}", "null", "{\"answer\":1,\"citations\":[]}", "{\"answer\":\"a\",\"citations\":[1]}")) {
            assertThatThrownBy(() -> ModelResponseChecks.json(value)).hasMessage("Invalid structured model response");
        }
    }

    @Test
    void validatesVectorsAndIndices() {
        var reordered = new EmbeddingResponse(
                List.of(new Embedding(new float[] {1, 2}, 1), new Embedding(new float[] {3, 4}, 0)));
        assertThat(ModelResponseChecks.embeddings(reordered, 2, 2)).isEqualTo(2);
        assertThat(reordered.getResults().getFirst().getIndex()).isEqualTo(1);
        for (var invalid : List.of(
                new EmbeddingResponse(List.of()),
                new EmbeddingResponse(List.of(new Embedding(new float[] {Float.NaN}, 0))),
                new EmbeddingResponse(List.of(new Embedding(new float[] {Float.POSITIVE_INFINITY}, 0))),
                new EmbeddingResponse(List.of(new Embedding(new float[] {1}, 1))))) {
            assertThatThrownBy(() -> ModelResponseChecks.embeddings(invalid, 1, null))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> ModelResponseChecks.embeddings(reordered, 2, 3))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void actualHttpProtocol() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains("fake-chat", "messages");
            byte[] response =
                    "{\"id\":\"fake\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"fake-chat\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}"
                            .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var model = new ModelConfiguration()
                    .chatModel(
                            properties("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"));
            assertThat(model.call("test")).isEqualTo("ok");
        } finally {
            server.stop(0);
        }
    }
}
