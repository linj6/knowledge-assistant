package com.lnjecit.knowledge.assistant.auth.service;

import java.io.Serializable;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

/** Session 中仅保存公开身份，不携带密码或散列。 */
public record AuthPrincipal(Long id, String username) implements Principal, Serializable {
    public static long requireUserId(Authentication authentication) {
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthPrincipal principal
                && principal.id() != null) {
            return principal.id();
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    @Override
    public String getName() {
        return username;
    }
}
