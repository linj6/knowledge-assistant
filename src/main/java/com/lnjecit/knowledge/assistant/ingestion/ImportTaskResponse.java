package com.lnjecit.knowledge.assistant.ingestion;

import com.lnjecit.knowledge.assistant.infrastructure.persistence.ImportTask;
import com.lnjecit.knowledge.assistant.infrastructure.persistence.ImportTaskStatus;
import java.time.Instant;

public record ImportTaskResponse(
        Long importTaskId, Long documentId, String status, Instant createdAt, Instant updatedAt, String failureCode) {
    public static ImportTaskResponse from(ImportTask task) {
        String status =
                switch (task.getStatus()) {
                    case PENDING -> "RECEIVED";
                    case RUNNING -> "PROCESSING";
                    case SUCCEEDED -> "READY";
                    case FAILED -> "FAILED";
                };
        // 不暴露数据库中的自由文本诊断，避免后续供应商原文泄露。
        return new ImportTaskResponse(
                task.getId(),
                task.getDocumentId(),
                status,
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getStatus() == ImportTaskStatus.FAILED ? "IMPORT_FAILED" : null);
    }
}
