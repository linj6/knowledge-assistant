package com.lnjecit.knowledge.assistant.evaluation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lnjecit.knowledge.assistant.rag.RagService;
import com.lnjecit.knowledge.assistant.rag.context.ContextBuilder;
import com.lnjecit.knowledge.assistant.rag.generation.AnswerGenerator;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalService;
import com.lnjecit.knowledge.assistant.retrieval.model.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvaluationTraceTests {
    @Test
    void snapshotGuardRetainsSameRetrievalAndBlocksContextAndGeneration() {
        RetrievalService retrieval = mock(RetrievalService.class);
        ContextBuilder contexts = mock(ContextBuilder.class);
        AnswerGenerator generator = mock(AnswerGenerator.class);
        RetrievalParameters parameters = RetrievalParameters.defaults();
        RetrievalResult result = new RetrievalResult(
                List.of(),
                new RetrievalDiagnostics(
                        RetrievalStrategy.BM25,
                        RetrievalStrategy.BM25,
                        false,
                        RetrievalStatus.EMPTY_RESULT,
                        "changed",
                        parameters,
                        List.of(),
                        null));
        when(retrieval.search(1L, "question", List.of(RetrievalStrategy.BM25), parameters))
                .thenReturn(List.of(result));
        RagService service = new RagService(retrieval, contexts, generator);
        RagService.RagExecutionResult trace =
                service.executeWithTrace(1L, "question", RetrievalStrategy.BM25, parameters, actual -> {
                    throw new IllegalStateException("mismatch");
                });
        assertSame(result, trace.retrieval());
        assertNull(trace.context());
        assertNull(trace.answer());
        assertEquals("IllegalStateException", trace.failureType());
        verify(retrieval, times(1)).search(1L, "question", List.of(RetrievalStrategy.BM25), parameters);
        verifyNoInteractions(contexts, generator);
    }
}
