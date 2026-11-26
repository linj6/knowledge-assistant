package com.lnjecit.knowledge.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lnjecit.knowledge.assistant.KnowledgeAssistantApplication;
import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import com.lnjecit.knowledge.assistant.conversation.service.ConversationService;
import com.lnjecit.knowledge.assistant.conversation.service.TurnService;
import com.lnjecit.knowledge.assistant.infrastructure.BusinessHttpTestSecurity;
import com.lnjecit.knowledge.assistant.ingestion.upload.DocumentImportService;
import com.lnjecit.knowledge.assistant.knowledge.entity.KnowledgeBase;
import com.lnjecit.knowledge.assistant.knowledge.service.KnowledgeBaseService;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
    void persistenceOrderingAndTransactionBoundary() throws Exception {
        SpringApplication application =
                new SpringApplication(KnowledgeAssistantApplication.class, BusinessHttpTestSecurity.class);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        try (ConfigurableApplicationContext context = application.run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--assistant.model.enabled=false",
                "--server.port=0",
                "--assistant.rerank.enabled=false")) {
            KnowledgeBaseService service = context.getBean(KnowledgeBaseService.class);
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            jdbc.update(
                    "INSERT INTO app_user(id,username,password_hash,status) VALUES (42,'fixture-owner','test-only','ACTIVE')");
            assertThat(service.list(42L)).isEmpty();
            KnowledgeBase first = service.create(42L, " Java ", " 说明 ");
            assertThat(first.getId()).isPositive();
            assertThat(first.getCreatedAt()).isNotNull();
            java.time.Instant savedAt = jdbc.queryForObject(
                            "SELECT created_at FROM knowledge_base WHERE id=?",
                            java.time.OffsetDateTime.class,
                            first.getId())
                    .toInstant();
            // PostgreSQL 以微秒保存，JDBC 可能对应用的纳秒部分舍入。
            assertThat(java.time.Duration.between(first.getCreatedAt(), savedAt).abs())
                    .isLessThan(java.time.Duration.ofNanos(1000));
            assertThat(service.requireOwned(first.getId(), 42L).getName()).isEqualTo("Java");
            assertThat(jdbc.queryForObject(
                            "SELECT description FROM knowledge_base WHERE id=?", String.class, first.getId()))
                    .isEqualTo("说明");
            KnowledgeBase second = service.create(42L, "Java", null);
            assertThat(second.getId()).isNotEqualTo(first.getId());
            jdbc.update("UPDATE knowledge_base SET updated_at='2026-01-01T00:00:00Z'");
            assertThat(service.list(42L))
                    .extracting(KnowledgeBase::getId)
                    .containsExactlyElementsOf(java.util.stream.Stream.of(first.getId(), second.getId())
                            .sorted(java.util.Comparator.reverseOrder())
                            .toList());
            jdbc.update("UPDATE knowledge_base SET updated_at='2025-01-01T00:00:00Z' WHERE id=?", first.getId());
            assertThat(service.list(42L))
                    .extracting(KnowledgeBase::getId)
                    .containsExactly(second.getId(), first.getId());
            assertThatThrownBy(() -> service.get(1L)).isInstanceOf(BusinessException.class);
            // 临时测试约束模拟真实数据库拒绝插入，不改变迁移或制造多表事务。
            jdbc.execute("ALTER TABLE knowledge_base ADD CONSTRAINT test_reject CHECK (name <> '拒绝')");
            assertThatThrownBy(() -> service.create(42L, "拒绝", null))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_base", Integer.class))
                    .isEqualTo(2);
            assertThat(service.create(42L, "恢复", null).getId()).isPositive();
            // 使用真实嵌入式服务器验证 YAML servlet 前缀、MVC Long 绑定和自动配置的 Jackson。
            int port = ((org.springframework.boot.web.server.context.WebServerApplicationContext) context)
                    .getWebServer()
                    .getPort();
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            String base = "http://localhost:" + port;
            java.net.http.HttpResponse<String> created = client.send(
                    BusinessHttpTestSecurity.request(context, java.net.URI.create(base + "/api/v1/knowledge-bases"))
                            .header("Content-Type", "application/json")
                            .header("X-Request-Id", "real-mvc")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"name\":\"HTTP\"}"))
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(created.statusCode()).isEqualTo(201);
            tools.jackson.databind.json.JsonMapper mapper =
                    context.getBean(tools.jackson.databind.json.JsonMapper.class);
            tools.jackson.databind.JsonNode body = mapper.readTree(created.body());
            assertThat(body.get("code").isInt()).isTrue();
            assertThat(body.get("code").intValue()).isZero();
            assertThat(body.get("requestId").asString())
                    .isEqualTo(created.headers().firstValue("X-Request-Id").orElseThrow())
                    .isEqualTo("real-mvc");
            assertThat(body.get("data").get("id").isString()).isTrue();
            String location = created.headers().firstValue("Location").orElseThrow();
            assertThat(location).startsWith("/api/v1/knowledge-bases/");
            assertThat(client.send(
                                    BusinessHttpTestSecurity.request(context, java.net.URI.create(base + location))
                                            .GET()
                                            .build(),
                                    java.net.http.HttpResponse.BodyHandlers.ofString())
                            .statusCode())
                    .isEqualTo(200);
            assertThat(client.send(
                                    BusinessHttpTestSecurity.request(
                                                    context, java.net.URI.create(base + "/v1/knowledge-bases"))
                                            .GET()
                                            .build(),
                                    java.net.http.HttpResponse.BodyHandlers.ofString())
                            .statusCode())
                    .isEqualTo(404);
            verifyManagement(service, jdbc, first, second);
            ConversationService conversations = context.getBean(ConversationService.class);
            TurnService turns = context.getBean(TurnService.class);
            Long deletedId = first.getId();
            UUID turnId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            assertDeletedBase(() -> conversations.list(42L, deletedId, null));
            assertDeletedBase(() -> conversations.requireOwned(42L, 40L));
            assertDeletedBase(() -> conversations.listMessages(42L, 40L, 1, 10));
            assertDeletedBase(() -> turns.get(42L, 40L, turnId));
            assertDeletedBase(() -> turns.cancel(42L, 40L, turnId));
            assertThatThrownBy(
                            () -> context.getBean(DocumentImportService.class).getOwned(20L, 42L))
                    .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getErrorCode())
                            .isEqualTo(CommonErrorCode.NOT_FOUND));
            verifyDeleteWins(context, service, jdbc);
            assertThat(mapper.writeValueAsString(java.util.Map.of("count", 9007199254740993L)))
                    .contains("\"9007199254740993\"");
        }
    }

    private void verifyManagement(
            KnowledgeBaseService service, JdbcTemplate jdbc, KnowledgeBase first, KnowledgeBase second) {
        Long id = first.getId();
        assertThat(first.getUpdatedAt()).isEqualTo(first.getCreatedAt());
        jdbc.update(
                """
                INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key,deleted_at)
                VALUES (10,?,'active','a','key',NULL),(11,?,'deleted','b','key',now())
                """,
                id,
                id);
        KnowledgeBase updated = service.update(id, 42L, "  Java%_!  ", null);
        assertThat(updated.getName()).isEqualTo("Java%_!");
        assertThat(updated.getDescription()).isNull();
        assertThat(updated.getCreatedAt()).isEqualTo(first.getCreatedAt());
        assertThat(updated.getUpdatedAt()).isAfter(first.getUpdatedAt());
        assertThat(updated.getDocumentCount()).isEqualTo(1);
        assertThat(service.list(42L, "  jAVA%_!  "))
                .extracting(KnowledgeBase::getId)
                .containsExactly(id);
        assertThat(service.list(42L, "_Java")).isEmpty();
        assertThat(service.list(42L, "  ")).hasSize(4);
        assertThat(service.list(42L, "java")).extracting(KnowledgeBase::getId).containsExactly(id, second.getId());
        assertThat(service.list(42L, "%"))
                .extracting(KnowledgeBase::getDocumentCount)
                .containsExactly(1);
        assertThatThrownBy(() -> service.update(id, 99L, "越权", null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.delete(id, 99L)).isInstanceOf(BusinessException.class);

        jdbc.update("INSERT INTO document_import_task(id,document_id,index_version_id) VALUES (20,10,30)");
        assertBusy(service, id);
        jdbc.update("UPDATE document_import_task SET status='RUNNING' WHERE id=20");
        assertBusy(service, id);
        jdbc.update(
                "UPDATE document_import_task SET status='FAILED', next_retry_at=now()+interval '1 hour' WHERE id=20");
        assertBusy(service, id);
        jdbc.update("UPDATE document_import_task SET next_retry_at=NULL WHERE id=20");
        jdbc.update("INSERT INTO chat_conversation(id,owner_user_id,knowledge_base_id) VALUES (40,42,?)", id);
        jdbc.update(
                """
                INSERT INTO chat_message(id,conversation_id,turn_id,role,status)
                VALUES (50,40,'00000000-0000-0000-0000-000000000001','ASSISTANT','PROCESSING')
                """);
        assertBusy(service, id);
        jdbc.update("UPDATE chat_message SET status='CANCEL_REQUESTED' WHERE id=50");
        assertBusy(service, id);
        jdbc.update("UPDATE chat_message SET status='CANCELLED' WHERE id=50");
        service.delete(id, 42L);
        assertThat(service.list(42L, "%")).isEmpty();
        assertThatThrownBy(() -> service.get(id)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.requireOwned(id, 42L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.update(id, 42L, "不可修改", null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.delete(id, 42L)).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject(
                        "SELECT deleted_at IS NOT NULL AND updated_at = ? FROM knowledge_base WHERE id=?",
                        Boolean.class,
                        updated.getUpdatedAt().atOffset(java.time.ZoneOffset.UTC),
                        id))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM document WHERE id=10", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT knowledge_base_id FROM chat_conversation WHERE id=40", Long.class))
                .isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT status FROM chat_message WHERE id=50", String.class))
                .isEqualTo("CANCELLED");
    }

    private void verifyDeleteWins(
            ConfigurableApplicationContext context, KnowledgeBaseService service, JdbcTemplate jdbc) throws Exception {
        Long id = service.create(42L, "并发测试", null).getId();
        jdbc.update("INSERT INTO chat_conversation(id,owner_user_id,knowledge_base_id) VALUES (41,42,?)", id);
        TurnService turns = context.getBean(TurnService.class);
        DocumentImportService imports = context.getBean(DocumentImportService.class);
        ConversationService conversations = context.getBean(ConversationService.class);
        jdbc.update(
                """
                INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key)
                VALUES (12,?,'race.txt','559aead08264d5795d3909718cdd05abd49572e84fe55590eef31a88a08fdffd','key')
                """,
                id);
        jdbc.update(
                "INSERT INTO document_import_task(id,document_id,index_version_id,status) VALUES (21,12,31,'FAILED')");
        TransactionTemplate transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        AtomicReference<Future<?>> turn = new AtomicReference<>();
        AtomicReference<Future<?>> upload = new AtomicReference<>();
        AtomicReference<Future<?>> creation = new AtomicReference<>();
        try (ExecutorService workers = Executors.newFixedThreadPool(3)) {
            transaction.executeWithoutResult(status -> {
                service.delete(id, 42L);
                turn.set(workers.submit(() -> assertThatThrownBy(
                                () -> turns.submit(42L, 41L, UUID.randomUUID(), "并发提交"))
                        .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getErrorCode())
                                .isEqualTo(CommonErrorCode.KNOWLEDGE_BASE_NOT_FOUND))));
                creation.set(workers.submit(() -> assertDeletedBase(() -> conversations.create(42L, id))));
                upload.set(workers.submit(() -> assertDeletedBase(() -> imports.receive(
                        id, new MockMultipartFile("file", "race.txt", "text/plain", new byte[] {65})))));
                // 用 PostgreSQL 的阻塞信息验证三个写入口都在等待删除事务提交。
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                Integer waiting;
                do {
                    jdbc.execute("SELECT pg_stat_clear_snapshot()");
                    waiting = jdbc.queryForObject(
                            """
                            SELECT COUNT(*) FROM pg_stat_activity
                            WHERE datname = current_database() AND cardinality(pg_blocking_pids(pid)) > 0
                            """,
                            Integer.class);
                } while (waiting < 3 && System.nanoTime() < deadline);
                assertThat(waiting).isEqualTo(3);
            });
            turn.get().get(5, TimeUnit.SECONDS);
            upload.get().get(5, TimeUnit.SECONDS);
            creation.get().get(5, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE conversation_id=41", Integer.class))
                .isZero();
    }

    private void assertDeletedBase(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getErrorCode())
                        .isEqualTo(CommonErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
    }

    private void assertBusy(KnowledgeBaseService service, Long id) {
        assertThatThrownBy(() -> service.delete(id, 42L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getMessage())
                        .contains("未结束"));
    }
}
