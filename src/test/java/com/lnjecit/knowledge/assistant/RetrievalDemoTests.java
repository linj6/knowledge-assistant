package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class RetrievalDemoTests {
    @Configuration(proxyBeanMethods = false)
    @Import({
        TomcatServletWebServerAutoConfiguration.class,
        DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class
    })
    static class WebConfiguration {}

    @Test
    void servesDemoUnderServletPrefixWithSameOriginApi() throws Exception {
        // 使用真实嵌入式服务器核验 Servlet 前缀，不连接数据库或模型。
        try (ServletWebServerApplicationContext context = (ServletWebServerApplicationContext) SpringApplication.run(
                WebConfiguration.class, "--server.port=0", "--spring.config.location=classpath:/application.yml")) {
            String base = "http://localhost:" + context.getWebServer().getPort();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/retrieval-demo.html"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/html");
            assertThat(response.body())
                    .contains("fetch('/api/v1/retrieval/search'", "el.textContent", "credentials:'same-origin'")
                    .doesNotContain("innerHTML", "localStorage", "https://", "http://");
            assertThat(client.send(
                                    HttpRequest.newBuilder(URI.create(base + "/retrieval-demo.html"))
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .statusCode())
                    .isEqualTo(404);
        }
    }
}
