package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelConfiguration;
import com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
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
    }

    @Test
    void embeddingConfigurationCanBeOverriddenOffline() throws Exception {
        Files.writeString(
                directory.resolve("application-local.yml"),
                "assistant.model.embedding-model: overridden-model\nassistant.model.dimensions: 768\n");
        ModelProperties properties = Binder.get(load("local", true))
                .bind("assistant.model", ModelProperties.class)
                .get();
        assertThat(properties.getEmbeddingModel()).isEqualTo("overridden-model");
        assertThat(properties.getDimensions()).isEqualTo(768);
        assertThat(properties.isEnabled()).isFalse();
    }

    @Test
    void externalOverridesClasspathLocalButNotOtherProfiles() throws Exception {
        Files.writeString(
                directory.resolve("application-local.yml"),
                "assistant.model.api-key: FAKE_PRIVATE_KEY\nassistant.model.chat-model: personal-chat\nspring.datasource.password: FAKE_DB_PASSWORD\n");
        StandardEnvironment local = load("local", true);
        assertThat(local.getProperty("assistant.model.api-key")).isEqualTo("FAKE_PRIVATE_KEY");
        assertThat(local.getProperty("assistant.model.chat-model")).isEqualTo("personal-chat");
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
        assertThat(environment.getProperty("spring.autoconfigure.exclude")).isNull();
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
