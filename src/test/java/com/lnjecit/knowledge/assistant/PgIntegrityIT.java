package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lnjecit.knowledge.assistant.ingestion.verification.PgIntegrityService;
import java.util.Arrays;
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
class PgIntegrityIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
                            "pgvector/pgvector:0.8.0-pg17@sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87")
                    .asCompatibleSubstituteFor("postgres"))
            .withImagePullPolicy(image -> false);

    @Test
    void validatesWithoutWritesAndRejectsIncompleteData() {
        SpringApplication app = new SpringApplication(KnowledgeAssistantApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = app.run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword())) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            PgIntegrityService service = context.getBean(PgIntegrityService.class);
            jdbc.update("INSERT INTO knowledge_base(id,name) VALUES(1,'test')");
            jdbc.update(
                    "INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key) VALUES(2,1,'a','hash','key')");
            jdbc.update(
                    "INSERT INTO document_index_version(id,document_id,expected_chunk_count,chunking_config) VALUES(3,2,1,'{}')");
            jdbc.update(
                    "INSERT INTO document_chunk(id,knowledge_base_id,document_id,index_version_id,chunk_order,content,source_start,source_end,embedding) VALUES(4,1,2,3,0,'知识库',0,3,?::vector)",
                    Arrays.toString(new float[1024]));
            jdbc.update("INSERT INTO document_import_task(id,document_id,index_version_id) VALUES(5,2,3)");
            List<String> tables =
                    List.of("document", "document_index_version", "document_chunk", "document_import_task");
            List<String> before = tables.stream()
                    .map(t -> jdbc.queryForList("SELECT row_to_json(t)::text FROM " + t + " t", String.class)
                            .toString())
                    .toList();
            assertThat(service.verify(2L, 3L).chunkCount()).isEqualTo(1);
            assertThat(tables.stream()
                            .map(t -> jdbc.queryForList("SELECT row_to_json(t)::text FROM " + t + " t", String.class)
                                    .toString())
                            .toList())
                    .isEqualTo(before);
            for (String mutation : List.of(
                    "UPDATE document_index_version SET expected_chunk_count=2 WHERE id=3",
                    "UPDATE document_chunk SET embedding=NULL WHERE id=4",
                    "UPDATE document_chunk SET chunk_order=1 WHERE id=4",
                    "UPDATE document_chunk SET knowledge_base_id=9 WHERE id=4",
                    "UPDATE document_index_version SET status='VERIFIED' WHERE id=3",
                    "UPDATE document_index_version SET published_at=now() WHERE id=3",
                    "UPDATE document SET active_version_id=3 WHERE id=2")) {
                jdbc.update(mutation);
                assertThatThrownBy(() -> service.verify(2L, 3L)).isInstanceOf(IllegalStateException.class);
                jdbc.update(
                        "UPDATE document_index_version SET expected_chunk_count=1,status='BUILDING',published_at=NULL WHERE id=3");
                jdbc.update("UPDATE document SET active_version_id=NULL WHERE id=2");
                jdbc.update(
                        "UPDATE document_chunk SET chunk_order=0,knowledge_base_id=1,embedding=?::vector WHERE id=4",
                        Arrays.toString(new float[1024]));
            }
            assertThat(tables.stream()
                            .map(t -> jdbc.queryForList("SELECT row_to_json(t)::text FROM " + t + " t", String.class)
                                    .toString())
                            .toList())
                    .isEqualTo(before);
            assertThat(jdbc.queryForObject("SHOW server_version", String.class)).startsWith("17.6");
            assertThat(jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname='vector'", String.class))
                    .isEqualTo("0.8.0");
        }
    }
}
