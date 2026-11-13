package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingConfig;
import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingProperties;
import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingStrategy;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;
import tools.jackson.databind.json.JsonMapper;

class ChunkingStrategyTests {
    @Test
    void oldSnapshotAndEnumRoundTrip() {
        JsonMapper json = JsonMapper.builder().build();
        String old = "{\"version\":1,\"strategy\":\"FIXED_CODE_POINT\",\"chunkSize\":800,\"chunkOverlap\":120}";
        ChunkingConfig config = json.readValue(old, ChunkingConfig.class);
        assertThat(config.strategy()).isEqualTo(ChunkingStrategy.FIXED_CODE_POINT);
        assertThat(json.readTree(json.writeValueAsString(config))).isEqualTo(json.readTree(old));
        assertThat(json.readValue(json.writeValueAsString(config), ChunkingConfig.class))
                .isEqualTo(config);
        assertThatThrownBy(() -> json.readValue(old.replace("FIXED_CODE_POINT", "UNKNOWN"), ChunkingConfig.class))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void yamlBindingAndUnknownStrategy() throws Exception {
        String yaml =
                "assistant:\n  chunking:\n    version: 1\n    strategy: FIXED_CODE_POINT\n    chunk-size: 800\n    chunk-overlap: 120\n";
        ChunkingProperties properties = bind(yaml);
        properties.afterPropertiesSet();
        assertThat(properties.snapshot()).isEqualTo(new ChunkingConfig(1, ChunkingStrategy.FIXED_CODE_POINT, 800, 120));
        assertThatThrownBy(() -> bind(yaml.replace("FIXED_CODE_POINT", "UNKNOWN")))
                .isInstanceOf(org.springframework.boot.context.properties.bind.BindException.class);
    }

    private ChunkingProperties bind(String yaml) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(new YamlPropertySourceLoader()
                        .load("test", new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)))
                        .getFirst());
        return Binder.get(environment)
                .bind("assistant.chunking", ChunkingProperties.class)
                .get();
    }
}
