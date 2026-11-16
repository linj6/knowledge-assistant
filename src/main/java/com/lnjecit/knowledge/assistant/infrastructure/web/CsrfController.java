package com.lnjecit.knowledge.assistant.infrastructure.web;

import com.lnjecit.knowledge.assistant.common.api.Result;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CsrfController {
    public record TokenResponse(String headerName, String token) {}

    @GetMapping("/v1/auth/csrf")
    public ResponseEntity<Result<TokenResponse>> csrf(CsrfToken token) {
        // 主动解析延迟 Token，保存到 Redis Session；登录或退出后客户端应重新获取。
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Result.success(new TokenResponse(token.getHeaderName(), token.getToken())));
    }
}
