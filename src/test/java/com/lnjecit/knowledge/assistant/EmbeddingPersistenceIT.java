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
    void upgradeV4PreservesExistingChunk() {
        org.springframework.jdbc.datasource.DriverManagerDataSource source =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        org.flywaydb.core.Flyway.configure()
                .dataSource(source)
                .target("4")
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.update(
                "INSERT INTO document_chunk(id,knowledge_base_id,document_id,index_version_id,chunk_order,content,source_start,source_end) VALUES(99,1,2,3,0,'existing',0,8)");
        java.util.Map<String, Object> before = jdbc.queryForMap("SELECT * FROM document_chunk WHERE id=99");
        assertThat(org.flywaydb.core.Flyway.configure()
                        .dataSource(source)
                        .load()
                        .migrate()
                        .migrationsExecuted)
                .isEqualTo(1);
        java.util.Map<String, Object> after = jdbc.queryForMap("SELECT * FROM document_chunk WHERE id=99");
        assertThat(after.remove("version")).isEqualTo(0);
        assertThat(after).isEqualTo(before);
    }

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
            java.util.List<com.lnjecit.knowledge.assistant.document.entity.DocumentChunk> first =
                    service.initialize(2L, 3L, parsed);
            com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingRepository embeddings =
                    context.getBean(com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingRepository.class);
            assertThat(first).allMatch(c -> Integer.valueOf(0).equals(c.getVersion()));
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
            assertThat(embeddings.read(2L, 3L)).allMatch(c -> Integer.valueOf(1).equals(c.getVersion()));
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
            jdbc.update("UPDATE document_chunk SET embedding=NULL,version=0 WHERE index_version_id=3");
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
            assertThat(embeddings.read(2L, 3L)).allMatch(c -> Integer.valueOf(0).equals(c.getVersion()));
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
            jdbc.update("UPDATE document SET active_version_id=NULL WHERE id=2");
            jdbc.update(
                    "INSERT INTO document_index_version(id,document_id,chunking_config) VALUES(5,2,?),(6,2,?),(7,2,?)",
                    config,
                    config,
                    config);
            java.util.List<com.lnjecit.knowledge.assistant.document.entity.DocumentChunk> recovery = service.initialize(
                    2L, 5L, new TxtDocumentParser().parse("a".repeat(11680).getBytes(StandardCharsets.UTF_8)));
            assertThat(recovery).hasSize(17);
            org.springframework.ai.openai.OpenAiEmbeddingModel model =
                    org.mockito.Mockito.mock(org.springframework.ai.openai.OpenAiEmbeddingModel.class);
            org.mockito.Mockito.when(model.getOptions())
                    .thenReturn(org.springframework.ai.openai.OpenAiEmbeddingOptions.builder()
                            .model("text-embedding-v4")
                            .dimensions(1024)
                            .build());
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            java.util.List<Integer> sizes = new java.util.ArrayList<>();
            org.mockito.Mockito.when(model.call(
                            org.mockito.ArgumentMatchers.any(org.springframework.ai.embedding.EmbeddingRequest.class)))
                    .thenAnswer(call -> {
                        org.springframework.ai.embedding.EmbeddingRequest request = call.getArgument(0);
                        sizes.add(request.getInstructions().size());
                        if (calls.incrementAndGet() == 2) throw new IllegalStateException("injected model failure");
                        java.util.List<org.springframework.ai.embedding.Embedding> result = new java.util.ArrayList<>();
                        for (int i = request.getInstructions().size() - 1; i >= 0; i--)
                            result.add(new org.springframework.ai.embedding.Embedding(new float[1024], i));
                        return new org.springframework.ai.embedding.EmbeddingResponse(result);
                    });
            com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingService embeddingService =
                    new com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingService(
                            model,
                            embeddings,
                            context.getBean(
                                    com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingProperties.class));
            assertThatThrownBy(() -> embeddingService.embed(2L, 5L)).isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM document_chunk WHERE index_version_id=5 AND embedding IS NOT NULL",
                            Integer.class))
                    .isEqualTo(10);
            embeddingService.embed(2L, 5L);
            embeddingService.embed(2L, 5L);
            assertThat(sizes).containsExactly(10, 7, 7);
            assertThat(embeddings.read(2L, 5L)).allMatch(c -> c.getEmbedding() != null);
            float[] different = new float[1024];
            different[0] = 1;
            embeddings.write(2L, 5L, recovery.subList(0, 1), java.util.List.of(java.util.Arrays.toString(different)));
            assertThat(jdbc.queryForObject(
                            "SELECT embedding::text FROM document_chunk WHERE id=?",
                            String.class,
                            recovery.getFirst().getId()))
                    .startsWith("[0,");
            service.initialize(2L, 6L, new TxtDocumentParser().parse("a".getBytes(StandardCharsets.UTF_8)));
            org.mockito.Mockito.when(model.call(
                            org.mockito.ArgumentMatchers.any(org.springframework.ai.embedding.EmbeddingRequest.class)))
                    .thenAnswer(call -> {
                        jdbc.update("UPDATE document_index_version SET status='VERIFIED' WHERE id=6");
                        return new org.springframework.ai.embedding.EmbeddingResponse(
                                java.util.List.of(new org.springframework.ai.embedding.Embedding(new float[1024], 0)));
                    });
            assertThatThrownBy(() -> embeddingService.embed(2L, 6L)).hasMessageContaining("version unavailable");
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM document_chunk WHERE index_version_id=6 AND embedding IS NOT NULL",
                            Integer.class))
                    .isZero();
            // 默认配置已为 v2，历史 v1 恢复仍只读取版本中保存的快照。
            jdbc.update("INSERT INTO document_index_version(id,document_id,chunking_config) VALUES(9,2,?)", config);
            com.lnjecit.knowledge.assistant.ingestion.parser.ParsedDocument blankParsed =
                    new TxtDocumentParser().parse(("a" + " ".repeat(2000) + "b").getBytes(StandardCharsets.UTF_8));
            java.util.List<com.lnjecit.knowledge.assistant.document.entity.DocumentChunk> legacy =
                    service.initialize(2L, 9L, blankParsed);
            assertThat(legacy).hasSize(3);
            assertThat(legacy.get(1).getContent()).isBlank();
            assertThat(legacy).extracting(c -> c.getChunkOrder()).containsExactly(0, 1, 2);
            assertThat(legacy).extracting(c -> c.getSourceStart()).containsExactly(0, 680, 1360);
            assertThat(legacy).extracting(c -> c.getSourceEnd()).containsExactly(800, 1480, 2002);
            assertThat(legacy).allMatch(c -> c.getContent()
                    .equals(blankParsed.source().substring(c.getSourceStart(), c.getSourceEnd())));
            String legacySnapshot = jdbc.queryForObject(
                    "SELECT json_agg(t ORDER BY id)::text FROM document_chunk t WHERE index_version_id=9",
                    String.class);
            assertThat(service.initialize(2L, 9L, blankParsed))
                    .extracting(c -> c.getId())
                    .containsExactlyElementsOf(
                            legacy.stream().map(c -> c.getId()).toList());
            assertThat(jdbc.queryForObject(
                            "SELECT json_agg(t ORDER BY id)::text FROM document_chunk t WHERE index_version_id=9",
                            String.class))
                    .isEqualTo(legacySnapshot);
            assertThat(jdbc.queryForObject(
                            "SELECT expected_chunk_count FROM document_index_version WHERE id=9", Integer.class))
                    .isEqualTo(3);
            org.mockito.Mockito.clearInvocations(model);
            assertThatThrownBy(() -> embeddingService.embed(2L, 9L)).hasMessageContaining("input limit exceeded");
            org.mockito.Mockito.verify(model, org.mockito.Mockito.never())
                    .call(org.mockito.ArgumentMatchers.any(org.springframework.ai.embedding.EmbeddingRequest.class));
            assertThat(jdbc.queryForObject(
                            "SELECT json_agg(t ORDER BY id)::text FROM document_chunk t WHERE index_version_id=9",
                            String.class))
                    .isEqualTo(legacySnapshot);
            jdbc.update(
                    "UPDATE document_index_version SET chunking_config=? WHERE id=7",
                    config.replace("\"version\":1", "\"version\":2"));
            java.util.List<com.lnjecit.knowledge.assistant.document.entity.DocumentChunk> whitespace =
                    service.initialize(
                            2L,
                            7L,
                            new TxtDocumentParser()
                                    .parse(("a" + " ".repeat(2000) + "b").getBytes(StandardCharsets.UTF_8)));
            assertThat(whitespace).hasSize(2).allMatch(c -> !c.getContent().isBlank());
            assertThat(whitespace).extracting(c -> c.getChunkOrder()).containsExactly(0, 1);
            assertThat(jdbc.queryForObject(
                            "SELECT expected_chunk_count FROM document_index_version WHERE id=7", Integer.class))
                    .isEqualTo(2);
            org.mockito.Mockito.clearInvocations(model);
            org.mockito.Mockito.when(model.call(
                            org.mockito.ArgumentMatchers.any(org.springframework.ai.embedding.EmbeddingRequest.class)))
                    .thenAnswer(call -> new org.springframework.ai.embedding.EmbeddingResponse(java.util.List.of(
                            new org.springframework.ai.embedding.Embedding(new float[1024], 0),
                            new org.springframework.ai.embedding.Embedding(new float[1024], 1))));
            embeddingService.embed(2L, 7L);
            assertThat(embeddings.read(2L, 7L)).allMatch(c -> c.getEmbedding() != null);
            jdbc.update("INSERT INTO document_index_version(id,document_id,chunking_config) VALUES(8,2,?)", config);
            java.util.List<com.lnjecit.knowledge.assistant.document.entity.DocumentChunk> locked =
                    service.initialize(2L, 8L, new TxtDocumentParser().parse("lock".getBytes(StandardCharsets.UTF_8)));
            for (String state : new String[] {"VERIFIED", "PUBLISHED", "FAILED"}) {
                jdbc.update("UPDATE document_index_version SET status=? WHERE id=8", state);
                assertThatThrownBy(() -> embeddings.write(2L, 8L, locked, java.util.List.of(vector)))
                        .hasMessageContaining("version unavailable");
            }
            jdbc.update("UPDATE document_index_version SET status='BUILDING',published_at=now() WHERE id=8");
            assertThatThrownBy(() -> embeddings.write(2L, 8L, locked, java.util.List.of(vector)))
                    .hasMessageContaining("version unavailable");
            jdbc.update("UPDATE document_index_version SET published_at=NULL WHERE id=8");
            jdbc.update("UPDATE document SET active_version_id=8 WHERE id=2");
            assertThatThrownBy(() -> embeddings.write(2L, 8L, locked, java.util.List.of(vector)))
                    .hasMessageContaining("version unavailable");
            jdbc.update("UPDATE document SET active_version_id=NULL WHERE id=2");
            com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties properties =
                    context.getBean(com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties.class);
            properties.setEmbeddingModel("wrong");
            assertThatThrownBy(() -> embeddings.write(2L, 8L, locked, java.util.List.of(vector)))
                    .hasMessageContaining("snapshot mismatch");
            properties.setEmbeddingModel("text-embedding-v4");
            properties.setDimensions(512);
            assertThatThrownBy(() -> embeddings.write(2L, 8L, locked, java.util.List.of(vector)))
                    .hasMessageContaining("snapshot mismatch");
            properties.setDimensions(1024);
            assertThatThrownBy(() -> embeddings.write(2L, 5L, locked, java.util.List.of(vector)))
                    .hasMessageContaining("ownership");
            com.lnjecit.knowledge.assistant.document.entity.DocumentChunk candidate = locked.getFirst();
            jdbc.update("UPDATE document_chunk SET version=version+1 WHERE id=?", candidate.getId());
            assertThatThrownBy(() -> embeddings.write(2L, 8L, locked, java.util.List.of(vector)))
                    .hasMessageContaining("optimistic lock");
            java.util.List<com.lnjecit.knowledge.assistant.document.entity.DocumentChunk> contenderA =
                    embeddings.read(2L, 8L);
            java.util.List<com.lnjecit.knowledge.assistant.document.entity.DocumentChunk> contenderB =
                    embeddings.read(2L, 8L);
            java.util.concurrent.CompletableFuture<Void> updateA = java.util.concurrent.CompletableFuture.runAsync(
                    () -> embeddings.write(2L, 8L, contenderA, java.util.List.of(vector)));
            java.util.concurrent.CompletableFuture<Void> updateB = java.util.concurrent.CompletableFuture.runAsync(
                    () -> embeddings.write(2L, 8L, contenderB, java.util.List.of(vector)));
            updateA.join();
            updateB.join();
            assertThat(embeddings.read(2L, 8L).getFirst().getVersion()).isEqualTo(2);
            embeddings.write(2L, 8L, locked, java.util.List.of(vector));
            assertThat(embeddings.read(2L, 8L).getFirst().getVersion()).isEqualTo(2);
            // 正式入口返回后，外层测试事务仍持有业务行锁，状态转换必须等待提交。
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Void>> transition =
                    new java.util.concurrent.atomic.AtomicReference<>();
            transaction.executeWithoutResult(status -> {
                embeddings.write(2L, 8L, locked, java.util.List.of(vector));
                transition.set(java.util.concurrent.CompletableFuture.runAsync(() -> new TransactionTemplate(
                                context.getBean(PlatformTransactionManager.class))
                        .executeWithoutResult(other -> {
                            context.getBean(com.lnjecit.knowledge.assistant.document.mapper.DocumentMapper.class)
                                    .selectForUpdate(2L);
                            context.getBean(com.lnjecit.knowledge.assistant.document.mapper.IndexVersionMapper.class)
                                    .selectForUpdate(8L);
                            jdbc.update("UPDATE document_index_version SET status='VERIFIED' WHERE id=8");
                        })));
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                boolean blocked = false;
                while (System.nanoTime() < deadline) {
                    if (jdbc.queryForObject(
                                    "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND cardinality(pg_blocking_pids(pid))>0",
                                    Integer.class)
                            > 0) {
                        blocked = true;
                        break;
                    }
                    java.util.concurrent.locks.LockSupport.parkNanos(
                            java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(20));
                }
                assertThat(blocked).isTrue();
                assertThat(transition.get()).isNotCompleted();
            });
            transition.get().join();
            assertThatThrownBy(() -> embeddings.write(2L, 8L, locked, java.util.List.of(vector)))
                    .hasMessageContaining("version unavailable");
        }
    }
}
