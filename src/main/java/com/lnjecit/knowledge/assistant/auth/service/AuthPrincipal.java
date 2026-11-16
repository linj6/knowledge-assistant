package com.lnjecit.knowledge.assistant.auth.service;

import java.io.Serializable;
import java.security.Principal;

/** Session 中仅保存公开身份，不携带密码或散列。 */
public record AuthPrincipal(Long id, String username) implements Principal, Serializable {
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Override
    public String getName() {
        return username;
    }
}
