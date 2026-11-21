package com.lnjecit.knowledge.assistant.knowledge.controller;

import com.lnjecit.knowledge.assistant.auth.service.AuthPrincipal;
import com.lnjecit.knowledge.assistant.common.api.Result;
import com.lnjecit.knowledge.assistant.knowledge.dto.CreateKnowledgeBaseRequest;
import com.lnjecit.knowledge.assistant.knowledge.dto.KnowledgeBaseResponse;
import com.lnjecit.knowledge.assistant.knowledge.service.KnowledgeBaseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/v1/knowledge-bases")
public class KnowledgeBaseController {
    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<KnowledgeBaseResponse> create(
            @Valid @RequestBody CreateKnowledgeBaseRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        KnowledgeBaseResponse response = KnowledgeBaseResponse.from(
                service.create(AuthPrincipal.requireUserId(authentication), request.name(), request.description()));
        // 从当前请求构建资源地址，自动保留公共 servlet 前缀和部署上下文。
        httpResponse.setHeader(
                "Location",
                ServletUriComponentsBuilder.fromRequestUri(httpRequest)
                        .path("/{id}")
                        .buildAndExpand(response.id())
                        .toUri()
                        .getRawPath());
        return Result.success(response);
    }

    @GetMapping
    public Result<List<KnowledgeBaseResponse>> list(Authentication authentication) {
        return Result.success(service.list(AuthPrincipal.requireUserId(authentication)).stream()
                .map(KnowledgeBaseResponse::from)
                .toList());
    }

    @GetMapping("/{id}")
    public Result<KnowledgeBaseResponse> get(@PathVariable @Positive Long id, Authentication authentication) {
        return Result.success(
                KnowledgeBaseResponse.from(service.requireOwned(id, AuthPrincipal.requireUserId(authentication))));
    }
}
