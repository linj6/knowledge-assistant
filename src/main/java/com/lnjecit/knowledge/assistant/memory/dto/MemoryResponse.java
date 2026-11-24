package com.lnjecit.knowledge.assistant.memory.dto;

import com.lnjecit.knowledge.assistant.memory.entity.MemoryType;
import com.lnjecit.knowledge.assistant.memory.entity.UserMemory;
import java.time.Instant;

public record MemoryResponse(
        Long id,
        MemoryType type,
        String content,
        String keywords,
        String sourceType,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {

    public static MemoryResponse from(UserMemory memory) {
        return new MemoryResponse(
                memory.getId(),
                memory.getType(),
                memory.getContent(),
                memory.getKeywords(),
                memory.getSourceType(),
                memory.getEnabled(),
                memory.getCreatedAt(),
                memory.getUpdatedAt());
    }
}
