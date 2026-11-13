package com.lnjecit.knowledge.assistant.document.controller;

import com.lnjecit.knowledge.assistant.common.api.Result;
import com.lnjecit.knowledge.assistant.document.dto.CitationEvidenceResponse;
import com.lnjecit.knowledge.assistant.document.dto.DocumentListResponse;
import com.lnjecit.knowledge.assistant.document.service.DocumentQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/knowledge-bases/{kbId}/documents")
public class DocumentQueryController {
    private final DocumentQueryService service;

    public DocumentQueryController(DocumentQueryService service) {
        this.service = service;
    }

    @GetMapping
    public Result<DocumentListResponse> list(
            @PathVariable @Positive Long kbId,
            @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize) {
        return Result.success(service.list(kbId, page, pageSize));
    }

    @GetMapping("/{documentId}/versions/{versionId}/chunks/{chunkId}")
    public Result<CitationEvidenceResponse> evidence(
            @PathVariable @Positive Long kbId,
            @PathVariable @Positive Long documentId,
            @PathVariable @Positive Long versionId,
            @PathVariable @Positive Long chunkId) {
        return Result.success(service.evidence(kbId, documentId, versionId, chunkId));
    }
}
