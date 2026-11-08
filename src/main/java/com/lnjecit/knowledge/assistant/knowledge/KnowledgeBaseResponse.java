package com.lnjecit.knowledge.assistant.knowledge;

import com.lnjecit.knowledge.assistant.infrastructure.persistence.KnowledgeBase;
import java.time.Instant;

/** ID 在 DTO 边界转为字符串，避免客户端数值精度丢失。 */
public record KnowledgeBaseResponse(String id, String name, String description, Instant createdAt) {
    static KnowledgeBaseResponse from(KnowledgeBase entity) {
        return new KnowledgeBaseResponse(
                entity.getId().toString(), entity.getName(), entity.getDescription(), entity.getCreatedAt());
    }
}
