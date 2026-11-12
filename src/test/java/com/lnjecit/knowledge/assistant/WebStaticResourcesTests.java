package com.lnjecit.knowledge.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lnjecit.knowledge.assistant.document.DocumentListResponse;
import com.lnjecit.knowledge.assistant.document.DocumentQueryController;
import com.lnjecit.knowledge.assistant.document.DocumentQueryService;
import com.lnjecit.knowledge.assistant.infrastructure.web.ApiJacksonConfiguration;
import com.lnjecit.knowledge.assistant.knowledge.KnowledgeBaseController;
import com.lnjecit.knowledge.assistant.knowledge.KnowledgeBaseService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class WebStaticResourcesTests {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(
            excludeName = {
                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
            })
    @Import({KnowledgeBaseController.class, DocumentQueryController.class, ApiJacksonConfiguration.class})
    static class StaticApplication {
        @Bean
        KnowledgeBaseService knowledgeBases() {
            KnowledgeBaseService service = mock(KnowledgeBaseService.class);
            when(service.list()).thenReturn(List.of());
            return service;
        }

        @Bean
        DocumentQueryService documents() {
            DocumentQueryService service = mock(DocumentQueryService.class);
            when(service.list(1L, 1, 20)).thenReturn(new DocumentListResponse(List.of(), 1, 20, 0L));
            return service;
        }
    }

    @Test
    void verifiesRealServletMappingWithoutBusinessInfrastructure() throws Exception {
        SpringApplication application = new SpringApplication(StaticApplication.class);
        try (ConfigurableApplicationContext context =
                application.run("--server.port=0", "--spring.mvc.servlet.path=/api")) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            HttpClient client = HttpClient.newHttpClient();
            for (String path : new String[] {
                "/api/index.html",
                "/api/retrieval-demo.html",
                "/api/css/app.css",
                "/api/js/app.js",
                "/api/js/api.js",
                "/api/js/state.js",
                "/api/js/knowledge.js",
                "/api/js/chat.js",
                "/api/js/evidence.js",
                "/api/js/import.js",
                "/api/js/retrieval.js"
            }) {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), path);
                assertTrue(!response.body().isBlank(), path);
                if (path.endsWith(".html") || path.endsWith(".css")) {
                    String mime = response.headers().firstValue("Content-Type").orElse("");
                    assertTrue(mime.contains(path.endsWith(".css") ? "text/css" : "text/html"), path + " " + mime);
                }
                if (path.endsWith(".js")) {
                    String mime = response.headers().firstValue("Content-Type").orElse("");
                    assertTrue(mime.contains("javascript"), path + " " + mime);
                }
            }
            for (String path :
                    new String[] {"/api/v1/knowledge-bases", "/api/v1/knowledge-bases/1/documents?page=1&pageSize=20"
                    }) {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), path);
                assertTrue(response.body().contains("\"code\":0"), path);
                if (path.contains("documents")) assertTrue(response.body().contains("\"total\":\"0\""));
            }
            for (String path : new String[] {"/", "/index.html"}) {
                assertEquals(
                        404,
                        client.send(
                                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                                                .build(),
                                        HttpResponse.BodyHandlers.discarding())
                                .statusCode(),
                        path);
            }
            HttpResponse<String> welcome = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, welcome.statusCode());
        }
    }
}
