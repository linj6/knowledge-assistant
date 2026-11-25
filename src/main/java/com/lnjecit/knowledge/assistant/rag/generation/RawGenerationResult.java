package com.lnjecit.knowledge.assistant.rag.generation;

import com.lnjecit.knowledge.assistant.memory.dto.MemoryTrace;

/** 未经解析和引用校验的内部候选，不能作为 API 答案。 */
public record RawGenerationResult(
        String rawText,
        String modelId,
        String promptVersion,
        Usage usage,
        String finishReason,
        long generationElapsedMillis,
        MemoryTrace memoryTrace) {
    public RawGenerationResult(String text, String model, String version, Usage usage, String finish, long elapsed) {
        this(text, model, version, usage, finish, elapsed, null);
    }

    public record Usage(Integer inputTokens, Integer outputTokens, Integer totalTokens) {}

    @Override
    public String toString() {
        return "RawGenerationResult[promptVersion=" + promptVersion + "]";
    }
}
