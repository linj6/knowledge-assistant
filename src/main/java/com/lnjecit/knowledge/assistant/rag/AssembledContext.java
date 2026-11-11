package com.lnjecit.knowledge.assistant.rag;

import com.lnjecit.knowledge.assistant.infrastructure.ai.TokenEstimator;
import java.util.List;
import java.util.Map;

public record AssembledContext(
        String contextText,
        List<SelectedEvidence> selectedEvidence,
        Map<String, SelectedEvidence> evidenceMap,
        TokenEstimator.Estimate usage,
        int estimatedTokenBudget,
        int maxCharacters,
        int excludedByBudget) {
    public AssembledContext {
        selectedEvidence = List.copyOf(selectedEvidence);
        evidenceMap = Map.copyOf(evidenceMap);
    }

    public int selectedCount() {
        return selectedEvidence.size();
    }
}
