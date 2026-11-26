package com.lnjecit.knowledge.assistant.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.lnjecit.knowledge.assistant.KnowledgeAssistantApplication;
import com.lnjecit.knowledge.assistant.infrastructure.storage.OriginalFileStore;
import com.lnjecit.knowledge.assistant.rag.RagService;
import com.lnjecit.knowledge.assistant.rag.model.RagAnswer;
import com.lnjecit.knowledge.assistant.rag.model.RagStatus;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalService;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStrategy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AuthBusinessHttpTests {
    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    private ConfigurableApplicationContext start(PostgreSQLContainer pg, int redis, String namespace) {
        return start(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword(), redis, namespace);
    }

    private ConfigurableApplicationContext start(
            String url, String username, String password, int redis, String namespace) {
        SpringApplication application = new SpringApplication(KnowledgeAssistantApplication.class);
        application.addInitializers(context -> ((GenericApplicationContext) context)
                .registerBean(
                        "testOriginalFileStore",
                        OriginalFileStore.class,
                        () -> mock(OriginalFileStore.class),
                        definition -> definition.setPrimary(true)));
        application.addInitializers(context -> {
            GenericApplicationContext beans = (GenericApplicationContext) context;
            beans.registerBean(
                    "testRetrievalService",
                    RetrievalService.class,
                    () -> mock(RetrievalService.class),
                    definition -> definition.setPrimary(true));
            beans.registerBean(
                    "testRagService",
                    RagService.class,
                    () -> mock(RagService.class),
                    definition -> definition.setPrimary(true));
        });
        return application.run(
                "--server.port=0",
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=" + username,
                "--spring.datasource.password=" + password,
                "--spring.data.redis.port=" + redis,
                "--spring.session.data.redis.namespace=" + namespace,
                "--assistant.model.enabled=false",
                "--assistant.rerank.enabled=false");
    }

    private HttpResponse<String> send(
            ConfigurableApplicationContext app, String method, String path, String cookie, String token, String body)
            throws Exception {
        int port = ((WebServerApplicationContext) app).getWebServer().getPort();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/" + path))
                .header("X-Request-Id", "auth03-test")
                .header("Content-Type", "application/json")
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) request.header("Cookie", cookie);
        if (token != null) request.header("X-CSRF-TOKEN", token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String cookie(HttpResponse<String> response) {
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private String token(HttpResponse<String> response) {
        return json.readTree(response.body()).path("data").path("token").asString();
    }

    private void error(HttpResponse<String> response, int status) {
        assertError(response, status, status * 100);
    }

    private void assertError(HttpResponse<String> response, int status, int code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(code);
        assertThat(json.readTree(response.body()).path("requestId").asString()).isEqualTo("auth03-test");
        assertThat(response.headers().firstValue("X-Request-Id")).contains("auth03-test");
    }

    private String createKnowledgeBase(
            ConfigurableApplicationContext app, String session, String name, long suppliedUserId) throws Exception {
        String csrf = token(send(app, "GET", "auth/csrf", session, null, null));
        HttpResponse<String> created = send(
                app,
                "POST",
                "knowledge-bases",
                session,
                csrf,
                json.writeValueAsString(java.util.Map.of("name", name, "userId", suppliedUserId)));
        assertThat(created.statusCode()).isEqualTo(201);
        return json.readTree(created.body()).path("data").path("id").asString();
    }

    private void assertOwnedKnowledgeBases(ConfigurableApplicationContext app, String session, String expectedId)
            throws Exception {
        HttpResponse<String> list = send(app, "GET", "knowledge-bases", session, null, null);
        assertThat(list.statusCode()).isEqualTo(200);
        tools.jackson.databind.JsonNode data = json.readTree(list.body()).path("data");
        assertThat(data.size()).isEqualTo(1);
        assertThat(data.path(0).path("id").asString()).isEqualTo(expectedId);
    }

    private void verifyKnowledgeBaseOwnership(
            ConfigurableApplicationContext app, JdbcTemplate jdbc, String aliceSession) throws Exception {
        String credentials = "{\"username\":\"bob\",\"password\":\"secret123\"}";
        HttpResponse<String> csrf = send(app, "GET", "auth/csrf", null, null, null);
        String anonymous = cookie(csrf);
        assertThat(send(app, "POST", "auth/register", anonymous, token(csrf), credentials)
                        .statusCode())
                .isEqualTo(200);
        HttpResponse<String> login = send(app, "POST", "auth/login", anonymous, token(csrf), credentials);
        assertThat(login.statusCode()).isEqualTo(200);
        String bobSession = cookie(login);
        long aliceId = jdbc.queryForObject("SELECT id FROM app_user WHERE username='alice'", Long.class);
        long bobId = jdbc.queryForObject("SELECT id FROM app_user WHERE username='bob'", Long.class);
        String aliceKb = createKnowledgeBase(app, aliceSession, "Alice KB", bobId);
        String bobKb = createKnowledgeBase(app, bobSession, "Bob KB", aliceId);
        assertThat(jdbc.queryForObject(
                        "SELECT owner_user_id FROM knowledge_base WHERE id=?", Long.class, Long.valueOf(aliceKb)))
                .isEqualTo(aliceId);
        assertThat(jdbc.queryForObject(
                        "SELECT owner_user_id FROM knowledge_base WHERE id=?", Long.class, Long.valueOf(bobKb)))
                .isEqualTo(bobId);
        jdbc.update(
                "INSERT INTO app_user(id,username,password_hash,status) VALUES (1,'legacy-owner','test-only','ACTIVE')");
        jdbc.update("INSERT INTO knowledge_base(id,name,owner_user_id) VALUES (1,'Legacy',1)");

        assertOwnedKnowledgeBases(app, aliceSession, aliceKb);
        assertOwnedKnowledgeBases(app, bobSession, bobKb);
        assertThat(send(app, "GET", "knowledge-bases/" + aliceKb, aliceSession, null, null)
                        .statusCode())
                .isEqualTo(200);
        for (String inaccessibleId : java.util.List.of(bobKb, "1", "2")) {
            HttpResponse<String> detail =
                    send(app, "GET", "knowledge-bases/" + inaccessibleId, aliceSession, null, null);
            assertThat(detail.statusCode()).isEqualTo(404);
            assertThat(json.readTree(detail.body()).path("code").asInt()).isEqualTo(40401);
        }
        error(send(app, "GET", "knowledge-bases", anonymous, null, null), 401);
        verifySearchAndAnswerOwnership(app, aliceSession, bobSession, aliceKb, aliceId);
        verifyResourceOwnership(app, jdbc, aliceSession, bobSession, aliceKb, bobKb);
        verifyConversationOwnership(app, aliceSession, bobSession, aliceKb, bobKb);
    }

    private void verifyConversationOwnership(
            ConfigurableApplicationContext app, String aliceSession, String bobSession, String aliceKb, String bobKb)
            throws Exception {
        String aliceToken = token(send(app, "GET", "auth/csrf", aliceSession, null, null));
        String bobToken = token(send(app, "GET", "auth/csrf", bobSession, null, null));
        String create = """
                {"knowledgeBaseId":%s}
                """;
        assertThat(send(app, "POST", "conversations", aliceSession, aliceToken, create.formatted(bobKb))
                        .statusCode())
                .isEqualTo(404);
        error(send(app, "POST", "conversations", aliceSession, null, create.formatted(aliceKb)), 403);
        HttpResponse<String> created =
                send(app, "POST", "conversations", aliceSession, aliceToken, create.formatted(aliceKb));
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode conversation = json.readTree(created.body()).path("data");
        assertThat(conversation.path("title").asString()).isEqualTo("新会话");
        String path = "conversations/" + conversation.path("id").asString();
        UUID turnId = UUID.randomUUID();
        String turnPath = path + "/turns/" + turnId;
        String body =
                """
                {"turnId":"%s","question":"missing evidence"}
                """.formatted(turnId);
        RagService rag = app.getBean(RagService.class);
        RetrievalStrategy strategy = RetrievalStrategy.HYBRID_RRF;
        when(rag.answer(Long.valueOf(aliceKb), "missing evidence", strategy, null, List.of(), List.of()))
                .thenReturn(new RagAnswer(
                        RagStatus.INSUFFICIENT_EVIDENCE, null, List.of(), strategy, strategy, false, null, null));
        assertThat(send(app, "POST", path + "/turns", aliceSession, aliceToken, body)
                        .statusCode())
                .isEqualTo(202);
        for (String hidden : List.of(path, path + "/messages", turnPath)) {
            assertThat(send(app, "GET", hidden, bobSession, null, null).statusCode())
                    .isEqualTo(404);
        }
        assertThat(send(app, "POST", path + "/turns", bobSession, bobToken, body)
                        .statusCode())
                .isEqualTo(404);
        assertThat(send(app, "POST", turnPath + "/cancel", bobSession, bobToken, null)
                        .statusCode())
                .isEqualTo(404);
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        String status;
        do {
            HttpResponse<String> response = send(app, "GET", turnPath, aliceSession, null, null);
            assertThat(response.statusCode()).isEqualTo(200);
            status = json.readTree(response.body()).path("data").path("status").asString();
            if (!status.equals("PROCESSING")) {
                break;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        assertThat(status).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(send(app, "POST", path + "/turns", aliceSession, aliceToken, body)
                        .statusCode())
                .isEqualTo(202);
        verify(rag).answer(Long.valueOf(aliceKb), "missing evidence", strategy, null, List.of(), List.of());
    }

    private void verifySearchAndAnswerOwnership(
            ConfigurableApplicationContext app, String aliceSession, String bobSession, String aliceKb, long aliceId)
            throws Exception {
        RetrievalService retrieval = app.getBean(RetrievalService.class);
        RagService rag = app.getBean(RagService.class);
        String aliceToken = token(send(app, "GET", "auth/csrf", aliceSession, null, null));
        String bobToken = token(send(app, "GET", "auth/csrf", bobSession, null, null));
        HttpResponse<String> anonymousCsrf = send(app, "GET", "auth/csrf", null, null, null);
        String anonymous = cookie(anonymousCsrf);
        String retrievalParameters =
                """
                {"vectorTopK":8,"bm25TopK":9,"rrfK":60,"fusionTopK":7,"finalTopK":3}
                """;
        String search =
                """
                {"knowledgeBaseId":"%s","query":"owned query","strategies":["VECTOR","BM25"],"parameters":%s,"userId":%d}
                """;
        String answer =
                """
                {"knowledgeBaseId":"%s","question":"owned question","strategy":"VECTOR","retrievalParameters":%s,"userId":%d}
                """;
        for (String path : List.of("retrieval/search", "chat/answers")) {
            String template = path.equals("retrieval/search") ? search : answer;
            String owned = template.formatted(aliceKb, retrievalParameters, aliceId);
            assertError(send(app, "POST", path, anonymous, token(anonymousCsrf), owned), 401, 40100);
            assertError(send(app, "POST", path, aliceSession, null, owned), 403, 40300);
            assertError(send(app, "POST", path, aliceSession, bobToken, owned), 403, 40300);
            for (String inaccessibleId : List.of(aliceKb, "1", "2")) {
                assertError(
                        send(
                                app,
                                "POST",
                                path,
                                bobSession,
                                bobToken,
                                template.formatted(inaccessibleId, retrievalParameters, aliceId)),
                        404,
                        40401);
            }
        }
        verifyNoInteractions(retrieval, rag);
        assertThat(send(
                                app,
                                "POST",
                                "retrieval/search",
                                aliceSession,
                                aliceToken,
                                search.formatted(aliceKb, retrievalParameters, aliceId))
                        .statusCode())
                .isEqualTo(200);
        assertThat(send(
                                app,
                                "POST",
                                "chat/answers",
                                aliceSession,
                                aliceToken,
                                answer.formatted(aliceKb, retrievalParameters, aliceId))
                        .statusCode())
                .isEqualTo(200);
        RetrievalParameters expected = new RetrievalParameters(8, 9, 60, 7, 3);
        verify(retrieval)
                .search(
                        Long.valueOf(aliceKb),
                        "owned query",
                        List.of(RetrievalStrategy.VECTOR, RetrievalStrategy.BM25),
                        expected);
        verify(rag).answer(Long.valueOf(aliceKb), "owned question", RetrievalStrategy.VECTOR, expected);
        verifyNoMoreInteractions(retrieval, rag);
    }

    private void verifyResourceOwnership(
            ConfigurableApplicationContext app,
            JdbcTemplate jdbc,
            String aliceSession,
            String bobSession,
            String aliceKb,
            String bobKb)
            throws Exception {
        seedDocument(jdbc, aliceKb, 101);
        seedDocument(jdbc, bobKb, 102);
        seedDocument(jdbc, "1", 103);

        assertResources(app, aliceSession, aliceKb, 101, 200);
        assertResources(app, bobSession, bobKb, 102, 200);
        assertResources(app, aliceSession, bobKb, 102, 404);
        assertResources(app, bobSession, aliceKb, 101, 404);
        assertResources(app, aliceSession, "1", 103, 404);

        HttpResponse<String> missingTask = send(app, "GET", "import-tasks/999", aliceSession, null, null);
        error(missingTask, 404);
        assertThat(send(app, "GET", "import-tasks/102", aliceSession, null, null)
                        .body())
                .isEqualTo(missingTask.body());
        // 即使 URL 使用自有知识库，也不能读取其他知识库的 Chunk 或未发布版本。
        error(
                send(
                        app,
                        "GET",
                        "knowledge-bases/" + aliceKb + "/documents/102/versions/102/chunks/102",
                        aliceSession,
                        null,
                        null),
                404);
        jdbc.update("UPDATE document SET active_version_id=NULL WHERE id=101");
        error(
                send(
                        app,
                        "GET",
                        "knowledge-bases/" + aliceKb + "/documents/101/versions/101/chunks/101",
                        aliceSession,
                        null,
                        null),
                404);

        String csrf = token(send(app, "GET", "auth/csrf", aliceSession, null, null));
        assertThat(upload(app, bobKb, aliceSession, csrf).statusCode()).isEqualTo(404);
        assertThat(upload(app, "1", aliceSession, csrf).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document", Long.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_import_task", Long.class))
                .isEqualTo(3);
        verifyNoInteractions(app.getBean(OriginalFileStore.class));

        HttpResponse<String> accepted = upload(app, aliceKb, aliceSession, csrf);
        assertThat(accepted.statusCode()).isEqualTo(202);
        assertThat(json.readTree(accepted.body()).path("data").path("status").asString())
                .isEqualTo("RECEIVED");
        assertThat(upload(app, aliceKb, aliceSession, csrf).statusCode()).isEqualTo(200);
    }

    private void seedDocument(JdbcTemplate jdbc, String kb, int id) {
        jdbc.update(
                "INSERT INTO document(id,knowledge_base_id,filename,content_hash,object_key) VALUES(?,?,'auth.md','seed','originals/seed')",
                id,
                Long.valueOf(kb));
        jdbc.update(
                "INSERT INTO document_index_version(id,document_id,chunking_config,status,expected_chunk_count,published_at) VALUES(?,?,'{\"version\":2,\"strategy\":\"FIXED_CODE_POINT\",\"chunkSize\":800,\"chunkOverlap\":120}','PUBLISHED',1,CURRENT_TIMESTAMP)",
                id,
                id);
        jdbc.update("UPDATE document SET active_version_id=? WHERE id=?", id, id);
        jdbc.update(
                "INSERT INTO document_chunk(id,knowledge_base_id,document_id,index_version_id,chunk_order,content,source_start,source_end) VALUES(?,?,?,?,0,'evidence',0,8)",
                id,
                Long.valueOf(kb),
                id,
                id);
        jdbc.update(
                "INSERT INTO document_import_task(id,document_id,index_version_id,status) VALUES(?,?,?,'SUCCEEDED')",
                id,
                id,
                id);
    }

    private void assertResources(ConfigurableApplicationContext app, String session, String kb, int id, int status)
            throws Exception {
        String documents = "knowledge-bases/" + kb + "/documents";
        HttpResponse<String> list = send(app, "GET", documents, session, null, null);
        HttpResponse<String> task = send(app, "GET", "import-tasks/" + id, session, null, null);
        HttpResponse<String> citation =
                send(app, "GET", documents + "/" + id + "/versions/" + id + "/chunks/" + id, session, null, null);
        for (HttpResponse<String> response : List.of(list, task, citation)) {
            assertThat(response.statusCode()).isEqualTo(status);
        }
        if (status == 200) {
            assertThat(list.body()).contains("auth.md");
            assertThat(task.body()).contains("READY");
            assertThat(citation.body()).contains("evidence");
        }
    }

    private HttpResponse<String> upload(ConfigurableApplicationContext app, String kb, String session, String csrf)
            throws Exception {
        int port = ((WebServerApplicationContext) app).getWebServer().getPort();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/knowledge-bases/" + kb + "/documents:import"))
                .header("Cookie", session)
                .header("X-CSRF-TOKEN", csrf)
                .header("Content-Type", "multipart/form-data; boundary=auth04")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "--auth04\r\nContent-Disposition: form-data; name=\"file\"; filename=\"upload.md\"\r\n"
                                + "Content-Type: text/markdown\r\n\r\nowned upload\r\n--auth04--\r\n"))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void managesExplicitMemoryWithOwnershipAndCapacity() throws Exception {
        try (PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse(
                                        "pgvector/pgvector:0.8.0-pg17@sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87")
                                .asCompatibleSubstituteFor("postgres"))
                        .withImagePullPolicy(image -> false);
                GenericContainer<?> redis = new GenericContainer<>("redis:7.4.11")
                        .withExposedPorts(6379)
                        .withImagePullPolicy(image -> false)) {
            pg.start();
            redis.start();
            try (ConfigurableApplicationContext app =
                    start(pg, redis.getMappedPort(6379), "mem01:" + UUID.randomUUID())) {
                verifyMemory(app);
            }
        }
    }

    private void verifyMemory(ConfigurableApplicationContext app) throws Exception {
        String memories = "memories";
        String settings = "memory/settings";
        error(send(app, "GET", memories, null, null, null), 401);
        String owner = registerAndLogin(app, "memoryowner");
        String other = registerAndLogin(app, "memoryother");
        String csrf = token(send(app, "GET", "auth/csrf", owner, null, null));
        String otherCsrf = token(send(app, "GET", "auth/csrf", other, null, null));
        String body =
                """
                {"type":"BACKGROUND","content":"  Java  ","keywords":"jvm","userId":"1","enabled":false}
                """;
        error(send(app, "POST", memories, owner, null, body), 403);
        assertThat(memoryData(send(app, "GET", settings, owner, null, null))
                        .path("memoryEnabled")
                        .asBoolean())
                .isFalse();
        error(send(app, "PUT", settings, owner, csrf, "{}"), 400);
        for (boolean enabled : new boolean[] {true, false}) {
            assertThat(memoryData(send(app, "PUT", settings, owner, csrf, "{\"memoryEnabled\":" + enabled + "}"))
                            .path("memoryEnabled")
                            .asBoolean())
                    .isEqualTo(enabled);
        }
        error(send(app, "POST", memories, owner, csrf, body.replace("Java", "")), 400);
        error(send(app, "POST", memories, owner, csrf, body.replace("Java", "x".repeat(501))), 400);
        error(send(app, "POST", memories, owner, csrf, body.replace("jvm", "x".repeat(201))), 400);
        error(send(app, "POST", memories, owner, csrf, body.replace("BACKGROUND", "UNKNOWN")), 400);
        JsonNode created = memoryData(send(app, "POST", memories, owner, csrf, body));
        assertThat(created.path("content").asString()).isEqualTo("Java");
        assertThat(created.path("enabled").asBoolean()).isTrue();
        assertThat(created.path("sourceType").asString()).isEqualTo("MANUAL");
        assertThat(created.path("id").isString()).isTrue();
        String path = memories + "/" + created.path("id").asString();
        assertError(send(app, "POST", memories, owner, csrf, body), 409, 40903);
        String update =
                """
                {"type":"TECH_PREFERENCE","content":"Spring","enabled":false}
                """;
        assertThat(memoryData(send(app, "GET", memories, other, null, null)).size())
                .isZero();
        assertError(send(app, "PUT", path, other, otherCsrf, update), 404, 40403);
        assertError(send(app, "DELETE", path, other, otherCsrf, null), 404, 40403);
        error(send(app, "PUT", path, owner, csrf, body.replace(",\"enabled\":false", "")), 400);
        JsonNode updated = memoryData(send(app, "PUT", path, owner, csrf, update));
        assertThat(updated.path("enabled").asBoolean()).isFalse();
        assertThat(updated.path("keywords").isNull()).isTrue();
        assertThat(updated.path("updatedAt").asString())
                .isNotEqualTo(created.path("updatedAt").asString());
        JsonNode second = memoryData(send(app, "POST", memories, owner, csrf, body));
        assertError(send(app, "PUT", memories + "/" + second.path("id").asString(), owner, csrf, update), 409, 40903);
        assertThat(memoryData(send(app, "GET", memories, owner, null, null))
                        .path(0)
                        .path("id"))
                .isEqualTo(second.path("id"));
        memoryData(send(app, "DELETE", memories + "/" + second.path("id").asString(), owner, csrf, null));

        JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
        jdbc.update(
                """
                INSERT INTO user_memory(id,user_id,type,content,source_type)
                SELECT n, app_user.id, 'OTHER', 'seed-' || n, 'MANUAL'
                FROM app_user CROSS JOIN generate_series(1,48) AS n
                WHERE username='memoryowner'
                """);
        // 已停用的记忆也计入容量。两个并发请求竞争最后一个名额。
        try (java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Future<Integer> first = executor.submit(() -> {
                gate.await();
                return send(app, "POST", memories, owner, csrf, body).statusCode();
            });
            java.util.concurrent.Future<Integer> secondWrite = executor.submit(() -> {
                gate.await();
                return send(app, "POST", memories, owner, csrf, body.replace("Java", "Kotlin"))
                        .statusCode();
            });
            gate.countDown();
            assertThat(List.of(first.get(), secondWrite.get())).containsExactlyInAnyOrder(200, 409);
        }
        assertError(send(app, "POST", memories, owner, csrf, body), 409, 40904);
        assertThat(memoryData(send(app, "GET", memories, owner, null, null)).size())
                .isEqualTo(50);
    }

    private String registerAndLogin(ConfigurableApplicationContext app, String username) throws Exception {
        String credentials = "{\"username\":\"" + username + "\",\"password\":\"secret123\"}";
        HttpResponse<String> csrf = send(app, "GET", "auth/csrf", null, null, null);
        memoryData(send(app, "POST", "auth/register", cookie(csrf), token(csrf), credentials));
        HttpResponse<String> login = send(app, "POST", "auth/login", cookie(csrf), token(csrf), credentials);
        memoryData(login);
        return cookie(login);
    }

    private JsonNode memoryData(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).path("data");
    }

    @Test
    void realLoginAcrossApplications() throws Exception {
        try (PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse(
                                        "pgvector/pgvector:0.8.0-pg17@sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87")
                                .asCompatibleSubstituteFor("postgres"))
                        .withImagePullPolicy(image -> false);
                GenericContainer<?> redis = new GenericContainer<>("redis:7.4.11")
                        .withExposedPorts(6379)
                        .withImagePullPolicy(image -> false)) {
            pg.start();
            redis.start();
            String namespace = "auth03:" + UUID.randomUUID();
            try (ConfigurableApplicationContext first = start(pg, redis.getMappedPort(6379), namespace);
                    ConfigurableApplicationContext second = start(pg, redis.getMappedPort(6379), namespace)) {
                String credentials = "{\"username\":\"alice\",\"password\":\"secret123\"}";
                HttpResponse<String> csrf = send(first, "GET", "auth/csrf", null, null, null);
                String anonymous = cookie(csrf);
                String token = token(csrf);
                error(send(first, "GET", "auth/me", anonymous, null, null), 401);
                error(send(first, "POST", "auth/login", anonymous, null, credentials), 403);
                assertThat(send(first, "POST", "auth/register", anonymous, token, credentials)
                                .statusCode())
                        .isEqualTo(200);
                error(send(first, "POST", "auth/register", anonymous, token, credentials), 409);
                error(
                        send(
                                first,
                                "POST",
                                "auth/register",
                                anonymous,
                                token,
                                "{\"username\":\"a\",\"password\":\"short\"}"),
                        400);
                // 两个应用同时写入同名用户，数据库必须只允许一次注册成功。
                try (java.util.concurrent.ExecutorService executor =
                        java.util.concurrent.Executors.newFixedThreadPool(2)) {
                    String concurrent = credentials.replace("alice", "concurrent");
                    java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
                    java.util.concurrent.Future<Integer> one = executor.submit(() -> {
                        gate.await();
                        return send(first, "POST", "auth/register", anonymous, token, concurrent)
                                .statusCode();
                    });
                    java.util.concurrent.Future<Integer> two = executor.submit(() -> {
                        gate.await();
                        return send(second, "POST", "auth/register", anonymous, token, concurrent)
                                .statusCode();
                    });
                    gate.countDown();
                    assertThat(java.util.List.of(one.get(), two.get())).containsExactlyInAnyOrder(200, 409);
                }
                JdbcTemplate jdbc = first.getBean(JdbcTemplate.class);
                String hash =
                        jdbc.queryForObject("select password_hash from app_user where username='alice'", String.class);
                assertThat(hash).startsWith("{bcrypt}").doesNotContain("secret123");
                error(
                        send(
                                first,
                                "POST",
                                "auth/login",
                                anonymous,
                                token,
                                credentials.replace("secret123", "wrong1234")),
                        401);
                error(
                        send(first, "POST", "auth/login", anonymous, token, credentials.replace("alice", "missing")),
                        401);
                jdbc.update("update app_user set status='DISABLED' where username='alice'");
                error(send(first, "POST", "auth/login", anonymous, token, credentials), 401);
                jdbc.update("update app_user set status='ACTIVE' where username='alice'");
                HttpResponse<String> login = send(first, "POST", "auth/login", anonymous, token, credentials);
                assertThat(login.statusCode()).isEqualTo(200);
                String authenticated = cookie(login);
                assertThat(authenticated).isNotEqualTo(anonymous);
                String sessionId = new String(
                        java.util.Base64.getDecoder().decode(authenticated.substring("SESSION=".length())),
                        java.nio.charset.StandardCharsets.UTF_8);
                org.springframework.session.SessionRepository<?> repository =
                        second.getBean(org.springframework.session.SessionRepository.class);
                org.springframework.session.Session saved = repository.findById(sessionId);
                org.springframework.security.core.context.SecurityContext context = saved.getAttribute(
                        org.springframework.security.web.context.HttpSessionSecurityContextRepository
                                .SPRING_SECURITY_CONTEXT_KEY);
                assertThat(context.getAuthentication().getCredentials()).isNull();
                assertThat(context.getAuthentication().getPrincipal())
                        .isInstanceOf(com.lnjecit.knowledge.assistant.auth.service.AuthPrincipal.class);
                assertThat(send(second, "GET", "knowledge-bases", authenticated, null, null)
                                .statusCode())
                        .isEqualTo(200);
                error(send(first, "GET", "auth/me", anonymous, null, null), 401);
                HttpResponse<String> me = send(second, "GET", "auth/me", authenticated, null, null);
                assertThat(me.statusCode()).isEqualTo(200);
                assertThat(me.body()).contains("alice").doesNotContain("password", "secret123", hash);
                assertThat(json.readTree(me.body()).path("data").path("id").isString())
                        .isTrue();
                error(send(second, "POST", "auth/logout", authenticated, null, null), 403);
                error(send(second, "POST", "auth/logout", authenticated, token, null), 403);
                verifyKnowledgeBaseOwnership(second, jdbc, authenticated);
                String fresh = token(send(second, "GET", "auth/csrf", authenticated, null, null));
                HttpResponse<String> logout = send(second, "POST", "auth/logout", authenticated, fresh, null);
                assertThat(logout.statusCode()).isEqualTo(200);
                assertThat(logout.headers().allValues("Set-Cookie").toString()).contains("Max-Age=0");
                error(send(first, "GET", "auth/me", authenticated, null, null), 401);
                assertThat(send(first, "GET", "auth/csrf", authenticated, null, null)
                                .statusCode())
                        .isEqualTo(200);
            }
        }
    }
}
