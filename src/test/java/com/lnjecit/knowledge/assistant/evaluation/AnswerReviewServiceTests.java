package com.lnjecit.knowledge.assistant.evaluation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lnjecit.knowledge.assistant.evaluation.metrics.AnswerMetrics;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class AnswerReviewServiceTests {
    @TempDir
    Path root;

    @Test
    void realLocalQ01ReadOnlyWhenAvailable() throws Exception {
        Path real = EvaluationFiles.ROOT.resolve("5644bc42-ad97-419a-8695-e853f26c25ff/manifest.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(real));
        AnswerReviewService service = new AnswerReviewService(new EvaluationRunStore(), new AnswerReviewStore());
        AnswerReview.Material material = service.read("5644bc42-ad97-419a-8695-e853f26c25ff");
        assertEquals("Q01", material.gold().caseId());
        assertFalse(material.gold().requiredFacts().isEmpty());
        assertNotNull(material.run().results().execution());
        assertEquals(
                AnswerMetrics.State.PENDING,
                service.score(material.run().runId()).wholeCase());
        assertThrows(ResponseStatusException.class, () -> service.read("d03-a-c01-history"));
        // 真实 Run 只读；合成人工标签仅写入临时目录，用于验证后端管线。
        EvaluationRunStore isolatedRuns = mock(EvaluationRunStore.class);
        when(isolatedRuns.read(material.run().runId())).thenReturn(material.run());
        java.nio.file.Files.createDirectory(root.resolve(material.run().runId()));
        AnswerReviewService isolated = new AnswerReviewService(isolatedRuns, new AnswerReviewStore(root));
        Map<String, AnswerReview.Judgment> coverage = new java.util.LinkedHashMap<>();
        for (AnswerEvaluationDataset.Fact fact : material.gold().requiredFacts())
            coverage.put(fact.factId(), AnswerReview.Judgment.PASS);
        List<String> references = material.run().results().execution().answer().citations().stream()
                .map(com.lnjecit.knowledge.assistant.rag.model.RagCitation::evidenceId)
                .toList();
        AnswerReview.Input confirmed = new AnswerReview.Input(
                material.run().runId(),
                "Q01",
                1,
                new EvaluationDatasetLoader().full().freezeFingerprint(),
                material.run().manifest().snapshot().snapshotHash(),
                "TEST_ONLY",
                "合成标签测试，不代表真实人工评分",
                AnswerReview.Status.CONFIRMED,
                AnswerReview.Answerable.TRUE,
                AnswerReview.Judgment.PASS,
                coverage,
                List.of(new AnswerMetrics.Claim("C1", "合成断言", "PASS", "PASS", references)),
                true);
        isolated.save(material.run().runId(), confirmed);
        AnswerMetrics.Result result = isolated.score(material.run().runId());
        assertEquals(AnswerMetrics.State.PASS, result.appropriateness());
        assertEquals(AnswerMetrics.State.PASS, result.correctness());
        assertEquals(AnswerMetrics.State.PASS, result.completeness());
        assertEquals(AnswerMetrics.State.PASS, result.validity());
        assertEquals(AnswerMetrics.State.PASS, result.support());
        assertEquals(AnswerMetrics.State.PASS, result.wholeCase());
        when(isolatedRuns.read("c02"))
                .thenReturn(new EvaluationRunStore.LiveResponse("c02", null, null, List.of(), null));
        assertThrows(ResponseStatusException.class, () -> isolated.read("c02"));
    }

    @Test
    void draftRoundTripAndIdentityAndFactValidation() throws Exception {
        EvaluationRunStore runs = mock(EvaluationRunStore.class);
        AnswerEvaluationDataset dataset = new EvaluationDatasetLoader().full();
        AnswerEvaluationDataset.Case gold = dataset.cases().get(0);
        AnswerEvaluationRun.Manifest manifest = new AnswerEvaluationRun.Manifest(
                "m4-d03-answer-v1",
                1,
                "stub",
                "now",
                "answer-evaluation-v1",
                dataset.freezeFingerprint(),
                null,
                null,
                "PRECHECK",
                null,
                null,
                RunStatus.NOT_READY,
                "PRECHECK",
                Map.of());
        AnswerEvaluationRun.Response run = new AnswerEvaluationRun.Response(
                "stub",
                manifest,
                new AnswerEvaluationRun.Results(
                        new EvaluationService.GoldCase(gold.caseId(), "question", List.of()), null));
        when(runs.read("stub")).thenReturn(run);
        java.nio.file.Files.createDirectory(root.resolve("stub"));
        AnswerReviewService service = new AnswerReviewService(runs, new AnswerReviewStore(root));
        AnswerReview.Input input = input(dataset, "stub", Map.of());
        service.save("stub", input);
        assertEquals(input, service.read("stub").review().judgments());
        assertThrows(ResponseStatusException.class, () -> service.save("stub", input(dataset, "other", Map.of())));
        assertThrows(
                ResponseStatusException.class,
                () -> service.save("stub", input(dataset, "stub", Map.of("OTHER", AnswerReview.Judgment.PASS))));
        assertEquals(AnswerMetrics.State.NOT_READY, service.score("stub").wholeCase());
    }

    @Test
    void confirmedRefusalUsesGoldAndFailedIsNotRefusal() throws Exception {
        EvaluationRunStore runs = mock(EvaluationRunStore.class);
        AnswerEvaluationDataset dataset = new EvaluationDatasetLoader().full();
        AnswerEvaluationDataset.Case gold = dataset.cases().get(0);
        java.nio.file.Files.createDirectory(root.resolve("stub"));
        AnswerReviewService service = new AnswerReviewService(runs, new AnswerReviewStore(root));
        com.lnjecit.knowledge.assistant.rag.model.RagAnswer answer =
                new com.lnjecit.knowledge.assistant.rag.model.RagAnswer(
                        com.lnjecit.knowledge.assistant.rag.model.RagStatus.INSUFFICIENT_EVIDENCE,
                        "材料不足",
                        List.of(),
                        null,
                        null,
                        false,
                        null,
                        null);
        AnswerEvaluationRun.Manifest manifest = new AnswerEvaluationRun.Manifest(
                "m4-d03-answer-v1",
                1,
                "stub",
                "now",
                "answer-evaluation-v1",
                dataset.freezeFingerprint(),
                null,
                null,
                "ANSWER_RESULT",
                null,
                null,
                RunStatus.COMPLETED,
                null,
                Map.of());
        AnswerEvaluationRun.Results results = new AnswerEvaluationRun.Results(
                new EvaluationService.GoldCase("Q01", "question", List.of()),
                new com.lnjecit.knowledge.assistant.rag.RagService.RagExecutionResult(null, null, answer, null));
        when(runs.read("stub")).thenReturn(new AnswerEvaluationRun.Response("stub", manifest, results));
        Map<String, AnswerReview.Judgment> facts = new java.util.LinkedHashMap<>();
        for (AnswerEvaluationDataset.Fact fact : gold.requiredFacts())
            facts.put(fact.factId(), AnswerReview.Judgment.FAIL);
        AnswerReview.Input confirmed = new AnswerReview.Input(
                "stub",
                "Q01",
                1,
                dataset.freezeFingerprint(),
                null,
                "TEST",
                "Stub 拒答评审",
                AnswerReview.Status.CONFIRMED,
                AnswerReview.Answerable.FALSE,
                AnswerReview.Judgment.PASS,
                facts,
                List.of(),
                true);
        service.save("stub", confirmed);
        AnswerMetrics.Result score = service.score("stub");
        assertEquals(AnswerMetrics.State.PASS, score.appropriateness());
        assertEquals(AnswerMetrics.State.NA, score.correctness());
        assertEquals(AnswerMetrics.State.FAIL, score.completeness());
        assertEquals(AnswerMetrics.State.NA, score.validity());
        assertEquals(AnswerMetrics.State.NA, score.support());
        assertEquals(AnswerMetrics.State.FAIL, score.wholeCase());
        AnswerReview.Input illegal = new AnswerReview.Input(
                "stub",
                "Q01",
                1,
                dataset.freezeFingerprint(),
                null,
                "TEST",
                "stub",
                AnswerReview.Status.PENDING,
                AnswerReview.Answerable.FALSE,
                AnswerReview.Judgment.PASS,
                facts,
                List.of(new AnswerMetrics.Claim("C1", "断言", "PASS", "PASS", List.of("E99"))),
                true);
        assertThrows(ResponseStatusException.class, () -> service.save("stub", illegal));
        AnswerEvaluationRun.Manifest failed = new AnswerEvaluationRun.Manifest(
                "m4-d03-answer-v1",
                1,
                "failed",
                "now",
                "answer-evaluation-v1",
                dataset.freezeFingerprint(),
                null,
                null,
                "GENERATION",
                null,
                null,
                RunStatus.FAILED,
                "MODEL_TIMEOUT",
                Map.of());
        when(runs.read("failed"))
                .thenReturn(new AnswerEvaluationRun.Response(
                        "failed", failed, new AnswerEvaluationRun.Results(results.goldCase(), null)));
        java.nio.file.Files.createDirectory(root.resolve("failed"));
        assertEquals(AnswerMetrics.State.FAIL, service.score("failed").wholeCase());
        assertEquals(AnswerMetrics.State.NA, service.score("failed").appropriateness());
    }

    private AnswerReview.Input input(
            AnswerEvaluationDataset dataset, String id, Map<String, AnswerReview.Judgment> coverage) {
        return new AnswerReview.Input(
                id,
                "Q01",
                1,
                dataset.freezeFingerprint(),
                null,
                "TEST",
                "stub",
                AnswerReview.Status.PENDING,
                AnswerReview.Answerable.PENDING,
                AnswerReview.Judgment.PENDING,
                coverage,
                List.of(),
                false);
    }
}
