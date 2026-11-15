package com.lnjecit.knowledge.assistant.ingestion.chunk;

/** 不可变切分协议；所有长度和位置均以 Unicode 码点计量。 */
public record ChunkingConfig(int version, ChunkingStrategy strategy, int chunkSize, int chunkOverlap) {
    public void validateMaxCodePoints(int maxCodePoints) {
        if (maxCodePoints <= 0 || chunkSize > maxCodePoints)
            throw new IllegalArgumentException("Chunk size exceeds configured embedding input limit");
    }

    public ChunkingConfig {
        // 严格拒绝未知协议，避免后续任务以错误参数构建索引。
        if (version != 1
                || strategy != ChunkingStrategy.FIXED_CODE_POINT
                || chunkSize <= 0
                || chunkOverlap < 0
                || chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("Invalid chunking configuration");
        }
    }
}
