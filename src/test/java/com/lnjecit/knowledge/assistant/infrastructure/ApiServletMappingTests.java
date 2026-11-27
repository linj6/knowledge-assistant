package com.lnjecit.knowledge.assistant.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lnjecit.knowledge.assistant.document.controller.DocumentQueryController;
import com.lnjecit.knowledge.assistant.document.dto.DocumentListResponse;
import com.lnjecit.knowledge.assistant.document.service.DocumentQueryService;
import com.lnjecit.knowledge.assistant.infrastructure.storage.OriginalFileStore;
import com.lnjecit.knowledge.assistant.infrastructure.web.ApiJacksonConfiguration;
import com.lnjecit.knowledge.assistant.knowledge.controller.KnowledgeBaseController;
import com.lnjecit.knowledge.assistant.knowledge.service.KnowledgeBaseService;
import java.net.URI;
import java.net.http.HttpClient;
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

class ApiServletMappingTests {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(
            excludeName = {
                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
            })
    @Import({
        KnowledgeBaseController.class,
        DocumentQueryController.class,
        ApiJacksonConfiguration.class,
        BusinessHttpTestSecurity.class
    })
    static class ApiApplication {
        @Bean
        KnowledgeBaseService knowledgeBases() {
            KnowledgeBaseService service = mock(KnowledgeBaseService.class);
            when(service.list(42L)).thenReturn(List.of());
            return service;
        }

        @Bean
        OriginalFileStore originalFileStore() {
            return mock(OriginalFileStore.class);
        }

        @Bean
        DocumentQueryService documents() {
            DocumentQueryService service = mock(DocumentQueryService.class);
            when(service.list(1L, 1, 20, null, null)).thenReturn(new DocumentListResponse(List.of(), 1, 20, 0L));
            return service;
        }
    }

    @Test
    void verifiesRealServletMappingWithoutBusinessInfrastructure() throws Exception {
        SpringApplication application = new SpringApplication(ApiApplication.class);
        try (ConfigurableApplicationContext context =
                application.run("--server.port=0", "--spring.mvc.servlet.path=/api")) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            HttpClient client = HttpClient.newHttpClient();
            for (String path :
                    new String[] {"/api/v1/knowledge-bases", "/api/v1/knowledge-bases/1/documents?page=1&pageSize=20"
                    }) {
                HttpResponse<String> response = client.send(
                        BusinessHttpTestSecurity.request(context, URI.create("http://localhost:" + port + path))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), path);
                assertTrue(response.body().contains("\"code\":0"), path);
                if (path.contains("documents")) assertTrue(response.body().contains("\"total\":\"0\""));
            }
        }
    }
}
