package com.lnjecit.knowledge.assistant.evaluation.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lnjecit.knowledge.assistant.evaluation.metrics.FrozenRankingEvaluation;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalService;
import com.lnjecit.knowledge.assistant.retrieval.model.RankedChunk;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalDiagnostics;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalResult;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStatus;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStrategy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class LiveEvaluationRunnerTests {
    private static final RetrievalParameters PARAMETERS = RetrievalParameters.defaults();

    private Map<Long, String> sources(FrozenRankingEvaluation.Frozen frozen) {
        Map<Long, String> sources = new LinkedHashMap<>();
        for (JsonNode pool : frozen.manifest().path("pools"))
            for (JsonNode label : pool.path("judgments"))
                sources.put(
                        label.path("documentId").asLong(),
                        label.path("sourceSha256").asString());
        return sources;
    }

    @Test
    void authorizationAndParameterValidationBeforeStartup() {
        assertThatThrownBy(() -> LiveEvaluationRunner.parse(
                        new String[] {"--knowledge-base-id=800000", "--chunking=FIXED_CODE_POINT", "--strategies=VECTOR"
                        }))
                .hasMessageContaining("AUTHORIZATION_REQUIRED");
        LiveEvaluationRunner.Options options =
                LiveEvaluationRunner.parse(new String[] {"--knowledge-base-id=800000", "--chunking=FIXED_CODE_POINT"});
        assertThat(options.strategies()).containsExactly(RetrievalStrategy.BM25);
        assertThatThrownBy(() -> LiveEvaluationRunner.parse(new String[] {
                    "--knowledge-base-id=800000",
                    "--chunking=FIXED_CODE_POINT",
                    "--strategies=HYBRID_RERANK",
                    "--authorize-embedding-cost"
                }))
                .hasMessageContaining("INPUT_ERROR");
        assertThatThrownBy(() -> LiveEvaluationRunner.parse(new String[] {
                    "--knowledge-base-id=800000",
                    "--chunking=FIXED_CODE_POINT",
                    "--output=target/evaluation/m4-c01-offline-v1"
                }))
                .hasMessageContaining("OUTPUT_ERROR");
    }

    @Test
    void scoreOnlyVerifiedSourcesAndFrozenPool() throws Exception {
        FrozenRankingEvaluation.Frozen frozen = LiveEvaluationRunner.load();
        ObjectNode run = (ObjectNode) frozen.fixture().path("runs").get(0).deepCopy();
        Map<Long, String> sources = sources(frozen);
        ObjectNode score = LiveEvaluationRunner.score(frozen, run, sources);
        assertThat(score.path("evidenceStatus").asString()).isEqualTo("SCORED");
        assertThat(score.path("ranking5").path("precision").isNumber()).isTrue();
        assertThat(LiveEvaluationRunner.score(frozen, run, Map.of())
                        .path("evidenceStatus")
                        .asString())
                .isEqualTo("GOLD_SOURCE_MISMATCH");
        ObjectNode unknown = run.deepCopy();
        ((ObjectNode) unknown.path("chunks").get(0)).put("chunkId", -1);
        assertThat(LiveEvaluationRunner.score(frozen, unknown, sources)
                        .path("rankingStatus")
                        .asString())
                .isEqualTo("PENDING_REVIEW");
        assertThat(LiveEvaluationRunner.score(frozen, unknown, sources)
                        .path("evidenceStatus")
                        .asString())
                .isEqualTo("SCORED");
        ObjectNode wrongQuery = run.deepCopy();
        wrongQuery.put("query", "其他问题");
        assertThat(LiveEvaluationRunner.score(frozen, wrongQuery, sources)
                        .path("evidenceStatus")
                        .asString())
                .isEqualTo("GOLD_QUERY_MISMATCH");
        ObjectNode changed = run.deepCopy();
        ((ObjectNode) changed.path("chunks").get(0)).put("content", "changed");
        assertThat(LiveEvaluationRunner.score(frozen, changed, sources)
                        .path("rankingStatus")
                        .asString())
                .isEqualTo("INVALID_POOL");
        run.put("snapshotHash", "different");
        assertThat(LiveEvaluationRunner.score(frozen, run, sources)
                        .path("evidenceStatus")
                        .asString())
                .isEqualTo("GOLD_SNAPSHOT_MISMATCH");
        assertThat(LiveEvaluationRunner.score(frozen, run, sources)
                        .path("rankingDenominator")
                        .asInt())
                .isZero();
    }

    @Test
    void multiStrategyConversionEmptyAndFailedArtifacts() throws Exception {
        FrozenRankingEvaluation.Frozen frozen = LiveEvaluationRunner.load();
        RetrievalService service = mock(RetrievalService.class);
        List<RetrievalStrategy> strategies =
                List.of(RetrievalStrategy.VECTOR, RetrievalStrategy.BM25, RetrievalStrategy.HYBRID_RRF);
        Path output = Path.of("target/evaluation/m4-c02-live-test-" + UUID.randomUUID());
        LiveEvaluationRunner.Options options =
                new LiveEvaluationRunner.Options(800000, "FIXED_CODE_POINT", strategies, PARAMETERS, true, output);
        when(service.search(anyLong(), anyString(), anyList(), any())).thenAnswer(call -> {
            String query = call.getArgument(1);
            List<RetrievalResult> results = new ArrayList<>();
            for (RetrievalStrategy strategy : strategies) {
                JsonNode reference = null;
                for (JsonNode run : frozen.fixture().path("runs"))
                    if (run.path("query").asString().equals(query)
                            && run.path("chunking").asString().equals("FIXED_CODE_POINT")
                            && run.path("strategy").asString().equals(strategy.name())) reference = run;
                List<RankedChunk> chunks = new ArrayList<>();
                if (strategy == RetrievalStrategy.VECTOR)
                    for (JsonNode chunk : reference.path("chunks"))
                        chunks.add(LiveEvaluationRunner.JSON.treeToValue(chunk, RankedChunk.class));
                RetrievalStatus status = strategy == RetrievalStrategy.VECTOR
                        ? RetrievalStatus.SUCCESS
                        : strategy == RetrievalStrategy.BM25 ? RetrievalStatus.EMPTY_RESULT : RetrievalStatus.FAILED;
                results.add(new RetrievalResult(
                        chunks,
                        new RetrievalDiagnostics(
                                strategy,
                                status == RetrievalStatus.FAILED ? null : strategy,
                                false,
                                status,
                                reference.path("snapshotHash").asString(),
                                PARAMETERS,
                                List.of(new RetrievalDiagnostics.Stage(
                                        RetrievalDiagnostics.Phase.BM25, 7, 0, List.of())),
                                status == RetrievalStatus.FAILED ? RetrievalDiagnostics.Failure.BM25_FAILED : null)));
            }
            return results;
        });
        try {
            LiveEvaluationRunner.execute(options, frozen, service, sources(frozen));
            verify(service, times(12))
                    .search(
                            org.mockito.ArgumentMatchers.eq(800000L),
                            org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.eq(strategies),
                            org.mockito.ArgumentMatchers.eq(PARAMETERS));
            JsonNode manifest = LiveEvaluationRunner.JSON.readTree(Files.readString(output.resolve("manifest.json")));
            assertThat(manifest.path("mode").asString()).isEqualTo("LIVE");
            assertThat(manifest.path("status").asString()).isEqualTo("PARTIAL_FAILURE");
            assertThat(manifest.path("artifactSha256").size()).isEqualTo(3);
            List<String> lines = Files.readAllLines(output.resolve("retrieval-results.jsonl"));
            assertThat(lines).hasSize(36);
            JsonNode empty = LiveEvaluationRunner.JSON.readTree(lines.get(1));
            assertThat(empty.path("status").asString()).isEqualTo("EMPTY_RESULT");
            assertThat(empty.path("diagnostics")
                            .path("stages")
                            .get(0)
                            .path("elapsedMillis")
                            .asInt())
                    .isEqualTo(7);
            JsonNode metrics = LiveEvaluationRunner.JSON.readTree(Files.readString(output.resolve("metrics.json")));
            assertThat(metrics.path("runs")
                            .get(1)
                            .path("evidence5")
                            .path("covered")
                            .asInt())
                    .isZero();
            assertThat(metrics.path("runs").get(2).path("evidenceDenominator").asInt())
                    .isZero();
            assertThatThrownBy(() -> LiveEvaluationRunner.execute(options, frozen, service, sources(frozen)))
                    .hasMessageContaining("OUTPUT_ERROR");
        } finally {
            cleanup(output);
        }
    }

    @Test
    void searchExceptionsProduceFailedReportWithoutSensitiveError() throws Exception {
        FrozenRankingEvaluation.Frozen frozen = LiveEvaluationRunner.load();
        RetrievalService service = mock(RetrievalService.class);
        when(service.search(anyLong(), anyString(), anyList(), any()))
                .thenThrow(new IllegalStateException("sensitive-provider-body"));
        Path output = Path.of("target/evaluation/m4-c02-live-test-" + UUID.randomUUID());
        LiveEvaluationRunner.Options options = new LiveEvaluationRunner.Options(
                800000, "FIXED_CODE_POINT", List.of(RetrievalStrategy.BM25), PARAMETERS, false, output);
        try {
            LiveEvaluationRunner.execute(options, frozen, service, sources(frozen));
            JsonNode manifest = LiveEvaluationRunner.JSON.readTree(Files.readString(output.resolve("manifest.json")));
            assertThat(manifest.path("status").asString()).isEqualTo("FAILED");
            assertThat(manifest.path("retrievalStatuses").path("FAILED").asInt())
                    .isEqualTo(12);
            assertThat(Files.readString(output.resolve("retrieval-results.jsonl")))
                    .doesNotContain("sensitive-provider-body");
            for (String file : List.of("retrieval-results.jsonl", "metrics.json", "report.md")) {
                String actual = java.util.HexFormat.of()
                        .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                .digest(Files.readAllBytes(output.resolve(file))));
                assertThat(manifest.path("artifactSha256").path(file).asString())
                        .isEqualTo(actual);
            }
        } finally {
            cleanup(output);
        }
    }

    private void cleanup(Path output) throws Exception {
        if (Files.exists(output)) {
            for (String file : List.of("manifest.json", "metrics.json", "report.md", "retrieval-results.jsonl"))
                Files.deleteIfExists(output.resolve(file));
            Files.delete(output);
        }
    }
}
