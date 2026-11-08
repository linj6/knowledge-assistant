package com.lnjecit.knowledge.assistant.ingestion;

/** 不可变切分协议；所有长度和位置均以 Unicode 码点计量。 */
public record ChunkingConfig(int version, String strategy, int chunkSize, int chunkOverlap) {
    public ChunkingConfig {
        // 严格拒绝未知协议，避免后续任务以错误参数构建索引。
        if (version != 1
                || !"FIXED_CODE_POINT".equals(strategy)
                || chunkSize <= 0
                || chunkOverlap < 0
                || chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("Invalid chunking configuration");
        }
    }
}
