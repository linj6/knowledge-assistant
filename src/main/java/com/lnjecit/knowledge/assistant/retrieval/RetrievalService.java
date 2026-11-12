package com.lnjecit.knowledge.assistant.retrieval;

import com.lnjecit.knowledge.assistant.common.RequestIdContext;
import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import com.lnjecit.knowledge.assistant.retrieval.embedding.QueryEmbeddingService;
import com.lnjecit.knowledge.assistant.retrieval.embedding.QueryVector;
import com.lnjecit.knowledge.assistant.retrieval.fusion.RrfFusionService;
import com.lnjecit.knowledge.assistant.retrieval.keyword.KeywordRetriever;
import com.lnjecit.knowledge.assistant.retrieval.model.RankedChunk;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalContext;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalDiagnostics;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalDiagnostics.Candidate;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalDiagnostics.Failure;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalDiagnostics.Phase;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalDiagnostics.Stage;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalResult;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStatus;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStrategy;
import com.lnjecit.knowledge.assistant.retrieval.rerank.RerankService;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedVersionSnapshot;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedVersionSnapshotService;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.SnapshotInvalidException;
import com.lnjecit.knowledge.assistant.retrieval.vector.VectorRetriever;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class RetrievalService {
    private static final Logger LOG = LoggerFactory.getLogger(RetrievalService.class);
    private final PublishedVersionSnapshotService snapshots;
    private final ObjectProvider<QueryEmbeddingService> embeddings;
    private final VectorRetriever vectors;
    private final ObjectProvider<KeywordRetriever> keywords;
    private final RrfFusionService fusion;
    private final RerankService rerank;

    public RetrievalService(
            PublishedVersionSnapshotService snapshots,
            ObjectProvider<QueryEmbeddingService> embeddings,
            VectorRetriever vectors,
            ObjectProvider<KeywordRetriever> keywords,
            RrfFusionService fusion,
            RerankService rerank) {
        this.snapshots = snapshots;
        this.embeddings = embeddings;
        this.vectors = vectors;
        this.keywords = keywords;
        this.fusion = fusion;
        this.rerank = rerank;
    }

    private record Output(List<RankedChunk> chunks, List<Stage> stages, Failure failure) {
        private Output {
            chunks = List.copyOf(chunks);
            stages = List.copyOf(stages);
        }
    }

    public List<RetrievalResult> search(
            Long knowledgeBaseId, String query, List<RetrievalStrategy> strategies, RetrievalParameters parameters) {
        validate(knowledgeBaseId, query, strategies, parameters);
        List<RetrievalStrategy> requested = List.copyOf(strategies);
        PublishedVersionSnapshot snapshot;
        // 一次读取 PG 权威发布事实，所有通道共享同一不可变对象，不延长事务到网络调用。
        try {
            snapshot = snapshots.read(knowledgeBaseId);
        } catch (BusinessException failure) {
            if (failure.getErrorCode() == CommonErrorCode.KNOWLEDGE_BASE_NOT_FOUND) throw failure;
            com.lnjecit.knowledge.assistant.infrastructure.logging.FailureLog.error(
                    LOG, "SNAPSHOT_READ_FAILED", failure);
            return failedSnapshot(knowledgeBaseId, requested, parameters, Failure.SNAPSHOT_READ_FAILED);
        } catch (SnapshotInvalidException failure) {
            com.lnjecit.knowledge.assistant.infrastructure.logging.FailureLog.error(LOG, "SNAPSHOT_INVALID", failure);
            return failedSnapshot(knowledgeBaseId, requested, parameters, Failure.SNAPSHOT_INVALID);
        } catch (RuntimeException failure) {
            com.lnjecit.knowledge.assistant.infrastructure.logging.FailureLog.error(
                    org.slf4j.LoggerFactory.getLogger(RetrievalService.class), "RetrievalService", failure);
            return failedSnapshot(knowledgeBaseId, requested, parameters, Failure.SNAPSHOT_READ_FAILED);
        }
        Output empty = new Output(List.of(), List.of(), null);
        if (snapshot.documents().isEmpty())
            return results(knowledgeBaseId, requested, parameters, snapshot.snapshotHash(), empty, empty, empty, empty);
        boolean hybrid =
                requested.contains(RetrievalStrategy.HYBRID_RRF) || requested.contains(RetrievalStrategy.HYBRID_RERANK);
        // 固定顺序执行便于诊断和测试；共享向量避免同一问题重复请求模型。
        Output vector = requested.contains(RetrievalStrategy.VECTOR) || hybrid
                ? vector(query, parameters, snapshot, hybrid ? RetrievalStrategy.HYBRID_RRF : RetrievalStrategy.VECTOR)
                : empty;
        Output keyword = requested.contains(RetrievalStrategy.BM25) || hybrid
                ? keyword(query, parameters, snapshot, hybrid ? RetrievalStrategy.HYBRID_RRF : RetrievalStrategy.BM25)
                : empty;
        Output fused = hybrid ? fuse(vector, keyword, parameters) : empty;
        Output reranked =
                requested.contains(RetrievalStrategy.HYBRID_RERANK) ? rerank(query, fused, parameters) : empty;
        return results(
                knowledgeBaseId, requested, parameters, snapshot.snapshotHash(), vector, keyword, fused, reranked);
    }

    private static void validate(
            Long kb, String query, List<RetrievalStrategy> strategies, RetrievalParameters parameters) {
        if (kb == null
                || kb <= 0
                || query == null
                || query.isBlank()
                || strategies == null
                || strategies.isEmpty()
                || strategies.size() > 4
                || strategies.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(strategies).size() != strategies.size()
                || parameters == null) throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT);
        try {
            for (RetrievalStrategy strategy : strategies) parameters.validate(strategy);
        } catch (RuntimeException failure) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT);
        }
    }

    private Output vector(
            String query,
            RetrievalParameters parameters,
            PublishedVersionSnapshot snapshot,
            RetrievalStrategy contextStrategy) {
        List<Stage> stages = new ArrayList<>();
        long start = System.nanoTime();
        QueryVector vector;
        try {
            vector = embeddings.getObject().embed(query);
        } catch (RuntimeException failure) {
            com.lnjecit.knowledge.assistant.infrastructure.logging.FailureLog.error(
                    org.slf4j.LoggerFactory.getLogger(RetrievalService.class), "EMBEDDING", failure);
            return new Output(List.of(), List.of(stage(Phase.EMBEDDING, start, List.of())), Failure.EMBEDDING_FAILED);
        }
        stages.add(stage(Phase.EMBEDDING, start, List.of()));
        start = System.nanoTime();
        try {
            VectorRetriever.Recall recall = vectors.retrieve(
                    new RetrievalContext(RequestIdContext.current(), query, contextStrategy, parameters, snapshot),
                    vector);
            stages.addAll(recall.stages());
            return new Output(recall.chunks(), stages, recall.failure());
        } catch (RuntimeException failure) {
            com.lnjecit.knowledge.assistant.infrastructure.logging.FailureLog.error(
                    org.slf4j.LoggerFactory.getLogger(RetrievalService.class), "VECTOR", failure);
            stages.add(stage(Phase.VECTOR, start, List.of()));
            return new Output(List.of(), stages, Failure.INTERNAL_ERROR);
        }
    }

    private Output keyword(
            String query,
            RetrievalParameters parameters,
            PublishedVersionSnapshot snapshot,
            RetrievalStrategy contextStrategy) {
        long start = System.nanoTime();
        try {
            KeywordRetriever retriever = keywords.getIfAvailable();
            if (retriever == null)
                return new Output(List.of(), List.of(stage(Phase.BM25, start, List.of())), Failure.BM25_FAILED);
            KeywordRetriever.Recall recall = retriever.retrieve(
                    new RetrievalContext(RequestIdContext.current(), query, contextStrategy, parameters, snapshot));
            return new Output(recall.chunks(), recall.stages(), recall.failure());
        } catch (RuntimeException failure) {
            com.lnjecit.knowledge.assistant.infrastructure.logging.FailureLog.error(
                    org.slf4j.LoggerFactory.getLogger(RetrievalService.class), "BM25", failure);
            return new Output(List.of(), List.of(stage(Phase.BM25, start, List.of())), Failure.INTERNAL_ERROR);
        }
    }

    private Output fuse(Output vector, Output keyword, RetrievalParameters parameters) {
        List<Stage> stages = new ArrayList<>(vector.stages());
        stages.addAll(keyword.stages());
        // 单路成功不能冒充混合成功，失败时不返回部分候选，也不隐式降级。
        if (vector.failure() != null || keyword.failure() != null)
            return new Output(List.of(), stages, vector.failure() != null ? vector.failure() : keyword.failure());
        long start = System.nanoTime();
        try {
            List<RankedChunk> chunks = fusion.fuse(vector.chunks(), keyword.chunks(), parameters);
            stages.add(stage(Phase.FUSION, start, candidates(chunks, false)));
            return new Output(chunks, stages, null);
        } catch (RuntimeException failure) {
            com.lnjecit.knowledge.assistant.infrastructure.logging.FailureLog.error(
                    org.slf4j.LoggerFactory.getLogger(RetrievalService.class), "FUSION", failure);
            stages.add(stage(Phase.FUSION, start, List.of()));
            return new Output(List.of(), stages, Failure.FUSION_FAILED);
        }
    }

    private Output rerank(String query, Output fused, RetrievalParameters parameters) {
        if (fused.failure() != null || fused.chunks().isEmpty()) return fused;
        List<Stage> stages = new ArrayList<>(fused.stages());
        long start = System.nanoTime();
        try {
            // 完整融合池参与重排，finalTopK 最后执行，避免提前丢失可能被提升的候选。
            List<RankedChunk> chunks = rerank.rerank(query, fused.chunks(), parameters);
            stages.add(stage(Phase.RERANK, start, candidates(chunks, true)));
            return new Output(chunks, stages, null);
        } catch (RuntimeException failure) {
            com.lnjecit.knowledge.assistant.infrastructure.logging.FailureLog.error(
                    org.slf4j.LoggerFactory.getLogger(RetrievalService.class), "RERANK", failure);
            stages.add(stage(Phase.RERANK, start, List.of()));
            return new Output(List.of(), stages, Failure.RERANK_FAILED);
        }
    }

    private static List<Candidate> candidates(List<RankedChunk> chunks, boolean reranked) {
        List<Candidate> result = new ArrayList<>();
        for (RankedChunk chunk : chunks)
            result.add(new Candidate(
                    chunk.chunkId(), result.size() + 1, reranked ? chunk.rerankScore() : chunk.rrfScore()));
        return result;
    }

    private static Stage stage(Phase phase, long start, List<Candidate> candidates) {
        return new Stage(phase, (System.nanoTime() - start) / 1_000_000, candidates.size(), candidates);
    }

    private List<RetrievalResult> failedSnapshot(
            Long kb, List<RetrievalStrategy> requested, RetrievalParameters parameters, Failure failure) {
        Output failed = new Output(List.of(), List.of(), failure);
        return results(kb, requested, parameters, null, failed, failed, failed, failed);
    }

    private List<RetrievalResult> results(
            Long kb,
            List<RetrievalStrategy> requested,
            RetrievalParameters parameters,
            String hash,
            Output vector,
            Output keyword,
            Output fused,
            Output reranked) {
        List<RetrievalResult> results = new ArrayList<>();
        for (RetrievalStrategy strategy : requested) {
            Output output =
                    switch (strategy) {
                        case VECTOR -> vector;
                        case BM25 -> keyword;
                        case HYBRID_RRF -> fused;
                        case HYBRID_RERANK -> reranked;
                    };
            List<RankedChunk> chunks =
                    output.failure() == null ? finish(output.chunks(), parameters.finalTopK()) : List.of();
            RetrievalStatus status = output.failure() != null
                    ? RetrievalStatus.FAILED
                    : chunks.isEmpty() ? RetrievalStatus.EMPTY_RESULT : RetrievalStatus.SUCCESS;
            RetrievalDiagnostics diagnostics = new RetrievalDiagnostics(
                    strategy,
                    output.failure() == null ? strategy : null,
                    false,
                    status,
                    hash,
                    parameters,
                    output.stages(),
                    output.failure());
            LOG.atLevel(status == RetrievalStatus.FAILED ? org.slf4j.event.Level.DEBUG : org.slf4j.event.Level.INFO)
                    .log(
                            "requestId={} knowledgeBaseId={} requestedStrategy={} actualStrategy={} degraded=false status={} error={}",
                            RequestIdContext.current(),
                            kb,
                            strategy,
                            diagnostics.actualStrategy(),
                            status,
                            output.failure());
            for (Stage stage : output.stages())
                LOG.atLevel(status == RetrievalStatus.FAILED ? org.slf4j.event.Level.DEBUG : org.slf4j.event.Level.INFO)
                        .log(
                                "requestId={} knowledgeBaseId={} requestedStrategy={} actualStrategy={} degraded=false status={} phase={} elapsedMillis={} candidateCount={} error={}",
                                RequestIdContext.current(),
                                kb,
                                strategy,
                                diagnostics.actualStrategy(),
                                status,
                                stage.phase(),
                                stage.elapsedMillis(),
                                stage.candidateCount(),
                                output.failure());
            results.add(new RetrievalResult(chunks, diagnostics));
        }
        return List.copyOf(results);
    }

    private static List<RankedChunk> finish(List<RankedChunk> input, int limit) {
        List<RankedChunk> result = new ArrayList<>();
        for (RankedChunk c : input.subList(0, Math.min(limit, input.size())))
            result.add(new RankedChunk(
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
                    c.rerankScore(),
                    result.size() + 1));
        return List.copyOf(result);
    }
}
