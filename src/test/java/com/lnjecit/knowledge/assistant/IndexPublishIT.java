package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lnjecit.knowledge.assistant.ingestion.publish.IndexPublishService;
import com.lnjecit.knowledge.assistant.ingestion.task.ClaimedTask;
import java.util.List;
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
class IndexPublishIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
                            "pgvector/pgvector:0.8.0-pg17@sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87")
                    .asCompatibleSubstituteFor("postgres"))
            .withImagePullPolicy(image -> false);

    @Test
    void publishesAndRollsBack() {
        SpringApplication app = new SpringApplication(KnowledgeAssistantApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = app.run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword())) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            IndexPublishService service = context.getBean(IndexPublishService.class);
            ClaimedTask claim = new ClaimedTask(5L, 2L, 3L, "worker", 1L);
            jdbc.update(
                    "INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key) VALUES(2,1,'a','hash','key')");
            jdbc.update(
                    "INSERT INTO document_index_version(id,document_id,status,expected_chunk_count,chunking_config) VALUES(3,2,'VERIFIED',1,'{}')");
            jdbc.update(
                    "INSERT INTO document_import_task(id,document_id,index_version_id,status,lease_owner,claim_epoch,lease_until) VALUES(5,2,3,'RUNNING','worker',1,statement_timestamp()+interval '1 hour')");
            for (String mutation : List.of(
                    "UPDATE document_index_version SET status='BUILDING'",
                    "UPDATE document SET active_version_id=9",
                    "UPDATE document_index_version SET document_id=9",
                    "UPDATE document_import_task SET document_id=9",
                    "UPDATE document_import_task SET index_version_id=9",
                    "UPDATE document_import_task SET status='FAILED'",
                    "UPDATE document_import_task SET lease_owner='other'",
                    "UPDATE document_import_task SET claim_epoch=2",
                    "UPDATE document_import_task SET lease_until=statement_timestamp()-interval '1 second'",
                    "UPDATE document SET active_version_id=3",
                    "UPDATE document_index_version SET status='PUBLISHED',published_at=statement_timestamp()",
                    "UPDATE document_import_task SET status='SUCCEEDED'")) {
                jdbc.update(mutation);
                List<String> before = snapshot(jdbc);
                assertThatThrownBy(() -> service.publish(claim)).isInstanceOf(IllegalStateException.class);
                assertThat(snapshot(jdbc)).isEqualTo(before);
                jdbc.update("UPDATE document SET active_version_id=NULL");
                jdbc.update(
                        "UPDATE document_index_version SET document_id=2,status='VERIFIED',published_at=NULL,expected_chunk_count=1");
                jdbc.update(
                        "UPDATE document_import_task SET document_id=2,index_version_id=3,status='RUNNING',lease_owner='worker',claim_epoch=1,lease_until=statement_timestamp()+interval '1 hour'");
            }
            jdbc.execute(
                    "CREATE FUNCTION reject_publish() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$");
            jdbc.execute(
                    "CREATE TRIGGER reject_publish BEFORE UPDATE ON document_import_task FOR EACH ROW EXECUTE FUNCTION reject_publish()");
            List<String> before = snapshot(jdbc);
            assertThatThrownBy(() -> service.publish(claim)).isInstanceOf(IllegalStateException.class);
            assertThat(snapshot(jdbc)).isEqualTo(before);
            jdbc.execute("DROP TRIGGER reject_publish ON document_import_task");
            jdbc.execute("DROP FUNCTION reject_publish()");
            service.publish(claim);
            assertThat(jdbc.queryForObject("SELECT active_version_id FROM document", Long.class))
                    .isEqualTo(3L);
            assertThat(jdbc.queryForObject(
                            "SELECT status='PUBLISHED' AND published_at IS NOT NULL FROM document_index_version",
                            Boolean.class))
                    .isTrue();
            assertThat(jdbc.queryForObject(
                            "SELECT status='SUCCEEDED' AND lease_owner IS NULL AND lease_until IS NULL AND next_retry_at IS NULL AND last_error_code IS NULL AND last_error_message IS NULL FROM document_import_task",
                            Boolean.class))
                    .isTrue();
            before = snapshot(jdbc);
            service.publish(claim);
            assertThat(snapshot(jdbc)).isEqualTo(before);
            assertThatThrownBy(() -> service.publish(new ClaimedTask(5L, 2L, 3L, "worker", 2L)))
                    .isInstanceOf(IllegalStateException.class);
            jdbc.update("UPDATE document SET active_version_id=9");
            before = snapshot(jdbc);
            assertThatThrownBy(() -> service.publish(claim)).isInstanceOf(IllegalStateException.class);
            assertThat(snapshot(jdbc)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SHOW server_version", String.class)).startsWith("17.6");
            assertThat(jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname='vector'", String.class))
                    .isEqualTo("0.8.0");
        }
    }

    private static List<String> snapshot(JdbcTemplate jdbc) {
        return List.of("document", "document_index_version", "document_import_task").stream()
                .map(t -> jdbc.queryForObject("SELECT row_to_json(t)::text FROM " + t + " t", String.class))
                .toList();
    }
}
