package com.lnjecit.knowledge.assistant;

import static org.junit.jupiter.api.Assertions.*;

import com.lnjecit.knowledge.assistant.infrastructure.ai.*;
import com.lnjecit.knowledge.assistant.retrieval.*;
import java.util.*;
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
        }
        assertThrows(UnsupportedOperationException.class, output::clear);
    }

    @Test
    void emptyAndMissing() {
        assertTrue(service(null).rerank("q", List.of(), p).isEmpty());
        assertThrows(ModelFailures.SafeModelException.class, () -> service(null).rerank("q", List.of(c(1)), p));
    }

    @Test
    void invalidInputs() {
        RerankService s = service((q, c, n) -> {
            fail("must not call");
            return null;
        });
        assertThrows(IllegalArgumentException.class, () -> s.rerank(" ", List.of(), p));
        assertThrows(IllegalArgumentException.class, () -> s.rerank("q", null, p));
        for (List<RankedChunk> input :
                List.of(Arrays.asList((RankedChunk) null), List.of(c(1), c(1)), List.of(c(2), c(1))))
            assertThrows(IllegalArgumentException.class, () -> s.rerank("q", input, p));
    }

    @Test
    void invalidResponses() {
        List<List<RerankResult>> invalid = new ArrayList<>();
        invalid.add(null);
        invalid.add(List.of());
        invalid.add(Arrays.asList((RerankResult) null));
        invalid.add(List.of(new RerankResult("99", .2)));
        invalid.add(List.of(new RerankResult("1", .2), new RerankResult("1", .3)));
        for (double score : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -.1, 1.1})
            invalid.add(List.of(new RerankResult("1", score)));
        for (List<RerankResult> response : invalid)
            assertThrows(ModelFailures.SafeModelException.class, () -> service((q, c, n) -> response)
                    .rerank("q", List.of(c(1)), p));
        assertThrows(ModelFailures.SafeModelException.class, () -> service((q, c, n) -> {
                    throw new IllegalStateException("secret");
                })
                .rerank("q", List.of(c(1)), p));
    }
}
