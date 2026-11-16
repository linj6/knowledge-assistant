package com.lnjecit.knowledge.assistant.auth.web;

import com.lnjecit.knowledge.assistant.auth.service.AuthPrincipal;
import com.lnjecit.knowledge.assistant.auth.service.AuthService;
import com.lnjecit.knowledge.assistant.common.RequestIdContext;
import com.lnjecit.knowledge.assistant.common.api.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfLogoutHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {
    private final AuthService service;
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy sessions;

    public AuthController(
            AuthService service, SecurityContextRepository contexts, SessionAuthenticationStrategy sessions) {
        this.service = service;
        this.contexts = contexts;
        this.sessions = sessions;
    }
    // 请求对象不生成 toString，避免意外输出凭据。
    public static final class Credentials {
        public String username;
        public String password;
    }

    @PostMapping("/register")
    public Result<AuthPrincipal> register(@RequestBody Credentials input) {
        return Result.success(service.register(input.username, input.password));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
            @RequestBody Credentials input, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication;
        try {
            authentication = service.login(input.username, input.password);
        } catch (AuthenticationException exception) {
            return ResponseEntity.status(401)
                    .body(new Result<Void>(40100, "用户名或密码无效", null, RequestIdContext.current()));
        }
        // 先轮换 Session 并清除旧 CSRF，再显式保存认证上下文。
        sessions.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return ResponseEntity.ok(Result.success((AuthPrincipal) authentication.getPrincipal()));
    }

    @GetMapping("/me")
    public Result<AuthPrincipal> me(Authentication authentication) {
        return Result.success((AuthPrincipal) authentication.getPrincipal());
    }

    @PostMapping("/logout")
    public Result<Void> logout(
            Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        new CsrfLogoutHandler(new HttpSessionCsrfTokenRepository()).logout(request, response, authentication);
        SecurityContextLogoutHandler logout = new SecurityContextLogoutHandler();
        logout.setSecurityContextRepository(contexts);
        logout.logout(request, response, authentication);
        new CookieClearingLogoutHandler("SESSION").logout(request, response, authentication);
        return Result.success(null);
    }
}
