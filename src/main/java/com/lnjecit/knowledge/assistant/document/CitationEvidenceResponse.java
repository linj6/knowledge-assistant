package com.lnjecit.knowledge.assistant.document;

public record CitationEvidenceResponse(
        Long chunkId,
        Long documentId,
        Long indexVersionId,
        String filename,
        String headingPath,
        String content,
        Integer sourceStart,
        Integer sourceEnd) {}
