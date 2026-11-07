package com.lnjecit.knowledge.assistant.common.error;

public enum CommonErrorCode implements ErrorCode {
    INVALID_ARGUMENT("请求参数不合法"),
    METHOD_NOT_ALLOWED("不支持的 HTTP 方法"),
    UNSUPPORTED_MEDIA_TYPE("不支持的媒体类型"),
    INTERNAL_ERROR("服务内部错误");

    private final String message;

    CommonErrorCode(String message) {
        this.message = message;
    }

    @Override
    public String getCode() {
        return name();
    }

    @Override
    public String getMessage() {
        return message;
    }
}
