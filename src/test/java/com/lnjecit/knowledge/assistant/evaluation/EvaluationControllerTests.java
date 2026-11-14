package com.lnjecit.knowledge.assistant.evaluation;

import static org.junit.jupiter.api.Assertions.*;

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
        EvaluationController controller = new EvaluationController(root);
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
        EvaluationController controller = new EvaluationController(root);
        assertEquals("history", controller.read("history").get("runId"));
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
                404,
                assertThrows(ResponseStatusException.class, () -> controller.read("history"))
                        .getStatusCode()
                        .value());
    }
}
