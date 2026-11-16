package com.lnjecit.knowledge.assistant.infrastructure.web;

import com.lnjecit.knowledge.assistant.common.RequestIdContext;
import com.lnjecit.knowledge.assistant.common.api.Result;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdRegistration(RequestIdFilter filter) {
        // 显式覆盖所有 URL；Servlet 前缀外的请求也可能被 Security 提前拒绝。
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR, DispatcherType.ASYNC);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper mapper) throws Exception {
        // 路径包含 DispatcherServlet 的 /api 前缀，不以扩展名或宽泛目录放行业务接口。
        PathPatternRequestMatcher.Builder paths =
                PathPatternRequestMatcher.withDefaults().basePath("/api");
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(paths.matcher("/v1/evaluation"), paths.matcher("/v1/evaluation/**"))
                        .denyAll()
                        .requestMatchers(
                                paths.matcher(HttpMethod.GET, "/"),
                                paths.matcher(HttpMethod.GET, "/index.html"),
                                paths.matcher(HttpMethod.GET, "/retrieval-demo.html"),
                                paths.matcher(HttpMethod.GET, "/css/app.css"),
                                paths.matcher(HttpMethod.GET, "/js/*.js"),
                                paths.matcher(HttpMethod.GET, "/v1/auth/csrf"),
                                paths.matcher(HttpMethod.POST, "/v1/auth/register"),
                                paths.matcher(HttpMethod.POST, "/v1/auth/login"))
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                // 保留官方 Session Token 与默认 XOR/BREACH 防护；前端通过专用接口读取。
                .csrf(Customizer.withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                                (request, response, exception) -> writeError(mapper, response, 401, "请先登录"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(mapper, response, 403, "无权访问或 CSRF 校验失败")));
        return http.build();
    }

    private static void writeError(JsonMapper mapper, HttpServletResponse response, int status, String message)
            throws IOException {
        // 不输出异常消息、请求凭据或 Token；请求标识由最外层过滤器统一提供。
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(
                response.getWriter(), new Result<Void>(status * 100, message, null, RequestIdContext.current()));
    }
}
