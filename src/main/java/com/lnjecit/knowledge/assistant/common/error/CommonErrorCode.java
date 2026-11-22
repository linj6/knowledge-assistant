package com.lnjecit.knowledge.assistant.common.error;

public enum CommonErrorCode implements ErrorCode {
    KNOWLEDGE_BASE_NOT_FOUND(40401, "知识库不存在"),
    CONVERSATION_NOT_FOUND(40402, "会话不存在"),
    USERNAME_EXISTS(40900, "用户名已存在"),
    INVALID_ARGUMENT(40000, "请求参数不合法"),
    METHOD_NOT_ALLOWED(40500, "不支持的 HTTP 方法"),
    UNSUPPORTED_MEDIA_TYPE(41500, "不支持的媒体类型"),
    NOT_FOUND(40400, "资源不存在"),
    INTERNAL_ERROR(50000, "服务内部错误");

    private final Integer code;
    private final String message;

    CommonErrorCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public Integer getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
