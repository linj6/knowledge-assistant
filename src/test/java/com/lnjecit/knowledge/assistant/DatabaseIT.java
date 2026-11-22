package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.lnjecit.knowledge.assistant.conversation.entity.Conversation;
import com.lnjecit.knowledge.assistant.conversation.entity.Message;
import com.lnjecit.knowledge.assistant.conversation.entity.MessageRole;
import com.lnjecit.knowledge.assistant.conversation.entity.MessageStatus;
import com.lnjecit.knowledge.assistant.conversation.repository.ConversationRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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

    @Test
    void upgradesV7AndPersistsConversationsAndMessages() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .target("7")
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.update(
                "INSERT INTO app_user (id, username, password_hash, status) VALUES (1, 'fixture', 'hash', 'ACTIVE')");
        jdbc.update("INSERT INTO knowledge_base (id, name, owner_user_id) VALUES (2, 'existing', 1)");
        jdbc.update(
                """
                INSERT INTO document (id, knowledge_base_id, filename, content_hash, object_key)
                VALUES (3, 2, 'existing.md', 'hash', 'object')
                """);
        jdbc.update("INSERT INTO document_index_version (id, document_id, chunking_config) VALUES (4, 3, '{}')");
        jdbc.update(
                """
                INSERT INTO document_chunk
                    (id, knowledge_base_id, document_id, index_version_id, chunk_order, content, source_start, source_end)
                VALUES (5, 2, 3, 4, 0, 'existing', 0, 8)
                """);
        jdbc.update("INSERT INTO document_import_task (id, document_id, index_version_id) VALUES (6, 3, 4)");
        List<String> before = existingRows(jdbc);
        try (ConfigurableApplicationContext context = start()) {
            assertThat(existingRows(jdbc)).isEqualTo(before);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM flyway_schema_history WHERE version = '8' AND success",
                            Integer.class))
                    .isEqualTo(1);
            ConversationRepository repository = context.getBean(ConversationRepository.class);
            Conversation conversation = new Conversation();
            conversation.setOwnerUserId(1L);
            conversation.setKnowledgeBaseId(2L);
            repository.insertConversation(conversation);
            Long id = conversation.getId();
            assertThat(id).isPositive();
            Conversation stored = repository.findOwnedConversation(id, 1L);
            assertThat(stored.getOwnerUserId()).isEqualTo(1L);
            assertThat(stored.getKnowledgeBaseId()).isEqualTo(2L);
            assertThat(stored.getTitle()).isEqualTo("新会话");
            assertThat(stored.getCreatedAt()).isNotNull().isEqualTo(stored.getUpdatedAt());
            jdbc.update(
                    "INSERT INTO app_user (id, username, password_hash, status) VALUES (7, 'other', 'hash', 'ACTIVE')");
            jdbc.update(
                    "INSERT INTO knowledge_base (id, name, owner_user_id) VALUES (8, 'other base', 1), (9, 'private', 7)");
            jdbc.update(
                    """
                    INSERT INTO chat_conversation (id, owner_user_id, knowledge_base_id, updated_at)
                    VALUES (20, 1, 2, '2026-01-01T00:00:00Z'),
                           (21, 1, 2, '2026-01-01T00:00:00Z'),
                           (22, 1, 8, '2026-01-01T00:00:00Z'),
                           (23, 7, 9, '2026-01-01T00:00:00Z')
                    """);
            assertThat(repository.findOwnedConversation(id, 7L)).isNull();
            assertThat(repository.listOwnedConversations(1L, 2L))
                    .extracting(Conversation::getId)
                    .containsExactly(id, 21L, 20L);
            assertThat(repository.listOwnedConversations(1L, 8L))
                    .extracting(Conversation::getId)
                    .containsExactly(22L);
            assertThat(repository.listOwnedConversations(1L, 9L)).isEmpty();

            UUID turn = UUID.randomUUID();
            Message user = message(id, turn, MessageRole.USER, null);
            user.setContent("问题");
            repository.insertMessage(user);
            Message assistant = message(id, turn, MessageRole.ASSISTANT, MessageStatus.PROCESSING);
            assistant.setCitations("[{\"chunkId\": 5}]");
            repository.insertMessage(assistant);
            // 固定为同一时刻，验证跨页时仍按主键顺序读取。
            jdbc.update("UPDATE chat_message SET created_at = CURRENT_TIMESTAMP WHERE conversation_id = ?", id);
            assertThat(repository.listMessages(id, 1, 0))
                    .extracting(Message::getId)
                    .containsExactly(user.getId());
            assertThat(repository.listMessages(id, 1, 1))
                    .extracting(Message::getId)
                    .containsExactly(assistant.getId());
            assertThat(repository.listMessages(id, 1, 2)).isEmpty();
            List<Message> messages = repository.listMessages(id, 10, 0);
            assertThat(messages).extracting(Message::getId).containsExactly(user.getId(), assistant.getId());
            assertThat(assistant.getId()).isPositive();
            Message storedUser = messages.getFirst();
            assertThat(storedUser.getConversationId()).isEqualTo(id);
            assertThat(storedUser.getTurnId()).isEqualTo(turn);
            assertThat(storedUser.getRole()).isEqualTo(MessageRole.USER);
            assertThat(storedUser.getContent()).isEqualTo("问题");
            assertThat(storedUser.getStatus()).isNull();
            assertThat(storedUser.getCitations()).isEqualTo("[]");
            Message storedAssistant = messages.getLast();
            assertThat(storedAssistant.getTurnId()).isEqualTo(turn);
            assertThat(storedAssistant.getRole()).isEqualTo(MessageRole.ASSISTANT);
            assertThat(storedAssistant.getStatus()).isEqualTo(MessageStatus.PROCESSING);
            assertThat(storedAssistant.getCitations()).isEqualTo("[{\"chunkId\": 5}]");
            assertThat(storedAssistant.getCreatedAt()).isNotNull();
            assertThat(storedAssistant.getCompletedAt()).isNull();
            assertThatThrownBy(() -> repository.insertMessage(message(id, turn, MessageRole.USER, null)))
                    .hasStackTraceContaining("uq_chat_message_turn_role");

            UUID nextTurn = UUID.randomUUID();
            assertThatThrownBy(() -> repository.insertMessage(
                            message(id, nextTurn, MessageRole.ASSISTANT, MessageStatus.CANCEL_REQUESTED)))
                    .hasStackTraceContaining("uq_chat_message_active_assistant");
            jdbc.update("UPDATE chat_message SET status = 'CANCEL_REQUESTED' WHERE id = ?", assistant.getId());
            assertThatThrownBy(() -> repository.insertMessage(
                            message(id, nextTurn, MessageRole.ASSISTANT, MessageStatus.PROCESSING)))
                    .hasStackTraceContaining("uq_chat_message_active_assistant");
            jdbc.update("UPDATE chat_message SET status = 'CANCELLED' WHERE id = ?", assistant.getId());

            Instant completedAt = Instant.parse("2026-11-22T10:00:00Z");
            Message failed = message(id, UUID.randomUUID(), MessageRole.ASSISTANT, MessageStatus.FAILED);
            failed.setErrorCode("TEST_FAILURE");
            failed.setCompletedAt(completedAt);
            repository.insertMessage(failed);
            repository.insertMessage(message(id, nextTurn, MessageRole.ASSISTANT, MessageStatus.PROCESSING));
            assertThat(repository.listMessages(id, 10, 0))
                    .filteredOn(row -> row.getId().equals(failed.getId()))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.getErrorCode()).isEqualTo("TEST_FAILURE");
                        assertThat(row.getCompletedAt()).isEqualTo(completedAt);
                    });

            assertThatThrownBy(() -> jdbc.update(
                            "INSERT INTO chat_conversation (id, owner_user_id, knowledge_base_id) VALUES (10, 99, 2)"))
                    .hasStackTraceContaining("chat_conversation_owner_user_id_fkey");
            assertThatThrownBy(() -> jdbc.update(
                            "INSERT INTO chat_conversation (id, owner_user_id, knowledge_base_id) VALUES (10, 1, 99)"))
                    .hasStackTraceContaining("chat_conversation_knowledge_base_id_fkey");
            assertThatThrownBy(() -> repository.insertMessage(message(99L, nextTurn, MessageRole.USER, null)))
                    .hasStackTraceContaining("chat_message_conversation_id_fkey");
            assertThatThrownBy(() -> repository.insertMessage(
                            message(id, UUID.randomUUID(), MessageRole.USER, MessageStatus.FAILED)))
                    .hasStackTraceContaining("chk_chat_message_role_status");
            assertThatThrownBy(
                            () -> repository.insertMessage(message(id, UUID.randomUUID(), MessageRole.ASSISTANT, null)))
                    .hasStackTraceContaining("chk_chat_message_role_status");
        }
    }

    private List<String> existingRows(JdbcTemplate jdbc) {
        return jdbc.queryForList(
                """
                SELECT to_jsonb(r)::text FROM app_user r
                UNION ALL SELECT to_jsonb(r)::text FROM knowledge_base r
                UNION ALL SELECT to_jsonb(r)::text FROM document r
                UNION ALL SELECT to_jsonb(r)::text FROM document_index_version r
                UNION ALL SELECT to_jsonb(r)::text FROM document_chunk r
                UNION ALL SELECT to_jsonb(r)::text FROM document_import_task r
                ORDER BY 1
                """,
                String.class);
    }

    private Message message(Long conversationId, UUID turnId, MessageRole role, MessageStatus status) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setTurnId(turnId);
        message.setRole(role);
        message.setStatus(status);
        return message;
    }
}
