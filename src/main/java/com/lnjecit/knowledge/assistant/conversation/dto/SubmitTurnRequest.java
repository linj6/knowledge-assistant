package com.lnjecit.knowledge.assistant.conversation.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SubmitTurnRequest(@NotNull UUID turnId, String question) {}
