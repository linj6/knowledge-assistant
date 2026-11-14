package com.lnjecit.knowledge.assistant.evaluation.metrics;

import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.ANSWERED;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.ANSWER_GOLD_VERSION;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.ANSWER_PROTOCOL;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.CITATION_INVALID;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.COMPLETED;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.CONFIRMED;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.DEFINED;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.FAIL;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.FAILED;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.FROZEN;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.INSUFFICIENT_EVIDENCE;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.NOT_RUN;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.PASS;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.PENDING;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.UNDEFINED;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** D01 冻结 Gold 上的纯离线人工标签计算，不推断语义。 */
public final class AnswerMetrics {
    private AnswerMetrics() {}

    public static final String GOLD_V1_FINGERPRINT = "57c202a788d20e86a2ed166776c77dab6e66bed8b4ae08a1a925806458a5dbca";

    public enum State {
        PASS,
        FAIL,
        NA,
        PENDING,
        NOT_READY
    }

    public record Result(
            String caseId,
            String executionStatus,
            boolean reviewComplete,
            State appropriateness,
            State correctness,
            State completeness,
            State validity,
            State support,
            State wholeCase,
            int requiredCount,
            int coveredCount) {}

    public record Metric(Map<State, Integer> counts, int numerator, int denominator, Double value, String status) {}

    public record Summary(
            int totalCases,
            int executed,
            int failed,
            int reviewed,
            int pending,
            int notReady,
            Map<String, Metric> metrics,
            List<Result> cases) {}

    private static State judgment(JsonNode node) {
        String value = node.asString("");
        return value.equals(PASS) ? State.PASS : value.equals(FAIL) ? State.FAIL : State.PENDING;
    }

    private static State all(List<State> values) {
        if (values.contains(State.FAIL)) return State.FAIL;
        return values.contains(State.PENDING) ? State.PENDING : State.PASS;
    }

    private static Result unavailable(String id, String execution, State state) {
        return new Result(id, execution, false, state, state, state, state, state, state, 0, 0);
    }

    private static boolean confirmed(JsonNode gold) {
        if (!gold.path("reviewStatus").asString().equals(CONFIRMED)) return false;
        for (String key : List.of("requiredFacts", "optionalFacts"))
            for (JsonNode fact : gold.path(key))
                if (!fact.path("reviewStatus").asString().equals(CONFIRMED)) return false;
        return true;
    }

    private static boolean ready(JsonNode dataset) {
        try {
            ObjectNode payload = (ObjectNode) dataset.deepCopy();
            String expected = payload.path("freezeFingerprint").asString();
            payload.remove("freezeFingerprint");
            if (!expected.equals(GOLD_V1_FINGERPRINT)
                    || dataset.path("datasetVersion").asInt() != ANSWER_GOLD_VERSION
                    || !dataset.path("freezeStatus").asString().equals(FROZEN)
                    || !dataset.path("protocol").asString().equals(ANSWER_PROTOCOL)
                    || !FrozenRankingEvaluation.fingerprint(payload).equals(expected)) return false;
            for (JsonNode gold : dataset.path("cases")) if (!confirmed(gold)) return false;
            return true;
        } catch (Exception invalid) {
            return false;
        }
    }

    public static Result calculate(JsonNode dataset, JsonNode gold, JsonNode review) {
        String id = gold.path("caseId").asString();
        String execution =
                review == null ? NOT_RUN : review.path("executionStatus").asString(NOT_RUN);
        // 缺少评审不等于身份错误；已提供但不匹配的评审不得进入分母。
        if (!ready(dataset) || !confirmed(gold) || !containsCase(dataset, gold))
            return unavailable(id, execution, State.NOT_READY);
        if (review == null || review.isMissingNode() || review.isNull()) return unavailable(id, NOT_RUN, State.PENDING);
        if (!id.equals(review.path("caseId").asString())
                || !dataset.path("datasetVersion").equals(review.path("datasetVersion"))
                || !dataset.path("freezeFingerprint").equals(review.path("freezeFingerprint"))
                || (!review.path("reviewStatus").asString().equals(CONFIRMED)
                        && !review.path("reviewStatus").asString().equals(PENDING)))
            return unavailable(id, execution, State.NOT_READY);
        if (execution.equals(NOT_RUN)) return unavailable(id, execution, State.PENDING);
        String rag = review.path("ragStatus").asString();
        if (execution.equals(FAILED)) {
            if (!rag.equals(FAILED)
                    || !review.path("answer").isNull()
                    || !review.path("citations").isArray()
                    || !review.path("citations").isEmpty()
                    || review.path("error").asString().isBlank()) return unavailable(id, execution, State.NOT_READY);
            State validity = review.path("error").asString().equals(CITATION_INVALID) ? State.FAIL : State.NA;
            return new Result(
                    id,
                    execution,
                    false,
                    State.NA,
                    State.NA,
                    State.NA,
                    validity,
                    State.NA,
                    State.FAIL,
                    gold.path("requiredFacts").size(),
                    0);
        }
        if (!execution.equals(COMPLETED) || !(rag.equals(ANSWERED) || rag.equals(INSUFFICIENT_EVIDENCE)))
            return unavailable(id, execution, State.NOT_READY);
        boolean reviewed = review.path("reviewStatus").asString().equals(CONFIRMED)
                && !review.path("reviewer").asString().isBlank()
                && !review.path("basis").asString().isBlank();
        if (!reviewed) return unavailable(id, execution, State.PENDING);
        boolean knowledge = gold.path("knowledgeAnswerable").asBoolean();
        if (!review.path("knowledgeAnswerable").isBoolean()
                || review.path("knowledgeAnswerable").asBoolean() != knowledge)
            return unavailable(id, execution, State.NOT_READY);
        return calculateReviewed(id, execution, knowledge, toReview(gold, review));
    }

    private record ClaimJudgment(String id, String text, State correctness, State support, Set<String> citations) {}

    private record AnswerReview(
            boolean answered,
            Boolean contextAnswerable,
            State appropriateness,
            List<State> coverage,
            Set<String> citations,
            boolean claimsComplete,
            List<ClaimJudgment> claims,
            State citationValidity,
            String answer) {}

    // 缺失的人工判断继续映射为 PENDING；不根据答案原文推断语义。
    // claimsComplete 缺失按既有协议视为清单未完成，contextAnswerable 缺失使用 null 保留未知状态。
    private static AnswerReview toReview(JsonNode gold, JsonNode review) {
        List<State> coverage = new ArrayList<>();
        for (JsonNode fact : gold.path("requiredFacts"))
            coverage.add(judgment(
                    review.path("factCoverage").path(fact.path("factId").asString())));
        List<ClaimJudgment> claims = new ArrayList<>();
        for (JsonNode claim : review.path("claims"))
            claims.add(new ClaimJudgment(
                    claim.path("claimId").asString(),
                    claim.path("text").asString(),
                    judgment(claim.path("correctness")),
                    judgment(claim.path("support")),
                    strings(claim.path("citationIds"))));
        JsonNode context = review.path("contextAnswerable");
        return new AnswerReview(
                review.path("ragStatus").asString().equals(ANSWERED),
                context.isBoolean() ? context.asBoolean() : null,
                judgment(review.path("appropriateness")),
                coverage,
                strings(review.path("citations")),
                review.path("claimsComplete").asBoolean(false),
                claims,
                judgment(review.path("citationValidity")),
                review.path("answer").asString());
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> values = new HashSet<>();
        for (JsonNode value : array) values.add(value.asString());
        return values;
    }

    private static State appropriateness(AnswerReview review, boolean knowledge) {
        State state = review.appropriateness();
        Boolean context = review.contextAnswerable();
        if (context == null && state != State.FAIL) return State.PENDING;
        boolean answerable = Boolean.TRUE.equals(context);
        if ((!review.answered() && answerable) || (review.answered() && (!answerable || !knowledge))) return State.FAIL;
        return state;
    }

    private static State completeness(AnswerReview review, boolean knowledge) {
        return !knowledge ? State.NA : !review.answered() ? State.FAIL : all(review.coverage());
    }

    private static State validity(AnswerReview review) {
        State state = review.answered() ? review.citationValidity() : State.NA;
        if ((review.answered()
                        && (review.citations().isEmpty() || review.answer().isBlank()))
                || (!review.answered() && !review.citations().isEmpty())) return State.FAIL;
        return state;
    }

    private record ClaimScores(State correctness, State support) {}

    private static ClaimScores claims(AnswerReview review) {
        List<State> factual = new ArrayList<>();
        List<State> supported = new ArrayList<>();
        Set<String> actual = review.citations();
        Set<String> used = new HashSet<>();
        Set<String> claimIds = new HashSet<>();
        boolean inventoryComplete = review.claimsComplete();
        for (ClaimJudgment claim : review.claims()) {
            if (claim.id().isBlank()
                    || !claimIds.add(claim.id())
                    || claim.text().isBlank()) inventoryComplete = false;
            factual.add(claim.correctness());
            State support = claim.support();
            Set<String> refs = claim.citations();
            if (!actual.containsAll(refs) || (support == State.PASS && refs.isEmpty())) support = State.FAIL;
            if (support == State.PASS) used.addAll(refs);
            supported.add(support);
        }
        if (!inventoryComplete) {
            factual.add(State.PENDING);
            supported.add(State.PENDING);
        }
        State correctness = factual.isEmpty() ? State.NA : all(factual);
        State support = !review.answered() ? State.NA : supported.isEmpty() ? State.PENDING : all(supported);
        if (review.answered() && inventoryComplete && support != State.PENDING && !used.containsAll(actual))
            support = State.FAIL;
        return new ClaimScores(correctness, support);
    }

    // 五维共享同一汇总优先级：FAIL 优先于 PENDING，N/A 不增加失败。
    private static Result calculateReviewed(String id, String execution, boolean knowledge, AnswerReview review) {
        State appropriate = appropriateness(review, knowledge);
        State complete = completeness(review, knowledge);
        ClaimScores claims = claims(review);
        State validity = validity(review);
        List<State> dimensions = List.of(appropriate, claims.correctness(), complete, validity, claims.support());
        int covered = (int)
                review.coverage().stream().filter(state -> state == State.PASS).count();
        return new Result(
                id,
                execution,
                !dimensions.contains(State.PENDING),
                appropriate,
                claims.correctness(),
                complete,
                validity,
                claims.support(),
                all(dimensions),
                review.coverage().size(),
                covered);
    }

    private static boolean containsCase(JsonNode dataset, JsonNode gold) {
        for (JsonNode item : dataset.path("cases")) if (item.equals(gold)) return true;
        return false;
    }

    public static Summary summarize(JsonNode dataset, List<JsonNode> reviews) {
        Set<String> ids = new HashSet<>();
        for (JsonNode review : reviews) {
            String id = review.path("caseId").asString();
            boolean known = false;
            for (JsonNode gold : dataset.path("cases"))
                if (gold.path("caseId").asString().equals(id)) known = true;
            if (!known || !ids.add(id)) throw new IllegalArgumentException("未知或重复 caseId");
        }
        List<Result> results = new ArrayList<>();
        int executed = 0, failed = 0, reviewed = 0, pending = 0, notReady = 0;
        for (JsonNode gold : dataset.path("cases")) {
            JsonNode review = null;
            for (JsonNode candidate : reviews)
                if (candidate.path("caseId").equals(gold.path("caseId"))) review = candidate;
            Result result = calculate(dataset, gold, review);
            results.add(result);
            if (result.executionStatus().equals(COMPLETED)
                    || result.executionStatus().equals(FAILED)) executed++;
            if (result.executionStatus().equals(FAILED)) failed++;
            if (result.wholeCase() == State.NOT_READY) notReady++;
            else if (result.reviewComplete()) reviewed++;
            else pending++;
        }
        Map<String, Metric> metrics = new java.util.LinkedHashMap<>();
        for (String dimension :
                List.of("appropriateness", "correctness", "completeness", "validity", "support", "wholeCase")) {
            Map<State, Integer> counts = new EnumMap<>(State.class);
            for (State state : State.values()) counts.put(state, 0);
            for (Result r : results) {
                State state =
                        switch (dimension) {
                            case "appropriateness" -> r.appropriateness();
                            case "correctness" -> r.correctness();
                            case "completeness" -> r.completeness();
                            case "validity" -> r.validity();
                            case "support" -> r.support();
                            default -> r.wholeCase();
                        };
                counts.put(state, counts.get(state) + 1);
            }
            int numerator = counts.get(State.PASS), denominator = numerator + counts.get(State.FAIL);
            metrics.put(
                    dimension,
                    new Metric(
                            counts,
                            numerator,
                            denominator,
                            denominator == 0 ? null : (double) numerator / denominator,
                            denominator == 0 ? UNDEFINED : DEFINED));
        }
        return new Summary(results.size(), executed, failed, reviewed, pending, notReady, metrics, results);
    }
}
