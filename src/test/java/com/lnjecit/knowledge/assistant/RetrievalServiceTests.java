package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lnjecit.knowledge.assistant.retrieval.RetrievalService;
import com.lnjecit.knowledge.assistant.retrieval.embedding.QueryEmbeddingService;
import com.lnjecit.knowledge.assistant.retrieval.embedding.QueryVector;
import com.lnjecit.knowledge.assistant.retrieval.fusion.RrfFusionService;
import com.lnjecit.knowledge.assistant.retrieval.keyword.KeywordRetriever;
import com.lnjecit.knowledge.assistant.retrieval.model.RankedChunk;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalContext;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalDiagnostics;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalResult;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStatus;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStrategy;
import com.lnjecit.knowledge.assistant.retrieval.rerank.RerankService;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedDocument;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedVersionSnapshot;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedVersionSnapshotService;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.SnapshotInvalidException;
import com.lnjecit.knowledge.assistant.retrieval.vector.VectorRetriever;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class RetrievalServiceTests {
    final PublishedVersionSnapshotService snapshots = mock(PublishedVersionSnapshotService.class);
    final QueryEmbeddingService embedding = mock(QueryEmbeddingService.class);
    final VectorRetriever vector = mock(VectorRetriever.class);
    final KeywordRetriever keyword = mock(KeywordRetriever.class);
    final RrfFusionService fusion = spy(new RrfFusionService());
    final RerankService rerank = mock(RerankService.class);
    final List<RetrievalStrategy> all = List.of(RetrievalStrategy.values());
    RetrievalService service;
    final RetrievalParameters parameters = new RetrievalParameters(20, 20, 60, 20, 1);
    final PublishedVersionSnapshot snapshot = PublishedVersionSnapshot.of(1L, List.of(new PublishedDocument(2L, 3L)));

    @BeforeEach
    void setup() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("embedding", embedding);
        beans.addBean("keyword", keyword);
        service = new RetrievalService(
                snapshots,
                beans.getBeanProvider(QueryEmbeddingService.class),
                vector,
                beans.getBeanProvider(KeywordRetriever.class),
                fusion,
                rerank);
        when(snapshots.read(1L)).thenReturn(snapshot);
        float[] values = new float[1024];
        values[0] = 1;
        when(embedding.embed("query")).thenReturn(new QueryVector(values));
        when(vector.retrieve(any(), any()))
                .thenReturn(new VectorRetriever.Recall(List.of(chunk(true)), List.of(), null));
        when(keyword.retrieve(any())).thenReturn(new KeywordRetriever.Recall(List.of(chunk(false)), List.of(), null));
        when(rerank.rerank(any(), any(), any())).thenAnswer(call -> call.getArgument(1));
    }

    static RankedChunk chunk(boolean vector) {
        return new RankedChunk(
                4L,
                2L,
                3L,
                1L,
                "body",
                0,
                4,
                null,
                vector ? 1 : null,
                vector ? 0.1 : null,
                vector ? null : 1,
                vector ? null : 2.0,
                null,
                null,
                null);
    }

    List<RetrievalResult> search(List<RetrievalStrategy> strategies) {
        return service.search(1L, "query", strategies, parameters);
    }

    @Test
    void failedStagesAreEmptyAndKeepOnlyExecutedPhaseDiagnostics() {
        when(embedding.embed("query")).thenThrow(new IllegalStateException("MODEL_SECRET"));
        List<RetrievalResult> embeddingFailed = search(all);
        for (int i : List.of(0, 2, 3)) {
            failed(embeddingFailed.get(i), RetrievalDiagnostics.Failure.EMBEDDING_FAILED);
            assertThat(embeddingFailed.get(i).diagnostics().stages()).hasSize(1);
            assertThat(embeddingFailed.get(i).diagnostics().stages().getFirst().phase())
                    .isEqualTo(RetrievalDiagnostics.Phase.EMBEDDING);
            assertThat(embeddingFailed.get(i).diagnostics().stages().getFirst().candidates())
                    .isEmpty();
        }
        org.mockito.Mockito.reset(embedding);
        float[] values = new float[1024];
        values[0] = 1;
        when(embedding.embed("query")).thenReturn(new QueryVector(values));
        when(rerank.rerank(any(), any(), any())).thenThrow(new IllegalStateException("MODEL_SECRET"));
        RetrievalResult rerankFailed = search(all).getLast();
        failed(rerankFailed, RetrievalDiagnostics.Failure.RERANK_FAILED);
        assertThat(rerankFailed.diagnostics().stages())
                .extracting(RetrievalDiagnostics.Stage::phase)
                .containsExactly(
                        RetrievalDiagnostics.Phase.EMBEDDING,
                        RetrievalDiagnostics.Phase.FUSION,
                        RetrievalDiagnostics.Phase.RERANK);
        assertThat(rerankFailed.diagnostics().stages().getLast().candidateCount())
                .isZero();
        assertThat(rerankFailed.diagnostics().stages().getLast().candidates()).isEmpty();
        assertThat(rerankFailed.diagnostics().stages()).allSatisfy(stage -> {
            assertThat(stage.elapsedMillis()).isNotNegative();
            assertThat(stage.candidateCount()).isEqualTo(stage.candidates().size());
        });
    }

    @Test
    void allShareCallsAndOrder() {
        List<RetrievalResult> results = search(all);
        assertThat(results).hasSize(4).allSatisfy(r -> {
            assertThat(r.diagnostics().status()).isEqualTo(RetrievalStatus.SUCCESS);
            assertThat(r.chunks().getFirst().finalRank()).isEqualTo(1);
        });
        org.mockito.InOrder order = inOrder(snapshots, embedding, vector, keyword, fusion, rerank);
        order.verify(snapshots).read(1L);
        order.verify(embedding).embed("query");
        order.verify(vector).retrieve(any(), any());
        order.verify(keyword).retrieve(any());
        order.verify(fusion).fuse(any(), any(), eq(parameters));
        order.verify(rerank).rerank(eq("query"), any(), eq(parameters));
        order.verifyNoMoreInteractions();
    }

    @Test
    void eachStrategyRunsOnlyNecessaryComponents() {
        for (RetrievalStrategy strategy : all) {
            clearInvocations(snapshots, embedding, vector, keyword, fusion, rerank);
            assertThat(search(List.of(strategy))).hasSize(1);
            verify(snapshots).read(1L);
            if (strategy == RetrievalStrategy.BM25) verifyNoInteractions(embedding, vector, fusion, rerank);
            if (strategy == RetrievalStrategy.VECTOR) verifyNoInteractions(keyword, fusion, rerank);
        }
    }

    @Test
    void emptySnapshotSkipsEverything() {
        when(snapshots.read(1L)).thenReturn(PublishedVersionSnapshot.of(1L, List.of()));
        assertThat(search(all))
                .allSatisfy(r -> assertThat(r.diagnostics().status()).isEqualTo(RetrievalStatus.EMPTY_RESULT));
        verifyNoInteractions(embedding, vector, keyword, fusion, rerank);
    }

    @Test
    void snapshotFailures() {
        for (RuntimeException failure : List.of(new SnapshotInvalidException(), new IllegalStateException("secret"))) {
            doThrow(failure).when(snapshots).read(1L);
            assertThat(search(all)).allSatisfy(r -> assertThat(r.diagnostics().error())
                    .isEqualTo(
                            failure instanceof SnapshotInvalidException
                                    ? RetrievalDiagnostics.Failure.SNAPSHOT_INVALID
                                    : RetrievalDiagnostics.Failure.SNAPSHOT_READ_FAILED));
        }
        verifyNoInteractions(embedding, vector, keyword, fusion, rerank);
    }

    @Test
    void embeddingFailureKeepsBm25() {
        when(embedding.embed("query")).thenThrow(new IllegalStateException("secret"));
        List<RetrievalResult> results = search(all);
        assertThat(results.get(1).diagnostics().status()).isEqualTo(RetrievalStatus.SUCCESS);
        for (int i : List.of(0, 2, 3)) failed(results.get(i), RetrievalDiagnostics.Failure.EMBEDDING_FAILED);
        verifyNoInteractions(vector, fusion, rerank);
    }

    @Test
    void keywordFailuresKeepVector() {
        for (RetrievalDiagnostics.Failure failure :
                List.of(RetrievalDiagnostics.Failure.BM25_FAILED, RetrievalDiagnostics.Failure.TERMS_LIMIT_EXCEEDED)) {
            when(keyword.retrieve(any())).thenReturn(new KeywordRetriever.Recall(List.of(), List.of(), failure));
            List<RetrievalResult> results = search(all);
            assertThat(results.getFirst().diagnostics().status()).isEqualTo(RetrievalStatus.SUCCESS);
            for (int i : List.of(1, 2, 3)) failed(results.get(i), failure);
        }
        verifyNoInteractions(fusion, rerank);
    }

    @Test
    void vectorFailureKeepsKeyword() {
        when(vector.retrieve(any(), any()))
                .thenReturn(
                        new VectorRetriever.Recall(List.of(), List.of(), RetrievalDiagnostics.Failure.VECTOR_FAILED));
        List<RetrievalResult> results = search(all);
        failed(results.getFirst(), RetrievalDiagnostics.Failure.VECTOR_FAILED);
        assertThat(results.get(1).diagnostics().status()).isEqualTo(RetrievalStatus.SUCCESS);
        failed(results.get(2), RetrievalDiagnostics.Failure.VECTOR_FAILED);
    }

    @Test
    void fusionFailureDoesNotRerank() {
        doThrow(new IllegalArgumentException("secret")).when(fusion).fuse(any(), any(), any());
        failed(search(all).get(2), RetrievalDiagnostics.Failure.FUSION_FAILED);
        verifyNoInteractions(rerank);
    }

    @Test
    void rerankFailureReturnsNoFallback() {
        when(rerank.rerank(any(), any(), any())).thenThrow(new IllegalStateException("secret"));
        List<RetrievalResult> results = search(all);
        assertThat(results.get(2).chunks()).hasSize(1);
        failed(results.get(3), RetrievalDiagnostics.Failure.RERANK_FAILED);
    }

    @Test
    void internalFailureIsSafe() {
        when(vector.retrieve(any(), any())).thenThrow(new IllegalStateException("secret"));
        failed(search(all).getFirst(), RetrievalDiagnostics.Failure.INTERNAL_ERROR);
    }

    @Test
    void invalidRequestsDoNotReadSnapshot() {
        assertThatThrownBy(() -> service.search(0L, "query", all, parameters))
                .isInstanceOf(com.lnjecit.knowledge.assistant.common.error.BusinessException.class);
        assertThatThrownBy(() -> search(List.of(RetrievalStrategy.VECTOR, RetrievalStrategy.VECTOR)))
                .isInstanceOf(com.lnjecit.knowledge.assistant.common.error.BusinessException.class);
        verifyNoInteractions(snapshots);
    }

    @Test
    void requestOrderAndScoresPreserved() {
        List<RetrievalResult> results =
                search(List.of(RetrievalStrategy.BM25, RetrievalStrategy.VECTOR, RetrievalStrategy.HYBRID_RRF));
        assertThat(results)
                .extracting(r -> r.diagnostics().requestedStrategy())
                .containsExactly(RetrievalStrategy.BM25, RetrievalStrategy.VECTOR, RetrievalStrategy.HYBRID_RRF);
        RankedChunk c = results.getLast().chunks().getFirst();
        assertThat(c.vectorRank()).isEqualTo(1);
        assertThat(c.vectorDistance()).isEqualTo(.1);
        assertThat(c.bm25Score()).isEqualTo(2);
        assertThat(c.bm25Rank()).isEqualTo(1);
        assertThat(c.rrfScore()).isPositive();
    }

    @Test
    void hybridPoolCanExceedSingleChannelPool() {
        RetrievalParameters larger = new RetrievalParameters(1, 1, 60, 2, 2);
        assertThat(service.search(1L, "query", List.of(RetrievalStrategy.HYBRID_RRF), larger)
                        .getFirst()
                        .diagnostics()
                        .status())
                .isEqualTo(RetrievalStatus.SUCCESS);
    }

    @Test
    void oneEmptyChannelStillFuses() {
        when(vector.retrieve(any(), any())).thenReturn(new VectorRetriever.Recall(List.of(), List.of(), null));
        assertThat(search(all).get(2).diagnostics().status()).isEqualTo(RetrievalStatus.SUCCESS);
        when(keyword.retrieve(any())).thenReturn(new KeywordRetriever.Recall(List.of(), List.of(), null));
        assertThat(search(all).getLast().diagnostics().status()).isEqualTo(RetrievalStatus.EMPTY_RESULT);
    }

    @Test
    void missingOptionalBeansAreFailures() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        service = new RetrievalService(
                snapshots,
                beans.getBeanProvider(QueryEmbeddingService.class),
                vector,
                beans.getBeanProvider(KeywordRetriever.class),
                fusion,
                rerank);
        List<RetrievalResult> results = search(all);
        failed(results.getFirst(), RetrievalDiagnostics.Failure.EMBEDDING_FAILED);
        failed(results.get(1), RetrievalDiagnostics.Failure.BM25_FAILED);
    }

    @Test
    void finalTruncationHappensAfterFullRerank() {
        RankedChunk first = chunk(true);
        RankedChunk second = new RankedChunk(5L, 2L, 3L, 1L, "body", 0, 4, null, 2, .2, null, null, null, null, null);
        when(vector.retrieve(any(), any()))
                .thenReturn(new VectorRetriever.Recall(List.of(first, second), List.of(), null));
        when(rerank.rerank(any(), any(), any())).thenAnswer(call -> {
            List<RankedChunk> pool = call.getArgument(1);
            assertThat(pool).hasSize(2).allSatisfy(c -> assertThat(c.finalRank())
                    .isNull());
            return pool;
        });
        List<RetrievalResult> results = search(all);
        assertThat(results).allSatisfy(r -> assertThat(r.chunks()).hasSize(1));
        assertThat(results.get(2).diagnostics().stages().getLast().candidateCount())
                .isEqualTo(2);
    }

    @Test
    void continuousRanksAndSameSnapshotObject() {
        RankedChunk second = new RankedChunk(5L, 2L, 3L, 1L, "body", 0, 4, null, 2, .2, null, null, null, null, null);
        when(vector.retrieve(any(), any()))
                .thenReturn(new VectorRetriever.Recall(List.of(chunk(true), second), List.of(), null));
        List<RetrievalResult> results = service.search(
                1L,
                "query",
                List.of(RetrievalStrategy.VECTOR, RetrievalStrategy.HYBRID_RRF),
                new RetrievalParameters(2, 2, 60, 2, 2));
        assertThat(results.getFirst().chunks())
                .extracting(RankedChunk::finalRank)
                .containsExactly(1, 2);
        org.mockito.ArgumentCaptor<RetrievalContext> vectorContext =
                org.mockito.ArgumentCaptor.forClass(RetrievalContext.class);
        org.mockito.ArgumentCaptor<RetrievalContext> keywordContext =
                org.mockito.ArgumentCaptor.forClass(RetrievalContext.class);
        verify(vector).retrieve(vectorContext.capture(), any());
        verify(keyword).retrieve(keywordContext.capture());
        assertThat(vectorContext.getValue().snapshot())
                .isSameAs(snapshot)
                .isSameAs(keywordContext.getValue().snapshot());
    }

    static void failed(RetrievalResult result, RetrievalDiagnostics.Failure failure) {
        assertThat(result.chunks()).isEmpty();
        assertThat(result.diagnostics().status()).isEqualTo(RetrievalStatus.FAILED);
        assertThat(result.diagnostics().actualStrategy()).isNull();
        assertThat(result.diagnostics().degraded()).isFalse();
        assertThat(result.diagnostics().error()).isEqualTo(failure);
    }
}
