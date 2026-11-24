package com.lnjecit.knowledge.assistant.memory.dto;

import com.lnjecit.knowledge.assistant.memory.entity.MemoryType;
import jakarta.validation.constraints.NotNull;

public final class MemoryRequest {
    @NotNull
    public MemoryType type;

    public String content;
    public String keywords;

    public static final class Update {
        @NotNull
        public MemoryType type;

        public String content;
        public String keywords;

        @NotNull
        public Boolean enabled;
    }
}
