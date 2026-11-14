package com.lnjecit.knowledge.assistant.evaluation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lnjecit.knowledge.assistant.document.mapper.DocumentMapper;
import com.lnjecit.knowledge.assistant.rag.RagService;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedVersionSnapshot;
import com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedVersionSnapshotService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class EvaluationServiceTests {
    @TempDir
    Path root;

    @Test
    void unpublishedKnowledgeBaseProducesReadableNotReadyWithoutModelCalls() throws Exception {
        RagService rag = mock(RagService.class);
        PublishedVersionSnapshotService snapshots = mock(PublishedVersionSnapshotService.class);
        DocumentMapper documents = mock(DocumentMapper.class);
        when(snapshots.read(1L)).thenReturn(PublishedVersionSnapshot.of(1L, List.of()));
        EvaluationService service = new EvaluationService(rag, snapshots, documents, new MockEnvironment(), root);
        EvaluationService.Outcome outcome =
                service.run(new EvaluationService.Request("answer-evaluation-v1", "Q01", 1L, null, null, true));
        assertEquals("NOT_READY", outcome.status());
        assertEquals("KB_NOT_PUBLISHED", outcome.errorCode());
        verifyNoInteractions(rag, documents);
        EvaluationController reader = new EvaluationController(root);
        assertEquals(outcome.runId(), reader.read(outcome.runId()).get("runId"));
        assertEquals(1, reader.list().size());
        Files.writeString(root.resolve(outcome.runId()).resolve("results.json"), "{}");
        assertTrue(reader.list().isEmpty());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"success", "cost", "source", "failure"})
    void httpStubRunPersistsAndReadsActualTrace(String scenario) throws Exception {
        PublishedVersionSnapshotService snapshots = mock(PublishedVersionSnapshotService.class);
        DocumentMapper documents = mock(DocumentMapper.class);
        PublishedVersionSnapshot snapshot = PublishedVersionSnapshot.of(
                1L, List.of(new com.lnjecit.knowledge.assistant.retrieval.snapshot.PublishedDocument(2L, 3L)));
        when(snapshots.read(1L)).thenReturn(snapshot);
        com.lnjecit.knowledge.assistant.document.entity.Document document =
                new com.lnjecit.knowledge.assistant.document.entity.Document();
        document.setKnowledgeBaseId(1L);
        document.setActiveVersionId(3L);
        document.setContentHash("5441d647f5eee2e17870e9be1ff5bca8add8322ed7a2ba7dbe88219caafe85f9");
        if (scenario.equals("source")) document.setContentHash("wrong");
        when(documents.selectById(2L)).thenReturn(document);
        com.lnjecit.knowledge.assistant.retrieval.RetrievalService retrieval =
                mock(com.lnjecit.knowledge.assistant.retrieval.RetrievalService.class);
        com.lnjecit.knowledge.assistant.rag.generation.AnswerGenerator generator =
                mock(com.lnjecit.knowledge.assistant.rag.generation.AnswerGenerator.class);
        com.lnjecit.knowledge.assistant.retrieval.model.RankedChunk chunk =
                new com.lnjecit.knowledge.assistant.retrieval.model.RankedChunk(
                        4L, 2L, 3L, 1L, "证据", 0, 2, "heading", null, null, 1, 1.0, null, null, 1);
        com.lnjecit.knowledge.assistant.retrieval.model.RetrievalResult result =
                new com.lnjecit.knowledge.assistant.retrieval.model.RetrievalResult(
                        List.of(chunk),
                        new com.lnjecit.knowledge.assistant.retrieval.model.RetrievalDiagnostics(
                                com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStrategy.BM25,
                                com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStrategy.BM25,
                                false,
                                com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStatus.SUCCESS,
                                snapshot.snapshotHash(),
                                com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters.defaults(),
                                List.of(),
                                null));
        when(retrieval.search(any(), any(), any(), any())).thenReturn(List.of(result));
        when(generator.generate(any(), any()))
                .thenReturn(new com.lnjecit.knowledge.assistant.rag.generation.RawGenerationResult(
                        "{\"answerable\":false,\"answer\":\"材料不足\",\"citations\":[]}",
                        "stub",
                        "rag-answer-v1",
                        null,
                        "stop",
                        1));
        RagService rag = new RagService(
                retrieval,
                new com.lnjecit.knowledge.assistant.rag.context.ContextBuilder(
                        new com.lnjecit.knowledge.assistant.rag.context.ContextProperties()),
                generator);
        MockEnvironment env = new MockEnvironment()
                .withProperty("assistant.model.enabled", "true")
                .withProperty("assistant.model.online-authorized", "true")
                .withProperty("assistant.model.api-key", "stub")
                .withProperty("assistant.model.chat-model", "stub");
        if (scenario.equals("cost")) env.setProperty("assistant.model.online-authorized", "false");
        if (scenario.equals("failure"))
            when(generator.generate(any(), any())).thenThrow(new IllegalStateException("stub"));
        EvaluationService service = new EvaluationService(rag, snapshots, documents, env, root);
        org.springframework.test.web.servlet.MockMvc mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                                new EvaluationRunController(service), new EvaluationController(root))
                        .build();
        String response = mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/evaluation/runs")
                                .contentType("application/json")
                                .content(
                                        "{\"dataset\":\"answer-evaluation-v1\",\"caseId\":\"Q01\",\"knowledgeBaseId\":1,\"authorizeModelCost\":true}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        tools.jackson.databind.JsonNode outcome =
                tools.jackson.databind.json.JsonMapper.builder().build().readTree(response);
        if (scenario.equals("cost") || scenario.equals("source")) {
            assertEquals("NOT_READY", outcome.path("status").asString(), response);
            verifyNoInteractions(retrieval, generator);
            return;
        }
        assertEquals(
                scenario.equals("failure") ? "FAILED" : "COMPLETED",
                outcome.path("status").asString(),
                response);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/v1/evaluation/runs/" + outcome.path("runId").asString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isOk());
        verify(retrieval, times(1)).search(any(), any(), any(), any());
        org.mockito.ArgumentCaptor<com.lnjecit.knowledge.assistant.rag.context.AssembledContext> context =
                org.mockito.ArgumentCaptor.forClass(com.lnjecit.knowledge.assistant.rag.context.AssembledContext.class);
        verify(generator, times(1)).generate(any(), context.capture());
        assertEquals("证据", context.getValue().selectedEvidence().getFirst().content());
    }
}
