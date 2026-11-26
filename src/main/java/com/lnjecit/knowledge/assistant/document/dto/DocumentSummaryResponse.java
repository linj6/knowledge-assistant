package com.lnjecit.knowledge.assistant.document.dto;

import com.lnjecit.knowledge.assistant.ingestion.dto.ImportTaskResponse;
import java.time.Instant;

public record DocumentSummaryResponse(
        Long documentId,
        Long knowledgeBaseId,
        tools.jackson.databind.JsonNode chunkingConfig,
        long chunkCount,
        String filename,
        Instant createdAt,
        String publicationStatus,
        Long activeVersionId,
        Instant publishedAt,
        ImportTaskResponse importTask) {}
