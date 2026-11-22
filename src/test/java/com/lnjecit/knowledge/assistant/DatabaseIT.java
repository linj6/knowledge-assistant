package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.conversation.dto.TurnResponse;
import com.lnjecit.knowledge.assistant.conversation.entity.Conversation;
import com.lnjecit.knowledge.assistant.conversation.entity.Message;
import com.lnjecit.knowledge.assistant.conversation.entity.MessageRole;
import com.lnjecit.knowledge.assistant.conversation.entity.MessageStatus;
import com.lnjecit.knowledge.assistant.conversation.repository.ConversationRepository;
import com.lnjecit.knowledge.assistant.conversation.service.ConversationService;
import com.lnjecit.knowledge.assistant.conversation.service.TurnExecutor;
import com.lnjecit.knowledge.assistant.conversation.service.TurnService;
import com.lnjecit.knowledge.assistant.rag.RagService;
import com.lnjecit.knowledge.assistant.rag.model.RagAnswer;
import com.lnjecit.knowledge.assistant.rag.model.RagCitation;
import com.lnjecit.knowledge.assistant.rag.model.RagError;
import com.lnjecit.knowledge.assistant.rag.model.RagStatus;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStrategy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

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
    void submitsAndExecutesConversationTurns() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            jdbc.update(
                    """
                    INSERT INTO app_user (id, username, password_hash, status)
                    VALUES (1, 'owner', 'hash', 'ACTIVE'), (2, 'other', 'hash', 'ACTIVE')
                    """);
            jdbc.update("INSERT INTO knowledge_base (id, name, owner_user_id) VALUES (3, 'test', 1)");
            ConversationService conversations = context.getBean(ConversationService.class);
            ConversationRepository repository = context.getBean(ConversationRepository.class);
            RagService rag = mock(RagService.class);
            TurnExecutor executor = new TurnExecutor(rag, repository, context.getBean(JsonMapper.class));
            TurnService turns = new TurnService(
                    conversations, repository, executor, context.getBean(PlatformTransactionManager.class));
            Long conversationId = conversations.create(1L, 3L).getId();
            Long otherConversation = conversations.create(1L, 3L).getId();
            String question = "  原始问题  ";
            UUID turnId = UUID.randomUUID();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            RetrievalStrategy strategy = RetrievalStrategy.HYBRID_RRF;
            when(rag.answer(3L, question, strategy, null)).thenAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                        .isFalse();
                assertThat(repository.findTurn(conversationId, turnId)).hasSize(2);
                entered.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                return new RagAnswer(
                        RagStatus.ANSWERED,
                        "回答",
                        List.of(new RagCitation("E1", 9L, 10L, 11L, 0, 2)),
                        strategy,
                        strategy,
                        false,
                        null,
                        null);
            });
            when(rag.answer(3L, "无证据", strategy, null))
                    .thenReturn(new RagAnswer(
                            RagStatus.INSUFFICIENT_EVIDENCE, null, List.of(), strategy, strategy, false, null, null));
            when(rag.answer(3L, "模型失败", strategy, null))
                    .thenReturn(new RagAnswer(
                            RagStatus.FAILED,
                            null,
                            List.of(),
                            strategy,
                            strategy,
                            false,
                            RagError.MODEL_TIMEOUT,
                            null));
            when(rag.answer(3L, "执行异常", strategy, null)).thenThrow(new IllegalStateException("private payload"));

            try {
                assertThat(turns.submit(1L, conversationId, turnId, question).status())
                        .isEqualTo(MessageStatus.PROCESSING);
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                TurnResponse pending = turns.get(1L, conversationId, turnId);
                assertThat(pending.question()).isEqualTo(question);
                assertThat(pending.userMessageId()).isNotEqualTo(pending.assistantMessageId());
                assertThat(pending.createdAt()).isNotNull();
                assertThat(pending.completedAt()).isNull();
                assertThat(pending.citations()).isEqualTo("[]");
                Conversation stored = conversations.requireOwned(1L, conversationId);
                assertThat(stored.getTitle()).isEqualTo("原始问题");
                assertThat(stored.getUpdatedAt()).isEqualTo(pending.createdAt());

                assertThat(turns.submit(1L, conversationId, turnId, question).status())
                        .isEqualTo(MessageStatus.PROCESSING);
                assertThatThrownBy(() -> turns.submit(1L, conversationId, turnId, "不同问题"))
                        .isInstanceOf(BusinessException.class)
                        .hasMessage("Turn ID 已用于其他问题");
                UUID blockedTurn = UUID.randomUUID();
                assertThatThrownBy(() -> turns.submit(1L, conversationId, blockedTurn, question))
                        .isInstanceOf(BusinessException.class)
                        .hasMessage("会话已有正在执行的 Turn");
                assertThat(repository.findTurn(conversationId, blockedTurn)).isEmpty();
                assertThatThrownBy(() -> turns.get(2L, conversationId, turnId))
                        .isInstanceOf(BusinessException.class)
                        .hasMessage("会话不存在");
                assertThatThrownBy(() -> turns.submit(2L, conversationId, turnId, question))
                        .isInstanceOf(BusinessException.class)
                        .hasMessage("会话不存在");

                UUID parallelTurn = UUID.randomUUID();
                turns.submit(1L, otherConversation, parallelTurn, "无证据");
                assertThat(awaitTurn(turns, otherConversation, parallelTurn).status())
                        .isEqualTo(MessageStatus.INSUFFICIENT_EVIDENCE);
                release.countDown();
                TurnResponse answered = awaitTurn(turns, conversationId, turnId);
                assertThat(answered.status()).isEqualTo(MessageStatus.ANSWERED);
                assertThat(answered.answer()).isEqualTo("回答");
                assertThat(answered.citations()).contains("E1");
                assertThat(answered.completedAt()).isAfterOrEqualTo(answered.createdAt());
                assertThat(turns.submit(1L, conversationId, turnId, question).status())
                        .isEqualTo(MessageStatus.ANSWERED);
                verify(rag, times(1)).answer(3L, question, strategy, null);

                UUID failedTurn = UUID.randomUUID();
                turns.submit(1L, conversationId, failedTurn, "模型失败");
                TurnResponse failed = awaitTurn(turns, conversationId, failedTurn);
                assertThat(failed.status()).isEqualTo(MessageStatus.FAILED);
                assertThat(failed.errorCode()).isEqualTo("MODEL_TIMEOUT");

                UUID exceptionalTurn = UUID.randomUUID();
                turns.submit(1L, conversationId, exceptionalTurn, "执行异常");
                assertThat(awaitTurn(turns, conversationId, exceptionalTurn).errorCode())
                        .isEqualTo("TURN_EXECUTION_FAILED");

                assertConcurrentSubmissions(turns, repository, rag, conversationId, strategy);

                executor.shutdown();
                UUID rejectedTurn = UUID.randomUUID();
                assertThat(turns.submit(1L, conversationId, rejectedTurn, "拒绝").status())
                        .isEqualTo(MessageStatus.FAILED);
                TurnResponse rejected = turns.get(1L, conversationId, rejectedTurn);
                assertThat(rejected.errorCode()).isEqualTo("TURN_EXECUTOR_REJECTED");
                assertThat(rejected.completedAt()).isNotNull();
                verify(rag, times(0)).answer(3L, "拒绝", strategy, null);
            } finally {
                release.countDown();
                executor.shutdown();
            }
        }
    }

    private static void assertConcurrentSubmissions(
            TurnService turns,
            ConversationRepository repository,
            RagService rag,
            Long conversationId,
            RetrievalStrategy strategy)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String question = "并发问题";
        when(rag.answer(3L, question, strategy, null)).thenAnswer(call -> {
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return new RagAnswer(
                    RagStatus.INSUFFICIENT_EVIDENCE, null, List.of(), strategy, strategy, false, null, null);
        });
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try (java.util.concurrent.ExecutorService callers = Executors.newFixedThreadPool(2)) {
            Future<Boolean> left =
                    callers.submit(() -> raceSubmit(turns, conversationId, first, question, ready, start));
            Future<Boolean> right =
                    callers.submit(() -> raceSubmit(turns, conversationId, second, question, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            boolean firstWon = left.get(5, TimeUnit.SECONDS);
            assertThat(right.get(5, TimeUnit.SECONDS)).isNotEqualTo(firstWon);
            assertThat(repository.findTurn(conversationId, firstWon ? second : first))
                    .isEmpty();
            release.countDown();
            assertThat(awaitTurn(turns, conversationId, firstWon ? first : second)
                            .status())
                    .isEqualTo(MessageStatus.INSUFFICIENT_EVIDENCE);
            verify(rag, times(1)).answer(3L, question, strategy, null);
        } finally {
            release.countDown();
        }
    }

    private static boolean raceSubmit(
            TurnService turns,
            Long conversationId,
            UUID turnId,
            String question,
            CountDownLatch ready,
            CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            turns.submit(1L, conversationId, turnId, question);
            return true;
        } catch (BusinessException rejected) {
            assertThat(rejected).hasMessage("会话已有正在执行的 Turn");
            return false;
        }
    }

    @Test
    void cancelsQueuedAndRunningTurnsWithoutOverwritingTerminalStatus() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            conversationFixtures(context);
            ConversationService conversations = context.getBean(ConversationService.class);
            ConversationRepository repository = context.getBean(ConversationRepository.class);
            RagService rag = mock(RagService.class);
            TurnExecutor executor = new TurnExecutor(rag, repository, context.getBean(JsonMapper.class));
            TurnService turns = new TurnService(
                    conversations, repository, executor, context.getBean(PlatformTransactionManager.class));
            RetrievalStrategy strategy = RetrievalStrategy.HYBRID_RRF;
            CountDownLatch entered = new CountDownLatch(4);
            CountDownLatch release = new CountDownLatch(1);
            RagAnswer answer = new RagAnswer(
                    RagStatus.ANSWERED,
                    "回答",
                    List.of(new RagCitation("E1", 9L, 10L, 11L, 0, 2)),
                    strategy,
                    strategy,
                    false,
                    null,
                    null);
            when(rag.answer(3L, "运行中", strategy, null)).thenAnswer(call -> {
                entered.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                return answer;
            });
            when(rag.answer(3L, "下一轮", strategy, null)).thenReturn(answer);
            Long running = conversations.create(1L, 3L).getId();
            Long queued = conversations.create(1L, 3L).getId();
            UUID runningId = UUID.randomUUID();
            UUID queuedId = UUID.randomUUID();
            UUID nextId = UUID.randomUUID();
            try {
                turns.submit(1L, running, runningId, "运行中");
                for (int i = 0; i < 3; i++) {
                    turns.submit(1L, conversations.create(1L, 3L).getId(), UUID.randomUUID(), "运行中");
                }
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                turns.submit(1L, queued, queuedId, "排队中");
                assertThatThrownBy(() -> turns.cancel(2L, queued, queuedId)).isInstanceOf(BusinessException.class);
                assertThat(turns.cancel(1L, queued, queuedId).status()).isEqualTo(MessageStatus.CANCELLED);
                assertThat(turns.cancel(1L, queued, queuedId).status()).isEqualTo(MessageStatus.CANCELLED);
                assertThat(turns.cancel(1L, running, runningId).status()).isEqualTo(MessageStatus.CANCEL_REQUESTED);
                assertThat(turns.cancel(1L, running, runningId).status()).isEqualTo(MessageStatus.CANCEL_REQUESTED);
                assertThatThrownBy(() -> turns.submit(1L, running, nextId, "下一轮"))
                        .isInstanceOf(BusinessException.class);
                release.countDown();
                TurnResponse cancelled = awaitTurn(turns, running, runningId);
                assertThat(cancelled.status()).isEqualTo(MessageStatus.CANCELLED);
                assertThat(cancelled.answer()).isNull();
                turns.submit(1L, running, nextId, "下一轮");
                assertThat(awaitTurn(turns, running, nextId).status()).isEqualTo(MessageStatus.ANSWERED);
                assertThat(turns.cancel(1L, running, nextId).status()).isEqualTo(MessageStatus.ANSWERED);
                verify(rag, never()).answer(3L, "排队中", strategy, null);
            } finally {
                release.countDown();
                executor.shutdown();
            }
        }
    }

    @Test
    void cancelAndCompleteCompeteOnTheSameDatabaseRow() throws Exception {
        try (ConfigurableApplicationContext context = start();
                var contenders = Executors.newFixedThreadPool(2)) {
            conversationFixtures(context);
            ConversationService conversations = context.getBean(ConversationService.class);
            ConversationRepository repository = context.getBean(ConversationRepository.class);
            Long conversation = conversations.create(1L, 3L).getId();
            Message pending = message(conversation, UUID.randomUUID(), MessageRole.ASSISTANT, MessageStatus.PROCESSING);
            repository.insertMessage(pending);
            Message result = new Message();
            result.setStatus(MessageStatus.ANSWERED);
            result.setContent("回答");
            result.setCompletedAt(Instant.now());
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> cancel = contenders.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return repository.requestCancellation(pending.getId());
            });
            Future<Boolean> complete = contenders.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return repository.completeTurn(pending.getId(), result);
            });
            start.countDown();
            boolean cancelled = cancel.get(5, TimeUnit.SECONDS);
            assertThat(complete.get(5, TimeUnit.SECONDS)).isEqualTo(!cancelled);
            if (cancelled) {
                assertThat(repository.completeCancellation(pending.getId())).isTrue();
                assertThat(repository.completeTurn(pending.getId(), result)).isFalse();
            }
            assertThat(repository.turnStatus(pending.getId()))
                    .isEqualTo(cancelled ? MessageStatus.CANCELLED : MessageStatus.ANSWERED);
        }
    }

    @Test
    void recoversPreviousProcessTurnsOnStartup() {
        try (ConfigurableApplicationContext context = start()) {
            JdbcTemplate jdbc = conversationFixtures(context);
            jdbc.update(
                    "INSERT INTO chat_conversation (id, owner_user_id, knowledge_base_id) VALUES (4, 1, 3), (5, 1, 3)");
            jdbc.update(
                    """
                    INSERT INTO chat_message (id, conversation_id, turn_id, role, status)
                    VALUES (6, 4, gen_random_uuid(), 'ASSISTANT', 'PROCESSING'),
                           (7, 5, gen_random_uuid(), 'ASSISTANT', 'CANCEL_REQUESTED')
                    """);
        }
        try (ConfigurableApplicationContext context = start()) {
            ConversationRepository repository = context.getBean(ConversationRepository.class);
            assertThat(repository.turnStatus(6L)).isEqualTo(MessageStatus.FAILED);
            assertThat(repository.turnStatus(7L)).isEqualTo(MessageStatus.CANCELLED);
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            assertThat(jdbc.queryForObject("SELECT error_code FROM chat_message WHERE id = 6", String.class))
                    .isEqualTo("TURN_PROCESS_RESTARTED");
            assertThat(jdbc.queryForObject(
                            "SELECT COUNT(*) FROM chat_message WHERE completed_at IS NOT NULL", Integer.class))
                    .isEqualTo(2);
        }
    }

    private static JdbcTemplate conversationFixtures(ConfigurableApplicationContext context) {
        JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        jdbc.update(
                """
                INSERT INTO app_user (id, username, password_hash, status)
                VALUES (1, 'owner', 'hash', 'ACTIVE'), (2, 'other', 'hash', 'ACTIVE')
                """);
        jdbc.update("INSERT INTO knowledge_base (id, name, owner_user_id) VALUES (3, 'test', 1)");
        return jdbc;
    }

    private static TurnResponse awaitTurn(TurnService turns, Long conversationId, UUID turnId)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        TurnResponse turn;
        do {
            turn = turns.get(1L, conversationId, turnId);
            if (turn.status() != MessageStatus.PROCESSING && turn.status() != MessageStatus.CANCEL_REQUESTED) {
                return turn;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Turn still processing: " + turnId);
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
