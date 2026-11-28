package com.lnjecit.knowledge.assistant.memory.dto;

import com.lnjecit.knowledge.assistant.memory.entity.MemoryType;
import java.util.List;

public record MemoryTrace(int injectedCount, List<Item> memories) {
    public MemoryTrace {
        memories = List.copyOf(memories);
    }

    public static MemoryTrace from(List<RecalledMemory> injected) {
        List<Item> items = injected.stream()
                .map(memory -> new Item(
                        Long.toString(memory.id()),
                        memory.type(),
                        memory.type().isGlobal() ? Scope.GLOBAL : Scope.TOPIC,
                        memory.content()))
                .toList();
        return new MemoryTrace(items.size(), items);
    }

    public enum Scope {
        GLOBAL,
        TOPIC
    }

    // 保存注入当时的正文，历史消息无需读取当前记忆。
    public record Item(String id, MemoryType type, Scope scope, String content) {}
}
