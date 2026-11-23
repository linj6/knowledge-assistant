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
        Path run = root.resolve("history");
        com.lnjecit.knowledge.assistant.evaluation.runner.OfflineEvaluationRunner.run(run, false);
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
        assertEquals(
                "ARTIFACT_HASH_MISMATCH: metrics.json",
                assertThrows(ResponseStatusException.class, () -> controller.read("history"))
                        .getReason());
        com.lnjecit.knowledge.assistant.evaluation.runner.OfflineEvaluationRunner.run(run, true);
        Files.writeString(run.resolve("manifest.json"), "broken json");
        assertEquals(
                "INVALID_RECORD_JSON_OR_PROTOCOL",
                assertThrows(ResponseStatusException.class, () -> controller.read("history"))
                        .getReason());
        com.lnjecit.knowledge.assistant.evaluation.runner.OfflineEvaluationRunner.run(run, true);
        Files.delete(run.resolve("report.md"));
        assertEquals(
                "MISSING_FILE",
                assertThrows(ResponseStatusException.class, () -> controller.read("history"))
                        .getReason());
    }

    @Test
    void runtimeArchivesRemainIdenticalToFrozenInputs() throws Exception {
        for (String path : java.util.List.of(
                "src/test/resources/retrieval-metrics-v1.json", "docs/evaluation/m4-01-gold-evidence.json")) {
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
        for (String id : java.util.List.of("offline", "live")) {
            Path archive = root.resolve(id);
            com.lnjecit.knowledge.assistant.evaluation.runner.OfflineEvaluationRunner.run(archive, false);
            if (id.equals("live")) {
                // 复用已生成归档的身份字段，构造最小 C02 空结果归档。
                tools.jackson.databind.node.ObjectNode manifest = (tools.jackson.databind.node.ObjectNode)
                        mapper.readTree(Files.readString(archive.resolve("manifest.json")));
                manifest.put("mode", "LIVE");
                manifest.put("runnerVersion", "m4-c02-live-v1");
                Files.writeString(archive.resolve("metrics.json"), "{\"runs\":[],\"summaries\":{}}");
                Files.writeString(archive.resolve("retrieval-results.jsonl"), "");
                tools.jackson.databind.node.ObjectNode hashes =
                        (tools.jackson.databind.node.ObjectNode) manifest.path("artifactSha256");
                for (String name : java.util.List.of("metrics.json", "retrieval-results.jsonl", "report.md"))
                    hashes.put(
                            name,
                            com.lnjecit.knowledge.assistant.evaluation.dataset.Sha256.file(archive.resolve(name)));
                Files.writeString(archive.resolve("manifest.json"), mapper.writeValueAsString(manifest));
            }
            EvaluationRunResponse response = new EvaluationRunStore(root).read(id);
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
