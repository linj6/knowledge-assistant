package com.lnjecit.knowledge.assistant.knowledge;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lnjecit.knowledge.assistant.auth.service.AuthPrincipal;
import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import com.lnjecit.knowledge.assistant.infrastructure.web.ApiExceptionHandler;
import com.lnjecit.knowledge.assistant.infrastructure.web.ApiJacksonConfiguration;
import com.lnjecit.knowledge.assistant.infrastructure.web.RequestIdFilter;
import com.lnjecit.knowledge.assistant.knowledge.controller.KnowledgeBaseController;
import com.lnjecit.knowledge.assistant.knowledge.entity.KnowledgeBase;
import com.lnjecit.knowledge.assistant.knowledge.service.KnowledgeBaseService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class KnowledgeBaseApiTests {
    private KnowledgeBaseService service;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        service = mock(KnowledgeBaseService.class);
        JsonMapper.Builder builder = JsonMapper.builder();
        new ApiJacksonConfiguration().longAsString().customize(builder);
        mvc = MockMvcBuilders.standaloneSetup(new KnowledgeBaseController(service))
                .defaultRequest(get("/").principal(UsernamePasswordAuthenticationToken.authenticated(
                        new AuthPrincipal(42L, "alice"), null, AuthorityUtils.NO_AUTHORITIES)))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(builder.build()))
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
        when(service.create(42L, "Java", "说明")).thenReturn(entity);
        when(service.requireOwned(entity.getId(), 42L)).thenReturn(entity);
        mvc.perform(post("/v1/knowledge-bases")
                        .contentType("application/json")
                        .content("{\"name\":\" Java \",\"description\":\" 说明 \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.code").isNumber())
                .andExpect(header().string("Location", "/v1/knowledge-bases/9007199254740993"))
                .andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.id").value("9007199254740993"))
                .andExpect(jsonPath("$.data.name").value("Java"))
                .andExpect(jsonPath("$.data.length()").value(6));
        mvc.perform(get("/v1/knowledge-bases/9007199254740993"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").isString());
    }

    @Test
    void normalizedLengthLimitsAndOptionalDescription() throws Exception {
        KnowledgeBase entity = new KnowledgeBase();
        entity.setId(1L);
        entity.setName("a".repeat(100));
        when(service.create(42L, "a".repeat(100), "d".repeat(1000))).thenReturn(entity);
        when(service.create(42L, "a".repeat(100), null)).thenReturn(entity);
        mvc.perform(post("/v1/knowledge-bases")
                        .contentType("application/json")
                        .content("{\"name\":\" " + "a".repeat(100) + " \",\"description\":\" " + "d".repeat(1000)
                                + " \"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/v1/knowledge-bases")
                        .contentType("application/json")
                        .content("{\"name\":\"" + "a".repeat(100) + "\"}"))
                .andExpect(status().isCreated());
        when(service.list(42L, null)).thenReturn(List.of(entity));
        mvc.perform(get("/v1/knowledge-bases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").isString())
                .andExpect(jsonPath("$.data[0].length()").value(6));
    }

    @Test
    void managementEndpointsUseExistingResultProtocol() throws Exception {
        KnowledgeBase entity = new KnowledgeBase();
        entity.setId(1L);
        entity.setName("Java");
        when(service.update(1L, 42L, "Java", null)).thenReturn(entity);
        when(service.list(42L, "Ja%")).thenReturn(List.of(entity));
        mvc.perform(put("/v1/knowledge-bases/1").contentType("application/json").content("{\"name\":\" Java \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Java"))
                .andExpect(jsonPath("$.data.documentCount").value(0));
        mvc.perform(get("/v1/knowledge-bases").param("keyword", "Ja%"))
                .andExpect(jsonPath("$.data[0].id").value("1"));
        org.mockito.Mockito.doThrow(new BusinessException(CommonErrorCode.KNOWLEDGE_BASE_BUSY))
                .when(service)
                .delete(2L, 42L);
        mvc.perform(delete("/v1/knowledge-bases/2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40905));
        mvc.perform(delete("/v1/knowledge-bases/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void invalidFields() throws Exception {
        for (String body : List.of(
                "{}",
                "{\"name\":\"\"}",
                "{\"name\":\"   \"}",
                "{\"name\":\"" + "a".repeat(101) + "\"}",
                "{\"name\":\"a\",\"description\":\"" + "a".repeat(1001) + "\"}")) {
            mvc.perform(post("/v1/knowledge-bases")
                            .contentType("application/json")
                            .content(body)
                            .header("X-Request-Id", "validation-test"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000))
                    .andExpect(jsonPath("$.requestId").value("validation-test"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void invalidIds() throws Exception {
        for (String id : List.of("abc", "-1", "0", "9223372036854775808", "1.5"))
            mvc.perform(get("/v1/knowledge-bases/" + id))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        verifyNoInteractions(service);
    }

    @Test
    void notFound() throws Exception {
        when(service.requireOwned(1L, 42L)).thenThrow(new BusinessException(CommonErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
        mvc.perform(get("/v1/knowledge-bases/1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void emptyList() throws Exception {
        when(service.list(42L, null)).thenReturn(List.of());
        mvc.perform(get("/v1/knowledge-bases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void databaseFailureIsSafe() throws Exception {
        when(service.create(42L, "a", null))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("SECRET SQL"));
        mvc.perform(post("/v1/knowledge-bases").contentType("application/json").content("{\"name\":\"a\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(50000))
                .andExpect(jsonPath("$.message").value("服务内部错误"));
    }
}
