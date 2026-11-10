package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.lnjecit.knowledge.assistant.infrastructure.ai.RerankResult;
import com.lnjecit.knowledge.assistant.infrastructure.ai.Reranker;
import com.lnjecit.knowledge.assistant.ingestion.indexing.EsChunkDocument;
import com.lnjecit.knowledge.assistant.ingestion.indexing.EsIndexRepository;
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
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--assistant.model.enabled=false",
                        "--assistant.elasticsearch.enabled=false");
                RestClientTransport transport = new RestClientTransport(
                        RestClient.builder(HttpHost.create("http://" + elasticsearch.getHttpHostAddress()))
                                .build(),
                        new JacksonJsonpMapper())) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            jdbc.update("INSERT INTO knowledge_base(id,name) VALUES(601,'orchestration')");
            jdbc.update(
                    "INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key) VALUES(602,601,'a.txt','hash','key')");
            jdbc.update(
                    "INSERT INTO document_index_version(id,document_id,expected_chunk_count,chunking_config) VALUES(603,602,1,'{}')");
            float[] values = new float[1024];
            values[0] = 1;
            QueryVector fixed = new QueryVector(values);
            jdbc.update(
                    "INSERT INTO document_chunk(id,knowledge_base_id,document_id,index_version_id,chunk_order,content,source_start,source_end,embedding) VALUES(604,601,602,603,0,'body',0,4,?::vector)",
                    fixed.binding());
            jdbc.update("UPDATE document SET active_version_id=603 WHERE id=602");
            ElasticsearchClient client = new ElasticsearchClient(transport);
            String index = "m206-" + UUID.randomUUID();
            EsIndexRepository writer = new EsIndexRepository(client);
            writer.ensureIndex(index);
            try {
                writer.create(index, List.of(new EsChunkDocument("604", "601", "602", "603", 0, "body")));
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
                beans.addBean("reranker", (Reranker) (query, candidates, topN) -> candidates.stream()
                        .map(c -> new RerankResult(c.id(), .9))
                        .toList());
                RetrievalService service = new RetrievalService(
                        context.getBean(PublishedVersionSnapshotService.class),
                        beans.getBeanProvider(QueryEmbeddingService.class),
                        context.getBean(VectorRetriever.class),
                        beans.getBeanProvider(KeywordRetriever.class),
                        context.getBean(RrfFusionService.class),
                        new RerankService(beans.getBeanProvider(Reranker.class)));
                // 相同请求重复执行时仅比较证据与分数，不比较波动的耗时。
                List<com.lnjecit.knowledge.assistant.retrieval.model.RetrievalResult> baseline = service.search(
                        601L, "body", List.of(RetrievalStrategy.values()), RetrievalParameters.defaults());
                for (int repeat = 0; repeat < 3; repeat++) {
                    List<com.lnjecit.knowledge.assistant.retrieval.model.RetrievalResult> repeated = service.search(
                            601L, "body", List.of(RetrievalStrategy.values()), RetrievalParameters.defaults());
                    for (int i = 0; i < baseline.size(); i++) {
                        assertThat(repeated.get(i).chunks())
                                .isEqualTo(baseline.get(i).chunks());
                        assertThat(repeated.get(i).diagnostics().snapshotHash())
                                .isEqualTo(baseline.get(i).diagnostics().snapshotHash());
                    }
                }
                baseline.forEach(result -> System.out.println("M2_ACCEPTANCE " + result));
                assertThat(service.search(
                                601L, "body", List.of(RetrievalStrategy.values()), RetrievalParameters.defaults()))
                        .hasSize(4)
                        .allSatisfy(result -> {
                            assertThat(result.diagnostics().status()).isEqualTo(RetrievalStatus.SUCCESS);
                            assertThat(result.chunks().getFirst().chunkId()).isEqualTo(604L);
                            assertThat(result.chunks().getFirst().finalRank()).isEqualTo(1);
                        });
            } finally {
                client.indices().delete(r -> r.index(index));
            }
        }
    }
}
