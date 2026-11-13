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

    EmbeddingSnapshotGenerator.Dataset small() throws Exception {
        return EmbeddingSnapshotGenerator.corpus();
    }

    EmbeddingSnapshotGenerator.Budget budget(boolean authorized, int inputs, int requests) {
        return new EmbeddingSnapshotGenerator.Budget(
                authorized, "text-embedding-v4", "fake-key", inputs, requests, true);
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
    void frozenGatesAndDryRun() throws Exception {
        EmbeddingSnapshotGenerator.Dataset data = small();
        OpenAiEmbeddingModel model = fake();
        EmbeddingSnapshotGenerator.Plan plan = EmbeddingSnapshotGenerator.run(
                data, temporary, 10, EmbeddingSnapshotGenerator.Mode.DRY_RUN, null, () -> {
                    throw new AssertionError("NO_FACTORY");
                });
        assertThat(plan.logical()).isEqualTo(153);
        assertThat(plan.unique()).isEqualTo(153);
        assertThat(plan.requests()).isEqualTo(16);
        for (EmbeddingSnapshotGenerator.Budget budget : List.of(
                budget(false, 153, 16),
                new EmbeddingSnapshotGenerator.Budget(true, "text-embedding-v4", "fake-key", 153, 16, false),
                budget(true, 152, 16),
                budget(true, 153, 15),
                budget(true, 154, 16),
                budget(true, 153, 17),
                new EmbeddingSnapshotGenerator.Budget(true, "text-embedding-v4", "", 153, 16, true))) {
            assertThatThrownBy(() -> EmbeddingSnapshotGenerator.run(
                            data,
                            EmbeddingSnapshotGenerator.TARGET,
                            10,
                            EmbeddingSnapshotGenerator.Mode.EXECUTE,
                            budget,
                            () -> {
                                throw new AssertionError("NO_FACTORY");
                            }))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> EmbeddingSnapshotGenerator.run(
                        data,
                        temporary,
                        10,
                        EmbeddingSnapshotGenerator.Mode.EXECUTE,
                        budget(true, 153, 16),
                        () -> model))
                .hasMessage("FIXED_TARGET_REQUIRED");
        Map<EmbeddingSnapshot.Input, String> changed = new LinkedHashMap<>(data.inputs());
        changed.remove(changed.keySet().iterator().next());
        assertThatThrownBy(() -> EmbeddingSnapshotGenerator.run(
                        new EmbeddingSnapshotGenerator.Dataset(data.manifest(), changed),
                        EmbeddingSnapshotGenerator.TARGET,
                        10,
                        EmbeddingSnapshotGenerator.Mode.EXECUTE,
                        budget(true, 153, 16),
                        () -> model))
                .hasMessage("FROZEN_DATASET_MISMATCH");
        EmbeddingSnapshot.Manifest m = data.manifest();
        EmbeddingSnapshot.Manifest wrong = new EmbeddingSnapshot.Manifest(
                m.schemaVersion(),
                m.provider(),
                m.modelId(),
                m.dimensions(),
                m.encoding(),
                "wrong",
                m.sampleFingerprint(),
                m.chunkingFingerprint());
        assertThatThrownBy(() -> EmbeddingSnapshotGenerator.run(
                        new EmbeddingSnapshotGenerator.Dataset(wrong, data.inputs()),
                        EmbeddingSnapshotGenerator.TARGET,
                        10,
                        EmbeddingSnapshotGenerator.Mode.EXECUTE,
                        budget(true, 153, 16),
                        () -> model))
                .hasMessage("FROZEN_DATASET_MISMATCH");
        for (int batch : List.of(0, 11, 9)) {
            assertThatThrownBy(() -> EmbeddingSnapshotGenerator.run(
                            data,
                            EmbeddingSnapshotGenerator.TARGET,
                            batch,
                            EmbeddingSnapshotGenerator.Mode.EXECUTE,
                            budget(true, 153, 16),
                            () -> model))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verify(model, never()).call(any(EmbeddingRequest.class));
    }

    @Test
    void fakeExecuteAndResumeRequireFreshAuthorization() throws Exception {
        Path target = EmbeddingSnapshotGenerator.TARGET;
        // 不覆盖任何已有快照；测试完成后清除自己创建的 Fake 产物。
        assertThat(Files.exists(target)).isFalse();
        EmbeddingSnapshotGenerator.Dataset data = small();
        OpenAiEmbeddingModel first = fake();
        org.mockito.stubbing.Answer<EmbeddingResponse> answer = invocation -> {
            EmbeddingRequest request = invocation.getArgument(0);
            java.util.ArrayList<Embedding> results = new java.util.ArrayList<>();
            for (int i = request.getInstructions().size() - 1; i >= 0; i--)
                results.add(new Embedding(
                        RetrievalExperimentCorpus.synthetic(
                                request.getInstructions().get(i)),
                        i));
            return new EmbeddingResponse(results);
        };
        when(first.call(any(EmbeddingRequest.class)))
                .thenAnswer(answer)
                .thenThrow(new IllegalStateException("fake failure"));
        try {
            assertThatThrownBy(() -> EmbeddingSnapshotGenerator.run(
                            data,
                            target,
                            10,
                            EmbeddingSnapshotGenerator.Mode.EXECUTE,
                            budget(true, 153, 16),
                            () -> first))
                    .hasMessage("BATCH_STOPPED_RECHECK_BUDGET");
            assertThatThrownBy(() -> EmbeddingSnapshot.load(
                            target.resolve("manifest.json"),
                            target.resolve("vectors.jsonl"),
                            data.manifest(),
                            data.inputs()))
                    .hasMessage("SNAPSHOT_MISSING");
            OpenAiEmbeddingModel resumed = fake();
            assertThatThrownBy(() -> EmbeddingSnapshotGenerator.run(
                            data,
                            target,
                            10,
                            EmbeddingSnapshotGenerator.Mode.EXECUTE,
                            budget(false, 153, 16),
                            () -> resumed))
                    .hasMessage("ONLINE_AUTHORIZATION_REQUIRED");
            EmbeddingSnapshotGenerator.Plan plan = EmbeddingSnapshotGenerator.run(
                    data, target, 10, EmbeddingSnapshotGenerator.Mode.EXECUTE, budget(true, 153, 16), () -> resumed);
            assertThat(plan.pending()).isEqualTo(143);
            verify(resumed, times(15)).call(any(EmbeddingRequest.class));
            assertThat(EmbeddingSnapshot.load(
                            target.resolve("manifest.json"),
                            target.resolve("vectors.jsonl"),
                            data.manifest(),
                            data.inputs()))
                    .hasSize(153);
        } finally {
            for (String name : List.of("manifest.json", "vectors.jsonl", "failure.json"))
                Files.deleteIfExists(target.resolve(name));
            Files.deleteIfExists(target);
        }
    }
}
