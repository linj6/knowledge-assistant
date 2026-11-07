package com.lnjecit.knowledge.assistant.infrastructure.ai;

public final class TokenEstimator {
    private final int fixedOverhead;
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
