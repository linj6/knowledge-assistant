package com.lnjecit.knowledge.assistant.ingestion.upload;

import com.lnjecit.knowledge.assistant.common.api.Result;
import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class DocumentImportController {
    private final DocumentImportService service;

    public DocumentImportController(DocumentImportService service) {
        this.service = service;
    }

    @PostMapping(value = "/v1/knowledge-bases/{kbId}/documents:import", consumes = "multipart/form-data")
    public ResponseEntity<Result<ImportTaskResponse>> receive(
            @PathVariable @Positive Long kbId, @RequestParam("file") List<MultipartFile> files) throws Exception {
        if (files.size() != 1) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT);
        }
        DocumentImportService.Outcome outcome = service.receive(kbId, files.getFirst());
        return ResponseEntity.status(outcome.duplicate() ? 200 : 202).body(Result.success(outcome.task()));
    }

    @GetMapping("/v1/import-tasks/{taskId}")
    public Result<ImportTaskResponse> get(@PathVariable @Positive Long taskId) {
        return Result.success(service.get(taskId));
    }
}
