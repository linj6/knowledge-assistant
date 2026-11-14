package com.lnjecit.knowledge.assistant.evaluation.runner;

import com.lnjecit.knowledge.assistant.evaluation.metrics.EvidenceMetrics;
import com.lnjecit.knowledge.assistant.evaluation.metrics.FrozenRankingEvaluation;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 仅复算 C01 固定数据集，不启动 Spring。 */
public final class OfflineEvaluationRunner {
    private static final JsonMapper JSON = JsonMapper.builder().build();
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
        List<JsonNode> inputs = new ArrayList<>();
        ObjectNode hashes = JSON.createObjectNode();
        try {
            for (String input : INPUTS) {
                byte[] bytes = Files.readAllBytes(Path.of(input));
                inputs.add(JSON.readTree(bytes));
                hashes.put(input, hash(bytes));
            }
        } catch (Exception e) {
            throw new IOException("INPUT_ERROR: 无法读取固定输入", e);
        }
        FrozenRankingEvaluation.Frozen frozen;
        try {
            frozen = FrozenRankingEvaluation.freeze(inputs.get(0), inputs.get(1), inputs.get(2));
            // C01 仅接受已确认的 v1 输入，不能悄悄换用另一版 Gold 或标签。
            if (!frozen.manifest()
                    .path("fingerprint")
                    .asString()
                    .equals("4100bec441b6dde69c893246af86313c95e6aa28e8cff789cf4aff6b724e388c"))
                throw new IllegalArgumentException("冻结指纹不是已确认的 retrieval-metrics-v1");
        } catch (IOException e) {
            throw new IOException("INPUT_ERROR: 无法读取冻结来源原文字节", e);
        } catch (Exception e) {
            throw new IllegalArgumentException("FREEZE_ERROR: 身份、Hash 或确认状态不一致", e);
        }
        ObjectNode metrics;
        String report;
        try {
            metrics = FrozenRankingEvaluation.evaluate(frozen);
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
            for (JsonNode run : inputs.get(0).path("runs")) lines.append(run).append('\n');
            Files.writeString(staging.resolve("retrieval-results.jsonl"), lines.toString());
            Files.writeString(staging.resolve("report.md"), report);
            ObjectNode manifest = JSON.createObjectNode();
            manifest.put("schemaVersion", 1);
            manifest.put("runnerVersion", "m4-c01-offline-v1");
            manifest.put("dataset", "retrieval-metrics-v1");
            manifest.put("mode", "OFFLINE");
            manifest.put("status", "SUCCESS");
            manifest.set("inputSha256", hashes);
            manifest.set("frozenPool", frozen.manifest());
            ObjectNode artifacts = manifest.putObject("artifactSha256");
            // manifest 不自引用，其余三个文件按最终 UTF-8 字节计算 Hash。
            for (String name : ARTIFACTS) artifacts.put(name, hash(Files.readAllBytes(staging.resolve(name))));
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
                + frozen.manifest().path("fingerprint").asString());
    }

    private static String summarize(FrozenRankingEvaluation.Frozen frozen, ObjectNode metrics) {
        StringBuilder report = new StringBuilder(
                "# C01 OFFLINE Evaluation\n\n来源：retrieval-metrics-v1；B03 confirmed judgments / review materials。\n\n冻结指纹：`"
                        + frozen.manifest().path("fingerprint").asString()
                        + "`\n\n|Chunking / Strategy|Recall@3 / @5|CEC@3 / @5|Hit@3 / @5|MRR@5|P@3 / @5|nDCG@3 / @5|Evidence scored / total|Ranking statuses|\n|---|---|---|---|---|---|---|---|---|\n");
        for (JsonNode node : metrics.path("groups")) {
            ObjectNode group = (ObjectNode) node;
            List<EvidenceMetrics.Case> cases = new ArrayList<>();
            for (JsonNode run : frozen.fixture().path("runs")) {
                if (!run.path("chunking").equals(group.path("chunking"))
                        || !run.path("strategy").equals(group.path("strategy"))) continue;
                List<EvidenceMetrics.Source> sources = new ArrayList<>();
                for (JsonNode pool : frozen.manifest().path("pools")) {
                    if (!pool.path("chunking").equals(run.path("chunking"))
                            || !pool.path("questionId").equals(run.path("questionId"))) continue;
                    for (JsonNode label : pool.path("judgments"))
                        sources.add(new EvidenceMetrics.Source(
                                label.path("documentId").asLong(),
                                label.path("indexVersionId").asLong(),
                                label.path("documentPath").asString(),
                                label.path("sourceSha256").asString()));
                }
                JsonNode gold = null;
                for (JsonNode question : frozen.fixture().path("gold").path("questions"))
                    if (question.path("questionId").equals(run.path("questionId"))) gold = question;
                cases.add(new EvidenceMetrics.Case(gold, run, sources));
            }
            EvidenceMetrics.Summary summary = EvidenceMetrics.summarize(cases);
            ObjectNode evidence = JSON.valueToTree(summary);
            for (String metric : List.of("recall3", "recall5", "cec3", "cec5", "hit3", "hit5", "mrr5")) {
                ObjectNode score = (ObjectNode) evidence.path(metric);
                int denominator = score.path("denominator").asInt();
                if (denominator == 0) score.putNull("value");
                else score.put("value", score.path("numerator").asDouble() / denominator);
            }
            group.set("evidence", evidence);
            report.append('|')
                    .append(group.path("chunking").asString())
                    .append(" / ")
                    .append(group.path("strategy").asString());
            for (String metric : List.of("recall", "cec", "hit"))
                report.append('|')
                        .append(display(evidence.path(metric + "3").path("value")))
                        .append(" / ")
                        .append(display(evidence.path(metric + "5").path("value")));
            report.append('|').append(display(evidence.path("mrr5").path("value")));
            for (String metric : List.of("precision", "ndcg"))
                report.append('|')
                        .append(display(group.path(metric + "3").path("value")))
                        .append(" / ")
                        .append(display(group.path(metric + "5").path("value")));
            report.append('|')
                    .append(summary.scoredCases())
                    .append(" / ")
                    .append(summary.totalCases())
                    .append('|')
                    .append(group.path("statuses"))
                    .append("|\n");
        }
        return report.append("\n异常状态、逐题 CCR 与分母详见 metrics.json。仅复算历史 Top-5 冻结池，不代表全库召回或答案质量。不包含人工语义指标重算。\n")
                .toString();
    }

    private static String display(JsonNode value) {
        return value.isNumber()
                ? BigDecimal.valueOf(value.asDouble())
                        .setScale(3, RoundingMode.HALF_EVEN)
                        .toPlainString()
                : "UNDEFINED";
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
