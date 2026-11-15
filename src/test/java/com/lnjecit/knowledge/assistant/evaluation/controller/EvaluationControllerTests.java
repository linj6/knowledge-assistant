package com.lnjecit.knowledge.assistant.evaluation.controller;

import static org.junit.jupiter.api.Assertions.*;

import com.lnjecit.knowledge.assistant.evaluation.model.EvaluationRunResponse;
import com.lnjecit.knowledge.assistant.evaluation.store.EvaluationRunStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class EvaluationControllerTests {
    @TempDir
    Path root;

    @Test
    void rejectsInvalidIdAndMissingFiles() throws Exception {
        EvaluationController controller = new EvaluationController(new EvaluationRunStore(root));
        assertEquals(
                400,
                assertThrows(ResponseStatusException.class, () -> controller.read("../secret"))
                        .getStatusCode()
                        .value());
        assertEquals(
                404,
                assertThrows(ResponseStatusException.class, () -> controller.read("missing"))
                        .getStatusCode()
                        .value());
        assertTrue(controller.list().isEmpty());
    }

    @Test
    void readsHistoryAndRejectsCorruption() throws Exception {
        Path source = Path.of("target/evaluation/m4-c01-offline-v1");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(source));
        Path run = Files.createDirectory(root.resolve("history"));
        for (String name : java.util.List.of("manifest.json", "metrics.json", "retrieval-results.jsonl", "report.md"))
            Files.copy(source.resolve(name), run.resolve(name));
        EvaluationController controller = new EvaluationController(new EvaluationRunStore(root));
        assertEquals("history", ((EvaluationRunStore.OfflineResponse) controller.read("history")).runId());
        assertEquals(1, controller.list().size());
        Files.writeString(run.resolve("metrics.json"), "{}");
        assertEquals(
                422,
                assertThrows(ResponseStatusException.class, () -> controller.read("history"))
                        .getStatusCode()
                        .value());
        assertTrue(controller.list().isEmpty());
        Files.writeString(run.resolve("manifest.json"), "broken json");
        assertThrows(ResponseStatusException.class, () -> controller.read("history"));
        Files.delete(run.resolve("report.md"));
        assertEquals(
                422,
                assertThrows(ResponseStatusException.class, () -> controller.read("history"))
                        .getStatusCode()
                        .value());
    }

    @Test
    void runtimeArchivesRemainIdenticalToFrozenInputs() throws Exception {
        for (String path : java.util.List.of(
                "src/test/resources/retrieval-metrics-v1.json", "docs/engineering/m4-01-gold-evidence.json")) {
            byte[] runtime = new org.springframework.core.io.ClassPathResource(
                            "evaluation/" + Path.of(path).getFileName())
                    .getContentAsByteArray();
            assertArrayEquals(Files.readAllBytes(Path.of(path)), runtime, "冻结归档不得与运行时材料独立漂移: " + path);
        }
    }

    @Test
    void historicalTypedResponsesPreserveArchivedJsonStructure() throws Exception {
        tools.jackson.databind.json.JsonMapper mapper =
                tools.jackson.databind.json.JsonMapper.builder().build();
        for (String id : java.util.List.of("m4-c01-offline-v1", "m4-c02-live-bm25-v1", "m4-c02-live-vector-rrf-v1")) {
            Path archive = Path.of("target/evaluation").resolve(id);
            org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(archive));
            EvaluationRunResponse response = new EvaluationRunStore(Path.of("target/evaluation")).read(id);
            tools.jackson.databind.JsonNode actual = mapper.readTree(mapper.writeValueAsString(response));
            assertEquals(mapper.readTree(Files.readString(archive.resolve("manifest.json"))), actual.path("manifest"));
            assertEquals(mapper.readTree(Files.readString(archive.resolve("metrics.json"))), actual.path("metrics"));
            java.util.List<tools.jackson.databind.JsonNode> lines = new java.util.ArrayList<>();
            for (String line : Files.readAllLines(archive.resolve("retrieval-results.jsonl")))
                if (!line.isBlank()) lines.add(mapper.readTree(line));
            assertEquals(mapper.readTree(mapper.writeValueAsString(lines)), actual.path("results"));
        }
    }
}
