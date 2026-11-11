package com.lnjecit.knowledge.assistant.rag;

import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalDiagnostics;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStatus;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStrategy;
import java.util.List;

public record RagAnswer(
        RagStatus status,
        String answer,
        List<RagCitation> citations,
        RetrievalStrategy requestedStrategy,
        RetrievalStrategy actualStrategy,
        boolean degraded,
        RagError error,
        Diagnostics diagnostics) {
    public RagAnswer {
        citations = List.copyOf(citations);
    }

    public record Diagnostics(
            RetrievalStatus retrievalStatus, RetrievalDiagnostics.Failure retrievalError, String snapshotHash) {}
}
