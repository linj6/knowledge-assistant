package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lnjecit.knowledge.assistant.infrastructure.ai.DashScopeReranker;
import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures;
import com.lnjecit.knowledge.assistant.infrastructure.ai.RerankCandidate;
import com.lnjecit.knowledge.assistant.infrastructure.ai.RerankResult;
import com.lnjecit.knowledge.assistant.infrastructure.ai.TokenEstimator;
import com.lnjecit.knowledge.assistant.retrieval.rerank.RerankProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RerankTests {
    private static final List<RerankCandidate> INPUT = List.of(
            new RerankCandidate("A", "private-text"), new RerankCandidate("B", "中文"), new RerankCandidate("C", "code"));

    private static String result(String items) {
        return "{\"output\":{\"results\":[" + items + "]}}";
    }

    private static String item(int index, double score) {
        return "{\"index\":" + index + ",\"relevance_score\":" + score + "}";
    }

    private void response(
            int status,
            String body,
            int topN,
            java.util.function.Consumer<List<RerankResult>> check,
            ModelFailures.Kind failure)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/rerank", exchange -> {
            requests.incrementAndGet();
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer fake-secret");
            JsonNode request = JsonMapper.builder()
                    .build()
                    .readTree(exchange.getRequestBody().readAllBytes());
            assertThat(request.path("model").asString()).isEqualTo("qwen3-rerank");
            assertThat(request.path("input").path("query").asString()).isEqualTo("question");
            assertThat(request.path("input").path("documents").size()).isEqualTo(3);
            assertThat(request.path("parameters").path("top_n").asInt()).isEqualTo(Math.min(topN, 3));
            assertThat(request.path("parameters").path("return_documents").asBoolean())
                    .isFalse();
            if (status == 302) exchange.getResponseHeaders().set("Location", "/rerank");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            DashScopeReranker client = client(server.getAddress().getPort(), Duration.ofSeconds(2));
            if (failure == null) check.accept(client.rerank("question", INPUT, topN));
            else {
                Throwable error =
                        org.assertj.core.api.Assertions.catchThrowable(() -> client.rerank("question", INPUT, topN));
                assertThat(ModelFailures.classify(error)).isEqualTo(failure);
                assertThat(error)
                        .hasMessageNotContaining("fake-secret")
                        .hasMessageNotContaining("private-text")
                        .hasMessageNotContaining(body);
            }
            assertThat(requests.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    private DashScopeReranker client(int port, Duration timeout) {
        return new DashScopeReranker(
                URI.create("http://127.0.0.1:" + port + "/rerank"), "fake-secret", "qwen3-rerank", timeout);
    }

    @Test
    void protocolAndMapping() throws Exception {
        response(
                200,
                result(item(2, .7) + "," + item(0, .9)),
                2,
                values -> assertThat(values).containsExactly(new RerankResult("A", .9), new RerankResult("C", .7)),
                null);
        response(
                200,
                result(item(2, .5) + "," + item(1, .5) + "," + item(0, .5)),
                99,
                values -> assertThat(values).extracting(RerankResult::id).containsExactly("A", "B", "C"),
                null);
    }

    @Test
    void invalidResponses() throws Exception {
        for (String body : List.of(
                "{",
                "null",
                "{}",
                "{\"code\":\"bad\"}",
                result(""),
                result(item(0, .2)),
                result(item(0, .2) + "," + item(1, .3) + "," + item(2, .4)),
                result(item(-1, .2) + "," + item(0, .1)),
                result(item(3, .2) + "," + item(0, .1)),
                result(item(0, .2) + "," + item(0, .1))))
            response(200, body, 2, null, ModelFailures.Kind.INVALID_RESPONSE);
        for (String field : List.of(
                "{}",
                "{\"index\":0.0,\"relevance_score\":0.2}",
                "{\"index\":99999999999999999,\"relevance_score\":0.2}",
                "{\"index\":\"0\",\"relevance_score\":0.2}"))
            response(200, result(field + "," + item(1, .1)), 2, null, ModelFailures.Kind.INVALID_RESPONSE);
        for (String score : List.of("null", "\"0.2\"", "-0.1", "1.1", "NaN", "Infinity", "1e999"))
            response(
                    200,
                    result("{\"index\":0,\"relevance_score\":" + score + "}," + item(1, .1)),
                    2,
                    null,
                    ModelFailures.Kind.INVALID_RESPONSE);
        response(200, result("{\"index\":0}," + item(1, .1)), 2, null, ModelFailures.Kind.INVALID_RESPONSE);
    }

    @Test
    void statusesAndBodyLimit() throws Exception {
        for (int status : List.of(401, 403, 429, 400, 503, 302))
            response(status, "private-text fake-secret", 2, null, ModelFailures.httpStatus(status));
        response(200, "x".repeat(262145), 2, null, ModelFailures.Kind.INVALID_RESPONSE);
    }

    @Test
    void inputsDoNotCallNetwork() {
        DashScopeReranker client = client(1, Duration.ofSeconds(1));
        assertThat(client.rerank("q", List.of(), 1)).isEmpty();
        assertThatThrownBy(() -> client.rerank("", INPUT, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.rerank("q", INPUT, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.rerank("q", null, 1)).isInstanceOf(IllegalArgumentException.class);
        for (List<RerankCandidate> input : List.of(
                List.of(INPUT.getFirst(), INPUT.getFirst()),
                java.util.Collections.nCopies(501, INPUT.getFirst()),
                List.of(new RerankCandidate("", "x")),
                List.of(new RerankCandidate("x", " ")),
                java.util.Arrays.asList((RerankCandidate) null)))
            assertThatThrownBy(() -> client.rerank("q", input, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void connectionAndTimeout() throws Exception {
        HttpServer unused = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = unused.getAddress().getPort();
        unused.start();
        unused.stop(0);
        Throwable error = org.assertj.core.api.Assertions.catchThrowable(
                () -> client(port, Duration.ofSeconds(5)).rerank("q", INPUT, 1));
        assertThat(ModelFailures.classify(error)).isEqualTo(ModelFailures.Kind.CONNECTION);
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext("/rerank", exchange -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        slow.start();
        try {
            Throwable timeout = org.assertj.core.api.Assertions.catchThrowable(() ->
                    client(slow.getAddress().getPort(), Duration.ofMillis(100)).rerank("q", INPUT, 1));
            assertThat(ModelFailures.classify(timeout)).isEqualTo(ModelFailures.Kind.TIMEOUT);
            // JDK HTTP 超时与外层总时限可能先后竞争，两者都必须保留实际 Cause。
            assertThat(timeout.getCause())
                    .isInstanceOfAny(
                            java.util.concurrent.TimeoutException.class, java.util.concurrent.ExecutionException.class);
            if (timeout.getCause() instanceof java.util.concurrent.ExecutionException execution)
                assertThat(execution.getCause()).isInstanceOf(java.net.http.HttpTimeoutException.class);
        } finally {
            slow.stop(0);
        }
    }

    @Test
    void estimatesAndConfiguration() {
        TokenEstimator estimator = new TokenEstimator(0);
        for (String text : List.of("", "中文", "hello", "😀", "int x = 1;", "x".repeat(100000))) {
            long expected = (text.codePointCount(0, text.length()) * 120L + 99) / 100;
            assertThat(estimator.estimate(text).estimatedTokens()).isEqualTo(expected);
            assertThat(estimator.estimate(text).exact()).isFalse();
        }
        assertThat(new TokenEstimator(Integer.MAX_VALUE, 1000).estimate("😀").estimatedTokens())
                .isEqualTo(23622320128L);
        assertThatThrownBy(() -> new TokenEstimator(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenEstimator(0, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> estimator.estimate(null)).isInstanceOf(IllegalArgumentException.class);
        RerankProperties properties = new RerankProperties();
        properties.afterPropertiesSet();
        properties.setEnabled(true);
        assertThatThrownBy(properties::afterPropertiesSet).hasMessage("Invalid assistant.rerank configuration");
        properties.setEndpoint("https://trusted.example/api");
        properties.setApiKey("fake-secret");
        properties.afterPropertiesSet();
        properties.setEndpoint("http://trusted.example/api");
        assertThatThrownBy(properties::afterPropertiesSet).hasMessageNotContaining("fake-secret");
    }

    @Test
    void unknownRuntimeAndSafeFailures() throws Exception {
        DashScopeReranker reranker = client(1, Duration.ofSeconds(1));
        java.net.http.HttpClient http = org.mockito.Mockito.mock(java.net.http.HttpClient.class);
        java.lang.reflect.Field field = DashScopeReranker.class.getDeclaredField("client");
        field.setAccessible(true);
        field.set(reranker, http);
        org.mockito.Mockito.when(http.sendAsync(
                        org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                        org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<byte[]>>any()))
                .thenThrow(new IllegalStateException("fake-secret private-text"));
        Throwable error = org.assertj.core.api.Assertions.catchThrowable(() -> reranker.rerank("q", INPUT, 1));
        assertThat(ModelFailures.classify(error)).isEqualTo(ModelFailures.Kind.OTHER);
        assertThat(error).hasMessageNotContaining("fake-secret").hasMessageNotContaining("private-text");
        for (ModelFailures.Kind kind : ModelFailures.Kind.values()) {
            ModelFailures.SafeModelException safe = ModelFailures.rerankFailure(kind);
            org.mockito.Mockito.when(http.sendAsync(
                            org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                            org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<byte[]>>any()))
                    .thenThrow(safe);
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> reranker.rerank("q", INPUT, 1)))
                    .isSameAs(safe);
            org.mockito.Mockito.reset(http);
        }
    }

    @Test
    void onlineGateRequiresExplicitConfigurationWithoutNetwork() {
        assertThatThrownBy(() -> OnlineConfiguration.rerank(OnlineAuthorizationTests.environment(java.util.Map.of())))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> OnlineConfiguration.rerank(OnlineAuthorizationTests.environment(java.util.Map.of(
                        "assistant.rerank.enabled", "true", "assistant.rerank.online-authorized", "true"))))
                .hasMessage("Invalid assistant.rerank configuration");
        assertThat(RerankOnlineIT.class.getSimpleName()).endsWith("IT");
    }
}
