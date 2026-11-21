package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.lnjecit.knowledge.assistant.infrastructure.storage.ObjectKeyPrefixes;
import com.lnjecit.knowledge.assistant.infrastructure.storage.OriginalFileStore;
import com.lnjecit.knowledge.assistant.ingestion.controller.DocumentImportController;
import com.lnjecit.knowledge.assistant.ingestion.task.TaskScheduler;
import com.lnjecit.knowledge.assistant.knowledge.service.KnowledgeBaseService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class IngestionPipelineIT extends ElasticsearchIT {
    static final StorageImportIT storage = new StorageImportIT();

    @Container
    static final PostgreSQLContainer postgres = storage.postgres;

    @Container
    static final GenericContainer<?> minio = storage.minio;

    private static final org.springframework.security.core.Authentication AUTHENTICATION =
            org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                    new com.lnjecit.knowledge.assistant.auth.service.AuthPrincipal(42L, "pipeline-test-user"),
                    null,
                    java.util.List.of());

    @Test
    void markdownAndTextThroughProductionScheduler() throws Exception {
        runPipeline("normal");
    }

    @Test
    void retryAfterChunkCommit() throws Exception {
        runPipeline("retry");
    }

    @Test
    void permanentSourceMismatch() throws Exception {
        runPipeline("permanent");
    }

    @Test
    void fourAttemptsOnly() throws Exception {
        runPipeline("exhaust");
    }

    @Test
    void partialEmbeddingRecovery() throws Exception {
        runPipeline("partial");
    }

    @Test
    void partialEsRecovery() throws Exception {
        runPipeline("es-partial");
    }

    @Test
    void esCommittedBeforeVerification() throws Exception {
        runPipeline("es-complete");
    }

    @Test
    void verifiedBeforePublish() throws Exception {
        runPipeline("verified");
    }

    @Test
    void committedPublishResponseLost() throws Exception {
        runPipeline("publish-lost");
    }

    @Test
    void rolledBackPublishIsRetriedUsingRealClaim() throws Exception {
        runPipeline("publish-rollback");
    }

    @Test
    void embeddingResponseExhaustsBudget() throws Exception {
        runPipeline("timeout-model");
    }

    @Test
    void verifiedBeforeDeadlineExhaustion() throws Exception {
        runPipeline("timeout-verified");
    }

    @Test
    void publishedConfirmationFailureCannotFailTask() throws Exception {
        runPipeline("publish-confirm-fails");
    }

    @Test
    void publishedTaskIgnoresExhaustedBudget() throws Exception {
        runPipeline("publish-timeout");
    }

    void runPipeline(String mode) throws Exception {
        java.util.concurrent.atomic.AtomicLong ticker = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicBoolean timeoutArmed = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicInteger publishCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger expiredReads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> publishedChunks =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<java.util.Map<String, Object>> publishedEs =
                new java.util.concurrent.atomic.AtomicReference<>();
        JsonMapper json = JsonMapper.builder().build();
        java.util.concurrent.atomic.AtomicBoolean unavailable = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        List<String> requested = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody().readAllBytes());
            int call = requests.incrementAndGet();
            request.path("input").forEach(text -> requested.add(text.asString()));
            if (unavailable.get() || (mode.equals("partial") && call >= 2)) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            assertThat(request.path("model").asString()).isEqualTo("text-embedding-v4");
            JsonNode input = request.path("input");
            int count = input.isArray() ? input.size() : 1;
            List<String> data = new ArrayList<>();
            for (int i = 0; i < count; i++)
                data.add("{\"object\":\"embedding\",\"index\":" + i + ",\"embedding\":[1" + ",0".repeat(1023) + "]}");
            byte[] body = ("{\"object\":\"list\",\"model\":\"text-embedding-v4\",\"data\":[" + String.join(",", data)
                            + "],\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}")
                    .getBytes(StandardCharsets.UTF_8);
            if (mode.equals("timeout-model") && timeoutArmed.compareAndSet(true, false))
                ticker.addAndGet(java.time.Duration.ofSeconds(2).toNanos());
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        String endpoint = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
        io.minio.MinioClient client = io.minio.MinioClient.builder()
                .endpoint(endpoint)
                .credentials("storage-user", "storage-test-password")
                .build();
        if (!client.bucketExists(
                io.minio.BucketExistsArgs.builder().bucket("pipeline-test").build()))
            client.makeBucket(
                    io.minio.MakeBucketArgs.builder().bucket("pipeline-test").build());
        SpringApplication app = new SpringApplication(KnowledgeAssistantApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.Map<String, Integer> calls = new java.util.concurrent.ConcurrentHashMap<>();
        app.addInitializers(context -> context.addBeanFactoryPostProcessor(beanFactory -> beanFactory.registerSingleton(
                "boundaryPostProcessor", new org.springframework.beans.factory.config.BeanPostProcessor() {
                    @Override
                    public Object postProcessAfterInitialization(Object bean, String name) {
                        if (!(bean instanceof com.lnjecit.knowledge.assistant.ingestion.task.IngestionTaskProcessor
                                || bean
                                        instanceof
                                        com.lnjecit.knowledge.assistant.ingestion.publish.IndexPublishRepository
                                || bean instanceof com.lnjecit.knowledge.assistant.ingestion.task.TaskService
                                || bean instanceof com.lnjecit.knowledge.assistant.ingestion.publish.IndexPublishService
                                || bean instanceof com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkService
                                || bean instanceof com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingService
                                || bean instanceof com.lnjecit.knowledge.assistant.ingestion.indexing.EsIndexService
                                || bean
                                        instanceof
                                        com.lnjecit.knowledge.assistant.infrastructure.elasticsearch.EsIndexRepository
                                || bean
                                        instanceof
                                        com.lnjecit.knowledge.assistant.ingestion.verification
                                                .IndexVerificationService)) return bean;
                        org.springframework.aop.framework.ProxyFactory proxy =
                                new org.springframework.aop.framework.ProxyFactory(bean);
                        proxy.setProxyTargetClass(true);
                        proxy.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
                            String method = invocation.getMethod().getName();
                            calls.merge(method, 1, Integer::sum);
                            String key = bean
                                            instanceof
                                            com.lnjecit.knowledge.assistant.ingestion.publish.IndexPublishService
                                    ? "service." + method
                                    : bean
                                                    instanceof
                                                    com.lnjecit.knowledge.assistant.ingestion.publish
                                                            .IndexPublishRepository
                                            ? "repository." + method
                                            : bean instanceof com.lnjecit.knowledge.assistant.ingestion.task.TaskService
                                                    ? "tasks." + method
                                                    : "other." + method;
                            calls.merge(key, 1, Integer::sum);
                            if ((mode.startsWith("timeout-") || mode.equals("publish-timeout"))
                                    && method.equals("process"))
                                invocation.getArguments()[1] =
                                        new com.lnjecit.knowledge.assistant.ingestion.task.TaskExecutionContext(
                                                java.time.Duration.ofSeconds(1), () -> {
                                                    if (ticker.get() > 0) expiredReads.incrementAndGet();
                                                    return ticker.get();
                                                });
                            boolean publication = bean
                                            instanceof
                                            com.lnjecit.knowledge.assistant.ingestion.publish.IndexPublishService
                                    && method.equals("publish");
                            int publicationCall = publication ? publishCalls.incrementAndGet() : 0;
                            if (publication && mode.equals("publish-confirm-fails") && publicationCall == 2)
                                throw new org.springframework.transaction.TransactionSystemException(
                                        "TEST_CONFIRMATION_UNAVAILABLE");
                            Object result = invocation.proceed();
                            if (publication
                                    && (mode.equals("publish-confirm-fails") || mode.equals("publish-timeout"))) {
                                com.lnjecit.knowledge.assistant.ingestion.task.ClaimedTask claim =
                                        (com.lnjecit.knowledge.assistant.ingestion.task.ClaimedTask)
                                                invocation.getArguments()[0];
                                JdbcTemplate evidence = context.getBeanFactory().getBean(JdbcTemplate.class);
                                publishedChunks.set(evidence.queryForObject(
                                        "SELECT json_agg(t ORDER BY id)::text FROM document_chunk t WHERE index_version_id=?",
                                        String.class,
                                        claim.indexVersionId()));
                                publishedEs.set(esSnapshot(
                                        context.getBeanFactory().getBean(ElasticsearchClient.class),
                                        claim.indexVersionId()));
                                if (mode.equals("publish-confirm-fails"))
                                    throw new org.springframework.transaction.TransactionSystemException(
                                            "TEST_COMMITTED_RESPONSE_LOST");
                                // 真实事务已经提交，尚未返回 Processor；之后任何预算读取都会被计数。
                                ticker.addAndGet(java.time.Duration.ofSeconds(2).toNanos());
                            }
                            if (mode.equals("publish-rollback")
                                    && bean
                                            instanceof
                                            com.lnjecit.knowledge.assistant.ingestion.publish.IndexPublishRepository
                                    && method.equals("publish")
                                    && armed.compareAndSet(true, false))
                                throw new org.springframework.transaction.TransactionSystemException(
                                        "TEST_ROLLBACK_BEFORE_COMMIT");
                            if (bean instanceof com.lnjecit.knowledge.assistant.ingestion.publish.IndexPublishService
                                    && mode.equals("publish-lost")
                                    && method.equals("publish")
                                    && armed.compareAndSet(true, false))
                                throw new org.springframework.transaction.TransactionSystemException(
                                        "TEST_COMMITTED_RESPONSE_LOST");
                            if (mode.equals("timeout-verified")
                                    && method.equals("verify")
                                    && timeoutArmed.compareAndSet(true, false))
                                ticker.addAndGet(java.time.Duration.ofSeconds(2).toNanos());
                            // 仅测试代理：真实方法返回意味着其持久化事务已提交。
                            boolean inject = mode.equals("es-partial") && method.equals("create")
                                    || mode.equals("es-complete") && method.equals("index")
                                    || mode.equals("verified") && method.equals("verify");
                            if (inject && armed.compareAndSet(true, false))
                                throw new com.lnjecit.knowledge.assistant.ingestion.task.TaskExecutionException(
                                        com.lnjecit.knowledge.assistant.ingestion.task.TaskExecutionException.Code
                                                .SEARCH_UNAVAILABLE);
                            return result;
                        });
                        return proxy.getProxy();
                    }
                })));
        try (ConfigurableApplicationContext c = app.run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--assistant.minio.endpoint=" + endpoint,
                "--assistant.minio.access-key=storage-user",
                "--assistant.minio.secret-key=storage-test-password",
                "--assistant.minio.bucket=pipeline-test",
                "--assistant.elasticsearch.enabled=true",
                "--assistant.elasticsearch.endpoint=http://" + elasticsearch.getHttpHostAddress(),
                "--assistant.model.enabled=true",
                "--assistant.model.api-key=local-stub",
                "--assistant.model.chat-model=unused",
                "--assistant.model.base-url=http://127.0.0.1:"
                        + stub.getAddress().getPort() + "/v1",
                "--assistant.embedding.batch-size=1",
                "--assistant.elasticsearch.batch-size=1",
                "--assistant.worker.enabled=true",
                "--assistant.worker.scan-interval=24h")) {
            // 初次空扫描后取消后台触发，测试只串行调用真实调度入口。
            c.getBean(ScheduledAnnotationBeanPostProcessor.class).destroy();
            MockMvc mvc = MockMvcBuilders.standaloneSetup(c.getBean(DocumentImportController.class))
                    .build();
            JdbcTemplate jdbc = c.getBean(JdbcTemplate.class);
            ElasticsearchClient es = c.getBean(ElasticsearchClient.class);
            for (String extension : List.of("md", "txt")) {
                publishCalls.set(0);
                expiredReads.set(0);
                ticker.set(0);
                publishedChunks.set(null);
                publishedEs.set(null);
                requests.set(0);
                timeoutArmed.set(true);
                armed.set(true);
                calls.clear();
                requested.clear();
                long kb = c.getBean(KnowledgeBaseService.class)
                        .create(42L, "pipeline-" + extension, null)
                        .getId();
                String source = "# 中文知识库 Spring Boot " + extension + "\n\n"
                        + java.util.stream.IntStream.range(0, 160)
                                .mapToObj(i -> "段落" + i + " 知识库 PostgreSQL Elasticsearch 向量检索 😀。\n\n")
                                .collect(java.util.stream.Collectors.joining());
                byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
                MockMultipartFile file = new MockMultipartFile("file", "fixture." + extension, "text/plain", bytes);
                JsonNode response = json.readTree(mvc.perform(multipart("/v1/knowledge-bases/{id}/documents:import", kb)
                                        .file(file)
                                        .principal(AUTHENTICATION))
                                .andExpect(status().isAccepted())
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                        .path("data");
                long document = response.path("documentId").asLong(),
                        task = response.path("importTaskId").asLong();
                long version = jdbc.queryForObject(
                        "SELECT index_version_id FROM document_import_task WHERE id=?", Long.class, task);
                assertThat(jdbc.queryForObject(
                                "SELECT status FROM document_import_task WHERE id=?", String.class, task))
                        .isEqualTo("PENDING");
                assertThat(jdbc.queryForObject(
                                "SELECT status FROM document_index_version WHERE id=?", String.class, version))
                        .isEqualTo("BUILDING");
                assertThat(jdbc.queryForObject(
                                "SELECT active_version_id FROM document WHERE id=?", Long.class, document))
                        .isNull();
                mvc.perform(get("/v1/import-tasks/{id}", task).principal(AUTHENTICATION))
                        .andExpect(jsonPath("$.data.status").value("RECEIVED"));
                String key = jdbc.queryForObject("SELECT object_key FROM document WHERE id=?", String.class, document);
                assertThat(key).startsWith(ObjectKeyPrefixes.ORIGINALS);
                assertThat(c.getBean(OriginalFileStore.class).read(key)).isEqualTo(bytes);
                String chunkSnapshot = null;
                String persistedSnapshot = null;
                java.util.Map<String, Object> esBefore = new java.util.HashMap<>();
                java.util.Map<String, Integer> beforeCalls = null;
                java.util.Map<Long, String> completed = new java.util.HashMap<>();
                if (mode.equals("permanent")) {
                    byte[] corrupt = "changed".getBytes(StandardCharsets.UTF_8);
                    client.putObject(io.minio.PutObjectArgs.builder().bucket("pipeline-test").object(key).stream(
                                    new java.io.ByteArrayInputStream(corrupt), corrupt.length, -1)
                            .build());
                    c.getBean(TaskScheduler.class).scan();
                    assertThat(jdbc.queryForObject(
                                    "SELECT status='FAILED' AND attempt_count=1 AND last_error_code='VALIDATION_FAILED' AND next_retry_at IS NULL FROM document_import_task WHERE id=?",
                                    Boolean.class,
                                    task))
                            .isTrue();
                    c.getBean(TaskScheduler.class).scan();
                    unpublished(jdbc, mvc, document, version, task);
                    continue;
                }
                if (!mode.equals("normal") && !mode.startsWith("publish-")) {
                    unavailable.set(mode.equals("retry") || mode.equals("exhaust"));
                    int attempts = mode.equals("exhaust") ? 4 : 1;
                    for (int attempt = 1; attempt <= attempts; attempt++) {
                        c.getBean(TaskScheduler.class).scan();
                        assertThat(jdbc.queryForObject(
                                        "SELECT status='FAILED' AND last_error_code=? FROM document_import_task WHERE id=?",
                                        Boolean.class,
                                        mode.startsWith("timeout-")
                                                ? "TASK_TIMEOUT"
                                                : mode.startsWith("es-") || mode.equals("verified")
                                                        ? "SEARCH_UNAVAILABLE"
                                                        : "MODEL_UNAVAILABLE",
                                        task))
                                .isTrue();
                        assertThat(jdbc.queryForObject(
                                        "SELECT attempt_count FROM document_import_task WHERE id=?",
                                        Integer.class,
                                        task))
                                .isEqualTo(attempt);
                        assertThat(jdbc.queryForObject(
                                        "SELECT claim_epoch FROM document_import_task WHERE id=?", Long.class, task))
                                .isEqualTo((long) attempt);
                        assertThat(jdbc.queryForObject(
                                        "SELECT next_retry_at IS NULL FROM document_import_task WHERE id=?",
                                        Boolean.class,
                                        task))
                                .isEqualTo(attempt == 4);
                        if (mode.startsWith("timeout-")) {
                            assertThat(calls.getOrDefault("publish", 0)).isZero();
                            if (mode.equals("timeout-model")) {
                                assertThat(calls.getOrDefault("index", 0)).isZero();
                                assertThat(calls.getOrDefault("verify", 0)).isZero();
                                assertThat(jdbc.queryForObject(
                                                "SELECT count(*) FROM document_chunk WHERE index_version_id=? AND embedding IS NOT NULL",
                                                Integer.class,
                                                version))
                                        .isZero();
                            } else {
                                assertThat(jdbc.queryForObject(
                                                "SELECT status FROM document_index_version WHERE id=?",
                                                String.class,
                                                version))
                                        .isEqualTo("VERIFIED");
                            }
                        }
                        String now = chunks(jdbc, version);
                        if (chunkSnapshot == null) chunkSnapshot = now;
                        assertThat(now).isEqualTo(chunkSnapshot);
                        unpublished(jdbc, mvc, document, version, task);
                        assertThat(c.getBean(com.lnjecit.knowledge.assistant.ingestion.task.TaskService.class)
                                        .claimNext("early-retry"))
                                .isEmpty();
                        if (attempt < 4)
                            jdbc.update(
                                    "UPDATE document_import_task SET next_retry_at=statement_timestamp()-interval '1 second' WHERE id=?",
                                    task);
                    }
                    if (mode.equals("exhaust")) {
                        c.getBean(TaskScheduler.class).scan();
                        assertThat(jdbc.queryForObject(
                                        "SELECT attempt_count FROM document_import_task WHERE id=?",
                                        Integer.class,
                                        task))
                                .isEqualTo(4);
                        continue;
                    }
                    if (mode.equals("partial")) {
                        jdbc.query(
                                "SELECT id,embedding::text FROM document_chunk WHERE index_version_id=? AND embedding IS NOT NULL",
                                rs -> {
                                    completed.put(rs.getLong(1), rs.getString(2));
                                },
                                version);
                        assertThat(completed).hasSize(1);
                        assertThat(jdbc.queryForObject(
                                        "SELECT count(*) FROM document_chunk WHERE index_version_id=? AND embedding IS NULL",
                                        Integer.class,
                                        version))
                                .isPositive();
                    }
                    if (mode.startsWith("es-") || mode.equals("verified")) {
                        assertThat(jdbc.queryForObject(
                                        "SELECT status FROM document_index_version WHERE id=?", String.class, version))
                                .isEqualTo(mode.equals("verified") ? "VERIFIED" : "BUILDING");
                        es.indices().refresh(r -> r.index("ka-chunks-v1"));
                        long esCount = es.count(r -> r.index("ka-chunks-v1")
                                        .query(q -> q.term(
                                                t -> t.field("index_version_id").value(Long.toString(version)))))
                                .count();
                        assertThat(esCount).isPositive();
                        es.search(
                                        r -> r.index("ka-chunks-v1")
                                                .size(100)
                                                .query(q -> q.term(t -> t.field("index_version_id")
                                                        .value(Long.toString(version)))),
                                        Object.class)
                                .hits()
                                .hits()
                                .forEach(hit -> esBefore.put(hit.id(), hit.source()));
                        int pgCount = jdbc.queryForObject(
                                "SELECT count(*) FROM document_chunk WHERE index_version_id=?", Integer.class, version);
                        if (mode.equals("es-partial")) assertThat(esCount).isLessThan(pgCount);
                        else assertThat(esCount).isEqualTo(pgCount);
                        persistedSnapshot = jdbc.queryForObject(
                                "SELECT json_agg(t ORDER BY id)::text FROM document_chunk t WHERE index_version_id=?",
                                String.class,
                                version);
                        beforeCalls = new java.util.HashMap<>(calls);
                    }
                    unavailable.set(false);
                    // 测试 Stub 恢复：负值保证本次剩余请求不会再次触发第二批故障。
                    requests.set(-10000);
                    requested.clear();
                }
                c.getBean(TaskScheduler.class).scan();
                assertThat(jdbc.queryForObject(
                                "SELECT status FROM document_import_task WHERE id=?", String.class, task))
                        .as("task %s", jdbc.queryForMap("SELECT * FROM document_import_task WHERE id=?", task))
                        .isEqualTo("SUCCEEDED");
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM document_chunk WHERE index_version_id=? AND embedding IS NOT NULL AND vector_dims(embedding)=1024",
                                Integer.class,
                                version))
                        .isGreaterThan(1);
                assertThat(jdbc.queryForObject(
                                "SELECT attempt_count FROM document_import_task WHERE id=?", Integer.class, task))
                        .isEqualTo((mode.equals("normal") || mode.startsWith("publish-")) ? 1 : 2);
                assertThat(jdbc.queryForObject(
                                "SELECT last_error_code IS NULL AND last_error_message IS NULL AND next_retry_at IS NULL FROM document_import_task WHERE id=?",
                                Boolean.class,
                                task))
                        .isTrue();
                if (chunkSnapshot != null) assertThat(chunks(jdbc, version)).isEqualTo(chunkSnapshot);
                for (java.util.Map.Entry<Long, String> entry : completed.entrySet()) {
                    assertThat(jdbc.queryForObject(
                                    "SELECT embedding::text FROM document_chunk WHERE id=?",
                                    String.class,
                                    entry.getKey()))
                            .isEqualTo(entry.getValue());
                    assertThat(requested)
                            .doesNotContain(jdbc.queryForObject(
                                    "SELECT content FROM document_chunk WHERE id=?", String.class, entry.getKey()));
                }
                if (persistedSnapshot != null)
                    assertThat(jdbc.queryForObject(
                                    "SELECT json_agg(t ORDER BY id)::text FROM document_chunk t WHERE index_version_id=?",
                                    String.class,
                                    version))
                            .isEqualTo(persistedSnapshot);
                if (mode.equals("verified")) {
                    for (String skipped : List.of("initialize", "embed", "index"))
                        assertThat(calls.get(skipped)).isEqualTo(beforeCalls.get(skipped));
                    assertThat(calls.get("verify")).isEqualTo(beforeCalls.get("verify") + 1);
                }
                for (java.util.Map.Entry<String, Object> entry : esBefore.entrySet())
                    assertThat(es.get(r -> r.index("ka-chunks-v1").id(entry.getKey()), Object.class)
                                    .source())
                            .isEqualTo(entry.getValue());
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM document_chunk WHERE index_version_id=? AND (embedding IS NULL OR vector_dims(embedding)<>1024)",
                                Integer.class,
                                version))
                        .isZero();
                List<Long> ids = new ArrayList<>();
                jdbc.query(
                        "SELECT id,content,source_start,source_end FROM document_chunk WHERE index_version_id=?",
                        rs -> {
                            ids.add(rs.getLong("id"));
                            int start = source.offsetByCodePoints(0, rs.getInt("source_start"));
                            int end = source.offsetByCodePoints(0, rs.getInt("source_end"));
                            assertThat(rs.getString("content")).isEqualTo(source.substring(start, end));
                        },
                        version);
                es.indices().refresh(r -> r.index("ka-chunks-v1"));
                assertThat(es
                                .search(
                                        r -> r.index("ka-chunks-v1")
                                                .size(100)
                                                .query(q -> q.term(t -> t.field("index_version_id")
                                                        .value(Long.toString(version)))),
                                        Object.class)
                                .hits()
                                .hits()
                                .stream()
                                .map(h -> Long.valueOf(h.id()))
                                .toList())
                        .containsExactlyInAnyOrderElementsOf(ids);
                assertThat(es.search(
                                        r -> r.index("ka-chunks-v1")
                                                .query(q -> q.match(
                                                        m -> m.field("content").query("知识库"))),
                                        Object.class)
                                .hits()
                                .hits())
                        .isNotEmpty();
                assertThat(jdbc.queryForObject(
                                "SELECT active_version_id FROM document WHERE id=?", Long.class, document))
                        .isEqualTo(version);
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM document_index_version WHERE id=? AND status='PUBLISHED' AND published_at IS NOT NULL",
                                Integer.class,
                                version))
                        .isEqualTo(1);
                mvc.perform(get("/v1/import-tasks/{id}", task).principal(AUTHENTICATION))
                        .andExpect(jsonPath("$.data.status").value("READY"));
                if (mode.equals("publish-confirm-fails") || mode.equals("publish-timeout")) {
                    assertThat(publishCalls.get()).isEqualTo(mode.equals("publish-confirm-fails") ? 2 : 1);
                    assertThat(calls.getOrDefault("repository.publish", 0)).isEqualTo(1);
                    assertThat(calls.getOrDefault("tasks.fail", 0))
                            .isEqualTo(mode.equals("publish-confirm-fails") ? 1 : 0);
                    assertThat(expiredReads.get()).isZero();
                    assertThat(jdbc.queryForObject(
                                    "SELECT json_agg(t ORDER BY id)::text FROM document_chunk t WHERE index_version_id=?",
                                    String.class,
                                    version))
                            .isEqualTo(publishedChunks.get());
                    assertThat(esSnapshot(es, version)).isEqualTo(publishedEs.get());
                    assertThat(jdbc.queryForObject(
                                    "SELECT claim_epoch=1 AND lease_owner IS NULL AND lease_until IS NULL FROM document_import_task WHERE id=?",
                                    Boolean.class,
                                    task))
                            .isTrue();
                }
                String snapshot = jdbc.queryForObject(
                        "SELECT json_agg(t ORDER BY id)::text FROM document_chunk t WHERE index_version_id=?",
                        String.class,
                        version);
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> c.getBean(
                                        com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkService.class)
                                .initialize(
                                        document,
                                        version,
                                        new com.lnjecit.knowledge.assistant.ingestion.parser.DocumentParserFactory()
                                                .parse("fixture." + extension, bytes)))
                        .isInstanceOf(IllegalStateException.class);
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> c.getBean(
                                        com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingService.class)
                                .embed(document, version))
                        .isInstanceOf(IllegalStateException.class);
                org.assertj.core.api.Assertions.assertThatThrownBy(
                                () -> c.getBean(com.lnjecit.knowledge.assistant.ingestion.indexing.EsIndexService.class)
                                        .index(document, version))
                        .isInstanceOf(IllegalStateException.class);
                assertThat(jdbc.queryForObject(
                                "SELECT json_agg(t ORDER BY id)::text FROM document_chunk t WHERE index_version_id=?",
                                String.class,
                                version))
                        .isEqualTo(snapshot);
                int before = jdbc.queryForObject("SELECT count(*) FROM document", Integer.class);
                mvc.perform(multipart("/v1/knowledge-bases/{id}/documents:import", kb)
                                .file(file)
                                .principal(AUTHENTICATION))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.importTaskId").value(task));
                assertThat(jdbc.queryForObject("SELECT count(*) FROM document", Integer.class))
                        .isEqualTo(before);
                for (String table : List.of("document", "document_index_version", "document_import_task")) {
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class))
                            .isEqualTo(before);
                }

                long otherKb = c.getBean(KnowledgeBaseService.class)
                        .create(42L, "isolated-" + extension, null)
                        .getId();
                mvc.perform(multipart("/v1/knowledge-bases/{id}/documents:import", otherKb)
                                .file(file)
                                .principal(AUTHENTICATION))
                        .andExpect(status().isAccepted());
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM document WHERE knowledge_base_id=?", Integer.class, otherKb))
                        .isEqualTo(1);
                c.getBean(TaskScheduler.class).scan();
            }
        } finally {
            stub.stop(0);
        }
    }

    static String chunks(JdbcTemplate jdbc, long version) {
        return jdbc.queryForObject(
                "SELECT json_agg(t ORDER BY id)::text FROM (SELECT id,chunk_order,content,heading_path,source_start,source_end FROM document_chunk WHERE index_version_id=?) t",
                String.class,
                version);
    }

    static java.util.Map<String, Object> esSnapshot(ElasticsearchClient es, long version) throws java.io.IOException {
        es.indices().refresh(r -> r.index("ka-chunks-v1"));
        java.util.Map<String, Object> snapshot = new java.util.TreeMap<>();
        es.search(
                        r -> r.index("ka-chunks-v1")
                                .size(100)
                                .query(q ->
                                        q.term(t -> t.field("index_version_id").value(Long.toString(version)))),
                        Object.class)
                .hits()
                .hits()
                .forEach(hit -> snapshot.put(hit.id(), hit.source()));
        return snapshot;
    }

    static void unpublished(JdbcTemplate jdbc, MockMvc mvc, long document, long version, long task) throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document WHERE id=?", Integer.class, document))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM document_index_version WHERE document_id=?", Integer.class, document))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT active_version_id FROM document WHERE id=?", Long.class, document))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM document_index_version WHERE id=?", String.class, version))
                .isNotEqualTo("PUBLISHED");
        mvc.perform(get("/v1/import-tasks/{id}", task).principal(AUTHENTICATION))
                .andExpect(jsonPath("$.data.status").value("FAILED"));
    }
}
