package com.lnjecit.knowledge.assistant.retrieval.keyword;

import com.lnjecit.knowledge.assistant.infrastructure.logging.FailureLog;
import com.lnjecit.knowledge.assistant.retrieval.model.RankedChunk;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalContext;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalDiagnostics;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStatus;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.TermsLimitExceededException;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "assistant.elasticsearch.enabled", havingValue = "true")
public class KeywordRetriever {
    private final KeywordSearchRepository search;
    private final KeywordChunkRepository chunks;

    public KeywordRetriever(KeywordSearchRepository search, KeywordChunkRepository chunks) {
        this.search = search;
        this.chunks = chunks;
    }

    public record Recall(
            List<RankedChunk> chunks, List<RetrievalDiagnostics.Stage> stages, RetrievalDiagnostics.Failure failure) {
        public Recall {
            chunks = List.copyOf(chunks);
            stages = List.copyOf(stages);
        }

        public RetrievalStatus status() {
            if (failure != null) return RetrievalStatus.FAILED;
            return chunks.isEmpty() ? RetrievalStatus.EMPTY_RESULT : RetrievalStatus.SUCCESS;
        }
    }

    public Recall retrieve(RetrievalContext context) {
        // 空快照无需访问任何依赖；后续通道始终复用入口快照。
        if (context.snapshot().documents().isEmpty()) return new Recall(List.of(), List.of(), null);
        long start = System.nanoTime();
        try {
            List<RankedChunk> result = chunks.complete(
                    context.snapshot(),
                    search.search(
                            context.snapshot(),
                            context.query(),
                            context.parameters().bm25TopK()));
            List<RetrievalDiagnostics.Candidate> candidates = result.stream()
                    .map(c -> new RetrievalDiagnostics.Candidate(c.chunkId(), c.bm25Rank(), c.bm25Score()))
                    .toList();
            return new Recall(result, List.of(stage(start, candidates)), null);
        } catch (TermsLimitExceededException failure) {
            return new Recall(
                    List.of(), List.of(stage(start, List.of())), RetrievalDiagnostics.Failure.TERMS_LIMIT_EXCEEDED);
        } catch (RuntimeException failure) {
            FailureLog.error(LoggerFactory.getLogger(getClass()), "KeywordRetriever", failure);
            return new Recall(List.of(), List.of(stage(start, List.of())), RetrievalDiagnostics.Failure.BM25_FAILED);
        }
    }

    private static RetrievalDiagnostics.Stage stage(long start, List<RetrievalDiagnostics.Candidate> candidates) {
        return new RetrievalDiagnostics.Stage(
                RetrievalDiagnostics.Phase.BM25,
                (System.nanoTime() - start) / 1_000_000,
                candidates.size(),
                candidates);
    }
}
