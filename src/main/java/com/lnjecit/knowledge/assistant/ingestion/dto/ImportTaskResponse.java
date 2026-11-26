package com.lnjecit.knowledge.assistant.ingestion.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingConfig;
import com.lnjecit.knowledge.assistant.ingestion.task.entity.ImportTask;
import com.lnjecit.knowledge.assistant.ingestion.task.entity.ImportTaskStatus;
import java.time.Instant;

public record ImportTaskResponse(
        Long importTaskId,
        Long documentId,
        String status,
        Instant createdAt,
        Instant updatedAt,
        String failureCode,
        @JsonInclude(JsonInclude.Include.NON_NULL) ChunkingConfig chunkingConfig) {
    public static ImportTaskResponse from(ImportTask task) {
        return from(task, null);
    }

    public static ImportTaskResponse from(ImportTask task, ChunkingConfig chunkingConfig) {
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
                task.getStatus() == ImportTaskStatus.FAILED ? "IMPORT_FAILED" : null,
                chunkingConfig);
    }
}
