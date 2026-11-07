package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

@org.testcontainers.junit.jupiter.Testcontainers
class DatabaseIT {
    @org.testcontainers.junit.jupiter.Container
    final org.testcontainers.postgresql.PostgreSQLContainer postgres =
            new org.testcontainers.postgresql.PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse(
                                    "pgvector/pgvector:0.8.0-pg17@sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87")
                            .asCompatibleSubstituteFor("postgres"))
                    .withImagePullPolicy(image -> false);

    private ConfigurableApplicationContext start(String... args) {
        var app = new SpringApplication(KnowledgeAssistantApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        // 所有启动路径强制使用容器连接，并关闭模型。
        java.util.List<String> options = new java.util.ArrayList<>(java.util.List.of(args));
        options.add("--spring.datasource.url=" + postgres.getJdbcUrl());
        options.add("--spring.datasource.username=" + postgres.getUsername());
        options.add("--spring.datasource.password=" + postgres.getPassword());
        options.add("--assistant.model.enabled=false");
        options.add("--assistant.rerank.enabled=false");
        return app.run(options.toArray(String[]::new));
    }

    @Test
    void migrationAndVector() throws Exception {
        // 每个方法独立容器，先证明空库，再验证真实自动迁移。
        try (Connection connection = java.sql.DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement sql = connection.createStatement();
                ResultSet rows = sql.executeQuery("SELECT to_regclass('public.flyway_schema_history')")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isNull();
        }
        // 临时表只在当前 JDBC 会话内存在。
        try (ConfigurableApplicationContext context = start()) {
            Flyway flyway = context.getBean(Flyway.class);
            DataSource dataSource = context.getBean(DataSource.class);
            var jdbc = new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname='vector'", String.class))
                    .isEqualTo("0.8.0");
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM flyway_schema_history WHERE version='1' AND success", Integer.class))
                    .isEqualTo(1);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            try (Connection connection = dataSource.getConnection();
                    Statement sql = connection.createStatement()) {
                sql.execute("CREATE TEMP TABLE vector_probe(id INT PRIMARY KEY, embedding vector(3))");
                sql.execute("INSERT INTO vector_probe VALUES (1,'[1,0,0]'),(2,'[0,1,0]')");
                try (ResultSet rows = sql.executeQuery(
                        "SELECT id, embedding <=> '[1,0,0]'::vector AS distance FROM vector_probe ORDER BY distance")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(1);
                    assertThat(rows.getDouble(2)).isCloseTo(0, within(1e-9));
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(2);
                    assertThat(rows.getDouble(2)).isCloseTo(1, within(1e-9));
                    assertThat(rows.next()).isFalse();
                }
            }
            System.out.println("M0-05: vector distances=0,1; repeat migrations=0");
        }
    }

    @Test
    void failedMigrationBlocksStartup() throws Exception {
        String schema = "m005_" + UUID.randomUUID().toString().replace("-", "");
        Path directory = Files.createTempDirectory("ka-m005-failure-");
        Path migration = directory.resolve("V1__deliberate_failure.sql");
        Files.writeString(migration, "SELECT deliberate_m005_missing_function();");
        // 随机 Schema 隔离失败迁移，只清理本测试自己创建的 Schema。
        try (ConfigurableApplicationContext context = start()) {
            var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            try {
                assertThatThrownBy(() -> start(
                                "--spring.flyway.default-schema=" + schema,
                                "--spring.flyway.schemas=" + schema,
                                "--spring.flyway.locations=filesystem:"
                                        + directory.toString().replace('\\', '/')))
                        .hasStackTraceContaining("deliberate_m005_missing_function");
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                Files.deleteIfExists(migration);
                Files.deleteIfExists(directory);
            }
        }
    }
}
