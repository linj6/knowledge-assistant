package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lnjecit.knowledge.assistant.infrastructure.storage.OriginalFileStore;
import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingConfig;
import com.lnjecit.knowledge.assistant.ingestion.upload.DocumentImportService;
import com.lnjecit.knowledge.assistant.knowledge.service.KnowledgeBaseService;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class ImportPersistenceTests {
    @Container
    final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
                            "pgvector/pgvector:0.8.0-pg17@sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87")
                    .asCompatibleSubstituteFor("postgres"))
            .withImagePullPolicy(image -> false);

    @Configuration(proxyBeanMethods = false)
    static class StorageConfig {
        @Bean
        @Primary
        TestStore testStore() {
            return new TestStore();
        }
    }

    static class TestStore implements OriginalFileStore {
        final ConcurrentMap<String, byte[]> objects = new ConcurrentHashMap<>();
        final AtomicBoolean failPut = new AtomicBoolean();
        final AtomicBoolean failDelete = new AtomicBoolean();
        volatile CyclicBarrier barrier;

        public void put(String key, byte[] bytes) {
            if (failPut.get()) throw new IllegalStateException("upload failure");
            objects.put(key, bytes);
            if (barrier != null)
                try {
                    barrier.await(20, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
        }

        public void copy(String source, String target) {
            objects.put(target, objects.get(source).clone());
        }

        public byte[] read(String key) {
            return objects.get(key);
        }

        public void delete(String key) {
            if (failDelete.get()) throw new IllegalStateException("cleanup failure");
            objects.remove(key);
        }
    }

    @Test
    void realMultipartBoundariesAndConfiguredSnapshots() throws Exception {
        SpringApplication app = new SpringApplication(
                KnowledgeAssistantApplication.class, StorageConfig.class, BusinessHttpTestSecurity.class);
        try (ConfigurableApplicationContext context = app.run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--server.port=0",
                "--assistant.model.embedding-model=text-embedding-v4",
                "--assistant.model.dimensions=1024")) {
            Long kb = context.getBean(KnowledgeBaseService.class)
                    .create(42L, "http", null)
                    .getId();
            int port = ((org.springframework.boot.web.server.context.WebServerApplicationContext) context)
                    .getWebServer()
                    .getPort();
            String url = "http://localhost:" + port + "/api/v1/knowledge-bases/" + kb + "/documents:import";
            assertThat(upload(context, url, "limit.md", 2097152, 'a').statusCode())
                    .isEqualTo(202);
            assertThat(upload(context, url, "large.txt", 2097153, 'a').statusCode())
                    .isEqualTo(413);
            context.getBean(com.lnjecit.knowledge.assistant.ingestion.upload.UploadProperties.class)
                    .setMaxFileBytes(16);
            assertThat(upload(context, url, "small.txt", 17, 'b').statusCode()).isEqualTo(400);
            java.net.http.HttpResponse<String> txt = upload(context, url, "small.txt", 16, 'b');
            assertThat(txt.statusCode()).isEqualTo(202);
            assertThat(txt.body()).contains("RECEIVED", "importTaskId", "documentId");
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM document_index_version WHERE embedding_model='text-embedding-v4' AND embedding_dimension=1024",
                            Integer.class))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM document", Integer.class))
                    .isEqualTo(2);
        }
    }

    private static java.net.http.HttpResponse<String> upload(
            ConfigurableApplicationContext context, String url, String name, int size, char content) throws Exception {
        String boundary = "m103-boundary";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name
                + "\"\r\nContent-Type: text/plain\r\n\r\n"
                + String.valueOf(content).repeat(size) + "\r\n--" + boundary + "--\r\n";
        return java.net.http.HttpClient.newHttpClient()
                .send(
                        BusinessHttpTestSecurity.request(context, java.net.URI.create(url))
                                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void transactionDeduplicationAndCompensation(org.springframework.boot.test.system.CapturedOutput output)
            throws Exception {
        SpringApplication app = new SpringApplication(
                KnowledgeAssistantApplication.class, StorageConfig.class, BusinessHttpTestSecurity.class);
        try (ConfigurableApplicationContext context = app.run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--server.port=0")) {
            DocumentImportService service = context.getBean(DocumentImportService.class);
            TestStore store = context.getBean(TestStore.class);
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            KnowledgeBaseService knowledge = context.getBean(KnowledgeBaseService.class);
            Long kb = knowledge.create(42L, "one", null).getId();
            Long other = knowledge.create(42L, "two", null).getId();
            byte[] bytes = new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 65};
            DocumentImportService.Outcome first = service.receive(kb, ImportTests.file("a.md", bytes));
            assertThat(first.duplicate()).isFalse();
            String snapshot = jdbc.queryForObject(
                    "SELECT chunking_config FROM document_index_version WHERE document_id=?",
                    String.class,
                    first.task().documentId());
            com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingProperties properties =
                    context.getBean(com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingProperties.class);
            properties.setChunkSize(700);
            assertThat(context.getBean(tools.jackson.databind.json.JsonMapper.class)
                            .readValue(snapshot, ChunkingConfig.class))
                    .isEqualTo(new ChunkingConfig(
                            2,
                            com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingStrategy.FIXED_CODE_POINT,
                            800,
                            120));
            assertThat(first.task().status()).isEqualTo("RECEIVED");
            assertThat(service.receive(kb, ImportTests.file("b.txt", bytes))
                            .task()
                            .importTaskId())
                    .isEqualTo(first.task().importTaskId());
            assertThat(service.receive(other, ImportTests.file("a.txt", bytes)).duplicate())
                    .isFalse();
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM document WHERE active_version_id IS NULL", Integer.class))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM document_index_version WHERE status='BUILDING' AND published_at IS NULL AND expected_chunk_count=0 AND chunking_config LIKE '%FIXED_CODE_POINT%'",
                            Integer.class))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM document_import_task t JOIN document_index_version v ON t.index_version_id=v.id AND t.document_id=v.document_id WHERE t.status='PENDING'",
                            Integer.class))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM document_chunk", Integer.class))
                    .isZero();
            for (String key : jdbc.queryForList("SELECT object_key FROM document", String.class))
                assertThat(store.read(key)).isEqualTo(bytes);
            assertThatThrownBy(() -> service.receive(1L, ImportTests.file("a.md", bytes)))
                    .isInstanceOf(com.lnjecit.knowledge.assistant.common.error.BusinessException.class);
            store.barrier = new CyclicBarrier(2);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Callable<DocumentImportService.Outcome> request =
                        () -> service.receive(kb, ImportTests.file("race.txt", new byte[] {66}));
                Future<DocumentImportService.Outcome> a = executor.submit(request);
                Future<DocumentImportService.Outcome> b = executor.submit(request);
                DocumentImportService.Outcome x = a.get(30, TimeUnit.SECONDS);
                DocumentImportService.Outcome y = b.get(30, TimeUnit.SECONDS);
                assertThat(x.task().documentId()).isEqualTo(y.task().documentId());
                assertThat(x.duplicate()).isNotEqualTo(y.duplicate());
            } finally {
                executor.shutdownNow();
                store.barrier = null;
            }
            assertThat(store.objects).hasSize(3);
            jdbc.execute("ALTER TABLE document_import_task ADD CONSTRAINT test_reject_task CHECK (false) NOT VALID");
            assertThatThrownBy(() -> service.receive(kb, ImportTests.file("fail.txt", new byte[] {67})))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(store.objects).hasSize(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM document", Integer.class))
                    .isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM document_index_version", Integer.class))
                    .isEqualTo(3);
            store.failDelete.set(true);
            assertThatThrownBy(() -> service.receive(kb, ImportTests.file("fail.txt", new byte[] {68})))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(store.objects).hasSize(5);
            assertThat(output.getOut()).contains("ORIGINAL_OBJECT_CLEANUP_FAILED");
            store.failDelete.set(false);
            store.failPut.set(true);
            assertThatThrownBy(() -> service.receive(kb, ImportTests.file("fail.txt", new byte[] {69})))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM document_import_task", Integer.class))
                    .isEqualTo(3);
        }
    }
}
