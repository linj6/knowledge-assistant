package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkService;
import com.lnjecit.knowledge.assistant.ingestion.parser.ParsedDocument;
import com.lnjecit.knowledge.assistant.ingestion.parser.TxtDocumentParser;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class EmbeddingPersistenceIT {
    @Container
    final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
                            "pgvector/pgvector:0.8.0-pg17@sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87")
                    .asCompatibleSubstituteFor("postgres"))
            .withImagePullPolicy(image -> false);

    @Test
    void retryRollbackAndUniqueConstraint() {
        SpringApplication app = new SpringApplication(KnowledgeAssistantApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = app.run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--assistant.model.enabled=false",
                "--assistant.rerank.enabled=false")) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            jdbc.update("INSERT INTO knowledge_base(id,name) VALUES(1,'test')");
            jdbc.update(
                    "INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key) VALUES(2,1,'a.txt','hash','key')");
            String config = "{\"version\":1,\"strategy\":\"FIXED_CODE_POINT\",\"chunkSize\":800,\"chunkOverlap\":120}";
            jdbc.update(
                    "INSERT INTO document_index_version(id,document_id,chunking_config) VALUES(3,2,?),(4,2,?)",
                    config,
                    config);
            ChunkService service = context.getBean(ChunkService.class);
            ParsedDocument parsed =
                    new TxtDocumentParser().parse("中😀".repeat(801).getBytes(StandardCharsets.UTF_8));
            java.util.List<com.lnjecit.knowledge.assistant.infrastructure.persistence.DocumentChunk> first =
                    service.initialize(2L, 3L, parsed);
            com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingRepository embeddings =
                    context.getBean(com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingRepository.class);
            String vector = java.util.Arrays.toString(new float[1024]);
            embeddings.write(2L, 3L, first, java.util.Collections.nCopies(first.size(), vector));
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM document_chunk WHERE embedding IS NOT NULL", Integer.class))
                    .isEqualTo(first.size());
            assertThat(jdbc.queryForObject("SELECT min(vector_dims(embedding)) FROM document_chunk", Integer.class))
                    .isEqualTo(1024);
            assertThatThrownBy(() -> jdbc.update(
                            "UPDATE document_chunk SET embedding='[1,2]' WHERE id=?",
                            first.getFirst().getId()))
                    .isInstanceOf(RuntimeException.class);
            String original = jdbc.queryForObject(
                    "SELECT embedding::text FROM document_chunk WHERE id=?",
                    String.class,
                    first.getFirst().getId());
            embeddings.write(
                    2L,
                    3L,
                    first,
                    java.util.Collections.nCopies(first.size(), java.util.Arrays.toString(new float[1024])));
            assertThat(jdbc.queryForObject(
                            "SELECT embedding::text FROM document_chunk WHERE id=?",
                            String.class,
                            first.getFirst().getId()))
                    .isEqualTo(original);
            jdbc.update("UPDATE document_chunk SET embedding=NULL WHERE index_version_id=3");
            java.util.List<String> invalid =
                    new java.util.ArrayList<>(java.util.Collections.nCopies(first.size(), vector));
            invalid.set(invalid.size() - 1, "[1,2]");
            assertThatThrownBy(() -> embeddings.write(2L, 3L, first, invalid)).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM document_chunk WHERE embedding IS NOT NULL", Integer.class))
                    .isZero();
            jdbc.execute(
                    "CREATE FUNCTION reject_embedding() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.chunk_order=1 THEN RAISE EXCEPTION 'injected'; END IF; RETURN NEW; END $$");
            jdbc.execute(
                    "CREATE TRIGGER reject_embedding BEFORE UPDATE ON document_chunk FOR EACH ROW EXECUTE FUNCTION reject_embedding()");
            assertThatThrownBy(
                            () -> embeddings.write(2L, 3L, first, java.util.Collections.nCopies(first.size(), vector)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM document_chunk WHERE embedding IS NOT NULL", Integer.class))
                    .isZero();
            jdbc.execute("DROP TRIGGER reject_embedding ON document_chunk");
            embeddings.write(2L, 3L, first, java.util.Collections.nCopies(first.size(), vector));
            assertThat(service.initialize(2L, 3L, parsed))
                    .extracting(c -> c.getId())
                    .containsExactlyElementsOf(
                            first.stream().map(c -> c.getId()).toList());
            assertThat(jdbc.queryForObject(
                            "SELECT expected_chunk_count FROM document_index_version WHERE id=3", Integer.class))
                    .isEqualTo(first.size());
            assertThatThrownBy(() -> service.initialize(
                            2L, 3L, new TxtDocumentParser().parse("different".getBytes(StandardCharsets.UTF_8))))
                    .isInstanceOf(IllegalStateException.class);
            TransactionTemplate transaction =
                    new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            assertThatThrownBy(() -> transaction.execute(status -> {
                        service.initialize(2L, 4L, parsed);
                        throw new IllegalStateException("rollback");
                    }))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM document_chunk WHERE index_version_id=4", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject(
                            "SELECT expected_chunk_count FROM document_index_version WHERE id=4", Integer.class))
                    .isZero();
            assertThatThrownBy(
                            () -> jdbc.update(
                                    "INSERT INTO document_chunk(id,knowledge_base_id,document_id,index_version_id,chunk_order,content,source_start,source_end) VALUES(999,1,2,3,0,'x',0,1)"))
                    .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
            for (String state : new String[] {"VERIFIED", "PUBLISHED", "FAILED"}) {
                jdbc.update("UPDATE document_index_version SET status=? WHERE id=3", state);
                assertThatThrownBy(() -> service.initialize(2L, 3L, parsed)).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() ->
                                embeddings.write(2L, 3L, first, java.util.Collections.nCopies(first.size(), vector)))
                        .isInstanceOf(IllegalStateException.class);
            }
            jdbc.update("UPDATE document_index_version SET status='BUILDING' WHERE id=3");
            jdbc.update("UPDATE document SET active_version_id=3 WHERE id=2");
            assertThatThrownBy(() -> service.initialize(2L, 3L, parsed)).isInstanceOf(IllegalStateException.class);
        }
    }
}
