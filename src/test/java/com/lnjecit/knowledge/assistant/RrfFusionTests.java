package com.lnjecit.knowledge.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lnjecit.knowledge.assistant.retrieval.fusion.RrfFusionService;
import com.lnjecit.knowledge.assistant.retrieval.model.RankedChunk;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RrfFusionTests {
    private final RrfFusionService service = new RrfFusionService();
    private final RetrievalParameters p = new RetrievalParameters(3, 3, 60, 3, 1);

    private RankedChunk c(long id, int rank, boolean vector) {
        return new RankedChunk(
                id,
                1L,
                2L,
                3L,
                "正文",
                0,
                2,
                "标题",
                vector ? rank : null,
                vector ? 0.2 : null,
                vector ? null : rank,
                vector ? null : 8.0,
                null,
                null,
                null);
    }

    @Test
    void formulaAndEvidence() {
        for (int k : List.of(1, 60, 1000)) {
            List<RankedChunk> result = service.fuse(
                    List.of(c(1, 1, true)),
                    List.of(c(2, 1, false), c(1, 2, false)),
                    new RetrievalParameters(3, 3, k, 3, 1));
            RankedChunk both = result.getFirst();
            assertEquals(1L, both.chunkId());
            assertEquals(1.0 / (k + 1) + 1.0 / (k + 2), both.rrfScore(), 1e-15);
            assertEquals(1, both.vectorRank());
            assertEquals(2, both.bm25Rank());
            assertEquals(0.2, both.vectorDistance());
            assertEquals(8.0, both.bm25Score());
            assertEquals("正文", both.content());
            assertEquals("标题", both.headingPath());
            assertEquals(0, both.sourceStart());
            assertEquals(2, both.sourceEnd());
            assertNull(both.finalRank());
            assertNull(both.rerankScore());
            assertNull(result.get(1).vectorRank());
            assertNull(result.get(1).vectorDistance());
        }
        RankedChunk only = service.fuse(List.of(c(1, 1, true)), List.of(), p).getFirst();
        assertEquals(1.0 / 61, only.rrfScore());
        assertNull(only.bm25Rank());
        assertNull(only.bm25Score());
        assertTrue(service.fuse(List.of(), List.of(), p).isEmpty());
    }

    @Test
    void stableSortAndTruncation() {
        List<RankedChunk> v = List.of(c(10, 1, true), c(30, 2, true));
        List<RankedChunk> b = List.of(c(2, 1, false), c(30, 2, false));
        for (int i = 0; i < 20; i++) {
            assertEquals(
                    List.of(30L, 2L, 10L),
                    service.fuse(v, b, p).stream().map(RankedChunk::chunkId).toList());
            assertEquals(
                    30L,
                    service.fuse(v, b, new RetrievalParameters(3, 3, 60, 1, 1))
                            .getFirst()
                            .chunkId());
        }
    }

    @Test
    void invalidInputs() {
        assertThrows(NullPointerException.class, () -> service.fuse(null, List.of(), p));
        assertThrows(NullPointerException.class, () -> service.fuse(List.of(), null, p));
        assertThrows(NullPointerException.class, () -> service.fuse(Arrays.asList((RankedChunk) null), List.of(), p));
        for (List<RankedChunk> invalid : List.of(
                List.of(c(1, 1, false)),
                List.of(c(1, 2, true)),
                List.of(c(1, 1, true), c(1, 2, true)),
                List.of(c(1, 1, true), c(2, 2, true), c(3, 3, true), c(4, 4, true)),
                service.fuse(List.of(c(1, 1, true)), List.of(), p)))
            assertThrows(IllegalArgumentException.class, () -> service.fuse(invalid, List.of(), p));
        assertThrows(IllegalArgumentException.class, () -> service.fuse(List.of(), List.of(c(1, 1, true)), p));
        assertThrows(IllegalArgumentException.class, () -> c(1, 0, true));
    }

    @Test
    void rejectsEveryEvidenceMismatch() {
        RankedChunk original = c(1, 1, true);
        for (int field = 0; field < 7; field++) {
            RankedChunk bad = new RankedChunk(
                    1L,
                    field == 0 ? 9L : 1L,
                    field == 1 ? 9L : 2L,
                    field == 2 ? 9L : 3L,
                    field == 3 ? "不同" : "正文",
                    field == 4 ? 1 : 0,
                    field == 5 ? 3 : 2,
                    field == 6 ? null : "标题",
                    null,
                    null,
                    1,
                    8.0,
                    null,
                    null,
                    null);
            assertThrows(IllegalArgumentException.class, () -> service.fuse(List.of(original), List.of(bad), p));
        }
    }
}
