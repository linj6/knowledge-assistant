package com.lnjecit.knowledge.assistant.evaluation.metrics;

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
        return value.equals("PASS") ? State.PASS : value.equals("FAIL") ? State.FAIL : State.PENDING;
    }

    private static State all(List<State> values) {
        if (values.contains(State.FAIL)) return State.FAIL;
        return values.contains(State.PENDING) ? State.PENDING : State.PASS;
    }

    private static Result unavailable(String id, String execution, State state) {
        return new Result(id, execution, false, state, state, state, state, state, state, 0, 0);
    }

    private static boolean confirmed(JsonNode gold) {
        if (!gold.path("reviewStatus").asString().equals("CONFIRMED")) return false;
        for (String key : List.of("requiredFacts", "optionalFacts"))
            for (JsonNode fact : gold.path(key))
                if (!fact.path("reviewStatus").asString().equals("CONFIRMED")) return false;
        return true;
    }

    private static boolean ready(JsonNode dataset) {
        try {
            ObjectNode payload = (ObjectNode) dataset.deepCopy();
            String expected = payload.path("freezeFingerprint").asString();
            payload.remove("freezeFingerprint");
            if (!expected.equals(GOLD_V1_FINGERPRINT)
                    || dataset.path("datasetVersion").asInt() != 1
                    || !dataset.path("freezeStatus").asString().equals("FROZEN")
                    || !dataset.path("protocol").asString().equals("answer-evaluation-v1")
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
                review == null ? "NOT_RUN" : review.path("executionStatus").asString("NOT_RUN");
        // 缺少评审不等于身份错误；已提供但不匹配的评审不得进入分母。
        if (!ready(dataset) || !confirmed(gold) || !containsCase(dataset, gold))
            return unavailable(id, execution, State.NOT_READY);
        if (review == null || review.isMissingNode() || review.isNull())
            return unavailable(id, "NOT_RUN", State.PENDING);
        if (!id.equals(review.path("caseId").asString())
                || !dataset.path("datasetVersion").equals(review.path("datasetVersion"))
                || !dataset.path("freezeFingerprint").equals(review.path("freezeFingerprint"))
                || (!review.path("reviewStatus").asString().equals("CONFIRMED")
                        && !review.path("reviewStatus").asString().equals("PENDING")))
            return unavailable(id, execution, State.NOT_READY);
        if (execution.equals("NOT_RUN")) return unavailable(id, execution, State.PENDING);
        String rag = review.path("ragStatus").asString();
        if (execution.equals("FAILED")) {
            if (!rag.equals("FAILED")
                    || !review.path("answer").isNull()
                    || !review.path("citations").isArray()
                    || !review.path("citations").isEmpty()
                    || review.path("error").asString().isBlank()) return unavailable(id, execution, State.NOT_READY);
            State validity = review.path("error").asString().equals("CITATION_INVALID") ? State.FAIL : State.NA;
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
        if (!execution.equals("COMPLETED") || !(rag.equals("ANSWERED") || rag.equals("INSUFFICIENT_EVIDENCE")))
            return unavailable(id, execution, State.NOT_READY);
        boolean reviewed = review.path("reviewStatus").asString().equals("CONFIRMED")
                && !review.path("reviewer").asString().isBlank()
                && !review.path("basis").asString().isBlank();
        if (!reviewed) return unavailable(id, execution, State.PENDING);
        boolean knowledge = gold.path("knowledgeAnswerable").asBoolean();
        if (!review.path("knowledgeAnswerable").isBoolean()
                || review.path("knowledgeAnswerable").asBoolean() != knowledge)
            return unavailable(id, execution, State.NOT_READY);
        boolean answered = rag.equals("ANSWERED");
        State appropriate = judgment(review.path("appropriateness"));
        JsonNode context = review.path("contextAnswerable");
        if (!context.isBoolean() && appropriate != State.FAIL) appropriate = State.PENDING;
        else if ((!answered && context.asBoolean()) || (answered && (!context.asBoolean() || !knowledge)))
            appropriate = State.FAIL;
        List<State> coverage = new ArrayList<>();
        int covered = 0;
        for (JsonNode fact : gold.path("requiredFacts")) {
            State state = judgment(
                    review.path("factCoverage").path(fact.path("factId").asString()));
            coverage.add(state);
            if (state == State.PASS) covered++;
        }
        State complete = !knowledge ? State.NA : !answered ? State.FAIL : all(coverage);
        List<State> factual = new ArrayList<>();
        List<State> supported = new ArrayList<>();
        Set<String> actual = new HashSet<>();
        for (JsonNode citation : review.path("citations")) actual.add(citation.asString());
        Set<String> used = new HashSet<>();
        Set<String> claimIds = new HashSet<>();
        boolean inventoryComplete = review.path("claimsComplete").asBoolean(false);
        for (JsonNode claim : review.path("claims")) {
            if (claim.path("claimId").asString().isBlank()
                    || !claimIds.add(claim.path("claimId").asString())
                    || claim.path("text").asString().isBlank()) inventoryComplete = false;
            factual.add(judgment(claim.path("correctness")));
            State support = judgment(claim.path("support"));
            Set<String> refs = new HashSet<>();
            for (JsonNode ref : claim.path("citationIds")) refs.add(ref.asString());
            if (!actual.containsAll(refs) || (support == State.PASS && refs.isEmpty())) support = State.FAIL;
            if (support == State.PASS) used.addAll(refs);
            supported.add(support);
        }
        if (!inventoryComplete) {
            factual.add(State.PENDING);
            supported.add(State.PENDING);
        }
        State correctness = factual.isEmpty() ? State.NA : all(factual);
        State support = !answered ? State.NA : supported.isEmpty() ? State.PENDING : all(supported);
        if (answered && inventoryComplete && support != State.PENDING && !used.containsAll(actual))
            support = State.FAIL;
        State validity = answered ? judgment(review.path("citationValidity")) : State.NA;
        if ((answered && (actual.isEmpty() || review.path("answer").asString().isBlank()))
                || (!answered && !actual.isEmpty())) validity = State.FAIL;
        List<State> dimensions = List.of(appropriate, correctness, complete, validity, support);
        State whole = all(dimensions);
        return new Result(
                id,
                execution,
                !dimensions.contains(State.PENDING),
                appropriate,
                correctness,
                complete,
                validity,
                support,
                whole,
                coverage.size(),
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
            if (result.executionStatus().equals("COMPLETED")
                    || result.executionStatus().equals("FAILED")) executed++;
            if (result.executionStatus().equals("FAILED")) failed++;
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
                            denominator == 0 ? "UNDEFINED" : "DEFINED"));
        }
        return new Summary(results.size(), executed, failed, reviewed, pending, notReady, metrics, results);
    }
}
