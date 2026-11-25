package com.lnjecit.knowledge.assistant.memory.dto;

import com.lnjecit.knowledge.assistant.memory.entity.MemoryType;
import java.util.List;

public record MemoryTrace(int injectedCount, List<Item> memories) {
    public MemoryTrace {
        memories = List.copyOf(memories);
    }

    public static MemoryTrace from(List<RecalledMemory> injected) {
        List<Item> items = injected.stream()
                .map(memory -> new Item(Long.toString(memory.id()), memory.type()))
                .toList();
        return new MemoryTrace(items.size(), items);
    }

    public record Item(String id, MemoryType type) {}
}
