package com.lnjecit.knowledge.assistant.evaluation.metrics;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 仅适配 M4 冻结实验；校验失败时拒绝评分，不修补人工标签。 */
public final class FrozenRankingEvaluation {
    private static final JsonMapper JSON = JsonMapper.builder().build();

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

    // 对象键递归排序，数组保留顺序；UTF-8 紧凑 JSON，无时间戳，作为指纹输入。
    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            node.propertyNames().stream().sorted().forEach(k -> sorted.set(k, canonical(node.path(k))));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = JSON.createArrayNode();
            for (JsonNode item : node) array.add(canonical(item));
            return array;
        }
        return node;
    }

    static String fingerprint(JsonNode node) throws Exception {
        return hash(canonical(node).toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String poolKey(JsonNode p) {
        return p.path("questionId").asString() + "/" + p.path("chunking").asString();
    }

    private static String identity(JsonNode c) {
        return c.path("documentId").asString() + "/" + c.path("indexVersionId").asString() + "/"
                + c.path("chunkId").asString();
    }

    private static Map<String, JsonNode> index(JsonNode array, boolean pools) {
        Map<String, JsonNode> map = new TreeMap<>();
        for (JsonNode item : array) {
            String key = pools ? poolKey(item) : identity(item);
            require(map.putIfAbsent(key, item) == null, "INVALID_POOL", "重复身份: " + key);
        }
        return map;
    }

    public record Frozen(ObjectNode fixture, ObjectNode manifest) {}

    public static Frozen freeze(JsonNode fixture, JsonNode materials, JsonNode judgments) throws Exception {
        require(judgments.path("status").asString().equals("CONFIRMED"), "NOT_READY", "确认文件未完成");
        Map<String, JsonNode> pools = index(materials.path("pools"), true);
        Map<String, JsonNode> labels = index(judgments.path("pools"), true);
        require(pools.size() == 24 && pools.keySet().equals(labels.keySet()), "INVALID_POOL", "池集合不一致");
        require(fixture.path("runs").size() == 72, "INVALID_POOL", "检索运行数量不是 72");
        java.util.Set<String> runKeys = new java.util.HashSet<>();
        Map<String, Map<String, JsonNode>> candidates = new TreeMap<>();
        Map<String, String> snapshots = new TreeMap<>();
        for (JsonNode run : fixture.path("runs")) {
            String key = poolKey(run);
            require(
                    List.of("VECTOR", "BM25", "HYBRID_RRF")
                                    .contains(run.path("strategy").asString())
                            && runKeys.add(key + "/" + run.path("strategy").asString()),
                    "INVALID_POOL",
                    "策略未知或运行重复");
            String snapshot = run.path("snapshotHash").asString();
            String previous = snapshots.putIfAbsent(key, snapshot);
            require(previous == null || previous.equals(snapshot), "INVALID_POOL", "快照冲突");
            Map<String, JsonNode> union = candidates.computeIfAbsent(key, ignored -> new TreeMap<>());
            for (JsonNode c : run.path("chunks")) {
                JsonNode prior = union.putIfAbsent(identity(c), c);
                require(
                        prior == null
                                || prior.equals(c)
                                || (prior.path("content").equals(c.path("content"))
                                        && prior.path("sourceStart").equals(c.path("sourceStart"))
                                        && prior.path("sourceEnd").equals(c.path("sourceEnd"))),
                        "INVALID_POOL",
                        "候选正文/来源区间冲突");
            }
        }
        require(candidates.keySet().equals(pools.keySet()), "INVALID_POOL", "Fixture 池集合不一致");
        List<String> paths = Files.readAllLines(Path.of("docs/engineering/m4-01-samples.txt"));
        ObjectNode view = (ObjectNode) fixture.deepCopy();
        ObjectNode adaptedPool = (ObjectNode) view.path("pool");
        ArrayNode adaptedLabels = JSON.createArrayNode();
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("schemaVersion", 1);
        manifest.put(
                "canonicalization",
                "recursive sorted object keys; array order retained; compact UTF-8 JSON; no timestamp");
        ArrayNode frozenPools = manifest.putArray("pools");
        int total = 0;
        for (String key : pools.keySet()) {
            JsonNode p = pools.get(key), l = labels.get(key);
            String expectedId = fixture.path("pool").path("id").asString() + "/" + key;
            require(
                    p.path("poolId").asString().equals(expectedId)
                            && p.path("poolVersion").asInt() == 1
                            && p.path("dataset").equals(fixture.path("protocol"))
                            && p.path("source").equals(fixture.path("source")),
                    "INVALID_POOL",
                    "池来源或版本不一致");
            for (String field : List.of("poolId", "poolVersion", "snapshotHash"))
                require(p.path(field).equals(l.path(field)), "INVALID_POOL", "池字段不一致: " + field);
            require(p.path("snapshotHash").asString().equals(snapshots.get(key)), "INVALID_POOL", "Fixture 快照不一致");
            require(l.path("reviewStatus").asString().equals("COMPLETE"), "NOT_READY", "池尚未确认");
            Map<String, JsonNode> material = index(p.path("candidates"), false);
            Map<String, JsonNode> grades = index(l.path("judgments"), false);
            require(
                    material.keySet().equals(grades.keySet())
                            && material.keySet().equals(candidates.get(key).keySet()),
                    "INVALID_POOL",
                    "候选缺失或池外候选");
            ObjectNode frozenPool = JSON.createObjectNode();
            for (String field : List.of("poolId", "poolVersion", "questionId", "chunking", "snapshotHash"))
                frozenPool.set(field, p.path(field));
            frozenPool.put("status", "COMPLETE");
            ArrayNode entries = frozenPool.putArray("judgments");
            for (String id : grades.keySet()) {
                JsonNode grade = grades.get(id),
                        c = material.get(id),
                        raw = candidates.get(key).get(id);
                require(grade.path("reviewStatus").asString().equals("CONFIRMED"), "NOT_READY", "标签未确认");
                require(
                        grade.path("proposedRelevance").isIntegralNumber()
                                && grade.path("proposedRelevance").asInt() >= 0
                                && grade.path("proposedRelevance").asInt() <= 3,
                        "INVALID_POOL",
                        "非法等级");
                for (String field : List.of("reviewer", "confirmationBasis", "confirmedAt"))
                    require(
                            grade.path(field).isString()
                                    && !grade.path(field).asString().isBlank(),
                            "NOT_READY",
                            "缺少确认字段: " + field);
                for (String field : List.of("documentPath", "sourceSha256", "contentSha256"))
                    require(grade.path(field).equals(c.path(field)), "INVALID_POOL", "来源身份不一致");
                require(
                        c.path("content").equals(raw.path("content"))
                                && c.path("sourceStart").equals(raw.path("sourceStart"))
                                && c.path("sourceEnd").equals(raw.path("sourceEnd")),
                        "INVALID_POOL",
                        "Fixture 正文或区间不一致");
                require(
                        hash(raw.path("content").asString().getBytes(StandardCharsets.UTF_8))
                                .equals(c.path("contentSha256").asString()),
                        "INVALID_POOL",
                        "正文 Hash 错误");
                long base = p.path("chunking").asString().equals("FIXED_CODE_POINT") ? 800000 : 900000;
                int offset = (int) (c.path("documentId").asLong() - base - 100);
                require(
                        offset >= 0
                                && offset < paths.size()
                                && c.path("indexVersionId").asLong() == base + 200 + offset
                                && paths.get(offset)
                                        .equals(c.path("documentPath").asString()),
                        "INVALID_POOL",
                        "实验来源映射错误");
                require(
                        hash(Files.readAllBytes(Path.of(paths.get(offset))))
                                .equals(c.path("sourceSha256").asString()),
                        "INVALID_POOL",
                        "来源文件 Hash 错误");
                ObjectNode entry = (ObjectNode) grade.deepCopy();
                entry.put(
                        "relevance",
                        Integer.toString(grade.path("proposedRelevance").asInt()));
                entries.add(entry);
                ObjectNode adapted = entry.deepCopy();
                adapted.set("questionId", p.path("questionId"));
                adapted.set("chunking", p.path("chunking"));
                adaptedLabels.add(adapted);
                total++;
            }
            frozenPool.put("fingerprint", fingerprint(frozenPool));
            frozenPools.add(frozenPool);
        }
        require(total == 186, "INVALID_POOL", "候选数不是 186");
        adaptedPool.set("judgments", adaptedLabels);
        adaptedPool.put("status", "COMPLETE");
        manifest.put("fixtureFingerprint", fingerprint(fixture));
        manifest.put("judgmentsFingerprint", fingerprint(judgments));
        manifest.put("materialsFingerprint", fingerprint(materials));
        manifest.put("fingerprint", fingerprint(manifest));
        return new Frozen(view, manifest);
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

    public static ObjectNode evaluate(Frozen frozen) {
        ObjectNode result = JSON.createObjectNode();
        result.set("frozenPool", frozen.manifest());
        ArrayNode runs = result.putArray("runs");
        for (JsonNode run : frozen.fixture().path("runs")) {
            ObjectNode item = JSON.createObjectNode();
            for (String field : List.of("questionId", "chunking", "strategy")) item.set(field, run.path(field));
            for (int k : List.of(3, 5))
                item.set("at" + k, JSON.valueToTree(RankingMetrics.calculate(frozen.fixture(), run, k)));
            // Evidence 与 Ranking 共用已验证的来源身份，但保留独立判定口径。
            Map<String, EvidenceMetrics.Source> sourceMap = new TreeMap<>();
            for (JsonNode pool : frozen.manifest().path("pools"))
                if (poolKey(pool).equals(poolKey(run)))
                    for (JsonNode c : pool.path("judgments"))
                        sourceMap.putIfAbsent(
                                c.path("documentId").asString(),
                                new EvidenceMetrics.Source(
                                        c.path("documentId").asLong(),
                                        c.path("indexVersionId").asLong(),
                                        c.path("documentPath").asString(),
                                        c.path("sourceSha256").asString()));
            JsonNode gold = null;
            for (JsonNode q : frozen.fixture().path("gold").path("questions"))
                if (q.path("questionId").equals(run.path("questionId"))) gold = q;
            for (int k : List.of(3, 5))
                item.set(
                        "evidence" + k,
                        JSON.valueToTree(EvidenceMetrics.calculate(gold, run, new ArrayList<>(sourceMap.values()), k)));
            ArrayNode ranked = item.putArray("grades");
            for (JsonNode c : run.path("chunks")) {
                for (JsonNode label : frozen.fixture().path("pool").path("judgments"))
                    if (poolKey(label).equals(poolKey(run)) && identity(label).equals(identity(c)))
                        ranked.add(Integer.parseInt(label.path("relevance").asString()));
            }
            runs.add(item);
        }
        ArrayNode groups = result.putArray("groups");
        for (String chunking : List.of("FIXED_CODE_POINT", "MARKDOWN_STRUCTURE_AWARE"))
            for (String strategy : List.of("VECTOR", "BM25", "HYBRID_RRF")) {
                ObjectNode group = groups.addObject();
                group.put("chunking", chunking);
                group.put("strategy", strategy);
                ObjectNode statuses = group.putObject("statuses");
                for (int k : List.of(3, 5)) {
                    Map<String, Integer> counts = new TreeMap<>();
                    for (String metric : List.of("precision", "ndcg")) {
                        List<Double> values = new ArrayList<>();
                        for (JsonNode run : runs)
                            if (run.path("chunking").asString().equals(chunking)
                                    && run.path("strategy").asString().equals(strategy)) {
                                JsonNode score = run.path("at" + k);
                                if (metric.equals("precision"))
                                    counts.merge(score.path("status").asString(), 1, Integer::sum);
                                values.add(
                                        score.path(metric).isNumber()
                                                ? score.path(metric).asDouble()
                                                : null);
                            }
                        Mean mean = mean(values);
                        ObjectNode summary = group.putObject(metric + k);
                        summary.put("numerator", mean.numerator());
                        summary.put("denominator", mean.denominator());
                        if (mean.value() == null) summary.putNull("value");
                        else summary.put("value", mean.value());
                    }
                    statuses.set("at" + k, JSON.valueToTree(counts));
                }
            }
        return result;
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
