package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;

class EmbeddingSnapshotGeneratorTests {
    @TempDir
    Path temporary;

    EmbeddingSnapshotGenerator.Dataset small() {
        Map<EmbeddingSnapshot.Input, String> inputs = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            String text = i == 2 ? "a" : i == 0 ? "a" : "b";
            inputs.put(
                    new EmbeddingSnapshot.Input(
                            "QUERY", "Q" + i, null, null, null, null, null, EmbeddingSnapshot.sha(text)),
                    text);
        }
        return new EmbeddingSnapshotGenerator.Dataset(
                new EmbeddingSnapshot.Manifest(
                        1, "fake", "text-embedding-v4", 1024, "FLOAT", "config", "samples", "chunks"),
                inputs);
    }

    EmbeddingSnapshotGenerator.Budget budget(boolean authorized, int inputs, int requests) {
        return new EmbeddingSnapshotGenerator.Budget(
                authorized,
                "text-embedding-v4",
                "fake-key",
                inputs,
                requests,
                2,
                Map.of(EmbeddingSnapshot.sha("a"), 1L, EmbeddingSnapshot.sha("b"), 1L));
    }

    OpenAiEmbeddingModel fake() {
        OpenAiEmbeddingModel model = mock(OpenAiEmbeddingModel.class);
        when(model.getOptions())
                .thenReturn(OpenAiEmbeddingOptions.builder()
                        .model("text-embedding-v4")
                        .dimensions(1024)
                        .maxRetries(0)
                        .encodingFormat(OpenAiEmbeddingOptions.EncodingFormat.FLOAT)
                        .build());
        when(model.call(any(EmbeddingRequest.class))).thenAnswer(invocation -> {
            EmbeddingRequest request = invocation.getArgument(0);
            java.util.ArrayList<Embedding> results = new java.util.ArrayList<>();
            for (int i = request.getInstructions().size() - 1; i >= 0; i--)
                results.add(new Embedding(
                        RetrievalExperimentCorpus.synthetic(
                                request.getInstructions().get(i)),
                        i));
            return new EmbeddingResponse(results);
        });
        return model;
    }

    @Test
    void gatesDryRunAndBudgetBeforeClientCreation() throws Exception {
        OpenAiEmbeddingModel model = fake();
        EmbeddingSnapshotGenerator.Plan plan = EmbeddingSnapshotGenerator.run(
                small(), temporary, 1, EmbeddingSnapshotGenerator.Mode.DRY_RUN, null, () -> model);
        assertThat(plan.unique()).isEqualTo(2);
        assertThat(plan.logical()).isEqualTo(3);
        assertThat(plan.requests()).isEqualTo(2);
        assertThat(Files.exists(temporary.resolve("manifest.json"))).isFalse();
        for (EmbeddingSnapshotGenerator.Budget b : List.of(
                budget(false, 2, 2),
                budget(true, 1, 2),
                budget(true, 2, 1),
                new EmbeddingSnapshotGenerator.Budget(true, "text-embedding-v4", "", 2, 2, 2, Map.of()),
                new EmbeddingSnapshotGenerator.Budget(true, "wrong", "key", 2, 2, 2, Map.of()),
                new EmbeddingSnapshotGenerator.Budget(
                        true,
                        "text-embedding-v4",
                        "key",
                        2,
                        2,
                        1,
                        Map.of(EmbeddingSnapshot.sha("a"), 1L, EmbeddingSnapshot.sha("b"), 1L)))) {
            assertThatThrownBy(() -> EmbeddingSnapshotGenerator.run(
                            small(), temporary, 1, EmbeddingSnapshotGenerator.Mode.EXECUTE, b, () -> model))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verify(model, never()).call(any(EmbeddingRequest.class));
    }

    @Test
    void preservesSuccessResumesAndLoadsOnlyCompleteSnapshot() throws Exception {
        OpenAiEmbeddingModel model = fake();
        when(model.call(any(EmbeddingRequest.class)))
                .thenReturn(new EmbeddingResponse(List.of(new Embedding(RetrievalExperimentCorpus.synthetic("a"), 0))))
                .thenReturn(new EmbeddingResponse(List.of(new Embedding(new float[1024], 0))));
        assertThatThrownBy(() -> EmbeddingSnapshotGenerator.run(
                        small(),
                        temporary,
                        1,
                        EmbeddingSnapshotGenerator.Mode.EXECUTE,
                        budget(true, 2, 2),
                        () -> model))
                .hasMessage("BATCH_STOPPED_RECHECK_BUDGET");
        Path vectors = temporary.resolve("vectors.jsonl");
        assertThat(Files.readAllLines(vectors)).hasSize(2);
        assertThatThrownBy(() -> EmbeddingSnapshot.load(
                        temporary.resolve("manifest.json"), vectors, small().manifest(), small().inputs()))
                .hasMessage("SNAPSHOT_MISSING");
        OpenAiEmbeddingModel resumed = fake();
        EmbeddingSnapshotGenerator.Plan plan = EmbeddingSnapshotGenerator.run(
                small(), temporary, 10, EmbeddingSnapshotGenerator.Mode.EXECUTE, budget(true, 1, 1), () -> resumed);
        assertThat(plan.pending()).isEqualTo(1);
        verify(resumed, times(1)).call(any(EmbeddingRequest.class));
        assertThat(EmbeddingSnapshot.load(
                        temporary.resolve("manifest.json"), vectors, small().manifest(), small().inputs()))
                .hasSize(3);
        EmbeddingSnapshotGenerator.run(
                small(), temporary, 10, EmbeddingSnapshotGenerator.Mode.EXECUTE, budget(true, 0, 0), () -> resumed);
        verify(resumed, times(1)).call(any(EmbeddingRequest.class));
    }

    @Test
    void validatesClientAndResponseIndex() throws Exception {
        OpenAiEmbeddingModel model = fake();
        when(model.getOptions())
                .thenReturn(OpenAiEmbeddingOptions.builder().model("wrong").build());
        assertThatThrownBy(() -> EmbeddingSnapshotGenerator.run(
                        small(),
                        temporary,
                        2,
                        EmbeddingSnapshotGenerator.Mode.EXECUTE,
                        budget(true, 2, 2),
                        () -> model))
                .hasMessage("CLIENT_CONFIGURATION_MISMATCH");
        verify(model, never()).call(any(EmbeddingRequest.class));
        OpenAiEmbeddingModel invalid = fake();
        when(invalid.call(any(EmbeddingRequest.class)))
                .thenReturn(new EmbeddingResponse(List.of(
                        new Embedding(RetrievalExperimentCorpus.synthetic("a"), 0),
                        new Embedding(RetrievalExperimentCorpus.synthetic("b"), 0))));
        assertThatThrownBy(() -> EmbeddingSnapshotGenerator.run(
                        small(),
                        temporary,
                        2,
                        EmbeddingSnapshotGenerator.Mode.EXECUTE,
                        budget(true, 2, 2),
                        () -> invalid))
                .hasMessage("BATCH_STOPPED_RECHECK_BUDGET");
        assertThat(Files.exists(temporary.resolve("vectors.jsonl"))).isFalse();
    }

    @Test
    void mapsReorderedIndicesAndRejectsCorruptCheckpoint() throws Exception {
        OpenAiEmbeddingModel model = fake();
        EmbeddingSnapshotGenerator.run(
                small(), temporary, 2, EmbeddingSnapshotGenerator.Mode.EXECUTE, budget(true, 2, 1), () -> model);
        Map<EmbeddingSnapshot.Input, com.lnjecit.knowledge.assistant.retrieval.embedding.QueryVector> loaded =
                EmbeddingSnapshot.load(
                        temporary.resolve("manifest.json"),
                        temporary.resolve("vectors.jsonl"),
                        small().manifest(),
                        small().inputs());
        small().inputs().forEach((input, text) -> assertThat(loaded.get(input).values())
                .containsExactly(RetrievalExperimentCorpus.synthetic(text)));
        Files.writeString(temporary.resolve("vectors.jsonl"), "invalid JSON");
        assertThatThrownBy(() -> EmbeddingSnapshotGenerator.run(
                        small(),
                        temporary,
                        2,
                        EmbeddingSnapshotGenerator.Mode.EXECUTE,
                        budget(true, 2, 1),
                        () -> model))
                .isInstanceOf(Exception.class);
        verify(model, times(1)).call(any(EmbeddingRequest.class));
    }

    @Test
    void realCorpusDryRun() throws Exception {
        EmbeddingSnapshotGenerator.Dataset corpus = EmbeddingSnapshotGenerator.corpus();
        assertThat(corpus.inputs().keySet().stream()
                        .filter(input -> "QUERY".equals(input.inputType()))
                        .count())
                .isEqualTo(12);
        assertThat(corpus.inputs().keySet().stream()
                        .filter(input -> "CHUNK".equals(input.inputType()))
                        .count())
                .isEqualTo(141);
        Files.createDirectories(Path.of("target/m4-02c1"));
        Files.writeString(
                Path.of("target/m4-02c1/inputs.json"),
                EmbeddingSnapshotGenerator.JSON.writeValueAsString(
                        corpus.inputs().keySet()));
        EmbeddingSnapshotGenerator.run(
                corpus, Path.of("target/m4-02c1/embedding"), 10, EmbeddingSnapshotGenerator.Mode.DRY_RUN, null, () -> {
                    throw new AssertionError("NO_CLIENT_IN_DRY_RUN");
                });
    }
}
