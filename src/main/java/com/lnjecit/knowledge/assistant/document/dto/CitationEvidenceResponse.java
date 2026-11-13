package com.lnjecit.knowledge.assistant.document.dto;

public record CitationEvidenceResponse(
        Long chunkId,
        Long documentId,
        Long indexVersionId,
        String filename,
        String headingPath,
        String content,
        Integer sourceStart,
        Integer sourceEnd) {}
