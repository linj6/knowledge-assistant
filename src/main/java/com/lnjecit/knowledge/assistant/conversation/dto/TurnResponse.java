package com.lnjecit.knowledge.assistant.conversation.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.lnjecit.knowledge.assistant.conversation.entity.Message;
import com.lnjecit.knowledge.assistant.conversation.entity.MessageStatus;
import java.time.Instant;
import java.util.UUID;

public record TurnResponse(
        UUID turnId,
        MessageStatus status,
        String question,
        String answer,
        @JsonRawValue String citations,
        @JsonRawValue String memoryTrace,
        String errorCode,
        Long userMessageId,
        Long assistantMessageId,
        Instant createdAt,
        Instant completedAt) {
    public static TurnResponse from(Message user, Message assistant) {
        return new TurnResponse(
                user.getTurnId(),
                assistant.getStatus(),
                user.getContent(),
                assistant.getContent(),
                assistant.getCitations(),
                assistant.getMemoryTrace(),
                assistant.getErrorCode(),
                user.getId(),
                assistant.getId(),
                user.getCreatedAt(),
                assistant.getCompletedAt());
    }

    public record Accepted(UUID turnId, MessageStatus status) {}
}
