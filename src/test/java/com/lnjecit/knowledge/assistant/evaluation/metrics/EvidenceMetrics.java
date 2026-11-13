package com.lnjecit.knowledge.assistant.evaluation.metrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** B01 的离线 SourceSpan 计算；调用方提供已核验的文档身份映射。 */
public final class EvidenceMetrics {
    private EvidenceMetrics() {}

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
    private static String exclusion(JsonNode gold) {
        if (gold == null || gold.isMissingNode() || gold.isNull()) return "MISSING_GOLD";
        if (gold.path("answerable").isBoolean() && !gold.path("answerable").asBoolean()) return "UNANSWERABLE";
        String review = gold.path("reviewStatus").asString();
        if (!review.equals("CONFIRMED")) return review.equals("PENDING") ? "PENDING_REVIEW" : "REJECTED_GOLD";
        if (gold.path("evidence").isEmpty()) return "MISSING_GOLD";
        return null;
    }

    public record Case(JsonNode gold, JsonNode run, List<Source> sources) {}

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
            if ("PENDING_REVIEW".equals(exclusion)) pending++;
            Result three = calculate(item.gold(), item.run(), item.sources(), 3);
            Result five = calculate(item.gold(), item.run(), item.sources(), 5);
            switch (five.executionStatus()) {
                case "SUCCESS" -> {
                    success++;
                    if (item.run().path("chunks").isEmpty()) empty++;
                }
                case "FAILED" -> failed++;
                case "MISSING" -> missing++;
                default -> throw new IllegalArgumentException("未知执行状态");
            }
            // 执行成功且 Gold 合格才进入宏平均分母，空结果保留零贡献。
            if (exclusion == null && five.executionStatus().equals("SUCCESS")) {
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
        if (k <= 0) throw new IllegalArgumentException("K 必须为正数");
        String status = run.path("status").asString();
        if (!status.equals("SUCCESS")) {
            if (!status.equals("FAILED") && !status.equals("MISSING")) throw new IllegalArgumentException("未知执行状态");
            return new Result(status, "EXCLUDED", 0, 0, 0, null, null);
        }
        String exclusion = exclusion(gold);
        if (exclusion != null) return new Result(status, exclusion, 0, 0, 0, null, null);
        if (!gold.path("relation").asString().equals("ALL")) throw new IllegalArgumentException("仅支持 ALL");
        List<JsonNode> chunks = new ArrayList<>();
        int rank = 0;
        for (JsonNode chunk : run.path("chunks")) {
            if (chunk.path("finalRank").asInt() != ++rank) throw new IllegalArgumentException("排名不连续");
            Source source = sources.stream()
                    .filter(s -> s.documentId() == chunk.path("documentId").asLong()
                            && s.versionId() == chunk.path("indexVersionId").asLong())
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("未知文档版本"));
            if (source.path().equals(gold.path("documentPath").asString())) {
                if (!source.sha256().equals(gold.path("sourceSha256").asString()))
                    throw new IllegalArgumentException("来源 Hash 不匹配");
                chunks.add(chunk);
            }
        }
        if (chunks.stream()
                        .map(c -> c.path("documentId").asLong() + ":"
                                + c.path("indexVersionId").asLong())
                        .distinct()
                        .count()
                > 1) throw new IllegalArgumentException("同一证据来源存在多个文档版本，不能联合覆盖");
        int depth = Math.min(k, rank);
        int covered = 0;
        Integer first = null;
        Integer complete = null;
        for (int prefix = 1; prefix <= depth; prefix++) {
            covered = 0;
            for (JsonNode evidence : gold.path("evidence")) {
                int cursor = evidence.path("sourceSpan").path("start").asInt();
                int end = evidence.path("sourceSpan").path("end").asInt();
                if (cursor < 0 || end <= cursor) throw new IllegalArgumentException("无效 Evidence 区间");
                List<JsonNode> intervals = new ArrayList<>();
                for (JsonNode chunk : chunks) if (chunk.path("finalRank").asInt() <= prefix) intervals.add(chunk);
                intervals.sort(
                        Comparator.comparingInt(c -> c.path("sourceStart").asInt()));
                // 只推进连续覆盖边界；重复区间无额外贡献，空洞不能跨越。
                for (JsonNode chunk : intervals) {
                    int start = chunk.path("sourceStart").asInt();
                    int stop = chunk.path("sourceEnd").asInt();
                    if (start < 0 || stop <= start) throw new IllegalArgumentException("无效 Chunk 区间");
                    if (start > cursor) break;
                    cursor = Math.max(cursor, stop);
                }
                if (cursor >= end) covered++;
            }
            if (covered > 0 && first == null) first = prefix;
            if (covered == gold.path("evidence").size() && complete == null) complete = prefix;
        }
        return new Result(
                status,
                rank == 0 ? "EMPTY_RESULT" : "SUCCESS",
                depth,
                covered,
                gold.path("evidence").size(),
                first,
                complete);
    }
}
