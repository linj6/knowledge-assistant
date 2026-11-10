package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lnjecit.knowledge.assistant.retrieval.PublishedDocument;
import com.lnjecit.knowledge.assistant.retrieval.PublishedVersionSnapshot;
import com.lnjecit.knowledge.assistant.retrieval.PublishedVersionTerms;
import com.lnjecit.knowledge.assistant.retrieval.RankedChunk;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalDiagnostics;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalParameters;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalResult;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalStatus;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalStrategy;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class RetrievalFoundationTests {
    @Test
    void strategiesAndParameters() {
        assertThat(RetrievalStrategy.values())
                .containsExactly(
                        RetrievalStrategy.VECTOR,
                        RetrievalStrategy.BM25,
                        RetrievalStrategy.HYBRID_RRF,
                        RetrievalStrategy.HYBRID_RERANK);
        assertThatThrownBy(() -> RetrievalStrategy.valueOf("unknown")).isInstanceOf(IllegalArgumentException.class);
        assertThat(RetrievalParameters.defaults()).isEqualTo(new RetrievalParameters(20, 20, 60, 20, 5));
        for (int i = 0; i < 5; i++) {
            final int index = i;
            for (int bad : new int[] {0, -1, index == 2 ? 1001 : 101})
                assertThatThrownBy(() -> {
                            int[] v = {20, 20, 60, 20, 5};
                            v[index] = bad;
                            new RetrievalParameters(v[0], v[1], v[2], v[3], v[4]);
                        })
                        .isInstanceOf(IllegalArgumentException.class);
        }
        new RetrievalParameters(100, 100, 1000, 100, 100).validate(RetrievalStrategy.HYBRID_RERANK);
        for (RetrievalStrategy strategy : RetrievalStrategy.values()) {
            RetrievalParameters.defaults().validate(strategy);
            assertThatThrownBy(() -> new RetrievalParameters(1, 1, 60, 1, 2).validate(strategy))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        new RetrievalParameters(2, 1, 60, 100, 2).validate(RetrievalStrategy.VECTOR);
        assertThatThrownBy(() -> new RetrievalParameters(1, 1, 60, 3, 1).validate(RetrievalStrategy.HYBRID_RRF))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotAndTerms() {
        PublishedVersionSnapshot a =
                PublishedVersionSnapshot.of(1L, List.of(new PublishedDocument(2L, 4L), new PublishedDocument(1L, 3L)));
        assertThat(a).isEqualTo(PublishedVersionSnapshot.of(1L, a.documents().reversed()));
        assertThatThrownBy(() -> a.documents().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(PublishedVersionTerms.validate(List.of(2L, 1L, 2L))).containsExactly(1L, 2L);
        assertThat(PublishedVersionTerms.validate(
                        LongStream.range(0, 2000).boxed().toList()))
                .hasSize(2000);
        assertThatThrownBy(() -> PublishedVersionTerms.validate(
                        LongStream.range(0, 2001).boxed().toList()))
                .hasMessage("TERMS_LIMIT_EXCEEDED");
    }

    @Test
    void statusAndImmutableCandidates() {
        RetrievalParameters p = RetrievalParameters.defaults();
        RetrievalDiagnostics empty = new RetrievalDiagnostics(
                RetrievalStrategy.VECTOR,
                RetrievalStrategy.VECTOR,
                false,
                RetrievalStatus.EMPTY_RESULT,
                "hash",
                p,
                List.of(),
                null);
        assertThat(new RetrievalResult(List.of(), empty).diagnostics().status())
                .isEqualTo(RetrievalStatus.EMPTY_RESULT);
        RetrievalDiagnostics failed = new RetrievalDiagnostics(
                RetrievalStrategy.HYBRID_RRF,
                null,
                false,
                RetrievalStatus.FAILED,
                "hash",
                p,
                List.of(),
                RetrievalDiagnostics.Failure.BM25_FAILED);
        assertThat(new RetrievalResult(List.of(), failed).diagnostics().status())
                .isEqualTo(RetrievalStatus.FAILED);
        assertThatThrownBy(() -> new RetrievalDiagnostics(
                        RetrievalStrategy.VECTOR,
                        RetrievalStrategy.VECTOR,
                        true,
                        RetrievalStatus.SUCCESS,
                        "hash",
                        p,
                        List.of(),
                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievalDiagnostics(
                        RetrievalStrategy.HYBRID_RRF,
                        RetrievalStrategy.HYBRID_RRF,
                        false,
                        RetrievalStatus.FAILED,
                        "hash",
                        p,
                        List.of(),
                        RetrievalDiagnostics.Failure.BM25_FAILED))
                .isInstanceOf(IllegalArgumentException.class);
        RankedChunk chunk = new RankedChunk(1L, 2L, 3L, 4L, "a", 0, 1, null, null, null, 1, 2.0, null, null, 1);
        assertThat(chunk.vectorDistance()).isNull();
        RetrievalDiagnostics success = new RetrievalDiagnostics(
                RetrievalStrategy.BM25,
                RetrievalStrategy.BM25,
                false,
                RetrievalStatus.SUCCESS,
                "hash",
                p,
                List.of(),
                null);
        RetrievalResult result = new RetrievalResult(List.of(chunk), success);
        assertThatThrownBy(() -> result.chunks().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new RetrievalResult(List.of(), success)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievalResult(List.of(chunk), failed))
                .isInstanceOf(IllegalArgumentException.class);
        RetrievalDiagnostics.Stage stage = new RetrievalDiagnostics.Stage(
                RetrievalDiagnostics.Phase.BM25, 1, 1, List.of(new RetrievalDiagnostics.Candidate(1L, 1, 0.0)));
        assertThatThrownBy(() -> stage.candidates().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new RankedChunk(1L, 2L, 3L, 4L, "a", 0, 1, null, 0, 0.0, null, null, null, null, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> empty.stages().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
