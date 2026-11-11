package com.lnjecit.knowledge.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

class WebStaticResourcesTests {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(
            excludeName = {
                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
            })
    static class StaticApplication {}

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
                "/api/js/state.js"
            }) {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), path);
                assertTrue(!response.body().isBlank(), path);
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
            System.out.println("WEB-01 /api/ actual status: " + welcome.statusCode());
        }
    }
}
