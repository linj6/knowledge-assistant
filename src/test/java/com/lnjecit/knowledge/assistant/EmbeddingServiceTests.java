package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lnjecit.knowledge.assistant.document.entity.DocumentChunk;
import com.lnjecit.knowledge.assistant.document.entity.IndexVersion;
import com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingProperties;
import com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingRepository;
import com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;

class EmbeddingServiceTests {
    @Test
    void incompatibleSnapshotsRefuseBeforeCallingModel() {
        for (int scenario = 0; scenario < 3; scenario++) {
            EmbeddingRepository repository = mock(EmbeddingRepository.class);
            OpenAiEmbeddingModel model = mock(OpenAiEmbeddingModel.class);
            when(repository.read(2L, 3L)).thenReturn(List.of());
            IndexVersion version = new IndexVersion();
            version.setEmbeddingModel("text-embedding-v4");
            version.setEmbeddingDimension(scenario == 2 ? 512 : 1024);
            when(repository.version(3L)).thenReturn(version);
            when(model.getOptions())
                    .thenReturn(OpenAiEmbeddingOptions.builder()
                            .model(scenario == 0 ? "other" : "text-embedding-v4")
                            .dimensions(scenario == 1 ? 512 : version.getEmbeddingDimension())
                            .build());
            assertThatThrownBy(() -> new EmbeddingService(model, repository, new EmbeddingProperties()).embed(2L, 3L))
                    .hasMessageContaining(scenario == 2 ? "schema" : "mismatch");
            verify(model, never()).call(any(EmbeddingRequest.class));
        }
    }

    @Test
    void invalidResponsesNeverWrite() {
        for (int mode = 0; mode < 5; mode++) {
            EmbeddingRepository repository = mock(EmbeddingRepository.class);
            OpenAiEmbeddingModel model = mock(OpenAiEmbeddingModel.class);
            when(model.getOptions())
                    .thenReturn(OpenAiEmbeddingOptions.builder()
                            .model("text-embedding-v4")
                            .dimensions(1024)
                            .build());
            IndexVersion version = new IndexVersion();
            version.setEmbeddingModel("text-embedding-v4");
            version.setEmbeddingDimension(1024);
            when(repository.version(3L)).thenReturn(version);
            DocumentChunk chunk = new DocumentChunk();
            chunk.setContent("text");
            when(repository.read(2L, 3L)).thenReturn(List.of(chunk, chunk));
            float[] vector = new float[mode == 2 ? 2 : 1024];
            if (mode == 3) vector[0] = Float.NaN;
            if (mode == 4) vector[0] = Float.POSITIVE_INFINITY;
            List<Embedding> results = mode == 0
                    ? List.of(new Embedding(vector, 0))
                    : List.of(new Embedding(vector, 0), new Embedding(vector, mode == 1 ? 0 : 1));
            when(model.call(any(EmbeddingRequest.class))).thenReturn(new EmbeddingResponse(results));
            assertThatThrownBy(() -> new EmbeddingService(model, repository, new EmbeddingProperties()).embed(2L, 3L))
                    .hasMessageContaining("INVALID_RESPONSE");
            verify(repository, never()).write(anyLong(), anyLong(), anyList(), anyList());
        }
    }

    @Test
    void batchesMapIndicesAndResumeAfterFailure() {
        EmbeddingRepository repository = mock(EmbeddingRepository.class);
        OpenAiEmbeddingModel model = mock(OpenAiEmbeddingModel.class);
        when(model.getOptions())
                .thenReturn(OpenAiEmbeddingOptions.builder()
                        .model("text-embedding-v4")
                        .dimensions(1024)
                        .build());
        IndexVersion version = new IndexVersion();
        version.setEmbeddingModel("text-embedding-v4");
        version.setEmbeddingDimension(1024);
        when(repository.version(3L)).thenReturn(version);
        List<DocumentChunk> chunks = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            DocumentChunk c = new DocumentChunk();
            c.setId((long) i);
            c.setContent("text");
            chunks.add(c);
        }
        when(repository.read(2L, 3L)).thenReturn(chunks);
        doAnswer(call -> {
                    List<DocumentChunk> batch = call.getArgument(2);
                    List<String> vectors = call.getArgument(3);
                    for (int i = 0; i < batch.size(); i++) {
                        assertThat(vectors.get(i)).startsWith("[" + (float) i + ",");
                        batch.get(i).setEmbedding(vectors.get(i));
                    }
                    return null;
                })
                .when(repository)
                .write(eq(2L), eq(3L), anyList(), anyList());
        List<Integer> sizes = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        when(model.call(any(EmbeddingRequest.class))).thenAnswer(call -> {
            EmbeddingRequest request = call.getArgument(0);
            sizes.add(request.getInstructions().size());
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("secret body");
            List<Embedding> results = new ArrayList<>();
            for (int i = request.getInstructions().size() - 1; i >= 0; i--) {
                float[] vector = new float[1024];
                vector[0] = i;
                results.add(new Embedding(vector, i));
            }
            return new EmbeddingResponse(results);
        });
        EmbeddingService service = new EmbeddingService(model, repository, new EmbeddingProperties());
        assertThatThrownBy(() -> service.embed(2L, 3L)).hasMessageNotContaining("secret body");
        assertThat(chunks.subList(0, 10)).allMatch(c -> c.getEmbedding() != null);
        service.embed(2L, 3L);
        assertThat(calls.get()).isEqualTo(4);
        assertThat(sizes).containsExactly(10, 10, 10, 7);
        service.embed(2L, 3L);
        assertThat(calls.get()).isEqualTo(4);
    }

    @Test
    void batchConfigurationIsBounded() {
        EmbeddingProperties properties = new EmbeddingProperties();
        for (int size : new int[] {0, -1, 17, Integer.MAX_VALUE}) {
            properties.setBatchSize(size);
            assertThatThrownBy(properties::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
        }
    }
}
