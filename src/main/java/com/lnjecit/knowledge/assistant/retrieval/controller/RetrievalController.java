package com.lnjecit.knowledge.assistant.retrieval.controller;

import com.lnjecit.knowledge.assistant.auth.service.AuthPrincipal;
import com.lnjecit.knowledge.assistant.common.api.Result;
import com.lnjecit.knowledge.assistant.knowledge.service.KnowledgeBaseService;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalService;
import com.lnjecit.knowledge.assistant.retrieval.dto.RetrievalSearchRequest;
import com.lnjecit.knowledge.assistant.retrieval.dto.RetrievalSearchResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/retrieval")
public class RetrievalController {
    private final RetrievalService service;

    private final KnowledgeBaseService knowledgeBases;

    public RetrievalController(RetrievalService service, KnowledgeBaseService knowledgeBases) {
        this.service = service;
        this.knowledgeBases = knowledgeBases;
    }

    @PostMapping("/search")
    public Result<RetrievalSearchResponse> search(
            @Valid @RequestBody RetrievalSearchRequest request, Authentication authentication) {
        Long knowledgeBaseId = request.knowledgeBaseId();
        // 在进入业务链路前，使用登录身份校验知识库归属。
        knowledgeBases.requireOwned(knowledgeBaseId, AuthPrincipal.requireUserId(authentication));
        return Result.success(new RetrievalSearchResponse(
                service.search(knowledgeBaseId, request.query(), request.strategies(), request.resolvedParameters())));
    }
}
