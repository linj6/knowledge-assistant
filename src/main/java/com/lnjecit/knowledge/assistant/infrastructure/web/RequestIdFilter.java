package com.lnjecit.knowledge.assistant.infrastructure.web;

import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String id = request.getHeader(HEADER);
        // 限制客户端输入，禁止空白、换行和其他日志控制字符。
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) id = UUID.randomUUID().toString();
        response.setHeader(HEADER, id);
        MDC.put("requestId", id);
        try { chain.doFilter(request, response); }
        finally { MDC.remove("requestId"); } // 容器线程会复用，所有退出路径都必须清理。
    }
}
