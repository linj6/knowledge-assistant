package com.lnjecit.knowledge.assistant.evaluation.metrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** 冻结评审池上的离线排名计算，不推断人工标签。 */
public final class RankingMetrics {
    private RankingMetrics() {}

    public record Result(String status, Double precision, Double dcg, Double idcg, Double ndcg) {}

    private static String identity(JsonNode chunk) {
        return chunk.path("documentId").asString() + "/"
                + chunk.path("indexVersionId").asString() + "/"
                + chunk.path("chunkId").asString();
    }

    public static double dcg(List<Integer> grades, int k) {
        if (k <= 0) throw new IllegalArgumentException("K 必须为正数");
        double value = 0;
        for (int i = 0; i < grades.size(); i++) {
            int grade = grades.get(i);
            if (grade < 0 || grade > 3) throw new IllegalArgumentException("等级必须为 0 至 3");
            if (i < k) value += (Math.pow(2, grade) - 1) / (Math.log(i + 2) / Math.log(2));
        }
        return value;
    }

    private static Result unavailable(String status) {
        return new Result(status, null, null, null, null);
    }

    public static Result calculate(JsonNode fixture, JsonNode run, int k) {
        if (k <= 0) throw new IllegalArgumentException("K 必须为正数");
        if (!run.path("status").asString().equals("SUCCESS")) return unavailable("EXECUTION_NOT_SUCCESS");
        Map<String, JsonNode> candidates = new HashMap<>();
        // 先核对同问题、同切分、同快照的候选身份；策略只决定排名。
        for (JsonNode other : fixture.path("runs")) {
            if (!other.path("questionId").equals(run.path("questionId"))
                    || !other.path("chunking").equals(run.path("chunking"))) continue;
            if (!other.path("snapshotHash").equals(run.path("snapshotHash"))) return unavailable("SNAPSHOT_MISMATCH");
            for (JsonNode chunk : other.path("chunks")) {
                String id = chunk.path("chunkId").asString();
                JsonNode previous = candidates.putIfAbsent(id, chunk);
                if (previous != null
                        && (!identity(previous).equals(identity(chunk))
                                || !previous.path("content").equals(chunk.path("content"))))
                    return unavailable("IDENTITY_CONFLICT");
            }
        }
        Map<String, Integer> grades = new HashMap<>();
        for (JsonNode judgment : fixture.path("pool").path("judgments")) {
            if (!judgment.path("questionId").equals(run.path("questionId"))
                    || !judgment.path("chunking").equals(run.path("chunking"))) continue;
            String id = judgment.path("chunkId").asString();
            String label = judgment.path("relevance").asString();
            if (!label.matches("[0-3]")
                    || judgment.path("reviewer").isNull()
                    || judgment.path("reviewer").asString().isBlank()) return unavailable("NOT_READY");
            if (!candidates.containsKey(id) || grades.putIfAbsent(id, Integer.parseInt(label)) != null)
                return unavailable("INVALID_POOL");
        }
        if (!fixture.path("pool").path("status").asString().equals("COMPLETE") || grades.isEmpty())
            return unavailable("NOT_READY");
        if (!grades.keySet().equals(candidates.keySet())) return unavailable("OUTSIDE_POOL");
        List<Integer> ranked = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode chunk : run.path("chunks")) {
            String id = chunk.path("chunkId").asString();
            if (!grades.containsKey(id) || !identity(chunk).equals(identity(candidates.get(id))))
                return unavailable("OUTSIDE_POOL");
            if (!seen.add(id) || chunk.path("finalRank").asInt() != ranked.size() + 1)
                return unavailable("INVALID_RANKING");
            ranked.add(grades.get(id));
        }
        List<Integer> ideal = new ArrayList<>(grades.values());
        ideal.sort(Comparator.reverseOrder());
        double idcg = dcg(ideal, k);
        double actual = dcg(ranked, k);
        double precision = ranked.stream().limit(k).filter(grade -> grade >= 2).count() / (double) k;
        // 全零池的 Precision/DCG 仍有定义，nDCG 明确无定义。
        return new Result(
                idcg == 0 ? "UNDEFINED" : "SCORED", precision, actual, idcg, idcg == 0 ? null : actual / idcg);
    }
}
