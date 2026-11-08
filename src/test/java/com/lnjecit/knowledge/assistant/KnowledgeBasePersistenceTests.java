package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.KnowledgeBase;
import com.lnjecit.knowledge.assistant.knowledge.KnowledgeBaseService;
import javax.sql.DataSource;
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
class KnowledgeBasePersistenceTests {
    @Container
    final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
                            "pgvector/pgvector:0.8.0-pg17@sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87")
                    .asCompatibleSubstituteFor("postgres"))
            .withImagePullPolicy(image -> false);

    @Test
    void persistenceOrderingAndTransactionBoundary() {
        SpringApplication application = new SpringApplication(KnowledgeAssistantApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = application.run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--assistant.model.enabled=false",
                "--assistant.rerank.enabled=false")) {
            KnowledgeBaseService service = context.getBean(KnowledgeBaseService.class);
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            assertThat(service.list()).isEmpty();
            KnowledgeBase first = service.create(" Java ", " 说明 ");
            assertThat(first.getId()).isPositive();
            assertThat(first.getCreatedAt()).isNotNull();
            assertThat(service.get(first.getId()).getName()).isEqualTo("Java");
            assertThat(jdbc.queryForObject(
                            "SELECT description FROM knowledge_base WHERE id=?", String.class, first.getId()))
                    .isEqualTo("说明");
            KnowledgeBase second = service.create("Java", null);
            assertThat(second.getId()).isNotEqualTo(first.getId());
            jdbc.update("UPDATE knowledge_base SET created_at='2026-01-01T00:00:00Z'");
            assertThat(service.list())
                    .extracting(KnowledgeBase::getId)
                    .containsExactlyElementsOf(java.util.stream.Stream.of(first.getId(), second.getId())
                            .sorted()
                            .toList());
            jdbc.update("UPDATE knowledge_base SET created_at='2025-01-01T00:00:00Z' WHERE id=?", second.getId());
            assertThat(service.list()).extracting(KnowledgeBase::getId).containsExactly(second.getId(), first.getId());
            assertThatThrownBy(() -> service.get(1L)).isInstanceOf(BusinessException.class);
            // 临时测试约束模拟真实数据库拒绝插入，不改变迁移或制造多表事务。
            jdbc.execute("ALTER TABLE knowledge_base ADD CONSTRAINT test_reject CHECK (name <> '拒绝')");
            assertThatThrownBy(() -> service.create("拒绝", null))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_base", Integer.class))
                    .isEqualTo(2);
            assertThat(service.create("恢复", null).getId()).isPositive();
        }
    }
}
