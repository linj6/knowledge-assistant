package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkRepository;
import com.lnjecit.knowledge.assistant.ingestion.indexing.EsChunkDocument;
import com.lnjecit.knowledge.assistant.ingestion.indexing.EsIndexRepository;
import com.lnjecit.knowledge.assistant.ingestion.verification.EsIntegrityService;
import com.lnjecit.knowledge.assistant.ingestion.verification.IndexVerificationService;
import com.lnjecit.knowledge.assistant.ingestion.verification.PgIntegrityService;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class IndexVerificationIT extends ElasticsearchIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
                            "pgvector/pgvector:0.8.0-pg17@sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87")
                    .asCompatibleSubstituteFor("postgres"))
            .withImagePullPolicy(image -> false);

    @Test
    void realDualIndexValidationAndConflicts() throws Exception {
        SpringApplication app = new SpringApplication(KnowledgeAssistantApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = app.run(
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword());
                RestClientTransport transport = new RestClientTransport(
                        RestClient.builder(HttpHost.create("http://" + elasticsearch.getHttpHostAddress()))
                                .build(),
                        new JacksonJsonpMapper())) {
            ElasticsearchClient client = new ElasticsearchClient(transport);
            EsIndexRepository esRepository = new EsIndexRepository(client);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            PgIntegrityService pg = context.getBean(PgIntegrityService.class);
            assertThat(jdbc.queryForObject("SHOW server_version", String.class)).startsWith("17.6");
            assertThat(jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname='vector'", String.class))
                    .isEqualTo("0.8.0");
            assertThat(client.info().version().number()).isEqualTo("8.18.0");
            assertThat(client.nodes().info().nodes().values().stream()
                            .flatMap(n -> n.plugins().stream())
                            .filter(p -> p.name().contains("ik"))
                            .map(p -> p.version())
                            .toList())
                    .contains("8.18.0");
            long sequence = 100;
            for (String scenario : List.of(
                    "success",
                    "missing",
                    "extra",
                    "content",
                    "embedding",
                    "chunkVersion",
                    "chunkContent",
                    "versionState",
                    "updateConflict")) {
                long documentId = sequence++;
                long versionId = sequence++;
                long chunkId = sequence++;
                String indexName = "m110c-" + UUID.randomUUID();
                jdbc.update("INSERT INTO knowledge_base(id,name) VALUES(?,'test')", documentId);
                jdbc.update(
                        "INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key) VALUES(?,?,'test','hash','key')",
                        documentId,
                        documentId);
                jdbc.update(
                        "INSERT INTO document_index_version(id,document_id,expected_chunk_count,chunking_config) VALUES(?,?,1,'{}')",
                        versionId,
                        documentId);
                jdbc.update(
                        "INSERT INTO document_chunk(id,knowledge_base_id,document_id,index_version_id,chunk_order,content,source_start,source_end,embedding) VALUES(?,?,?,?,0,'中文知识库',0,5,?::vector)",
                        chunkId,
                        documentId,
                        documentId,
                        versionId,
                        Arrays.toString(new float[1024]));
                jdbc.update(
                        "INSERT INTO document_import_task(id,document_id,index_version_id) VALUES(?,?,?)",
                        chunkId,
                        documentId,
                        versionId);
                String taskBefore = jdbc.queryForObject(
                        "SELECT row_to_json(t)::text FROM document_import_task t WHERE id=?", String.class, chunkId);
                EsChunkDocument expected = EsChunkDocument.from(
                        context.getBean(ChunkRepository.class).find(versionId).getFirst());
                try {
                    esRepository.ensureIndex(indexName);
                    if (!scenario.equals("missing")) esRepository.create(indexName, List.of(expected));
                    if (scenario.equals("extra"))
                        esRepository.create(
                                indexName,
                                List.of(new EsChunkDocument(
                                        "999999",
                                        expected.knowledge_base_id(),
                                        expected.document_id(),
                                        expected.index_version_id(),
                                        1,
                                        "多余知识库")));
                    if (scenario.equals("content"))
                        client.index(r -> r.index(indexName)
                                .id(expected.chunk_id())
                                .document(new EsChunkDocument(
                                        expected.chunk_id(),
                                        expected.knowledge_base_id(),
                                        expected.document_id(),
                                        expected.index_version_id(),
                                        0,
                                        "错误正文")));
                    if (scenario.equals("embedding"))
                        jdbc.update("UPDATE document_chunk SET embedding=NULL WHERE id=?", chunkId);
                    if (scenario.equals("updateConflict")) {
                        // 仅在本测试独立数据库注入零行更新，确认不能把状态提交失败视为成功。
                        jdbc.execute(
                                "CREATE FUNCTION m110c_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$");
                        jdbc.execute(
                                "CREATE TRIGGER m110c_reject BEFORE UPDATE ON document_index_version FOR EACH ROW WHEN (NEW.status='VERIFIED') EXECUTE FUNCTION m110c_reject()");
                    }
                    EsIntegrityService es = new EsIntegrityService(client, esRepository) {
                        @Override
                        public Result verify(
                                Long knowledgeBaseId,
                                Long requestedDocumentId,
                                Long indexVersionId,
                                List<EsChunkDocument> expectedChunks) {
                            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                                    .isFalse();
                            Result result = super.verifyIndex(
                                    indexName, knowledgeBaseId, requestedDocumentId, indexVersionId, expectedChunks);
                            // 故障发生在真实 ES 验证完成后、最终 PG 事务开始前。
                            if (scenario.equals("chunkVersion"))
                                jdbc.update("UPDATE document_chunk SET version=version+1 WHERE id=?", chunkId);
                            if (scenario.equals("chunkContent"))
                                jdbc.update("UPDATE document_chunk SET content='变更正文' WHERE id=?", chunkId);
                            if (scenario.equals("versionState"))
                                jdbc.update("UPDATE document_index_version SET status='FAILED' WHERE id=?", versionId);
                            return result;
                        }
                    };
                    IndexVerificationService service = new IndexVerificationService(pg, es);
                    if (scenario.equals("success")) {
                        TransactionTemplate outerTransaction =
                                new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
                        outerTransaction.executeWithoutResult(
                                status -> assertThatThrownBy(() -> service.verify(documentId, versionId))
                                        .hasMessage("VERIFICATION_TRANSACTION_NOT_ALLOWED"));
                        PgIntegrityService.Snapshot immutable = pg.snapshot(documentId, versionId);
                        assertThatThrownBy(() -> immutable.chunks().clear())
                                .isInstanceOf(UnsupportedOperationException.class);
                        assertThat(service.verify(documentId, versionId).verificationStatus())
                                .isEqualTo("VERIFIED");
                        assertThat(service.verify(documentId, versionId).verificationStatus())
                                .isEqualTo("VERIFIED");
                        client.delete(r -> r.index(indexName).id(expected.chunk_id()));
                        assertThatThrownBy(() -> service.verify(documentId, versionId))
                                .hasMessage("ES_VERIFICATION_FAILED");
                        assertThat(client.get(r -> r.index(indexName).id(expected.chunk_id()), EsChunkDocument.class)
                                        .found())
                                .isFalse();
                    } else {
                        assertThatThrownBy(() -> service.verify(documentId, versionId))
                                .isInstanceOf(IllegalStateException.class);
                    }
                    assertThat(jdbc.queryForObject(
                                    "SELECT status FROM document_index_version WHERE id=?", String.class, versionId))
                            .isEqualTo(
                                    scenario.equals("success")
                                            ? "VERIFIED"
                                            : scenario.equals("versionState") ? "FAILED" : "BUILDING");
                    assertThat(jdbc.queryForObject(
                                    "SELECT active_version_id FROM document WHERE id=?", Long.class, documentId))
                            .isNull();
                    assertThat(jdbc.queryForObject(
                                    "SELECT published_at FROM document_index_version WHERE id=?",
                                    Object.class,
                                    versionId))
                            .isNull();
                    assertThat(jdbc.queryForObject(
                                    "SELECT row_to_json(t)::text FROM document_import_task t WHERE id=?",
                                    String.class,
                                    chunkId))
                            .isEqualTo(taskBefore);
                    if (scenario.equals("missing"))
                        assertThat(client.get(r -> r.index(indexName).id(expected.chunk_id()), EsChunkDocument.class)
                                        .found())
                                .isFalse();
                    if (scenario.equals("extra"))
                        assertThat(client.get(r -> r.index(indexName).id("999999"), EsChunkDocument.class)
                                        .found())
                                .isTrue();
                    if (scenario.equals("content"))
                        assertThat(client.get(r -> r.index(indexName).id(expected.chunk_id()), EsChunkDocument.class)
                                        .source()
                                        .content())
                                .isEqualTo("错误正文");
                } finally {
                    if (scenario.equals("updateConflict")) {
                        jdbc.execute("DROP TRIGGER IF EXISTS m110c_reject ON document_index_version");
                        jdbc.execute("DROP FUNCTION IF EXISTS m110c_reject()");
                    }
                    if (client.indices().exists(r -> r.index(indexName)).value())
                        client.indices().delete(r -> r.index(indexName));
                }
            }
        }
    }
}
