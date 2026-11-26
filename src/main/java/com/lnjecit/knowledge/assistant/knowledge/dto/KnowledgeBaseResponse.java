package com.lnjecit.knowledge.assistant.knowledge.dto;

import com.lnjecit.knowledge.assistant.knowledge.entity.KnowledgeBase;
import java.time.Instant;

/** 业务 ID 保持 Long，由统一 Jackson 配置输出十进制字符串。 */
public record KnowledgeBaseResponse(
        Long id, String name, String description, Instant createdAt, Instant updatedAt, int documentCount) {
    public static KnowledgeBaseResponse from(KnowledgeBase entity) {
        return new KnowledgeBaseResponse(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDocumentCount());
    }
}
