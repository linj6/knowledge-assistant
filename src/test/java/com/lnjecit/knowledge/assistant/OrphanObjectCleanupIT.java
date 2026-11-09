package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lnjecit.knowledge.assistant.infrastructure.config.WorkerProperties;
import com.lnjecit.knowledge.assistant.infrastructure.storage.OriginalFileStore;
import com.lnjecit.knowledge.assistant.infrastructure.storage.OriginalObjectGuard;
import com.lnjecit.knowledge.assistant.ingestion.upload.OrphanObjectCleanupService;
import com.lnjecit.knowledge.assistant.ingestion.upload.OrphanObjectRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class OrphanObjectCleanupIT {
    @Container
    final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
                            "pgvector/pgvector:0.8.0-pg17@sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87")
                    .asCompatibleSubstituteFor("postgres"))
            .withImagePullPolicy(i -> false);

    @Container
    final GenericContainer<?> minio = new GenericContainer<>(
                    DockerImageName.parse("knowledge-assistant-minio:RELEASE.2025-09-07T16-13-09Z-local"))
            .withEnv("MINIO_ROOT_USER", "m112-user")
            .withEnv("MINIO_ROOT_PASSWORD", "m112-test-password")
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000))
            .withImagePullPolicy(i -> false);

    @Test
    void realObjectsAndDatabase() throws Exception {
        String endpoint = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
        String bucket = "m112-test";
        io.minio.MinioClient client = io.minio.MinioClient.builder()
                .endpoint(endpoint)
                .credentials("m112-user", "m112-test-password")
                .build();
        client.makeBucket(io.minio.MakeBucketArgs.builder().bucket(bucket).build());
        assertThat(minio.execInContainer("minio", "--version").getStdout()).contains("RELEASE.2025-09-07T16-13-09Z");
        SpringApplication app = new SpringApplication(KnowledgeAssistantApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext c = app.run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--assistant.minio.endpoint=" + endpoint,
                "--assistant.minio.access-key=m112-user",
                "--assistant.minio.secret-key=m112-test-password",
                "--assistant.minio.bucket=" + bucket)) {
            OriginalFileStore real = c.getBean(OriginalFileStore.class);
            OrphanObjectRepository mapper = c.getBean(OrphanObjectRepository.class);
            WorkerProperties properties = c.getBean(WorkerProperties.class);
            OriginalObjectGuard guard = c.getBean(OriginalObjectGuard.class);
            JdbcTemplate j = c.getBean(JdbcTemplate.class);
            for (String key :
                    java.util.List.of("originals/a-fail", "originals/b-orphan", "originals/c-used", "other/keep"))
                real.put(key, new byte[] {65});
            j.update(
                    "INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key) VALUES(1,1,'a.txt','hash','originals/c-used')");
            OrphanObjectCleanupService cleanup = c.getBean(OrphanObjectCleanupService.class);
            assertThat(cleanup.cleanup().deleted()).isZero();
            assertThat(real.read("originals/b-orphan")).containsExactly((byte) 65);
            // 推进测试时钟，使用真实 MinIO lastModified 验证24小时保留边界，无需修改对象元数据。
            Clock future = Clock.fixed(Instant.now().plus(Duration.ofHours(25)), ZoneOffset.UTC);
            OriginalFileStore faulty = new OriginalFileStore() {
                public void put(String k, byte[] b) {
                    real.put(k, b);
                }

                public byte[] read(String k) {
                    return real.read(k);
                }

                public void delete(String k) {
                    if (k.equals("originals/a-fail")) throw new IllegalStateException("injected");
                    real.delete(k);
                }

                public ObjectPage listOriginals(String t, int n) {
                    return real.listOriginals(t, n);
                }
            };
            OrphanObjectCleanupService injected = new OrphanObjectCleanupService(faulty, mapper, properties, guard);
            OrphanObjectCleanupService.Result result = injected.cleanup(future);
            assertThat(result.deleted()).isEqualTo(1);
            assertThat(result.failed()).isEqualTo(1);
            assertThatThrownBy(() -> real.read("originals/b-orphan")).isInstanceOf(IllegalStateException.class);
            assertThat(real.read("originals/c-used")).containsExactly((byte) 65);
            assertThat(real.read("other/keep")).containsExactly((byte) 65);
            assertThat(cleanup.cleanup(future).deleted()).isEqualTo(1);
            assertThat(cleanup.cleanup(future).deleted()).isZero();
            real.put("originals/in-use", new byte[] {65});

            try (java.util.concurrent.ExecutorService executor =
                    java.util.concurrent.Executors.newSingleThreadExecutor()) {
                java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1),
                        release = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.Future<?> holding = executor.submit(() -> {
                    OriginalObjectGuard.Handle lock = guard.acquire("originals/in-use");
                    try {
                        held.countDown();
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        lock.close();
                    }
                });
                held.await();
                try {
                    assertThat(cleanup.cleanup(future).deleted()).isZero();
                } finally {
                    release.countDown();
                }
                holding.get();
            }
            assertThat(real.read("originals/in-use")).containsExactly((byte) 65);
            properties.setOrphanCleanupBatchSize(1);
            real.put("originals/z-last", new byte[] {65});
            OrphanObjectCleanupService paged = new OrphanObjectCleanupService(real, mapper, properties, guard);
            int removed = 0;
            for (int i = 0; i < 5; i++) {
                OrphanObjectCleanupService.Result batch = paged.cleanup(future);
                assertThat(batch.deleted() + batch.skipped() + batch.failed()).isLessThanOrEqualTo(1);
                removed += batch.deleted();
            }
            assertThat(removed).isEqualTo(2);
            assertThat(real.read("originals/c-used")).containsExactly((byte) 65);
            properties.setOrphanCleanupBatchSize(100);
            real.put("originals/in-use", new byte[] {65});
            // 真正断开 PG：引用查询失败后必须保留所有剩余对象。
            postgres.stop();
            assertThat(cleanup.cleanup(future).deleted()).isZero();
            assertThat(real.read("originals/in-use")).containsExactly((byte) 65);
            assertThat(real.read("originals/c-used")).containsExactly((byte) 65);
        }
    }
}
