package com.lnjecit.knowledge.assistant.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.lnjecit.knowledge.assistant.infrastructure.web.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.web.bind.annotation.*;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;

class AuthSecurityHttpTests {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(
            excludeName = {
                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
            })
    @Import({SecurityConfiguration.class, CsrfController.class, RequestIdFilter.class, Probe.class})
    static class TestApplication {}

    @RestController
    static class Probe {
        final AtomicInteger mutations = new AtomicInteger();

        @GetMapping("/v1/probe")
        String get() {
            return "allowed";
        }

        @RequestMapping(
                value = "/v1/probe",
                method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
        String mutate() {
            mutations.incrementAndGet();
            return "changed";
        }
    }

    private ConfigurableApplicationContext start(int redisPort, String namespace) {
        return new SpringApplication(TestApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.config.location=classpath:/",
                        "--spring.data.redis.host=localhost",
                        "--spring.data.redis.port=" + redisPort,
                        "--spring.session.data.redis.namespace=" + namespace,
                        "--assistant.model.enabled=false",
                        "--assistant.rerank.enabled=false");
    }

    private HttpResponse<String> request(
            ConfigurableApplicationContext context, String method, String path, String cookie, String token)
            throws Exception {
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Request-Id", "auth02-test")
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (cookie != null) builder.header("Cookie", cookie);
        if (token != null) builder.header("X-CSRF-TOKEN", token);
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void error(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("X-Request-Id")).contains("auth02-test");
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        assertThat(response.body()).contains("\"code\":" + status * 100, "\"requestId\":\"auth02-test\"");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void realHttpAndSharedRedisSessions() throws Exception {
        try (GenericContainer<?> redis =
                new GenericContainer<>("redis:7.4.11").withExposedPorts(6379).withImagePullPolicy(image -> false)) {
            redis.start();
            String namespace = "auth02:" + UUID.randomUUID();
            try (ConfigurableApplicationContext first = start(redis.getMappedPort(6379), namespace);
                    ConfigurableApplicationContext second = start(redis.getMappedPort(6379), namespace)) {
                assertThat(first.getBean(SessionRepository.class)).isInstanceOf(RedisSessionRepository.class);
                assertThat(first.getBeansOfType(org.springframework.security.core.userdetails.UserDetailsService.class))
                        .isEmpty();
                error(request(first, "GET", "/api/v1/probe", null, null), 401);
                for (String path : new String[] {"/api/v1/unknown", "/api/v1/unknown.js", "/v1/probe", "/unknown"})
                    error(request(first, "GET", path, null, null), 401);
                error(request(first, "POST", "/api/v1/probe", null, null), 403);
                assertThat(first.getBean(Probe.class).mutations).hasValue(0);
                for (String path : new String[] {
                    "/api/", "/api/index.html", "/api/retrieval-demo.html", "/api/css/app.css", "/api/js/app.js"
                })
                    assertThat(request(first, "GET", path, null, null).statusCode())
                            .isEqualTo(200);
                HttpResponse<String> csrf = request(first, "GET", "/api/v1/auth/csrf", null, null);
                assertThat(csrf.statusCode()).isEqualTo(200);
                assertThat(csrf.headers().firstValue("Cache-Control").orElse(""))
                        .contains("no-store");
                String setCookie = csrf.headers().firstValue("Set-Cookie").orElseThrow();
                assertThat(setCookie).contains("HttpOnly");
                String anonymousCookie = setCookie.split(";", 2)[0];
                String token = JsonMapper.builder()
                        .build()
                        .readTree(csrf.body())
                        .path("data")
                        .path("token")
                        .asString();
                error(request(second, "POST", "/api/v1/probe", anonymousCookie, token), 401);

                SessionRepository repository = first.getBean(SessionRepository.class);
                Session session = repository.createSession();
                assertThat(session.getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(30));
                session.setAttribute(
                        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                        new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
                                "auth02-test-user", null, AuthorityUtils.createAuthorityList("ROLE_USER"))));
                repository.save(session);
                assertThat(first.getBean(org.springframework.data.redis.core.StringRedisTemplate.class)
                                .hasKey(namespace + ":sessions:" + session.getId()))
                        .isTrue();
                String cookie = "SESSION="
                        + Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8));
                assertThat(second.getBean(SessionRepository.class).findById(session.getId()))
                        .isNotNull();
                assertThat(request(first, "GET", "/api/v1/probe", cookie, null).statusCode())
                        .isEqualTo(200);
                assertThat(request(second, "GET", "/api/v1/probe", cookie, null).statusCode())
                        .isEqualTo(200);
                error(request(second, "GET", "/api/v1/evaluation/runs", cookie, null), 403);
                csrf = request(first, "GET", "/api/v1/auth/csrf", cookie, null);
                token = JsonMapper.builder()
                        .build()
                        .readTree(csrf.body())
                        .path("data")
                        .path("token")
                        .asString();
                for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
                    error(request(second, method, "/api/v1/probe", cookie, null), 403);
                    error(request(second, method, "/api/v1/probe", cookie, "invalid-token"), 403);
                    assertThat(request(second, method, "/api/v1/probe", cookie, token)
                                    .statusCode())
                            .isEqualTo(200);
                }
                assertThat(second.getBean(Probe.class).mutations).hasValue(4);
                Session expiring = (Session) repository.findById(session.getId());
                expiring.setMaxInactiveInterval(Duration.ofSeconds(1));
                repository.save(expiring);
                Thread.sleep(1500);
                error(request(second, "GET", "/api/v1/probe", cookie, null), 401);
                // 新建仍有效的身份；停掉真实 Redis 后不能继续从 JVM 读取该身份。
                Session live = repository.createSession();
                live.setAttribute(
                        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                        new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
                                "auth02-live-user", null, AuthorityUtils.createAuthorityList("ROLE_USER"))));
                repository.save(live);
                String liveCookie = "SESSION="
                        + Base64.getEncoder().encodeToString(live.getId().getBytes(StandardCharsets.UTF_8));
                assertThat(request(second, "GET", "/api/v1/probe", liveCookie, null)
                                .statusCode())
                        .isEqualTo(200);
                redis.stop();
                assertThat(request(second, "GET", "/api/v1/probe", liveCookie, null)
                                .statusCode())
                        .isGreaterThanOrEqualTo(500);
            }
        }
    }
}
