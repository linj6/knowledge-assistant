package com.lnjecit.knowledge.assistant.rag.controller;

import com.lnjecit.knowledge.assistant.auth.service.AuthPrincipal;
import com.lnjecit.knowledge.assistant.common.api.Result;
import com.lnjecit.knowledge.assistant.knowledge.service.KnowledgeBaseService;
import com.lnjecit.knowledge.assistant.rag.RagService;
import com.lnjecit.knowledge.assistant.rag.dto.RagAnswerRequest;
import com.lnjecit.knowledge.assistant.rag.model.RagAnswer;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/chat")
public class RagController {
    private final RagService service;

    private final KnowledgeBaseService knowledgeBases;

    public RagController(RagService service, KnowledgeBaseService knowledgeBases) {
        this.service = service;
        this.knowledgeBases = knowledgeBases;
    }

    @PostMapping("/answers")
    public Result<RagAnswer> answer(@Valid @RequestBody RagAnswerRequest request, Authentication authentication) {
        Long knowledgeBaseId = request.knowledgeBaseId();
        // 在进入业务链路前，使用登录身份校验知识库归属。
        knowledgeBases.requireOwned(knowledgeBaseId, AuthPrincipal.requireUserId(authentication));
        return Result.success(
                service.answer(knowledgeBaseId, request.question(), request.strategy(), request.retrievalParameters()));
    }
}
