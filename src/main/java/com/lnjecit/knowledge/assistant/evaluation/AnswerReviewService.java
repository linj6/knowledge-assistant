package com.lnjecit.knowledge.assistant.evaluation;

import com.lnjecit.knowledge.assistant.evaluation.metrics.AnswerMetrics;
import com.lnjecit.knowledge.assistant.rag.model.RagAnswer;
import com.lnjecit.knowledge.assistant.rag.model.RagCitation;
import com.lnjecit.knowledge.assistant.rag.validation.CitationValidator;
import com.lnjecit.knowledge.assistant.rag.validation.StructuredOutputParser;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(name = "app.evaluation.enabled", havingValue = "true")
public class AnswerReviewService {
    private final EvaluationRunStore runs;
    private final AnswerReviewStore reviews;
    private final EvaluationDatasetLoader loader = new EvaluationDatasetLoader();

    public AnswerReviewService(EvaluationRunStore runs, AnswerReviewStore reviews) {
        this.runs = runs;
        this.reviews = reviews;
    }

    private ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    private AnswerEvaluationRun.Response run(String id) {
        EvaluationRunResponse response = runs.read(id);
        if (!(response instanceof AnswerEvaluationRun.Response answer)) throw invalid("ANSWER_REVIEW_NOT_APPLICABLE");
        return answer;
    }

    public AnswerReview.Material read(String id) throws Exception {
        AnswerEvaluationRun.Response run = run(id);
        AnswerEvaluationDataset dataset = loader.full();
        if (!dataset.freezeFingerprint().equals(run.manifest().datasetSha256()))
            throw invalid("GOLD_IDENTITY_MISMATCH");
        AnswerEvaluationDataset.Case gold = dataset.cases().stream()
                .filter(c -> c.caseId().equals(run.results().goldCase().caseId()))
                .findFirst()
                .orElseThrow(() -> invalid("UNKNOWN_CASE"));
        AnswerReview.Saved saved = reviews.read(id);
        if (saved != null) validateIdentity(run, gold, dataset, saved.judgments());
        return new AnswerReview.Material(
                run,
                gold,
                saved,
                saved == null ? AnswerReview.Status.PENDING : saved.judgments().reviewStatus(),
                validity(run));
    }

    private void validateIdentity(
            AnswerEvaluationRun.Response run,
            AnswerEvaluationDataset.Case gold,
            AnswerEvaluationDataset dataset,
            AnswerReview.Input input) {
        String snapshot = run.manifest().snapshot() == null
                ? null
                : run.manifest().snapshot().snapshotHash();
        if (input == null
                || !run.runId().equals(input.runId())
                || !gold.caseId().equals(input.caseId())
                || input.datasetVersion() != dataset.datasetVersion()
                || !dataset.freezeFingerprint().equals(input.freezeFingerprint())
                || !Objects.equals(snapshot, input.snapshotHash())) throw invalid("REVIEW_IDENTITY_MISMATCH");
    }

    public AnswerReview.Saved save(String id, AnswerReview.Input input) throws Exception {
        AnswerReview.Material material = read(id);
        // V1 确认后只读，普通 PUT 不能覆盖或退回草稿。
        if (material.reviewStatus() == AnswerReview.Status.CONFIRMED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REVIEW_ALREADY_CONFIRMED");
        validateIdentity(material.run(), material.gold(), loader.full(), input);
        boolean failed = material.run().manifest().status() == RunStatus.FAILED;
        if (input.reviewStatus() == null
                || (!failed
                        && (input.contextAnswerable() == null
                                || input.appropriateness() == null
                                || input.factCoverage() == null
                                || input.claims() == null))) throw invalid("INVALID_REVIEW");
        Set<String> facts = new HashSet<>();
        for (AnswerEvaluationDataset.Fact fact : material.gold().requiredFacts()) facts.add(fact.factId());
        if (input.factCoverage() != null
                && (!facts.containsAll(input.factCoverage().keySet())
                        || input.factCoverage().values().stream().anyMatch(Objects::isNull)))
            throw invalid("INVALID_FACT_ID_OR_JUDGMENT");
        List<String> citations = citations(material.run());
        Set<String> claims = new HashSet<>();
        for (AnswerMetrics.Claim claim : input.claims() == null ? List.<AnswerMetrics.Claim>of() : input.claims()) {
            if (claim == null
                    || claim.claimId() == null
                    || claim.claimId().isBlank()
                    || !claims.add(claim.claimId())
                    || claim.text() == null
                    || claim.text().isBlank()
                    || claim.citationIds() == null
                    || !citations.containsAll(claim.citationIds())
                    || claim.correctness() == null
                    || !List.of("PASS", "FAIL", "PENDING").contains(claim.correctness())
                    || claim.support() == null
                    || !List.of("PASS", "FAIL", "PENDING").contains(claim.support()))
                throw invalid("INVALID_CLAIM_OR_CITATION");
        }
        AnswerReview.Saved saved = new AnswerReview.Saved(
                input, Instant.now(), material.gold().knowledgeAnswerable(), validity(material.run()));
        // 执行失败没有可评回答，只确认评审人和依据；完成的回答仍须完成人工判断。
        if (input.reviewStatus() == AnswerReview.Status.CONFIRMED) {
            AnswerMetrics.Result result = score(material, saved);
            if (input.reviewer() == null
                    || input.reviewer().isBlank()
                    || input.basis() == null
                    || input.basis().isBlank()
                    || (!failed
                            && (input.contextAnswerable() == null
                                    || input.contextAnswerable() == AnswerReview.Answerable.PENDING
                                    || input.appropriateness() == AnswerReview.Judgment.PENDING
                                    || !input.claimsComplete()
                                    || !input.factCoverage().keySet().equals(facts)
                                    || input.factCoverage().containsValue(AnswerReview.Judgment.PENDING)
                                    || input.claims().stream()
                                            .anyMatch(c -> "PENDING".equals(c.correctness())
                                                    || "PENDING".equals(c.support()))))
                    || result.wholeCase() == AnswerMetrics.State.NOT_READY
                    || result.wholeCase() == AnswerMetrics.State.PENDING) throw invalid("REVIEW_INCOMPLETE");
        }
        reviews.save(id, saved);
        return saved;
    }

    private List<String> citations(AnswerEvaluationRun.Response run) {
        return run.results().execution() == null || run.results().execution().answer() == null
                ? List.of()
                : run.results().execution().answer().citations().stream()
                        .map(RagCitation::evidenceId)
                        .toList();
    }

    private String validity(AnswerEvaluationRun.Response run) {
        if (run.results().execution() == null || run.results().execution().answer() == null) return "PENDING";
        RagAnswer answer = run.results().execution().answer();
        if (answer.error() != null) return answer.error().name().equals("CITATION_INVALID") ? "FAIL" : "PENDING";
        if (!answer.status().name().equals("ANSWERED")) return "PENDING";
        try {
            List<RagCitation> checked = new CitationValidator()
                    .validate(
                            new StructuredOutputParser.ParsedOutput(true, answer.answer(), citations(run)),
                            run.manifest().request().knowledgeBaseId(),
                            run.results().execution().context(),
                            run.results().execution().retrieval());
            return checked.equals(answer.citations()) ? "PASS" : "FAIL";
        } catch (RuntimeException invalid) {
            return "FAIL";
        }
    }

    public AnswerMetrics.Result score(String id) throws Exception {
        AnswerReview.Material material = read(id);
        return score(material, material.review());
    }

    private AnswerMetrics.Result score(AnswerReview.Material material, AnswerReview.Saved saved) throws Exception {
        AnswerEvaluationDataset dataset = loader.full();
        AnswerMetrics.DatasetInput datasetInput = new AnswerMetrics.DatasetInput(dataset, dataset.freezeFingerprint());
        if (saved == null) {
            AnswerEvaluationRun.Response actual = material.run();
            AnswerReview.Input pending = new AnswerReview.Input(
                    actual.runId(),
                    material.gold().caseId(),
                    dataset.datasetVersion(),
                    dataset.freezeFingerprint(),
                    actual.manifest().snapshot() == null
                            ? null
                            : actual.manifest().snapshot().snapshotHash(),
                    "",
                    "",
                    AnswerReview.Status.PENDING,
                    AnswerReview.Answerable.PENDING,
                    AnswerReview.Judgment.PENDING,
                    Map.of(),
                    List.of(),
                    false);
            saved = new AnswerReview.Saved(pending, null, material.gold().knowledgeAnswerable(), validity(actual));
        }
        AnswerReview.Input input = saved.judgments();
        AnswerEvaluationRun.Response run = material.run();
        RagAnswer answer = run.results().execution() == null
                ? null
                : run.results().execution().answer();
        boolean failed = run.manifest().status() == RunStatus.FAILED;
        Map<String, String> coverage = new LinkedHashMap<>();
        if (input.factCoverage() != null) input.factCoverage().forEach((key, value) -> coverage.put(key, value.name()));
        // 执行状态、答案、引用与错误只取持久化 Run，不接受人工输入覆盖。
        AnswerMetrics.Review review = new AnswerMetrics.Review(
                input.caseId(),
                input.datasetVersion(),
                input.freezeFingerprint(),
                run.manifest().status().name(),
                failed ? "FAILED" : answer == null ? "" : answer.status().name(),
                answer == null ? null : answer.answer(),
                answer == null || answer.answer() == null,
                citations(run),
                answer != null && answer.error() != null
                        ? answer.error().name()
                        : Objects.toString(run.manifest().errorCode(), ""),
                input.reviewStatus().name(),
                Objects.toString(input.reviewer(), ""),
                Objects.toString(input.basis(), ""),
                material.gold().knowledgeAnswerable(),
                input.contextAnswerable() == null || input.contextAnswerable() == AnswerReview.Answerable.PENDING
                        ? null
                        : input.contextAnswerable() == AnswerReview.Answerable.TRUE,
                input.appropriateness() == null
                        ? "PENDING"
                        : input.appropriateness().name(),
                validity(run),
                input.claimsComplete(),
                coverage,
                input.claims() == null ? List.of() : input.claims());
        return AnswerMetrics.calculate(datasetInput, material.gold(), review);
    }
}
