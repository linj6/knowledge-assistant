package com.lnjecit.knowledge.assistant.evaluation.model;

import java.util.List;

/** D01 完整冻结模型；可选字段保持 null，不补造确认信息。 */
public record AnswerEvaluationDataset(
        String protocol,
        int schemaVersion,
        String freezeStatus,
        String baseline,
        String sourceScope,
        String sourceEvidenceIndexConvention,
        List<Case> cases,
        int datasetVersion,
        Confirmation confirmation,
        Provenance provenance,
        FreezeManifest freezeManifest,
        String fingerprintAlgorithm,
        String freezeFingerprint) {
    public enum ReviewStatus {
        PENDING,
        CONFIRMED,
        REJECTED
    }

    public record Span(int start, int end) {}

    public record Source(String documentPath, String sourceSha256, String basis, Span sourceSpan) {}

    public record SourceReference(String documentPath, String sourceSha256, List<Integer> evidenceIndexes) {}

    public record Fact(
            String factId,
            String description,
            String qualifications,
            String role,
            List<SourceReference> sourceRefs,
            ReviewStatus reviewStatus) {}

    public record Case(
            String caseId,
            String question,
            boolean knowledgeAnswerable,
            ReviewStatus reviewStatus,
            List<Source> sources,
            List<Fact> requiredFacts,
            List<Fact> optionalFacts,
            String unanswerableReason,
            String allowedAnswerBoundary,
            List<String> forbiddenFabrications) {
        public boolean confirmed() {
            return reviewStatus == ReviewStatus.CONFIRMED && confirmed(requiredFacts) && confirmed(optionalFacts);
        }

        private static boolean confirmed(List<Fact> facts) {
            return facts == null || facts.stream().allMatch(f -> f.reviewStatus() == ReviewStatus.CONFIRMED);
        }
    }

    public record Confirmation(String reviewer, String reviewBasis, String confirmedAt) {}

    public record Provenance(String candidateAuthor, String historyDocument, String freezeBaseline) {}

    public record FreezeManifest(
            int caseCount,
            int requiredFactCount,
            int optionalFactCount,
            String protocolVersion,
            List<Source> sourceHashes) {}
}
