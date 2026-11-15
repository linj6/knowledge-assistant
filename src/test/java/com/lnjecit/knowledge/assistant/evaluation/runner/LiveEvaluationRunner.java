package com.lnjecit.knowledge.assistant.evaluation.runner;

import com.lnjecit.knowledge.assistant.KnowledgeAssistantApplication;
import com.lnjecit.knowledge.assistant.evaluation.metrics.EvidenceMetrics;
import com.lnjecit.knowledge.assistant.evaluation.metrics.FrozenRankingEvaluation;
import com.lnjecit.knowledge.assistant.evaluation.metrics.RankingMetrics;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.FrozenPool;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.GoldQuestion;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.GoldQuestionIdentity;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.LiveConfiguration;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.LiveMetricsReportSummariesValue;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.LiveMetricsReportSummariesValueRecall3;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.LiveRetrievalRunResult;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.LiveRetrievalRunResultDiagnostics;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.OfflineRetrievalRunResult;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.RelevanceJudgment;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.RetrievalRunManifest;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalService;
import com.lnjecit.knowledge.assistant.retrieval.model.RankedChunk;
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
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** C02 只读 LIVE 检索入口；费用授权在创建 Spring 上下文之前检查。 */
public final class LiveEvaluationRunner {
    static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .addMixIn(LiveRetrievalRunResult.class, RunJson.class)
            .build();

    // 异常运行没有 diagnostics/snapshot 字段，保留历史协议中的缺失与显式 null 差别。
    abstract static class RunJson {
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        abstract LiveRetrievalRunResultDiagnostics diagnostics();

        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        abstract String snapshotHash();
    }

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
        if (!frozen.manifest().fingerprint().equals("4100bec441b6dde69c893246af86313c95e6aa28e8cff789cf4aff6b724e388c"))
            throw new IllegalArgumentException("INPUT_ERROR: 非冻结 v1 输入");
        questions(frozen);
        return frozen;
    }

    static Map<String, String> questions(FrozenRankingEvaluation.Frozen frozen) throws Exception {
        List<GoldQuestion> questions = JSON.readValue(
                Files.readString(Path.of("docs/engineering/m4-01-gold-evidence.json")),
                JSON.getTypeFactory().constructCollectionType(List.class, GoldQuestion.class));
        Map<String, String> queries = new LinkedHashMap<>();
        for (OfflineRetrievalRunResult run : frozen.fixture().runs()) {
            for (GoldQuestion question : questions) {
                if (!question.questionId().equals(run.questionId())) continue;
                GoldQuestionIdentity gold = frozen.fixture().gold().questions().stream()
                        .filter(q -> q.questionId().equals(run.questionId()))
                        .findFirst()
                        .orElse(null);
                if (gold == null
                        || !gold.querySha256()
                                .equals(hash(question.question().getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                    throw new IllegalArgumentException("INPUT_ERROR: 问题原文 Hash 不一致");
                queries.put(run.questionId(), question.question());
            }
            if (!queries.containsKey(run.questionId())
                    || queries.get(run.questionId()).isBlank())
                throw new IllegalArgumentException("INPUT_ERROR: 缺少问题原文");
        }
        return queries;
    }

    record Metrics(List<Score> runs, Map<String, LiveMetricsReportSummariesValue> summaries) {}

    private static LiveMetricsReportSummariesValueRecall3 mean(
            List<Score> scores, String strategy, int k, String metric) {
        List<Double> values = new ArrayList<>();
        for (Score score : scores) {
            if (!score.strategy().equals(strategy)) continue;
            EvidenceValues e = k == 3 ? score.evidence3() : score.evidence5();
            RankingMetrics.Result r = k == 3 ? score.ranking3() : score.ranking5();
            Double value =
                    switch (metric) {
                        case "recall" -> e == null ? null : e.recall();
                        case "cec" -> e == null ? null : (double) e.cec();
                        case "hit" -> e == null ? null : (double) e.hit();
                        case "mrr" -> e == null ? null : e.mrr();
                        case "precision" -> r == null ? null : r.precision();
                        case "ndcg" -> r == null ? null : r.ndcg();
                        default -> throw new IllegalArgumentException("未知指标");
                    };
            values.add(value);
        }
        FrozenRankingEvaluation.Mean mean = FrozenRankingEvaluation.mean(values);
        return new LiveMetricsReportSummariesValueRecall3(mean.numerator(), (long) mean.denominator(), mean.value());
    }

    static void execute(
            Options options,
            FrozenRankingEvaluation.Frozen frozen,
            RetrievalService service,
            Map<Long, String> sourceHashes)
            throws Exception {
        if (Files.exists(options.output())) throw new IllegalArgumentException("OUTPUT_ERROR: 目录已存在");
        Map<String, String> queries = questions(frozen);
        Map<String, OfflineRetrievalRunResult> questions = new LinkedHashMap<>();
        for (OfflineRetrievalRunResult run : frozen.fixture().runs())
            if (run.chunking().equals(options.chunking())) questions.putIfAbsent(run.questionId(), run);
        List<LiveRetrievalRunResult> results = new ArrayList<>();
        List<Score> scores = new ArrayList<>();
        // 每题一次 search，多策略共享服务内部 Embedding，不重试失败通道。
        for (OfflineRetrievalRunResult question : questions.values()) {
            String query = queries.get(question.questionId());
            List<RetrievalResult> found;
            try {
                found = service.search(options.knowledgeBaseId(), query, options.strategies(), options.parameters());
            } catch (RuntimeException failure) {
                for (RetrievalStrategy strategy : options.strategies()) {
                    LiveRetrievalRunResult run = new LiveRetrievalRunResult(
                            question.questionId(),
                            query,
                            options.knowledgeBaseId(),
                            options.chunking(),
                            strategy.name(),
                            strategy.name(),
                            options.parameters(),
                            null,
                            "FAILED",
                            null,
                            "SEARCH_EXCEPTION",
                            null,
                            List.of());
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
                LiveRetrievalRunResultDiagnostics diagnostics = JSON.treeToValue(
                        JSON.valueToTree(result.diagnostics()), LiveRetrievalRunResultDiagnostics.class);
                LiveRetrievalRunResult run = new LiveRetrievalRunResult(
                        question.questionId(),
                        query,
                        options.knowledgeBaseId(),
                        options.chunking(),
                        strategy.name(),
                        strategy.name(),
                        options.parameters(),
                        diagnostics,
                        diagnostics.status(),
                        diagnostics.actualStrategy(),
                        diagnostics.error(),
                        diagnostics.snapshotHash(),
                        result.chunks());
                results.add(run);
                scores.add(scoreTyped(frozen, run, sourceHashes));
            }
        }
        Map<String, LiveMetricsReportSummariesValue> summaries = new LinkedHashMap<>();
        for (RetrievalStrategy strategy : options.strategies()) {
            String name = strategy.name();
            summaries.put(
                    name,
                    new LiveMetricsReportSummariesValue(
                            mean(scores, name, 3, "recall"),
                            mean(scores, name, 3, "cec"),
                            mean(scores, name, 3, "hit"),
                            mean(scores, name, 3, "mrr"),
                            mean(scores, name, 3, "precision"),
                            mean(scores, name, 3, "ndcg"),
                            mean(scores, name, 5, "recall"),
                            mean(scores, name, 5, "cec"),
                            mean(scores, name, 5, "hit"),
                            mean(scores, name, 5, "mrr"),
                            mean(scores, name, 5, "precision"),
                            mean(scores, name, 5, "ndcg")));
        }
        Metrics metrics = new Metrics(scores, summaries);
        Map<String, Long> statuses = new LinkedHashMap<>(), metricStatuses = new LinkedHashMap<>();
        for (LiveRetrievalRunResult run : results) statuses.merge(run.status(), 1L, Long::sum);
        for (Score score : scores) {
            metricStatuses.merge("evidenceStatus:" + score.evidenceStatus(), 1L, Long::sum);
            metricStatuses.merge("rankingStatus:" + score.rankingStatus(), 1L, Long::sum);
        }
        long failures = statuses.getOrDefault("FAILED", 0L);
        String status = failures == results.size() ? "FAILED" : failures > 0 ? "PARTIAL_FAILURE" : "COMPLETED";
        Map<String, String> hashes = new LinkedHashMap<>();
        sourceHashes.forEach((id, value) -> hashes.put(id.toString(), value));
        RetrievalRunManifest manifest = new RetrievalRunManifest(
                1L,
                "m4-c02-live-v1",
                "retrieval-metrics-v1",
                "LIVE",
                status,
                null,
                null,
                new LinkedHashMap<>(),
                new LiveConfiguration(
                        options.knowledgeBaseId(),
                        options.chunking(),
                        options.strategies().stream().map(Enum::name).toList(),
                        options.parameters(),
                        options.authorizeEmbedding(),
                        options.output().toUri().toString()),
                frozen.manifest(),
                "docs/engineering/m4-01-gold-evidence.json",
                hash(Files.readAllBytes(Path.of("docs/engineering/m4-01-gold-evidence.json"))),
                hashes,
                statuses,
                results.stream()
                        .map(r -> r.snapshotHash() == null ? "" : r.snapshotHash())
                        .distinct()
                        .toList(),
                metricStatuses);
        StringBuilder report = new StringBuilder("# C02 LIVE Retrieval Evaluation\n\n执行状态：" + status
                + "\n\n来源：retrieval-metrics-v1 / B03 确认池。Snapshot 仅为版本身份，评分另核验来源 Hash、Chunk 字节及区间。\n\n检索状态："
                + JSON.writeValueAsString(statuses) + "\n\n指标状态：" + JSON.writeValueAsString(metricStatuses)
                + "\n\n|Question / Strategy|Evidence|Ranking|\n|---|---|---|\n");
        for (Score score : scores)
            report.append('|')
                    .append(score.questionId())
                    .append(" / ")
                    .append(score.strategy())
                    .append('|')
                    .append(score.evidenceStatus())
                    .append('|')
                    .append(score.rankingStatus())
                    .append("|\n");
        report.append("\n|Strategy|Recall@5|CEC@5|MRR@5|Precision@5|nDCG@5|\n|---|---|---|---|---|---|\n");
        for (RetrievalStrategy strategy : options.strategies()) {
            report.append('|').append(strategy.name());
            LiveMetricsReportSummariesValue summary = summaries.get(strategy.name());
            for (LiveMetricsReportSummariesValueRecall3 value :
                    List.of(summary.recall5(), summary.cec5(), summary.mrr5(), summary.precision5(), summary.ndcg5()))
                report.append('|')
                        .append(
                                value.value() == null
                                        ? "UNDEFINED"
                                        : java.math.BigDecimal.valueOf(value.value())
                                                .setScale(3, java.math.RoundingMode.HALF_EVEN)
                                                .toPlainString())
                        .append(" (n=")
                        .append(value.denominator())
                        .append(')');
            report.append("|\n");
        }
        report.append("\n完整数值、分母、逐题 @3/@5 见 metrics.json。不可评分不记零，不代表质量通过。未执行 Chat/Rerank 或答案评估。\n");
        publish(options.output(), manifest, results, metrics, report.toString());
        System.out.println("LIVE " + status + ": " + results.size() + " runs; " + options.output());
    }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    record Score(
            String questionId,
            String strategy,
            String evidenceStatus,
            String rankingStatus,
            int evidenceDenominator,
            int rankingDenominator,
            EvidenceValues evidence3,
            RankingMetrics.Result ranking3,
            EvidenceValues evidence5,
            RankingMetrics.Result ranking5) {}

    record EvidenceValues(
            String executionStatus,
            String qualityStatus,
            int depth,
            int covered,
            int evidenceCount,
            Integer firstRank,
            Integer completeRank,
            double recall,
            int cec,
            int hit,
            double mrr) {
        static EvidenceValues of(EvidenceMetrics.Result e) {
            return new EvidenceValues(
                    e.executionStatus(),
                    e.qualityStatus(),
                    e.depth(),
                    e.covered(),
                    e.evidenceCount(),
                    e.firstRank(),
                    e.completeRank(),
                    e.recall(),
                    e.cec(),
                    e.hit(),
                    e.mrr());
        }
    }

    static ObjectNode score(FrozenRankingEvaluation.Frozen frozen, ObjectNode run, Map<Long, String> sources) {
        return (ObjectNode) JSON.readTree(JSON.writeValueAsString(
                scoreTyped(frozen, JSON.treeToValue(run, LiveRetrievalRunResult.class), sources)));
    }

    private static Score unavailable(LiveRetrievalRunResult run, String evidence, String ranking) {
        return new Score(
                run.questionId(),
                run.strategy(),
                evidence,
                ranking,
                evidence.equals("SCORED") ? 1 : 0,
                ranking.equals("SCORED") ? 1 : 0,
                null,
                null,
                null,
                null);
    }

    static Score scoreTyped(
            FrozenRankingEvaluation.Frozen frozen, LiveRetrievalRunResult run, Map<Long, String> sourceHashes) {
        if ("FAILED".equals(run.status())) return unavailable(run, "EXECUTION_FAILED", "EXECUTION_FAILED");
        OfflineRetrievalRunResult reference = frozen.fixture().runs().stream()
                .filter(c ->
                        c.questionId().equals(run.questionId()) && c.chunking().equals(run.chunking()))
                .findFirst()
                .orElse(null);
        GoldQuestionIdentity gold = frozen.fixture().gold().questions().stream()
                .filter(c -> c.questionId().equals(run.questionId()))
                .findFirst()
                .orElse(null);
        if (reference == null || !java.util.Objects.equals(reference.snapshotHash(), run.snapshotHash()))
            return unavailable(run, "GOLD_SNAPSHOT_MISMATCH", "INVALID_POOL");
        List<EvidenceMetrics.Source> sources = new ArrayList<>();
        Map<Long, RankedChunk> known = new LinkedHashMap<>();
        boolean sourceMatch = true;
        for (FrozenPool pool : frozen.manifest().pools())
            if (pool.questionId().equals(run.questionId()) && pool.chunking().equals(run.chunking()))
                for (RelevanceJudgment label : pool.judgments()) {
                    sourceMatch &= label.sourceSha256().equals(sourceHashes.get(label.documentId()));
                    sources.add(new EvidenceMetrics.Source(
                            label.documentId(), label.indexVersionId(), label.documentPath(), label.sourceSha256()));
                }
        for (OfflineRetrievalRunResult candidate : frozen.fixture().runs())
            if (candidate.questionId().equals(run.questionId())
                    && candidate.chunking().equals(run.chunking()))
                for (RankedChunk chunk : candidate.chunks()) known.put(chunk.chunkId(), chunk);
        if (!sourceMatch) return unavailable(run, "GOLD_SOURCE_MISMATCH", "INVALID_POOL");
        String rankingStatus = "SCORED";
        for (RankedChunk chunk : run.chunks()) {
            RankedChunk prior = known.get(chunk.chunkId());
            if (prior == null) {
                rankingStatus = "PENDING_REVIEW";
                if (!verifiedSourceSpan(chunk, sources))
                    return unavailable(run, "GOLD_CHUNK_UNVERIFIED", rankingStatus);
                continue;
            }
            if (!prior.documentId().equals(chunk.documentId())
                    || !prior.indexVersionId().equals(chunk.indexVersionId())
                    || !prior.knowledgeBaseId().equals(chunk.knowledgeBaseId())
                    || !prior.content().equals(chunk.content())
                    || prior.sourceStart() != chunk.sourceStart()
                    || prior.sourceEnd() != chunk.sourceEnd())
                return unavailable(run, "GOLD_CHUNK_MISMATCH", "INVALID_POOL");
        }
        if (gold == null
                || !gold.querySha256()
                        .equals(hash((run.query() == null ? "" : run.query())
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))))
            return unavailable(run, "GOLD_QUERY_MISMATCH", "INVALID_POOL");
        // 空结果按成功计算零贡献，原始运行状态不修改。
        try {
            EvidenceMetrics.Gold g = new EvidenceMetrics.Gold(
                    null,
                    gold.reviewStatus(),
                    gold.relation(),
                    gold.documentPath(),
                    gold.sourceSha256(),
                    gold.evidence());
            EvidenceMetrics.Run e = new EvidenceMetrics.Run(
                    "SUCCESS",
                    run.chunks().stream()
                            .map(c -> new EvidenceMetrics.Candidate(
                                    c.documentId(), c.indexVersionId(), c.finalRank(), c.sourceStart(), c.sourceEnd()))
                            .toList());
            RankingMetrics.Fixture f = new RankingMetrics.Fixture(
                    frozen.fixture().pool(),
                    frozen.fixture().runs().stream()
                            .map(c -> new RankingMetrics.Run(
                                    c.questionId(),
                                    c.chunking(),
                                    c.snapshotHash(),
                                    c.status(),
                                    c.chunks().stream()
                                            .map(x -> new RankingMetrics.Candidate(
                                                    x.documentId(),
                                                    x.indexVersionId(),
                                                    x.chunkId(),
                                                    x.content(),
                                                    x.finalRank()))
                                            .toList()))
                            .toList());
            RankingMetrics.Run r = new RankingMetrics.Run(
                    run.questionId(),
                    run.chunking(),
                    run.snapshotHash(),
                    "SUCCESS",
                    run.chunks().stream()
                            .map(c -> new RankingMetrics.Candidate(
                                    c.documentId(), c.indexVersionId(), c.chunkId(), c.content(), c.finalRank()))
                            .toList());
            EvidenceValues three = EvidenceValues.of(EvidenceMetrics.calculate(g, e, sources, 3));
            RankingMetrics.Result ranking3 = rankingStatus.equals("SCORED") ? RankingMetrics.calculate(f, r, 3) : null;
            EvidenceValues five = EvidenceValues.of(EvidenceMetrics.calculate(g, e, sources, 5));
            RankingMetrics.Result ranking5 = rankingStatus.equals("SCORED") ? RankingMetrics.calculate(f, r, 5) : null;
            return new Score(
                    run.questionId(),
                    run.strategy(),
                    "SCORED",
                    rankingStatus,
                    1,
                    rankingStatus.equals("SCORED") ? 1 : 0,
                    three,
                    ranking3,
                    five,
                    ranking5);
        } catch (RuntimeException failure) {
            return unavailable(run, "METRIC_ERROR", "INVALID_POOL");
        }
    }

    // 新 Chunk 不能借用旧等级；正文确实是已核验来源区间时，Evidence 仍可独立评分。
    private static boolean verifiedSourceSpan(RankedChunk chunk, List<EvidenceMetrics.Source> sources) {
        try {
            for (EvidenceMetrics.Source source : sources) {
                if (source.documentId() != chunk.documentId() || source.versionId() != chunk.indexVersionId()) continue;
                byte[] bytes = Files.readAllBytes(Path.of(source.path()));
                if (!hash(bytes).equals(source.sha256())) return false;
                String text = new com.lnjecit.knowledge.assistant.ingestion.parser.DocumentParserFactory()
                        .parse(source.path(), bytes)
                        .source();
                int start = chunk.sourceStart(), end = chunk.sourceEnd();
                return start >= 0
                        && end > start
                        && end <= text.codePointCount(0, text.length())
                        && text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end))
                                .equals(chunk.content());
            }
        } catch (Exception unavailable) {
            return false;
        }
        return false;
    }

    private static void publish(
            Path output,
            RetrievalRunManifest manifest,
            List<LiveRetrievalRunResult> runs,
            Metrics metrics,
            String report)
            throws Exception {
        Path staging = null;
        try {
            Files.createDirectories(output.toAbsolutePath().getParent());
            staging = Files.createTempDirectory(output.toAbsolutePath().getParent(), ".live-");
            StringBuilder lines = new StringBuilder();
            for (LiveRetrievalRunResult run : runs)
                lines.append(JSON.writeValueAsString(run)).append('\n');
            Files.writeString(staging.resolve("retrieval-results.jsonl"), lines.toString());
            Files.writeString(
                    staging.resolve("metrics.json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(metrics) + "\n");
            Files.writeString(staging.resolve("report.md"), report);
            Map<String, String> hashes = manifest.artifactSha256();
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
