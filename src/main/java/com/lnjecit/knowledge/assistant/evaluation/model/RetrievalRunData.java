package com.lnjecit.knowledge.assistant.evaluation.model;

import java.util.List;
import java.util.Map;

/** C01/C02 归档固定结构；路径和状态计数使用动态键映射，null 与缺失分别保留。 */
public final class RetrievalRunData {
    private RetrievalRunData() {}

    public record RelevanceJudgment(
            Long documentId,
            Long indexVersionId,
            Long chunkId,
            String documentPath,
            String sourceSha256,
            String contentSha256,
            Long proposedRelevance,
            String rationale,
            String reviewStatus,
            String proposalSource,
            String reviewer,
            String confirmedAt,
            String confirmationBasis,
            String relevance) {}

    public record FrozenPool(
            String poolId,
            Long poolVersion,
            String questionId,
            String chunking,
            String snapshotHash,
            String status,
            List<RelevanceJudgment> judgments,
            String fingerprint) {}

    public record FrozenPools(
            Long schemaVersion,
            String canonicalization,
            List<FrozenPool> pools,
            String fixtureFingerprint,
            String judgmentsFingerprint,
            String materialsFingerprint,
            String fingerprint) {}

    public record LiveConfiguration(
            Long knowledgeBaseId,
            String chunking,
            List<String> strategies,
            com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters parameters,
            Boolean authorizeEmbedding,
            String output) {}

    public record RetrievalRunManifest(
            Long schemaVersion,
            String runnerVersion,
            String dataset,
            String mode,
            String status,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    Map<String, String> inputSha256,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    FrozenPools frozenPool,
            Map<String, String> artifactSha256,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    LiveConfiguration configuration,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    FrozenPools frozenInputs,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    String questionSource,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    String questionSourceSha256,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    Map<String, String> sourceContentHashes,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    Map<String, Long> retrievalStatuses,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    List<String> snapshots,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    Map<String, Long> metricStatuses) {}

    public record OfflineMetricsReportRunsItemAt3(
            String status, Double precision, Double dcg, Double idcg, Double ndcg) {}

    public record OfflineMetricsReportRunsItemEvidence3(
            String executionStatus,
            String qualityStatus,
            Long depth,
            Long covered,
            Long evidenceCount,
            Long firstRank,
            Long completeRank) {}

    public record OfflineMetricsReportRunsItem(
            String questionId,
            String chunking,
            String strategy,
            OfflineMetricsReportRunsItemAt3 at3,
            OfflineMetricsReportRunsItemAt3 at5,
            OfflineMetricsReportRunsItemEvidence3 evidence3,
            OfflineMetricsReportRunsItemEvidence3 evidence5,
            List<Long> grades) {}

    public record OfflineMetricsReportGroupsItemStatusesValue(Long SCORED) {}

    public record OfflineMetricsReportGroupsItemPrecision3(Double numerator, Long denominator, Double value) {}

    public record OfflineMetricsReportGroupsItemEvidence(
            Long totalCases,
            Long eligibleCases,
            Long scoredCases,
            Long successCount,
            Long emptyResultCount,
            Long failedCount,
            Long missingCount,
            Long pendingReviewCount,
            OfflineMetricsReportGroupsItemPrecision3 recall3,
            OfflineMetricsReportGroupsItemPrecision3 recall5,
            OfflineMetricsReportGroupsItemPrecision3 cec3,
            OfflineMetricsReportGroupsItemPrecision3 cec5,
            OfflineMetricsReportGroupsItemPrecision3 hit3,
            OfflineMetricsReportGroupsItemPrecision3 hit5,
            OfflineMetricsReportGroupsItemPrecision3 mrr5) {}

    public record OfflineMetricsReportGroupsItem(
            String chunking,
            String strategy,
            Map<String, OfflineMetricsReportGroupsItemStatusesValue> statuses,
            OfflineMetricsReportGroupsItemPrecision3 precision3,
            OfflineMetricsReportGroupsItemPrecision3 ndcg3,
            OfflineMetricsReportGroupsItemPrecision3 precision5,
            OfflineMetricsReportGroupsItemPrecision3 ndcg5,
            OfflineMetricsReportGroupsItemEvidence evidence) {}

    public record OfflineMetricsReport(
            FrozenPools frozenPool,
            List<OfflineMetricsReportRunsItem> runs,
            List<OfflineMetricsReportGroupsItem> groups) {}

    public record OfflineRetrievalRunResult(
            String questionId,
            String chunking,
            String strategy,
            String status,
            String snapshotHash,
            com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters parameters,
            List<com.lnjecit.knowledge.assistant.retrieval.model.RankedChunk> chunks,
            Map<String, String> semantic) {}

    public record LiveMetricsReportRunsItem(
            String questionId,
            String strategy,
            String evidenceStatus,
            String rankingStatus,
            Long evidenceDenominator,
            Long rankingDenominator) {}

    public record LiveMetricsReportSummariesValueRecall3(Double numerator, Long denominator, Double value) {}

    public record LiveMetricsReportSummariesValue(
            LiveMetricsReportSummariesValueRecall3 recall3,
            LiveMetricsReportSummariesValueRecall3 cec3,
            LiveMetricsReportSummariesValueRecall3 hit3,
            LiveMetricsReportSummariesValueRecall3 mrr3,
            LiveMetricsReportSummariesValueRecall3 precision3,
            LiveMetricsReportSummariesValueRecall3 ndcg3,
            LiveMetricsReportSummariesValueRecall3 recall5,
            LiveMetricsReportSummariesValueRecall3 cec5,
            LiveMetricsReportSummariesValueRecall3 hit5,
            LiveMetricsReportSummariesValueRecall3 mrr5,
            LiveMetricsReportSummariesValueRecall3 precision5,
            LiveMetricsReportSummariesValueRecall3 ndcg5) {}

    public record LiveMetricsReport(
            List<LiveMetricsReportRunsItem> runs, Map<String, LiveMetricsReportSummariesValue> summaries) {}

    public record LiveRetrievalRunResultDiagnosticsStagesItemCandidatesItem(Long chunkId, Long rank, Double score) {}

    public record LiveRetrievalRunResultDiagnosticsStagesItem(
            String phase,
            Long elapsedMillis,
            Long candidateCount,
            List<LiveRetrievalRunResultDiagnosticsStagesItemCandidatesItem> candidates) {}

    public record LiveRetrievalRunResultDiagnostics(
            String requestedStrategy,
            String actualStrategy,
            Boolean degraded,
            String status,
            String snapshotHash,
            com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters parameters,
            List<LiveRetrievalRunResultDiagnosticsStagesItem> stages,
            String error) {}

    public record LiveRetrievalRunResult(
            String questionId,
            String query,
            Long knowledgeBaseId,
            String chunking,
            String strategy,
            String requestedStrategy,
            com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters parameters,
            LiveRetrievalRunResultDiagnostics diagnostics,
            String status,
            String actualStrategy,
            String error,
            String snapshotHash,
            List<com.lnjecit.knowledge.assistant.retrieval.model.RankedChunk> chunks) {}

    public record RetrievalFixtureSource(String path, String sha256, String baseline) {}

    public record SourceSpan(Long start, Long end) {}

    public record GoldEvidence(String evidenceId, SourceSpan sourceSpan) {}

    public record GoldQuestionIdentity(
            String questionId,
            String documentPath,
            String sourceSha256,
            String relation,
            String reviewStatus,
            List<GoldEvidence> evidence,
            String querySha256) {}

    public record RetrievalFixtureGold(
            Long schemaVersion, List<String> reviewStatuses, List<GoldQuestionIdentity> questions) {}

    public record RetrievalFixturePoolJudgmentsItem(
            String questionId, String chunking, Long chunkId, String relevance, String reviewer) {}

    public record RetrievalFixturePool(
            String id, String scope, String status, List<RetrievalFixturePoolJudgmentsItem> judgments) {}

    public record RetrievalFixture(
            String protocol,
            RetrievalFixtureSource source,
            RetrievalFixtureGold gold,
            RetrievalFixturePool pool,
            List<OfflineRetrievalRunResult> runs) {}

    public record GoldTextEvidence(SourceSpan span, String text) {}

    public record GoldQuestion(
            String questionId,
            String documentPath,
            String sha256,
            String question,
            List<String> expectedAnswerPoints,
            List<GoldTextEvidence> goldEvidence,
            String annotation) {}
}
