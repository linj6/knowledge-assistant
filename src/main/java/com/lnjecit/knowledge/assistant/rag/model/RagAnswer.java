package com.lnjecit.knowledge.assistant.rag.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lnjecit.knowledge.assistant.memory.dto.MemoryTrace;
import com.lnjecit.knowledge.assistant.rag.generation.RawGenerationResult;
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
        Diagnostics diagnostics,
        @JsonIgnore MemoryTrace memoryTrace) {
    public RagAnswer(
            RagStatus status,
            String answer,
            List<RagCitation> citations,
            RetrievalStrategy requested,
            RetrievalStrategy actual,
            boolean degraded,
            RagError error,
            Diagnostics diagnostics) {
        this(status, answer, citations, requested, actual, degraded, error, diagnostics, null);
    }

    public RagAnswer {
        citations = List.copyOf(citations);
        if (status == null
                || switch (status) {
                    case ANSWERED -> answer == null || answer.isBlank() || citations.isEmpty() || error != null;
                    case INSUFFICIENT_EVIDENCE -> !citations.isEmpty()
                            || error != null
                            || (answer != null && answer.isBlank());
                    case FAILED -> answer != null || !citations.isEmpty() || error == null;
                }) throw new IllegalArgumentException("RAG_ANSWER_STATE_INVALID");
    }

    public record Diagnostics(
            RetrievalStatus retrievalStatus,
            RetrievalDiagnostics.Failure retrievalError,
            String snapshotHash,
            RawGenerationResult.Usage usage) {
        public Diagnostics(RetrievalStatus status, RetrievalDiagnostics.Failure error, String hash) {
            this(status, error, hash, null);
        }
    }
}
