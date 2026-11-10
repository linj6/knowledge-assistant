package com.lnjecit.knowledge.assistant.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class RrfFusionService {
    /** 仅接收两路成功的完整召回；调用方负责失败编排和 FUSION 阶段计时。 */
    public List<RankedChunk> fuse(List<RankedChunk> vector, List<RankedChunk> bm25, RetrievalParameters parameters) {
        Objects.requireNonNull(parameters);
        parameters.validate(RetrievalStrategy.HYBRID_RRF);
        validate(vector, true, parameters.vectorTopK());
        validate(bm25, false, parameters.bm25TopK());
        Map<Long, RankedChunk> merged = new HashMap<>();
        for (RankedChunk chunk : vector) merged.put(chunk.chunkId(), chunk);
        for (RankedChunk chunk : bm25) {
            RankedChunk previous = merged.get(chunk.chunkId());
            // 相同 ID 必须代表完全一致的证据，不能用去重掩盖索引数据损坏。
            if (previous != null) {
                if (!Objects.equals(previous.documentId(), chunk.documentId())
                        || !Objects.equals(previous.indexVersionId(), chunk.indexVersionId())
                        || !Objects.equals(previous.knowledgeBaseId(), chunk.knowledgeBaseId())
                        || !Objects.equals(previous.content(), chunk.content())
                        || previous.sourceStart() != chunk.sourceStart()
                        || previous.sourceEnd() != chunk.sourceEnd()
                        || !Objects.equals(previous.headingPath(), chunk.headingPath()))
                    throw new IllegalArgumentException("FUSION_EVIDENCE_MISMATCH");
                chunk = copy(chunk, previous.vectorRank(), previous.vectorDistance(), null);
            }
            merged.put(chunk.chunkId(), chunk);
        }
        List<RankedChunk> result = new ArrayList<>();
        for (RankedChunk chunk : merged.values()) {
            // 只累加排名贡献，原始距离和 BM25 分数仅作为证据保留。
            double score = contribution(chunk.vectorRank(), parameters.rrfK())
                    + contribution(chunk.bm25Rank(), parameters.rrfK());
            result.add(copy(chunk, chunk.vectorRank(), chunk.vectorDistance(), score));
        }
        // 完整融合后排序再截断，同分按 Long 数值排序，避免依赖 Map 遍历顺序。
        result.sort(Comparator.comparing(RankedChunk::rrfScore).reversed().thenComparing(RankedChunk::chunkId));
        return List.copyOf(result.subList(0, Math.min(result.size(), parameters.fusionTopK())));
    }

    private static double contribution(Integer rank, int k) {
        return rank == null ? 0 : 1.0 / (k + rank);
    }

    private static void validate(List<RankedChunk> chunks, boolean vector, int limit) {
        Objects.requireNonNull(chunks);
        if (chunks.size() > limit) throw new IllegalArgumentException("FUSION_POOL_EXCEEDED");
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < chunks.size(); i++) {
            RankedChunk chunk = Objects.requireNonNull(chunks.get(i));
            Integer rank = vector ? chunk.vectorRank() : chunk.bm25Rank();
            if (rank == null
                    || rank != i + 1
                    || rank > limit
                    || (vector ? chunk.bm25Rank() != null : chunk.vectorRank() != null)
                    || chunk.rrfScore() != null
                    || chunk.rerankScore() != null
                    || chunk.finalRank() != null
                    || !ids.add(chunk.chunkId())) throw new IllegalArgumentException("FUSION_INPUT_INVALID");
        }
    }

    private static RankedChunk copy(RankedChunk c, Integer rank, Double distance, Double score) {
        return new RankedChunk(
                c.chunkId(),
                c.documentId(),
                c.indexVersionId(),
                c.knowledgeBaseId(),
                c.content(),
                c.sourceStart(),
                c.sourceEnd(),
                c.headingPath(),
                rank,
                distance,
                c.bm25Rank(),
                c.bm25Score(),
                score,
                null,
                null);
    }
}
