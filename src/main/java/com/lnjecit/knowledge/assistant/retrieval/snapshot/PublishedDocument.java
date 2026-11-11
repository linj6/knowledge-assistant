package com.lnjecit.knowledge.assistant.retrieval.snapshot;

public record PublishedDocument(Long documentId, Long indexVersionId) {
    public PublishedDocument {
        if (documentId == null || indexVersionId == null) throw new IllegalArgumentException("SNAPSHOT_INVALID_ID");
    }
}
