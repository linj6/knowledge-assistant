package com.lnjecit.knowledge.assistant.ingestion.controller;

import com.lnjecit.knowledge.assistant.auth.service.AuthPrincipal;
import com.lnjecit.knowledge.assistant.common.api.Result;
import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingStrategy;
import com.lnjecit.knowledge.assistant.ingestion.dto.ImportTaskResponse;
import com.lnjecit.knowledge.assistant.ingestion.upload.DocumentImportService;
import com.lnjecit.knowledge.assistant.knowledge.service.KnowledgeBaseService;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class DocumentImportController {
    private final KnowledgeBaseService knowledgeBases;
    private final DocumentImportService service;

    public DocumentImportController(DocumentImportService service, KnowledgeBaseService knowledgeBases) {
        this.knowledgeBases = knowledgeBases;
        this.service = service;
    }

    @PostMapping(value = "/v1/knowledge-bases/{kbId}/documents:import", consumes = "multipart/form-data")
    public ResponseEntity<Result<ImportTaskResponse>> receive(
            @PathVariable @Positive Long kbId,
            @RequestParam("file") List<MultipartFile> files,
            @RequestParam(required = false) ChunkingStrategy strategy,
            @RequestParam(required = false) Integer chunkSize,
            @RequestParam(required = false) Integer chunkOverlap,
            Authentication authentication)
            throws Exception {
        // Servlet 可能已经解析 multipart，业务文件读取和写入须在授权通过后执行。
        knowledgeBases.requireOwned(kbId, AuthPrincipal.requireUserId(authentication));
        if (files.size() != 1) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT);
        }
        DocumentImportService.Outcome outcome =
                service.receive(kbId, files.getFirst(), strategy, chunkSize, chunkOverlap);
        return ResponseEntity.status(outcome.duplicate() ? 200 : 202).body(Result.success(outcome.task()));
    }

    @PostMapping("/v1/import-tasks/{taskId}:retry")
    public ResponseEntity<Result<ImportTaskResponse>> retry(
            @PathVariable @Positive Long taskId, Authentication authentication) {
        return ResponseEntity.accepted()
                .body(Result.success(service.retryOwned(taskId, AuthPrincipal.requireUserId(authentication))));
    }

    @GetMapping("/v1/import-tasks/{taskId}")
    public Result<ImportTaskResponse> get(@PathVariable @Positive Long taskId, Authentication authentication) {
        return Result.success(service.getOwned(taskId, AuthPrincipal.requireUserId(authentication)));
    }
}
