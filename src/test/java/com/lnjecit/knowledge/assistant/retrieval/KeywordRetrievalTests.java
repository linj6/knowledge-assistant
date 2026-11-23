package com.lnjecit.knowledge.assistant.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lnjecit.knowledge.assistant.infrastructure.elasticsearch.EsChunkDocument;
import com.lnjecit.knowledge.assistant.retrieval.keyword.KeywordChunkMapper;
import com.lnjecit.knowledge.assistant.retrieval.keyword.KeywordChunkRepository;
import com.lnjecit.knowledge.assistant.retrieval.keyword.KeywordRetriever;
import com.lnjecit.knowledge.assistant.retrieval.keyword.KeywordSearchRepository;
import com.lnjecit.knowledge.assistant.retrieval.model.RankedChunk;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalContext;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalDiagnostics;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStatus;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStrategy;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedDocument;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedVersionSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeywordRetrievalTests {
    private final PublishedVersionSnapshot snapshot =
            PublishedVersionSnapshot.of(1L, List.of(new PublishedDocument(2L, 3L)));

    private RetrievalContext context(PublishedVersionSnapshot s) {
        return new RetrievalContext("r", "query", RetrievalStrategy.BM25, RetrievalParameters.defaults(), s);
    }

    @Test
    void applicationTermsLimitIsClassifiedWithoutDependencies() {
        co.elastic.clients.elasticsearch.ElasticsearchClient client =
                mock(co.elastic.clients.elasticsearch.ElasticsearchClient.class);
        KeywordChunkRepository chunks = mock(KeywordChunkRepository.class);
        PublishedVersionSnapshot large = PublishedVersionSnapshot.of(
                1L,
                java.util.stream.LongStream.rangeClosed(1, 2001)
                        .mapToObj(id -> new PublishedDocument(id, id))
                        .toList());
        assertThat(new KeywordRetriever(new KeywordSearchRepository(client), chunks)
                        .retrieve(context(large))
                        .failure())
                .isEqualTo(RetrievalDiagnostics.Failure.TERMS_LIMIT_EXCEEDED);
        verifyNoInteractions(client, chunks);
    }

    @Test
    void emptySnapshotDoesNotAccessDependencies() {
        KeywordSearchRepository search = mock(KeywordSearchRepository.class);
        KeywordChunkRepository chunks = mock(KeywordChunkRepository.class);
        assertThat(new KeywordRetriever(search, chunks)
                        .retrieve(context(PublishedVersionSnapshot.of(1L, List.of())))
                        .status())
                .isEqualTo(RetrievalStatus.EMPTY_RESULT);
        verifyNoInteractions(search, chunks);
    }

    @Test
    void failedDependencyReturnsNoPartialCandidates() {
        KeywordSearchRepository search = mock(KeywordSearchRepository.class);
        KeywordChunkRepository chunks = mock(KeywordChunkRepository.class);
        when(search.search(snapshot, "query", 20)).thenThrow(new IllegalStateException("private"));
        KeywordRetriever.Recall recall = new KeywordRetriever(search, chunks).retrieve(context(snapshot));
        assertThat(recall.status()).isEqualTo(RetrievalStatus.FAILED);
        assertThat(recall.failure()).isEqualTo(RetrievalDiagnostics.Failure.BM25_FAILED);
        assertThat(recall.chunks()).isEmpty();
        verifyNoInteractions(chunks);
    }

    @Test
    void pgAuthorityAndSourceEnrichment() {
        KeywordChunkMapper mapper = mock(KeywordChunkMapper.class);
        KeywordChunkRepository repository = new KeywordChunkRepository(mapper);
        KeywordSearchRepository.Candidate candidate =
                new KeywordSearchRepository.Candidate(new EsChunkDocument("10", "1", "2", "3", 0, "body"), 2.5);
        when(mapper.find(List.of(10L)))
                .thenReturn(
                        List.of(new KeywordChunkMapper.Row(10L, 2L, 3L, 1L, "body", 0, 0, 4, "heading", 3L, 1L, 2L)));
        RankedChunk result = repository.complete(snapshot, List.of(candidate)).getFirst();
        assertThat(result.bm25Score()).isEqualTo(2.5);
        assertThat(result.bm25Rank()).isEqualTo(1);
        assertThat(result.headingPath()).isEqualTo("heading");
        assertThat(result.vectorRank()).isNull();
        for (KeywordChunkMapper.Row bad : List.of(
                new KeywordChunkMapper.Row(10L, 2L, 3L, 1L, "changed", 0, 0, 4, null, 3L, 1L, 2L),
                new KeywordChunkMapper.Row(10L, 2L, 3L, 1L, "body", 0, 0, 4, null, 99L, 1L, 2L),
                new KeywordChunkMapper.Row(10L, 9L, 3L, 1L, "body", 0, 0, 4, null, 3L, 1L, 9L))) {
            when(mapper.find(List.of(10L))).thenReturn(List.of(bad));
            assertThatThrownBy(() -> repository.complete(snapshot, List.of(candidate)))
                    .hasMessage("BM25_FAILED");
        }
        when(mapper.find(List.of(10L))).thenReturn(List.of());
        assertThatThrownBy(() -> repository.complete(snapshot, List.of(candidate)))
                .hasMessage("BM25_FAILED");
    }
}
