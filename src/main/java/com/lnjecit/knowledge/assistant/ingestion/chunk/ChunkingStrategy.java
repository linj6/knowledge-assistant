package com.lnjecit.knowledge.assistant.ingestion.chunk;

/** 文档导入时使用的切分策略，随索引版本持久化。 */
public enum ChunkingStrategy {
    /** 按 Unicode 码点使用固定长度窗口切分，相邻窗口按配置重叠。 */
    FIXED_CODE_POINT,

    /** 优先保留 Markdown 块结构，仅在拆分超长结构时使用配置的重叠长度。 */
    MARKDOWN_STRUCTURE_AWARE
}
