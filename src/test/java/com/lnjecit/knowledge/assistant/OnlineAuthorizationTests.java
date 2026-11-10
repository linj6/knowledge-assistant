package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

@ExtendWith(OutputCaptureExtension.class)
class OnlineAuthorizationTests {
    @TempDir
    Path directory;

    static StandardEnvironment environment(Map<String, ?> values) {
        StandardEnvironment environment = new StandardEnvironment();
        // 隔离操作者的环境和私有配置；只使用固定假密钥。
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("test", new HashMap<>(values)));
        return environment;
    }

    private Map<String, Object> complete() {
        return new HashMap<>(Map.of(
                "assistant.model.enabled", true,
                "assistant.model.api-key", "FAKE_LOCAL_SECRET",
                "assistant.model.chat-model", "fake-chat",
                "assistant.model.embedding-model", "text-embedding-v4",
                "assistant.model.dimensions", 1024));
    }

    @Test
    void defaultAndCompleteConfigurationStillRequireAuthorization() {
        assertThatThrownBy(() -> OnlineConfiguration.model(environment(Map.of())))
                .hasMessage("Explicit online authorization required");
        assertThatThrownBy(() -> OnlineConfiguration.model(environment(complete())))
                .hasMessage("Explicit online authorization required");
        assertThatThrownBy(() -> OnlineConfiguration.rerank(environment(Map.of())))
                .hasMessage("Explicit online authorization required");
    }

    @Test
    void safeMissingKeyAndBinding(CapturedOutput output) {
        Map<String, Object> values = complete();
        values.put("assistant.model.online-authorized", true);
        ModelProperties properties = OnlineConfiguration.model(environment(values));
        assertThat(properties.getEmbeddingModel()).isEqualTo("text-embedding-v4");
        assertThat(properties.getDimensions()).isEqualTo(1024);
        values.remove("assistant.model.api-key");
        assertThatThrownBy(() -> OnlineConfiguration.model(environment(values)))
                .hasMessage("assistant.model.api-key is required when enabled");
        values.put("assistant.model.dimensions", "FAKE_LOCAL_SECRET");
        assertThatThrownBy(() -> OnlineConfiguration.model(environment(values)))
                .hasMessage("Invalid assistant.model configuration")
                .hasNoCause();
        assertThat(output.getAll()).doesNotContain("FAKE_LOCAL_SECRET");
    }

    @Test
    void baselineRequiresExtraAuthorizationAndFixedSettings() {
        Map<String, Object> values = complete();
        values.put("assistant.model.online-authorized", true);
        assertThatThrownBy(() -> OnlineConfiguration.baseline(environment(values)))
                .hasMessage("Explicit embedding baseline authorization required");
        values.put("assistant.embedding.baseline-authorized", true);
        assertThat(OnlineConfiguration.baseline(environment(values)).getTimeoutSeconds())
                .isEqualTo(20);
        values.put("assistant.model.embedding-model", "wrong-model");
        assertThatThrownBy(() -> OnlineConfiguration.baseline(environment(values)))
                .hasMessage("Embedding baseline requires fixed model, dimensions and endpoint");
    }

    @Test
    void publicDefaultsDisableAllOnlineAuthorization() {
        StandardEnvironment environment =
                OnlineConfiguration.load(environment(Map.of("spring.config.location", "classpath:/application.yml")));
        assertThat(environment.getProperty("assistant.model.online-authorized", Boolean.class))
                .isFalse();
        assertThat(environment.getProperty("assistant.embedding.baseline-authorized", Boolean.class))
                .isFalse();
        assertThat(environment.getProperty("assistant.rerank.online-authorized", Boolean.class))
                .isFalse();
    }

    @Test
    void malformedYamlAndAuthorizationCannotExposeValues(CapturedOutput output) throws Exception {
        Files.writeString(directory.resolve("application.yml"), "broken: [FAKE_LOCAL_SECRET");
        assertThatThrownBy(() -> OnlineConfiguration.load(environment(
                        Map.of("spring.config.location", directory.toUri().toString()))))
                .hasMessage("Unable to load online configuration; check YAML and profile")
                .hasNoCause();
        assertThatThrownBy(() -> OnlineConfiguration.authorize(
                        environment(Map.of("assistant.model.enabled", "FAKE_LOCAL_SECRET")), "assistant.model"))
                .hasMessage("Invalid online authorization configuration")
                .hasNoCause();
        assertThat(output.getAll()).doesNotContain("FAKE_LOCAL_SECRET");
    }

    @Test
    void bootLoadsLocalOnlyWhenActivated(CapturedOutput output) throws Exception {
        Files.writeString(directory.resolve("application.yml"), "assistant.model.enabled: false\n");
        Files.writeString(
                directory.resolve("application-local.yml"),
                "assistant.model.api-key: FAKE_LOCAL_SECRET\nassistant.model.embedding-model: text-embedding-v4\nassistant.model.dimensions: 1024\n");
        Map<String, Object> values = new HashMap<>();
        values.put("spring.config.location", directory.toUri().toString());
        StandardEnvironment inactive = OnlineConfiguration.load(environment(values));
        assertThat(inactive.getProperty("assistant.model.api-key")).isNull();
        assertThat(inactive.getProperty("assistant.model.online-authorized", Boolean.class, false))
                .isFalse();
        values.put("spring.profiles.active", "local");
        StandardEnvironment active = OnlineConfiguration.load(environment(values));
        assertThat(active.getProperty("assistant.model.api-key")).isEqualTo("FAKE_LOCAL_SECRET");
        assertThat(active.getProperty("assistant.model.dimensions", Integer.class))
                .isEqualTo(1024);
        assertThat(output.getAll()).doesNotContain("FAKE_LOCAL_SECRET");
    }
}
