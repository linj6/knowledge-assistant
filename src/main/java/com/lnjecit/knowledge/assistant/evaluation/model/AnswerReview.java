package com.lnjecit.knowledge.assistant.evaluation.model;

import com.lnjecit.knowledge.assistant.evaluation.metrics.AnswerMetrics;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 人工输入不包含执行事实或 Gold 正文。 */
public final class AnswerReview {
    private AnswerReview() {}

    public enum Status {
        PENDING,
        CONFIRMED
    }

    public enum Judgment {
        PASS,
        FAIL,
        PENDING
    }

    public enum Answerable {
        TRUE,
        FALSE,
        PENDING
    }

    public record Input(
            String runId,
            String caseId,
            int datasetVersion,
            String freezeFingerprint,
            String snapshotHash,
            String reviewer,
            String basis,
            Status reviewStatus,
            Answerable contextAnswerable,
            Judgment appropriateness,
            Map<String, Judgment> factCoverage,
            List<AnswerMetrics.Claim> claims,
            boolean claimsComplete) {}

    public record Saved(Input judgments, Instant reviewedAt, boolean knowledgeAnswerable, String citationValidity) {}

    public record Material(
            AnswerEvaluationRun.Response run,
            AnswerEvaluationDataset.Case gold,
            Saved review,
            Status reviewStatus,
            String citationValidity) {}
}
