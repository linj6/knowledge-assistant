package com.lnjecit.knowledge.assistant.evaluation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lnjecit.knowledge.assistant.evaluation.dataset.EvaluationDatasetLoader;
import com.lnjecit.knowledge.assistant.evaluation.metrics.AnswerMetrics;
import com.lnjecit.knowledge.assistant.evaluation.model.AnswerEvaluationDataset;
import com.lnjecit.knowledge.assistant.evaluation.model.AnswerEvaluationRun;
import com.lnjecit.knowledge.assistant.evaluation.model.AnswerReview;
import com.lnjecit.knowledge.assistant.evaluation.model.RunStatus;
import com.lnjecit.knowledge.assistant.evaluation.store.AnswerReviewStore;
import com.lnjecit.knowledge.assistant.evaluation.store.EvaluationFiles;
import com.lnjecit.knowledge.assistant.evaluation.store.EvaluationRunStore;
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
        // 未完成的正常拒答仍不能确认。
        AnswerReview.Input incomplete = new AnswerReview.Input(
                "stub",
                "Q01",
                1,
                dataset.freezeFingerprint(),
                null,
                "TEST",
                "stub",
                AnswerReview.Status.CONFIRMED,
                AnswerReview.Answerable.PENDING,
                AnswerReview.Judgment.PENDING,
                Map.of(),
                List.of(),
                false);
        assertThrows(ResponseStatusException.class, () -> service.save("stub", incomplete));
        // 确认请求停在原子保存之前，第二个 PUT 必须阻塞在业务临界区之外。
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AnswerReviewStore blockingStore = new AnswerReviewStore(root) {
            @Override
            public void save(String id, AnswerReview.Saved review) throws Exception {
                entered.countDown();
                assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                super.save(id, review);
            }
        };
        AnswerReviewService concurrent = new AnswerReviewService(runs, blockingStore);
        java.util.concurrent.FutureTask<AnswerReview.Saved> first =
                new java.util.concurrent.FutureTask<>(() -> concurrent.save("stub", confirmed));
        java.util.concurrent.FutureTask<ResponseStatusException> second =
                new java.util.concurrent.FutureTask<>(() -> assertThrows(
                        ResponseStatusException.class,
                        () -> concurrent.save("stub", input(dataset, "stub", Map.of()))));
        Thread confirming = new Thread(first);
        Thread drafting = new Thread(second);
        confirming.start();
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            drafting.start();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (drafting.getState() != Thread.State.BLOCKED && !second.isDone() && System.nanoTime() < deadline)
                Thread.yield();
            assertEquals(Thread.State.BLOCKED, drafting.getState());
        } finally {
            release.countDown();
        }
        first.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(
                org.springframework.http.HttpStatus.CONFLICT,
                second.get(5, java.util.concurrent.TimeUnit.SECONDS).getStatusCode());
        assertEquals(confirmed, service.read("stub").review().judgments());
        byte[] savedReview = java.nio.file.Files.readAllBytes(root.resolve("stub/review.json"));
        ResponseStatusException conflict =
                assertThrows(ResponseStatusException.class, () -> service.save("stub", confirmed));
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals("REVIEW_ALREADY_CONFIRMED", conflict.getReason());
        assertThrows(ResponseStatusException.class, () -> service.save("stub", input(dataset, "stub", Map.of())));
        assertArrayEquals(savedReview, java.nio.file.Files.readAllBytes(root.resolve("stub/review.json")));
        AnswerMetrics.Result score = service.score("stub");
        assertEquals(AnswerMetrics.State.PASS, score.appropriateness());
        assertEquals(AnswerMetrics.State.NA, score.correctness());
        assertEquals(AnswerMetrics.State.FAIL, score.completeness());
        assertEquals(AnswerMetrics.State.NA, score.validity());
        assertEquals(AnswerMetrics.State.NA, score.support());
        assertEquals(AnswerMetrics.State.FAIL, score.wholeCase());
        when(runs.read("invalid")).thenReturn(new AnswerEvaluationRun.Response("invalid", manifest, results));
        java.nio.file.Files.createDirectory(root.resolve("invalid"));
        AnswerReview.Input illegal = new AnswerReview.Input(
                "invalid",
                "Q01",
                1,
                dataset.freezeFingerprint(),
                null,
                "TEST",
                "invalid",
                AnswerReview.Status.PENDING,
                AnswerReview.Answerable.FALSE,
                AnswerReview.Judgment.PASS,
                facts,
                List.of(new AnswerMetrics.Claim("C1", "断言", "PASS", "PASS", List.of("E99"))),
                true);
        ResponseStatusException invalid =
                assertThrows(ResponseStatusException.class, () -> service.save("invalid", illegal));
        assertEquals(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, invalid.getStatusCode());
        assertEquals("INVALID_CLAIM_OR_CITATION", invalid.getReason());
        assertNull(service.read("invalid").review());
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
        // 使用真实文件 Store 发布 Stub Run，逐字节确认历史产物不受评审影响。
        EvaluationRunStore fileRuns = new EvaluationRunStore(root);
        fileRuns.save(failed, new AnswerEvaluationRun.Results(results.goldCase(), null));
        AnswerReviewService failedService = new AnswerReviewService(fileRuns, new AnswerReviewStore(root));
        Map<String, byte[]> artifacts = new java.util.LinkedHashMap<>();
        for (String name : List.of("manifest.json", "results.json", "report.md"))
            artifacts.put(
                    name,
                    java.nio.file.Files.readAllBytes(root.resolve("failed").resolve(name)));
        AnswerReview.Input failedConfirmation = new AnswerReview.Input(
                "failed",
                "Q01",
                1,
                dataset.freezeFingerprint(),
                null,
                "TEST",
                "确认 MODEL_TIMEOUT 执行失败，无可评回答",
                AnswerReview.Status.CONFIRMED,
                null,
                null,
                null,
                null,
                false);
        failedService.save("failed", failedConfirmation);
        assertEquals(AnswerReview.Status.CONFIRMED, failedService.read("failed").reviewStatus());
        AnswerMetrics.Result failedScore = failedService.score("failed");
        assertEquals(AnswerMetrics.State.FAIL, failedScore.wholeCase());
        for (AnswerMetrics.State state : List.of(
                failedScore.appropriateness(),
                failedScore.correctness(),
                failedScore.completeness(),
                failedScore.validity(),
                failedScore.support())) assertEquals(AnswerMetrics.State.NA, state);
        for (Map.Entry<String, byte[]> artifact : artifacts.entrySet())
            assertArrayEquals(
                    artifact.getValue(),
                    java.nio.file.Files.readAllBytes(root.resolve("failed").resolve(artifact.getKey())));
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
