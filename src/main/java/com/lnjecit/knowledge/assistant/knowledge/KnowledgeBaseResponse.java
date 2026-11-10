package com.lnjecit.knowledge.assistant.knowledge;

import com.lnjecit.knowledge.assistant.infrastructure.persistence.KnowledgeBase;
import java.time.Instant;

/** 业务 ID 保持 Long，由统一 Jackson 配置输出十进制字符串。 */
public record KnowledgeBaseResponse(Long id, String name, String description, Instant createdAt) {
    static KnowledgeBaseResponse from(KnowledgeBase entity) {
        return new KnowledgeBaseResponse(
                entity.getId(), entity.getName(), entity.getDescription(), entity.getCreatedAt());
    }
}
