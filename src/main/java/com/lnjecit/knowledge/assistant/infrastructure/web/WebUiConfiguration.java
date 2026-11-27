package com.lnjecit.knowledge.assistant.infrastructure.web;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebUiConfiguration {
    private static final Pattern PAGE = Pattern.compile(
            "/(?:index\\.html|chat(?:/[^/.]+)?|libraries(?:/[^/.]+(?:/documents/[^/.]+)?)?|retrieval|memory|login|register)?/?");

    static boolean isPublicRequest(HttpServletRequest request) {
        // 使用完整应用路径，避免把 /api 内的 Servlet 相对路径当作页面放行。
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return (HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod()))
                && (PAGE.matcher(path).matches() || path.startsWith("/assets/"));
    }

    @Bean
    ResourceHttpRequestHandler webUiResources() {
        ResourceHttpRequestHandler handler = new ResourceHttpRequestHandler();
        handler.setLocations(List.of(new ClassPathResource("static/web/")));
        return handler;
    }

    @Bean
    ServletRegistrationBean<HttpServlet> webUiServlet(ResourceHttpRequestHandler webUiResources) {
        // /api/* 继续由 DispatcherServlet 处理；只有明确的页面路由才读取 Vue 入口。
        return new ServletRegistrationBean<>(
                new HttpServlet() {
                    @Override
                    protected void service(HttpServletRequest request, HttpServletResponse response)
                            throws IOException, ServletException {
                        String path = request.getServletPath();
                        if (PAGE.matcher(path).matches()) {
                            path = "/index.html";
                        } else if (!path.startsWith("/assets/")) {
                            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                            return;
                        }
                        request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, path);
                        try {
                            webUiResources.handleRequest(request, response);
                        } catch (NoResourceFoundException exception) {
                            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                        }
                    }
                },
                "/");
    }
}
