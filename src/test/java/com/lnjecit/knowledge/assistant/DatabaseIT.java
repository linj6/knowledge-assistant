package com.lnjecit.knowledge.assistant;

import java.nio.file.Files;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.flywaydb.core.Flyway;
import static org.assertj.core.api.Assertions.*;

class DatabaseIT {
    private org.springframework.context.ConfigurableApplicationContext start(String... args) {
        var app = new SpringApplication(KnowledgeAssistantApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        return app.run(args);
    }

    @Test void migrationAndVector() throws Exception {
        // 使用应用真实自动配置及同一数据源，临时表只在当前 JDBC 会话内存在。
        try (var context = start()) {
            var flyway = context.getBean(Flyway.class);
            var dataSource = context.getBean(DataSource.class);
            var jdbc = new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname='vector'", String.class)).isEqualTo("0.8.0");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='1' AND success", Integer.class)).isEqualTo(1);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
                sql.execute("CREATE TEMP TABLE vector_probe(id INT PRIMARY KEY, embedding vector(3))");
                sql.execute("INSERT INTO vector_probe VALUES (1,'[1,0,0]'),(2,'[0,1,0]')");
                try (var rows = sql.executeQuery("SELECT id, embedding <=> '[1,0,0]'::vector AS distance FROM vector_probe ORDER BY distance")) {
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

    @Test void failedMigrationBlocksStartup() throws Exception {
        String schema = "m005_" + UUID.randomUUID().toString().replace("-", "");
        var directory = Files.createTempDirectory("ka-m005-failure-");
        var migration = directory.resolve("V1__deliberate_failure.sql");
        Files.writeString(migration, "SELECT deliberate_m005_missing_function();");
        // 随机 Schema 隔离失败迁移，只清理本测试自己创建的 Schema。
        try (var context = start()) {
            var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            try {
                assertThatThrownBy(() -> start("--spring.flyway.default-schema=" + schema,
                        "--spring.flyway.schemas=" + schema,
                        "--spring.flyway.locations=filesystem:" + directory.toString().replace('\\', '/')))
                        .hasStackTraceContaining("deliberate_m005_missing_function");
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                Files.deleteIfExists(migration);
                Files.deleteIfExists(directory);
            }
        }
    }
}
