package com.lnjecit.knowledge.assistant.retrieval.dto;

import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalResult;
import java.util.List;

public record RetrievalSearchResponse(List<RetrievalResult> results) {
    public RetrievalSearchResponse {
        results = List.copyOf(results);
    }
}
