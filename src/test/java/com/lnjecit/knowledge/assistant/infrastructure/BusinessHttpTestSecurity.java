package com.lnjecit.knowledge.assistant.infrastructure;

import com.lnjecit.knowledge.assistant.auth.service.AuthPrincipal;
import com.lnjecit.knowledge.assistant.infrastructure.web.CsrfController;
import com.lnjecit.knowledge.assistant.infrastructure.web.RequestIdFilter;
import com.lnjecit.knowledge.assistant.infrastructure.web.SecurityConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;

/** 仅测试使用：直接写入模拟身份到真实 Redis，不新增登录接口或绕过 CSRF。 */
@TestConfiguration(proxyBeanMethods = false)
@Import({SecurityConfiguration.class, CsrfController.class, RequestIdFilter.class})
public class BusinessHttpTestSecurity {
    @Bean(destroyMethod = "stop")
    GenericContainer<?> businessRedis() {
        GenericContainer<?> redis =
                new GenericContainer<>("redis:7.4.11").withExposedPorts(6379).withImagePullPolicy(image -> false);
        redis.start();
        return redis;
    }

    @Bean
    LettuceConnectionFactory redisConnectionFactory(GenericContainer<?> businessRedis) {
        return new LettuceConnectionFactory(businessRedis.getHost(), businessRedis.getMappedPort(6379));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static HttpRequest.Builder request(ConfigurableApplicationContext context, URI uri) throws Exception {
        SessionRepository repository = context.getBean(SessionRepository.class);
        Session session = repository.createSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
                        new AuthPrincipal(42L, "business-test-user"),
                        null,
                        AuthorityUtils.createAuthorityList("ROLE_USER"))));
        repository.save(session);
        String cookie =
                "SESSION=" + Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8));
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        HttpResponse<String> csrf = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/csrf"))
                                .header("Cookie", cookie)
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        if (csrf.statusCode() != 200) throw new IllegalStateException("测试 CSRF 获取失败");
        String token = context.getBean(JsonMapper.class)
                .readTree(csrf.body())
                .path("data")
                .path("token")
                .asString();
        return HttpRequest.newBuilder(uri).header("Cookie", cookie).header("X-CSRF-TOKEN", token);
    }
}
