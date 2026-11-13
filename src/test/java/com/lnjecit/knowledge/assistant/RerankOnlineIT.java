package com.lnjecit.knowledge.assistant;

import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelFailures;
import com.lnjecit.knowledge.assistant.infrastructure.ai.RerankCandidate;
import com.lnjecit.knowledge.assistant.infrastructure.ai.RerankConfiguration;
import com.lnjecit.knowledge.assistant.infrastructure.ai.RerankResult;
import com.lnjecit.knowledge.assistant.infrastructure.ai.Reranker;
import com.lnjecit.knowledge.assistant.retrieval.rerank.RerankProperties;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

// IT 命名不在默认 Surefire 范围；即使显式执行，也必须先通过独立授权和配置校验。
class RerankOnlineIT {
    @Test
    @Timeout(30)
    void rerankOnce() {
        String stage = "CONFIGURATION";
        long start = System.nanoTime();
        try {
            RerankProperties properties = OnlineConfiguration.rerank(OnlineConfiguration.load());
            properties.setTimeoutSeconds(20);
            Reranker reranker = new RerankConfiguration().reranker(properties);
            List<RerankCandidate> candidates = List.of(
                    new RerankCandidate("spring-di", "Spring Boot 使用依赖注入管理 Bean，由容器注入依赖对象。"),
                    new RerankCandidate("postgres", "PostgreSQL 是关系型数据库，支持事务。"),
                    new RerankCandidate("weather", "晴天适合户外散步。"));
            stage = "RERANK";
            // 单次调用，不为验证排序质量或获取 usage 追加供应商请求。
            List<RerankResult> results = reranker.rerank("Spring Boot 如何管理并注入 Bean 依赖？", candidates, 3);
            HashSet<String> seen = new HashSet<>();
            if (results.size() != 3) throw ModelFailures.rerankFailure(ModelFailures.Kind.INVALID_RESPONSE);
            for (RerankResult result : results) {
                if (!List.of("spring-di", "postgres", "weather").contains(result.id())
                        || !seen.add(result.id())
                        || !Double.isFinite(result.score())
                        || result.score() < 0
                        || result.score() > 1) throw ModelFailures.rerankFailure(ModelFailures.Kind.INVALID_RESPONSE);
            }
            // 仅输出固定 ID 和数值；不输出 Endpoint（可能包含业务空间）、文本或响应正文。
            System.out.printf(
                    "stage=RERANK status=SUCCESS model=qwen3-rerank endpoint=configured-https candidates=3 topN=3 elapsedMs=%d usage=unavailable results=%s%n",
                    (System.nanoTime() - start) / 1_000_000, results);
        } catch (RuntimeException ex) {
            ModelFailures.Kind kind =
                    "CONFIGURATION".equals(stage) ? ModelFailures.Kind.CONFIGURATION : ModelFailures.classify(ex);
            System.out.printf(
                    "stage=%s status=FAILED kind=%s elapsedMs=%d%n",
                    stage, kind, (System.nanoTime() - start) / 1_000_000);
            throw ModelFailures.safe(stage, ex);
        }
    }
}
