package com.lnjecit.knowledge.assistant.retrieval;

import java.util.Objects;

public record RetrievalContext(
        String requestId,
        String query,
        RetrievalStrategy strategy,
        RetrievalParameters parameters,
        PublishedVersionSnapshot snapshot) {
    public RetrievalContext {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("RETRIEVAL_QUERY_REQUIRED");
        Objects.requireNonNull(snapshot);
        parameters.validate(strategy);
    }
}
