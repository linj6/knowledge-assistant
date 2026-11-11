package com.lnjecit.knowledge.assistant.retrieval.vector;

import com.lnjecit.knowledge.assistant.retrieval.embedding.QueryEmbeddingService;
import com.lnjecit.knowledge.assistant.retrieval.embedding.QueryVector;
import com.lnjecit.knowledge.assistant.retrieval.model.RankedChunk;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalContext;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalDiagnostics;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStatus;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class VectorRetriever {
    private final ObjectProvider<QueryEmbeddingService> embedding;
    private final VectorSearchRepository repository;

    public VectorRetriever(ObjectProvider<QueryEmbeddingService> embedding, VectorSearchRepository repository) {
        this.embedding = embedding;
        this.repository = repository;
    }

    public record Recall(
            List<RankedChunk> chunks, List<RetrievalDiagnostics.Stage> stages, RetrievalDiagnostics.Failure failure) {
        public Recall {
            chunks = List.copyOf(chunks);
            stages = List.copyOf(stages);
        }

        public RetrievalStatus status() {
            return failure != null
                    ? RetrievalStatus.FAILED
                    : chunks.isEmpty() ? RetrievalStatus.EMPTY_RESULT : RetrievalStatus.SUCCESS;
        }
    }

    public Recall retrieve(RetrievalContext context) {
        if (context.snapshot().documents().isEmpty()) return new Recall(List.of(), List.of(), null);
        long start = System.nanoTime();
        QueryVector vector;
        try {
            vector = embedding.getObject().embed(context.query());
        } catch (RuntimeException failure) {
            return new Recall(
                    List.of(),
                    List.of(stage(RetrievalDiagnostics.Phase.EMBEDDING, start, List.of())),
                    RetrievalDiagnostics.Failure.EMBEDDING_FAILED);
        }
        RetrievalDiagnostics.Stage embedded = stage(RetrievalDiagnostics.Phase.EMBEDDING, start, List.of());
        Recall recall = retrieve(context, vector);
        List<RetrievalDiagnostics.Stage> stages = new ArrayList<>();
        stages.add(embedded);
        stages.addAll(recall.stages());
        return new Recall(recall.chunks(), stages, recall.failure());
    }
    /** 已生成向量入口供后续多策略对照复用；不读取或刷新快照。 */
    public Recall retrieve(RetrievalContext context, QueryVector vector) {
        if (context.snapshot().documents().isEmpty()) return new Recall(List.of(), List.of(), null);
        long start = System.nanoTime();
        try {
            List<VectorSearchMapper.Row> rows = repository.search(
                    context.snapshot(), vector, context.parameters().vectorTopK());
            long queryElapsedMillis = (System.nanoTime() - start) / 1_000_000;
            List<RankedChunk> chunks = new ArrayList<>();
            List<RetrievalDiagnostics.Candidate> candidates = new ArrayList<>();
            for (VectorSearchMapper.Row row : rows) {
                int rank = chunks.size() + 1;
                chunks.add(new RankedChunk(
                        row.chunkId(),
                        row.documentId(),
                        row.indexVersionId(),
                        row.knowledgeBaseId(),
                        row.content(),
                        row.sourceStart(),
                        row.sourceEnd(),
                        row.headingPath(),
                        rank,
                        row.distance(),
                        null,
                        null,
                        null,
                        null,
                        null));
                candidates.add(new RetrievalDiagnostics.Candidate(row.chunkId(), rank, row.distance()));
            }
            return new Recall(
                    chunks,
                    List.of(new RetrievalDiagnostics.Stage(
                            RetrievalDiagnostics.Phase.VECTOR, queryElapsedMillis, candidates.size(), candidates)),
                    null);
        } catch (RuntimeException failure) {
            return new Recall(
                    List.of(),
                    List.of(stage(RetrievalDiagnostics.Phase.VECTOR, start, List.of())),
                    RetrievalDiagnostics.Failure.VECTOR_FAILED);
        }
    }

    private static RetrievalDiagnostics.Stage stage(
            RetrievalDiagnostics.Phase phase, long start, List<RetrievalDiagnostics.Candidate> candidates) {
        return new RetrievalDiagnostics.Stage(
                phase, (System.nanoTime() - start) / 1_000_000, candidates.size(), candidates);
    }
}
