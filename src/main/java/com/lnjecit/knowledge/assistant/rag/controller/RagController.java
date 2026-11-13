package com.lnjecit.knowledge.assistant.rag.controller;

import com.lnjecit.knowledge.assistant.common.api.Result;
import com.lnjecit.knowledge.assistant.rag.RagService;
import com.lnjecit.knowledge.assistant.rag.dto.RagAnswerRequest;
import com.lnjecit.knowledge.assistant.rag.model.RagAnswer;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/chat")
public class RagController {
    private final RagService service;

    public RagController(RagService service) {
        this.service = service;
    }

    @PostMapping("/answers")
    public Result<RagAnswer> answer(@Valid @RequestBody RagAnswerRequest request) {
        return Result.success(service.answer(
                request.knowledgeBaseId(), request.question(), request.strategy(), request.retrievalParameters()));
    }
}
