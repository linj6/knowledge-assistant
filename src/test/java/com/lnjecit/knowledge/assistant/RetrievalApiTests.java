package com.lnjecit.knowledge.assistant;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lnjecit.knowledge.assistant.infrastructure.web.ApiExceptionHandler;
import com.lnjecit.knowledge.assistant.infrastructure.web.ApiJacksonConfiguration;
import com.lnjecit.knowledge.assistant.infrastructure.web.RequestIdFilter;
import com.lnjecit.knowledge.assistant.retrieval.api.RetrievalController;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class RetrievalApiTests {
    RetrievalServiceTests fixture;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        fixture = new RetrievalServiceTests();
        fixture.setup();
        JsonMapper.Builder builder = JsonMapper.builder();
        new ApiJacksonConfiguration().longAsString().customize(builder);
        mvc = MockMvcBuilders.standaloneSetup(new RetrievalController(fixture.service))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(builder.build()))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    String body(String strategies, String parameters) {
        return "{\"knowledgeBaseId\":1,\"query\":\"query\",\"strategies\":" + strategies
                + (parameters == null ? "" : ",\"parameters\":" + parameters) + "}";
    }

    @Test
    void fullDiagnosticsAndStringIds() throws Exception {
        mvc.perform(post("/v1/retrieval/search")
                        .contentType("application/json")
                        .header("X-Request-Id", "retrieval-test")
                        .content(body("[\"VECTOR\",\"BM25\",\"HYBRID_RRF\",\"HYBRID_RERANK\"]", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("retrieval-test"))
                .andExpect(jsonPath("$.data.results.length()").value(4))
                .andExpect(jsonPath("$.data.results[0].chunks[0].chunkId").value("4"))
                .andExpect(jsonPath("$.data.results[0].diagnostics.parameters.vectorTopK")
                        .value(20))
                .andExpect(jsonPath("$.data.results[0].diagnostics.stages[0].phase")
                        .value("EMBEDDING"))
                .andExpect(jsonPath("$.data.query").doesNotExist());
    }

    @Test
    void customParameters() throws Exception {
        mvc.perform(post("/v1/retrieval/search")
                        .contentType("application/json")
                        .content(body(
                                "[\"BM25\"]",
                                "{\"vectorTopK\":2,\"bm25TopK\":3,\"rrfK\":10,\"fusionTopK\":2,\"finalTopK\":1}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].diagnostics.parameters.bm25TopK")
                        .value(3));
        verifyNoInteractions(fixture.embedding);
    }

    @Test
    void invalidRequestsRejectBeforeDependencies() throws Exception {
        for (String request : List.of(
                body("[]", null),
                body("[\"VECTOR\",\"VECTOR\"]", null),
                body("[\"VECTOR\",\"BM25\",\"HYBRID_RRF\",\"HYBRID_RERANK\",\"VECTOR\"]", null),
                body("[null]", null),
                body("[\"UNKNOWN\"]", null),
                body("[\"VECTOR\"]", "{}"),
                body(
                        "[\"VECTOR\"]",
                        "{\"vectorTopK\":1,\"bm25TopK\":20,\"rrfK\":60,\"fusionTopK\":20,\"finalTopK\":2}"),
                body("[\"VECTOR\"]", null).replace("\"query\":\"query\"", "\"query\":\" \""),
                body("[\"VECTOR\"]", null).replace("\"knowledgeBaseId\":1", "\"knowledgeBaseId\":0")))
            mvc.perform(post("/v1/retrieval/search")
                            .contentType("application/json")
                            .content(request))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        verifyNoInteractions(fixture.snapshots, fixture.embedding, fixture.vector, fixture.keyword);
    }

    @Test
    void unknownKb() throws Exception {
        when(fixture.snapshots.read(1L))
                .thenThrow(new com.lnjecit.knowledge.assistant.common.error.BusinessException(
                        com.lnjecit.knowledge.assistant.common.error.CommonErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
        mvc.perform(post("/v1/retrieval/search").contentType("application/json").content(body("[\"VECTOR\"]", null)))
                .andExpect(status().isNotFound());
    }

    @Test
    void failedStrategyStillReturnsSuccessfulEnvelope() throws Exception {
        when(fixture.embedding.embed("query")).thenThrow(new IllegalStateException("SECRET query body key"));
        mvc.perform(post("/v1/retrieval/search")
                        .contentType("application/json")
                        .content(body("[\"VECTOR\",\"BM25\"]", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.results[0].diagnostics.error").value("EMBEDDING_FAILED"))
                .andExpect(jsonPath("$.data.results[0].chunks").isEmpty())
                .andExpect(jsonPath("$.data.results[1].diagnostics.status").value("SUCCESS"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SECRET"))));
    }
}
