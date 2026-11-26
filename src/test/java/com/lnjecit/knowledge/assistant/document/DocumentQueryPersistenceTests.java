package com.lnjecit.knowledge.assistant.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.lnjecit.knowledge.assistant.KnowledgeAssistantApplication;
import com.lnjecit.knowledge.assistant.document.entity.DocumentChunk;
import com.lnjecit.knowledge.assistant.document.mapper.DocumentQueryMapper;
import com.lnjecit.knowledge.assistant.document.repository.DocumentQueryRepository;
import com.lnjecit.knowledge.assistant.infrastructure.BusinessHttpTestSecurity;
import com.lnjecit.knowledge.assistant.infrastructure.storage.OriginalFileStore;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedVersionSnapshotService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class DocumentQueryPersistenceTests {
    @Container
    final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
                            "pgvector/pgvector:0.8.0-pg17@sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87")
                    .asCompatibleSubstituteFor("postgres"))
            .withImagePullPolicy(image -> false);

    private ConfigurableApplicationContext start() {
        return new SpringApplication(
                        KnowledgeAssistantApplication.class, TransactionProbe.class, BusinessHttpTestSecurity.class)
                .run(
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--server.port=0",
                        "--assistant.model.enabled=false",
                        "--assistant.rerank.enabled=false",
                        "--assistant.elasticsearch.enabled=false");
    }

    private JsonNode get(ConfigurableApplicationContext context, String path, int status, int code) throws Exception {
        return request(context, path, status, code, false);
    }

    private JsonNode request(ConfigurableApplicationContext context, String path, int status, int code, boolean delete)
            throws Exception {
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        BusinessHttpTestSecurity.request(
                                        context,
                                        URI.create("http://localhost:" + port + "/api/v1/knowledge-bases/" + path))
                                .header("X-Request-Id", "web02-http")
                                .method(delete ? "DELETE" : "GET", java.net.http.HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path).isEqualTo(status);
        JsonNode body = context.getBean(JsonMapper.class).readTree(response.body());
        assertThat(body.get("code").intValue()).isEqualTo(code);
        assertThat(body.get("requestId").asString()).isEqualTo("web02-http");
        assertThat(response.headers().firstValue("X-Request-Id")).contains("web02-http");
        if (status != 200) assertThat(body.get("data").isNull()).isTrue();
        return body.get("data");
    }

    private void seedOwnedKnowledgeBases(JdbcTemplate jdbc, String secondName) {
        jdbc.update(
                "INSERT INTO app_user(id,username,password_hash,status) VALUES(42,'business-test-user','unused','ACTIVE')");
        jdbc.update("INSERT INTO knowledge_base(id,name,owner_user_id) VALUES(1,'a',42),(2,?,42)", secondName);
    }

    @Test
    void realPaginationTasksAndHttp() throws Exception {
        try (ConfigurableApplicationContext c = start()) {
            JdbcTemplate j = c.getBean(JdbcTemplate.class);
            seedOwnedKnowledgeBases(j, "empty");
            assertThat(get(c, "2/documents", 200, 0).get("items").size()).isZero();
            get(c, "9/documents", 404, 40401);
            j.update(
                    "INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key,created_at) SELECT n,1,'notes.md',n::text,'secret','2026-01-01'::timestamptz FROM generate_series(10,13) n");
            j.update(
                    "INSERT INTO document_index_version(id,document_id,chunking_config,status,expected_chunk_count) VALUES(20,13,'{}','VERIFIED',1)");
            j.update("UPDATE document SET active_version_id=20 WHERE id=13");
            String snapshot =
                    "{\"version\":2,\"strategy\":\"MARKDOWN_STRUCTURE_AWARE\",\"chunkSize\":128,\"chunkOverlap\":16}";
            j.update("INSERT INTO document_index_version(id,document_id,chunking_config) VALUES(23,12,?)", snapshot);

            j.update(
                    "INSERT INTO document_import_task(id,document_id,index_version_id,status,created_at) VALUES(30,13,20,'FAILED','2025-01-01'),(31,13,20,'SUCCEEDED','2026-01-01'),(32,13,20,'RUNNING','2026-01-01'),(33,12,23,'PENDING','2026-01-01'),(34,11,20,'FAILED','2026-01-01'),(35,10,20,'SUCCEEDED','2026-01-01')");
            JsonNode data = get(c, "1/documents?pageSize=2", 200, 0);
            JsonNode draft = get(c, "1/documents/12", 200, 0);
            assertThat(draft.get("publicationStatus").asString()).isEqualTo("UNPUBLISHED");
            assertThat(draft.get("chunkCount").asString()).isEqualTo("0");
            assertThat(draft.get("chunkingConfig"))
                    .isEqualTo(c.getBean(JsonMapper.class).readTree(snapshot));
            assertThat(data.get("items").get(1)).isEqualTo(draft);

            assertThat(data.get("total").isString()).isTrue();
            assertThat(data.get("total").asString()).isEqualTo("4");
            assertThat(data.get("items").get(0).get("documentId").asString()).isEqualTo("13");
            assertThat(data.get("items").get(0).get("publicationStatus").asString())
                    .isEqualTo("PUBLISHED");
            assertThat(data.get("items").get(0).get("publishedAt").isNull()).isTrue();
            assertThat(data.get("items")
                            .get(0)
                            .get("importTask")
                            .get("importTaskId")
                            .asString())
                    .isEqualTo("32");
            assertThat(data.get("items").get(0).get("importTask").get("status").asString())
                    .isEqualTo("PROCESSING");
            assertThat(data.get("items").get(1).get("importTask").get("status").asString())
                    .isEqualTo("RECEIVED");
            data = get(c, "1/documents?page=2&pageSize=2", 200, 0);
            assertThat(data.get("items")
                            .get(0)
                            .get("importTask")
                            .get("failureCode")
                            .asString())
                    .isEqualTo("IMPORT_FAILED");
            assertThat(data.get("items").get(1).get("importTask").get("status").asString())
                    .isEqualTo("READY");
            assertThat(get(c, "1/documents?page=3&pageSize=2", 200, 0)
                            .get("items")
                            .size())
                    .isZero();
            for (String query : List.of("page=0", "pageSize=101", "pageSize=0", "page=x"))
                get(c, "1/documents?" + query, 400, 40000);
            j.update("UPDATE document SET filename='Literal%_!.MD' WHERE id=12");
            String filters = "1/documents?keyword=%20literal%25_%21%20&status=IMPORTING";
            data = get(c, filters, 200, 0);
            assertThat(data.get("total").asString()).isEqualTo("1");
            assertThat(data.get("items").get(0).get("documentId").asString()).isEqualTo("12");
            assertThat(get(c, filters + "&page=2", 200, 0).get("total").asString())
                    .isEqualTo("1");
            assertThat(get(c, "1/documents?keyword=%20%20", 200, 0).get("total").asString())
                    .isEqualTo("4");
            for (String status : List.of("PUBLISHED", "FAILED", "UNPUBLISHED"))
                assertThat(get(c, "1/documents?status=" + status, 200, 0)
                                .get("total")
                                .asString())
                        .isEqualTo("1");
            get(c, "1/documents?status=UNKNOWN", 400, 40000);
            request(c, "1/documents/12", 409, 40906, true);
            j.update("UPDATE document_import_task SET next_retry_at=now() WHERE id=34");
            request(c, "1/documents/11", 409, 40906, true);
            get(c, "0/documents", 400, 40000);
            assertThat(j.queryForObject("SELECT count(*) FROM document", Integer.class))
                    .isEqualTo(4);
        }
    }

    @Test
    void realEvidenceOwnershipAndCorruption() throws Exception {
        try (ConfigurableApplicationContext c = start()) {
            JdbcTemplate j = c.getBean(JdbcTemplate.class);
            seedOwnedKnowledgeBases(j, "b");
            j.update(
                    "INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key,active_version_id) VALUES(10,1,'notes.md','a','secret',20),(11,2,'other','b','secret',21),(12,1,'unpublished','c','secret',NULL)");
            j.update(
                    "INSERT INTO document_index_version(id,document_id,chunking_config,status,expected_chunk_count) VALUES(20,10,'{}','VERIFIED',1),(21,11,'{}','PUBLISHED',1),(22,10,'{}','BUILDING',0)");
            j.update(
                    "INSERT INTO document_chunk(id,knowledge_base_id,document_id,index_version_id,chunk_order,content,heading_path,source_start,source_end) VALUES(9007199254740993,1,10,20,0,'正文','标题',0,2),(31,2,11,21,0,'secret',NULL,0,2),(32,1,10,22,0,'hidden',NULL,0,2)");
            OriginalFileStore store = c.getBean(OriginalFileStore.class);
            String raw = "# 标题\r\n<script>😀</script>\n";
            org.mockito.Mockito.when(store.read("secret")).thenAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                        .isFalse();
                return raw.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            });
            j.update("UPDATE document SET filename='draft.txt' WHERE id=12");
            for (long doc : List.of(10L, 12L)) {
                JsonNode preview = get(c, "1/documents/" + doc + "/content", 200, 0);
                assertThat(preview.get("content").asString()).isEqualTo(raw);
                assertThat(preview.has("objectKey")).isFalse();
            }
            org.mockito.Mockito.when(store.read("secret"))
                    .thenThrow(new OriginalFileStore.MissingObjectException())
                    .thenThrow(new IllegalStateException());
            get(c, "1/documents/12/content", 404, 40400);
            get(c, "1/documents/12/content", 500, 50000);
            JsonNode detail = get(c, "1/documents/10", 200, 0);
            assertThat(detail.get("chunkCount").asString()).isEqualTo("1");
            assertThat(detail.get("chunkingConfig").isObject()).isTrue();
            JsonNode chunks = get(c, "1/documents/10/chunks?pageSize=1", 200, 0);
            assertThat(chunks.get("total").asString()).isEqualTo("1");
            assertThat(chunks.get("items").get(0).get("sourceEnd").asInt()).isEqualTo(2);
            assertThat(get(c, "1/documents/10/chunks?page=2", 200, 0)
                            .get("items")
                            .size())
                    .isZero();
            assertThat(get(c, "1/documents/12/chunks", 200, 0).get("total").asString())
                    .isEqualTo("0");
            for (String suffix : List.of("", "/content", "/chunks")) get(c, "2/documents/10" + suffix, 404, 40400);
            String path = "1/documents/10/versions/20/chunks/9007199254740993";
            JsonNode data = get(c, path, 200, 0);
            assertThat(data.get("chunkId").isString()).isTrue();
            assertThat(data.get("chunkId").asString()).isEqualTo("9007199254740993");
            assertThat(data.get("content").asString()).isEqualTo("正文");
            assertThat(data.get("filename").asString()).isEqualTo("notes.md");
            assertThat(data.get("headingPath").asString()).isEqualTo("标题");
            assertThat(data.has("embedding")).isFalse();
            assertThat(data.has("objectKey")).isFalse();
            for (String p : List.of(
                    "1/documents/10/versions/20/chunks/99",
                    "1/documents/99/versions/20/chunks/31",
                    "1/documents/10/versions/99/chunks/31",
                    "1/documents/10/versions/20/chunks/31",
                    "1/documents/10/versions/22/chunks/32",
                    "1/documents/12/versions/20/chunks/32",
                    "2/documents/10/versions/20/chunks/9007199254740993")) get(c, p, 404, 40400);
            get(c, "9/documents/10/versions/20/chunks/31", 404, 40401);
            j.update("UPDATE knowledge_base SET deleted_at=now() WHERE id=2");
            for (String suffix : List.of("", "/content", "/chunks")) get(c, "2/documents/11" + suffix, 404, 40401);
            j.update("UPDATE knowledge_base SET deleted_at=NULL WHERE id=2");

            j.update("UPDATE document SET active_version_id=999 WHERE id=10");
            get(c, path, 500, 50000);
            get(c, "1/documents", 500, 50000);
            j.update("UPDATE document SET active_version_id=21 WHERE id=10");
            get(c, path, 500, 50000);
            j.update("UPDATE document SET active_version_id=20 WHERE id=10");
            j.update("UPDATE document_index_version SET status='PUBLISHED',published_at=now() WHERE id=20");
            get(c, path, 200, 0);
            PublishedVersionSnapshotService snapshots = c.getBean(PublishedVersionSnapshotService.class);
            assertThat(snapshots.read(1L).documents()).hasSize(1);
            j.update(
                    "INSERT INTO app_user(id,username,password_hash,status) VALUES(43,'other-user','unused','ACTIVE')");
            j.update("INSERT INTO knowledge_base(id,name,owner_user_id) VALUES(3,'private',43)");
            request(c, "3/documents/10", 404, 40401, true);
            request(c, "2/documents/10", 404, 40400, true);
            request(c, "1/documents/10", 200, 0, true);
            for (String suffix : List.of("", "/content", "/chunks")) {
                get(c, "3/documents/10" + suffix, 404, 40401);
                get(c, "1/documents/10" + suffix, 404, 40400);
            }
            request(c, "1/documents/10", 404, 40400, true);
            get(c, path, 404, 40400);
            assertThat(get(c, "1/documents?status=PUBLISHED", 200, 0)
                            .get("total")
                            .asString())
                    .isEqualTo("0");
            assertThat(snapshots.read(1L).documents()).isEmpty();
            assertThat(j.queryForObject("SELECT active_version_id FROM document WHERE id=10", Long.class))
                    .isEqualTo(20L);
            assertThat(j.queryForObject("SELECT status FROM document_index_version WHERE id=20", String.class))
                    .isEqualTo("PUBLISHED");
            assertThat(j.queryForObject("SELECT count(*) FROM document_chunk WHERE document_id=10", Integer.class))
                    .isEqualTo(2);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TransactionProbe {
        @Bean
        @Primary
        OriginalFileStore previewStore() {
            return org.mockito.Mockito.mock(OriginalFileStore.class);
        }

        @Bean
        @Primary
        DocumentQueryRepository queryProbe(DocumentQueryMapper mapper, JdbcTemplate jdbc) {
            return new DocumentQueryRepository(mapper) {
                private void checkTransaction() {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isTrue();
                    assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class))
                            .isEqualTo("repeatable read");
                    assertThat(jdbc.queryForObject("SHOW transaction_read_only", String.class))
                            .isEqualTo("on");
                }

                @Override
                public long count(Long kbId, String pattern, String status) {
                    checkTransaction();
                    return super.count(kbId, pattern, status);
                }

                @Override
                public DocumentChunk chunk(Long id) {
                    checkTransaction();
                    return super.chunk(id);
                }
            };
        }
    }
}
