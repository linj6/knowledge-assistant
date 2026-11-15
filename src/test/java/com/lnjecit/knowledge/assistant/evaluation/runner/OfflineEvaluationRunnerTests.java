package com.lnjecit.knowledge.assistant.evaluation.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class OfflineEvaluationRunnerTests {
    @TempDir
    Path temporary;

    @Test
    void deterministicArtifactsAndFrozenRanking() throws Exception {
        Path first = temporary.resolve("first"), second = temporary.resolve("second");
        OfflineEvaluationRunner.run(first, false);
        OfflineEvaluationRunner.run(second, false);
        for (String name : List.of("manifest.json", "metrics.json", "retrieval-results.jsonl", "report.md"))
            assertThat(Files.readAllBytes(first.resolve(name))).isEqualTo(Files.readAllBytes(second.resolve(name)));
        JsonMapper json = JsonMapper.builder().build();
        ObjectNode metrics = (ObjectNode) json.readTree(Files.readString(first.resolve("metrics.json")));
        JsonNode baseline =
                json.readTree(Files.readString(Path.of("docs/engineering/m4-b03-frozen-ranking-evaluation.json")));
        double[][] expected = {
            {7, 9, 7, 9, 7, 9, 337.0 / 60},
            {8.5, 10.5, 8, 10, 9, 11, 179.0 / 20},
            {8, 10, 8, 10, 8, 10, 159.0 / 20},
            {10, 10, 10, 10, 10, 10, 53.0 / 6},
            {11, 11.5, 11, 11, 11, 12, 43.0 / 4},
            {11, 11, 11, 11, 11, 11, 21.0 / 2}
        };
        int index = 0;
        for (JsonNode group : metrics.path("groups")) {
            assertThat(group.path("evidence").path("scoredCases").asInt()).isEqualTo(12);
            int metricIndex = 0;
            for (String metric : List.of("recall3", "recall5", "cec3", "cec5", "hit3", "hit5", "mrr5")) {
                JsonNode score = group.path("evidence").path(metric);
                assertThat(score.path("denominator").asInt()).isEqualTo(12);
                assertThat(score.path("numerator").asDouble())
                        .isCloseTo(expected[index][metricIndex++], org.assertj.core.data.Offset.offset(1e-12));
            }
            index++;
            ((ObjectNode) group).remove("evidence");
        }
        assertThat(metrics).isEqualTo(baseline);
        assertThat(Files.readAllLines(first.resolve("retrieval-results.jsonl"))).hasSize(72);
        assertThatThrownBy(() -> OfflineEvaluationRunner.run(first, false)).hasMessageContaining("OUTPUT_ERROR");
        OfflineEvaluationRunner.run(first, true);
        assertThat(Files.readAllBytes(first.resolve("manifest.json")))
                .isEqualTo(Files.readAllBytes(second.resolve("manifest.json")));
    }

    @Test
    void missingInputsFailWithoutSuccessArtifacts() throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String absoluteClasspath = java.util.Arrays.stream(classpath.split(java.io.File.pathSeparator))
                .map(p -> Path.of(p).toAbsolutePath().toString())
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        Path log = temporary.resolve("failure.log");
        Process process = new ProcessBuilder(
                        Path.of(
                                        System.getProperty("java.home"),
                                        "bin",
                                        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
                                .toString(),
                        "-cp",
                        absoluteClasspath,
                        OfflineEvaluationRunner.class.getName())
                .directory(temporary.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        assertThat(process.waitFor()).isNotZero();
        assertThat(Files.readString(log, java.nio.charset.StandardCharsets.ISO_8859_1))
                .contains("INPUT_ERROR");
        assertThat(Files.exists(temporary.resolve(OfflineEvaluationRunner.OUTPUT)))
                .isFalse();
    }

    @Test
    void invalidArgumentsAndOutputDoNotPublishSuccess() throws Exception {
        assertThatThrownBy(() -> OfflineEvaluationRunner.main(new String[] {"--live"}))
                .isInstanceOf(IllegalArgumentException.class);
        Path parent = temporary.resolve("file");
        Files.writeString(parent, "blocked");
        assertThatThrownBy(() -> OfflineEvaluationRunner.run(parent.resolve("output"), false))
                .hasMessageContaining("OUTPUT_ERROR");
        assertThat(Files.exists(parent.resolve("output/manifest.json"))).isFalse();
    }
}
