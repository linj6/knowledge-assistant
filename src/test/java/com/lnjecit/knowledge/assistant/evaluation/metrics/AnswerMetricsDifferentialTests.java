package com.lnjecit.knowledge.assistant.evaluation.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class AnswerMetricsDifferentialTests {
    @Test
    void thirtyFrozenBaselineResultsRemainIdenticalFieldByField() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        JsonNode dataset =
                json.readTree(new org.springframework.core.io.ClassPathResource("evaluation/answer-evaluation-v1.json")
                        .getContentAsByteArray());
        for (int i = 0; i < 30; i++) {
            JsonNode gold = dataset.path("cases").get(i % 16);
            ObjectNode review = json.createObjectNode();
            review.put("caseId", gold.path("caseId").asString());
            review.put("datasetVersion", 1);
            review.put("freezeFingerprint", dataset.path("freezeFingerprint").asString());
            review.put("executionStatus", i % 5 == 0 ? "FAILED" : "COMPLETED");
            review.put("ragStatus", i % 5 == 0 ? "FAILED" : i % 3 == 0 ? "INSUFFICIENT_EVIDENCE" : "ANSWERED");
            if (i % 5 == 0) review.putNull("answer");
            else review.put("answer", "合成答案");
            review.put("error", i % 2 == 0 ? "CITATION_INVALID" : "MODEL_TIMEOUT");
            review.putArray("citations");
            if (i % 5 != 0 && i % 3 != 0) review.withArray("citations").add("E1");
            review.put("reviewStatus", i % 7 == 0 ? "PENDING" : "CONFIRMED");
            review.put("reviewer", "TEST");
            review.put("basis", "冻结基线差分，非人工质量结论");
            review.put("knowledgeAnswerable", gold.path("knowledgeAnswerable").asBoolean());
            review.put("contextAnswerable", i % 3 != 0);
            review.put("appropriateness", i % 4 == 0 ? "FAIL" : "PASS");
            review.put("citationValidity", "PASS");
            review.put("claimsComplete", true);
            ObjectNode coverage = review.putObject("factCoverage");
            for (JsonNode fact : gold.path("requiredFacts"))
                coverage.put(fact.path("factId").asString(), i % 2 == 0 ? "PASS" : "FAIL");
            ObjectNode claim = review.putArray("claims").addObject();
            claim.put("claimId", "C1");
            claim.put("text", "合成断言");
            claim.put("correctness", i % 6 == 0 ? "PENDING" : "PASS");
            claim.put("support", "PASS");
            claim.putArray("citationIds").add("E1");
            assertThat(json.<JsonNode>valueToTree(AnswerMetrics.calculate(dataset, gold, review)))
                    .as("case %s", i)
                    .isEqualTo(json.<JsonNode>valueToTree(BaselineAnswerMetrics.calculate(dataset, gold, review)));
            assertThat(json.<JsonNode>valueToTree(AnswerMetrics.summarize(dataset, List.of(review))))
                    .isEqualTo(json.<JsonNode>valueToTree(BaselineAnswerMetrics.summarize(dataset, List.of(review))));
        }
    }
}
