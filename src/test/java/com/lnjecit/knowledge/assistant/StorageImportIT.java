package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lnjecit.knowledge.assistant.infrastructure.config.ChunkingProperties;
import com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties;
import com.lnjecit.knowledge.assistant.infrastructure.config.UploadProperties;
import com.lnjecit.knowledge.assistant.infrastructure.storage.MinioOriginalFileStore;
import com.lnjecit.knowledge.assistant.infrastructure.storage.ObjectKeyPrefixes;
import com.lnjecit.knowledge.assistant.infrastructure.storage.OriginalFileStore;
import com.lnjecit.knowledge.assistant.ingestion.upload.DocumentImportRepository;
import com.lnjecit.knowledge.assistant.ingestion.upload.DocumentImportService;
import com.lnjecit.knowledge.assistant.knowledge.service.KnowledgeBaseService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class StorageImportIT {
    @Container
    final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
                            "pgvector/pgvector:0.8.0-pg17@sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87")
                    .asCompatibleSubstituteFor("postgres"))
            .withImagePullPolicy(i -> false);

    @Container
    final GenericContainer<?> minio = new GenericContainer<>(
                    DockerImageName.parse("knowledge-assistant-minio:RELEASE.2025-09-07T16-13-09Z-local"))
            .withEnv("MINIO_ROOT_USER", "storage-user")
            .withEnv("MINIO_ROOT_PASSWORD", "storage-test-password")
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000))
            .withImagePullPolicy(i -> false);

    static class FaultStore implements OriginalFileStore {
        final OriginalFileStore real;
        final List<String> staged = new java.util.concurrent.CopyOnWriteArrayList<>(),
                finals = new java.util.concurrent.CopyOnWriteArrayList<>();
        boolean copyBefore, copyAfter, stagingDelete, finalDelete;
        volatile CyclicBarrier barrier;

        FaultStore(OriginalFileStore real) {
            this.real = real;
        }

        void outside() {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
        }

        public void put(String k, byte[] b) {
            outside();
            assertThat(k).startsWith(ObjectKeyPrefixes.STAGING);
            staged.add(k);
            real.put(k, b);
            if (barrier != null)
                try {
                    barrier.await(20, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("Injected barrier failure");
                }
        }

        public void copy(String s, String t) {
            outside();
            finals.add(t);
            if (copyBefore) throw new IllegalStateException("injected");
            real.copy(s, t);
            if (copyAfter) throw new IllegalStateException("injected uncertain copy");
        }

        public byte[] read(String k) {
            return real.read(k);
        }

        public void delete(String k) {
            outside();
            if ((k.startsWith(ObjectKeyPrefixes.STAGING) && stagingDelete)
                    || (k.startsWith(ObjectKeyPrefixes.ORIGINALS) && finalDelete))
                throw new IllegalStateException("injected delete");
            real.delete(k);
        }
    }

    DocumentImportService service(
            ConfigurableApplicationContext c, OriginalFileStore store, PlatformTransactionManager manager) {
        return new DocumentImportService(
                c.getBean(DocumentImportRepository.class),
                c.getBean(KnowledgeBaseService.class),
                store,
                c.getBean(UploadProperties.class),
                c.getBean(ChunkingProperties.class),
                c.getBean(tools.jackson.databind.json.JsonMapper.class),
                c.getBean(ModelProperties.class),
                manager);
    }

    List<String> keys(io.minio.MinioClient client, String bucket) throws Exception {
        List<String> keys = new ArrayList<>();
        for (io.minio.Result<io.minio.messages.Item> item : client.listObjects(io.minio.ListObjectsArgs.builder()
                .bucket(bucket)
                .recursive(true)
                .build())) keys.add(item.get().objectName());
        return keys;
    }

    String mc(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "mc";
        System.arraycopy(args, 0, command, 1, args.length);
        org.testcontainers.containers.Container.ExecResult result = minio.execInContainer(command);
        assertThat(result.getExitCode()).isZero();
        return result.getStdout();
    }

    String ilm(boolean check) throws Exception {
        return ilm(check, 0);
    }

    String ilm(boolean check, int expectedExit) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                "python",
                "infra/minio/configure-staging-ilm.py",
                "--container",
                minio.getContainerId(),
                "--bucket",
                "storage-test"));
        if (check) command.add("--check");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isEqualTo(expectedExit);
        return output;
    }

    @Test
    void realStorageAndCompensation(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String endpoint = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000), bucket = "storage-test";
        io.minio.MinioClient client = io.minio.MinioClient.builder()
                .endpoint(endpoint)
                .credentials("storage-user", "storage-test-password")
                .build();
        client.makeBucket(io.minio.MakeBucketArgs.builder().bucket(bucket).build());
        OriginalFileStore real = new MinioOriginalFileStore(endpoint, "storage-user", "storage-test-password", bucket);
        assertThat(minio.execInContainer("minio", "--version").getStdout()).contains("RELEASE.2025-09-07T16-13-09Z");
        assertThat(mc("--version")).contains("RELEASE.2025-08-13T08-35-41Z");
        mc("alias", "set", "probe", "http://localhost:9000", "storage-user", "storage-test-password");
        assertThat(ilm(true, 1)).contains("Missing staging ILM");
        mc("ilm", "rule", "add", "--prefix", "archive/", "--expire-days", "30", "probe/" + bucket);
        assertThat(ilm(false)).contains("\"expireDays\": 2", "\"ruleCount\": 2");
        String rules = mc("ilm", "rule", "export", "probe/" + bucket);
        assertThat(ilm(false)).contains("\"ruleCount\": 2");
        assertThat(ilm(true)).contains("\"verified\": true");
        assertThat(mc("ilm", "rule", "export", "probe/" + bucket)).isEqualTo(rules);
        assertThat(rules.replaceAll("\\s", ""))
                .contains("\"Prefix\":\"" + ObjectKeyPrefixes.STAGING + "\"", "\"Days\":2", "\"Prefix\":\"archive/\"")
                .doesNotContain(ObjectKeyPrefixes.ORIGINALS);
        byte[] bytes = {65, 66, 67};
        real.put(ObjectKeyPrefixes.STAGING + "probe", bytes);
        real.copy(ObjectKeyPrefixes.STAGING + "probe", ObjectKeyPrefixes.ORIGINALS + "probe");
        assertThat(real.read(ObjectKeyPrefixes.STAGING + "probe"))
                .isEqualTo(real.read(ObjectKeyPrefixes.ORIGINALS + "probe"));
        real.delete(ObjectKeyPrefixes.STAGING + "probe");
        assertThat(real.read(ObjectKeyPrefixes.ORIGINALS + "probe")).isEqualTo(bytes);
        assertThatThrownBy(
                        () -> real.copy(ObjectKeyPrefixes.ORIGINALS + "probe", ObjectKeyPrefixes.ORIGINALS + "invalid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> real.copy(ObjectKeyPrefixes.STAGING + "probe", ObjectKeyPrefixes.STAGING + "invalid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> real.copy(ObjectKeyPrefixes.STAGING + "missing", ObjectKeyPrefixes.ORIGINALS + "missing"))
                .hasMessage("Object copy failed");
        // 旧正式对象仍使用原读取路径，不需要迁移。
        real.put(ObjectKeyPrefixes.ORIGINALS + "legacy", bytes);
        assertThat(real.read(ObjectKeyPrefixes.ORIGINALS + "legacy")).isEqualTo(bytes);
        SpringApplication app = new SpringApplication(KnowledgeAssistantApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext c = app.run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword())) {
            JdbcTemplate j = c.getBean(JdbcTemplate.class);
            assertThat(j.queryForObject("SHOW server_version", String.class)).startsWith("17.6");
            assertThat(j.queryForObject("SELECT extversion FROM pg_extension WHERE extname='vector'", String.class))
                    .isEqualTo("0.8.0");
            long kb = c.getBean(KnowledgeBaseService.class)
                    .create("storage", null)
                    .getId();
            PlatformTransactionManager manager = c.getBean(PlatformTransactionManager.class);
            FaultStore store = new FaultStore(real);
            DocumentImportService upload = service(c, store, manager);
            DocumentImportService.Outcome first = upload.receive(kb, ImportTests.file("first.txt", bytes));
            String key = j.queryForObject(
                    "SELECT object_key FROM document WHERE id=?",
                    String.class,
                    first.task().documentId());
            assertThat(key).startsWith(ObjectKeyPrefixes.ORIGINALS);
            assertThat(real.read(key)).isEqualTo(bytes);
            assertThat(keys(client, bucket)).noneMatch(k -> k.startsWith(ObjectKeyPrefixes.STAGING));
            store.stagingDelete = true;
            DocumentImportService.Outcome committed = upload.receive(kb, ImportTests.file("kept.txt", new byte[] {68}));
            assertThat(committed.task().status()).isEqualTo("RECEIVED");
            assertThat(output.getOut()).contains("STAGING_OBJECT_CLEANUP_FAILED");
            store.stagingDelete = false;
            int count = j.queryForObject("SELECT count(*) FROM document_import_task", Integer.class);
            for (boolean after : new boolean[] {false, true}) {
                store.copyBefore = !after;
                store.copyAfter = after;
                assertThatThrownBy(() -> upload.receive(kb, ImportTests.file("copy.txt", new byte[] {69})))
                        .isInstanceOf(IllegalStateException.class);
                assertThat(j.queryForObject("SELECT count(*) FROM document_import_task", Integer.class))
                        .isEqualTo(count);
                String finalKey = store.finals.getLast();
                assertThat(keys(client, bucket)).doesNotContain(finalKey, store.staged.getLast());
            }
            store.copyBefore = false;
            store.copyAfter = false;
            j.execute("ALTER TABLE document_import_task ADD CONSTRAINT reject_storage CHECK(false) NOT VALID");
            assertThatThrownBy(() -> upload.receive(kb, ImportTests.file("pg.txt", new byte[] {70})))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(keys(client, bucket)).doesNotContain(store.finals.getLast(), store.staged.getLast());
            assertThat(j.queryForObject("SELECT count(*) FROM document", Integer.class))
                    .isEqualTo(count);
            assertThat(j.queryForObject("SELECT count(*) FROM document_index_version", Integer.class))
                    .isEqualTo(count);
            assertThat(j.queryForObject("SELECT count(*) FROM document_import_task", Integer.class))
                    .isEqualTo(count);
            store.finalDelete = true;
            assertThatThrownBy(() -> upload.receive(kb, ImportTests.file("pg.txt", new byte[] {71})))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(keys(client, bucket)).contains(store.finals.getLast());
            assertThat(output.getOut()).contains("ORIGINAL_OBJECT_CLEANUP_FAILED");
            store.finalDelete = false;
            j.execute("ALTER TABLE document_import_task DROP CONSTRAINT reject_storage");
            // 真PG已提交后注入响应丢失，引用查询必须保护正式对象。
            PlatformTransactionManager uncertain = new PlatformTransactionManager() {
                public TransactionStatus getTransaction(TransactionDefinition d) {
                    return manager.getTransaction(d);
                }

                public void commit(TransactionStatus s) {
                    manager.commit(s);
                    throw new IllegalStateException("injected lost commit response");
                }

                public void rollback(TransactionStatus s) {
                    manager.rollback(s);
                }
            };
            assertThatThrownBy(() -> service(c, store, uncertain)
                            .receive(kb, ImportTests.file("uncertain.txt", new byte[] {72})))
                    .isInstanceOf(IllegalStateException.class);
            String saved = store.finals.getLast();
            assertThat(j.queryForObject("SELECT count(*) FROM document WHERE object_key=?", Integer.class, saved))
                    .isEqualTo(1);
            assertThat(real.read(saved)).containsExactly((byte) 72);
            store.barrier = new CyclicBarrier(2);
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                java.util.concurrent.Callable<DocumentImportService.Outcome> call =
                        () -> upload.receive(kb, ImportTests.file("race.txt", new byte[] {73}));
                Future<DocumentImportService.Outcome> a = executor.submit(call), b = executor.submit(call);
                DocumentImportService.Outcome x = a.get(30, TimeUnit.SECONDS), y = b.get(30, TimeUnit.SECONDS);
                assertThat(x.task().documentId()).isEqualTo(y.task().documentId());
                assertThat(x.duplicate()).isNotEqualTo(y.duplicate());
                String winner = j.queryForObject(
                        "SELECT object_key FROM document WHERE id=?",
                        String.class,
                        x.task().documentId());
                assertThat(real.read(winner)).containsExactly((byte) 73);
            } finally {
                store.barrier = null;
            }
            assertThat(j.queryForObject(
                            "SELECT count(*) FROM document WHERE object_key LIKE 'staging/%'", Integer.class))
                    .isZero();
            // 主库真实不可用时无法确认引用，必须保留正式对象而不是尝试删除。
            PlatformTransactionManager unavailable = new PlatformTransactionManager() {
                public TransactionStatus getTransaction(TransactionDefinition d) {
                    return manager.getTransaction(d);
                }

                public void commit(TransactionStatus status) {
                    manager.commit(status);
                    postgres.stop();
                    throw new IllegalStateException("injected unavailable after commit");
                }

                public void rollback(TransactionStatus status) {
                    manager.rollback(status);
                }
            };
            assertThatThrownBy(() -> service(c, store, unavailable)
                            .receive(kb, ImportTests.file("unavailable.txt", new byte[] {74})))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(real.read(store.finals.getLast())).containsExactly((byte) 74);
            assertThat(output.getOut()).contains("ORIGINAL_OBJECT_REFERENCE_UNKNOWN");
        }
    }
}
