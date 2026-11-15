package com.lnjecit.knowledge.assistant.evaluation.runner;

import com.lnjecit.knowledge.assistant.evaluation.dataset.FrozenFingerprint;
import com.lnjecit.knowledge.assistant.evaluation.metrics.EvidenceMetrics;
import com.lnjecit.knowledge.assistant.evaluation.metrics.FrozenRankingEvaluation;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.FrozenPool;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.FrozenPools;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.GoldQuestionIdentity;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.OfflineMetricsReportGroupsItemEvidence;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.OfflineMetricsReportGroupsItemPrecision3;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.OfflineRetrievalRunResult;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.RelevanceJudgment;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.RetrievalFixture;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.RetrievalRunManifest;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 仅复算 C01 固定数据集，不启动 Spring。 */
public final class OfflineEvaluationRunner {
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    static final Path OUTPUT = Path.of("target/evaluation/m4-c01-offline-v1");
    private static final List<String> INPUTS = List.of(
            "src/test/resources/retrieval-metrics-v1.json",
            "docs/engineering/m4-b03-review-materials.json",
            "docs/engineering/m4-b03-relevance-judgments.json");
    private static final List<String> ARTIFACTS = List.of("retrieval-results.jsonl", "metrics.json", "report.md");

    private OfflineEvaluationRunner() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 1 || (args.length == 1 && !args[0].equals("--overwrite")))
            throw new IllegalArgumentException("仅支持 --overwrite");
        run(OUTPUT, args.length == 1);
    }

    static void run(Path output, boolean overwrite) throws Exception {
        if (Files.exists(output) && !overwrite) throw new IOException("OUTPUT_ERROR: 输出目录已存在: " + output);
        RetrievalFixture fixture;
        FrozenRankingEvaluation.ReviewMaterials materials;
        FrozenRankingEvaluation.ConfirmedJudgments judgments;
        FrozenRankingEvaluation.InputFingerprints fingerprints;
        Map<String, String> hashes = new LinkedHashMap<>();
        try {
            // 原始树只用于读取和规范化摘要，加载后主流程只保留类型化数据。
            List<JsonNode> nodes = new ArrayList<>();
            for (String input : INPUTS) {
                byte[] bytes = Files.readAllBytes(Path.of(input));
                nodes.add(JSON.readTree(bytes));
                hashes.put(input, hash(bytes));
            }
            fixture = JSON.treeToValue(nodes.get(0), RetrievalFixture.class);
            materials = JSON.treeToValue(nodes.get(1), FrozenRankingEvaluation.ReviewMaterials.class);
            judgments = JSON.treeToValue(nodes.get(2), FrozenRankingEvaluation.ConfirmedJudgments.class);
            fingerprints = new FrozenRankingEvaluation.InputFingerprints(
                    FrozenFingerprint.fingerprint(nodes.get(0)),
                    FrozenFingerprint.fingerprint(nodes.get(1)),
                    FrozenFingerprint.fingerprint(nodes.get(2)));
        } catch (Exception e) {
            throw new IOException("INPUT_ERROR: 无法读取固定输入", e);
        }
        FrozenRankingEvaluation.Frozen frozen;
        try {
            frozen = FrozenRankingEvaluation.freeze(fixture, materials, judgments, fingerprints);
            // C01 仅接受已确认的 v1 输入，不能悄悄换用另一版 Gold 或标签。
            if (!frozen.manifest()
                    .fingerprint()
                    .equals("4100bec441b6dde69c893246af86313c95e6aa28e8cff789cf4aff6b724e388c"))
                throw new IllegalArgumentException("冻结指纹不是已确认的 retrieval-metrics-v1");
        } catch (IOException e) {
            throw new IOException("INPUT_ERROR: 无法读取冻结来源原文字节", e);
        } catch (Exception e) {
            throw new IllegalArgumentException("FREEZE_ERROR: 身份、Hash 或确认状态不一致", e);
        }
        Metrics metrics;
        String report;
        try {
            metrics = metrics(frozen, FrozenRankingEvaluation.evaluateTyped(frozen));
            report = summarize(frozen, metrics);
        } catch (Exception e) {
            throw new IllegalStateException("METRIC_ERROR: 指标计算失败", e);
        }
        Path staging = null;
        try {
            // 先在同级临时目录生成全部文件，再发布；失败不留下完整成功报告。
            Files.createDirectories(output.toAbsolutePath().getParent());
            staging = Files.createTempDirectory(output.toAbsolutePath().getParent(), ".offline-");
            Files.writeString(
                    staging.resolve("metrics.json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(metrics) + "\n");
            StringBuilder lines = new StringBuilder();
            for (OfflineRetrievalRunResult run : fixture.runs())
                lines.append(JSON.writeValueAsString(run)).append('\n');
            Files.writeString(staging.resolve("retrieval-results.jsonl"), lines.toString());
            Files.writeString(staging.resolve("report.md"), report);
            Map<String, String> artifacts = new LinkedHashMap<>();
            // manifest 不自引用，其余三个文件按最终 UTF-8 字节计算 Hash。
            for (String name : ARTIFACTS) artifacts.put(name, hash(Files.readAllBytes(staging.resolve(name))));
            RetrievalRunManifest manifest = new RetrievalRunManifest(
                    1L,
                    "m4-c01-offline-v1",
                    "retrieval-metrics-v1",
                    "OFFLINE",
                    "SUCCESS",
                    hashes,
                    frozen.manifest(),
                    artifacts,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
            Files.writeString(
                    staging.resolve("manifest.json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n");
            if (Files.exists(output)) {
                try (java.util.stream.Stream<Path> files = Files.list(output)) {
                    if (files.anyMatch(p -> (!ARTIFACTS.contains(p.getFileName().toString())
                                    && !p.getFileName().toString().equals("manifest.json"))
                            || !Files.isRegularFile(p))) throw new IOException("输出目录含未知内容");
                }
                Files.deleteIfExists(output.resolve("manifest.json"));
                for (String name : ARTIFACTS) Files.deleteIfExists(output.resolve(name));
                Files.delete(output);
            }
            Files.move(staging, output);
            staging = null;
        } catch (Exception e) {
            throw new IOException("OUTPUT_ERROR: 写入或发布失败", e);
        } finally {
            if (staging != null) {
                try (java.util.stream.Stream<Path> files = Files.list(staging)) {
                    for (Path file : files.toList()) Files.deleteIfExists(file);
                }
                Files.deleteIfExists(staging);
            }
        }
        System.out.println("OFFLINE SUCCESS: 72 runs / 24 pools / 186 labels; " + output + "; frozen="
                + frozen.manifest().fingerprint());
    }

    public record Group(
            String chunking,
            String strategy,
            Map<String, Map<String, Integer>> statuses,
            FrozenRankingEvaluation.Aggregate precision3,
            FrozenRankingEvaluation.Aggregate ndcg3,
            FrozenRankingEvaluation.Aggregate precision5,
            FrozenRankingEvaluation.Aggregate ndcg5,
            OfflineMetricsReportGroupsItemEvidence evidence) {}

    public record Metrics(FrozenPools frozenPool, List<FrozenRankingEvaluation.RunMetrics> runs, List<Group> groups) {}

    private static OfflineMetricsReportGroupsItemPrecision3 metric(EvidenceMetrics.Metric metric) {
        return new OfflineMetricsReportGroupsItemPrecision3(
                metric.numerator(), (long) metric.denominator(), metric.value());
    }

    private static Metrics metrics(FrozenRankingEvaluation.Frozen frozen, FrozenRankingEvaluation.Report report) {
        List<Group> groups = new ArrayList<>();
        for (FrozenRankingEvaluation.GroupMetrics group : report.groups()) {
            List<EvidenceMetrics.Case> cases = new ArrayList<>();
            for (OfflineRetrievalRunResult run : frozen.fixture().runs()) {
                if (!run.chunking().equals(group.chunking()) || !run.strategy().equals(group.strategy())) continue;
                List<EvidenceMetrics.Source> sources = new ArrayList<>();
                for (FrozenPool pool : frozen.manifest().pools()) {
                    if (!pool.chunking().equals(run.chunking())
                            || !pool.questionId().equals(run.questionId())) continue;
                    for (RelevanceJudgment label : pool.judgments())
                        sources.add(new EvidenceMetrics.Source(
                                label.documentId(),
                                label.indexVersionId(),
                                label.documentPath(),
                                label.sourceSha256()));
                }
                GoldQuestionIdentity gold = frozen.fixture().gold().questions().stream()
                        .filter(q -> q.questionId().equals(run.questionId()))
                        .findFirst()
                        .orElse(null);
                EvidenceMetrics.Gold typedGold = gold == null
                        ? null
                        : new EvidenceMetrics.Gold(
                                null,
                                gold.reviewStatus(),
                                gold.relation(),
                                gold.documentPath(),
                                gold.sourceSha256(),
                                gold.evidence());
                EvidenceMetrics.Run typedRun = new EvidenceMetrics.Run(
                        run.status(),
                        run.chunks().stream()
                                .map(c -> new EvidenceMetrics.Candidate(
                                        c.documentId(),
                                        c.indexVersionId(),
                                        c.finalRank(),
                                        c.sourceStart(),
                                        c.sourceEnd()))
                                .toList());
                cases.add(new EvidenceMetrics.Case(typedGold, typedRun, sources));
            }
            EvidenceMetrics.Summary s = EvidenceMetrics.summarize(cases);
            OfflineMetricsReportGroupsItemEvidence evidence = new OfflineMetricsReportGroupsItemEvidence(
                    (long) s.totalCases(),
                    (long) s.eligibleCases(),
                    (long) s.scoredCases(),
                    (long) s.successCount(),
                    (long) s.emptyResultCount(),
                    (long) s.failedCount(),
                    (long) s.missingCount(),
                    (long) s.pendingReviewCount(),
                    metric(s.recall3()),
                    metric(s.recall5()),
                    metric(s.cec3()),
                    metric(s.cec5()),
                    metric(s.hit3()),
                    metric(s.hit5()),
                    metric(s.mrr5()));
            groups.add(new Group(
                    group.chunking(),
                    group.strategy(),
                    group.statuses(),
                    group.precision3(),
                    group.ndcg3(),
                    group.precision5(),
                    group.ndcg5(),
                    evidence));
        }
        return new Metrics(report.frozenPool(), report.runs(), groups);
    }

    private static String summarize(FrozenRankingEvaluation.Frozen frozen, Metrics metrics) {
        StringBuilder report = new StringBuilder(
                "# C01 OFFLINE Evaluation\n\n来源：retrieval-metrics-v1；B03 confirmed judgments / review materials。\n\n冻结指纹：`"
                        + frozen.manifest().fingerprint()
                        + "`\n\n|Chunking / Strategy|Recall@3 / @5|CEC@3 / @5|Hit@3 / @5|MRR@5|P@3 / @5|nDCG@3 / @5|Evidence scored / total|Ranking statuses|\n|---|---|---|---|---|---|---|---|---|\n");
        for (Group group : metrics.groups()) {
            OfflineMetricsReportGroupsItemEvidence e = group.evidence();
            report.append('|').append(group.chunking()).append(" / ").append(group.strategy());
            for (List<Double> pair : List.of(
                    java.util.Arrays.asList(e.recall3().value(), e.recall5().value()),
                    java.util.Arrays.asList(e.cec3().value(), e.cec5().value()),
                    java.util.Arrays.asList(e.hit3().value(), e.hit5().value())))
                report.append('|').append(display(pair.get(0))).append(" / ").append(display(pair.get(1)));
            report.append('|').append(display(e.mrr5().value()));
            report.append('|')
                    .append(display(group.precision3().value()))
                    .append(" / ")
                    .append(display(group.precision5().value()));
            report.append('|')
                    .append(display(group.ndcg3().value()))
                    .append(" / ")
                    .append(display(group.ndcg5().value()));
            report.append('|')
                    .append(e.scoredCases())
                    .append(" / ")
                    .append(e.totalCases())
                    .append('|')
                    .append(JSON.writeValueAsString(group.statuses()))
                    .append("|\n");
        }
        return report.append("\n异常状态、逐题 CCR 与分母详见 metrics.json。仅复算历史 Top-5 冻结池，不代表全库召回或答案质量。不包含人工语义指标重算。\n")
                .toString();
    }

    private static String display(Double value) {
        return value == null
                ? "UNDEFINED"
                : BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_EVEN).toPlainString();
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
