package com.lnjecit.knowledge.assistant.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lnjecit.knowledge.assistant.common.RequestIdContext;
import com.lnjecit.knowledge.assistant.common.api.Result;
import com.lnjecit.knowledge.assistant.infrastructure.web.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdContextTests {
    @Test
    void contextVisibleInResponseAndClearedOnExceptionalExit() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "context-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> new RequestIdFilter().doFilter(request, response, (req, res) -> {
                    assertThat(RequestIdContext.current()).isEqualTo("context-123");
                    assertThat(Result.success("ok").requestId()).isEqualTo("context-123");
                    throw new jakarta.servlet.ServletException("test");
                }))
                .isInstanceOf(jakarta.servlet.ServletException.class);
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("context-123");
        assertThat(RequestIdContext.current()).isNull();
        assertThat(org.slf4j.MDC.get("requestId")).isNull();
    }
}
