package com.lnjecit.knowledge.assistant.evaluation.metrics;

import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.ALL;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.CONFIRMED;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.EMPTY_RESULT;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.EXCLUDED;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.FAILED;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.MISSING;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.MISSING_GOLD;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.PENDING;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.PENDING_REVIEW;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.REJECTED_GOLD;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.SUCCESS;
import static com.lnjecit.knowledge.assistant.evaluation.metrics.EvaluationProtocol.UNANSWERABLE;

import com.lnjecit.knowledge.assistant.evaluation.model.RetrievalRunData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** B01 的离线 SourceSpan 计算；调用方提供已核验的文档身份映射。 */
public final class EvidenceMetrics {
    private EvidenceMetrics() {}

    private record SourceSpan(int start, int end) {}

    private record EvaluationChunk(long documentId, long versionId, int rank, SourceSpan span) {}

    public record Source(long documentId, long versionId, String path, String sha256) {}

    public record Result(
            String executionStatus,
            String qualityStatus,
            int depth,
            int covered,
            int evidenceCount,
            Integer firstRank,
            Integer completeRank) {
        public double recall() {
            return evidenceCount == 0 ? 0 : (double) covered / evidenceCount;
        }

        public int cec() {
            return evidenceCount > 0 && covered == evidenceCount ? 1 : 0;
        }

        public int hit() {
            return covered > 0 ? 1 : 0;
        }

        public double mrr() {
            return firstRank == null ? 0 : 1.0 / firstRank;
        }
    }

    // 无答案必须显式声明；缺失标注不能被推断成无答案。
    private static String exclusion(Gold gold) {
        if (gold == null) return MISSING_GOLD;
        if (Boolean.FALSE.equals(gold.answerable())) return UNANSWERABLE;
        String review = gold.reviewStatus();
        if (!CONFIRMED.equals(review)) return PENDING.equals(review) ? PENDING_REVIEW : REJECTED_GOLD;
        if (gold.evidence() == null || gold.evidence().isEmpty()) return MISSING_GOLD;
        return null;
    }

    public record Gold(
            Boolean answerable,
            String reviewStatus,
            String relation,
            String documentPath,
            String sourceSha256,
            List<RetrievalRunData.GoldEvidence> evidence) {}

    public record Run(String status, List<Candidate> chunks) {}

    public record Candidate(long documentId, long indexVersionId, int finalRank, int sourceStart, int sourceEnd) {}

    public record Case(Gold gold, Run run, List<Source> sources) {
        public Case(JsonNode gold, JsonNode run, List<Source> sources) {
            this(readGold(gold), readRun(run), sources);
        }
    }

    // 历史调用只在边界转换，计算流程不再读取 JSON 树。
    private static final tools.jackson.databind.json.JsonMapper MAPPER =
            tools.jackson.databind.json.JsonMapper.builder()
                    .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build();

    private static Gold readGold(JsonNode gold) {
        return gold == null || gold.isNull() || gold.isMissingNode() ? null : MAPPER.treeToValue(gold, Gold.class);
    }

    private static Run readRun(JsonNode run) {
        return MAPPER.treeToValue(run, Run.class);
    }

    public record Metric(double numerator, int denominator) {
        public Double value() {
            return denominator == 0 ? null : numerator / denominator;
        }
    }

    public record Summary(
            int totalCases,
            int eligibleCases,
            int scoredCases,
            int successCount,
            int emptyResultCount,
            int failedCount,
            int missingCount,
            int pendingReviewCount,
            Metric recall3,
            Metric recall5,
            Metric cec3,
            Metric cec5,
            Metric hit3,
            Metric hit5,
            Metric mrr5) {}

    public static Summary summarize(List<Case> cases) {
        int eligible = 0, scored = 0, success = 0, empty = 0, failed = 0, missing = 0, pending = 0;
        double[] sums = new double[7];
        for (Case item : cases) {
            String exclusion = exclusion(item.gold());
            if (exclusion == null) eligible++;
            if (PENDING_REVIEW.equals(exclusion)) pending++;
            Result three = calculate(item.gold(), item.run(), item.sources(), 3);
            Result five = calculate(item.gold(), item.run(), item.sources(), 5);
            switch (five.executionStatus()) {
                case SUCCESS -> {
                    success++;
                    if (item.run().chunks().isEmpty()) empty++;
                }
                case FAILED -> failed++;
                case MISSING -> missing++;
                default -> throw new IllegalArgumentException("未知执行状态");
            }
            // 执行成功且 Gold 合格才进入宏平均分母，空结果保留零贡献。
            if (exclusion == null && five.executionStatus().equals(SUCCESS)) {
                scored++;
                double[] values = {
                    three.recall(), five.recall(), three.cec(), five.cec(), three.hit(), five.hit(), five.mrr()
                };
                for (int i = 0; i < values.length; i++) sums[i] += values[i];
            }
        }
        return new Summary(
                cases.size(),
                eligible,
                scored,
                success,
                empty,
                failed,
                missing,
                pending,
                new Metric(sums[0], scored),
                new Metric(sums[1], scored),
                new Metric(sums[2], scored),
                new Metric(sums[3], scored),
                new Metric(sums[4], scored),
                new Metric(sums[5], scored),
                new Metric(sums[6], scored));
    }

    public static Result calculate(JsonNode gold, JsonNode run, List<Source> sources, int k) {
        return calculate(readGold(gold), readRun(run), sources, k);
    }

    public static Result calculate(Gold gold, Run run, List<Source> sources, int k) {
        if (k <= 0) throw new IllegalArgumentException("K 必须为正数");
        String status = run.status();
        if (!status.equals(SUCCESS)) {
            if (!status.equals(FAILED) && !status.equals(MISSING)) throw new IllegalArgumentException("未知执行状态");
            return new Result(status, EXCLUDED, 0, 0, 0, null, null);
        }
        String exclusion = exclusion(gold);
        if (exclusion != null) return new Result(status, exclusion, 0, 0, 0, null, null);
        if (!gold.relation().equals(ALL)) throw new IllegalArgumentException("仅支持 ALL");
        List<EvaluationChunk> chunks = new ArrayList<>();
        int rank = 0;
        for (Candidate chunk : run.chunks()) {
            if (chunk.finalRank() != ++rank) throw new IllegalArgumentException("排名不连续");
            Source source = sources.stream()
                    .filter(s -> s.documentId() == chunk.documentId() && s.versionId() == chunk.indexVersionId())
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("未知文档版本"));
            if (source.path().equals(gold.documentPath())) {
                if (!source.sha256().equals(gold.sourceSha256())) throw new IllegalArgumentException("来源 Hash 不匹配");
                chunks.add(new EvaluationChunk(
                        source.documentId(),
                        source.versionId(),
                        rank,
                        new SourceSpan(chunk.sourceStart(), chunk.sourceEnd())));
            }
        }
        if (chunks.stream()
                        .map(c -> c.documentId() + ":" + c.versionId())
                        .distinct()
                        .count()
                > 1) throw new IllegalArgumentException("同一证据来源存在多个文档版本，不能联合覆盖");
        List<SourceSpan> evidence = new ArrayList<>();
        for (RetrievalRunData.GoldEvidence item : gold.evidence()) {
            evidence.add(new SourceSpan(
                    item.sourceSpan().start().intValue(),
                    item.sourceSpan().end().intValue()));
        }
        return calculateCoverage(chunks, evidence, rank, k);
    }

    // 身份核验和 JSON 转换完成后，连续覆盖只依赖来源区间与排名。
    private static Result calculateCoverage(
            List<EvaluationChunk> chunks, List<SourceSpan> evidenceSpans, int rank, int k) {
        int depth = Math.min(k, rank);
        int covered = 0;
        Integer first = null;
        Integer complete = null;
        for (int prefix = 1; prefix <= depth; prefix++) {
            covered = 0;
            for (SourceSpan evidence : evidenceSpans) {
                int cursor = evidence.start();
                int end = evidence.end();
                if (cursor < 0 || end <= cursor) throw new IllegalArgumentException("无效 Evidence 区间");
                List<EvaluationChunk> intervals = new ArrayList<>();
                for (EvaluationChunk chunk : chunks) if (chunk.rank() <= prefix) intervals.add(chunk);
                intervals.sort(Comparator.comparingInt(c -> c.span().start()));
                // 只推进连续覆盖边界；重复区间无额外贡献，空洞不能跨越。
                for (EvaluationChunk chunk : intervals) {
                    int start = chunk.span().start();
                    int stop = chunk.span().end();
                    if (start < 0 || stop <= start) throw new IllegalArgumentException("无效 Chunk 区间");
                    if (start > cursor) break;
                    cursor = Math.max(cursor, stop);
                }
                if (cursor >= end) covered++;
            }
            if (covered > 0 && first == null) first = prefix;
            if (covered == evidenceSpans.size() && complete == null) complete = prefix;
        }
        return new Result(
                SUCCESS, rank == 0 ? EMPTY_RESULT : SUCCESS, depth, covered, evidenceSpans.size(), first, complete);
    }
}
