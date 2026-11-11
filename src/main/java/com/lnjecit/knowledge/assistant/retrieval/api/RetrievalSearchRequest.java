package com.lnjecit.knowledge.assistant.retrieval.api;

import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStrategy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record RetrievalSearchRequest(
        @NotNull @Positive Long knowledgeBaseId,
        @NotBlank String query,
        @NotNull @Size(min = 1, max = 4) List<@NotNull RetrievalStrategy> strategies,
        @Valid Parameters parameters) {
    public record Parameters(
            @NotNull Integer vectorTopK,
            @NotNull Integer bm25TopK,
            @NotNull Integer rrfK,
            @NotNull Integer fusionTopK,
            @NotNull Integer finalTopK) {
        RetrievalParameters toModel() {
            try {
                return new RetrievalParameters(vectorTopK, bm25TopK, rrfK, fusionTopK, finalTopK);
            } catch (RuntimeException failure) {
                throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT);
            }
        }
    }

    public RetrievalParameters resolvedParameters() {
        return parameters == null ? RetrievalParameters.defaults() : parameters.toModel();
    }
}
