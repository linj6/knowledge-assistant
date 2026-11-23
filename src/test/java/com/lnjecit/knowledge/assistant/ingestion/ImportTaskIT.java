package com.lnjecit.knowledge.assistant.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lnjecit.knowledge.assistant.KnowledgeAssistantApplication;
import com.lnjecit.knowledge.assistant.ingestion.task.ClaimedTask;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskFailure;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskScheduler;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class ImportTaskIT {
    @Container
    final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
                            "pgvector/pgvector:0.8.0-pg17@sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87")
                    .asCompatibleSubstituteFor("postgres"))
            .withImagePullPolicy(image -> false);

    private ConfigurableApplicationContext start() {
        SpringApplication app = new SpringApplication(KnowledgeAssistantApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        return app.run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--assistant.model.enabled=false",
                "--assistant.elasticsearch.enabled=false");
    }

    @Test
    void realPostgresClaimsAndRecovery() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            TaskService service = context.getBean(TaskService.class);
            assertThat(context.getBeansOfType(TaskScheduler.class)).isEmpty();
            assertThat(service.claimNext("a")).isEmpty();
            ImportTaskFixture.insertPendingTask(jdbc, 1, "hash" + 1);
            assertThat(jdbc.queryForObject("SELECT status FROM document_import_task WHERE id=1", String.class))
                    .isEqualTo("PENDING");
            try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
                CountDownLatch gate = new CountDownLatch(1);
                Callable<Optional<ClaimedTask>> call = () -> {
                    gate.await();
                    return service.claimNext(Thread.currentThread().getName());
                };
                Future<Optional<ClaimedTask>> a = executor.submit(call), b = executor.submit(call);
                gate.countDown();
                List<ClaimedTask> claims = new ArrayList<>();
                a.get(10, TimeUnit.SECONDS).ifPresent(claims::add);
                b.get(10, TimeUnit.SECONDS).ifPresent(claims::add);
                assertThat(claims).hasSize(1);
                ClaimedTask claim = claims.getFirst();
                assertThat(claim.claimEpoch()).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT status FROM document_import_task WHERE id=1", String.class))
                        .isEqualTo("RUNNING");
                assertThat(jdbc.queryForObject("SELECT lease_owner FROM document_import_task WHERE id=1", String.class))
                        .isEqualTo(claim.leaseOwner());
                assertThat(jdbc.queryForObject(
                                "SELECT extract(epoch FROM lease_until-updated_at)::int FROM document_import_task WHERE id=1",
                                Integer.class))
                        .isEqualTo(900);
                assertThat(jdbc.queryForObject(
                                "SELECT attempt_count FROM document_import_task WHERE id=1", Integer.class))
                        .isEqualTo(1);
                assertThatThrownBy(() -> service.fail(new ClaimedTask(1L, 1L, 1L, "old", 1)))
                        .isInstanceOf(TaskFailure.class);
                assertThatThrownBy(() -> service.fail(new ClaimedTask(1L, 1L, 1L, claim.leaseOwner(), 0)))
                        .isInstanceOf(TaskFailure.class);
                // 领取返回后，另一连接可以立即加锁，证明领取事务已经提交。
                try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                        java.sql.Statement statement = connection.createStatement()) {
                    connection.setAutoCommit(false);
                    statement
                            .executeQuery("SELECT id FROM document_import_task WHERE id=1 FOR UPDATE NOWAIT")
                            .close();
                    connection.rollback();
                }
                service.fail(claim);
                assertThat(service.claimNext("a")).isEmpty();
                jdbc.update("UPDATE document_import_task SET status='SUCCEEDED' WHERE id=1");
                assertThat(service.claimNext("a")).isEmpty();
                assertThatThrownBy(() -> service.claimById(999L, "a")).hasMessage("NOT_FOUND");
                assertThatThrownBy(() -> service.claimById(1L, "a")).hasMessage("NOT_CLAIMABLE");
                ImportTaskFixture.insertPendingTask(jdbc, 2, "hash" + 2);
                ImportTaskFixture.insertPendingTask(jdbc, 3, "hash" + 3);
                TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
                tx.executeWithoutResult(status -> {
                    jdbc.queryForList("SELECT * FROM document_import_task WHERE id=2 FOR UPDATE");
                    try {
                        ClaimedTask other = executor.submit(
                                        () -> service.claimNext("skip").orElseThrow())
                                .get(5, TimeUnit.SECONDS);
                        assertThat(other.taskId()).isEqualTo(3L);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                });
                jdbc.update("UPDATE document_import_task SET attempt_count=2,claim_epoch=8 WHERE id=2");
                ClaimedTask incremented = service.claimNext("next").orElseThrow();
                assertThat(incremented.claimEpoch()).isEqualTo(9);
                assertThat(jdbc.queryForObject(
                                "SELECT attempt_count FROM document_import_task WHERE id=2", Integer.class))
                        .isEqualTo(3);
                jdbc.update("UPDATE document_import_task SET lease_until=now()-interval '1 second' WHERE id=2");
                assertThatThrownBy(() -> service.fail(incremented)).hasMessage("AUTHORITY_EXPIRED");
                assertThat(service.claimNext("a")).isEmpty();
                assertThatThrownBy(() -> service.claimById(2L, "a")).hasMessage("ALREADY_CLAIMED");
                for (long i = 10; i < 30; i++) ImportTaskFixture.insertPendingTask(jdbc, i, "hash" + i);
                List<Future<Optional<ClaimedTask>>> futures = new ArrayList<>();
                for (int i = 0; i < 20; i++) futures.add(executor.submit(() -> service.claimNext("multi")));
                Set<Long> ids = new HashSet<>();
                for (Future<Optional<ClaimedTask>> future : futures)
                    assertThat(ids.add(future.get(10, TimeUnit.SECONDS)
                                    .orElseThrow()
                                    .taskId()))
                            .isTrue();
                assertThat(ids).hasSize(20);
                ImportTaskFixture.insertPendingTask(jdbc, 35, "hash" + 35);
                tx.executeWithoutResult(status -> {
                    jdbc.queryForList("SELECT * FROM document WHERE id=35 FOR UPDATE");
                    jdbc.queryForList("SELECT * FROM document_index_version WHERE id=35 FOR UPDATE");
                    try {
                        ClaimedTask concurrent = executor.submit(
                                        () -> service.claimNext("lock-order").orElseThrow())
                                .get(5, TimeUnit.SECONDS);
                        assertThat(concurrent.taskId()).isEqualTo(35L);
                        jdbc.queryForList("SELECT * FROM document_import_task WHERE id=35 FOR UPDATE");
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                });
                ImportTaskFixture.insertPendingTask(jdbc, 40, "hash" + 40);
                jdbc.update("UPDATE document_index_version SET document_id=999 WHERE id=40");
                assertThatThrownBy(() -> service.claimNext("bad")).hasMessage("OWNERSHIP_INVALID");
                assertThat(jdbc.queryForObject("SELECT claim_epoch FROM document_import_task WHERE id=40", Long.class))
                        .isZero();
                jdbc.update("UPDATE document_index_version SET document_id=40 WHERE id=40");
                jdbc.update("UPDATE document_import_task SET document_id=999 WHERE id=40");
                assertThatThrownBy(() -> service.claimNext("missing-document")).hasMessage("OWNERSHIP_INVALID");
                jdbc.update("UPDATE document_import_task SET document_id=40,index_version_id=999 WHERE id=40");
                assertThatThrownBy(() -> service.claimNext("missing-version")).hasMessage("OWNERSHIP_INVALID");
                jdbc.update("UPDATE document_import_task SET index_version_id=40 WHERE id=40");
                jdbc.execute(
                        "CREATE FUNCTION reject_claim() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END $$");
                jdbc.execute(
                        "CREATE TRIGGER reject_claim BEFORE UPDATE ON document_import_task FOR EACH ROW EXECUTE FUNCTION reject_claim()");
                Map<String, Object> before = jdbc.queryForMap("SELECT * FROM document_import_task WHERE id=40");
                assertThatThrownBy(() -> service.claimNext("rollback")).isInstanceOf(RuntimeException.class);
                assertThat(jdbc.queryForMap("SELECT * FROM document_import_task WHERE id=40"))
                        .isEqualTo(before);
                jdbc.execute("DROP TRIGGER reject_claim ON document_import_task");
                // 零行条件更新同样必须失败，不能留下部分领取状态。
                jdbc.execute(
                        "CREATE FUNCTION skip_claim() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$");
                jdbc.execute(
                        "CREATE TRIGGER skip_claim BEFORE UPDATE ON document_import_task FOR EACH ROW EXECUTE FUNCTION skip_claim()");
                assertThatThrownBy(() -> service.claimNext("conflict")).hasMessage("TRANSITION_CONFLICT");
                assertThat(jdbc.queryForMap("SELECT * FROM document_import_task WHERE id=40"))
                        .isEqualTo(before);
                jdbc.execute("DROP TRIGGER skip_claim ON document_import_task");
            }
        }
        // 新建应用上下文再次读取同一个 PostgreSQL，不依赖上一个进程的内存队列。
        try (ConfigurableApplicationContext restarted = start()) {
            assertThat(restarted
                            .getBean(TaskService.class)
                            .claimNext("restart")
                            .orElseThrow()
                            .taskId())
                    .isEqualTo(40L);
        }
    }
}
