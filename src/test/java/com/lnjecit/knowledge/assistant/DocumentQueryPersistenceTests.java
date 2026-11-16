package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.lnjecit.knowledge.assistant.document.entity.DocumentChunk;
import com.lnjecit.knowledge.assistant.document.mapper.DocumentQueryMapper;
import com.lnjecit.knowledge.assistant.document.repository.DocumentQueryRepository;
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
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        BusinessHttpTestSecurity.request(
                                        context,
                                        URI.create("http://localhost:" + port + "/api/v1/knowledge-bases/" + path))
                                .header("X-Request-Id", "web02-http")
                                .GET()
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

    @Test
    void realPaginationTasksAndHttp() throws Exception {
        try (ConfigurableApplicationContext c = start()) {
            JdbcTemplate j = c.getBean(JdbcTemplate.class);
            j.update("INSERT INTO knowledge_base(id,name) VALUES(1,'a'),(2,'empty')");
            assertThat(get(c, "2/documents", 200, 0).get("items").size()).isZero();
            get(c, "9/documents", 404, 40401);
            j.update(
                    "INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key,created_at) SELECT n,1,'notes.md',n::text,'secret','2026-01-01'::timestamptz FROM generate_series(10,13) n");
            j.update(
                    "INSERT INTO document_index_version(id,document_id,chunking_config,status,expected_chunk_count) VALUES(20,13,'{}','VERIFIED',1)");
            j.update("UPDATE document SET active_version_id=20 WHERE id=13");
            j.update(
                    "INSERT INTO document_import_task(id,document_id,index_version_id,status,created_at) VALUES(30,13,20,'FAILED','2025-01-01'),(31,13,20,'SUCCEEDED','2026-01-01'),(32,13,20,'RUNNING','2026-01-01'),(33,12,20,'PENDING','2026-01-01'),(34,11,20,'FAILED','2026-01-01'),(35,10,20,'SUCCEEDED','2026-01-01')");
            JsonNode data = get(c, "1/documents?pageSize=2", 200, 0);
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
            get(c, "0/documents", 400, 40000);
            assertThat(j.queryForObject("SELECT count(*) FROM document", Integer.class))
                    .isEqualTo(4);
        }
    }

    @Test
    void realEvidenceOwnershipAndCorruption() throws Exception {
        try (ConfigurableApplicationContext c = start()) {
            JdbcTemplate j = c.getBean(JdbcTemplate.class);
            j.update("INSERT INTO knowledge_base(id,name) VALUES(1,'a'),(2,'b')");
            j.update(
                    "INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key,active_version_id) VALUES(10,1,'notes.md','a','secret',20),(11,2,'other','b','secret',21),(12,1,'unpublished','c','secret',NULL)");
            j.update(
                    "INSERT INTO document_index_version(id,document_id,chunking_config,status,expected_chunk_count) VALUES(20,10,'{}','VERIFIED',1),(21,11,'{}','PUBLISHED',1),(22,10,'{}','BUILDING',0)");
            j.update(
                    "INSERT INTO document_chunk(id,knowledge_base_id,document_id,index_version_id,chunk_order,content,heading_path,source_start,source_end) VALUES(9007199254740993,1,10,20,0,'正文','标题',0,2),(31,2,11,21,0,'secret',NULL,0,2),(32,1,10,22,0,'hidden',NULL,0,2)");
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
            j.update("UPDATE document SET active_version_id=999 WHERE id=10");
            get(c, path, 500, 50000);
            get(c, "1/documents", 500, 50000);
            j.update("UPDATE document SET active_version_id=21 WHERE id=10");
            get(c, path, 500, 50000);
            j.update("UPDATE document SET active_version_id=20 WHERE id=10");
            j.update("UPDATE document_index_version SET status='PUBLISHED',published_at=now() WHERE id=20");
            get(c, path, 200, 0);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TransactionProbe {
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
                public long count(Long kbId) {
                    checkTransaction();
                    return super.count(kbId);
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
