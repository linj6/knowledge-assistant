package com.lnjecit.knowledge.assistant.evaluation.metrics;

import com.lnjecit.knowledge.assistant.evaluation.EvaluationCorpus;
import com.lnjecit.knowledge.assistant.evaluation.dataset.FrozenFingerprint;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.FrozenPool;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.FrozenPools;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.GoldQuestionIdentity;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.OfflineRetrievalRunResult;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.RelevanceJudgment;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.RetrievalFixture;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.RetrievalFixturePool;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.RetrievalFixturePoolJudgmentsItem;
import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData.RetrievalFixtureSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 仅适配 M4 冻结实验；校验失败时拒绝评分，不修补人工标签。 */
public final class FrozenRankingEvaluation {
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private FrozenRankingEvaluation() {}

    public static final class Rejected extends IllegalArgumentException {
        private final String status;

        Rejected(String status, String reason) {
            super(reason);
            this.status = status;
        }

        public String status() {
            return status;
        }
    }

    private static void require(boolean valid, String status, String reason) {
        if (!valid) throw new Rejected(status, reason);
    }

    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static String fingerprint(JsonNode node) throws Exception {
        return FrozenFingerprint.fingerprint(node);
    }

    private static String poolKey(String questionId, String chunking) {
        return questionId + "/" + chunking;
    }

    private static String identity(Long documentId, Long versionId, Long chunkId) {
        return documentId + "/" + versionId + "/" + chunkId;
    }

    private static <T> Map<String, T> index(List<T> items, java.util.function.Function<T, String> keyOf) {
        Map<String, T> map = new TreeMap<>();
        for (T item : items) {
            String key = keyOf.apply(item);
            require(map.putIfAbsent(key, item) == null, "INVALID_POOL", "重复身份: " + key);
        }
        return map;
    }

    public record MaterialCandidate(
            Long chunkId,
            Long documentId,
            Long indexVersionId,
            String documentPath,
            String sourceSha256,
            Integer sourceStart,
            Integer sourceEnd,
            String content,
            String contentSha256) {}

    public record MaterialPool(
            String poolId,
            Long poolVersion,
            String dataset,
            RetrievalFixtureSource source,
            String snapshotHash,
            String questionId,
            String chunking,
            List<MaterialCandidate> candidates) {}

    public record ReviewMaterials(List<MaterialPool> pools) {}

    public record JudgmentPool(
            String poolId,
            Long poolVersion,
            String questionId,
            String chunking,
            String snapshotHash,
            List<RelevanceJudgment> judgments,
            String reviewStatus) {}

    public record ConfirmedJudgments(String status, List<JudgmentPool> pools) {}
    /** 原始输入的规范化摘要，包含投影模型不参与业务计算的说明字段。 */
    public record InputFingerprints(String fixture, String materials, String judgments) {}

    public record Frozen(RetrievalFixture fixture, FrozenPools manifest) {}

    public static JsonNode fixtureJson(Frozen frozen) {
        return JSON.valueToTree(frozen.fixture());
    }

    public static JsonNode manifestJson(Frozen frozen) {
        return JSON.valueToTree(frozen.manifest());
    }

    // JSON 入口只读取类型和规范化摘要；所有冻结规则集中在强类型实现。
    public static Frozen freeze(JsonNode fixture, JsonNode materials, JsonNode judgments) throws Exception {
        return freeze(
                JSON.treeToValue(fixture, RetrievalFixture.class),
                JSON.treeToValue(materials, ReviewMaterials.class),
                JSON.treeToValue(judgments, ConfirmedJudgments.class),
                new InputFingerprints(fingerprint(fixture), fingerprint(materials), fingerprint(judgments)));
    }

    private static String fingerprintWithoutDigest(Object value) throws Exception {
        ObjectNode node = (ObjectNode) JSON.valueToTree(value);
        node.remove("fingerprint");
        return fingerprint(node);
    }

    public static Frozen freeze(
            RetrievalFixture fixture, ReviewMaterials materials, ConfirmedJudgments judgments, InputFingerprints inputs)
            throws Exception {
        require("CONFIRMED".equals(judgments.status()), "NOT_READY", "确认文件未完成");
        Map<String, MaterialPool> pools = index(materials.pools(), p -> poolKey(p.questionId(), p.chunking()));
        Map<String, JudgmentPool> labels = index(judgments.pools(), p -> poolKey(p.questionId(), p.chunking()));
        require(pools.size() == 24 && pools.keySet().equals(labels.keySet()), "INVALID_POOL", "池集合不一致");
        require(fixture.runs().size() == 72, "INVALID_POOL", "检索运行数量不是 72");
        java.util.Set<String> runKeys = new java.util.HashSet<>();
        Map<String, Map<String, com.lnjecit.knowledge.assistant.retrieval.model.RankedChunk>> candidates =
                new TreeMap<>();
        Map<String, String> snapshots = new TreeMap<>();
        // 保持原校验顺序：运行身份、快照、正文区间，再核对人工候选与标签。
        for (OfflineRetrievalRunResult run : fixture.runs()) {
            String key = poolKey(run.questionId(), run.chunking());
            require(
                    List.of("VECTOR", "BM25", "HYBRID_RRF").contains(run.strategy())
                            && runKeys.add(key + "/" + run.strategy()),
                    "INVALID_POOL",
                    "策略未知或运行重复");
            String previous = snapshots.putIfAbsent(key, run.snapshotHash());
            require(previous == null || previous.equals(run.snapshotHash()), "INVALID_POOL", "快照冲突");
            Map<String, com.lnjecit.knowledge.assistant.retrieval.model.RankedChunk> union =
                    candidates.computeIfAbsent(key, ignored -> new TreeMap<>());
            for (com.lnjecit.knowledge.assistant.retrieval.model.RankedChunk c : run.chunks()) {
                com.lnjecit.knowledge.assistant.retrieval.model.RankedChunk prior =
                        union.putIfAbsent(identity(c.documentId(), c.indexVersionId(), c.chunkId()), c);
                require(
                        prior == null
                                || prior.equals(c)
                                || (prior.content().equals(c.content())
                                        && prior.sourceStart() == c.sourceStart()
                                        && prior.sourceEnd() == c.sourceEnd()),
                        "INVALID_POOL",
                        "候选正文/来源区间冲突");
            }
        }
        require(candidates.keySet().equals(pools.keySet()), "INVALID_POOL", "Fixture 池集合不一致");
        List<String> paths = Files.readAllLines(Path.of("docs/engineering/m4-01-samples.txt"));
        List<RetrievalFixturePoolJudgmentsItem> adaptedLabels = new ArrayList<>();
        List<FrozenPool> frozenPools = new ArrayList<>();
        int total = 0;
        for (String key : pools.keySet()) {
            MaterialPool p = pools.get(key);
            JudgmentPool l = labels.get(key);
            String expectedId = fixture.pool().id() + "/" + key;
            require(
                    expectedId.equals(p.poolId())
                            && Long.valueOf(1).equals(p.poolVersion())
                            && java.util.Objects.equals(p.dataset(), fixture.protocol())
                            && java.util.Objects.equals(p.source(), fixture.source()),
                    "INVALID_POOL",
                    "池来源或版本不一致");
            require(java.util.Objects.equals(p.poolId(), l.poolId()), "INVALID_POOL", "池字段不一致: poolId");
            require(java.util.Objects.equals(p.poolVersion(), l.poolVersion()), "INVALID_POOL", "池字段不一致: poolVersion");
            require(
                    java.util.Objects.equals(p.snapshotHash(), l.snapshotHash()),
                    "INVALID_POOL",
                    "池字段不一致: snapshotHash");
            require(java.util.Objects.equals(p.snapshotHash(), snapshots.get(key)), "INVALID_POOL", "Fixture 快照不一致");
            require("COMPLETE".equals(l.reviewStatus()), "NOT_READY", "池尚未确认");
            Map<String, MaterialCandidate> material =
                    index(p.candidates(), c -> identity(c.documentId(), c.indexVersionId(), c.chunkId()));
            Map<String, RelevanceJudgment> grades =
                    index(l.judgments(), c -> identity(c.documentId(), c.indexVersionId(), c.chunkId()));
            require(
                    material.keySet().equals(grades.keySet())
                            && material.keySet().equals(candidates.get(key).keySet()),
                    "INVALID_POOL",
                    "候选缺失或池外候选");
            List<RelevanceJudgment> entries = new ArrayList<>();
            for (String id : grades.keySet()) {
                RelevanceJudgment grade = grades.get(id);
                MaterialCandidate c = material.get(id);
                com.lnjecit.knowledge.assistant.retrieval.model.RankedChunk raw =
                        candidates.get(key).get(id);
                require("CONFIRMED".equals(grade.reviewStatus()), "NOT_READY", "标签未确认");
                require(
                        grade.proposedRelevance() != null
                                && grade.proposedRelevance() >= 0
                                && grade.proposedRelevance() <= 3,
                        "INVALID_POOL",
                        "非法等级");
                require(grade.reviewer() != null && !grade.reviewer().isBlank(), "NOT_READY", "缺少确认字段: reviewer");
                require(
                        grade.confirmationBasis() != null
                                && !grade.confirmationBasis().isBlank(),
                        "NOT_READY",
                        "缺少确认字段: confirmationBasis");
                require(
                        grade.confirmedAt() != null && !grade.confirmedAt().isBlank(),
                        "NOT_READY",
                        "缺少确认字段: confirmedAt");
                require(java.util.Objects.equals(grade.documentPath(), c.documentPath()), "INVALID_POOL", "来源身份不一致");
                require(java.util.Objects.equals(grade.sourceSha256(), c.sourceSha256()), "INVALID_POOL", "来源身份不一致");
                require(java.util.Objects.equals(grade.contentSha256(), c.contentSha256()), "INVALID_POOL", "来源身份不一致");
                require(
                        java.util.Objects.equals(c.content(), raw.content())
                                && java.util.Objects.equals(c.sourceStart(), raw.sourceStart())
                                && java.util.Objects.equals(c.sourceEnd(), raw.sourceEnd()),
                        "INVALID_POOL",
                        "Fixture 正文或区间不一致");
                require(
                        hash(raw.content().getBytes(StandardCharsets.UTF_8)).equals(c.contentSha256()),
                        "INVALID_POOL",
                        "正文 Hash 错误");
                long base = "FIXED_CODE_POINT".equals(p.chunking()) ? 800000 : 900000;
                int offset = (int) (c.documentId() - base - 100);
                require(
                        offset >= 0
                                && offset < paths.size()
                                && c.indexVersionId() == base + 200 + offset
                                && paths.get(offset).equals(c.documentPath()),
                        "INVALID_POOL",
                        "实验来源映射错误");
                require(
                        hash(Files.readAllBytes(EvaluationCorpus.resolve(paths.get(offset))))
                                .equals(c.sourceSha256()),
                        "INVALID_POOL",
                        "来源文件 Hash 错误");
                String relevance = Long.toString(grade.proposedRelevance());
                entries.add(new RelevanceJudgment(
                        grade.documentId(),
                        grade.indexVersionId(),
                        grade.chunkId(),
                        grade.documentPath(),
                        grade.sourceSha256(),
                        grade.contentSha256(),
                        grade.proposedRelevance(),
                        grade.rationale(),
                        grade.reviewStatus(),
                        grade.proposalSource(),
                        grade.reviewer(),
                        grade.confirmedAt(),
                        grade.confirmationBasis(),
                        relevance));
                adaptedLabels.add(new RetrievalFixturePoolJudgmentsItem(
                        p.questionId(), p.chunking(), grade.chunkId(), relevance, grade.reviewer()));
                total++;
            }
            FrozenPool pool = new FrozenPool(
                    p.poolId(),
                    p.poolVersion(),
                    p.questionId(),
                    p.chunking(),
                    p.snapshotHash(),
                    "COMPLETE",
                    entries,
                    null);
            frozenPools.add(new FrozenPool(
                    pool.poolId(),
                    pool.poolVersion(),
                    pool.questionId(),
                    pool.chunking(),
                    pool.snapshotHash(),
                    pool.status(),
                    entries,
                    fingerprintWithoutDigest(pool)));
        }
        require(total == 186, "INVALID_POOL", "候选数不是 186");
        RetrievalFixturePool adaptedPool =
                new RetrievalFixturePool(fixture.pool().id(), fixture.pool().scope(), "COMPLETE", adaptedLabels);
        RetrievalFixture view =
                new RetrievalFixture(fixture.protocol(), fixture.source(), fixture.gold(), adaptedPool, fixture.runs());
        FrozenPools manifest = new FrozenPools(
                1L,
                "recursive sorted object keys; array order retained; compact UTF-8 JSON; no timestamp",
                frozenPools,
                inputs.fixture(),
                inputs.judgments(),
                inputs.materials(),
                null);
        return new Frozen(
                view,
                new FrozenPools(
                        manifest.schemaVersion(),
                        manifest.canonicalization(),
                        manifest.pools(),
                        manifest.fixtureFingerprint(),
                        manifest.judgmentsFingerprint(),
                        manifest.materialsFingerprint(),
                        fingerprintWithoutDigest(manifest)));
    }

    public record Mean(double numerator, int denominator) {
        public Double value() {
            return denominator == 0 ? null : numerator / denominator;
        }
    }

    public static Mean mean(List<Double> values) {
        double sum = 0;
        int count = 0;
        for (Double value : values)
            if (value != null) {
                sum += value;
                count++;
            }
        return new Mean(sum, count);
    }

    public record RunMetrics(
            String questionId,
            String chunking,
            String strategy,
            RankingMetrics.Result at3,
            RankingMetrics.Result at5,
            EvidenceMetrics.Result evidence3,
            EvidenceMetrics.Result evidence5,
            List<Integer> grades) {}

    public record Aggregate(double numerator, int denominator, Double value) {}

    public record GroupMetrics(
            String chunking,
            String strategy,
            Map<String, Map<String, Integer>> statuses,
            Aggregate precision3,
            Aggregate ndcg3,
            Aggregate precision5,
            Aggregate ndcg5) {}

    public record Report(FrozenPools frozenPool, List<RunMetrics> runs, List<GroupMetrics> groups) {}

    private static RankingMetrics.Run rankingRun(OfflineRetrievalRunResult run) {
        return new RankingMetrics.Run(
                run.questionId(),
                run.chunking(),
                run.snapshotHash(),
                run.status(),
                run.chunks().stream()
                        .map(c -> new RankingMetrics.Candidate(
                                c.documentId(), c.indexVersionId(), c.chunkId(), c.content(), c.finalRank()))
                        .toList());
    }

    private static Aggregate aggregate(List<Double> values) {
        Mean mean = mean(values);
        return new Aggregate(mean.numerator(), mean.denominator(), mean.value());
    }

    public static Report evaluateTyped(Frozen frozen) {
        RetrievalFixture fixture = frozen.fixture();
        RankingMetrics.Fixture ranking = new RankingMetrics.Fixture(
                fixture.pool(),
                fixture.runs().stream().map(FrozenRankingEvaluation::rankingRun).toList());
        List<RunMetrics> runs = new ArrayList<>();
        for (OfflineRetrievalRunResult run : fixture.runs()) {
            Map<String, EvidenceMetrics.Source> sources = new TreeMap<>();
            for (FrozenPool pool : frozen.manifest().pools()) {
                if (!pool.questionId().equals(run.questionId())
                        || !pool.chunking().equals(run.chunking())) continue;
                for (RelevanceJudgment c : pool.judgments())
                    sources.putIfAbsent(
                            c.documentId().toString(),
                            new EvidenceMetrics.Source(
                                    c.documentId(), c.indexVersionId(), c.documentPath(), c.sourceSha256()));
            }
            GoldQuestionIdentity gold = fixture.gold().questions().stream()
                    .filter(q -> q.questionId().equals(run.questionId()))
                    .findFirst()
                    .orElse(null);
            EvidenceMetrics.Gold evidenceGold = gold == null
                    ? null
                    : new EvidenceMetrics.Gold(
                            null,
                            gold.reviewStatus(),
                            gold.relation(),
                            gold.documentPath(),
                            gold.sourceSha256(),
                            gold.evidence());
            EvidenceMetrics.Run evidenceRun = new EvidenceMetrics.Run(
                    run.status(),
                    run.chunks().stream()
                            .map(c -> new EvidenceMetrics.Candidate(
                                    c.documentId(), c.indexVersionId(), c.finalRank(), c.sourceStart(), c.sourceEnd()))
                            .toList());
            List<Integer> grades = new ArrayList<>();
            for (com.lnjecit.knowledge.assistant.retrieval.model.RankedChunk c : run.chunks())
                for (RetrievalFixturePoolJudgmentsItem label : fixture.pool().judgments())
                    if (label.questionId().equals(run.questionId())
                            && label.chunking().equals(run.chunking())
                            && label.chunkId().equals(c.chunkId())) grades.add(Integer.parseInt(label.relevance()));
            // 两类指标共用已冻结身份，各自保留原有分母和排除规则。
            runs.add(new RunMetrics(
                    run.questionId(),
                    run.chunking(),
                    run.strategy(),
                    RankingMetrics.calculate(ranking, rankingRun(run), 3),
                    RankingMetrics.calculate(ranking, rankingRun(run), 5),
                    EvidenceMetrics.calculate(evidenceGold, evidenceRun, new ArrayList<>(sources.values()), 3),
                    EvidenceMetrics.calculate(evidenceGold, evidenceRun, new ArrayList<>(sources.values()), 5),
                    grades));
        }
        List<GroupMetrics> groups = new ArrayList<>();
        for (String chunking : List.of("FIXED_CODE_POINT", "MARKDOWN_STRUCTURE_AWARE"))
            for (String strategy : List.of("VECTOR", "BM25", "HYBRID_RRF")) {
                List<RunMetrics> selected = runs.stream()
                        .filter(r ->
                                r.chunking().equals(chunking) && r.strategy().equals(strategy))
                        .toList();
                Map<String, Map<String, Integer>> statuses = new java.util.LinkedHashMap<>();
                for (int k : List.of(3, 5)) {
                    Map<String, Integer> counts = new TreeMap<>();
                    for (RunMetrics run : selected)
                        counts.merge((k == 3 ? run.at3() : run.at5()).status(), 1, Integer::sum);
                    statuses.put("at" + k, counts);
                }
                groups.add(new GroupMetrics(
                        chunking,
                        strategy,
                        statuses,
                        aggregate(
                                selected.stream().map(r -> r.at3().precision()).toList()),
                        aggregate(selected.stream().map(r -> r.at3().ndcg()).toList()),
                        aggregate(
                                selected.stream().map(r -> r.at5().precision()).toList()),
                        aggregate(selected.stream().map(r -> r.at5().ndcg()).toList())));
            }
        return new Report(frozen.manifest(), runs, groups);
    }

    public static ObjectNode evaluate(Frozen frozen) {
        return (ObjectNode) JSON.readTree(JSON.writeValueAsString(evaluateTyped(frozen)));
    }

    public static void main(String[] args) throws Exception {
        JsonNode f = JSON.readTree(Files.readString(Path.of("src/test/resources/retrieval-metrics-v1.json")));
        JsonNode m = JSON.readTree(Files.readString(Path.of("docs/engineering/m4-b03-review-materials.json")));
        JsonNode j = JSON.readTree(Files.readString(Path.of("docs/engineering/m4-b03-relevance-judgments.json")));
        ObjectNode result = evaluate(freeze(f, m, j));
        Path output = Path.of("target/m4-b03-ranking-evaluation.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n");
        System.out.println("Frozen evaluation: " + output + " "
                + result.path("frozenPool").path("fingerprint").asString());
    }
}
