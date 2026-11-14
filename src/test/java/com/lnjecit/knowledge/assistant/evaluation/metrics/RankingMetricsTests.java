package com.lnjecit.knowledge.assistant.evaluation.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RankingMetricsTests {
    private final JsonMapper mapper = JsonMapper.builder().build();

    private JsonNode fixture(String labels, String status) throws Exception {
        String[] grades = labels.split(",");
        StringBuilder judgments = new StringBuilder();
        StringBuilder chunks = new StringBuilder();
        for (int i = 0; i < grades.length; i++) {
            if (i > 0) {
                judgments.append(',');
                chunks.append(',');
            }
            judgments
                    .append("{\"questionId\":\"Q\",\"chunking\":\"A\",\"chunkId\":")
                    .append(i)
                    .append(",\"relevance\":\"")
                    .append(grades[i])
                    .append("\",\"reviewer\":\"human\"}");
            chunks.append("{\"documentId\":1,\"indexVersionId\":2,\"chunkId\":")
                    .append(i)
                    .append(",\"content\":\"text\",\"finalRank\":")
                    .append(i + 1)
                    .append('}');
        }
        return mapper.readTree("{\"pool\":{\"status\":\"" + status + "\",\"judgments\":[" + judgments
                + "]},\"runs\":[{\"questionId\":\"Q\",\"chunking\":\"A\",\"snapshotHash\":\"s\",\"status\":\"SUCCESS\",\"chunks\":["
                + chunks + "]}]}");
    }

    @Test
    void gradesAndFixedDenominator() throws Exception {
        JsonNode f = fixture("3,2,1,0", "COMPLETE");
        RankingMetrics.Result r = RankingMetrics.calculate(f, f.path("runs").get(0), 5);
        assertThat(r.precision()).isEqualTo(.4);
        assertThat(r.ndcg()).isCloseTo(1, within(1e-12));
        assertThat(RankingMetrics.dcg(List.of(0, 1, 2, 3), 5)).isLessThan(r.dcg());
        assertThat(RankingMetrics.calculate(f, f.path("runs").get(0), 3).precision())
                .isEqualTo(2.0 / 3);
    }

    @Test
    void unavailableStates() throws Exception {
        for (String status : List.of("PENDING", "COMPLETE")) {
            JsonNode f = fixture("UNJUDGED", status);
            assertThat(RankingMetrics.calculate(f, f.path("runs").get(0), 3).status())
                    .isEqualTo("NOT_READY");
        }
        JsonNode zero = fixture("0,0", "COMPLETE");
        assertThat(RankingMetrics.calculate(zero, zero.path("runs").get(0), 3).ndcg())
                .isNull();
        assertThat(RankingMetrics.calculate(zero, zero.path("runs").get(0), 3).status())
                .isEqualTo("UNDEFINED");
        JsonNode f = fixture("3,2", "COMPLETE");
        String run = f.path("runs").get(0).toString();
        assertThat(RankingMetrics.calculate(f, mapper.readTree(run.replace("\"chunkId\":0", "\"chunkId\":99")), 3)
                        .status())
                .isEqualTo("OUTSIDE_POOL");
        assertThat(RankingMetrics.calculate(
                                f, mapper.readTree(run.replace("\"chunking\":\"A\"", "\"chunking\":\"B\"")), 3)
                        .status())
                .isEqualTo("NOT_READY");
        assertThat(RankingMetrics.calculate(
                                f,
                                mapper.readTree(run.replace("SUCCESS", "SUCCESS")
                                        .replace("\"chunks\"", "\"strategy\":\"OTHER\",\"chunks\"")),
                                3)
                        .idcg())
                .isEqualTo(RankingMetrics.calculate(f, f.path("runs").get(0), 3).idcg());
    }

    @Test
    void realFixtureAndMaterials() throws Exception {
        JsonNode f = mapper.readTree(Files.readString(Path.of("src/test/resources/retrieval-metrics-v1.json")));
        JsonNode materials =
                mapper.readTree(Files.readString(Path.of("docs/engineering/m4-b03-review-materials.json")));
        assertThat(f.path("runs").size()).isEqualTo(72);
        int count = 0;
        int hits = 0;
        for (JsonNode pool : materials.path("pools"))
            for (JsonNode candidate : pool.path("candidates")) {
                count++;
                hits += candidate.path("hits").size();
                assertThat(candidate.path("relevance").asString()).isEqualTo("UNJUDGED");
                assertThat(candidate.path("content").asString()).isNotBlank();
            }
        assertThat(count).isEqualTo(186);
        assertThat(hits).isEqualTo(360);
        for (JsonNode run : f.path("runs"))
            assertThat(RankingMetrics.calculate(f, run, 5).status()).isEqualTo("NOT_READY");
    }
}
