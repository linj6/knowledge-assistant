package com.lnjecit.knowledge.assistant.infrastructure.ai;

/**
 * 基于 Unicode 码点数的本地 Token 估算器，将固定开销计入基础值后再增加安全余量。
 * 不使用模型专用分词规则；默认 20% 余量未经实际 usage 校准，不保证不会低估。
 */
public final class TokenEstimator {
    // 调用方为消息封装等额外内容预留的固定估算量，非负，先于百分比余量计入。
    private final int fixedOverhead;
    // 在码点数与固定开销之和上追加的余量百分比，范围 0–1000，默认 20。
    private final int marginPercent;

    public TokenEstimator(int fixedOverhead) {
        this(fixedOverhead, 20);
    }

    public TokenEstimator(int fixedOverhead, int marginPercent) {
        if (fixedOverhead < 0 || marginPercent < 0 || marginPercent > 1000)
            throw new IllegalArgumentException("Invalid token estimate parameters");
        this.fixedOverhead = fixedOverhead;
        this.marginPercent = marginPercent;
    }

    /** 本地估算结果，不能作为供应商实际计费 Token 数。
     * @param estimatedTokens 含固定开销与向上取整余量的估算值
     */
    public record Estimate(long estimatedTokens) {
        public boolean exact() {
            return false;
        }
    }

    public Estimate estimate(String text) {
        if (text == null) throw new IllegalArgumentException("Text is required");
        // 使用码点而非 UTF-16 长度；余量向上取整，不宣称为精确计数或严格上界。
        long base = (long) text.codePointCount(0, text.length()) + fixedOverhead;
        return new Estimate(Math.addExact(base, (Math.multiplyExact(base, marginPercent) + 99) / 100));
    }
}
