package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lnjecit.knowledge.assistant.retrieval.QueryEmbeddingService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;

class QueryEmbeddingTests {
    private OpenAiEmbeddingModel model(String name, int dimensions) {
        OpenAiEmbeddingModel model = mock(OpenAiEmbeddingModel.class);
        when(model.getOptions())
                .thenReturn(OpenAiEmbeddingOptions.builder()
                        .model(name)
                        .dimensions(dimensions)
                        .maxRetries(0)
                        .encodingFormat(OpenAiEmbeddingOptions.EncodingFormat.FLOAT)
                        .build());
        return model;
    }

    @Test
    void successCallsExactlyOnce() {
        OpenAiEmbeddingModel model = model("text-embedding-v4", 1024);
        float[] values = new float[1024];
        values[0] = 1;
        when(model.call(any(EmbeddingRequest.class)))
                .thenReturn(new EmbeddingResponse(List.of(new Embedding(values, 0))));
        assertThat(new QueryEmbeddingService(model).embed("query").values()).hasSize(1024);
        verify(model, times(1)).call(any(EmbeddingRequest.class));
    }

    @Test
    void invalidConfigurationsAndBlankQueryDoNotCall() {
        for (int mode = 0; mode < 3; mode++) {
            OpenAiEmbeddingModel model = model(mode == 0 ? "other" : "text-embedding-v4", mode == 1 ? 512 : 1024);
            String query = mode == 2 ? " " : "query";
            assertThatThrownBy(() -> new QueryEmbeddingService(model).embed(query))
                    .isInstanceOf(RuntimeException.class);
            verify(model, never()).call(any(EmbeddingRequest.class));
        }
    }

    @Test
    void invalidResponsesAndModelFailureAreSafeAndNeverRetried() {
        for (int mode = 0; mode < 8; mode++) {
            OpenAiEmbeddingModel model = model("text-embedding-v4", 1024);
            float[] values = new float[mode == 0 ? 512 : 1024];
            values[0] = mode == 1 ? Float.NaN : mode == 2 ? Float.POSITIVE_INFINITY : 1;
            List<Embedding> results = mode == 3
                    ? List.of()
                    : mode == 4
                            ? List.of(new Embedding(values, 0), new Embedding(values, 0))
                            : List.of(new Embedding(values, mode == 5 ? 1 : 0));
            if (mode == 6) when(model.call(any(EmbeddingRequest.class))).thenThrow(new IllegalStateException("secret"));
            else {
                if (mode == 7) values[0] = 0;
                when(model.call(any(EmbeddingRequest.class))).thenReturn(new EmbeddingResponse(results));
            }
            assertThatThrownBy(() -> new QueryEmbeddingService(model).embed("query"))
                    .hasNoCause()
                    .hasMessageNotContaining("secret");
            verify(model, times(1)).call(any(EmbeddingRequest.class));
        }
    }

    @Test
    void transactionGuardAndFailureClassification() {
        OpenAiEmbeddingModel model = model("text-embedding-v4", 1024);
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> new QueryEmbeddingService(model).embed("q"))
                    .hasMessage("QUERY_EMBEDDING_TRANSACTION_FORBIDDEN");
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(model, never()).call(any(EmbeddingRequest.class));
        when(model.call(any(EmbeddingRequest.class))).thenReturn(new EmbeddingResponse(List.of()));
        assertThatThrownBy(() -> new QueryEmbeddingService(model).embed("q")).hasMessageContaining("INVALID_RESPONSE");
    }
}
