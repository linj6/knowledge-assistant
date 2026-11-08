package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.lnjecit.knowledge.assistant.infrastructure.web.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

class ApiBaselineTests {
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @RestController
    static class TestController {
        record Input(@NotBlank String name) {}

        @PostMapping(value = "/test", consumes = "application/json")
        Object post(@Valid @RequestBody Input input) {
            return input;
        }

        @GetMapping("/fail")
        void fail() {
            throw new IllegalStateException("SECRET_TEST_PASSWORD");
        }
    }

    @Test
    void generatedAndCleaned() throws Exception {
        String id = mvc.perform(get("/fail"))
                .andExpect(status().isInternalServerError())
                .andReturn()
                .getResponse()
                .getHeader("X-Request-Id");
        assertThat(id).matches("[a-f0-9-]{36}");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void acceptedAndCorrelated() throws Exception {
        mvc.perform(get("/fail").header("X-Request-Id", "safe-123"))
                .andExpect(header().string("X-Request-Id", "safe-123"))
                .andExpect(jsonPath("$.requestId").value("safe-123"));
    }

    @Test
    void invalidIdsAndNoReuse() throws Exception {
        for (String id : new String[] {"", "bad\r\ninput", "x".repeat(65), "bad space"}) {
            MockHttpServletResponse response = mvc.perform(get("/fail").header("X-Request-Id", id))
                    .andReturn()
                    .getResponse();
            assertThat(response.getHeader("X-Request-Id")).isNotEqualTo(id);
            assertThat(MDC.get("requestId")).isNull();
        }
        acceptedAndCorrelated();
        generatedAndCleaned();
    }

    @Test
    void validation() throws Exception {
        mvc.perform(post("/test").contentType("application/json").content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void malformedJson() throws Exception {
        mvc.perform(post("/test").contentType("application/json").content("{")).andExpect(status().isBadRequest());
    }

    @Test
    void method() throws Exception {
        mvc.perform(get("/test"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(40500));
    }

    @Test
    void media() throws Exception {
        mvc.perform(post("/test").contentType("text/plain").content("secret"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(41500));
    }

    @Test
    void safeError() throws Exception {
        String body = mvc.perform(get("/fail"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(50000))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain("SECRET_TEST_PASSWORD", "IllegalStateException", "stackTrace");
    }
}
