package com.lnjecit.knowledge.assistant.retrieval.model;

import java.util.Objects;

public record RankedChunk(
        Long chunkId,
        Long documentId,
        Long indexVersionId,
        Long knowledgeBaseId,
        String content,
        int sourceStart,
        int sourceEnd,
        String headingPath,
        Integer vectorRank,
        Double vectorDistance,
        Integer bm25Rank,
        Double bm25Score,
        Double rrfScore,
        Double rerankScore,
        Integer finalRank) {
    public RankedChunk {
        Objects.requireNonNull(chunkId);
        Objects.requireNonNull(documentId);
        Objects.requireNonNull(indexVersionId);
        Objects.requireNonNull(knowledgeBaseId);
        Objects.requireNonNull(content);
        if (sourceStart < 0 || sourceEnd <= sourceStart) throw new IllegalArgumentException("CHUNK_SOURCE_INVALID");
        rank(vectorRank);
        rank(bm25Rank);
        rank(finalRank);
        if ((vectorRank == null) != (vectorDistance == null) || (bm25Rank == null) != (bm25Score == null))
            throw new IllegalArgumentException("CHUNK_CHANNEL_INVALID");
        score(vectorDistance);
        score(bm25Score);
        score(rrfScore);
        score(rerankScore);
    }

    private static void rank(Integer n) {
        if (n != null && n <= 0) throw new IllegalArgumentException("CHUNK_RANK_INVALID");
    }

    private static void score(Double n) {
        if (n != null && !Double.isFinite(n)) throw new IllegalArgumentException("CHUNK_SCORE_INVALID");
    }
}
