package com.lnjecit.knowledge.assistant.conversation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateConversationRequest(@NotNull @Positive Long knowledgeBaseId) {}
