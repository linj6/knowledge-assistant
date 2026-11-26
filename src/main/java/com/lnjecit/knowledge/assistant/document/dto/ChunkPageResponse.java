package com.lnjecit.knowledge.assistant.document.dto;

import java.util.List;

public record ChunkPageResponse(List<Item> items, int page, int pageSize, long total) {
    public record Item(
            Long chunkId,
            int chunkOrder,
            String content,
            String headingPath,
            int sourceStart,
            int sourceEnd,
            Long indexVersionId) {}
}
