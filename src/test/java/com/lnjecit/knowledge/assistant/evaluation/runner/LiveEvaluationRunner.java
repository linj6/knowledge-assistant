package com.lnjecit.knowledge.assistant.evaluation.runner;

import com.lnjecit.knowledge.assistant.KnowledgeAssistantApplication;
import com.lnjecit.knowledge.assistant.evaluation.metrics.EvidenceMetrics;
import com.lnjecit.knowledge.assistant.evaluation.metrics.FrozenRankingEvaluation;
import com.lnjecit.knowledge.assistant.evaluation.metrics.RankingMetrics;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalService;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalResult;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStrategy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** C02 只读 LIVE 检索入口；费用授权在创建 Spring 上下文之前检查。 */
public final class LiveEvaluationRunner {
    static final JsonMapper JSON = JsonMapper.builder().build();

    private LiveEvaluationRunner() {}

    record Options(
            long knowledgeBaseId,
            String chunking,
            List<RetrievalStrategy> strategies,
            RetrievalParameters parameters,
            boolean authorizeEmbedding,
            Path output) {
        Options {
            strategies = List.copyOf(strategies);
            if (knowledgeBaseId <= 0
                    || !List.of("FIXED_CODE_POINT", "MARKDOWN_STRUCTURE_AWARE").contains(chunking)
                    || strategies.isEmpty()
                    || strategies.stream().distinct().count() != strategies.size()
                    || strategies.contains(RetrievalStrategy.HYBRID_RERANK))
                throw new IllegalArgumentException("INPUT_ERROR: 非法 LIVE 配置");
            for (RetrievalStrategy strategy : strategies) parameters.validate(strategy);
            if (!authorizeEmbedding && strategies.stream().anyMatch(s -> s != RetrievalStrategy.BM25))
                throw new IllegalArgumentException(
                        "AUTHORIZATION_REQUIRED: VECTOR/HYBRID_RRF 需要 --authorize-embedding-cost");
            Path root = Path.of("target/evaluation").toAbsolutePath().normalize();
            Path target = output.toAbsolutePath().normalize();
            if (!target.getParent().equals(root)
                    || !target.getFileName().toString().startsWith("m4-c02-live-"))
                throw new IllegalArgumentException("OUTPUT_ERROR: 输出须为 target/evaluation/m4-c02-live-* 独立目录");
        }
    }

    static Options parse(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        boolean authorized = false;
        for (String arg : args) {
            if (arg.equals("--authorize-embedding-cost") && !authorized) {
                authorized = true;
                continue;
            }
            String[] pair = arg.split("=", 2);
            if (pair.length != 2
                    || !List.of("--knowledge-base-id", "--chunking", "--strategies", "--parameters", "--output")
                            .contains(pair[0])
                    || values.putIfAbsent(pair[0], pair[1]) != null)
                throw new IllegalArgumentException("INPUT_ERROR: 未知或重复参数");
        }
        if (!values.containsKey("--knowledge-base-id") || !values.containsKey("--chunking"))
            throw new IllegalArgumentException("INPUT_ERROR: 必须指定 --knowledge-base-id 和 --chunking");
        List<RetrievalStrategy> strategies = java.util.Arrays.stream(
                        values.getOrDefault("--strategies", "BM25").split(","))
                .map(RetrievalStrategy::valueOf)
                .toList();
        String[] p = values.getOrDefault("--parameters", "20,20,60,20,5").split(",");
        if (p.length != 5)
            throw new IllegalArgumentException(
                    "INPUT_ERROR: parameters 为 vectorTopK,bm25TopK,rrfK,fusionTopK,finalTopK");
        return new Options(
                Long.parseLong(values.get("--knowledge-base-id")),
                values.get("--chunking"),
                strategies,
                new RetrievalParameters(
                        Integer.parseInt(p[0]),
                        Integer.parseInt(p[1]),
                        Integer.parseInt(p[2]),
                        Integer.parseInt(p[3]),
                        Integer.parseInt(p[4])),
                authorized,
                Path.of(values.getOrDefault("--output", "target/evaluation/m4-c02-live-v1")));
    }

    public static void main(String[] args) throws Exception {
        Options options = parse(args);
        if (Files.exists(options.output())) throw new IllegalArgumentException("OUTPUT_ERROR: 目录已存在");
        FrozenRankingEvaluation.Frozen frozen = load();
        SpringApplication app = new SpringApplication(KnowledgeAssistantApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        // 使用标准 application 配置加载；命令行最高优先级关闭写入和无关模型路径。
        try (ConfigurableApplicationContext context = app.run(
                "--spring.main.web-application-type=none",
                "--spring.flyway.enabled=false",
                "--spring.sql.init.mode=never",
                "--spring.datasource.hikari.read-only=true",
                "--assistant.worker.enabled=false",
                "--assistant.rerank.enabled=false",
                "--assistant.model.enabled=" + options.authorizeEmbedding(),
                "--spring.main.lazy-initialization=true")) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            Map<Long, String> sources = new LinkedHashMap<>();
            jdbc.query(
                    "SELECT id, content_hash FROM document WHERE knowledge_base_id = ? AND active_version_id IS NOT NULL",
                    (org.springframework.jdbc.core.RowCallbackHandler)
                            row -> sources.put(row.getLong("id"), row.getString("content_hash")),
                    options.knowledgeBaseId());
            execute(options, frozen, context.getBean(RetrievalService.class), sources);
        }
    }

    static FrozenRankingEvaluation.Frozen load() throws Exception {
        FrozenRankingEvaluation.Frozen frozen = FrozenRankingEvaluation.freeze(
                JSON.readTree(Files.readString(Path.of("src/test/resources/retrieval-metrics-v1.json"))),
                JSON.readTree(Files.readString(Path.of("docs/engineering/m4-b03-review-materials.json"))),
                JSON.readTree(Files.readString(Path.of("docs/engineering/m4-b03-relevance-judgments.json"))));
        if (!frozen.manifest()
                .path("fingerprint")
                .asString()
                .equals("4100bec441b6dde69c893246af86313c95e6aa28e8cff789cf4aff6b724e388c"))
            throw new IllegalArgumentException("INPUT_ERROR: 非冻结 v1 输入");
        JsonNode questions = JSON.readTree(Files.readString(Path.of("docs/engineering/m4-01-gold-evidence.json")));
        for (JsonNode run : frozen.fixture().path("runs")) {
            for (JsonNode question : questions)
                if (question.path("questionId").equals(run.path("questionId"))) {
                    String query = question.path("question").asString();
                    JsonNode gold = null;
                    for (JsonNode candidate : frozen.fixture().path("gold").path("questions"))
                        if (candidate.path("questionId").equals(run.path("questionId"))) gold = candidate;
                    if (gold == null
                            || !gold.path("querySha256")
                                    .asString()
                                    .equals(hash(query.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                        throw new IllegalArgumentException("INPUT_ERROR: 问题原文 Hash 不一致");
                    ((ObjectNode) run).put("query", query);
                }
            if (run.path("query").asString().isBlank()) throw new IllegalArgumentException("INPUT_ERROR: 缺少问题原文");
        }
        return frozen;
    }

    static void execute(
            Options options,
            FrozenRankingEvaluation.Frozen frozen,
            RetrievalService service,
            Map<Long, String> sourceHashes)
            throws Exception {
        if (Files.exists(options.output())) throw new IllegalArgumentException("OUTPUT_ERROR: 目录已存在");
        ObjectNode metrics = JSON.createObjectNode();
        ArrayNode scores = metrics.putArray("runs");
        ArrayNode results = JSON.createArrayNode();
        Map<String, JsonNode> questions = new LinkedHashMap<>();
        for (JsonNode run : frozen.fixture().path("runs"))
            if (run.path("chunking").asString().equals(options.chunking()))
                questions.putIfAbsent(run.path("questionId").asString(), run);
        // 每题一次 search，三策略共享服务内部的 Embedding，不重试或补跑失败通道。
        for (JsonNode question : questions.values()) {
            String query = question.path("query").asString();
            List<RetrievalResult> found;
            try {
                found = service.search(options.knowledgeBaseId(), query, options.strategies(), options.parameters());
            } catch (RuntimeException failure) {
                for (RetrievalStrategy strategy : options.strategies()) {
                    ObjectNode run = base(question, options, strategy);
                    run.put("status", "FAILED");
                    run.put("error", "SEARCH_EXCEPTION");
                    run.putNull("actualStrategy");
                    run.putArray("chunks");
                    results.add(run);
                    scores.add(unavailable(run, "EXECUTION_FAILED", "EXECUTION_FAILED"));
                }
                continue;
            }
            if (found.size() != options.strategies().size())
                throw new IllegalStateException("RETRIEVAL_PROTOCOL_ERROR: 结果数量不一致");
            for (int i = 0; i < found.size(); i++) {
                RetrievalResult result = found.get(i);
                RetrievalStrategy strategy = options.strategies().get(i);
                if (result.diagnostics().requestedStrategy() != strategy)
                    throw new IllegalStateException("RETRIEVAL_PROTOCOL_ERROR: 策略不一致");
                ObjectNode run = base(question, options, strategy);
                run.set("diagnostics", JSON.valueToTree(result.diagnostics()));
                run.put("status", result.diagnostics().status().name());
                run.set("actualStrategy", JSON.valueToTree(result.diagnostics().actualStrategy()));
                run.set("error", JSON.valueToTree(result.diagnostics().error()));
                run.put("snapshotHash", result.diagnostics().snapshotHash());
                run.set("chunks", JSON.valueToTree(result.chunks()));
                results.add(run);
                scores.add(score(frozen, run, sourceHashes));
            }
        }
        ObjectNode summaries = metrics.putObject("summaries");
        for (RetrievalStrategy strategy : options.strategies()) {
            ObjectNode summary = summaries.putObject(strategy.name());
            for (int k : List.of(3, 5))
                for (String metric : List.of("recall", "cec", "hit", "mrr", "precision", "ndcg")) {
                    List<Double> values = new ArrayList<>();
                    String type = List.of("precision", "ndcg").contains(metric) ? "ranking" : "evidence";
                    for (JsonNode score : scores)
                        if (score.path("strategy").asString().equals(strategy.name())) {
                            JsonNode value = score.path(type + k).path(metric);
                            values.add(value.isNumber() ? value.asDouble() : null);
                        }
                    FrozenRankingEvaluation.Mean mean = FrozenRankingEvaluation.mean(values);
                    ObjectNode value = summary.putObject(metric + k);
                    value.put("numerator", mean.numerator());
                    value.put("denominator", mean.denominator());
                    if (mean.value() == null) value.putNull("value");
                    else value.put("value", mean.value());
                }
        }
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("schemaVersion", 1);
        manifest.put("runnerVersion", "m4-c02-live-v1");
        manifest.put("mode", "LIVE");
        manifest.put("dataset", "retrieval-metrics-v1");
        manifest.set("configuration", JSON.valueToTree(options));
        manifest.set("frozenInputs", frozen.manifest());
        manifest.put("questionSource", "docs/engineering/m4-01-gold-evidence.json");
        manifest.put(
                "questionSourceSha256", hash(Files.readAllBytes(Path.of("docs/engineering/m4-01-gold-evidence.json"))));
        manifest.set("sourceContentHashes", JSON.valueToTree(sourceHashes));
        ObjectNode statuses = manifest.putObject("retrievalStatuses");
        for (JsonNode run : results) {
            String status = run.path("status").asString();
            statuses.put(status, statuses.path(status).asInt() + 1);
        }
        manifest.put(
                "status",
                statuses.path("FAILED").asInt() == results.size()
                        ? "FAILED"
                        : statuses.path("FAILED").asInt() > 0 ? "PARTIAL_FAILURE" : "COMPLETED");
        manifest.set(
                "snapshots",
                JSON.valueToTree(java.util.stream.StreamSupport.stream(results.spliterator(), false)
                        .map(r -> r.path("snapshotHash").asString())
                        .distinct()
                        .toList()));
        ObjectNode metricStatuses = manifest.putObject("metricStatuses");
        for (JsonNode score : scores)
            for (String type : List.of("evidenceStatus", "rankingStatus")) {
                String key = type + ":" + score.path(type).asString();
                metricStatuses.put(key, metricStatuses.path(key).asInt() + 1);
            }
        StringBuilder report = new StringBuilder("# C02 LIVE Retrieval Evaluation\n\n执行状态："
                + manifest.path("status").asString()
                + "\n\n来源：retrieval-metrics-v1 / B03 确认池。Snapshot 仅为版本身份，评分另核验来源 Hash、Chunk 字节及区间。\n\n检索状态：" + statuses
                + "\n\n指标状态：" + metricStatuses + "\n\n|Question / Strategy|Evidence|Ranking|\n|---|---|---|\n");
        for (JsonNode score : scores)
            report.append('|')
                    .append(score.path("questionId").asString())
                    .append(" / ")
                    .append(score.path("strategy").asString())
                    .append('|')
                    .append(score.path("evidenceStatus").asString())
                    .append('|')
                    .append(score.path("rankingStatus").asString())
                    .append("|\n");
        report.append("\n|Strategy|Recall@5|CEC@5|MRR@5|Precision@5|nDCG@5|\n|---|---|---|---|---|---|\n");
        for (RetrievalStrategy strategy : options.strategies()) {
            report.append('|').append(strategy.name());
            for (String metric : List.of("recall5", "cec5", "mrr5", "precision5", "ndcg5")) {
                JsonNode summary = summaries.path(strategy.name()).path(metric);
                JsonNode value = summary.path("value");
                report.append('|')
                        .append(
                                value.isNumber()
                                        ? java.math.BigDecimal.valueOf(value.asDouble())
                                                .setScale(3, java.math.RoundingMode.HALF_EVEN)
                                                .toPlainString()
                                        : "UNDEFINED")
                        .append(" (n=")
                        .append(summary.path("denominator").asInt())
                        .append(')');
            }
            report.append("|\n");
        }
        report.append("\n完整数值、分母、逐题 @3/@5 见 metrics.json。不可评分不记零，不代表质量通过。未执行 Chat/Rerank 或答案评估。\n");
        publish(options.output(), manifest, results, metrics, report.toString());
        System.out.println(
                "LIVE " + manifest.path("status").asString() + ": " + results.size() + " runs; " + options.output());
    }

    private static ObjectNode base(JsonNode question, Options options, RetrievalStrategy strategy) {
        ObjectNode run = JSON.createObjectNode();
        run.set("questionId", question.path("questionId"));
        run.set("query", question.path("query"));
        run.put("knowledgeBaseId", options.knowledgeBaseId());
        run.put("chunking", options.chunking());
        run.put("strategy", strategy.name());
        run.put("requestedStrategy", strategy.name());
        run.set("parameters", JSON.valueToTree(options.parameters()));
        return run;
    }

    static ObjectNode score(FrozenRankingEvaluation.Frozen frozen, ObjectNode run, Map<Long, String> sourceHashes) {
        if (run.path("status").asString().equals("FAILED"))
            return unavailable(run, "EXECUTION_FAILED", "EXECUTION_FAILED");
        JsonNode reference = null, gold = null;
        for (JsonNode candidate : frozen.fixture().path("runs"))
            if (candidate.path("questionId").equals(run.path("questionId"))
                    && candidate.path("chunking").equals(run.path("chunking"))) {
                reference = candidate;
                break;
            }
        for (JsonNode candidate : frozen.fixture().path("gold").path("questions"))
            if (candidate.path("questionId").equals(run.path("questionId"))) gold = candidate;
        if (reference == null || !reference.path("snapshotHash").equals(run.path("snapshotHash")))
            return unavailable(run, "GOLD_SNAPSHOT_MISMATCH", "INVALID_POOL");
        List<EvidenceMetrics.Source> sources = new ArrayList<>();
        Map<String, JsonNode> known = new LinkedHashMap<>();
        boolean sourceMatch = true;
        for (JsonNode pool : frozen.manifest().path("pools"))
            if (pool.path("questionId").equals(run.path("questionId"))
                    && pool.path("chunking").equals(run.path("chunking")))
                for (JsonNode label : pool.path("judgments")) {
                    sourceMatch &= label.path("sourceSha256")
                            .asString()
                            .equals(sourceHashes.get(label.path("documentId").asLong()));
                    sources.add(new EvidenceMetrics.Source(
                            label.path("documentId").asLong(),
                            label.path("indexVersionId").asLong(),
                            label.path("documentPath").asString(),
                            label.path("sourceSha256").asString()));
                }
        for (JsonNode candidate : frozen.fixture().path("runs"))
            if (candidate.path("questionId").equals(run.path("questionId"))
                    && candidate.path("chunking").equals(run.path("chunking")))
                for (JsonNode chunk : candidate.path("chunks"))
                    known.put(chunk.path("chunkId").asString(), chunk);
        if (!sourceMatch) return unavailable(run, "GOLD_SOURCE_MISMATCH", "INVALID_POOL");
        String rankingStatus = "SCORED";
        for (JsonNode chunk : run.path("chunks")) {
            JsonNode prior = known.get(chunk.path("chunkId").asString());
            if (prior == null) {
                rankingStatus = "PENDING_REVIEW";
                if (!verifiedSourceSpan(chunk, sources))
                    return unavailable(run, "GOLD_CHUNK_UNVERIFIED", rankingStatus);
                continue;
            }
            for (String field :
                    List.of("documentId", "indexVersionId", "knowledgeBaseId", "content", "sourceStart", "sourceEnd"))
                if (!prior.path(field).equals(chunk.path(field)))
                    return unavailable(run, "GOLD_CHUNK_MISMATCH", "INVALID_POOL");
        }
        if (gold == null
                || !gold.path("querySha256")
                        .asString()
                        .equals(hash(run.path("query").asString().getBytes(java.nio.charset.StandardCharsets.UTF_8))))
            return unavailable(run, "GOLD_QUERY_MISMATCH", "INVALID_POOL");
        ObjectNode adapted = run.deepCopy();
        // Evidence/Ranking 的 SUCCESS 包含空结果；原始 LIVE EMPTY_RESULT 保留在运行产物。
        adapted.put("status", "SUCCESS");
        ObjectNode result = unavailable(run, "SCORED", rankingStatus);
        try {
            for (int k : List.of(3, 5)) {
                EvidenceMetrics.Result evidence = EvidenceMetrics.calculate(gold, adapted, sources, k);
                ObjectNode values = JSON.valueToTree(evidence);
                values.put("recall", evidence.recall());
                values.put("cec", evidence.cec());
                values.put("hit", evidence.hit());
                values.put("mrr", evidence.mrr());
                result.set("evidence" + k, values);
                if (rankingStatus.equals("SCORED"))
                    result.set("ranking" + k, JSON.valueToTree(RankingMetrics.calculate(frozen.fixture(), adapted, k)));
            }
        } catch (RuntimeException failure) {
            return unavailable(run, "METRIC_ERROR", "INVALID_POOL");
        }
        return result;
    }

    // 新 Chunk 不能借用旧等级；正文确实是已核验来源区间时，Evidence 仍可独立评分。
    private static boolean verifiedSourceSpan(JsonNode chunk, List<EvidenceMetrics.Source> sources) {
        try {
            for (EvidenceMetrics.Source source : sources) {
                if (source.documentId() != chunk.path("documentId").asLong()
                        || source.versionId() != chunk.path("indexVersionId").asLong()) continue;
                byte[] bytes = Files.readAllBytes(Path.of(source.path()));
                if (!hash(bytes).equals(source.sha256())) return false;
                String text = new com.lnjecit.knowledge.assistant.ingestion.parser.DocumentParserFactory()
                        .parse(source.path(), bytes)
                        .source();
                int start = chunk.path("sourceStart").asInt(),
                        end = chunk.path("sourceEnd").asInt();
                return start >= 0
                        && end > start
                        && end <= text.codePointCount(0, text.length())
                        && text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end))
                                .equals(chunk.path("content").asString());
            }
        } catch (Exception unavailable) {
            return false;
        }
        return false;
    }

    private static ObjectNode unavailable(JsonNode run, String evidence, String ranking) {
        ObjectNode score = JSON.createObjectNode();
        score.set("questionId", run.path("questionId"));
        score.set("strategy", run.path("strategy"));
        score.put("evidenceStatus", evidence);
        score.put("rankingStatus", ranking);
        score.put("evidenceDenominator", evidence.equals("SCORED") ? 1 : 0);
        score.put("rankingDenominator", ranking.equals("SCORED") ? 1 : 0);
        return score;
    }

    private static void publish(Path output, ObjectNode manifest, ArrayNode runs, ObjectNode metrics, String report)
            throws Exception {
        Path staging = null;
        try {
            Files.createDirectories(output.toAbsolutePath().getParent());
            staging = Files.createTempDirectory(output.toAbsolutePath().getParent(), ".live-");
            StringBuilder lines = new StringBuilder();
            for (JsonNode run : runs) lines.append(run).append('\n');
            Files.writeString(staging.resolve("retrieval-results.jsonl"), lines.toString());
            Files.writeString(
                    staging.resolve("metrics.json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(metrics) + "\n");
            Files.writeString(staging.resolve("report.md"), report);
            ObjectNode hashes = manifest.putObject("artifactSha256");
            for (String file : List.of("retrieval-results.jsonl", "metrics.json", "report.md"))
                hashes.put(file, hash(Files.readAllBytes(staging.resolve(file))));
            Files.writeString(
                    staging.resolve("manifest.json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n");
            Files.move(staging, output);
            staging = null;
        } catch (Exception failure) {
            throw new java.io.IOException("OUTPUT_ERROR: LIVE 产物发布失败", failure);
        } finally {
            if (staging != null) {
                try (java.util.stream.Stream<Path> files = Files.list(staging)) {
                    for (Path file : files.toList()) Files.deleteIfExists(file);
                }
                Files.deleteIfExists(staging);
            }
        }
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
