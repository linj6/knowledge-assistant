package com.lnjecit.knowledge.assistant.rag.model;

public record RagCitation(
        String evidenceId, Long chunkId, Long documentId, Long indexVersionId, int sourceStart, int sourceEnd) {}
