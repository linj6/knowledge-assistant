package com.lnjecit.knowledge.assistant.retrieval.controller;

import com.lnjecit.knowledge.assistant.common.api.Result;
import com.lnjecit.knowledge.assistant.retrieval.RetrievalService;
import com.lnjecit.knowledge.assistant.retrieval.dto.RetrievalSearchRequest;
import com.lnjecit.knowledge.assistant.retrieval.dto.RetrievalSearchResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/retrieval")
public class RetrievalController {
    private final RetrievalService service;

    public RetrievalController(RetrievalService service) {
        this.service = service;
    }

    @PostMapping("/search")
    public Result<RetrievalSearchResponse> search(@Valid @RequestBody RetrievalSearchRequest request) {
        return Result.success(new RetrievalSearchResponse(service.search(
                request.knowledgeBaseId(), request.query(), request.strategies(), request.resolvedParameters())));
    }
}
