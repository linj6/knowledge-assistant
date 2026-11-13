package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.lnjecit.knowledge.assistant.infrastructure.elasticsearch.EsChunkDocument;
import com.lnjecit.knowledge.assistant.infrastructure.elasticsearch.EsChunkFields;
import com.lnjecit.knowledge.assistant.infrastructure.elasticsearch.EsIndexRepository;
import java.util.List;
import java.util.UUID;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;

class EsIndexIT extends ElasticsearchIT {
    @org.testcontainers.junit.jupiter.Container
    static final org.testcontainers.postgresql.PostgreSQLContainer postgres =
            new org.testcontainers.postgresql.PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse(
                                    "pgvector/pgvector:0.8.0-pg17@sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87")
                            .asCompatibleSubstituteFor("postgres"))
                    .withImagePullPolicy(image -> false);

    @Test
    void rejectsDisabledInvertedIndexes() throws Exception {
        try (RestClientTransport transport = new RestClientTransport(
                RestClient.builder(HttpHost.create("http://" + elasticsearch.getHttpHostAddress()))
                        .build(),
                new JacksonJsonpMapper())) {
            ElasticsearchClient client = new ElasticsearchClient(transport);
            EsIndexRepository repository = new EsIndexRepository(client);
            for (String disabled : List.of(EsChunkFields.KNOWLEDGE_BASE_ID, EsChunkFields.CONTENT)) {
                String index = "m107-" + UUID.randomUUID();
                try {
                    client.indices().create(r -> r.index(index).mappings(m -> m.properties(
                                    EsChunkFields.CHUNK_ID, p -> p.keyword(k -> k))
                            .properties(
                                    EsChunkFields.KNOWLEDGE_BASE_ID,
                                    p -> p.keyword(k -> k.index(!disabled.equals(EsChunkFields.KNOWLEDGE_BASE_ID))))
                            .properties(EsChunkFields.DOCUMENT_ID, p -> p.keyword(k -> k))
                            .properties(EsChunkFields.INDEX_VERSION_ID, p -> p.keyword(k -> k))
                            .properties(EsChunkFields.CHUNK_ORDER, p -> p.integer(k -> k))
                            .properties(
                                    EsChunkFields.CONTENT,
                                    p -> p.text(t -> t.index(!disabled.equals(EsChunkFields.CONTENT))
                                            .analyzer("ik_max_word")
                                            .searchAnalyzer("ik_smart")
                                            .similarity("BM25")))));
                    assertThatThrownBy(() -> repository.ensureIndex(index)).hasMessage("ES_MAPPING_INCOMPATIBLE");
                } finally {
                    if (client.indices().exists(r -> r.index(index)).value())
                        client.indices().delete(r -> r.index(index));
                }
            }
        }
    }

    @Test
    void postgresSnapshotRemainsUnpublishedAndUnchanged() throws Exception {
        org.springframework.boot.SpringApplication app =
                new org.springframework.boot.SpringApplication(KnowledgeAssistantApplication.class);
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        String index = "m107-" + UUID.randomUUID();
        try (org.springframework.context.ConfigurableApplicationContext context = app.run(
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword());
                RestClientTransport transport = new RestClientTransport(
                        RestClient.builder(HttpHost.create("http://" + elasticsearch.getHttpHostAddress()))
                                .build(),
                        new JacksonJsonpMapper())) {
            ElasticsearchClient client = new ElasticsearchClient(transport);
            org.springframework.jdbc.core.JdbcTemplate jdbc =
                    context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
            jdbc.update("INSERT INTO knowledge_base(id,name) VALUES(101,'test')");
            jdbc.update(
                    "INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key) VALUES(102,101,'a.txt','hash','key')");
            jdbc.update(
                    "INSERT INTO document_index_version(id,document_id,expected_chunk_count,chunking_config) VALUES(103,102,1,'{}')");
            jdbc.update(
                    "INSERT INTO document_chunk(id,knowledge_base_id,document_id,index_version_id,chunk_order,content,source_start,source_end,version) VALUES(9223372036854775806,101,102,103,0,'中文知识库',0,5,7)");
            java.util.Map<String, Object> before =
                    jdbc.queryForMap("SELECT * FROM document_chunk WHERE document_id=102");
            // 测试覆盖真实 PG 读取与 ES 写入；只将测试仓储目标重定向至随机索引。
            EsIndexRepository repository = new EsIndexRepository(client) {
                @Override
                public void ensureIndex(String ignored) {
                    super.ensureIndex(index);
                }

                @Override
                public void create(String ignored, List<EsChunkDocument> docs) {
                    super.create(index, docs);
                }
            };
            try {
                com.lnjecit.knowledge.assistant.ingestion.indexing.EsIndexService service =
                        new com.lnjecit.knowledge.assistant.ingestion.indexing.EsIndexService(
                                context.getBean(com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkRepository.class),
                                repository,
                                100);
                service.index(102L, 103L);
                service.index(102L, 103L);
                assertThat(jdbc.queryForMap("SELECT * FROM document_chunk WHERE document_id=102"))
                        .isEqualTo(before);
                assertThat(jdbc.queryForObject("SELECT active_version_id FROM document WHERE id=102", Long.class))
                        .isNull();
                assertThat(jdbc.queryForObject("SELECT status FROM document_index_version WHERE id=103", String.class))
                        .isEqualTo("BUILDING");
                assertThat(client.get(r -> r.index(index).id("9223372036854775806"), EsChunkDocument.class)
                                .found())
                        .isTrue();
                // ES 中存在不赋予发布事实；正式查询必须使用 PG 发布版本集合。
                assertThat(jdbc.queryForList(
                                "SELECT active_version_id FROM document WHERE active_version_id IS NOT NULL"))
                        .isEmpty();
            } finally {
                if (client.indices().exists(r -> r.index(index)).value())
                    client.indices().delete(r -> r.index(index));
            }
        }
    }

    @Test
    void officialClientBulkAndRecovery() throws Exception {
        String index = "m107-" + UUID.randomUUID(), incompatible = "m107-" + UUID.randomUUID();
        try (RestClientTransport transport = new RestClientTransport(
                RestClient.builder(HttpHost.create("http://" + elasticsearch.getHttpHostAddress()))
                        .build(),
                new JacksonJsonpMapper())) {
            ElasticsearchClient client = new ElasticsearchClient(transport);
            EsIndexRepository repository = new EsIndexRepository(client);
            try {
                assertThat(client.info().version().number()).isEqualTo("8.18.0");
                assertThat(client.nodes().info().nodes().values().stream()
                                .flatMap(n -> n.plugins().stream())
                                .filter(p -> p.name().contains("ik"))
                                .map(p -> p.version())
                                .toList())
                        .contains("8.18.0");
                repository.ensureIndex(index);
                repository.ensureIndex(index);
                EsChunkDocument a = new EsChunkDocument(
                        "9223372036854775806", "101", "102", "103", 0, "中文知识库 Spring Boot Spring AI Redis RocketMQ");
                EsChunkDocument b = new EsChunkDocument("9223372036854775805", "101", "102", "103", 1, "Redis 缓存");
                repository.create(index, List.of(a, b));
                repository.create(index, List.of(a, b));
                assertThat(client.get(r -> r.index(index).id(a.chunk_id()), EsChunkDocument.class)
                                .source())
                        .isEqualTo(a);
                EsChunkDocument changed = new EsChunkDocument(a.chunk_id(), "101", "102", "103", 0, "different");
                EsChunkDocument fresh =
                        new EsChunkDocument("9223372036854775804", "101", "102", "103", 2, "RocketMQ 消息");
                // 真实 ES 一项成功、一项 409 数据冲突；失败后成功项必须保留。
                assertThatThrownBy(() -> repository.create(index, List.of(fresh, changed)))
                        .hasMessage("ES_BULK_ITEM_REJECTED");
                assertThat(client.get(r -> r.index(index).id(fresh.chunk_id()), EsChunkDocument.class)
                                .source())
                        .isEqualTo(fresh);
                repository.create(index, List.of(fresh, a));
                client.indices().refresh(r -> r.index(index));
                for (String term : List.of("知识库", "Spring Boot", "Spring AI", "Redis", "RocketMQ")) {
                    assertThat(client.search(
                                            r -> r.index(index)
                                                    .query(q -> q.bool(
                                                            v -> v.must(m -> m.match(t -> t.field(EsChunkFields.CONTENT)
                                                                            .query(term)))
                                                                    .filter(f -> f.term(
                                                                            t -> t.field(EsChunkFields.INDEX_VERSION_ID)
                                                                                    .value("103"))))),
                                            EsChunkDocument.class)
                                    .hits()
                                    .hits())
                            .isNotEmpty();
                }
                assertThat(client.indices()
                                .getMapping(r -> r.index(index))
                                .result()
                                .get(index)
                                .mappings()
                                .properties()
                                .get(EsChunkFields.CONTENT)
                                .text()
                                .similarity())
                        .isEqualTo("BM25");
                assertThat(client.indices()
                                .getSettings(r -> r.index(index))
                                .result()
                                .get(index)
                                .settings()
                                .index()
                                .numberOfReplicas())
                        .isEqualTo("0");
                client.indices().create(r -> r.index(incompatible)
                        .mappings(m -> m.properties(EsChunkFields.CONTENT, v -> v.keyword(k -> k))));
                assertThatThrownBy(() -> repository.ensureIndex(incompatible)).hasMessage("ES_MAPPING_INCOMPATIBLE");
            } finally {
                // 仅清理本次随机命名的测试索引，绝不访问共享业务索引。
                for (String name : List.of(index, incompatible))
                    if (client.indices().exists(r -> r.index(name)).value())
                        client.indices().delete(r -> r.index(name));
            }
        }
    }
}
