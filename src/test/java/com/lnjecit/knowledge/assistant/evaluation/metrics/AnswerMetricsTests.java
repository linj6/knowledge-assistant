package com.lnjecit.knowledge.assistant.evaluation.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class AnswerMetricsTests {
    private final JsonMapper json = JsonMapper.builder().build();

    private JsonNode dataset() throws Exception {
        return json.readTree(new org.springframework.core.io.ClassPathResource("evaluation/answer-evaluation-v1.json")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private ObjectNode review(JsonNode d, int index) {
        JsonNode gold = d.path("cases").get(index);
        ObjectNode r = json.createObjectNode();
        r.put("caseId", gold.path("caseId").asString());
        r.put("datasetVersion", 1);
        r.put("freezeFingerprint", d.path("freezeFingerprint").asString());
        r.put("executionStatus", "COMPLETED");
        r.put("ragStatus", "ANSWERED");
        r.put("answer", "测试答案");
        r.putArray("citations").add("E1");
        r.put("knowledgeAnswerable", gold.path("knowledgeAnswerable").asBoolean());
        r.put("contextAnswerable", true);
        r.put("appropriateness", "PASS");
        r.put("citationValidity", "PASS");
        r.put("reviewStatus", "CONFIRMED");
        r.put("reviewer", "TEST_ONLY");
        r.put("basis", "合成测试，非正式评分");
        r.put("claimsComplete", true);
        ObjectNode coverage = r.putObject("factCoverage");
        for (JsonNode fact : gold.path("requiredFacts"))
            coverage.put(fact.path("factId").asString(), "PASS");
        ObjectNode claim = r.putArray("claims").addObject();
        claim.put("claimId", "C1");
        claim.put("text", "合成断言");
        claim.put("correctness", "PASS");
        claim.put("support", "PASS");
        claim.putArray("citationIds").add("E1");
        return r;
    }

    private AnswerMetrics.Result score(JsonNode d, int index, JsonNode r) {
        return AnswerMetrics.calculate(
                AnswerMetrics.readDataset(d),
                json.treeToValue(
                        d.path("cases").get(index),
                        com.lnjecit.knowledge.assistant.evaluation.AnswerEvaluationDataset.Case.class),
                AnswerMetrics.readReview(r));
    }

    private ObjectNode claim(ObjectNode r) {
        return (ObjectNode) r.path("claims").get(0);
    }

    @Test
    void correctAndRequiredOmissionAndOptional() throws Exception {
        JsonNode d = dataset();
        ObjectNode r = review(d, 2);
        assertThat(score(d, 2, r).wholeCase()).isEqualTo(AnswerMetrics.State.PASS);
        assertThat(score(d, 2, r).requiredCount()).isEqualTo(7);
        ((ObjectNode) r.path("factCoverage")).put("Q03-F08", "FAIL");
        assertThat(score(d, 2, r).completeness()).isEqualTo(AnswerMetrics.State.PASS);
        ((ObjectNode) r.path("factCoverage")).put("Q03-F01", "FAIL");
        assertThat(score(d, 2, r).wholeCase()).isEqualTo(AnswerMetrics.State.FAIL);
        ((ObjectNode) r.path("factCoverage")).remove("Q03-F01");
        assertThat(score(d, 2, r).completeness()).isEqualTo(AnswerMetrics.State.PENDING);
    }

    @Test
    void claimsAndJointSupport() throws Exception {
        JsonNode d = dataset();
        ObjectNode r = review(d, 0);
        r.withArray("citations").add("E2");
        claim(r).withArray("citationIds").add("E2");
        assertThat(score(d, 0, r).support()).isEqualTo(AnswerMetrics.State.PASS);
        claim(r).put("support", "FAIL");
        assertThat(score(d, 0, r).correctness()).isEqualTo(AnswerMetrics.State.PASS);
        assertThat(score(d, 0, r).support()).isEqualTo(AnswerMetrics.State.FAIL);
        claim(r).put("correctness", "FAIL");
        assertThat(score(d, 0, r).correctness()).isEqualTo(AnswerMetrics.State.FAIL);
        claim(r).put("support", "PASS");
        claim(r).putArray("citationIds").add("E3");
        assertThat(score(d, 0, r).support()).isEqualTo(AnswerMetrics.State.FAIL);
    }

    @Test
    void unrelatedCitationAndIncompleteInventory() throws Exception {
        JsonNode d = dataset();
        ObjectNode r = review(d, 0);
        r.withArray("citations").add("E2");
        assertThat(score(d, 0, r).support()).isEqualTo(AnswerMetrics.State.FAIL);
        r.put("claimsComplete", false);
        assertThat(score(d, 0, r).support()).isEqualTo(AnswerMetrics.State.PENDING);
        claim(r).remove("correctness");
        assertThat(score(d, 0, r).correctness()).isEqualTo(AnswerMetrics.State.PENDING);
    }

    @Test
    void refusalAndWrongRefusal() throws Exception {
        JsonNode d = dataset();
        ObjectNode r = review(d, 12);
        r.put("ragStatus", "INSUFFICIENT_EVIDENCE");
        r.put("contextAnswerable", false);
        r.putArray("citations");
        r.putArray("claims");
        assertThat(score(d, 12, r).wholeCase()).isEqualTo(AnswerMetrics.State.PASS);
        assertThat(score(d, 12, r).support()).isEqualTo(AnswerMetrics.State.NA);
        r.put("caseId", "Q01");
        r.put("knowledgeAnswerable", true);
        assertThat(score(d, 0, r).appropriateness()).isEqualTo(AnswerMetrics.State.PASS);
        assertThat(score(d, 0, r).completeness()).isEqualTo(AnswerMetrics.State.FAIL);
        r.put("contextAnswerable", true);
        assertThat(score(d, 0, r).appropriateness()).isEqualTo(AnswerMetrics.State.FAIL);
    }

    @Test
    void failureMissingAndIdentity() throws Exception {
        JsonNode d = dataset();
        ObjectNode r = review(d, 0);
        r.put("executionStatus", "FAILED");
        r.put("ragStatus", "FAILED");
        r.putNull("answer");
        r.putArray("citations");
        r.put("error", "MODEL_TIMEOUT");
        assertThat(score(d, 0, r).wholeCase()).isEqualTo(AnswerMetrics.State.FAIL);
        assertThat(score(d, 0, r).correctness()).isEqualTo(AnswerMetrics.State.NA);
        r.put("error", "CITATION_INVALID");
        assertThat(score(d, 0, r).validity()).isEqualTo(AnswerMetrics.State.FAIL);
        assertThat(score(d, 0, null).wholeCase()).isEqualTo(AnswerMetrics.State.PENDING);
        r.put("freezeFingerprint", "other");
        assertThat(score(d, 0, r).wholeCase()).isEqualTo(AnswerMetrics.State.NOT_READY);
    }

    @Test
    void missingContextAndDuplicateClaimsPreservePendingAndFailurePriority() throws Exception {
        JsonNode d = dataset();
        ObjectNode r = review(d, 0);
        r.remove("contextAnswerable");
        assertThat(score(d, 0, r).appropriateness()).isEqualTo(AnswerMetrics.State.PENDING);
        r.put("appropriateness", "FAIL");
        assertThat(score(d, 0, r).appropriateness()).isEqualTo(AnswerMetrics.State.FAIL);
        assertThat(score(d, 0, r).wholeCase()).isEqualTo(AnswerMetrics.State.FAIL);
        r.put("contextAnswerable", true);
        r.put("appropriateness", "PASS");
        r.withArray("claims").add(claim(r).deepCopy());
        assertThat(score(d, 0, r).correctness()).isEqualTo(AnswerMetrics.State.PENDING);
        assertThat(score(d, 0, r).support()).isEqualTo(AnswerMetrics.State.PENDING);
    }

    @Test
    void aggregateAndSerialization() throws Exception {
        JsonNode d = dataset();
        ObjectNode good = review(d, 0), failed = review(d, 1), invalid = review(d, 2);
        failed.put("executionStatus", "FAILED");
        failed.put("ragStatus", "FAILED");
        failed.putNull("answer");
        failed.putArray("citations");
        failed.put("error", "MODEL_TIMEOUT");
        invalid.put("freezeFingerprint", "wrong");
        AnswerMetrics.Summary s = AnswerMetrics.summarize(d, List.of(good, failed, invalid));
        AnswerMetrics.Metric m = s.metrics().get("wholeCase");
        assertThat(m.numerator()).isEqualTo(1);
        assertThat(m.denominator()).isEqualTo(2);
        assertThat(m.value()).isEqualTo(0.5);
        assertThat(m.counts().get(AnswerMetrics.State.PENDING)).isEqualTo(13);
        assertThat(m.counts().get(AnswerMetrics.State.NOT_READY)).isEqualTo(1);
        assertThat(s.metrics().get("correctness").counts().get(AnswerMetrics.State.NA))
                .isEqualTo(1);
        assertThat(s.executed()).isEqualTo(3);
        assertThat(s.failed()).isEqualTo(1);
        assertThat(json.writeValueAsString(s)).contains("wholeCase");
        AnswerMetrics.Metric empty =
                AnswerMetrics.summarize(d, List.of()).metrics().get("wholeCase");
        assertThat(empty.value()).isNull();
        assertThat(empty.status()).isEqualTo("UNDEFINED");
        assertThatThrownBy(() -> AnswerMetrics.summarize(d, List.of(good, good)))
                .isInstanceOf(IllegalArgumentException.class);
        ((ObjectNode) d).put("datasetVersion", 2);
        assertThat(score(d, 0, good).wholeCase()).isEqualTo(AnswerMetrics.State.NOT_READY);
    }
}
