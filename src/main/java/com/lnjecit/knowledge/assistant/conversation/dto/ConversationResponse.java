package com.lnjecit.knowledge.assistant.conversation.dto;

import com.lnjecit.knowledge.assistant.conversation.entity.Conversation;
import java.time.Instant;

public record ConversationResponse(Long id, Long knowledgeBaseId, String title, Instant createdAt, Instant updatedAt) {
    public static ConversationResponse from(Conversation conversation) {
        return new ConversationResponse(
                conversation.getId(),
                conversation.getKnowledgeBaseId(),
                conversation.getTitle(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt());
    }
}
