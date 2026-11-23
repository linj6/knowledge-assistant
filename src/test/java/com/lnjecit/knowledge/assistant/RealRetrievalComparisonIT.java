package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.lnjecit.knowledge.assistant.infrastructure.elasticsearch.EsChunkDocument;
import com.lnjecit.knowledge.assistant.infrastructure.elasticsearch.EsIndexRepository;
import com.lnjecit.knowledge.assistant.ingestion.publish.IndexPublishService;
import com.lnjecit.knowledge.assistant.ingestion.task.ClaimedTask;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalService;
import com.lnjecit.knowledge.assistant.retrieval.embedding.QueryEmbeddingService;
import com.lnjecit.knowledge.assistant.retrieval.fusion.RrfFusionService;
import com.lnjecit.knowledge.assistant.retrieval.keyword.KeywordChunkRepository;
import com.lnjecit.knowledge.assistant.retrieval.keyword.KeywordRetriever;
import com.lnjecit.knowledge.assistant.retrieval.keyword.KeywordSearchRepository;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStrategy;
import com.lnjecit.knowledge.assistant.retrieval.rerank.RerankService;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedVersionSnapshotService;
import com.lnjecit.knowledge.assistant.retrieval.vector.VectorRetriever;
import java.util.ArrayList;
import java.util.List;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class RealRetrievalComparisonIT extends ElasticsearchIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
                            "pgvector/pgvector:0.8.0-pg17@sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87")
                    .asCompatibleSubstituteFor("postgres"))
            .withImagePullPolicy(image -> false);

    @Test
    void realSnapshotComparison() throws Exception {
        new GoldPreparationTests().validateFrozenEvidence();
        EmbeddingSnapshotGenerator.Dataset data = EmbeddingSnapshotGenerator.corpus();
        var vectors = EmbeddingSnapshot.load(
                EmbeddingSnapshotGenerator.TARGET.resolve("manifest.json"),
                EmbeddingSnapshotGenerator.TARGET.resolve("vectors.jsonl"),
                data.manifest(),
                data.inputs());
        assertThat(data.manifest().configFingerprint()).isEqualTo(EmbeddingSnapshotGenerator.CONFIG_FINGERPRINT);
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var questions = json.readTree(
                java.nio.file.Files.readString(java.nio.file.Path.of("docs/evaluation/m4-01-gold-evidence.json")));
        java.nio.file.Path output = java.nio.file.Path.of("target/m4-02d/results.jsonl");
        java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.writeString(output, "");
        List<RetrievalExperimentCorpus.Document> documents = RetrievalExperimentCorpus.load();
        SpringApplication app = new SpringApplication(KnowledgeAssistantApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = app.run(
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--spring.config.location=classpath:/application.yml",
                        "--assistant.model.enabled=false",
                        "--assistant.rerank.enabled=false",
                        "--assistant.elasticsearch.enabled=false");
                RestClientTransport transport = new RestClientTransport(
                        RestClient.builder(HttpHost.create("http://" + elasticsearch.getHttpHostAddress()))
                                .build(),
                        new JacksonJsonpMapper())) {
            ElasticsearchClient client = new ElasticsearchClient(transport);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class))
                    .isGreaterThanOrEqualTo(5);
            EsIndexRepository writer = new EsIndexRepository(client);
            jdbc.update(
                    "INSERT INTO app_user(id,username,password_hash,status) VALUES (1,'fixture-owner','test-only','ACTIVE')");
            for (int group = 0; group < 2; group++) {
                long kb = 800000L + group * 100000L;
                String index = group == 0 ? "ka_chunk_eval_fixed" : "ka_chunk_eval_structure";
                jdbc.update("INSERT INTO knowledge_base(id,name,owner_user_id) VALUES(?,?,1)", kb, index);
                List<EsChunkDocument> corpus = new ArrayList<>();
                for (int d = 0; d < documents.size(); d++) {
                    RetrievalExperimentCorpus.Document source = documents.get(d);
                    long doc = kb + 100 + d, version = kb + 200 + d, task = kb + 300 + d;
                    List<MarkdownStructureExperiment.Piece> pieces = group == 0 ? source.fixed() : source.structure();
                    jdbc.update(
                            "INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key) VALUES(?,?,?,?,?)",
                            doc,
                            kb,
                            source.path(),
                            source.sha(),
                            "experiment/" + doc);
                    jdbc.update(
                            "INSERT INTO document_index_version(id,document_id,status,expected_chunk_count,chunking_config) VALUES(?,?,'VERIFIED',?,?)",
                            version,
                            doc,
                            pieces.size(),
                            "{\"experiment\":true}");
                    for (int order = 0; order < pieces.size(); order++) {
                        MarkdownStructureExperiment.Piece piece = pieces.get(order);
                        long id = kb + 1000 + d * 1000 + order;
                        String content = piece.sourceContent();
                        jdbc.update(
                                "INSERT INTO document_chunk(id,knowledge_base_id,document_id,index_version_id,chunk_order,content,source_start,source_end,heading_path,embedding) VALUES(?,?,?,?,?,?,?,?,?,?::vector)",
                                id,
                                kb,
                                doc,
                                version,
                                order,
                                content,
                                piece.span().start(),
                                piece.span().end(),
                                tools.jackson.databind.json.JsonMapper.builder()
                                        .build()
                                        .writeValueAsString(
                                                new com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkService
                                                        .HeadingPath(1, piece.headingPath())),
                                java.util.Arrays.toString(vectors.entrySet().stream()
                                        .filter(e -> source.sha()
                                                        .equals(e.getKey().documentSha256())
                                                && (groupStrategy(pieces, source))
                                                        .equals(e.getKey().strategy())
                                                && e.getKey().sourceStart()
                                                        == piece.span().start()
                                                && e.getKey().sourceEnd()
                                                        == piece.span().end())
                                        .findFirst()
                                        .orElseThrow()
                                        .getValue()
                                        .values()));
                        corpus.add(new EsChunkDocument(
                                Long.toString(id),
                                Long.toString(kb),
                                Long.toString(doc),
                                Long.toString(version),
                                order,
                                content));
                    }
                    jdbc.update(
                            "INSERT INTO document_import_task(id,document_id,index_version_id,status,lease_owner,claim_epoch,lease_until) VALUES(?,?,?,'RUNNING','experiment',1,statement_timestamp()+interval '1 hour')",
                            task,
                            doc,
                            version);
                    context.getBean(IndexPublishService.class)
                            .publish(new ClaimedTask(task, doc, version, "experiment", 1L));
                }
                writer.ensureIndex(index);
                writer.create(index, corpus);
                client.indices().refresh(r -> r.index(index));
                writer.validateIndex(index);
                assertThat(client.count(r -> r.index(index)).count()).isEqualTo(group == 0 ? 58 : 83);
                for (EsChunkDocument chunk : corpus) {
                    assertThat(client.get(r -> r.index(index).id(chunk.chunk_id()), EsChunkDocument.class)
                                    .source())
                            .isEqualTo(chunk);
                }
                assertThat(context.getBean(PublishedVersionSnapshotService.class)
                                .read(kb)
                                .documents())
                        .hasSize(8);
                StaticListableBeanFactory beans = new StaticListableBeanFactory();
                beans.addBean("embedding", EmbeddingSnapshot.queries(vectors, data.inputs()));
                beans.addBean(
                        "keyword",
                        new KeywordRetriever(
                                new KeywordSearchRepository(client, index),
                                context.getBean(KeywordChunkRepository.class)));
                RetrievalService service = new RetrievalService(
                        context.getBean(PublishedVersionSnapshotService.class),
                        beans.getBeanProvider(QueryEmbeddingService.class),
                        context.getBean(VectorRetriever.class),
                        beans.getBeanProvider(KeywordRetriever.class),
                        context.getBean(RrfFusionService.class),
                        new RerankService(beans.getBeanProvider(
                                com.lnjecit.knowledge.assistant.infrastructure.ai.Reranker.class)));
                for (var question : questions) {
                    long started = System.nanoTime();
                    var results = service.search(
                            kb,
                            question.path("question").asString(),
                            List.of(RetrievalStrategy.VECTOR, RetrievalStrategy.BM25, RetrievalStrategy.HYBRID_RRF),
                            RetrievalParameters.defaults());
                    long elapsed = (System.nanoTime() - started) / 1000000;
                    for (var result : results) {
                        // 保存完整候选和诊断，失败保留独立状态，语义支持留待人工复核。
                        var row = new java.util.LinkedHashMap<String, Object>();
                        row.put("questionId", question.path("questionId").asString());
                        row.put("chunking", group == 0 ? "FIXED_CODE_POINT" : "MARKDOWN_STRUCTURE_AWARE");
                        row.put("elapsedAllStrategiesMillis", elapsed);
                        row.put("result", result);
                        row.put("gold", question);
                        java.nio.file.Files.writeString(
                                output, json.writeValueAsString(row) + "\n", java.nio.file.StandardOpenOption.APPEND);
                    }
                }
            }
        }
    }

    private static String groupStrategy(
            List<MarkdownStructureExperiment.Piece> pieces, RetrievalExperimentCorpus.Document source) {
        return pieces == source.fixed() ? "FIXED_CODE_POINT" : "MARKDOWN_STRUCTURE_AWARE";
    }
}
