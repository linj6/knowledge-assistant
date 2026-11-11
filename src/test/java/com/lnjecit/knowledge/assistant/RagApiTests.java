package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lnjecit.knowledge.assistant.infrastructure.web.ApiExceptionHandler;
import com.lnjecit.knowledge.assistant.infrastructure.web.ApiJacksonConfiguration;
import com.lnjecit.knowledge.assistant.infrastructure.web.RequestIdFilter;
import com.lnjecit.knowledge.assistant.rag.RagCitation;
import com.lnjecit.knowledge.assistant.rag.RagService;
import com.lnjecit.knowledge.assistant.rag.api.RagController;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalService;
import com.lnjecit.knowledge.assistant.retrieval.model.RankedChunk;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalDiagnostics;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalResult;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStatus;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalStrategy;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class RagApiTests {
    RetrievalService retrieval;
    MockMvc mvc;
    JsonMapper mapper;

    @BeforeEach
    void setup() {
        retrieval = mock(RetrievalService.class);
        JsonMapper.Builder builder = JsonMapper.builder();
        new ApiJacksonConfiguration().longAsString().customize(builder);
        mapper = builder.build();
        mvc = MockMvcBuilders.standaloneSetup(new RagController(new RagService(retrieval)))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    void result(RetrievalStatus status) {
        RetrievalDiagnostics d = new RetrievalDiagnostics(
                RetrievalStrategy.HYBRID_RRF,
                status == RetrievalStatus.FAILED ? null : RetrievalStrategy.HYBRID_RRF,
                false,
                status,
                status == RetrievalStatus.FAILED ? null : "snapshot",
                RetrievalParameters.defaults(),
                List.of(),
                status == RetrievalStatus.FAILED ? RetrievalDiagnostics.Failure.EMBEDDING_FAILED : null);
        RankedChunk chunk =
                new RankedChunk(4L, 2L, 3L, 1L, "private content", 0, 15, null, 1, .1, null, null, null, null, null);
        when(retrieval.search(any(), any(), any(), any()))
                .thenReturn(List.of(
                        new RetrievalResult(status == RetrievalStatus.SUCCESS ? List.of(chunk) : List.of(), d)));
    }

    @Test
    void emptyAndDefaults() throws Exception {
        result(RetrievalStatus.EMPTY_RESULT);
        mvc.perform(post("/v1/chat/answers")
                        .contentType("application/json")
                        .content("{\"knowledgeBaseId\":\"9007199254740993\",\"question\":\"问题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.data.requestedStrategy").value("HYBRID_RRF"));
        verify(retrieval)
                .search(9007199254740993L, "问题", List.of(RetrievalStrategy.HYBRID_RRF), RetrievalParameters.defaults());
    }

    @Test
    void failedIsNotInsufficient() throws Exception {
        result(RetrievalStatus.FAILED);
        request()
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.error").value("RETRIEVAL_FAILED"))
                .andExpect(jsonPath("$.data.diagnostics.retrievalError").value("EMBEDDING_FAILED"));
    }

    @Test
    void successCannotPretendAnswered() throws Exception {
        result(RetrievalStatus.SUCCESS);
        request()
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.error").value("GENERATION_NOT_IMPLEMENTED"))
                .andExpect(jsonPath("$.data.citations").isEmpty())
                .andExpect(jsonPath("$.data.answer").isEmpty());
    }

    org.springframework.test.web.servlet.ResultActions request() throws Exception {
        return mvc.perform(post("/v1/chat/answers")
                        .contentType("application/json")
                        .content("{\"knowledgeBaseId\":1,\"question\":\"q\"}"))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"knowledgeBaseId\":0,\"question\":\"q\"}",
                "{\"question\":\"q\"}",
                "{\"knowledgeBaseId\":1,\"question\":\" \"}",
                "{\"knowledgeBaseId\":1,\"question\":\"q\",\"strategy\":\"UNKNOWN\"}",
                "{\"knowledgeBaseId\":1,\"question\":\"q\",\"retrievalParameters\":{}}"
            })
    void invalid(String body) throws Exception {
        mvc.perform(post("/v1/chat/answers").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        verifyNoInteractions(retrieval);
    }

    @Test
    void longCitationIds() {
        String json = mapper.writeValueAsString(
                new RagCitation("E1", 9007199254740993L, 9007199254740994L, 9007199254740995L, 0, 10));
        assertThat(json)
                .contains(
                        "\"chunkId\":\"9007199254740993\"",
                        "\"documentId\":\"9007199254740994\"",
                        "\"indexVersionId\":\"9007199254740995\"");
    }

    @Test
    void rejectsLongQuestionAndInvalidPool() throws Exception {
        String longBody =
                mapper.writeValueAsString(java.util.Map.of("knowledgeBaseId", 1, "question", "q".repeat(4001)));
        mvc.perform(post("/v1/chat/answers").contentType("application/json").content(longBody))
                .andExpect(status().isBadRequest());
        String poolBody = mapper.writeValueAsString(java.util.Map.of(
                "knowledgeBaseId",
                1,
                "question",
                "q",
                "strategy",
                "VECTOR",
                "retrievalParameters",
                java.util.Map.of("vectorTopK", 1, "bm25TopK", 20, "rrfK", 60, "fusionTopK", 20, "finalTopK", 5)));
        mvc.perform(post("/v1/chat/answers").contentType("application/json").content(poolBody))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(retrieval);
    }
}
