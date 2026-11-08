package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Mapper;
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
class MybatisCompatibilityTests {
    @Container
    final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
                            "pgvector/pgvector:0.8.0-pg17@sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87")
                    .asCompatibleSubstituteFor("postgres"))
            .withImagePullPolicy(image -> false);

    @TableName("compatibility_probe")
    public static class Probe {
        @TableId(type = IdType.ASSIGN_ID)
        public Long id;

        public String content;
    }

    @Mapper
    public interface ProbeMapper extends BaseMapper<Probe> {}

    @Test
    void crudBindingSnowflakeAndRollback() {
        SpringApplication application = new SpringApplication(KnowledgeAssistantApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = application.run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--assistant.model.enabled=false",
                "--assistant.rerank.enabled=false")) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            jdbc.execute("CREATE TABLE compatibility_probe(id BIGINT PRIMARY KEY, content TEXT NOT NULL)");
            assertThat(context.getBean(org.flywaydb.core.Flyway.class).migrate().migrationsExecuted)
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class))
                    .isEqualTo(4);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM pg_constraint WHERE contype='f' AND connamespace='public'::regnamespace",
                            Integer.class))
                    .isZero();
            jdbc.execute("INSERT INTO knowledge_base(id,name) VALUES (1,'测试'),(2,'跨库')");
            jdbc.execute(
                    "INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key) VALUES (1,1,'a','hash','key'),(2,2,'a','hash','key')");
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> jdbc.execute(
                                    "INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key) VALUES (3,1,'a','hash','key')"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            jdbc.execute("INSERT INTO document_index_version(id,document_id,chunking_config) VALUES (1,1,'{}')");
            String vector = "[" + "0,".repeat(1023) + "1]";
            jdbc.update(
                    "INSERT INTO document_chunk(id,knowledge_base_id,document_id,index_version_id,chunk_order,content,source_start,source_end,embedding) VALUES (1,1,1,1,0,'中文😀',0,3,?::vector)",
                    vector);
            assertThat(jdbc.queryForObject(
                            "SELECT vector_dims(embedding) FROM document_chunk WHERE id=1", Integer.class))
                    .isEqualTo(1024);
            for (String sql : java.util.List.of(
                    "UPDATE document_chunk SET embedding='[1,2,3]' WHERE id=1",
                    "UPDATE document_chunk SET source_start=-1 WHERE id=1",
                    "UPDATE document_chunk SET source_end=0 WHERE id=1",
                    "UPDATE document_index_version SET status='UNKNOWN' WHERE id=1",
                    "UPDATE document_import_task SET status='UNKNOWN'",
                    "INSERT INTO document_chunk(id,knowledge_base_id,document_id,index_version_id,chunk_order,content,source_start,source_end) VALUES (2,1,1,1,0,'重复',0,2)")) {
                if (!sql.startsWith("UPDATE document_import_task")) {
                    org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.execute(sql))
                            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
                }
            }
            jdbc.execute("INSERT INTO document_import_task(id,document_id,index_version_id) VALUES (1,1,1)");
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> jdbc.execute("UPDATE document_import_task SET status='UNKNOWN' WHERE id=1"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            // 负面边界：无外键时数据库接受错误归属，后续业务事务必须拒绝这些写入。
            jdbc.execute("UPDATE document SET knowledge_base_id=999,active_version_id=999 WHERE id=1");
            jdbc.execute("UPDATE document_index_version SET document_id=999 WHERE id=1");
            assertThat(jdbc.queryForObject("SELECT document_id FROM document_index_version WHERE id=1", Long.class))
                    .isEqualTo(999L);
            for (String table : java.util.List.of(
                    "knowledge_base", "document", "document_index_version", "document_chunk", "document_import_task")) {
                assertThat(jdbc.queryForObject(
                                "SELECT format_type(atttypid,atttypmod) FROM pg_attribute WHERE attrelid=?::regclass AND attname='id'",
                                String.class,
                                table))
                        .isEqualTo("bigint");
            }
            assertThat(jdbc.queryForObject(
                            "SELECT format_type(atttypid,atttypmod) FROM pg_attribute WHERE attrelid='document_chunk'::regclass AND attname='embedding'",
                            String.class))
                    .isEqualTo("vector(1024)");
            com.lnjecit.knowledge.assistant.infrastructure.persistence.KnowledgeBaseMapper bases = context.getBean(
                    com.lnjecit.knowledge.assistant.infrastructure.persistence.KnowledgeBaseMapper.class);
            com.lnjecit.knowledge.assistant.infrastructure.persistence.KnowledgeBase base =
                    new com.lnjecit.knowledge.assistant.infrastructure.persistence.KnowledgeBase();
            base.setName("模型持久化");
            base.setCreatedAt(java.time.Instant.parse("2026-11-08T00:00:00Z"));
            assertThat(bases.insert(base)).isEqualTo(1);
            assertThat(base.getId()).isPositive();
            assertThat(bases.selectById(base.getId()).getCreatedAt()).isEqualTo(base.getCreatedAt());
            // 检查 V3 覆盖五表及每一个实际业务列，避免遗漏新增字段的注释。
            for (String table : java.util.List.of(
                    "knowledge_base", "document", "document_index_version", "document_chunk", "document_import_task")) {
                assertThat(jdbc.queryForObject("SELECT obj_description(?::regclass, 'pg_class')", String.class, table))
                        .isNotBlank();
                assertThat(jdbc.queryForList(
                                "SELECT col_description(attrelid,attnum) FROM pg_attribute WHERE attrelid=?::regclass AND attnum>0 AND NOT attisdropped",
                                String.class,
                                table))
                        .allSatisfy(comment -> assertThat(comment).isNotBlank());
            }
            com.lnjecit.knowledge.assistant.infrastructure.persistence.DocumentChunkMapper chunks = context.getBean(
                    com.lnjecit.knowledge.assistant.infrastructure.persistence.DocumentChunkMapper.class);
            com.lnjecit.knowledge.assistant.infrastructure.persistence.DocumentChunk chunk =
                    new com.lnjecit.knowledge.assistant.infrastructure.persistence.DocumentChunk();
            chunk.setKnowledgeBaseId(1L);
            chunk.setDocumentId(1L);
            chunk.setIndexVersionId(1L);
            chunk.setChunkOrder(10);
            chunk.setContent("向量映射");
            chunk.setSourceStart(0);
            chunk.setSourceEnd(4);
            chunk.setEmbedding(vector);
            assertThat(chunks.insert(chunk)).isEqualTo(1);
            assertThat(chunk.getId()).isPositive();
            assertThat(chunks.selectById(chunk.getId()).getEmbedding()).isEqualTo(vector);
            TransactionTemplate entityTransaction =
                    new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            entityTransaction.executeWithoutResult(status -> {
                base.setName("回滚更新");
                bases.updateById(base);
                status.setRollbackOnly();
            });
            assertThat(bases.selectById(base.getId()).getName()).isEqualTo("模型持久化");
            ProbeMapper mapper = context.getBean(ProbeMapper.class);
            Probe probe = new Probe();
            probe.content = "中文😀 ' OR 1=1 --";
            // ID 由应用端 MyBatis-Plus Snowflake 生成，数据库没有默认序列。
            assertThat(mapper.insert(probe)).isEqualTo(1);
            assertThat(probe.id).isPositive();
            assertThat(mapper.selectById(probe.id).content).isEqualTo(probe.content);
            probe.content = "更新";
            assertThat(mapper.updateById(probe)).isEqualTo(1);
            assertThat(mapper.selectById(probe.id).content).isEqualTo("更新");
            TransactionTemplate transaction =
                    new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            Probe rolledBack = new Probe();
            rolledBack.content = "回滚";
            transaction.executeWithoutResult(status -> {
                mapper.insert(rolledBack);
                status.setRollbackOnly();
            });
            assertThat(mapper.selectById(rolledBack.id)).isNull();
            assertThat(mapper.deleteById(probe.id)).isEqualTo(1);
            assertThat(mapper.selectById(probe.id)).isNull();
        }
        // 再次启动使用同一数据库，不重复迁移。
        try (ConfigurableApplicationContext restarted = application.run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--assistant.model.enabled=false",
                "--assistant.rerank.enabled=false")) {
            assertThat(restarted.getBean(org.flywaydb.core.Flyway.class).migrate().migrationsExecuted)
                    .isZero();
        }
    }
}
