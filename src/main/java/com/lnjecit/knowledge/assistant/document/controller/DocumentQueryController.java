package com.lnjecit.knowledge.assistant.document.controller;

import com.lnjecit.knowledge.assistant.auth.service.AuthPrincipal;
import com.lnjecit.knowledge.assistant.common.api.Result;
import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import com.lnjecit.knowledge.assistant.document.dto.ChunkPageResponse;
import com.lnjecit.knowledge.assistant.document.dto.CitationEvidenceResponse;
import com.lnjecit.knowledge.assistant.document.dto.DocumentContentResponse;
import com.lnjecit.knowledge.assistant.document.dto.DocumentListResponse;
import com.lnjecit.knowledge.assistant.document.dto.DocumentSummaryResponse;
import com.lnjecit.knowledge.assistant.document.entity.Document;
import com.lnjecit.knowledge.assistant.document.service.DocumentQueryService;
import com.lnjecit.knowledge.assistant.infrastructure.storage.OriginalFileStore;
import com.lnjecit.knowledge.assistant.knowledge.service.KnowledgeBaseService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/knowledge-bases/{kbId}/documents")
public class DocumentQueryController {
    private final KnowledgeBaseService knowledgeBases;
    private final OriginalFileStore store;
    private final DocumentQueryService service;

    public DocumentQueryController(
            DocumentQueryService service, KnowledgeBaseService knowledgeBases, OriginalFileStore store) {
        this.store = store;
        this.knowledgeBases = knowledgeBases;
        this.service = service;
    }

    @GetMapping
    public Result<DocumentListResponse> list(
            @PathVariable @Positive Long kbId,
            @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            Authentication authentication) {
        knowledgeBases.requireOwned(kbId, AuthPrincipal.requireUserId(authentication));
        return Result.success(service.list(kbId, page, pageSize, keyword, status));
    }

    @GetMapping("/{documentId}")
    public Result<DocumentSummaryResponse> detail(
            @PathVariable @Positive Long kbId, @PathVariable @Positive Long documentId, Authentication authentication) {
        return Result.success(service.detail(kbId, documentId, AuthPrincipal.requireUserId(authentication)));
    }

    @GetMapping("/{documentId}/content")
    public Result<DocumentContentResponse> content(
            @PathVariable @Positive Long kbId, @PathVariable @Positive Long documentId, Authentication authentication) {
        Document d = service.ownedDocument(kbId, documentId, AuthPrincipal.requireUserId(authentication));
        // 权限检查事务结束后，再读取有大小上限的原始对象。
        try {
            return Result.success(new DocumentContentResponse(
                    d.getFilename(), new String(store.read(d.getObjectKey()), StandardCharsets.UTF_8)));
        } catch (OriginalFileStore.MissingObjectException ex) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
    }

    @GetMapping("/{documentId}/chunks")
    public Result<ChunkPageResponse> chunks(
            @PathVariable @Positive Long kbId,
            @PathVariable @Positive Long documentId,
            Authentication authentication,
            @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize) {
        return Result.success(
                service.chunks(kbId, documentId, AuthPrincipal.requireUserId(authentication), page, pageSize));
    }

    @DeleteMapping("/{documentId}")
    public Result<Void> delete(
            @PathVariable @Positive Long kbId, @PathVariable @Positive Long documentId, Authentication authentication) {
        service.delete(kbId, documentId, AuthPrincipal.requireUserId(authentication));
        return Result.success(null);
    }

    @GetMapping("/{documentId}/versions/{versionId}/chunks/{chunkId}")
    public Result<CitationEvidenceResponse> evidence(
            @PathVariable @Positive Long kbId,
            @PathVariable @Positive Long documentId,
            @PathVariable @Positive Long versionId,
            @PathVariable @Positive Long chunkId,
            Authentication authentication) {
        knowledgeBases.requireOwned(kbId, AuthPrincipal.requireUserId(authentication));
        return Result.success(service.evidence(kbId, documentId, versionId, chunkId));
    }
}
