package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.lnjecit.knowledge.assistant.ingestion.indexing.EsChunkDocument;
import com.lnjecit.knowledge.assistant.ingestion.indexing.EsIndexRepository;
import com.lnjecit.knowledge.assistant.retrieval.KeywordChunkRepository;
import com.lnjecit.knowledge.assistant.retrieval.KeywordRetriever;
import com.lnjecit.knowledge.assistant.retrieval.KeywordSearchRepository;
import com.lnjecit.knowledge.assistant.retrieval.PublishedDocument;
import com.lnjecit.knowledge.assistant.retrieval.PublishedVersionSnapshot;
import com.lnjecit.knowledge.assistant.retrieval.RankedChunk;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalContext;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalParameters;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalStatus;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalStrategy;
import java.util.List;
import java.util.UUID;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;

class KeywordSearchIT extends ElasticsearchIT {
    @org.testcontainers.junit.jupiter.Container
    static final org.testcontainers.postgresql.PostgreSQLContainer postgres =
            new org.testcontainers.postgresql.PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse(
                                    "pgvector/pgvector:0.8.0-pg17@sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87")
                            .asCompatibleSubstituteFor("postgres"))
                    .withImagePullPolicy(image -> false);

    @Test
    void realPgBatchEnrichment() throws Exception {
        org.springframework.boot.SpringApplication app =
                new org.springframework.boot.SpringApplication(KnowledgeAssistantApplication.class);
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        try (org.springframework.context.ConfigurableApplicationContext context = app.run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword())) {
            org.springframework.jdbc.core.JdbcTemplate jdbc =
                    context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
            jdbc.update("INSERT INTO knowledge_base(id,name) VALUES(301,'bm25')");
            jdbc.update(
                    "INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key) VALUES(302,301,'a.txt','hash','key')");
            jdbc.update(
                    "INSERT INTO document_index_version(id,document_id,expected_chunk_count,chunking_config) VALUES(303,302,1,'{}')");
            jdbc.update(
                    "INSERT INTO document_chunk(id,knowledge_base_id,document_id,index_version_id,chunk_order,content,source_start,source_end) VALUES(304,301,302,303,0,'body',0,4)");
            jdbc.update("UPDATE document SET active_version_id=303 WHERE id=302");
            KeywordChunkRepository repository = context.getBean(KeywordChunkRepository.class);
            PublishedVersionSnapshot snapshot =
                    PublishedVersionSnapshot.of(301L, List.of(new PublishedDocument(302L, 303L)));
            List<KeywordSearchRepository.Candidate> candidates = List.of(new KeywordSearchRepository.Candidate(
                    new EsChunkDocument("304", "301", "302", "303", 0, "body"), 1.5));
            assertThat(repository.complete(snapshot, candidates).getFirst().sourceEnd())
                    .isEqualTo(4);
            // 同一真实请求串联 ES Top-K 与 PG 权威补全，不用 Fake 候选替代通道验收。
            try (RestClientTransport transport = new RestClientTransport(
                    RestClient.builder(HttpHost.create("http://" + elasticsearch.getHttpHostAddress()))
                            .build(),
                    new JacksonJsonpMapper())) {
                ElasticsearchClient client = new ElasticsearchClient(transport);
                String index = "m203-pg-" + UUID.randomUUID();
                EsIndexRepository writer = new EsIndexRepository(client);
                writer.ensureIndex(index);
                try {
                    writer.create(index, List.of(candidates.getFirst().source()));
                    client.indices().refresh(r -> r.index(index));
                    KeywordRetriever retriever =
                            new KeywordRetriever(new KeywordSearchRepository(client, index), repository);
                    RetrievalContext request = new RetrievalContext(
                            "test", "body", RetrievalStrategy.BM25, RetrievalParameters.defaults(), snapshot);
                    KeywordRetriever.Recall recall = retriever.retrieve(request);
                    assertThat(recall.status()).isEqualTo(RetrievalStatus.SUCCESS);
                    assertThat(recall.chunks().getFirst().chunkId()).isEqualTo(304L);
                    assertThat(recall.chunks().getFirst().bm25Score())
                            .isFinite()
                            .isPositive();
                    // 后续首次发布不进入已读取的旧快照。
                    jdbc.update(
                            "INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key) VALUES(305,301,'b.txt','hash-b','key-b')");
                    jdbc.update(
                            "INSERT INTO document_index_version(id,document_id,expected_chunk_count,chunking_config) VALUES(306,305,1,'{}')");
                    jdbc.update("UPDATE document SET active_version_id=306 WHERE id=305");
                    writer.create(index, List.of(new EsChunkDocument("307", "301", "305", "306", 0, "body")));
                    client.indices().refresh(r -> r.index(index));
                    assertThat(retriever.retrieve(request).chunks())
                            .extracting(RankedChunk::chunkId)
                            .containsExactly(304L);
                    jdbc.update("UPDATE document_chunk SET content='changed' WHERE id=304");
                    assertThat(retriever.retrieve(request).status()).isEqualTo(RetrievalStatus.FAILED);
                } finally {
                    client.indices().delete(r -> r.index(index));
                }
            }
            jdbc.update("UPDATE document_chunk SET content='changed' WHERE id=304");
            assertThatThrownBy(() -> repository.complete(snapshot, candidates)).hasMessage("BM25_FAILED");
        }
    }

    @Test
    void realBm25FilteringSortingAndTerms() throws Exception {
        try (RestClientTransport transport = new RestClientTransport(
                RestClient.builder(HttpHost.create("http://" + elasticsearch.getHttpHostAddress()))
                        .build(),
                new JacksonJsonpMapper())) {
            ElasticsearchClient client = new ElasticsearchClient(transport);
            String index = "m203-" + UUID.randomUUID();
            EsIndexRepository writer = new EsIndexRepository(client);
            writer.ensureIndex(index);
            try {
                writer.create(
                        index,
                        List.of(
                                new EsChunkDocument("10", "1", "2", "3", 0, "Spring Boot 中文知识库"),
                                new EsChunkDocument("2", "1", "2", "3", 1, "Spring Boot 中文知识库"),
                                new EsChunkDocument("1", "1", "5", "6", 0, "Spring Boot 中文知识库"),
                                new EsChunkDocument("7", "8", "9", "10", 0, "Spring Boot 中文知识库")));
                client.indices().refresh(r -> r.index(index));
                KeywordSearchRepository repository = new KeywordSearchRepository(client, index);
                PublishedVersionSnapshot snapshot =
                        PublishedVersionSnapshot.of(1L, List.of(new PublishedDocument(2L, 3L)));
                for (String query : List.of("知识库", "Spring", "Spring 知识库")) {
                    List<KeywordSearchRepository.Candidate> result = repository.search(snapshot, query, 2);
                    assertThat(result).extracting(c -> c.source().chunk_id()).containsExactly("10", "2");
                    assertThat(result)
                            .allSatisfy(c -> assertThat(c.score()).isFinite().isPositive());
                    assertThat(result.get(0).score()).isEqualTo(result.get(1).score());
                    assertThat(repository.search(snapshot, query, 1)).hasSize(1);
                }
                assertThat(repository.search(snapshot, "zzzznomatch", 2)).isEmpty();
                client.indices().putSettings(r -> r.index(index).settings(s -> s.maxTermsCount(2)));
                PublishedVersionSnapshot two = PublishedVersionSnapshot.of(
                        1L, List.of(new PublishedDocument(2L, 3L), new PublishedDocument(5L, 6L)));
                assertThat(repository.search(two, "Spring", 10)).hasSize(3);
                PublishedVersionSnapshot duplicateVersions = PublishedVersionSnapshot.of(
                        1L,
                        List.of(
                                new PublishedDocument(2L, 3L),
                                new PublishedDocument(99L, 3L),
                                new PublishedDocument(5L, 6L)));
                assertThat(repository.search(duplicateVersions, "Spring", 10)).hasSize(3);
                // 增加长度不同的发布候选，检查真实不同分数仍按相关性降序。
                writer.create(
                        index,
                        List.of(new EsChunkDocument(
                                "20", "1", "2", "3", 2, "Spring Boot 中文知识库 extra extra extra extra extra")));
                client.indices().refresh(r -> r.index(index));
                List<KeywordSearchRepository.Candidate> scored = repository.search(snapshot, "Spring", 10);
                assertThat(scored).hasSize(3);
                assertThat(scored.stream()
                                .map(KeywordSearchRepository.Candidate::score)
                                .toList())
                        .isSortedAccordingTo(java.util.Comparator.reverseOrder());
                assertThat(scored.getFirst().score())
                        .isGreaterThan(scored.getLast().score());
                PublishedVersionSnapshot three = PublishedVersionSnapshot.of(
                        1L,
                        List.of(
                                new PublishedDocument(2L, 3L),
                                new PublishedDocument(5L, 6L),
                                new PublishedDocument(11L, 12L)));
                assertThatThrownBy(() -> repository.search(three, "Spring", 10)).hasMessage("BM25_FAILED");
                assertThat(new KeywordSearchRepository(client, "missing-index")
                                .search(PublishedVersionSnapshot.of(1L, List.of()), "Spring", 2))
                        .isEmpty();
                assertThatThrownBy(() ->
                                new KeywordSearchRepository(client, "missing-index").search(snapshot, "Spring", 2))
                        .hasMessage("BM25_FAILED");
                assertThatThrownBy(() -> repository.search(
                                PublishedVersionSnapshot.of(1L, List.of(new PublishedDocument(99L, 3L))), "Spring", 2))
                        .hasMessage("BM25_FAILED");
            } finally {
                client.indices().delete(r -> r.index(index));
            }
        }
    }
}
