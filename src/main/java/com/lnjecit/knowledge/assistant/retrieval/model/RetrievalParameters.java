package com.lnjecit.knowledge.assistant.retrieval.model;

import java.util.Objects;

public record RetrievalParameters(int vectorTopK, int bm25TopK, int rrfK, int fusionTopK, int finalTopK) {
    public RetrievalParameters {
        range(vectorTopK, 100);
        range(bm25TopK, 100);
        range(rrfK, 1000);
        range(fusionTopK, 100);
        range(finalTopK, 100);
    }

    public static RetrievalParameters defaults() {
        return new RetrievalParameters(20, 20, 60, 20, 5);
    }

    private static void range(int n, int max) {
        if (n <= 0 || n > max) throw new IllegalArgumentException("RETRIEVAL_PARAMETER_RANGE");
    }

    public void validate(RetrievalStrategy strategy) {
        Objects.requireNonNull(strategy);
        int pool =
                switch (strategy) {
                    case VECTOR -> vectorTopK;
                    case BM25 -> bm25TopK;
                    case HYBRID_RRF, HYBRID_RERANK -> fusionTopK;
                };
        if (finalTopK > pool) throw new IllegalArgumentException("RETRIEVAL_CANDIDATE_POOL");
        if ((strategy == RetrievalStrategy.HYBRID_RRF || strategy == RetrievalStrategy.HYBRID_RERANK)
                && fusionTopK > vectorTopK + bm25TopK) throw new IllegalArgumentException("RETRIEVAL_FUSION_POOL");
    }
}
