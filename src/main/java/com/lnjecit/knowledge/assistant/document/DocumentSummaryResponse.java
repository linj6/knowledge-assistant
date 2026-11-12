package com.lnjecit.knowledge.assistant.document;

import com.lnjecit.knowledge.assistant.ingestion.upload.ImportTaskResponse;
import java.time.Instant;

public record DocumentSummaryResponse(
        Long documentId,
        String filename,
        Instant createdAt,
        String publicationStatus,
        Long activeVersionId,
        Instant publishedAt,
        ImportTaskResponse importTask) {}
