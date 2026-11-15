package com.lnjecit.knowledge.assistant.evaluation.metrics;

import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.COMPLETE;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.EXECUTION_NOT_SUCCESS;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.IDENTITY_CONFLICT;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.INVALID_POOL;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.INVALID_RANKING;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.NOT_READY;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.OUTSIDE_POOL;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.SCORED;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.SNAPSHOT_MISMATCH;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.SUCCESS;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.UNDEFINED;

import com.lnjecit.knowledge.assistant.evaluation.RetrievalRunData.RetrievalFixturePool;
import com.lnjecit.knowledge.assistant.evaluation.RetrievalRunData.RetrievalFixturePoolJudgmentsItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 冻结评审池上的离线排名计算，不推断人工标签。 */
public final class RankingMetrics {
    private RankingMetrics() {}

    private enum RelevanceGrade {
        NONE,
        MARGINAL,
        RELEVANT,
        HIGHLY_RELEVANT;

        static RelevanceGrade of(int value) {
            if (value < 0 || value > 3) throw new IllegalArgumentException("等级必须为 0 至 3");
            return values()[value];
        }

        boolean relevant() {
            return ordinal() >= RELEVANT.ordinal();
        }
    }

    public record Result(String status, Double precision, Double dcg, Double idcg, Double ndcg) {}

    // 历史候选缺少 RankedChunk 必填字段，使用仅含评分所需字段的投影。
    public record Candidate(Long documentId, Long indexVersionId, Long chunkId, String content, int finalRank) {}

    public record Run(String questionId, String chunking, String snapshotHash, String status, List<Candidate> chunks) {}

    public record Fixture(RetrievalFixturePool pool, List<Run> runs) {}

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public static Fixture readFixture(JsonNode node) {
        return JSON.treeToValue(node, Fixture.class);
    }

    public static Run readRun(JsonNode node) {
        return JSON.treeToValue(node, Run.class);
    }

    private static String identity(Candidate chunk) {
        return chunk.documentId() + "/" + chunk.indexVersionId() + "/" + chunk.chunkId();
    }

    public static double dcg(List<Integer> grades, int k) {
        if (k <= 0) throw new IllegalArgumentException("K 必须为正数");
        double value = 0;
        for (int i = 0; i < grades.size(); i++) {
            int grade = grades.get(i);
            if (grade < 0 || grade > 3) throw new IllegalArgumentException("等级必须为 0 至 3");
            // 指数增益除以以 2 为底的排名折扣；首位不折扣。
            if (i < k) value += (Math.pow(2, grade) - 1) / (Math.log(i + 2) / Math.log(2));
        }
        return value;
    }

    private static Result unavailable(String status) {
        return new Result(status, null, null, null, null);
    }

    public static Result calculate(JsonNode fixture, JsonNode run, int k) {
        return calculate(readFixture(fixture), readRun(run), k);
    }

    public static Result calculate(Fixture fixture, Run run, int k) {
        if (k <= 0) throw new IllegalArgumentException("K 必须为正数");
        if (!run.status().equals(SUCCESS)) return unavailable(EXECUTION_NOT_SUCCESS);
        Map<Long, Candidate> candidates = new HashMap<>();
        // 先核对同问题、同切分、同快照的候选身份；策略只决定排名。
        for (Run other : fixture.runs()) {
            if (!Objects.equals(other.questionId(), run.questionId())
                    || !Objects.equals(other.chunking(), run.chunking())) continue;
            if (!Objects.equals(other.snapshotHash(), run.snapshotHash())) return unavailable(SNAPSHOT_MISMATCH);
            for (Candidate chunk : other.chunks()) {
                Long id = chunk.chunkId();
                Candidate previous = candidates.putIfAbsent(id, chunk);
                if (previous != null
                        && (!identity(previous).equals(identity(chunk))
                                || !Objects.equals(previous.content(), chunk.content())))
                    return unavailable(IDENTITY_CONFLICT);
            }
        }
        Map<Long, Integer> grades = new HashMap<>();
        for (RetrievalFixturePoolJudgmentsItem judgment : fixture.pool().judgments()) {
            if (!Objects.equals(judgment.questionId(), run.questionId())
                    || !Objects.equals(judgment.chunking(), run.chunking())) continue;
            Long id = judgment.chunkId();
            String label = judgment.relevance();
            if ((label == null || !label.matches("[0-3]"))
                    || judgment.reviewer() == null
                    || judgment.reviewer().isBlank()) return unavailable(NOT_READY);
            if (!candidates.containsKey(id) || grades.putIfAbsent(id, Integer.parseInt(label)) != null)
                return unavailable(INVALID_POOL);
        }
        if (!fixture.pool().status().equals(COMPLETE) || grades.isEmpty()) return unavailable(NOT_READY);
        if (!grades.keySet().equals(candidates.keySet())) return unavailable(OUTSIDE_POOL);
        List<Integer> ranked = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Candidate chunk : run.chunks()) {
            Long id = chunk.chunkId();
            if (!grades.containsKey(id) || !identity(chunk).equals(identity(candidates.get(id))))
                return unavailable(OUTSIDE_POOL);
            if (!seen.add(id) || chunk.finalRank() != ranked.size() + 1) return unavailable(INVALID_RANKING);
            ranked.add(grades.get(id));
        }
        return score(
                ranked.stream().map(RelevanceGrade::of).toList(),
                grades.values().stream().map(RelevanceGrade::of).toList(),
                k);
    }

    // IDCG 始终取同问题、同 Chunking 的完整冻结候选池，不能只取本次返回结果。
    private static Result score(List<RelevanceGrade> ranked, List<RelevanceGrade> pool, int k) {
        List<Integer> ideal =
                new ArrayList<>(pool.stream().map(RelevanceGrade::ordinal).toList());
        ideal.sort(Comparator.reverseOrder());
        double idcg = dcg(ideal, k);
        double actual = dcg(ranked.stream().map(RelevanceGrade::ordinal).toList(), k);
        double precision =
                ranked.stream().limit(k).filter(RelevanceGrade::relevant).count() / (double) k;
        // 全零池的 Precision/DCG 仍有定义，nDCG 明确无定义。
        return new Result(idcg == 0 ? UNDEFINED : SCORED, precision, actual, idcg, idcg == 0 ? null : actual / idcg);
    }
}
