package com.lnjecit.knowledge.assistant.auth.service;

import com.lnjecit.knowledge.assistant.auth.entity.User;
import com.lnjecit.knowledge.assistant.auth.entity.UserStatus;
import com.lnjecit.knowledge.assistant.auth.repository.UserRepository;
import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuthenticationManager manager;

    public AuthService(UserRepository users, PasswordEncoder encoder, AuthenticationManager manager) {
        this.users = users;
        this.encoder = encoder;
        this.manager = manager;
    }

    public AuthPrincipal register(String username, String password) {
        validate(username, password);
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(encoder.encode(password));
        user.setStatus(UserStatus.ACTIVE);
        user.setCreatedAt(Instant.now());
        // 唯一性由数据库保障，包含并发注册，不依赖先查后写。
        try {
            users.insert(user);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(CommonErrorCode.USERNAME_EXISTS);
        }
        return new AuthPrincipal(user.getId(), user.getUsername());
    }

    public Authentication login(String username, String password) {
        validate(username, password);
        Authentication verified =
                manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, password));
        User user = users.findByUsername(verified.getName());
        // 认证结果转换为最小身份，框架 UserDetails 的散列不会进入 Redis。
        return UsernamePasswordAuthenticationToken.authenticated(
                new AuthPrincipal(user.getId(), user.getUsername()), null, verified.getAuthorities());
    }

    private void validate(String username, String password) {
        if (username == null
                || !username.matches("[A-Za-z0-9_]{3,64}")
                || password == null
                || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72
                || password.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT);
        }
    }
}
