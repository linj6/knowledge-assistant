package com.lnjecit.knowledge.assistant.ingestion.upload;

import com.lnjecit.knowledge.assistant.common.error.ErrorCode;

public enum ImportErrorCode implements ErrorCode {
    KNOWLEDGE_BASE_CAPACITY_EXCEEDED;

    public Integer getCode() {
        return 40010;
    }

    public String getMessage() {
        return "知识库文档数量已达上限";
    }
}
