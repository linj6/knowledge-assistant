package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class ElasticsearchIT {
    @Container
    static final ElasticsearchContainer elasticsearch = new ElasticsearchContainer(
                    DockerImageName.parse("knowledge-assistant-elasticsearch:8.18.0-ik8.18.0")
                            .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch"))
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withImagePullPolicy(image -> false)
            .withStartupTimeout(Duration.ofSeconds(180));

    private String request(String method, String path, String body) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://" + elasticsearch.getHttpHostAddress() + path))
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/json")
                            .method(method, HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            // 删除不存在的测试索引属于幂等清理，其他响应仍须成功。
            if (!("DELETE".equals(method) && response.statusCode() == 404)) {
                assertThat(response.statusCode()).isBetween(200, 299);
            }
            return response.body();
        }
    }

    @Test
    void analyzerAndBm25() throws Exception {
        assertThat(request("GET", "/", "")).contains("8.18.0");
        String tokens = request("POST", "/_analyze", "{\"analyzer\":\"ik_max_word\",\"text\":\"Spring Boot 中文知识库\"}");
        JsonNode tokenNodes = JsonMapper.builder().build().readTree(tokens).path("tokens");
        assertThat(tokenNodes.isArray()).isTrue();
        Set<String> actualTokens = new HashSet<>();
        for (JsonNode tokenNode : tokenNodes) {
            JsonNode token = tokenNode.path("token");
            assertThat(token.isString()).isTrue();
            actualTokens.add(token.asString());
        }
        assertThat(actualTokens).contains("spring", "boot", "知识库");
        String index = "m007-" + java.util.UUID.randomUUID();
        Throwable failure = null;
        // 创建请求可能已在服务端成功，即使客户端失败也必须尝试清理。
        try {
            request(
                    "PUT",
                    "/" + index,
                    "{\"settings\":{\"number_of_replicas\":0,\"similarity\":{\"probe\":{\"type\":\"BM25\"}}},\"mappings\":{\"properties\":{\"content\":{\"type\":\"text\",\"analyzer\":\"ik_max_word\",\"similarity\":\"probe\"}}}}");
            request("PUT", "/" + index + "/_doc/1?refresh=true", "{\"content\":\"Spring Boot 中文知识库\"}");
            assertThat(request("POST", "/" + index + "/_search", "{\"query\":{\"match\":{\"content\":\"Spring\"}}}"))
                    .contains("\"_id\":\"1\"");
        } catch (Exception | Error primaryFailure) {
            failure = primaryFailure;
            throw primaryFailure;
        } finally {
            try {
                request("DELETE", "/" + index, "");
            } catch (Exception | Error cleanupFailure) {
                // 保留主测试失败，清理失败作为附加证据；单独清理失败则直接抛出。
                if (failure != null) {
                    failure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }
}
