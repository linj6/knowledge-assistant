package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

class AuthBusinessHttpTests {
    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    private ConfigurableApplicationContext start(PostgreSQLContainer pg, int redis, String namespace) {
        return new SpringApplication(KnowledgeAssistantApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + pg.getJdbcUrl(),
                        "--spring.datasource.username=" + pg.getUsername(),
                        "--spring.datasource.password=" + pg.getPassword(),
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
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).contains("\"code\":" + status * 100, "\"requestId\":\"auth03-test\"");
        assertThat(response.headers().firstValue("X-Request-Id")).contains("auth03-test");
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
