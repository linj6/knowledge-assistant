package com.lnjecit.knowledge.assistant.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures;
import com.lnjecit.knowledge.assistant.infrastructure.ai.RerankCandidate;
import com.lnjecit.knowledge.assistant.infrastructure.ai.RerankResult;
import com.lnjecit.knowledge.assistant.infrastructure.ai.Reranker;
import com.lnjecit.knowledge.assistant.retrieval.model.RankedChunk;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters;
import com.lnjecit.knowledge.assistant.retrieval.rerank.RerankService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class RerankServiceTests {
    private final RetrievalParameters p = new RetrievalParameters(5, 5, 60, 5, 2);

    private RankedChunk c(long id) {
        return new RankedChunk(id, 1L, 2L, 3L, "正文", 0, 2, "标题", 1, .2, 1, 8., 1.0 / id, null, null);
    }

    private RerankService service(Reranker fake) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (fake != null) factory.addBean("reranker", fake);
        return new RerankService(factory.getBeanProvider(Reranker.class));
    }

    @Test
    void completePoolAndEvidence() {
        AtomicInteger calls = new AtomicInteger();
        List<RankedChunk> input = List.of(c(1), c(2), c(3), c(4), c(5));
        List<RankedChunk> output = service((q, candidates, topN) -> {
                    calls.incrementAndGet();
                    assertEquals(5, topN);
                    assertEquals(
                            List.of("1", "2", "3", "4", "5"),
                            candidates.stream().map(RerankCandidate::id).toList());
                    return List.of(
                            new RerankResult("3", .9),
                            new RerankResult("1", .7),
                            new RerankResult("2", .2),
                            new RerankResult("5", .1),
                            new RerankResult("4", .1));
                })
                .rerank("q", input, p);
        assertEquals(1, calls.get());
        assertEquals(
                List.of(3L, 1L, 2L, 4L, 5L),
                output.stream().map(RankedChunk::chunkId).toList());
        for (RankedChunk actual : output) {
            RankedChunk original = input.get(actual.chunkId().intValue() - 1);
            assertEquals(
                    original,
                    new RankedChunk(
                            actual.chunkId(),
                            actual.documentId(),
                            actual.indexVersionId(),
                            actual.knowledgeBaseId(),
                            actual.content(),
                            actual.sourceStart(),
                            actual.sourceEnd(),
                            actual.headingPath(),
                            actual.vectorRank(),
                            actual.vectorDistance(),
                            actual.bm25Rank(),
                            actual.bm25Score(),
                            actual.rrfScore(),
                            null,
                            null));
            assertNull(actual.finalRank());
            assertEquals(Map.of(1L, .7, 2L, .2, 3L, .9, 4L, .1, 5L, .1).get(actual.chunkId()), actual.rerankScore());
        }
        assertThrows(UnsupportedOperationException.class, output::clear);
    }

    @Test
    void actualPoolSmallerThanFusionLimit() {
        AtomicInteger calls = new AtomicInteger();
        List<RankedChunk> input = new ArrayList<>(List.of(c(1), c(2), c(3)));
        List<RankedChunk> original = List.copyOf(input);
        List<RankedChunk> output = service((query, candidates, topN) -> {
                    calls.incrementAndGet();
                    assertEquals("question", query);
                    assertEquals(3, topN);
                    assertEquals(
                            List.of("1", "2", "3"),
                            candidates.stream().map(RerankCandidate::id).toList());
                    assertEquals(
                            input.stream().map(RankedChunk::content).toList(),
                            candidates.stream().map(RerankCandidate::text).toList());
                    return List.of(new RerankResult("3", .9), new RerankResult("1", .7), new RerankResult("2", .2));
                })
                .rerank("question", input, p);
        assertEquals(1, calls.get());
        assertEquals(
                List.of(3L, 1L, 2L), output.stream().map(RankedChunk::chunkId).toList());
        assertEquals(original, input);
    }

    private void rejected(String query, List<RankedChunk> candidates, RetrievalParameters parameters) {
        AtomicInteger calls = new AtomicInteger();
        RerankService s = service((q, c, n) -> {
            calls.incrementAndGet();
            return List.of();
        });
        assertThrows(IllegalArgumentException.class, () -> s.rerank(query, candidates, parameters));
        assertEquals(0, calls.get());
    }

    private RankedChunk changed(int field, Object value) {
        RankedChunk c = c(1);
        return new RankedChunk(
                field == 0 ? (Long) value : c.chunkId(),
                field == 1 ? (Long) value : c.documentId(),
                field == 2 ? (Long) value : c.indexVersionId(),
                field == 3 ? (Long) value : c.knowledgeBaseId(),
                field == 4 ? (String) value : c.content(),
                0,
                2,
                "标题",
                1,
                .2,
                1,
                8.,
                field == 5 ? (Double) value : c.rrfScore(),
                field == 6 ? (Double) value : null,
                field == 7 ? (Integer) value : null);
    }

    @Test
    void nullQuery() {
        rejected(null, List.of(c(1)), p);
    }

    @Test
    void blankQuery() {
        rejected(" ", List.of(c(1)), p);
    }

    @Test
    void nullCandidates() {
        rejected("q", null, p);
    }

    @Test
    void nullElement() {
        rejected("q", Arrays.asList((RankedChunk) null), p);
    }

    @Test
    void nullParameters() {
        rejected("q", List.of(c(1)), null);
    }

    @Test
    void excessivePool() {
        rejected("q", List.of(c(1), c(2), c(3), c(4), c(5), c(6)), p);
    }

    @Test
    void duplicateChunk() {
        rejected("q", List.of(c(1), c(1)), p);
    }

    @Test
    void rawVector() {
        rejected(
                "q",
                List.of(new RankedChunk(1L, 1L, 2L, 3L, "正文", 0, 2, null, 1, .2, null, null, null, null, null)),
                p);
    }

    @Test
    void rawBm25() {
        rejected(
                "q",
                List.of(new RankedChunk(1L, 1L, 2L, 3L, "正文", 0, 2, null, null, null, 1, 8., null, null, null)),
                p);
    }

    @Test
    void nullRrf() {
        rejected("q", List.of(changed(5, null)), p);
    }

    @Test
    void zeroRrf() {
        rejected("q", List.of(changed(5, 0.)), p);
    }

    @Test
    void negativeRrf() {
        rejected("q", List.of(changed(5, -.1)), p);
    }

    @Test
    void alreadyReranked() {
        rejected("q", List.of(changed(6, .3)), p);
    }

    @Test
    void alreadyFinalized() {
        rejected("q", List.of(changed(7, 1)), p);
    }

    @Test
    void blankContent() {
        rejected("q", List.of(changed(4, " ")), p);
    }

    @Test
    void invalidChunkId() {
        for (long n : new long[] {0, -1}) rejected("q", List.of(changed(0, n)), p);
    }

    @Test
    void invalidDocumentId() {
        for (long n : new long[] {0, -1}) rejected("q", List.of(changed(1, n)), p);
    }

    @Test
    void invalidVersionId() {
        for (long n : new long[] {0, -1}) rejected("q", List.of(changed(2, n)), p);
    }

    @Test
    void invalidKbId() {
        for (long n : new long[] {0, -1}) rejected("q", List.of(changed(3, n)), p);
    }

    @Test
    void descendingRrfRequired() {
        rejected("q", List.of(c(2), c(1)), p);
    }

    @Test
    void numericIdTieOrderRequired() {
        rejected("q", List.of(changed(0, 10L), changed(0, 2L)), p);
    }

    @Test
    void constructorOwnsStructuralValidation() {
        assertThrows(NullPointerException.class, () -> changed(0, null));
        assertThrows(NullPointerException.class, () -> changed(4, null));
        assertThrows(IllegalArgumentException.class, () -> changed(5, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> changed(7, 0));
    }

    private void invalidResponse(List<RerankResult> response) {
        AtomicInteger calls = new AtomicInteger();
        ModelFailures.SafeModelException ex =
                assertThrows(ModelFailures.SafeModelException.class, () -> service((q, c, n) -> {
                            calls.incrementAndGet();
                            return response;
                        })
                        .rerank("q", List.of(c(1), c(2)), p));
        assertEquals(ModelFailures.Kind.INVALID_RESPONSE, ex.kind());
        org.junit.jupiter.api.Assertions.assertNotNull(ex.getCause());
        assertEquals(1, calls.get());
    }

    @Test
    void nullResponse() {
        invalidResponse(null);
    }

    @Test
    void insufficientResponse() {
        invalidResponse(List.of(new RerankResult("1", .2)));
    }

    @Test
    void excessiveResponse() {
        invalidResponse(List.of(new RerankResult("1", .2), new RerankResult("2", .2), new RerankResult("3", .2)));
    }

    @Test
    void duplicateResponseId() {
        invalidResponse(List.of(new RerankResult("1", .2), new RerankResult("1", .3)));
    }

    @Test
    void unknownResponseId() {
        invalidResponse(List.of(new RerankResult("1", .2), new RerankResult("99", .3)));
    }
    // 等长响应缺少输入 ID 必然同时包含重复或未知 ID，分别由对应前置分支拒绝。
    @Test
    void missingInputIdWithCorrectCount() {
        invalidResponse(List.of(new RerankResult("2", .2), new RerankResult("2", .3)));
    }

    @Test
    void nullResponseElement() {
        invalidResponse(Arrays.asList(new RerankResult("1", .2), null));
    }

    private void invalidScore(double score) {
        invalidResponse(List.of(new RerankResult("1", .2), new RerankResult("2", score)));
    }

    @Test
    void nanScore() {
        invalidScore(Double.NaN);
    }

    @Test
    void positiveInfinityScore() {
        invalidScore(Double.POSITIVE_INFINITY);
    }

    @Test
    void negativeInfinityScore() {
        invalidScore(Double.NEGATIVE_INFINITY);
    }

    @Test
    void negativeScore() {
        invalidScore(-.1);
    }

    @Test
    void excessiveScore() {
        invalidScore(1.1);
    }

    @Test
    void boundaryScores() {
        List<RankedChunk> result = service((q, c, n) -> List.of(new RerankResult("1", 0), new RerankResult("2", 1)))
                .rerank("q", List.of(c(1), c(2)), p);
        assertEquals(
                List.of(1., 0.), result.stream().map(RankedChunk::rerankScore).toList());
        assertEquals(List.of(2L, 1L), result.stream().map(RankedChunk::chunkId).toList());
    }

    @Test
    void tiesPreserveFusionPriorityInsteadOfNumericId() {
        List<RankedChunk> input = new ArrayList<>(List.of(changed(0, 30L), c(2), c(3)));
        List<RankedChunk> original = List.copyOf(input);
        List<RankedChunk> result = service((q, c, n) ->
                        List.of(new RerankResult("3", .8), new RerankResult("30", .8), new RerankResult("2", .8)))
                .rerank("q", input, p);
        assertEquals(
                List.of(30L, 2L, 3L), result.stream().map(RankedChunk::chunkId).toList());
        assertEquals(original, input);
    }

    @Test
    void emptyDoesNotCallModel() {
        AtomicInteger calls = new AtomicInteger();
        assertTrue(service((q, c, n) -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException();
                })
                .rerank("q", List.of(), p)
                .isEmpty());
        assertEquals(0, calls.get());
        assertTrue(service(null).rerank("q", List.of(), p).isEmpty());
    }

    @Test
    void missingModelFailsNonemptyRequest() {
        ModelFailures.SafeModelException ex = assertThrows(
                ModelFailures.SafeModelException.class, () -> service(null).rerank("q", List.of(c(1)), p));
        assertEquals(ModelFailures.Kind.CONFIGURATION, ex.kind());
    }

    @Test
    void failureDoesNotLeakSensitiveContent() {
        String sensitive = "private-query private-body provider-response api-key-secret";
        ModelFailures.SafeModelException ex =
                assertThrows(ModelFailures.SafeModelException.class, () -> service((q, c, n) -> {
                            throw new IllegalStateException(sensitive);
                        })
                        .rerank("q", List.of(c(1), c(2)), p));
        assertEquals(ModelFailures.Kind.OTHER, ex.kind());
        assertEquals("Model request failed: stage=RERANK kind=OTHER", ex.getMessage());
        org.junit.jupiter.api.Assertions.assertNotNull(ex.getCause());
        assertEquals(0, ex.getSuppressed().length);
    }
}
