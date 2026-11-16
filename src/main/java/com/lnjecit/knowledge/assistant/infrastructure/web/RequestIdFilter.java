package com.lnjecit.knowledge.assistant.infrastructure.web;

import com.lnjecit.knowledge.assistant.common.RequestIdContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
// 在 Session 和 Security 过滤器之前建立请求标识，提前拒绝也能关联响应。
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";
    private static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterNestedErrorDispatch(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 容器重置错误响应时重新写入同一个标识。
        doFilterInternal(request, response, chain);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = (String) request.getAttribute(ATTRIBUTE);
        if (id == null) id = request.getHeader(HEADER);
        // 限制客户端输入，禁止空白、换行和其他日志控制字符。
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}"))
            id = UUID.randomUUID().toString();
        response.setHeader(HEADER, id);
        request.setAttribute(ATTRIBUTE, id);
        RequestIdContext.set(id);
        try {
            chain.doFilter(request, response);
        } finally {
            RequestIdContext.clear();
        } // 容器线程会复用，所有退出路径都必须清理。
    }
}
