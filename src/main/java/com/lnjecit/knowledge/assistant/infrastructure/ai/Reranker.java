package com.lnjecit.knowledge.assistant.infrastructure.ai;

import java.util.List;

public interface Reranker {
    List<RerankResult> rerank(String query, List<RerankCandidate> candidates, int topN);
}
