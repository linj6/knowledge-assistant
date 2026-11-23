package com.lnjecit.knowledge.assistant.evaluation.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class FrozenRankingEvaluationTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private JsonNode read(String path) throws Exception {
        return JSON.readTree(Files.readString(Path.of(path)));
    }

    private JsonNode fixture() throws Exception {
        return read("src/test/resources/retrieval-metrics-v1.json");
    }

    private JsonNode materials() throws Exception {
        return read("docs/evaluation/m4-b03-review-materials.json");
    }

    private JsonNode labels() throws Exception {
        return read("docs/evaluation/m4-b03-relevance-judgments.json");
    }

    @Test
    void completeMappingSharedIdealAndReproducibleEvaluation() throws Exception {
        JsonNode f = fixture(), m = materials(), j = labels();
        String original = f.toString();
        FrozenRankingEvaluation.Frozen frozen = FrozenRankingEvaluation.freeze(f, m, j);
        assertThat(f.toString()).isEqualTo(original);
        assertThat(FrozenRankingEvaluation.fixtureJson(frozen)
                        .path("pool")
                        .path("judgments")
                        .size())
                .isEqualTo(186);
        assertThat(FrozenRankingEvaluation.manifestJson(frozen).path("pools").size())
                .isEqualTo(24);
        ObjectNode result = FrozenRankingEvaluation.evaluate(frozen);
        assertThat(result).isEqualTo(read("docs/evaluation/m4-b03-frozen-ranking-evaluation.json"));
        assertThat(result).isEqualTo(FrozenRankingEvaluation.evaluate(FrozenRankingEvaluation.freeze(f, m, j)));
        Map<String, Double> ideals = new HashMap<>();
        for (JsonNode run : result.path("runs"))
            for (int k : new int[] {3, 5}) {
                String key = run.path("questionId").asString() + "/"
                        + run.path("chunking").asString() + "/" + k;
                double idcg = run.path("at" + k).path("idcg").asDouble();
                Double prior = ideals.putIfAbsent(key, idcg);
                if (prior != null) assertThat(idcg).isEqualTo(prior);
            }
        assertThat(ideals).hasSize(48);
        for (JsonNode group : result.path("groups"))
            for (String metric : new String[] {"precision3", "precision5", "ndcg3", "ndcg5"}) {
                JsonNode mean = group.path(metric);
                assertThat(mean.path("denominator").asInt()).isEqualTo(12);
                assertThat(mean.path("value").asDouble())
                        .isCloseTo(mean.path("numerator").asDouble() / 12, within(1e-12));
            }
        Files.createDirectories(Path.of("target"));
        FrozenRankingEvaluation.main(new String[0]);
        assertThat(read("target/m4-b03-ranking-evaluation.json")).isEqualTo(result);
    }

    @Test
    void rejectUnconfirmedOutsidePoolAndCrossChunking() throws Exception {
        JsonNode f = fixture(), m = materials();
        ObjectNode pending = (ObjectNode) labels().deepCopy();
        ((ObjectNode) pending.path("pools").get(0).path("judgments").get(0))
                .put("reviewStatus", "PENDING_HUMAN_CONFIRMATION");
        assertThatThrownBy(() -> FrozenRankingEvaluation.freeze(f, m, pending))
                .isInstanceOf(FrozenRankingEvaluation.Rejected.class)
                .satisfies(e -> assertThat(((FrozenRankingEvaluation.Rejected) e).status())
                        .isEqualTo("NOT_READY"));
        ObjectNode outside = (ObjectNode) labels().deepCopy();
        ((ObjectNode) outside.path("pools").get(0).path("judgments").get(0)).put("chunkId", -1);
        assertThatThrownBy(() -> FrozenRankingEvaluation.freeze(f, m, outside))
                .isInstanceOf(FrozenRankingEvaluation.Rejected.class)
                .satisfies(e -> assertThat(((FrozenRankingEvaluation.Rejected) e).status())
                        .isEqualTo("INVALID_POOL"));
        ObjectNode crossed = (ObjectNode) labels().deepCopy();
        ((ObjectNode) crossed.path("pools").get(0)).put("chunking", "MARKDOWN_STRUCTURE_AWARE");
        assertThatThrownBy(() -> FrozenRankingEvaluation.freeze(f, m, crossed))
                .isInstanceOf(FrozenRankingEvaluation.Rejected.class);
        ObjectNode hash = (ObjectNode) labels().deepCopy();
        ((ObjectNode) hash.path("pools").get(0).path("judgments").get(0)).put("contentSha256", "wrong");
        assertThatThrownBy(() -> FrozenRankingEvaluation.freeze(f, m, hash))
                .isInstanceOf(FrozenRankingEvaluation.Rejected.class);
    }

    @Test
    void macroDenominatorsExcludeOnlyUndefinedValues() {
        assertThat(FrozenRankingEvaluation.mean(Arrays.asList(0.0, 1.0, null)))
                .isEqualTo(new FrozenRankingEvaluation.Mean(1, 2));
        assertThat(FrozenRankingEvaluation.mean(Arrays.asList(null, null)).value())
                .isNull();
    }
}
