package com.lnjecit.knowledge.assistant.conversation.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.lnjecit.knowledge.assistant.conversation.entity.Message;
import com.lnjecit.knowledge.assistant.conversation.entity.MessageRole;
import com.lnjecit.knowledge.assistant.conversation.entity.MessageStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MessagePageResponse(int page, int pageSize, boolean hasNext, List<Item> items) {
    public record Item(
            Long id,
            Long conversationId,
            UUID turnId,
            MessageRole role,
            String content,
            MessageStatus status,
            String errorCode,
            @JsonRawValue String citations,
            @JsonRawValue String memoryTrace,
            Instant createdAt,
            Instant completedAt) {
        public static Item from(Message message) {
            return new Item(
                    message.getId(),
                    message.getConversationId(),
                    message.getTurnId(),
                    message.getRole(),
                    message.getContent(),
                    message.getStatus(),
                    message.getErrorCode(),
                    message.getCitations(),
                    message.getMemoryTrace(),
                    message.getCreatedAt(),
                    message.getCompletedAt());
        }
    }
}
