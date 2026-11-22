package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelConfiguration;
import com.lnjecit.knowledge.assistant.infrastructure.ai.RerankCandidate;
import com.lnjecit.knowledge.assistant.infrastructure.ai.RerankConfiguration;
import com.lnjecit.knowledge.assistant.infrastructure.ai.RerankResult;
import com.lnjecit.knowledge.assistant.infrastructure.ai.Reranker;
import com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties;
import com.lnjecit.knowledge.assistant.retrieval.rerank.RerankProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

class ProfileConfigurationTests {
    @TempDir
    Path directory;

    private StandardEnvironment load(String profile, boolean external) {
        Map<String, Object> values = new HashMap<>();
        // 固定搜索位置，避免读取操作者的真实私有文件；顺序与 Boot 默认规则一致。
        values.put("spring.config.location", "classpath:/" + (external ? "," + directory.toUri() : ""));
        if (profile != null) values.put("spring.profiles.active", profile);
        return OnlineConfiguration.load(OnlineAuthorizationTests.environment(values));
    }

    @Test
    void commonAndLocalDefaults() {
        StandardEnvironment common = load(null, false);
        assertThat(common.getProperty("spring.application.name")).isEqualTo("knowledge-assistant");
        assertThat(common.getProperty("assistant.model.enabled", Boolean.class)).isFalse();
        assertThat(common.getProperty("assistant.model.online-authorized", Boolean.class))
                .isFalse();
        assertThat(common.getProperty("spring.datasource.url")).isNull();
        ModelProperties commonModel = Binder.get(common)
                .bind("assistant.model", ModelProperties.class)
                .get();
        assertThat(commonModel.getEmbeddingModel()).isEqualTo("text-embedding-v4");
        assertThat(commonModel.getDimensions()).isEqualTo(1024);
        StandardEnvironment local = load("local", false);
        assertThat(local.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://localhost:5432/ka");
        ModelProperties properties =
                Binder.get(local).bind("assistant.model", ModelProperties.class).get();
        assertThat(properties.getEmbeddingModel()).isEqualTo("text-embedding-v4");
        assertThat(properties.getDimensions()).isEqualTo(1024);
        assertThat(properties.getChatModel()).isEqualTo("qwen-plus");
        assertThat(properties.getBaseUrl()).isEqualTo(EmbeddingBaselineOnlineIT.ENDPOINT);
        assertThat(properties.isEnabled()).isFalse();
        assertThat(local.getProperty("assistant.model.online-authorized", Boolean.class))
                .isFalse();
    }

    @Test
    void localProfileAloneDoesNotAuthorizeOnlineTests() {
        StandardEnvironment local = load("local", false);
        assertThat(local.getProperty("assistant.rerank.enabled", Boolean.class)).isFalse();
        assertThat(local.getProperty("assistant.rerank.online-authorized", Boolean.class))
                .isFalse();
        assertThatThrownBy(() -> OnlineConfiguration.model(local)).hasMessage("Explicit online authorization required");
        assertThatThrownBy(() -> OnlineConfiguration.rerank(local))
                .hasMessage("Explicit online authorization required");
    }

    @Test
    void clientsOnlyInitializeAndCallStubWithBothFlags() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        Map<String, String> responses = Map.of(
                "/v1/chat/completions",
                """
                {"id":"fake","object":"chat.completion","created":1,"model":"fake-chat","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                """,
                "/v1/embeddings",
                """
                {"object":"list","model":"fake-embedding","data":[{"object":"embedding","index":0,"embedding":[1,2]}],"usage":{"prompt_tokens":1,"total_tokens":1}}
                """,
                "/rerank",
                """
                {"output":{"results":[{"index":0,"relevance_score":0.9}]}}
                """);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = responses.get(exchange.getRequestURI().getPath()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            ModelProperties model = ModelCapabilityTests.properties(url + "/v1");
            // 测试专用属性允许 HTTP Stub，生产配置仍校验 HTTPS。
            RerankProperties rerank = org.mockito.Mockito.mock(RerankProperties.class);
            org.mockito.Mockito.when(rerank.getEndpoint()).thenReturn(url + "/rerank");
            org.mockito.Mockito.when(rerank.getApiKey()).thenReturn("offline-test-value");
            org.mockito.Mockito.when(rerank.getModel()).thenReturn("qwen3-rerank");
            org.mockito.Mockito.when(rerank.getTimeoutSeconds()).thenReturn(2);

            ApplicationContextRunner runner = new ApplicationContextRunner()
                    .withInitializer(context -> {
                        context.getEnvironment()
                                .getPropertySources()
                                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                        context.getEnvironment()
                                .getPropertySources()
                                .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                        load("local", false).getPropertySources().forEach(source -> {
                            if (source.getName().startsWith("Config resource")) {
                                context.getEnvironment().getPropertySources().addLast(source);
                            }
                        });
                    })
                    .withUserConfiguration(ModelConfiguration.class, RerankConfiguration.class)
                    .withBean(ModelProperties.class, () -> model)
                    .withBean(RerankProperties.class, () -> rerank);
            assertClientsDisabled(runner);
            ApplicationContextRunner enabled =
                    runner.withPropertyValues("assistant.model.enabled=true", "assistant.rerank.enabled=true");
            assertClientsDisabled(enabled.withPropertyValues(
                    "assistant.model.online-authorized=false", "assistant.rerank.online-authorized=false"));
            assertThat(requests.get()).isZero();

            enabled.withPropertyValues(
                            "assistant.model.online-authorized=true", "assistant.rerank.online-authorized=true")
                    .run(context -> {
                        assertThat(context.getBean(OpenAiChatModel.class).call("test"))
                                .isEqualTo("ok");
                        assertThat(context.getBean(OpenAiEmbeddingModel.class).embed("test"))
                                .containsExactly(1, 2);
                        assertThat(context.getBean(Reranker.class)
                                        .rerank("test", List.of(new RerankCandidate("A", "test")), 1))
                                .extracting(RerankResult::id)
                                .containsExactly("A");
                    });
            assertThat(requests.get()).isEqualTo(3);
        } finally {
            server.stop(0);
        }
    }

    private void assertClientsDisabled(ApplicationContextRunner runner) {
        runner.run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(OpenAiChatModel.class)
                .doesNotHaveBean(OpenAiEmbeddingModel.class)
                .doesNotHaveBean(Reranker.class));
    }

    @Test
    void embeddingConfigurationCanBeOverriddenOffline() throws Exception {
        Files.writeString(
                directory.resolve("application-local.yml"),
                "assistant.model.embedding-model: overridden-model\nassistant.model.dimensions: 768\n");
        StandardEnvironment local = load("local", true);
        ModelProperties properties =
                Binder.get(local).bind("assistant.model", ModelProperties.class).get();
        assertThat(properties.getEmbeddingModel()).isEqualTo("overridden-model");
        assertThat(properties.getDimensions()).isEqualTo(768);
        assertThat(properties.isEnabled()).isFalse();
        assertThat(local.getProperty("assistant.model.online-authorized", Boolean.class))
                .isFalse();
    }

    @Test
    void externalOverridesClasspathLocalButNotOtherProfiles() throws Exception {
        Files.writeString(
                directory.resolve("application-local.yml"),
                "assistant.model.enabled: true\nassistant.model.online-authorized: true\nassistant.model.api-key: FAKE_PRIVATE_KEY\nassistant.model.chat-model: personal-chat\nassistant.rerank.enabled: true\nassistant.rerank.online-authorized: true\nassistant.rerank.api-key: FAKE_RERANK_KEY\nspring.datasource.password: FAKE_DB_PASSWORD\n");
        StandardEnvironment local = load("local", true);
        assertThat(local.getProperty("assistant.model.api-key")).isEqualTo("FAKE_PRIVATE_KEY");
        assertThat(OnlineConfiguration.model(local).getChatModel()).isEqualTo("personal-chat");
        assertThat(OnlineConfiguration.rerank(local).getModel()).isEqualTo("qwen3-rerank");
        assertThat(local.getProperty("assistant.model.dimensions", Integer.class))
                .isEqualTo(1024);
        for (String profile : new String[] {"test", "prod"}) {
            StandardEnvironment environment = load(profile, true);
            assertThat(environment.getProperty("assistant.model.api-key")).isEmpty();
            assertThat(environment.getProperty("assistant.model.enabled", Boolean.class))
                    .isFalse();
            assertThat(environment.getProperty("assistant.model.online-authorized", Boolean.class))
                    .isFalse();
            assertThat(environment.getProperty("assistant.embedding.baseline-authorized", Boolean.class))
                    .isFalse();
            assertThat(environment.getProperty("assistant.model.chat-model", ""))
                    .isEmpty();
        }
        StandardEnvironment common = load(null, true);
        assertThat(common.getProperty("assistant.model.api-key")).isEmpty();
        StandardEnvironment prod = load("prod", true);
        // 缺少部署变量时必须报错，不能落回本地数据库。
        assertThatThrownBy(() -> prod.getProperty("spring.datasource.url"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testKeepsDatabaseOverridesAvailable() {
        StandardEnvironment environment = load("test", false);
        assertThat(environment.getProperty("spring.autoconfigure.exclude"))
                .isEqualTo("org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration");
        environment
                .getPropertySources()
                .addFirst(new org.springframework.core.env.MapPropertySource(
                        "container", Map.of("spring.datasource.url", "jdbc:postgresql://container:5432/test")));
        assertThat(environment.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://container:5432/test");
    }

    @Test
    void chatAndEmbeddingValidateIndependentlyWithoutRequests() {
        ModelProperties properties = new ModelProperties();
        properties.setEnabled(true);
        assertThatThrownBy(() -> ModelConfiguration.validateEmbedding(properties))
                .hasMessage("assistant.model.api-key is required when enabled");
        properties.setApiKey("FAKE_PRIVATE_KEY");
        properties.setEmbeddingModel("text-embedding-v4");
        properties.setDimensions(1024);
        ModelConfiguration.validateEmbedding(properties);
        assertThatThrownBy(() -> ModelConfiguration.validateChat(properties)).hasMessageContaining("chat-model");
        properties.setChatModel("fake-chat");
        properties.setEmbeddingModel(null);
        properties.setDimensions(0);
        ModelConfiguration.validateChat(properties);
        assertThatThrownBy(() -> ModelConfiguration.validateEmbedding(properties))
                .hasMessageContaining("embedding-model");
    }

    @Test
    void gitIgnoreProtectsPrivateFileAndAllowsTrackedProfiles() throws Exception {
        Process process = new ProcessBuilder("git", "check-ignore", "--no-index", "config/application-local.yml")
                .redirectErrorStream(true)
                .start();
        assertThat(process.waitFor()).isZero();
        for (String path :
                new String[] {"src/main/resources/application-local.yml", "config/application-local.yml.example"}) {
            Process allowed = new ProcessBuilder("git", "check-ignore", "--no-index", path)
                    .redirectErrorStream(true)
                    .start();
            assertThat(allowed.waitFor()).isEqualTo(1);
        }
    }
}
