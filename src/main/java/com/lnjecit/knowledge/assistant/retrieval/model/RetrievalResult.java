package com.lnjecit.knowledge.assistant.retrieval.model;

import java.util.List;
import java.util.Objects;

public record RetrievalResult(List<RankedChunk> chunks, RetrievalDiagnostics diagnostics) {
    public RetrievalResult {
        chunks = List.copyOf(chunks);
        Objects.requireNonNull(diagnostics);
        if ((diagnostics.status() == RetrievalStatus.SUCCESS) == chunks.isEmpty()
                || (diagnostics.status() == RetrievalStatus.FAILED && !chunks.isEmpty()))
            throw new IllegalArgumentException("RETRIEVAL_RESULT_INVALID");
    }
}
