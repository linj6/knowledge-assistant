package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;

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
class VectorSearchIT {
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
    void exactVisibility() {
        try (ConfigurableApplicationContext c = start()) {
            JdbcTemplate j = c.getBean(JdbcTemplate.class);
            com.lnjecit.knowledge.assistant.retrieval.vector.VectorSearchRepository r =
                    c.getBean(com.lnjecit.knowledge.assistant.retrieval.vector.VectorSearchRepository.class);
            j.update("INSERT INTO knowledge_base(id,name) VALUES(1,'a'),(2,'b')");
            j.update(
                    "INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key,active_version_id) VALUES(10,1,'a','a','k',20),(11,1,'b','b','k',NULL),(12,2,'c','c','k',22),(13,1,'d','d','k',NULL)");
            j.update(
                    "INSERT INTO document_index_version(id,document_id,chunking_config) VALUES(20,10,'{}'),(21,11,'{}'),(22,12,'{}'),(23,13,'{}')");
            float[] q = new float[1024];
            q[0] = 1;
            com.lnjecit.knowledge.assistant.retrieval.embedding.QueryVector v =
                    new com.lnjecit.knowledge.assistant.retrieval.embedding.QueryVector(q);
            // 最相似的未发布数据不能占用正式 Top-K 名额。
            chunk(j, 100, 1, 11, 21, 0, 1, 0);
            chunk(j, 102, 1, 10, 20, 0, .8f, .6f);
            chunk(j, 101, 1, 10, 20, 1, .8f, .6f);
            chunk(j, 103, 1, 10, 20, 2, 0, 1);
            chunk(j, 104, 2, 12, 22, 0, 1, 0);
            chunk(j, 105, 1, 13, 23, 0, 1, 0);
            chunk(j, 106, 1, 11, 20, 3, 1, 0);
            j.update(
                    "INSERT INTO document_chunk(id,knowledge_base_id,document_id,index_version_id,chunk_order,content,source_start,source_end) VALUES(107,1,10,20,4,'null',0,4)");
            com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedVersionSnapshot s =
                    com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedVersionSnapshot.of(
                            1L,
                            List.of(new com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedDocument(
                                    10L, 20L)));
            assertThat(r.search(s, v, 2))
                    .extracting(com.lnjecit.knowledge.assistant.retrieval.vector.VectorSearchMapper.Row::chunkId)
                    .containsExactly(101L, 102L);
            assertThat(r.search(s, v, 10)).hasSize(3);
            assertThat(r.search(s, v, 10).getFirst().distance())
                    .isCloseTo(.2, org.assertj.core.data.Offset.offset(.000001));
            j.update("UPDATE document SET active_version_id=23 WHERE id=13");
            assertThat(r.search(s, v, 10)).hasSize(3);
            com.lnjecit.knowledge.assistant.retrieval.vector.VectorRetriever retriever =
                    c.getBean(com.lnjecit.knowledge.assistant.retrieval.vector.VectorRetriever.class);
            com.lnjecit.knowledge.assistant.retrieval.model.RetrievalContext context =
                    new com.lnjecit.knowledge.assistant.retrieval.model.RetrievalContext(
                            "r",
                            "q",
                            com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStrategy.VECTOR,
                            new com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters(2, 2, 60, 2, 1),
                            s);
            assertThat(retriever.retrieve(context, v).chunks()).hasSize(2).allMatch(chunk -> chunk.finalRank() == null);
            com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedVersionSnapshot empty =
                    com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedVersionSnapshot.of(1L, List.of());
            assertThat(retriever
                            .retrieve(new com.lnjecit.knowledge.assistant.retrieval.model.RetrievalContext(
                                    "r", "q", context.strategy(), context.parameters(), empty))
                            .status())
                    .isEqualTo(com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStatus.EMPTY_RESULT);
            j.execute("ALTER TABLE document_chunk RENAME TO unavailable_chunks");
            assertThat(retriever.retrieve(context, v).failure())
                    .isEqualTo(
                            com.lnjecit.knowledge.assistant.retrieval.model.RetrievalDiagnostics.Failure.VECTOR_FAILED);
            assertThat(r.search(empty, v, 2)).isEmpty();
        }
    }

    private void chunk(JdbcTemplate j, long id, long kb, long doc, long version, int order, float x, float y) {
        float[] values = new float[1024];
        values[0] = x;
        values[1] = y;
        j.update(
                "INSERT INTO document_chunk(id,knowledge_base_id,document_id,index_version_id,chunk_order,content,source_start,source_end,embedding) VALUES(?,?,?,?,?,'text',0,4,?::vector)",
                id,
                kb,
                doc,
                version,
                order,
                java.util.Arrays.toString(values));
    }
}
