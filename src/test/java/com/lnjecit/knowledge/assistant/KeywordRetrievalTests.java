package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lnjecit.knowledge.assistant.ingestion.indexing.EsChunkDocument;
import com.lnjecit.knowledge.assistant.retrieval.KeywordChunkMapper;
import com.lnjecit.knowledge.assistant.retrieval.KeywordChunkRepository;
import com.lnjecit.knowledge.assistant.retrieval.KeywordRetriever;
import com.lnjecit.knowledge.assistant.retrieval.KeywordSearchRepository;
import com.lnjecit.knowledge.assistant.retrieval.PublishedDocument;
import com.lnjecit.knowledge.assistant.retrieval.PublishedVersionSnapshot;
import com.lnjecit.knowledge.assistant.retrieval.RankedChunk;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalContext;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalDiagnostics;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalParameters;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalStatus;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalStrategy;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeywordRetrievalTests {
    private final PublishedVersionSnapshot snapshot =
            PublishedVersionSnapshot.of(1L, List.of(new PublishedDocument(2L, 3L)));

    private RetrievalContext context(PublishedVersionSnapshot s) {
        return new RetrievalContext("r", "query", RetrievalStrategy.BM25, RetrievalParameters.defaults(), s);
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
