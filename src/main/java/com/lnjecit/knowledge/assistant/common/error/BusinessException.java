package com.lnjecit.knowledge.assistant.common.error;

import java.util.Objects;

/** 可预期的业务失败，不接受任意异常消息作为客户端文案。 */
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(Objects.requireNonNull(errorCode, "errorCode").getMessage());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
