package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class EmbeddingSnapshotTests {
    @TempDir
    Path directory;

    @Test
    void strictOfflineProtocol() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        EmbeddingSnapshot.Manifest manifest = new EmbeddingSnapshot.Manifest(
                1, "bailian", "text-embedding-v4", 1024, "FLOAT", "config", "samples", "chunks");
        EmbeddingSnapshot.Input input = new EmbeddingSnapshot.Input(
                "QUERY", "Q01", null, null, null, null, null, EmbeddingSnapshot.sha("query\r\n"));
        Map<EmbeddingSnapshot.Input, String> inputs = Map.of(input, "query\r\n");
        Path header = directory.resolve("manifest.json");
        Path entries = directory.resolve("vectors.jsonl");
        Files.writeString(header, json.writeValueAsString(manifest));
        float[] vector = new float[1024];
        vector[0] = 1;
        String valid = json.writeValueAsString(new EmbeddingSnapshot.Entry(input, vector));
        Files.writeString(entries, valid);
        assertThat(EmbeddingSnapshot.load(header, entries, manifest, inputs)).hasSize(1);
        QueryEmbeddingCheck(header, entries, manifest, inputs);
        // 任一协议错误均必须终止，加载器没有模型依赖。
        for (float[] invalid : new float[][] {new float[1023], new float[1024], nonfinite()}) {
            Files.writeString(entries, json.writeValueAsString(new EmbeddingSnapshot.Entry(input, invalid)));
            assertThatThrownBy(() -> EmbeddingSnapshot.load(header, entries, manifest, inputs))
                    .isInstanceOf(Exception.class);
        }
        Files.writeString(entries, "");
        assertThatThrownBy(() -> EmbeddingSnapshot.load(header, entries, manifest, inputs))
                .isInstanceOf(IllegalArgumentException.class);
        vector[0] = 2;
        Files.writeString(entries, valid + "\n" + json.writeValueAsString(new EmbeddingSnapshot.Entry(input, vector)));
        assertThatThrownBy(() -> EmbeddingSnapshot.load(header, entries, manifest, inputs))
                .isInstanceOf(IllegalArgumentException.class);
        Files.writeString(entries, valid);
        assertThatThrownBy(() -> EmbeddingSnapshot.load(header, entries, manifest, Map.of(input, "query\n")))
                .isInstanceOf(IllegalArgumentException.class);
        for (EmbeddingSnapshot.Manifest wrong : new EmbeddingSnapshot.Manifest[] {
            new EmbeddingSnapshot.Manifest(1, "bailian", "other", 1024, "FLOAT", "config", "samples", "chunks"),
            new EmbeddingSnapshot.Manifest(
                    1, "bailian", "text-embedding-v4", 512, "FLOAT", "config", "samples", "chunks")
        }) {
            Files.writeString(header, json.writeValueAsString(wrong));
            assertThatThrownBy(() -> EmbeddingSnapshot.load(header, entries, manifest, inputs))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static float[] nonfinite() {
        float[] values = new float[1024];
        values[0] = Float.NaN;
        return values;
    }

    private static void QueryEmbeddingCheck(
            Path header, Path entries, EmbeddingSnapshot.Manifest manifest, Map<EmbeddingSnapshot.Input, String> inputs)
            throws Exception {
        com.lnjecit.knowledge.assistant.retrieval.embedding.QueryEmbeddingService offline =
                EmbeddingSnapshot.queries(EmbeddingSnapshot.load(header, entries, manifest, inputs), inputs);
        assertThat(offline.embed("query\r\n").values()).hasSize(1024);
        assertThatThrownBy(() -> offline.embed("missing")).isInstanceOf(IllegalArgumentException.class);
    }
}
