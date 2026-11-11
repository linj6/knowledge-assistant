package com.lnjecit.knowledge.assistant.retrieval.model;

import java.util.List;
import java.util.Objects;

public record RetrievalDiagnostics(
        RetrievalStrategy requestedStrategy,
        RetrievalStrategy actualStrategy,
        boolean degraded,
        RetrievalStatus status,
        String snapshotHash,
        RetrievalParameters parameters,
        List<Stage> stages,
        Failure error) {
    public enum Failure {
        SNAPSHOT_READ_FAILED,
        SNAPSHOT_INVALID,
        TERMS_LIMIT_EXCEEDED,
        EMBEDDING_FAILED,
        VECTOR_FAILED,
        BM25_FAILED,
        FUSION_FAILED,
        RERANK_FAILED,
        INTERNAL_ERROR
    }

    public enum Phase {
        EMBEDDING,
        VECTOR,
        BM25,
        FUSION,
        RERANK
    }

    public record Candidate(Long chunkId, int rank, Double score) {
        public Candidate {
            Objects.requireNonNull(chunkId);
            if (rank <= 0 || (score != null && !Double.isFinite(score)))
                throw new IllegalArgumentException("DIAGNOSTIC_CANDIDATE_INVALID");
        }
    }

    public record Stage(Phase phase, long elapsedMillis, int candidateCount, List<Candidate> candidates) {
        public Stage {
            Objects.requireNonNull(phase);
            candidates = List.copyOf(candidates);
            if (elapsedMillis < 0 || candidateCount != candidates.size())
                throw new IllegalArgumentException("DIAGNOSTIC_STAGE_INVALID");
        }
    }

    public RetrievalDiagnostics {
        Objects.requireNonNull(requestedStrategy);
        Objects.requireNonNull(status);
        parameters.validate(requestedStrategy);
        stages = List.copyOf(stages);
        if (degraded
                || (status == RetrievalStatus.FAILED
                        ? actualStrategy != null || error == null
                        : actualStrategy != requestedStrategy || error != null || snapshotHash == null))
            throw new IllegalArgumentException("RETRIEVAL_STATE_INVALID");
        if (stages.stream().map(Stage::phase).distinct().count() != stages.size())
            throw new IllegalArgumentException("DIAGNOSTIC_DUPLICATE_STAGE");
    }
}
