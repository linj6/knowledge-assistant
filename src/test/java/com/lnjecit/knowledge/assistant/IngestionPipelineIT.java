package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.lnjecit.knowledge.assistant.infrastructure.storage.OriginalFileStore;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskScheduler;
import com.lnjecit.knowledge.assistant.ingestion.upload.DocumentImportController;
import com.lnjecit.knowledge.assistant.knowledge.KnowledgeBaseService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class IngestionPipelineIT extends ElasticsearchIT {
    static final StorageImportIT storage = new StorageImportIT();

    @Container
    static final PostgreSQLContainer postgres = storage.postgres;

    @Container
    static final GenericContainer<?> minio = storage.minio;

    @Test
    void markdownAndTextThroughProductionScheduler() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody().readAllBytes());
            assertThat(request.path("model").asString()).isEqualTo("text-embedding-v4");
            JsonNode input = request.path("input");
            int count = input.isArray() ? input.size() : 1;
            List<String> data = new ArrayList<>();
            for (int i = 0; i < count; i++)
                data.add("{\"object\":\"embedding\",\"index\":" + i + ",\"embedding\":[1" + ",0".repeat(1023) + "]}");
            byte[] body = ("{\"object\":\"list\",\"model\":\"text-embedding-v4\",\"data\":[" + String.join(",", data)
                            + "],\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        String endpoint = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
        io.minio.MinioClient client = io.minio.MinioClient.builder()
                .endpoint(endpoint)
                .credentials("storage-user", "storage-test-password")
                .build();
        client.makeBucket(
                io.minio.MakeBucketArgs.builder().bucket("pipeline-test").build());
        SpringApplication app = new SpringApplication(KnowledgeAssistantApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext c = app.run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--assistant.minio.endpoint=" + endpoint,
                "--assistant.minio.access-key=storage-user",
                "--assistant.minio.secret-key=storage-test-password",
                "--assistant.minio.bucket=pipeline-test",
                "--assistant.elasticsearch.enabled=true",
                "--assistant.elasticsearch.endpoint=http://" + elasticsearch.getHttpHostAddress(),
                "--assistant.model.enabled=true",
                "--assistant.model.api-key=local-stub",
                "--assistant.model.chat-model=unused",
                "--assistant.model.base-url=http://127.0.0.1:"
                        + stub.getAddress().getPort() + "/v1",
                "--assistant.worker.enabled=true",
                "--assistant.worker.scan-interval=24h")) {
            // 初次空扫描后取消后台触发，测试只串行调用真实调度入口。
            c.getBean(ScheduledAnnotationBeanPostProcessor.class).destroy();
            MockMvc mvc = MockMvcBuilders.standaloneSetup(c.getBean(DocumentImportController.class))
                    .build();
            JdbcTemplate jdbc = c.getBean(JdbcTemplate.class);
            ElasticsearchClient es = c.getBean(ElasticsearchClient.class);
            for (String extension : List.of("md", "txt")) {
                long kb = c.getBean(KnowledgeBaseService.class)
                        .create("pipeline-" + extension, null)
                        .getId();
                String source = "# 中文知识库 Spring Boot " + extension + "\n\n"
                        + "知识库 PostgreSQL Elasticsearch 向量检索 😀。\n\n".repeat(100);
                byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
                MockMultipartFile file = new MockMultipartFile("file", "fixture." + extension, "text/plain", bytes);
                JsonNode response = json.readTree(mvc.perform(multipart("/v1/knowledge-bases/{id}/documents:import", kb)
                                        .file(file))
                                .andExpect(status().isAccepted())
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                        .path("data");
                long document = response.path("documentId").asLong(),
                        task = response.path("importTaskId").asLong();
                long version = jdbc.queryForObject(
                        "SELECT index_version_id FROM document_import_task WHERE id=?", Long.class, task);
                assertThat(jdbc.queryForObject(
                                "SELECT status FROM document_import_task WHERE id=?", String.class, task))
                        .isEqualTo("PENDING");
                assertThat(jdbc.queryForObject(
                                "SELECT status FROM document_index_version WHERE id=?", String.class, version))
                        .isEqualTo("BUILDING");
                assertThat(jdbc.queryForObject(
                                "SELECT active_version_id FROM document WHERE id=?", Long.class, document))
                        .isNull();
                mvc.perform(get("/v1/import-tasks/{id}", task))
                        .andExpect(jsonPath("$.data.status").value("RECEIVED"));
                String key = jdbc.queryForObject("SELECT object_key FROM document WHERE id=?", String.class, document);
                assertThat(key).startsWith("originals/");
                assertThat(c.getBean(OriginalFileStore.class).read(key)).isEqualTo(bytes);
                c.getBean(TaskScheduler.class).scan();
                assertThat(jdbc.queryForObject(
                                "SELECT status FROM document_import_task WHERE id=?", String.class, task))
                        .as("task %s", jdbc.queryForMap("SELECT * FROM document_import_task WHERE id=?", task))
                        .isEqualTo("SUCCEEDED");
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM document_chunk WHERE index_version_id=? AND embedding IS NOT NULL AND vector_dims(embedding)=1024",
                                Integer.class,
                                version))
                        .isGreaterThan(1);
                List<Long> ids = new ArrayList<>();
                jdbc.query(
                        "SELECT id,content,source_start,source_end FROM document_chunk WHERE index_version_id=?",
                        rs -> {
                            ids.add(rs.getLong("id"));
                            int start = source.offsetByCodePoints(0, rs.getInt("source_start"));
                            int end = source.offsetByCodePoints(0, rs.getInt("source_end"));
                            assertThat(rs.getString("content")).isEqualTo(source.substring(start, end));
                        },
                        version);
                es.indices().refresh(r -> r.index("ka-chunks-v1"));
                assertThat(es
                                .search(
                                        r -> r.index("ka-chunks-v1")
                                                .size(100)
                                                .query(q -> q.term(t -> t.field("index_version_id")
                                                        .value(Long.toString(version)))),
                                        Object.class)
                                .hits()
                                .hits()
                                .stream()
                                .map(h -> Long.valueOf(h.id()))
                                .toList())
                        .containsExactlyInAnyOrderElementsOf(ids);
                assertThat(es.search(
                                        r -> r.index("ka-chunks-v1")
                                                .query(q -> q.match(
                                                        m -> m.field("content").query("知识库"))),
                                        Object.class)
                                .hits()
                                .hits())
                        .isNotEmpty();
                assertThat(jdbc.queryForObject(
                                "SELECT active_version_id FROM document WHERE id=?", Long.class, document))
                        .isEqualTo(version);
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM document_index_version WHERE id=? AND status='PUBLISHED' AND published_at IS NOT NULL",
                                Integer.class,
                                version))
                        .isEqualTo(1);
                mvc.perform(get("/v1/import-tasks/{id}", task))
                        .andExpect(jsonPath("$.data.status").value("READY"));
                String snapshot = jdbc.queryForObject(
                        "SELECT json_agg(t ORDER BY id)::text FROM document_chunk t WHERE index_version_id=?",
                        String.class,
                        version);
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> c.getBean(
                                        com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkService.class)
                                .initialize(
                                        document,
                                        version,
                                        new com.lnjecit.knowledge.assistant.ingestion.parser.DocumentParserFactory()
                                                .parse("fixture." + extension, bytes)))
                        .isInstanceOf(IllegalStateException.class);
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> c.getBean(
                                        com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingService.class)
                                .embed(document, version))
                        .isInstanceOf(IllegalStateException.class);
                org.assertj.core.api.Assertions.assertThatThrownBy(
                                () -> c.getBean(com.lnjecit.knowledge.assistant.ingestion.indexing.EsIndexService.class)
                                        .index(document, version))
                        .isInstanceOf(IllegalStateException.class);
                assertThat(jdbc.queryForObject(
                                "SELECT json_agg(t ORDER BY id)::text FROM document_chunk t WHERE index_version_id=?",
                                String.class,
                                version))
                        .isEqualTo(snapshot);
                int before = jdbc.queryForObject("SELECT count(*) FROM document", Integer.class);
                mvc.perform(multipart("/v1/knowledge-bases/{id}/documents:import", kb)
                                .file(file))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.importTaskId").value(task));
                assertThat(jdbc.queryForObject("SELECT count(*) FROM document", Integer.class))
                        .isEqualTo(before);
                for (String table : List.of("document", "document_index_version", "document_import_task")) {
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class))
                            .isEqualTo(before);
                }
                assertThat(storage.keys(client, "pipeline-test").size()).isEqualTo(before);
                long otherKb = c.getBean(KnowledgeBaseService.class)
                        .create("isolated-" + extension, null)
                        .getId();
                mvc.perform(multipart("/v1/knowledge-bases/{id}/documents:import", otherKb)
                                .file(file))
                        .andExpect(status().isAccepted());
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM document WHERE knowledge_base_id=?", Integer.class, otherKb))
                        .isEqualTo(1);
                c.getBean(TaskScheduler.class).scan();
            }
        } finally {
            stub.stop(0);
        }
    }
}
