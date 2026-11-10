package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lnjecit.knowledge.assistant.infrastructure.config.WorkerProperties;
import com.lnjecit.knowledge.assistant.ingestion.task.ClaimedTask;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskExecutionException;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskFailure;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskService;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskWorker;
import java.time.Duration;
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
class TaskRecoveryIT {
    @Container
    final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
                            "pgvector/pgvector:0.8.0-pg17@sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87")
                    .asCompatibleSubstituteFor("postgres"))
            .withImagePullPolicy(i -> false);

    ConfigurableApplicationContext start() {
        SpringApplication app = new SpringApplication(KnowledgeAssistantApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        return app.run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--assistant.model.enabled=false",
                "--assistant.elasticsearch.enabled=false");
    }

    void seed(JdbcTemplate j, long id) {
        j.update(
                "INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key) VALUES(?,1,'a.txt',?,'key')",
                id,
                "h" + id);
        j.update("INSERT INTO document_index_version(id,document_id,chunking_config) VALUES(?,?, '{}')", id, id);
        j.update("INSERT INTO document_import_task(id,document_id,index_version_id) VALUES(?,?,?)", id, id, id);
    }

    void due(JdbcTemplate j, long id) {
        j.update(
                "UPDATE document_import_task SET next_retry_at=statement_timestamp()-interval '1 second' WHERE id=?",
                id);
    }

    @Test
    void serialSchedulerWithExpiredRealClaim() throws Exception {
        try (ConfigurableApplicationContext c = start()) {
            JdbcTemplate jdbc = c.getBean(JdbcTemplate.class);
            TaskService tasks = c.getBean(TaskService.class);
            seed(jdbc, 90);
            java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<ClaimedTask> original =
                    new java.util.concurrent.atomic.AtomicReference<>();
            com.lnjecit.knowledge.assistant.ingestion.task.TaskProcessor processor = (claim, budget) -> {
                original.set(claim);
                entered.countDown();
                try {
                    if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("test timeout");
                } catch (InterruptedException failure) {
                    throw new IllegalStateException(failure);
                }
            };
            org.springframework.beans.factory.support.StaticListableBeanFactory factory =
                    new org.springframework.beans.factory.support.StaticListableBeanFactory();
            factory.addBean("processor", processor);
            WorkerProperties properties = new WorkerProperties();
            properties.setBatchSize(1);
            com.lnjecit.knowledge.assistant.ingestion.task.TaskScheduler scheduler =
                    new com.lnjecit.knowledge.assistant.ingestion.task.TaskScheduler(
                            tasks,
                            factory.getBeanProvider(com.lnjecit.knowledge.assistant.ingestion.task.TaskProcessor.class),
                            properties);
            try (java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                java.util.concurrent.Future<?> first = executor.submit(scheduler::scan);
                try {
                    assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                            .isTrue();
                    // 测试推进数据库租约时间，不实际等待十五分钟。
                    jdbc.update(
                            "UPDATE document_import_task SET lease_until=statement_timestamp()-interval '1 second' WHERE id=90");
                    assertThatThrownBy(() -> tasks.fail(original.get(), TaskExecutionException.Code.MODEL_TIMEOUT))
                            .hasMessage("AUTHORITY_EXPIRED");
                    java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
                    java.util.concurrent.Future<?> second = executor.submit(() -> {
                        started.countDown();
                        scheduler.scan();
                    });
                    assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
                            .isTrue();
                    assertThatThrownBy(() -> second.get(100, java.util.concurrent.TimeUnit.MILLISECONDS))
                            .isInstanceOf(java.util.concurrent.TimeoutException.class);
                    assertThat(jdbc.queryForObject("SELECT status FROM document_import_task WHERE id=90", String.class))
                            .isEqualTo("RUNNING");
                    release.countDown();
                    first.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    second.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    assertThat(jdbc.queryForObject(
                                    "SELECT last_error_code FROM document_import_task WHERE id=90", String.class))
                            .isEqualTo("WORKER_INTERRUPTED");
                    due(jdbc, 90);
                    ClaimedTask next = tasks.claimNext("new-owner").orElseThrow();
                    assertThat(next.claimEpoch()).isEqualTo(original.get().claimEpoch() + 1);
                    assertThatThrownBy(() -> tasks.fail(original.get(), TaskExecutionException.Code.MODEL_TIMEOUT))
                            .hasMessage("AUTHORITY_EXPIRED");
                    assertThat(jdbc.queryForObject(
                                    "SELECT lease_owner FROM document_import_task WHERE id=90", String.class))
                            .isEqualTo("new-owner");
                } finally {
                    release.countDown();
                }
            }
        }
    }

    @Test
    void retryAndTimeout() {
        try (ConfigurableApplicationContext c = start()) {
            JdbcTemplate j = c.getBean(JdbcTemplate.class);
            TaskService tasks = c.getBean(TaskService.class);
            assertThat(j.queryForObject("SHOW server_version", String.class)).startsWith("17.6");
            assertThat(j.queryForObject("SELECT extversion FROM pg_extension WHERE extname='vector'", String.class))
                    .isEqualTo("0.8.0");
            seed(j, 1);
            long[] lower = {4, 12, 36};
            long[] upper = {6, 18, 54};
            for (int attempt = 1; attempt <= 4; attempt++) {
                ClaimedTask claim =
                        (attempt == 1 ? tasks.claimById(1L, "worker") : tasks.claimNext("worker")).orElseThrow();
                assertThat(claim.claimEpoch()).isEqualTo(attempt);
                assertThat(j.queryForObject("SELECT attempt_count FROM document_import_task WHERE id=1", Integer.class))
                        .isEqualTo(attempt);
                tasks.fail(claim, TaskExecutionException.Code.MODEL_TIMEOUT);
                assertThat(j.queryForObject(
                                "SELECT lease_owner IS NULL AND lease_until IS NULL FROM document_import_task WHERE id=1",
                                Boolean.class))
                        .isTrue();
                if (attempt < 4) {
                    Double delay = j.queryForObject(
                            "SELECT extract(epoch FROM next_retry_at-updated_at) FROM document_import_task WHERE id=1",
                            Double.class);
                    assertThat(delay).isBetween((double) lower[attempt - 1], (double) upper[attempt - 1]);
                    assertThat(tasks.claimNext("other")).isEmpty();
                    assertThatThrownBy(() -> tasks.claimById(1L, "other")).isInstanceOf(TaskFailure.class);
                    due(j, 1);
                } else {
                    assertThat(j.queryForObject(
                                    "SELECT next_retry_at IS NULL AND status='FAILED' FROM document_import_task WHERE id=1",
                                    Boolean.class))
                            .isTrue();
                    assertThat(tasks.claimNext("other")).isEmpty();
                }
            }
            seed(j, 2);
            ClaimedTask permanent = tasks.claimById(2L, "worker").orElseThrow();
            tasks.fail(permanent, TaskExecutionException.Code.INVALID_CONTENT);
            assertThat(j.queryForObject(
                            "SELECT next_retry_at IS NULL FROM document_import_task WHERE id=2", Boolean.class))
                    .isTrue();
            assertThat(tasks.claimNext("other")).isEmpty();
            seed(j, 3);
            ClaimedTask timed = tasks.claimById(3L, "worker").orElseThrow();
            WorkerProperties properties = new WorkerProperties();
            properties.setMaxTaskDuration(Duration.ofMillis(1));
            new TaskWorker(
                            tasks,
                            (task, context) -> {
                                while (!context.remaining().isZero()) Thread.onSpinWait();
                                context.checkTimeout();
                            },
                            properties)
                    .execute(timed);
            assertThat(j.queryForObject("SELECT last_error_code FROM document_import_task WHERE id=3", String.class))
                    .isEqualTo("TASK_TIMEOUT");
            assertThat(j.queryForObject(
                            "SELECT status='FAILED' AND next_retry_at IS NOT NULL FROM document_import_task WHERE id=3",
                            Boolean.class))
                    .isTrue();
            seed(j, 4);
            ClaimedTask claim = tasks.claimById(4L, "owner").orElseThrow();
            assertThatThrownBy(() -> tasks.fail(
                            new ClaimedTask(4L, 4L, 4L, "wrong", claim.claimEpoch()),
                            TaskExecutionException.Code.MODEL_TIMEOUT))
                    .hasMessage("AUTHORITY_EXPIRED");
            assertThatThrownBy(() -> tasks.fail(
                            new ClaimedTask(4L, 4L, 4L, "owner", 0), TaskExecutionException.Code.MODEL_TIMEOUT))
                    .hasMessage("AUTHORITY_EXPIRED");
            assertThatThrownBy(() -> tasks.fail(
                            new ClaimedTask(4L, 999L, 4L, "owner", 1), TaskExecutionException.Code.MODEL_TIMEOUT))
                    .hasMessage("AUTHORITY_EXPIRED");
            assertThatThrownBy(() -> tasks.fail(
                            new ClaimedTask(4L, 4L, 999L, "owner", 1), TaskExecutionException.Code.MODEL_TIMEOUT))
                    .hasMessage("AUTHORITY_EXPIRED");
            assertThat(j.queryForObject("SELECT status FROM document_import_task WHERE id=4", String.class))
                    .isEqualTo("RUNNING");
            seed(j, 5);
            ClaimedTask unknown = tasks.claimById(5L, "worker").orElseThrow();
            new TaskWorker(tasks, (task, context) -> {
                        throw new IllegalStateException("supplier secret document");
                    })
                    .execute(unknown);
            assertThat(j.queryForObject(
                            "SELECT next_retry_at IS NULL AND last_error_code='EXECUTION_FAILED' AND last_error_message='任务执行失败' FROM document_import_task WHERE id=5",
                            Boolean.class))
                    .isTrue();
            j.update(
                    "UPDATE document_import_task SET lease_until=statement_timestamp()-interval '1 second' WHERE id=4");
            assertThatThrownBy(() -> tasks.fail(claim, TaskExecutionException.Code.MODEL_TIMEOUT))
                    .hasMessage("AUTHORITY_EXPIRED");
        }
    }

    @Test
    void recoveryAcrossContexts() {
        try (ConfigurableApplicationContext c = start()) {
            JdbcTemplate j = c.getBean(JdbcTemplate.class);
            TaskService tasks = c.getBean(TaskService.class);
            for (long id = 10; id <= 13; id++) {
                seed(j, id);
                tasks.claimById(id, id == 12 ? "current" : "old");
            }
            j.update(
                    "UPDATE document_import_task SET lease_until=statement_timestamp()-interval '1 second' WHERE id IN(10,12,13)");
            j.update("UPDATE document_import_task SET attempt_count=4 WHERE id=13");
            assertThat(tasks.recoverNext("current")).isTrue();
            assertThat(tasks.recoverNext("current")).isTrue();
            assertThat(tasks.recoverNext("current")).isFalse();
            assertThat(j.queryForObject(
                            "SELECT status='FAILED' AND lease_owner IS NULL AND lease_until IS NULL AND next_retry_at IS NOT NULL AND last_error_code='WORKER_INTERRUPTED' FROM document_import_task WHERE id=10",
                            Boolean.class))
                    .isTrue();
            assertThat(j.queryForObject(
                            "SELECT status='FAILED' AND next_retry_at IS NULL FROM document_import_task WHERE id=13",
                            Boolean.class))
                    .isTrue();
            assertThat(j.queryForObject(
                            "SELECT count(*) FROM document_import_task WHERE status='RUNNING'", Integer.class))
                    .isEqualTo(2);
            due(j, 10);
            assertThat(tasks.claimById(10L, "current").orElseThrow().claimEpoch())
                    .isEqualTo(2);
            j.update(
                    "UPDATE document_import_task SET lease_until=statement_timestamp()-interval '1 second' WHERE id=11");
        }
        try (ConfigurableApplicationContext c = start()) {
            TaskService tasks = c.getBean(TaskService.class);
            assertThat(tasks.recoverNext("restart")).isTrue();
            assertThat(tasks.recoverNext("restart")).isTrue();
            JdbcTemplate j = c.getBean(JdbcTemplate.class);
            assertThat(j.queryForObject("SELECT status FROM document_import_task WHERE id=11", String.class))
                    .isEqualTo("FAILED");
            j.update("UPDATE document_index_version SET status='VERIFIED',expected_chunk_count=1 WHERE id=11");
            due(j, 11);
            assertThat(tasks.claimById(11L, "restart").orElseThrow().claimEpoch())
                    .isEqualTo(2);
        }
    }
}
