package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.lnjecit.knowledge.assistant.document.entity.IndexVersion;
import com.lnjecit.knowledge.assistant.document.mapper.IndexVersionMapper;
import com.lnjecit.knowledge.assistant.ingestion.task.entity.ImportTask;
import com.lnjecit.knowledge.assistant.ingestion.task.mapper.ImportTaskMapper;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class DocumentLifecycleMigrationTests {
    @Container
    final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
                            "pgvector/pgvector:0.8.0-pg17@sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87")
                    .asCompatibleSubstituteFor("postgres"))
            .withImagePullPolicy(image -> false);

    @Test
    void upgradeV3PreservesDataAndPhysicalObjects() {
        DriverManagerDataSource source =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway v3 = Flyway.configure().dataSource(source).target("3").load();
        assertThat(v3.migrate().migrationsExecuted).isEqualTo(3);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        String snapshot = "{\"version\":1,\"strategy\":\"FIXED_CODE_POINT\",\"chunkSize\":800,\"chunkOverlap\":120}";
        jdbc.execute("INSERT INTO knowledge_base(id,name) VALUES (101,'existing')");
        jdbc.execute(
                "INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key) VALUES (201,101,'existing.md','hash','originals/existing')");
        jdbc.update("INSERT INTO index_version(id,document_id,chunking_config) VALUES (301,201,?)", snapshot);
        jdbc.execute("INSERT INTO import_task(id,document_id,index_version_id) VALUES (401,201,301)");
        Long versionOid = jdbc.queryForObject("SELECT 'index_version'::regclass::oid::bigint", Long.class);
        Long taskOid = jdbc.queryForObject("SELECT 'import_task'::regclass::oid::bigint", Long.class);
        List<Map<String, Object>> versionRows = jdbc.queryForList("SELECT * FROM index_version");
        List<Map<String, Object>> taskRows = jdbc.queryForList("SELECT * FROM import_task");
        List<Map<String, Object>> columns = columns(jdbc, versionOid, taskOid);
        List<Map<String, Object>> constraints = constraints(jdbc, versionOid, taskOid);
        List<Map<String, Object>> indexes = indexes(jdbc, versionOid, taskOid);
        // V3 实际目录中无额外序列、触发器或视图，避免只依据迁移文本作推断。
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pg_class WHERE relnamespace='public'::regnamespace AND relkind IN ('S','v','m')",
                        Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pg_trigger WHERE tgrelid IN (?,?) AND NOT tgisinternal",
                        Integer.class,
                        versionOid,
                        taskOid))
                .isZero();
        SpringApplication app = new SpringApplication(KnowledgeAssistantApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = app.run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword())) {
            assertThat(jdbc.queryForObject("SELECT 'document_index_version'::regclass::oid::bigint", Long.class))
                    .isEqualTo(versionOid);
            assertThat(jdbc.queryForObject("SELECT 'document_import_task'::regclass::oid::bigint", Long.class))
                    .isEqualTo(taskOid);
            assertThat(jdbc.queryForObject("SELECT to_regclass('index_version')::text", String.class))
                    .isNull();
            assertThat(jdbc.queryForObject("SELECT to_regclass('import_task')::text", String.class))
                    .isNull();
            assertThat(jdbc.queryForList("SELECT * FROM document_index_version"))
                    .isEqualTo(versionRows);
            assertThat(jdbc.queryForList("SELECT * FROM document_import_task")).isEqualTo(taskRows);
            assertThat(columns(jdbc, versionOid, taskOid)).isEqualTo(columns);
            assertThat(constraints(jdbc, versionOid, taskOid)).isEqualTo(constraints);
            assertThat(indexes(jdbc, versionOid, taskOid)).isEqualTo(indexes);
            assertThat(jdbc.queryForList(
                            "SELECT conname FROM pg_constraint WHERE conrelid IN (?,?) ORDER BY conname",
                            String.class,
                            versionOid,
                            taskOid))
                    .allSatisfy(name -> assertThat(name).startsWith("document_"));
            assertThat(jdbc.queryForList(
                            "SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid WHERE i.indrelid IN (?,?)",
                            String.class,
                            versionOid,
                            taskOid))
                    .allSatisfy(name -> assertThat(name).matches("(document_|idx_document_).*"));
            IndexVersion version = context.getBean(IndexVersionMapper.class).selectById(301L);
            ImportTask task = context.getBean(ImportTaskMapper.class).selectById(401L);
            assertThat(version.getDocumentId()).isEqualTo(201L);
            assertThat(version.getChunkingConfig()).isEqualTo(snapshot);
            assertThat(version.getEmbeddingModel()).isEqualTo("text-embedding-v4");
            assertThat(version.getEmbeddingDimension()).isEqualTo(1024);
            assertThat(task.getDocumentId()).isEqualTo(version.getDocumentId());
            assertThat(task.getIndexVersionId()).isEqualTo(version.getId());
            assertThat(context.getBean(Flyway.class).migrate().migrationsExecuted)
                    .isZero();
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM flyway_schema_history WHERE version='4' AND success", Integer.class))
                    .isEqualTo(1);
        }
    }

    private static List<Map<String, Object>> columns(JdbcTemplate jdbc, Long version, Long task) {
        return jdbc.queryForList(
                "SELECT a.attrelid::bigint,a.attnum,a.attname,a.atttypid,a.atttypmod,a.attnotnull,pg_get_expr(d.adbin,d.adrelid) AS default_value,col_description(a.attrelid,a.attnum) AS comment FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum WHERE a.attrelid IN (?,?) AND a.attnum>0 ORDER BY a.attrelid,a.attnum",
                version,
                task);
    }

    private static List<Map<String, Object>> constraints(JdbcTemplate jdbc, Long version, Long task) {
        return jdbc.queryForList(
                "SELECT oid::bigint,conrelid::bigint,contype,pg_get_constraintdef(oid) AS definition FROM pg_constraint WHERE conrelid IN (?,?) ORDER BY oid",
                version,
                task);
    }

    private static List<Map<String, Object>> indexes(JdbcTemplate jdbc, Long version, Long task) {
        return jdbc.queryForList(
                "SELECT indexrelid::bigint,indrelid::bigint,indisunique,indisprimary,indisvalid,indkey::text,indclass::text,indcollation::text,indoption::text,pg_get_expr(indexprs,indrelid) AS expressions,pg_get_expr(indpred,indrelid) AS predicate FROM pg_index WHERE indrelid IN (?,?) ORDER BY indexrelid",
                version,
                task);
    }
}
