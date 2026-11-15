package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class AuthMigrationTests {
    @Container
    final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
                            "pgvector/pgvector:0.8.0-pg17@sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87")
                    .asCompatibleSubstituteFor("postgres"))
            .withImagePullPolicy(image -> false);

    @Test
    void upgradePreservesHistoryAndEnforcesUserConstraints() {
        DriverManagerDataSource source =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        assertThat(Flyway.configure().dataSource(source).target("5").load().migrate().migrationsExecuted)
                .isEqualTo(5);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("INSERT INTO knowledge_base(id,name) VALUES (101,'existing')");
        jdbc.execute(
                "INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key) VALUES (201,101,'a.md','hash','originals/a')");
        java.util.List<java.util.Map<String, Object>> documents = jdbc.queryForList("SELECT * FROM document");
        Flyway flyway = Flyway.configure().dataSource(source).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM document")).isEqualTo(documents);
        assertThat(jdbc.queryForObject("SELECT owner_user_id FROM knowledge_base WHERE id=101", Long.class))
                .isNull();
        jdbc.execute("INSERT INTO app_user(id,username,password_hash,status) VALUES (1,'user','test-only','ACTIVE')");
        assertThatThrownBy(
                        () -> jdbc.execute(
                                "INSERT INTO app_user(id,username,password_hash,status) VALUES (2,'user','test-only','ACTIVE')"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () -> jdbc.execute(
                                "INSERT INTO app_user(id,username,password_hash,status) VALUES (3,'other','test-only','INVALID')"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void generatedUserIdAndOwnerMappingPersist() {
        org.springframework.boot.SpringApplication application =
                new org.springframework.boot.SpringApplication(KnowledgeAssistantApplication.class);
        application.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        try (org.springframework.context.ConfigurableApplicationContext context = application.run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--assistant.model.enabled=false",
                "--assistant.rerank.enabled=false")) {
            com.lnjecit.knowledge.assistant.auth.mapper.UserMapper users =
                    context.getBean(com.lnjecit.knowledge.assistant.auth.mapper.UserMapper.class);
            com.lnjecit.knowledge.assistant.auth.entity.User user =
                    new com.lnjecit.knowledge.assistant.auth.entity.User();
            user.setUsername("mapping-user");
            user.setPasswordHash("test-only-hash");
            user.setStatus(com.lnjecit.knowledge.assistant.auth.entity.UserStatus.ACTIVE);
            user.setCreatedAt(java.time.Instant.now());
            assertThat(user.getId()).isNull();
            assertThat(users.insert(user)).isEqualTo(1);
            assertThat(user.getId()).isPositive();
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
            assertThat(jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, user.getUsername()))
                    .isEqualTo(user.getId());
            com.lnjecit.knowledge.assistant.auth.entity.User saved = users.selectById(user.getId());
            assertThat(saved.getUsername()).isEqualTo(user.getUsername());
            assertThat(saved.getPasswordHash()).isEqualTo(user.getPasswordHash());
            assertThat(saved.getStatus()).isEqualTo(user.getStatus());
            com.lnjecit.knowledge.assistant.knowledge.mapper.KnowledgeBaseMapper bases =
                    context.getBean(com.lnjecit.knowledge.assistant.knowledge.mapper.KnowledgeBaseMapper.class);
            com.lnjecit.knowledge.assistant.knowledge.entity.KnowledgeBase owned =
                    new com.lnjecit.knowledge.assistant.knowledge.entity.KnowledgeBase();
            owned.setName("owned");
            owned.setCreatedAt(java.time.Instant.now());
            owned.setOwnerUserId(user.getId());
            bases.insert(owned);
            assertThat(bases.selectById(owned.getId()).getOwnerUserId()).isEqualTo(user.getId());
            assertThat(jdbc.queryForObject(
                            "SELECT owner_user_id FROM knowledge_base WHERE id=?", Long.class, owned.getId()))
                    .isEqualTo(user.getId());
            jdbc.execute("INSERT INTO knowledge_base(id,name) VALUES (101,'historical')");
            assertThat(bases.selectById(101L).getOwnerUserId()).isNull();
        }
    }

    @Test
    void documentedBindingRejectsInvalidBatchesWithoutChangingData() throws Exception {
        DriverManagerDataSource source =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute(
                "INSERT INTO app_user(id,username,password_hash,status) VALUES (1,'active','test','ACTIVE'),(2,'disabled','test','DISABLED'),(3,'other','test','ACTIVE')");
        jdbc.execute("INSERT INTO knowledge_base(id,name,owner_user_id) VALUES (101,'free',NULL),(102,'owned',3)");
        String document =
                java.nio.file.Files.readString(java.nio.file.Path.of("docs/operations/auth-01-owner-binding.md"));
        String transaction = document.split("```sql")[2].split("```")[0];
        String setup = transaction.substring(transaction.indexOf("LOCK TABLE"), transaction.indexOf("-- 人工填入"));
        String operation = transaction.substring(
                transaction.indexOf("DO $$"), transaction.indexOf("END $$;") + "END $$;".length());
        java.util.List<java.util.Map<String, Object>> before =
                jdbc.queryForList("SELECT * FROM knowledge_base ORDER BY id");
        // 每批均含有效记录，保证无效项不能造成部分提交。
        long[][] cases = {{999, 101}, {2, 101}, {1, 101, 999}, {1, 101, 102}};
        for (long[] scenario : cases) {
            try (java.sql.Connection connection = source.getConnection()) {
                connection.setAutoCommit(false);
                try (java.sql.Statement statement = connection.createStatement()) {
                    statement.execute(setup);
                    statement.execute("INSERT INTO binding_target VALUES (" + scenario[0] + ")");
                    for (int i = 1; i < scenario.length; i++)
                        statement.execute("INSERT INTO binding_kb VALUES (" + scenario[i] + ")");
                    assertThatThrownBy(() -> statement.execute(operation)).isInstanceOf(java.sql.SQLException.class);
                    // PostgreSQL 中失败事务即使调用 commit 也不能提交先前写入。
                    connection.commit();
                }
            }
            assertThat(jdbc.queryForList("SELECT * FROM knowledge_base ORDER BY id"))
                    .isEqualTo(before);
        }
    }
}
