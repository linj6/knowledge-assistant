package com.lnjecit.knowledge.assistant.infrastructure.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class DashScopeReranker implements Reranker {
    private static final int MAX_RESPONSE_BYTES = 262144;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final HttpClient client;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public DashScopeReranker(URI endpoint, String apiKey, String model, Duration timeout) {
        // HTTP 仅允许本机 Fake；线上配置另行强制 HTTPS，地址只来自可信配置。
        if (endpoint == null
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || !("https".equals(endpoint.getScheme())
                        || ("http".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost())))
                || apiKey == null
                || apiKey.isBlank()
                || apiKey.chars().anyMatch(c -> c < 32 || c == 127)
                || !"qwen3-rerank".equals(model)
                || timeout == null
                || timeout.isZero()
                || timeout.isNegative()) throw new IllegalArgumentException("Invalid rerank client configuration");
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
        client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public List<RerankResult> rerank(String query, List<RerankCandidate> candidates, int topN) {
        if (query == null || query.isBlank() || topN < 1 || candidates == null || candidates.size() > 500)
            throw new IllegalArgumentException("Invalid rerank input");
        List<RerankCandidate> snapshot = new ArrayList<>(candidates);
        HashSet<String> ids = new HashSet<>();
        for (RerankCandidate candidate : snapshot) {
            if (candidate == null
                    || candidate.id() == null
                    || candidate.id().isBlank()
                    || candidate.text() == null
                    || candidate.text().isBlank()
                    || !ids.add(candidate.id())) throw new IllegalArgumentException("Invalid rerank candidate");
        }
        snapshot = List.copyOf(snapshot);
        if (snapshot.isEmpty()) return List.of();
        int count = Math.min(topN, snapshot.size());
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "model",
                    model,
                    "input",
                    Map.of(
                            "query",
                            query,
                            "documents",
                            snapshot.stream().map(RerankCandidate::text).toList()),
                    "parameters",
                    Map.of("top_n", count, "return_documents", false)));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> pending =
                    client.sendAsync(request, info -> limitedBody());
            HttpResponse<byte[]> response;
            try {
                // 总时限覆盖响应体读取，防止只收到响应头后持续阻塞。
                response = pending.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException ex) {
                pending.cancel(true);
                throw ModelFailures.rerankFailure(ModelFailures.Kind.TIMEOUT);
            } catch (java.util.concurrent.ExecutionException ex) {
                throw ModelFailures.rerankFailure(ModelFailures.classify(ex));
            } catch (InterruptedException ex) {
                pending.cancel(true);
                throw ex;
            }
            if (response.statusCode() != 200)
                throw ModelFailures.rerankFailure(ModelFailures.httpStatus(response.statusCode()));
            return parse(response.body(), snapshot, count);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ModelFailures.rerankFailure(ModelFailures.Kind.OTHER);

        } catch (RuntimeException ex) {
            if (ex instanceof ModelFailures.SafeModelException safe) throw safe;
            throw ModelFailures.rerankFailure(ModelFailures.Kind.INVALID_RESPONSE);
        }
    }

    private List<RerankResult> parse(byte[] body, List<RerankCandidate> snapshot, int count) {
        JsonNode root = mapper.readTree(body);
        JsonNode results = root.path("output").path("results");
        if (root.has("code") || !results.isArray() || results.size() != count) throw invalid();
        record Indexed(int index, double score) {}
        List<Indexed> validated = new ArrayList<>();
        HashSet<Integer> seen = new HashSet<>();
        for (JsonNode item : results) {
            JsonNode index = item.path("index");
            JsonNode score = item.path("relevance_score");
            if (!index.isIntegralNumber()
                    || !index.canConvertToInt()
                    || index.asInt() < 0
                    || index.asInt() >= snapshot.size()
                    || !seen.add(index.asInt())
                    || !score.isNumber()
                    || !Double.isFinite(score.asDouble())
                    || score.asDouble() < 0
                    || score.asDouble() > 1) throw invalid();
            validated.add(new Indexed(index.asInt(), score.asDouble()));
        }
        // 全部校验后才排序与映射，供应商数组顺序和正文都不是业务 ID 的来源。
        validated.sort(Comparator.comparingDouble(Indexed::score).reversed().thenComparingInt(Indexed::index));
        return validated.stream()
                .map(item -> new RerankResult(snapshot.get(item.index()).id(), item.score()))
                .toList();
    }

    private static ModelFailures.SafeModelException invalid() {
        return ModelFailures.rerankFailure(ModelFailures.Kind.INVALID_RESPONSE);
    }

    private static HttpResponse.BodySubscriber<byte[]> limitedBody() {
        HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        return new HttpResponse.BodySubscriber<>() {
            private Flow.Subscription subscription;
            private long size;

            public java.util.concurrent.CompletionStage<byte[]> getBody() {
                return delegate.getBody();
            }

            public void onSubscribe(Flow.Subscription value) {
                subscription = value;
                delegate.onSubscribe(value);
            }

            public void onNext(List<ByteBuffer> buffers) {
                for (ByteBuffer buffer : buffers) size += buffer.remaining();
                if (size > MAX_RESPONSE_BYTES) {
                    subscription.cancel();
                    delegate.onError(invalid());
                } else delegate.onNext(buffers);
            }

            public void onError(Throwable error) {
                delegate.onError(error);
            }

            public void onComplete() {
                delegate.onComplete();
            }
        };
    }
}
