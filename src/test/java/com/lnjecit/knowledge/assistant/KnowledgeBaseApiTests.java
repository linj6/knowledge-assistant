package com.lnjecit.knowledge.assistant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.KnowledgeBase;
import com.lnjecit.knowledge.assistant.infrastructure.web.ApiExceptionHandler;
import com.lnjecit.knowledge.assistant.infrastructure.web.RequestIdFilter;
import com.lnjecit.knowledge.assistant.knowledge.KnowledgeBaseController;
import com.lnjecit.knowledge.assistant.knowledge.KnowledgeBaseService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class KnowledgeBaseApiTests {
    private KnowledgeBaseService service;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        service = mock(KnowledgeBaseService.class);
        mvc = MockMvcBuilders.standaloneSetup(new KnowledgeBaseController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void createAndDetailExposeOnlyDto() throws Exception {
        KnowledgeBase entity = new KnowledgeBase();
        entity.setId(9007199254740993L);
        entity.setName("Java");
        entity.setDescription("说明");
        when(service.create("Java", "说明")).thenReturn(entity);
        when(service.get(entity.getId())).thenReturn(entity);
        mvc.perform(post("/api/v1/knowledge-bases")
                        .contentType("application/json")
                        .content("{\"name\":\" Java \",\"description\":\" 说明 \"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/knowledge-bases/9007199254740993"))
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.id").value("9007199254740993"))
                .andExpect(jsonPath("$.name").value("Java"))
                .andExpect(jsonPath("$.length()").value(4));
        mvc.perform(get("/api/v1/knowledge-bases/9007199254740993"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString());
    }

    @Test
    void normalizedLengthLimitsAndOptionalDescription() throws Exception {
        KnowledgeBase entity = new KnowledgeBase();
        entity.setId(1L);
        entity.setName("a".repeat(100));
        when(service.create("a".repeat(100), "d".repeat(1000))).thenReturn(entity);
        when(service.create("a".repeat(100), null)).thenReturn(entity);
        mvc.perform(post("/api/v1/knowledge-bases")
                        .contentType("application/json")
                        .content("{\"name\":\" " + "a".repeat(100) + " \",\"description\":\" " + "d".repeat(1000)
                                + " \"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/knowledge-bases")
                        .contentType("application/json")
                        .content("{\"name\":\"" + "a".repeat(100) + "\"}"))
                .andExpect(status().isCreated());
        when(service.list()).thenReturn(List.of(entity));
        mvc.perform(get("/api/v1/knowledge-bases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").isString())
                .andExpect(jsonPath("$[0].length()").value(4));
    }

    @Test
    void invalidFields() throws Exception {
        for (String body : List.of(
                "{}",
                "{\"name\":\"\"}",
                "{\"name\":\"   \"}",
                "{\"name\":\"" + "a".repeat(101) + "\"}",
                "{\"name\":\"a\",\"description\":\"" + "a".repeat(1001) + "\"}")) {
            mvc.perform(post("/api/v1/knowledge-bases")
                            .contentType("application/json")
                            .content(body)
                            .header("X-Request-Id", "validation-test"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                    .andExpect(jsonPath("$.requestId").value("validation-test"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void invalidIds() throws Exception {
        for (String id : List.of("abc", "-1", "0", "+1", "9223372036854775808", "1.5"))
            mvc.perform(get("/api/v1/knowledge-bases/" + id))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        verifyNoInteractions(service);
    }

    @Test
    void notFound() throws Exception {
        when(service.get(1L)).thenThrow(new BusinessException(CommonErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
        mvc.perform(get("/api/v1/knowledge-bases/1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void emptyList() throws Exception {
        when(service.list()).thenReturn(List.of());
        mvc.perform(get("/api/v1/knowledge-bases"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void databaseFailureIsSafe() throws Exception {
        when(service.create("a", null))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("SECRET SQL"));
        mvc.perform(post("/api/v1/knowledge-bases")
                        .contentType("application/json")
                        .content("{\"name\":\"a\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("服务内部错误"));
    }
}
