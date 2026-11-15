package com.lnjecit.knowledge.assistant.evaluation.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class EvidenceMetricsTests {
    @Test
    void frozenSixGroups() throws Exception {
        JsonNode fixture = JsonMapper.builder()
                .build()
                .readTree(Files.readString(Path.of("src/test/resources/retrieval-metrics-v1.json")));
        List<String> paths = Files.readAllLines(Path.of("docs/engineering/m4-01-samples.txt"));
        double[][] expected = {
            {.583, .750, .750, .468},
            {.667, .833, .875, .746},
            {.667, .833, .833, .662},
            {.833, .833, .833, .736},
            {.917, .917, .958, .896},
            {.917, .917, .917, .875}
        };
        int group = 0;
        for (String chunking : List.of("FIXED_CODE_POINT", "MARKDOWN_STRUCTURE_AWARE")) {
            List<EvidenceMetrics.Source> sources = new ArrayList<>();
            long base = group == 0 ? 800000 : 900000;
            for (int i = 0; i < paths.size(); i++) {
                String path = paths.get(i);
                String hash = HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(path))));
                for (JsonNode gold : fixture.path("gold").path("questions"))
                    if (gold.path("documentPath").asString().equals(path))
                        assertThat(hash).isEqualTo(gold.path("sourceSha256").asString());
                assertThat(hash).isNotNull();
                sources.add(new EvidenceMetrics.Source(base + 100 + i, base + 200 + i, path, hash));
            }
            for (String strategy : List.of("VECTOR", "BM25", "HYBRID_RRF")) {
                double[] sums = new double[4];
                List<EvidenceMetrics.Case> cases = new ArrayList<>();
                List<Integer> firstRanks = new ArrayList<>();
                int count = 0;
                for (JsonNode run : fixture.path("runs")) {
                    if (!run.path("chunking").asString().equals(chunking)
                            || !run.path("strategy").asString().equals(strategy)) continue;
                    JsonNode gold = null;
                    for (JsonNode candidate : fixture.path("gold").path("questions"))
                        if (candidate.path("questionId").equals(run.path("questionId"))) gold = candidate;
                    cases.add(new EvidenceMetrics.Case(gold, run, sources));
                    EvidenceMetrics.Result three = EvidenceMetrics.calculate(gold, run, sources, 3);
                    EvidenceMetrics.Result five = EvidenceMetrics.calculate(gold, run, sources, 5);
                    if (group == 4 && run.path("questionId").asString().equals("Q04")) {
                        assertThat(five.firstRank()).isEqualTo(4);
                        assertThat(five.completeRank()).isNull();
                        assertThat(five.covered()).isEqualTo(1);
                        assertThat(five.evidenceCount()).isEqualTo(2);
                    }
                    if (group == 1 && run.path("questionId").asString().equals("Q08")) {
                        assertThat(three.covered()).isZero();
                        assertThat(five.completeRank()).isEqualTo(5);
                    }
                    firstRanks.add(five.firstRank() == null ? 0 : five.firstRank());
                    sums[0] += three.cec();
                    sums[1] += five.cec();
                    sums[2] += five.recall();
                    sums[3] += five.mrr();
                    if (group == 2)
                        System.out.println("MRR " + run.path("questionId").asString() + " rank=" + five.firstRank()
                                + " contribution=" + five.mrr());
                    if (run.path("questionId").asString().equals("Q04")
                            || run.path("questionId").asString().equals("Q08")) {
                        List<Integer> coverage = new ArrayList<>();
                        for (int k = 1; k <= 5; k++)
                            coverage.add(EvidenceMetrics.calculate(gold, run, sources, k)
                                    .covered());
                        System.out.println("CCR " + chunking + " " + strategy + " "
                                + run.path("questionId").asString() + " " + coverage + " first=" + five.firstRank()
                                + " ccr=" + five.completeRank());
                    }
                    count++;
                }
                if (group == 2) assertThat(firstRanks).containsExactly(1, 1, 1, 0, 1, 1, 5, 0, 4, 2, 1, 1);
                assertThat(count).isEqualTo(12);
                EvidenceMetrics.Summary summary = EvidenceMetrics.summarize(cases);
                assertThat(summary.scoredCases()).isEqualTo(12);
                System.out.println("SUMMARY " + chunking + " " + strategy + " " + summary);
                // 历史表是三位小数展示；原始值另以冻结的排名贡献和精确分数验证。
                for (int i = 0; i < 4; i++) {
                    double rounded = java.math.BigDecimal.valueOf(sums[i] / count)
                            .setScale(3, java.math.RoundingMode.HALF_EVEN)
                            .doubleValue();
                    assertThat(rounded).isEqualTo(expected[group][i]);
                }
                double[] exactMrr = {337.0 / 720, 179.0 / 240, 53.0 / 80, 53.0 / 72, 43.0 / 48, 7.0 / 8};
                assertThat(summary.mrr5().value())
                        .isCloseTo(exactMrr[group], org.assertj.core.data.Offset.offset(1e-12));
                group++;
            }
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<EvidenceMetrics.Source> SOURCES =
            List.of(new EvidenceMetrics.Source(1, 1, "a", "hash"), new EvidenceMetrics.Source(2, 2, "b", "other"));

    private static JsonNode gold(String spans) {
        return JSON.readTree(
                "{\"reviewStatus\":\"CONFIRMED\",\"relation\":\"ALL\",\"documentPath\":\"a\",\"sourceSha256\":\"hash\",\"evidence\":["
                        + spans + "]}");
    }

    private static JsonNode run(String status, int[]... chunks) {
        StringBuilder text = new StringBuilder("{\"status\":\"" + status + "\",\"chunks\":[");
        for (int i = 0; i < chunks.length; i++) {
            if (i > 0) text.append(',');
            int[] c = chunks[i];
            text.append("{\"documentId\":" + c[0] + ",\"indexVersionId\":" + c[0] + ",\"finalRank\":" + (i + 1)
                    + ",\"sourceStart\":" + c[1] + ",\"sourceEnd\":" + c[2] + "}");
        }
        return JSON.readTree(text.append("]}").toString());
    }

    private static final String E = "{\"sourceSpan\":{\"start\":0,\"end\":10}}";

    @Test
    void continuousCoverageAndIdentity() {
        JsonNode gold = gold(E);
        assertThat(EvidenceMetrics.calculate(gold, run("SUCCESS", new int[] {1, 0, 10}), SOURCES, 5)
                        .cec())
                .isEqualTo(1);
        assertThat(EvidenceMetrics.calculate(
                                gold, run("SUCCESS", new int[] {1, 0, 5}, new int[] {1, 5, 10}), SOURCES, 5)
                        .completeRank())
                .isEqualTo(2);
        assertThat(EvidenceMetrics.calculate(
                                gold, run("SUCCESS", new int[] {1, 0, 4}, new int[] {1, 5, 10}), SOURCES, 5)
                        .cec())
                .isZero();
        assertThat(EvidenceMetrics.calculate(
                                gold, run("SUCCESS", new int[] {1, 0, 5}, new int[] {2, 5, 10}), SOURCES, 5)
                        .cec())
                .isZero();
        assertThat(EvidenceMetrics.calculate(gold, run("SUCCESS", new int[] {1, 0, 5}, new int[] {1, 0, 5}), SOURCES, 5)
                        .covered())
                .isZero();
        assertThatThrownBy(() -> EvidenceMetrics.calculate(
                        gold,
                        run("SUCCESS", new int[] {1, 0, 10}),
                        List.of(new EvidenceMetrics.Source(1, 1, "a", "wrong")),
                        5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Hash");
        assertThatThrownBy(() -> EvidenceMetrics.calculate(gold, run("SUCCESS", new int[] {1, 0, 10}), List.of(), 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allEvidenceRanksAndCutoff() {
        JsonNode gold = gold(E + ",{\"sourceSpan\":{\"start\":20,\"end\":30}}");
        JsonNode run = run("SUCCESS", new int[] {1, 0, 10}, new int[] {1, 20, 25}, new int[] {1, 25, 30});
        EvidenceMetrics.Result partial = EvidenceMetrics.calculate(gold, run, SOURCES, 2);
        assertThat(partial.covered()).isEqualTo(1);
        assertThat(partial.evidenceCount()).isEqualTo(2);
        assertThat(partial.recall()).isEqualTo(.5);
        assertThat(partial.hit()).isEqualTo(1);
        assertThat(partial.cec()).isZero();
        assertThat(partial.completeRank()).isNull();
        assertThat(partial.depth()).isEqualTo(2);
        EvidenceMetrics.Result full = EvidenceMetrics.calculate(gold, run, SOURCES, 3);
        assertThat(full.firstRank()).isEqualTo(1);
        assertThat(full.completeRank()).isEqualTo(3);
        assertThat(full.cec()).isEqualTo(1);
    }

    @Test
    void statesAndDenominators() {
        JsonNode gold = gold(E);
        JsonNode empty = run("SUCCESS");
        JsonNode failed = run("FAILED");
        JsonNode pending = JSON.readTree("{\"reviewStatus\":\"PENDING\"}");
        JsonNode rejected = JSON.readTree("{\"reviewStatus\":\"REJECTED\"}");
        JsonNode unanswerable = JSON.readTree("{\"answerable\":false}");
        assertThat(EvidenceMetrics.calculate(gold, empty, SOURCES, 5).qualityStatus())
                .isEqualTo("EMPTY_RESULT");
        assertThat(EvidenceMetrics.calculate(gold, failed, SOURCES, 5).executionStatus())
                .isEqualTo("FAILED");
        assertThat(EvidenceMetrics.calculate(null, empty, SOURCES, 5).qualityStatus())
                .isEqualTo("MISSING_GOLD");
        assertThat(EvidenceMetrics.calculate(unanswerable, empty, SOURCES, 5).qualityStatus())
                .isEqualTo("UNANSWERABLE");
        assertThat(EvidenceMetrics.calculate(pending, empty, SOURCES, 5).qualityStatus())
                .isEqualTo("PENDING_REVIEW");
        assertThat(EvidenceMetrics.calculate(rejected, empty, SOURCES, 5).qualityStatus())
                .isEqualTo("REJECTED_GOLD");
        List<EvidenceMetrics.Case> cases = new ArrayList<>();
        for (JsonNode g : List.of(gold, pending, rejected, unanswerable))
            cases.add(new EvidenceMetrics.Case(g, empty, SOURCES));
        cases.add(new EvidenceMetrics.Case(null, empty, SOURCES));
        cases.add(new EvidenceMetrics.Case(gold, failed, SOURCES));
        EvidenceMetrics.Summary summary = EvidenceMetrics.summarize(cases);
        assertThat(summary.totalCases()).isEqualTo(6);
        assertThat(summary.eligibleCases()).isEqualTo(2);
        assertThat(summary.scoredCases()).isEqualTo(1);
        assertThat(summary.successCount()).isEqualTo(5);
        assertThat(summary.emptyResultCount()).isEqualTo(5);
        assertThat(summary.failedCount()).isEqualTo(1);
        assertThat(summary.pendingReviewCount()).isEqualTo(1);
        assertThat(summary.recall5()).isEqualTo(new EvidenceMetrics.Metric(0, 1));
        assertThat(EvidenceMetrics.summarize(List.of()).mrr5().value()).isNull();
    }

    @org.junit.jupiter.api.Test
    void missingGoldFieldsKeepLegacyExclusions() {
        JsonNode empty = run("SUCCESS");
        assertThat(EvidenceMetrics.calculate(JSON.readTree("{}"), empty, SOURCES, 5)
                        .qualityStatus())
                .isEqualTo("REJECTED_GOLD");
        assertThat(EvidenceMetrics.calculate(JSON.readTree("{\"reviewStatus\":\"CONFIRMED\"}"), empty, SOURCES, 5)
                        .qualityStatus())
                .isEqualTo("MISSING_GOLD");
    }
}
