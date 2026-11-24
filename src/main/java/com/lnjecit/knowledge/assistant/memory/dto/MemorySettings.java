package com.lnjecit.knowledge.assistant.memory.dto;

import jakarta.validation.constraints.NotNull;

public record MemorySettings(@NotNull Boolean memoryEnabled) {}
