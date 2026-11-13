package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.lnjecit.knowledge.assistant.infrastructure.ai.RerankResult;
import com.lnjecit.knowledge.assistant.infrastructure.ai.Reranker;
import com.lnjecit.knowledge.assistant.infrastructure.elasticsearch.EsChunkDocument;
import com.lnjecit.knowledge.assistant.infrastructure.elasticsearch.EsIndexRepository;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalService;
import com.lnjecit.knowledge.assistant.retrieval.embedding.QueryEmbeddingService;
import com.lnjecit.knowledge.assistant.retrieval.embedding.QueryVector;
import com.lnjecit.knowledge.assistant.retrieval.fusion.RrfFusionService;
import com.lnjecit.knowledge.assistant.retrieval.keyword.KeywordChunkRepository;
import com.lnjecit.knowledge.assistant.retrieval.keyword.KeywordRetriever;
import com.lnjecit.knowledge.assistant.retrieval.keyword.KeywordSearchRepository;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStatus;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStrategy;
import com.lnjecit.knowledge.assistant.retrieval.rerank.RerankService;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedVersionSnapshotService;
import com.lnjecit.knowledge.assistant.retrieval.vector.VectorRetriever;
import java.util.List;
import java.util.UUID;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class RetrievalServiceIT extends ElasticsearchIT {
    private static final List<RetrievalStrategy> ALL = List.of(RetrievalStrategy.values());

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
                            "pgvector/pgvector:0.8.0-pg17@sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87")
                    .asCompatibleSubstituteFor("postgres"))
            .withImagePullPolicy(image -> false);

    @Test
    void realFourStrategyDataFlow() throws Exception {
        SpringApplication app = new SpringApplication(KnowledgeAssistantApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (org.springframework.context.ConfigurableApplicationContext context = app.run(
                        "--spring.datasource.url=" + postgres.getJdbcUrl() + "&socketTimeout=2&connectTimeout=2",
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--spring.datasource.hikari.connection-timeout=1500",
                        "--assistant.model.enabled=false",
                        "--assistant.elasticsearch.enabled=false");
                RestClientTransport transport = new RestClientTransport(
                        RestClient.builder(HttpHost.create("http://" + elasticsearch.getHttpHostAddress()))
                                .setRequestConfigCallback(
                                        config -> config.setConnectTimeout(1000).setSocketTimeout(1500))
                                .build(),
                        new JacksonJsonpMapper())) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            jdbc.update("INSERT INTO knowledge_base(id,name) VALUES(601,'primary'),(611,'other')");
            jdbc.update(
                    "INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key) VALUES(602,601,'a','a','a'),(605,601,'b','b','b'),(608,601,'unpublished','c','c'),(612,611,'other','d','d')");
            jdbc.update(
                    "INSERT INTO document_index_version(id,document_id,chunking_config) VALUES(603,602,'{}'),(606,605,'{}'),(609,608,'{}'),(613,612,'{}'),(620,602,'{}')");
            float[] values = new float[1024];
            values[0] = 1;
            QueryVector fixed = new QueryVector(values);
            List<EsChunkDocument> corpus = new java.util.ArrayList<>();
            addChunk(jdbc, corpus, 604, 601, 602, 603, 0, "body", .9f, .1f);
            addChunk(jdbc, corpus, 607, 601, 602, 603, 1, "body extra extra extra extra", .8f, .2f);
            addChunk(jdbc, corpus, 610, 601, 605, 606, 0, "body body body", .7f, .3f);
            addChunk(
                    jdbc,
                    corpus,
                    615,
                    601,
                    605,
                    606,
                    1,
                    "body extra extra extra extra extra extra extra extra",
                    .6f,
                    .4f);
            // 非法候选向量完全一致，关键词更集中，必须在存储端 Top-K 前排除。
            addChunk(jdbc, corpus, 616, 601, 608, 609, 0, "body body body body body body", 1, 0);
            addChunk(jdbc, corpus, 617, 601, 602, 620, 0, "body body body body body body", 1, 0);
            addChunk(jdbc, corpus, 614, 611, 612, 613, 0, "body body body body body body", 1, 0);
            addChunk(jdbc, corpus, 618, 611, 612, 613, 1, "body", 1, 0);
            jdbc.update(
                    "UPDATE document SET active_version_id=CASE id WHEN 602 THEN 603 WHEN 605 THEN 606 WHEN 612 THEN 613 END WHERE id IN(602,605,612)");
            ElasticsearchClient client = new ElasticsearchClient(transport);
            String index = "m206-" + UUID.randomUUID();
            EsIndexRepository writer = new EsIndexRepository(client);
            writer.ensureIndex(index);
            try {
                writer.create(index, corpus);
                client.indices().refresh(r -> r.index(index));
                StaticListableBeanFactory beans = new StaticListableBeanFactory();
                // 固定向量只验证真实检索数据流，不证明在线语义质量。
                beans.addBean("embedding", new QueryEmbeddingService(null) {
                    @Override
                    public QueryVector embed(String query) {
                        return fixed;
                    }
                });
                beans.addBean(
                        "keyword",
                        new KeywordRetriever(
                                new KeywordSearchRepository(client, index),
                                context.getBean(KeywordChunkRepository.class)));
                beans.addBean("reranker", (Reranker) (query, candidates, topN) -> {
                    assertThat(candidates).hasSize(4);
                    assertThat(topN).isEqualTo(4);
                    return candidates.stream()
                            .map(c -> new RerankResult(c.id(), c.id().equals("615") ? .99 : .1))
                            .toList();
                });
                RetrievalService service = new RetrievalService(
                        context.getBean(PublishedVersionSnapshotService.class),
                        beans.getBeanProvider(QueryEmbeddingService.class),
                        context.getBean(VectorRetriever.class),
                        beans.getBeanProvider(KeywordRetriever.class),
                        context.getBean(RrfFusionService.class),
                        new RerankService(beans.getBeanProvider(Reranker.class)));
                RetrievalParameters parameters = new RetrievalParameters(4, 4, 60, 4, 2);
                List<com.lnjecit.knowledge.assistant.retrieval.model.RetrievalResult> baseline =
                        service.search(601L, "body", ALL, parameters);
                verifyDiagnostics(baseline);
                assertThat(baseline).allSatisfy(result -> {
                    assertThat(result.chunks()).hasSize(2);
                    assertThat(result.diagnostics().status()).isEqualTo(RetrievalStatus.SUCCESS);
                    assertThat(result.chunks()).allSatisfy(chunk -> {
                        assertThat(chunk.knowledgeBaseId()).isEqualTo(601L);
                        assertThat(chunk.chunkId()).isIn(604L, 607L, 610L, 615L);
                        assertThat(chunk.content())
                                .isEqualTo(corpus.stream()
                                        .filter(c -> c.chunk_id()
                                                .equals(chunk.chunkId().toString()))
                                        .findFirst()
                                        .orElseThrow()
                                        .content());
                        assertThat(chunk.documentId())
                                .isEqualTo(chunk.chunkId() == 604L || chunk.chunkId() == 607L ? 602L : 605L);
                        assertThat(chunk.indexVersionId()).isEqualTo(chunk.documentId() == 602L ? 603L : 606L);
                        assertThat(chunk.headingPath()).isEqualTo("section");
                        assertThat(chunk.sourceStart()).isZero();
                        assertThat(chunk.sourceEnd()).isEqualTo(chunk.content().length());
                    });
                    assertThat(result.chunks()).extracting(c -> c.finalRank()).containsExactly(1, 2);
                });
                assertThat(baseline.get(0).diagnostics().stages().getLast().candidates())
                        .extracting(c -> c.chunkId())
                        .containsExactly(604L, 607L, 610L, 615L);
                assertThat(baseline.get(1).diagnostics().stages().getFirst().candidates())
                        .extracting(c -> c.score())
                        .isSortedAccordingTo(java.util.Comparator.reverseOrder());
                assertThat(baseline.get(0).diagnostics().stages().getLast().candidates())
                        .extracting(c -> c.score())
                        .isSorted();
                assertThat(baseline.get(3).chunks().getFirst().chunkId()).isEqualTo(615L);
                assertThat(baseline.get(2).chunks().getFirst().chunkId()).isNotEqualTo(615L);
                for (int repeat = 0; repeat < 3; repeat++) {
                    List<com.lnjecit.knowledge.assistant.retrieval.model.RetrievalResult> repeated =
                            service.search(601L, "body", ALL, parameters);
                    for (int i = 0; i < 4; i++) {
                        assertThat(repeated.get(i).chunks())
                                .isEqualTo(baseline.get(i).chunks());
                        assertThat(repeated.get(i).diagnostics().snapshotHash())
                                .isEqualTo(baseline.get(i).diagnostics().snapshotHash());
                    }
                }
                baseline.forEach(result -> System.out.println("M2_MULTI " + result.diagnostics()));
                assertThat(service.search(
                                611L, "body", List.of(RetrievalStrategy.VECTOR, RetrievalStrategy.BM25), parameters))
                        .allSatisfy(result -> assertThat(result.chunks())
                                .extracting(c -> c.chunkId())
                                .containsOnly(614L, 618L)
                                .hasSize(2));
                // 仅暂停本类 Testcontainers 容器，保留端口和数据；finally 必须恢复。
                org.testcontainers.DockerClientFactory.instance()
                        .client()
                        .pauseContainerCmd(postgres.getContainerId())
                        .exec();
                try {
                    List<com.lnjecit.knowledge.assistant.retrieval.model.RetrievalResult> failed =
                            service.search(601L, "body", ALL, parameters);
                    assertThat(failed).allSatisfy(result -> {
                        RetrievalServiceTests.failed(
                                result,
                                com.lnjecit.knowledge.assistant.retrieval.model.RetrievalDiagnostics.Failure
                                        .SNAPSHOT_READ_FAILED);
                        assertThat(result.diagnostics().stages()).isEmpty();
                    });
                } finally {
                    org.testcontainers.DockerClientFactory.instance()
                            .client()
                            .unpauseContainerCmd(postgres.getContainerId())
                            .exec();
                }
                assertThat(service.search(601L, "body", ALL, parameters))
                        .allSatisfy(r -> assertThat(r.diagnostics().status()).isEqualTo(RetrievalStatus.SUCCESS));
                org.testcontainers.DockerClientFactory.instance()
                        .client()
                        .pauseContainerCmd(elasticsearch.getContainerId())
                        .exec();
                try {
                    List<com.lnjecit.knowledge.assistant.retrieval.model.RetrievalResult> failed =
                            service.search(601L, "body", ALL, parameters);
                    assertThat(failed.getFirst().diagnostics().status()).isEqualTo(RetrievalStatus.SUCCESS);
                    for (int i = 1; i < 4; i++) {
                        RetrievalServiceTests.failed(
                                failed.get(i),
                                com.lnjecit.knowledge.assistant.retrieval.model.RetrievalDiagnostics.Failure
                                        .BM25_FAILED);
                        assertThat(failed.get(i)
                                        .diagnostics()
                                        .stages()
                                        .getLast()
                                        .candidates())
                                .isEmpty();
                    }
                } finally {
                    org.testcontainers.DockerClientFactory.instance()
                            .client()
                            .unpauseContainerCmd(elasticsearch.getContainerId())
                            .exec();
                }
                assertThat(service.search(601L, "body", ALL, parameters))
                        .allSatisfy(r -> assertThat(r.diagnostics().status()).isEqualTo(RetrievalStatus.SUCCESS));
            } finally {
                client.indices().delete(r -> r.index(index));
            }
        }
    }

    private static void addChunk(
            JdbcTemplate jdbc,
            List<EsChunkDocument> corpus,
            long id,
            long kb,
            long doc,
            long version,
            int order,
            String content,
            float x,
            float y) {
        float[] values = new float[1024];
        values[0] = x;
        values[1] = y;
        jdbc.update(
                "INSERT INTO document_chunk(id,knowledge_base_id,document_id,index_version_id,chunk_order,content,source_start,source_end,heading_path,embedding) VALUES(?,?,?,?,?,?,0,?,'section',?::vector)",
                id,
                kb,
                doc,
                version,
                order,
                content,
                content.length(),
                java.util.Arrays.toString(values));
        corpus.add(new EsChunkDocument(
                Long.toString(id), Long.toString(kb), Long.toString(doc), Long.toString(version), order, content));
    }

    private static void verifyDiagnostics(
            List<com.lnjecit.knowledge.assistant.retrieval.model.RetrievalResult> results) {
        assertThat(results.stream().map(r -> r.diagnostics().snapshotHash()).distinct())
                .hasSize(1);
        for (int i = 0; i < results.size(); i++) {
            com.lnjecit.knowledge.assistant.retrieval.model.RetrievalResult result = results.get(i);
            assertThat(result.diagnostics().requestedStrategy()).isEqualTo(ALL.get(i));
            assertThat(result.diagnostics().actualStrategy()).isEqualTo(ALL.get(i));
            assertThat(result.diagnostics().degraded()).isFalse();
            List<String> expected =
                    switch (ALL.get(i)) {
                        case VECTOR -> List.of("EMBEDDING", "VECTOR");
                        case BM25 -> List.of("BM25");
                        case HYBRID_RRF -> List.of("EMBEDDING", "VECTOR", "BM25", "FUSION");
                        case HYBRID_RERANK -> List.of("EMBEDDING", "VECTOR", "BM25", "FUSION", "RERANK");
                    };
            assertThat(result.diagnostics().stages())
                    .extracting(s -> s.phase().name())
                    .containsExactlyElementsOf(expected);
            for (com.lnjecit.knowledge.assistant.retrieval.model.RetrievalDiagnostics.Stage stage :
                    result.diagnostics().stages()) {
                assertThat(stage.elapsedMillis()).isNotNegative();
                assertThat(stage.candidateCount()).isEqualTo(stage.candidates().size());
                assertThat(stage.candidateCount())
                        .isEqualTo(stage.phase().name().equals("EMBEDDING") ? 0 : 4);
                for (int rank = 0; rank < stage.candidates().size(); rank++)
                    assertThat(stage.candidates().get(rank).rank()).isEqualTo(rank + 1);
                for (com.lnjecit.knowledge.assistant.retrieval.model.RankedChunk chunk : result.chunks()) {
                    if (stage.phase().name().equals("EMBEDDING")) continue;
                    double score =
                            switch (stage.phase()) {
                                case VECTOR -> chunk.vectorDistance();
                                case BM25 -> chunk.bm25Score();
                                case FUSION -> chunk.rrfScore();
                                case RERANK -> chunk.rerankScore();
                                default -> throw new AssertionError();
                            };
                    assertThat(stage.candidates().stream()
                                    .filter(c -> c.chunkId().equals(chunk.chunkId()))
                                    .findFirst()
                                    .orElseThrow()
                                    .score())
                            .isEqualTo(score);
                    if (chunk.rrfScore() != null)
                        assertThat(chunk.rrfScore())
                                .isCloseTo(
                                        1.0 / (60 + chunk.vectorRank()) + 1.0 / (60 + chunk.bm25Rank()),
                                        org.assertj.core.data.Offset.offset(1e-12));
                }
            }
        }
    }
}
