package com.lnjecit.knowledge.assistant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lnjecit.knowledge.assistant.infrastructure.web.ApiExceptionHandler;
import com.lnjecit.knowledge.assistant.infrastructure.web.ApiJacksonConfiguration;
import com.lnjecit.knowledge.assistant.ingestion.DocumentImportController;
import com.lnjecit.knowledge.assistant.ingestion.DocumentImportService;
import com.lnjecit.knowledge.assistant.ingestion.ImportTaskResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class ImportApiTests {
    @Test
    void acceptedDuplicateLookupAndErrors() throws Exception {
        DocumentImportService service = mock(DocumentImportService.class);
        JsonMapper.Builder builder = JsonMapper.builder();
        new ApiJacksonConfiguration().longAsString().customize(builder);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DocumentImportController(service))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(builder.build()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        org.springframework.mock.web.MockMultipartFile file = ImportTests.file("a.md", new byte[] {65});
        ImportTaskResponse response = new ImportTaskResponse(9007199254740993L, 2L, "RECEIVED", null, null, null);
        when(service.receive(1L, file))
                .thenReturn(
                        new DocumentImportService.Outcome(response, false),
                        new DocumentImportService.Outcome(response, true));
        mvc.perform(multipart("/v1/knowledge-bases/1/documents:import").file(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("RECEIVED"))
                .andExpect(jsonPath("$.data.importTaskId").value("9007199254740993"));
        mvc.perform(multipart("/v1/knowledge-bases/1/documents:import").file(file))
                .andExpect(status().isOk());
        when(service.get(9007199254740993L)).thenReturn(response);
        mvc.perform(get("/v1/import-tasks/9007199254740993"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentId").value("2"));
        when(service.get(99L))
                .thenThrow(new com.lnjecit.knowledge.assistant.common.error.BusinessException(
                        com.lnjecit.knowledge.assistant.common.error.CommonErrorCode.NOT_FOUND));
        mvc.perform(get("/v1/import-tasks/99")).andExpect(status().isNotFound());
        mvc.perform(get("/v1/import-tasks/0")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/v1/knowledge-bases/1/documents:import")
                        .file(file)
                        .file(file))
                .andExpect(status().isBadRequest());
    }
}
