package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.lnjecit.knowledge.assistant.ingestion.indexing.EsChunkDocument;
import com.lnjecit.knowledge.assistant.ingestion.indexing.EsIndexRepository;
import com.lnjecit.knowledge.assistant.ingestion.verification.EsIntegrityService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;

class EsIntegrityIT extends ElasticsearchIT {
    @Test
    void completeTraversalSearchAndFaults() throws Exception {
        String index = "m110b-" + UUID.randomUUID();
        try (RestClientTransport transport = new RestClientTransport(
                RestClient.builder(HttpHost.create("http://" + elasticsearch.getHttpHostAddress()))
                        .build(),
                new JacksonJsonpMapper())) {
            ElasticsearchClient client = new ElasticsearchClient(transport);
            EsIndexRepository repository = new EsIndexRepository(client);
            EsIntegrityService service = new EsIntegrityService(client, repository);
            try {
                assertThat(client.info().version().number()).isEqualTo("8.18.0");
                assertThat(client.nodes().info().nodes().values().stream()
                                .flatMap(n -> n.plugins().stream())
                                .filter(p -> p.name().contains("ik"))
                                .map(p -> p.version())
                                .toList())
                        .contains("8.18.0");
                repository.ensureIndex(index);
                List<EsChunkDocument> expected = new ArrayList<>();
                for (int i = 0; i < 205; i++)
                    expected.add(new EsChunkDocument(
                            Long.toString(Long.MAX_VALUE - i), "1", "2", "3", i, "中文知识库 Spring Boot 搜索验证"));
                repository.create(index, expected);
                // 三种不同归属的真实文档必须全部被过滤，不依赖缺省前十条。
                repository.create(
                        index,
                        List.of(
                                new EsChunkDocument("11", "9", "2", "3", 0, "中文知识库"),
                                new EsChunkDocument("12", "1", "9", "3", 0, "中文知识库"),
                                new EsChunkDocument("13", "1", "2", "9", 0, "中文知识库")));
                assertThat(service.verifyIndex(index, 1L, 2L, 3L, expected))
                        .isEqualTo(new EsIntegrityService.Result(205, 205, 3));
                assertThat(service.verifyIndex(index, 1L, 2L, 3L, expected).esChunkCount())
                        .isEqualTo(205);
                assertThat(client.count(r -> r.index(index)).count()).isEqualTo(208);
                String id = expected.getFirst().chunk_id();
                client.delete(r -> r.index(index).id(id));
                rejected(service, index, expected);
                assertThat(client.get(r -> r.index(index).id(id), EsChunkDocument.class)
                                .found())
                        .isFalse();
                repository.create(index, List.of(expected.getFirst()));
                EsChunkDocument extra = new EsChunkDocument("14", "1", "2", "3", 206, "中文知识库");
                repository.create(index, List.of(extra));
                rejected(service, index, expected);
                client.delete(r -> r.index(index).id("14"));
                for (EsChunkDocument corrupt : List.of(
                        new EsChunkDocument(id, "1", "2", "3", 0, "其他正文"),
                        new EsChunkDocument(id, "1", "2", "3", 999, "中文知识库 Spring Boot 搜索验证"),
                        new EsChunkDocument("15", "1", "2", "3", 0, "中文知识库 Spring Boot 搜索验证"))) {
                    client.index(r -> r.index(index).id(id).document(corrupt));
                    rejected(service, index, expected);
                    assertThat(client.get(r -> r.index(index).id(id), EsChunkDocument.class)
                                    .source())
                            .isEqualTo(corrupt);
                }
                client.index(r -> r.index(index).id(id).document(expected.getFirst()));
                // 标点正文可通过集合比较，但 IK 无有效词项，必须拒绝搜索验证。
                EsChunkDocument punctuation = new EsChunkDocument("16", "1", "20", "30", 0, "!!!");
                repository.create(index, List.of(punctuation));
                assertThatThrownBy(() -> service.verifyIndex(index, 1L, 20L, 30L, List.of(punctuation)))
                        .hasMessage("ES_VERIFICATION_FAILED");
                assertThat(service.verifyIndex(index, 1L, 2L, 3L, expected).searchableSampleCount())
                        .isEqualTo(3);
            } finally {
                if (client.indices().exists(r -> r.index(index)).value())
                    client.indices().delete(r -> r.index(index));
            }
        }
    }

    private static void rejected(EsIntegrityService service, String index, List<EsChunkDocument> expected) {
        assertThatThrownBy(() -> service.verifyIndex(index, 1L, 2L, 3L, expected))
                .hasMessage("ES_VERIFICATION_FAILED");
    }
}
