package com.lnjecit.knowledge.assistant.retrieval.rerank;

import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures;
import com.lnjecit.knowledge.assistant.infrastructure.ai.RerankCandidate;
import com.lnjecit.knowledge.assistant.infrastructure.ai.RerankResult;
import com.lnjecit.knowledge.assistant.infrastructure.ai.Reranker;
import com.lnjecit.knowledge.assistant.retrieval.model.RankedChunk;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class RerankService {
    private final ObjectProvider<Reranker> provider;

    public RerankService(ObjectProvider<Reranker> provider) {
        this.provider = provider;
    }

    public List<RankedChunk> rerank(String query, List<RankedChunk> fusedCandidates, RetrievalParameters parameters) {
        if (query == null || query.isBlank() || fusedCandidates == null || parameters == null)
            throw new IllegalArgumentException("RERANK_INPUT_INVALID");
        List<RankedChunk> input = new ArrayList<>(fusedCandidates);
        if (input.size() > parameters.fusionTopK()) throw new IllegalArgumentException("RERANK_POOL_INVALID");
        Map<String, RankedChunk> evidence = new LinkedHashMap<>();
        RankedChunk previous = null;
        for (RankedChunk c : input) {
            // 只接受完整融合证据；顺序必须与上游 RRF 契约一致，不重新计算分数。
            if (c == null
                    || c.chunkId() <= 0
                    || c.documentId() <= 0
                    || c.indexVersionId() <= 0
                    || c.knowledgeBaseId() <= 0
                    || c.content().isBlank()
                    || c.rrfScore() == null
                    || c.rrfScore() <= 0
                    || c.rerankScore() != null
                    || c.finalRank() != null
                    || (c.vectorRank() == null && c.bm25Rank() == null)
                    || (previous != null
                            && (previous.rrfScore() < c.rrfScore()
                                    || (Double.compare(previous.rrfScore(), c.rrfScore()) == 0
                                            && previous.chunkId() > c.chunkId())))
                    || evidence.putIfAbsent(c.chunkId().toString(), c) != null)
                throw new IllegalArgumentException("RERANK_CANDIDATE_INVALID");
            previous = c;
        }
        if (input.isEmpty()) return List.of();
        try {
            Reranker reranker = provider.getIfAvailable();
            if (reranker == null) throw ModelFailures.rerankFailure(ModelFailures.Kind.CONFIGURATION);
            // 十进制业务 ID 是唯一映射依据，完整候选池只请求一次，不使用 finalTopK。
            List<RerankCandidate> request = input.stream()
                    .map(c -> new RerankCandidate(c.chunkId().toString(), c.content()))
                    .toList();
            List<RerankResult> results = reranker.rerank(query, request, request.size());
            if (results == null || results.size() != input.size()) throw invalidResponse();
            Map<String, Double> scores = new HashMap<>();
            for (RerankResult result : results) {
                if (result == null
                        || !evidence.containsKey(result.id())
                        || !Double.isFinite(result.score())
                        || result.score() < 0
                        || result.score() > 1
                        || scores.putIfAbsent(result.id(), result.score()) != null) throw invalidResponse();
            }
            if (scores.size() != evidence.size()) throw invalidResponse();
            // 稳定排序保留原融合优先级，不依赖供应商返回顺序。
            input.sort(Comparator.comparingDouble(
                            (RankedChunk c) -> scores.get(c.chunkId().toString()))
                    .reversed());
            return input.stream()
                    .map(c -> copy(c, scores.get(c.chunkId().toString())))
                    .toList();
        } catch (RuntimeException ex) {
            // 未来替换适配器也不能泄露底层消息或返回部分成功。
            throw ModelFailures.safe("RERANK", ex);
        }
    }

    private static ModelFailures.SafeModelException invalidResponse() {
        return ModelFailures.rerankFailure(ModelFailures.Kind.INVALID_RESPONSE);
    }

    private static RankedChunk copy(RankedChunk c, double score) {
        return new RankedChunk(
                c.chunkId(),
                c.documentId(),
                c.indexVersionId(),
                c.knowledgeBaseId(),
                c.content(),
                c.sourceStart(),
                c.sourceEnd(),
                c.headingPath(),
                c.vectorRank(),
                c.vectorDistance(),
                c.bm25Rank(),
                c.bm25Score(),
                c.rrfScore(),
                score,
                null);
    }
}
