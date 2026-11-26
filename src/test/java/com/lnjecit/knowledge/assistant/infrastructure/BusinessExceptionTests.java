package com.lnjecit.knowledge.assistant.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.lnjecit.knowledge.assistant.common.error.*;
import com.lnjecit.knowledge.assistant.infrastructure.web.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.*;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

@ExtendWith(OutputCaptureExtension.class)
class BusinessExceptionTests {
    private MockMvc mvc;

    enum TestCode implements ErrorCode {
        REJECTED;

        public Integer getCode() {
            return 90001;
        }

        public String getMessage() {
            return "测试请求被拒绝";
        }
    }

    @RestController
    static class Controller {
        @GetMapping("/business/{code}")
        void fail(@PathVariable String code) {
            throw new BusinessException(code.equals("custom") ? TestCode.REJECTED : CommonErrorCode.valueOf(code)) {
                @Override
                public String getMessage() {
                    return "SECRET_API_KEY SQL /private/path";
                }
            };
        }
    }

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new Controller())
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void commonStatuses() throws Exception {
        CommonErrorCode[] codes = CommonErrorCode.values();
        assertThat(java.util.Arrays.stream(codes).map(CommonErrorCode::getCode).toList())
                .doesNotHaveDuplicates()
                .doesNotContain(0, 90001);
        for (CommonErrorCode code : codes) {
            int expectedStatus =
                    switch (code) {
                        case KNOWLEDGE_BASE_NOT_FOUND, CONVERSATION_NOT_FOUND, MEMORY_NOT_FOUND, NOT_FOUND -> 404;
                        case USERNAME_EXISTS,
                                TURN_ID_CONFLICT,
                                TURN_IN_PROGRESS,
                                MEMORY_DUPLICATE,
                                KNOWLEDGE_BASE_BUSY,
                                DOCUMENT_BUSY,
                                MEMORY_LIMIT_REACHED -> 409;
                        case INVALID_ARGUMENT -> 400;
                        case METHOD_NOT_ALLOWED -> 405;
                        case UNSUPPORTED_MEDIA_TYPE -> 415;
                        case INTERNAL_ERROR -> 500;
                    };
            mvc.perform(get("/business/" + code.name()))
                    .andExpect(status().is(expectedStatus))
                    .andExpect(jsonPath("$.code").value(code.getCode()))
                    .andExpect(jsonPath("$.message").value(code.getMessage()));
        }
    }

    @Test
    void customCodeAndSafeMessage(CapturedOutput output) throws Exception {
        String body = mvc.perform(get("/business/custom").header("X-Request-Id", "safe-123"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Request-Id", "safe-123"))
                .andExpect(jsonPath("$.requestId").value("safe-123"))
                .andExpect(jsonPath("$.code").value(90001))
                .andExpect(jsonPath("$.message").value("测试请求被拒绝"))
                .andExpect(jsonPath("$.*").isArray())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain("SECRET_API_KEY", "SQL", "/private/path", "stackTrace");
        assertThat(output.getAll()).doesNotContain("SECRET_API_KEY", "/private/path");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void generatedIdIsCorrelated() throws Exception {
        MockHttpServletResponse response =
                mvc.perform(get("/business/custom")).andReturn().getResponse();
        String id = response.getHeader("X-Request-Id");
        assertThat(id).matches("[a-f0-9-]{36}");
        assertThat(response.getContentAsString()).contains(id);
        assertThat(MDC.get("requestId")).isNull();
    }
}
