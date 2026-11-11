package com.lnjecit.knowledge.assistant.rag.api;

import com.lnjecit.knowledge.assistant.rag.RagService;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStrategy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record RagAnswerRequest(
        @NotNull @Positive Long knowledgeBaseId,
        @NotBlank @Size(max = RagService.MAX_QUESTION_LENGTH) String question,
        RetrievalStrategy strategy,
        RetrievalParameters retrievalParameters) {}
