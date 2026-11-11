package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lnjecit.knowledge.assistant.ingestion.task.ClaimedTask;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RetrievalFoundationIT {
    @Container
    final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
                            "pgvector/pgvector:0.8.0-pg17@sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87")
                    .asCompatibleSubstituteFor("postgres"))
            .withImagePullPolicy(image -> false);

    private ConfigurableApplicationContext start() {
        SpringApplication app =
                new SpringApplication(KnowledgeAssistantApplication.class, ImportPersistenceTests.StorageConfig.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        return app.run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--assistant.model.enabled=false",
                "--assistant.elasticsearch.enabled=false");
    }

    @Test
    void snapshotVisibilityAndCorruption() {
        try (ConfigurableApplicationContext c = start()) {
            JdbcTemplate j = c.getBean(JdbcTemplate.class);
            com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedVersionSnapshotService s =
                    c.getBean(com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedVersionSnapshotService.class);
            assertThatThrownBy(() -> s.read(1L))
                    .isInstanceOf(com.lnjecit.knowledge.assistant.common.error.BusinessException.class);
            j.update("INSERT INTO knowledge_base(id,name) VALUES(1,'a'),(2,'b')");
            assertThat(s.read(1L).documents()).isEmpty();
            j.update(
                    "INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key) VALUES(10,1,'a','a','key'),(11,1,'b','b','key'),(12,2,'c','c','key')");
            j.update(
                    "INSERT INTO document_index_version(id,document_id,chunking_config,status,expected_chunk_count) VALUES(20,10,'{}','BUILDING',0),(21,11,'{}','VERIFIED',1),(22,12,'{}','PUBLISHED',1)");
            assertThat(s.read(1L).documents()).isEmpty();
            j.update("UPDATE document SET active_version_id=22 WHERE id=12");
            assertThat(s.read(2L).documents()).hasSize(1);
            // 正常发布使用生产协议；后续指针破坏是显式异常数据注入。
            j.update("UPDATE document_index_version SET status='VERIFIED',expected_chunk_count=1 WHERE id=20");
            j.update(
                    "INSERT INTO document_import_task(id,document_id,index_version_id,status,lease_owner,claim_epoch,lease_until) VALUES(30,10,20,'RUNNING','worker',1,now()+interval '1 hour')");
            c.getBean(com.lnjecit.knowledge.assistant.ingestion.publish.IndexPublishService.class)
                    .publish(new ClaimedTask(30L, 10L, 20L, "worker", 1L));
            com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedVersionSnapshot old = s.read(1L);
            assertThat(old.documents()).hasSize(1);
            assertThat(old.snapshotHash()).isEqualTo(s.read(1L).snapshotHash());
            assertThatThrownBy(() -> old.documents().clear()).isInstanceOf(UnsupportedOperationException.class);
            j.update(
                    "INSERT INTO document_import_task(id,document_id,index_version_id,status,lease_owner,claim_epoch,lease_until) VALUES(31,11,21,'RUNNING','worker',1,now()+interval '1 hour')");
            c.getBean(com.lnjecit.knowledge.assistant.ingestion.publish.IndexPublishService.class)
                    .publish(new ClaimedTask(31L, 11L, 21L, "worker", 1L));
            assertThat(s.read(1L).documents()).hasSize(2);
            assertThat(old.documents()).hasSize(1);
            j.update("UPDATE document SET active_version_id=999 WHERE id=10");
            assertThatThrownBy(() -> s.read(1L))
                    .isInstanceOf(com.lnjecit.knowledge.assistant.retrieval.snapshot.SnapshotInvalidException.class);
            j.update("UPDATE document SET active_version_id=22 WHERE id=10");
            assertThatThrownBy(() -> s.read(1L))
                    .isInstanceOf(com.lnjecit.knowledge.assistant.retrieval.snapshot.SnapshotInvalidException.class);
            j.execute("ALTER TABLE document_index_version RENAME TO unavailable_versions");
            assertThatThrownBy(() -> s.read(1L)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
    }

    @Test
    void capacityConcurrentUploadsAndRollback() throws Exception {
        try (ConfigurableApplicationContext c = start()) {
            JdbcTemplate j = c.getBean(JdbcTemplate.class);
            com.lnjecit.knowledge.assistant.ingestion.upload.DocumentImportService service =
                    c.getBean(com.lnjecit.knowledge.assistant.ingestion.upload.DocumentImportService.class);
            ImportPersistenceTests.TestStore store = c.getBean(ImportPersistenceTests.TestStore.class);
            j.update("INSERT INTO knowledge_base(id,name) VALUES(1,'a'),(2,'b')");
            j.update(
                    "INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key) SELECT n,1,'seed',n::text,'seed' FROM generate_series(1,999) n");
            store.barrier = new java.util.concurrent.CyclicBarrier(2);
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                List<Future<Boolean>> results = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    final byte value = (byte) (65 + i);
                    results.add(executor.submit(() -> {
                        try {
                            service.receive(1L, ImportTests.file("a.txt", new byte[] {value}));
                            return true;
                        } catch (com.lnjecit.knowledge.assistant.common.error.BusinessException ex) {
                            assertThat(ex.getErrorCode())
                                    .isEqualTo(
                                            com.lnjecit.knowledge.assistant.ingestion.upload.ImportErrorCode
                                                    .KNOWLEDGE_BASE_CAPACITY_EXCEEDED);
                            return false;
                        }
                    }));
                }
                int successes = 0;
                for (Future<Boolean> f : results) if (f.get(30, TimeUnit.SECONDS)) successes++;
                assertThat(successes).isEqualTo(1);
            } finally {
                store.barrier = null;
            }
            assertThat(j.queryForObject("SELECT count(*) FROM document WHERE knowledge_base_id=1", Integer.class))
                    .isEqualTo(1000);
            assertThat(j.queryForObject("SELECT count(*) FROM document_index_version", Integer.class))
                    .isEqualTo(1);
            assertThat(j.queryForObject("SELECT count(*) FROM document_import_task", Integer.class))
                    .isEqualTo(1);
            assertThat(store.objects).hasSize(1);
            byte[] winner = store.objects.values().iterator().next();
            assertThat(service.receive(1L, ImportTests.file("duplicate.txt", winner))
                            .duplicate())
                    .isTrue();
            assertThatThrownBy(() -> service.receive(1L, ImportTests.file("full.txt", new byte[] {90})))
                    .isInstanceOf(com.lnjecit.knowledge.assistant.common.error.BusinessException.class);
            assertThat(store.objects).hasSize(1);
            service.receive(2L, ImportTests.file("other.txt", new byte[] {90}));
            j.execute("ALTER TABLE document_import_task ADD CONSTRAINT reject_new CHECK(false) NOT VALID");
            assertThatThrownBy(() -> service.receive(2L, ImportTests.file("rollback.txt", new byte[] {91})))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(j.queryForObject("SELECT count(*) FROM document WHERE knowledge_base_id=2", Integer.class))
                    .isEqualTo(1);
            assertThat(j.queryForObject("SELECT count(*) FROM document_index_version", Integer.class))
                    .isEqualTo(2);
            assertThat(store.objects).hasSize(2);
        }
    }
}
